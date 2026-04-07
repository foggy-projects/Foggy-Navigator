import { beforeEach, describe, expect, it, vi } from 'vitest'

const mockGetToken = vi.fn(() => 'test-token')
const mockGetSseBaseUrl = vi.fn(() => '/api/v1')
const mockGetApiBaseUrl = vi.fn(() => '/api/v1')
const mockTransportClose = vi.fn()
let capturedOptions: any = null

const mockCreateFetchSseTransport = vi.fn((opts) => {
  capturedOptions = opts
  return {
    close: mockTransportClose,
  }
})

vi.mock('@/utils/auth', () => ({
  getToken: mockGetToken,
}))

vi.mock('@/utils/config', () => ({
  getSseBaseUrl: mockGetSseBaseUrl,
  getApiBaseUrl: mockGetApiBaseUrl,
}))

vi.mock('@/sse/fetchSseTransport', () => ({
  createFetchSseTransport: mockCreateFetchSseTransport,
}))

vi.mock('@/sse/wxSseTransport', () => ({
  createWxSseTransport: mockCreateFetchSseTransport,
}))

describe('useUnifiedSse', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.clearAllMocks()
    capturedOptions = null
    ;(globalThis.uni.request as any).mockClear()
  })

  it('auto-connects and subscribes when first session listener is added', async () => {
    const { useUnifiedSse } = await import('@/composables/useUnifiedSse')
    const { subscribeSession } = useUnifiedSse()

    const unsubscribe = subscribeSession('session-1', vi.fn())
    await Promise.resolve()

    expect(mockCreateFetchSseTransport).toHaveBeenCalledWith(
      expect.objectContaining({
        url: '/api/v1/sse/unified',
        headers: { Authorization: 'Bearer test-token' },
      }),
    )
    expect(globalThis.uni.request).toHaveBeenCalledWith(expect.objectContaining({
      url: '/api/v1/sse/subscribe',
      method: 'POST',
      data: { sessionIds: ['session-1'] },
    }))

    unsubscribe()
    await Promise.resolve()

    expect(globalThis.uni.request).toHaveBeenCalledWith(expect.objectContaining({
      url: '/api/v1/sse/unsubscribe',
      method: 'POST',
      data: { sessionIds: ['session-1'] },
    }))
  })

  it('routes session and notification events to the right listeners', async () => {
    const { useUnifiedSse } = await import('@/composables/useUnifiedSse')
    const { subscribeSession, addNotificationListener, connected } = useUnifiedSse()

    const sessionListener = vi.fn()
    const notificationListener = vi.fn()
    subscribeSession('session-1', sessionListener)
    addNotificationListener(notificationListener)

    capturedOptions.onEvent('message', JSON.stringify({ type: 'connected' }))
    await Promise.resolve()

    expect(connected.value).toBe(true)
    expect(globalThis.uni.request).toHaveBeenLastCalledWith(expect.objectContaining({
      url: '/api/v1/sse/subscribe',
      data: { sessionIds: ['session-1'] },
    }))

    capturedOptions.onEvent('session_event', JSON.stringify({ sessionId: 'session-1', type: 'TEXT_COMPLETE' }))
    expect(sessionListener).toHaveBeenCalledWith({ sessionId: 'session-1', type: 'TEXT_COMPLETE' })

    capturedOptions.onEvent('task_update', JSON.stringify({ sessionId: 'session-1', status: 'RUNNING' }))
    expect(notificationListener).toHaveBeenCalledWith('task_update', { sessionId: 'session-1', status: 'RUNNING' })
  })

  it('disconnect closes transport and resets connected state', async () => {
    const { useUnifiedSse } = await import('@/composables/useUnifiedSse')
    const { connect, disconnect, connected } = useUnifiedSse()

    connect()
    capturedOptions.onEvent('message', JSON.stringify({ type: 'connected' }))
    expect(connected.value).toBe(true)

    disconnect()

    expect(mockTransportClose).toHaveBeenCalled()
    expect(connected.value).toBe(false)
  })
})
