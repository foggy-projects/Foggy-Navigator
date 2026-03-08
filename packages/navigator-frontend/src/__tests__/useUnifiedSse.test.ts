import { describe, it, expect, vi, beforeEach } from 'vitest'

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
    global.fetch = vi.fn().mockResolvedValue({ ok: false })

    const { useUnifiedSse } = await import('@/composables/useUnifiedSse')
    const { subscribeSession } = useUnifiedSse()

    const callback = vi.fn()
    const unsubscribe = subscribeSession('session-1', callback)

    expect(unsubscribe).toBeInstanceOf(Function)

    // POST /subscribe should be called
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/v1/sse/subscribe',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ sessionIds: ['session-1'] }),
      }),
    )
  })

  it('unsubscribe calls POST /unsubscribe when last listener removed', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: false })

    const { useUnifiedSse } = await import('@/composables/useUnifiedSse')
    const { subscribeSession } = useUnifiedSse()

    const callback = vi.fn()
    const unsubscribe = subscribeSession('session-1', callback)

    vi.clearAllMocks()
    global.fetch = vi.fn().mockResolvedValue({ ok: true })

    unsubscribe()

    expect(global.fetch).toHaveBeenCalledWith(
      '/api/v1/sse/unsubscribe',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ sessionIds: ['session-1'] }),
      }),
    )
  })

  it('multiple listeners on same session: unsubscribe only when last removed', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: true })

    const { useUnifiedSse } = await import('@/composables/useUnifiedSse')
    const { subscribeSession } = useUnifiedSse()

    const cb1 = vi.fn()
    const cb2 = vi.fn()
    const unsub1 = subscribeSession('session-1', cb1)
    const unsub2 = subscribeSession('session-1', cb2)

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
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/v1/sse/unsubscribe',
      expect.objectContaining({
        method: 'POST',
      }),
    )
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
