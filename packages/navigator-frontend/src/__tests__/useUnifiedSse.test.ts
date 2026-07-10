import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('@/utils/auth', () => ({
  getToken: vi.fn(() => 'test-token'),
}))

// We test internal routing logic by importing and calling the composable
// Note: actual SSE connections (fetch) are not tested here — those are integration tests.

describe('useUnifiedSse', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // Reset module state between tests
    vi.resetModules()
  })

  it('exports composable with correct API', async () => {
    const { useUnifiedSse } = await import('@/composables/useUnifiedSse')
    const api = useUnifiedSse()

    expect(api.connected).toBeDefined()
    expect(api.connect).toBeInstanceOf(Function)
    expect(api.disconnect).toBeInstanceOf(Function)
    expect(api.subscribeSession).toBeInstanceOf(Function)
    expect(api.addNotificationListener).toBeInstanceOf(Function)
  })

  it('subscribeSession returns unsubscribe function', async () => {
    // Mock fetch to prevent actual connection
    global.fetch = vi.fn().mockResolvedValue({ ok: true, status: 200 })

    const { useUnifiedSse } = await import('@/composables/useUnifiedSse')
    const { subscribeSession } = useUnifiedSse()

    const callback = vi.fn()
    const unsubscribe = subscribeSession('session-1', callback)

    expect(unsubscribe).toBeInstanceOf(Function)

    // POST /subscribe should be called
    await vi.waitFor(() => {
      expect(global.fetch).toHaveBeenCalledWith(
        '/api/v1/sse/subscribe',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ sessionIds: ['session-1'] }),
        }),
      )
    })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('notifies a listener only after the backend confirms session routing', async () => {
    let resolveSubscribe!: (value: { ok: boolean; status: number }) => void
    global.fetch = vi.fn().mockReturnValue(new Promise((resolve) => {
      resolveSubscribe = resolve
    }))

    const { useUnifiedSse } = await import('@/composables/useUnifiedSse')
    const { subscribeSession } = useUnifiedSse()
    const onSubscribed = vi.fn()
    const unsubscribe = subscribeSession('session-ready', vi.fn(), { onSubscribed })

    expect(onSubscribed).not.toHaveBeenCalled()
    resolveSubscribe({ ok: true, status: 200 })
    await vi.waitFor(() => expect(onSubscribed).toHaveBeenCalledTimes(1))
    unsubscribe()
  })

  it('retries a transient subscribe failure with bounded per-session backoff', async () => {
    vi.useFakeTimers()
    let subscribeAttempts = 0
    global.fetch = vi.fn().mockImplementation((url: string) => {
      if (url === '/api/v1/sse/subscribe') {
        subscribeAttempts++
        return Promise.resolve({ ok: subscribeAttempts >= 3, status: subscribeAttempts >= 3 ? 200 : 503 })
      }
      return Promise.resolve({ ok: true, status: 200 })
    })

    const { useUnifiedSse } = await import('@/composables/useUnifiedSse')
    const { subscribeSession } = useUnifiedSse()
    const onSubscribed = vi.fn()
    const unsubscribe = subscribeSession('session-retry', vi.fn(), { onSubscribed })
    await flushAsyncWork()

    expect(subscribeAttempts).toBe(1)
    expect(onSubscribed).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(999)
    expect(subscribeAttempts).toBe(1)
    await vi.advanceTimersByTimeAsync(1)
    await flushAsyncWork()
    expect(subscribeAttempts).toBe(2)

    await vi.advanceTimersByTimeAsync(2000)
    await flushAsyncWork()
    expect(subscribeAttempts).toBe(3)
    expect(onSubscribed).toHaveBeenCalledTimes(1)
    unsubscribe()
  })

  it('cancels a pending subscribe retry after unsubscribe or disconnect', async () => {
    vi.useFakeTimers()
    let subscribeAttempts = 0
    global.fetch = vi.fn().mockImplementation((url: string) => {
      if (url === '/api/v1/sse/subscribe') {
        subscribeAttempts++
        return Promise.resolve({ ok: false, status: 503 })
      }
      return Promise.resolve({ ok: true, status: 200 })
    })

    const { useUnifiedSse } = await import('@/composables/useUnifiedSse')
    const api = useUnifiedSse()
    const unsubscribe = api.subscribeSession('session-cancel-retry', vi.fn())
    await flushAsyncWork()
    expect(subscribeAttempts).toBe(1)

    unsubscribe()
    await flushAsyncWork()
    await vi.advanceTimersByTimeAsync(60000)
    expect(subscribeAttempts).toBe(1)

    api.subscribeSession('session-disconnect-retry', vi.fn())
    await flushAsyncWork()
    expect(subscribeAttempts).toBe(2)
    api.disconnect()
    await vi.advanceTimersByTimeAsync(60000)
    expect(subscribeAttempts).toBe(2)
  })

  it('serializes an in-flight unsubscribe before re-subscribing the same session', async () => {
    let subscribeCount = 0
    let resolveUnsubscribe!: (value: { ok: boolean; status: number }) => void
    let resolveSecondSubscribe!: (value: { ok: boolean; status: number }) => void
    global.fetch = vi.fn().mockImplementation((url: string) => {
      if (url === '/api/v1/sse/subscribe') {
        subscribeCount++
        if (subscribeCount === 1) return Promise.resolve({ ok: true, status: 200 })
        return new Promise((resolve) => { resolveSecondSubscribe = resolve })
      }
      if (url === '/api/v1/sse/unsubscribe') {
        return new Promise((resolve) => { resolveUnsubscribe = resolve })
      }
      return Promise.resolve({ ok: true, status: 200 })
    })

    const { useUnifiedSse } = await import('@/composables/useUnifiedSse')
    const { subscribeSession } = useUnifiedSse()
    const firstReady = vi.fn()
    const secondReady = vi.fn()
    const unsubscribeFirst = subscribeSession('session-resume', vi.fn(), { onSubscribed: firstReady })
    await vi.waitFor(() => expect(firstReady).toHaveBeenCalled())

    unsubscribeFirst()
    await vi.waitFor(() => {
      expect(global.fetch).toHaveBeenCalledWith('/api/v1/sse/unsubscribe', expect.anything())
    })
    const unsubscribeSecond = subscribeSession('session-resume', vi.fn(), { onSubscribed: secondReady })
    resolveUnsubscribe({ ok: true, status: 200 })
    await vi.waitFor(() => expect(subscribeCount).toBe(2))
    expect(secondReady).not.toHaveBeenCalled()

    resolveSecondSubscribe({ ok: true, status: 200 })
    await vi.waitFor(() => expect(secondReady).toHaveBeenCalled())
    unsubscribeSecond()
  })

  it('confirms a synchronous same-session resume without redundant backend routing', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: true, status: 200 })

    const { useUnifiedSse } = await import('@/composables/useUnifiedSse')
    const { subscribeSession } = useUnifiedSse()
    const firstReady = vi.fn()
    const secondReady = vi.fn()
    const unsubscribeFirst = subscribeSession('session-sync-resume', vi.fn(), { onSubscribed: firstReady })
    await vi.waitFor(() => expect(firstReady).toHaveBeenCalledTimes(1))
    vi.clearAllMocks()

    unsubscribeFirst()
    const unsubscribeSecond = subscribeSession('session-sync-resume', vi.fn(), { onSubscribed: secondReady })

    await vi.waitFor(() => expect(secondReady).toHaveBeenCalledTimes(1))
    expect(global.fetch).not.toHaveBeenCalled()
    unsubscribeSecond()
  })

  it('confirms the same callback independently for different sessions', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: true, status: 200 })

    const { useUnifiedSse } = await import('@/composables/useUnifiedSse')
    const { subscribeSession } = useUnifiedSse()
    const sharedReady = vi.fn()
    const unsubscribeA = subscribeSession('session-shared-a', vi.fn(), { onSubscribed: sharedReady })
    const unsubscribeB = subscribeSession('session-shared-b', vi.fn(), { onSubscribed: sharedReady })

    await vi.waitFor(() => expect(sharedReady).toHaveBeenCalledTimes(2))
    unsubscribeA()
    unsubscribeB()
  })

  it('does not accept a delayed subscribe response from an older connection epoch', async () => {
    const encoder = new TextEncoder()
    let streamController!: ReadableStreamDefaultController<Uint8Array>
    let subscribeCount = 0
    const subscribeResolvers: Array<(value: { ok: boolean; status: number }) => void> = []
    global.fetch = vi.fn().mockImplementation((url: string) => {
      if (url === '/api/v1/sse/unified') {
        const body = new ReadableStream<Uint8Array>({
          start(controller) {
            streamController = controller
            controller.enqueue(encoder.encode('data: {"type":"connected"}\n\n'))
          },
        })
        return Promise.resolve({ ok: true, body })
      }
      if (url === '/api/v1/sse/subscribe') {
        subscribeCount++
        return new Promise((resolve) => subscribeResolvers.push(resolve))
      }
      return Promise.resolve({ ok: true, status: 200 })
    })

    const { useUnifiedSse } = await import('@/composables/useUnifiedSse')
    const api = useUnifiedSse()
    const onSubscribed = vi.fn()
    const unsubscribe = api.subscribeSession('session-epoch', vi.fn(), { onSubscribed })
    api.connect()
    await vi.waitFor(() => expect(subscribeCount).toBe(1))

    subscribeResolvers[0]?.({ ok: true, status: 200 })
    await vi.waitFor(() => expect(subscribeCount).toBe(2))
    expect(onSubscribed).not.toHaveBeenCalled()

    subscribeResolvers[1]?.({ ok: true, status: 200 })
    await vi.waitFor(() => expect(onSubscribed).toHaveBeenCalledTimes(1))

    unsubscribe()
    api.disconnect()
    streamController.close()
  })

  it('unsubscribe calls POST /unsubscribe when last listener removed', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: true, status: 200 })

    const { useUnifiedSse } = await import('@/composables/useUnifiedSse')
    const { subscribeSession } = useUnifiedSse()

    const callback = vi.fn()
    const unsubscribe = subscribeSession('session-1', callback)

    await vi.waitFor(() => {
      expect(global.fetch).toHaveBeenCalledWith('/api/v1/sse/subscribe', expect.anything())
    })

    vi.clearAllMocks()
    global.fetch = vi.fn().mockResolvedValue({ ok: true })

    unsubscribe()

    await vi.waitFor(() => {
      expect(global.fetch).toHaveBeenCalledWith(
        '/api/v1/sse/unsubscribe',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ sessionIds: ['session-1'] }),
        }),
      )
    })
  })

  it('multiple listeners on same session: unsubscribe only when last removed', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: true })

    const { useUnifiedSse } = await import('@/composables/useUnifiedSse')
    const { subscribeSession } = useUnifiedSse()

    const cb1 = vi.fn()
    const cb2 = vi.fn()
    const unsub1 = subscribeSession('session-1', cb1)
    const unsub2 = subscribeSession('session-1', cb2)

    await vi.waitFor(() => {
      expect(global.fetch).toHaveBeenCalledWith('/api/v1/sse/subscribe', expect.anything())
    })

    vi.clearAllMocks()
    global.fetch = vi.fn().mockResolvedValue({ ok: true })

    // Remove first listener — should NOT call unsubscribe
    unsub1()
    expect(global.fetch).not.toHaveBeenCalledWith(
      '/api/v1/sse/unsubscribe',
      expect.anything(),
    )

    // Remove second (last) listener — SHOULD call unsubscribe
    unsub2()
    await vi.waitFor(() => {
      expect(global.fetch).toHaveBeenCalledWith(
        '/api/v1/sse/unsubscribe',
        expect.objectContaining({
          method: 'POST',
        }),
      )
    })
  })

  it('addNotificationListener returns remove function', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: false })

    const { useUnifiedSse } = await import('@/composables/useUnifiedSse')
    const { addNotificationListener } = useUnifiedSse()

    const callback = vi.fn()
    const remove = addNotificationListener(callback)

    expect(remove).toBeInstanceOf(Function)

    // Should not throw
    remove()
  })

  it('connected starts as false', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: false })

    const { useUnifiedSse } = await import('@/composables/useUnifiedSse')
    const { connected } = useUnifiedSse()

    expect(connected.value).toBe(false)
  })
})

async function flushAsyncWork(): Promise<void> {
  for (let i = 0; i < 6; i++) await Promise.resolve()
}
