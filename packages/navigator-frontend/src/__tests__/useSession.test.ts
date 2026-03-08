import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

// Mock dependencies before imports
vi.mock('@/api/session', () => ({
  getMessages: vi.fn(),
  sendMessage: vi.fn(),
}))

vi.mock('@/utils/auth', () => ({
  getToken: vi.fn(() => 'test-token'),
}))

vi.mock('@/router', () => ({
  default: { push: vi.fn() },
}))

vi.mock('element-plus', () => ({
  ElNotification: vi.fn(),
}))

// Mock useUnifiedSse — capture subscribeSession callback
let capturedSessionCallback: ((msg: any) => void) | null = null
const mockUnsubscribe = vi.fn()
const mockSubscribeSession = vi.fn((sessionId: string, callback: (msg: any) => void) => {
  capturedSessionCallback = callback
  return mockUnsubscribe
})

vi.mock('@/composables/useUnifiedSse', () => ({
  useUnifiedSse: () => ({
    connected: { value: true },
    connect: vi.fn(),
    disconnect: vi.fn(),
    subscribeSession: mockSubscribeSession,
    addNotificationListener: vi.fn(() => vi.fn()),
  }),
}))

import * as sessionApi from '@/api/session'
import router from '@/router'
import { ElNotification } from 'element-plus'
import { useChatStore, AipMessageType } from '@foggy/chat'
import { useSession, setRouteRequestHandler } from '@/composables/useSession'
import type { AgentMessage, RoutePayload } from '@/types'

const mockSessionApi = vi.mocked(sessionApi)
const mockRouter = vi.mocked(router)

describe('useSession', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    capturedSessionCallback = null
    setActivePinia(createPinia())
    setRouteRequestHandler(null)
  })

  afterEach(() => {
    const { disconnectSession } = useSession()
    disconnectSession()
  })

  // ========== connectToSession ==========

  describe('connectToSession', () => {
    it('loads history messages and subscribes to unified SSE', async () => {
      mockSessionApi.getMessages.mockResolvedValue([
        { id: 'm1', sessionId: 's1', role: 'USER', content: 'Hello', createdAt: '2026-01-01T00:00:00' },
        { id: 'm2', sessionId: 's1', role: 'ASSISTANT', content: 'Hi there', createdAt: '2026-01-01T00:01:00' },
      ])

      const { connectToSession, connectedSessionId } = useSession()
      await connectToSession('session-abc')

      expect(connectedSessionId.value).toBe('session-abc')
      expect(mockSessionApi.getMessages).toHaveBeenCalledWith('session-abc')

      // History loaded into chat store
      const store = useChatStore()
      expect(store.messages).toHaveLength(2)
      expect(store.messages[0]!.sender).toBe('user')
      expect(store.messages[1]!.sender).toBe('assistant')

      // subscribeSession called with correct sessionId
      expect(mockSubscribeSession).toHaveBeenCalledWith('session-abc', expect.any(Function))
    })

    it('handles history load failure gracefully', async () => {
      mockSessionApi.getMessages.mockRejectedValue(new Error('Network error'))

      const { connectToSession } = useSession()
      await connectToSession('s1')

      // Should still subscribe to SSE
      expect(mockSubscribeSession).toHaveBeenCalled()
    })

    it('disconnects previous session before connecting', async () => {
      mockSessionApi.getMessages.mockResolvedValue([])

      const { connectToSession } = useSession()
      await connectToSession('s1')
      await connectToSession('s2')

      // First subscription should be unsubscribed
      expect(mockUnsubscribe).toHaveBeenCalled()
    })

    it('discards stale connect when rapid switching', async () => {
      let resolveFirst!: (value: any) => void
      mockSessionApi.getMessages
        .mockReturnValueOnce(new Promise(r => { resolveFirst = r }))  // s1: delayed
        .mockResolvedValueOnce([{ id: 'm2', sessionId: 's2', role: 'USER',
                                  content: 'S2', createdAt: '2026-01-01T00:00:00' }])

      const { connectToSession } = useSession()
      const first = connectToSession('s1')
      const second = connectToSession('s2')

      // Resolve s1 after s2 has already started — should be discarded
      resolveFirst([{ id: 'm1', sessionId: 's1', role: 'USER',
                      content: 'STALE', createdAt: '2026-01-01T00:00:00' }])
      await first
      await second

      const store = useChatStore()
      expect(store.messages).toHaveLength(1)
      expect(store.messages[0]!.content).toBe('S2')
    })

    it('reconstructs structured messages from metadata', async () => {
      mockSessionApi.getMessages.mockResolvedValue([
        {
          id: 'm1',
          sessionId: 's1',
          role: 'ASSISTANT',
          content: 'thinking...',
          createdAt: '2026-01-01T00:00:00',
          metadata: {
            type: 'THINKING',
            content: 'Let me think...',
          },
        },
      ])

      const { connectToSession } = useSession()
      await connectToSession('s1')

      const store = useChatStore()
      expect(store.messages.length).toBeGreaterThanOrEqual(1)
    })
  })

  // ========== SSE event routing ==========

  describe('SSE event routing', () => {
    beforeEach(async () => {
      mockSessionApi.getMessages.mockResolvedValue([])
      const { connectToSession } = useSession()
      await connectToSession('s1')
    })

    it('ROUTE_REQUEST calls routeRequestHandler when set', () => {
      const handler = vi.fn()
      setRouteRequestHandler(handler)

      const routePayload: RoutePayload = {
        action: 'DELEGATE',
        mode: 'REPLACE',
        target: { agentId: 'coding-agent', sessionId: 'child-1' },
      }
      capturedSessionCallback!({
        messageId: 'r1',
        sessionId: 's1',
        agentId: 'tutor-agent',
        timestamp: Date.now(),
        version: '1.0',
        type: 'ROUTE_REQUEST',
        payload: routePayload,
      })

      expect(handler).toHaveBeenCalledWith(routePayload)
    })

    it('ROUTE_REQUEST does nothing when no handler set', () => {
      setRouteRequestHandler(null)

      // Should not throw
      capturedSessionCallback!({
        messageId: 'r1',
        sessionId: 's1',
        agentId: 'tutor-agent',
        timestamp: Date.now(),
        version: '1.0',
        type: 'ROUTE_REQUEST',
        payload: {},
      })
    })

    it('TASK_COMPLETED shows success notification', () => {
      capturedSessionCallback!({
        messageId: 't1',
        sessionId: 's1',
        agentId: 'tutor-agent',
        timestamp: Date.now(),
        version: '1.0',
        type: 'TASK_COMPLETED',
        payload: {
          taskId: 'task-1',
          targetAgentId: 'coding-agent',
          status: 'COMPLETED',
          resultSummary: 'All tests passed',
        },
      })

      expect(ElNotification).toHaveBeenCalledWith(
        expect.objectContaining({
          title: '任务完成',
          type: 'success',
          duration: 6000,
        }),
      )
      const call = vi.mocked(ElNotification).mock.calls[0]![0] as Record<string, unknown>
      expect(call.message).toContain('coding-agent')
      expect(call.message).toContain('All tests passed')
    })

    it('TASK_COMPLETED shows error notification for failed task', () => {
      capturedSessionCallback!({
        messageId: 't2',
        sessionId: 's1',
        agentId: 'tutor-agent',
        timestamp: Date.now(),
        version: '1.0',
        type: 'TASK_COMPLETED',
        payload: { status: 'FAILED', taskType: 'CLAUDE_WORKER' },
      })

      expect(ElNotification).toHaveBeenCalledWith(
        expect.objectContaining({
          title: '任务失败',
          type: 'error',
        }),
      )
    })

    it('TASK_COMPLETED onClick navigates to /tasks', () => {
      capturedSessionCallback!({
        messageId: 't3',
        sessionId: 's1',
        agentId: 'tutor-agent',
        timestamp: Date.now(),
        version: '1.0',
        type: 'TASK_COMPLETED',
        payload: { status: 'COMPLETED' },
      })

      const call = vi.mocked(ElNotification).mock.calls[0]![0] as Record<string, unknown>
      const onClick = call.onClick as () => void
      onClick()
      expect(mockRouter.push).toHaveBeenCalledWith('/tasks')
    })
  })

  // ========== sendMessage ==========

  describe('sendMessage', () => {
    it('adds user message to store and sends via API', async () => {
      mockSessionApi.getMessages.mockResolvedValue([])
      mockSessionApi.sendMessage.mockResolvedValue({
        id: 'sent-1',
        sessionId: 's1',
        role: 'USER',
        content: 'test',
        createdAt: '2026-01-01T00:00:00',
      })

      const { connectToSession, sendMessage } = useSession()
      await connectToSession('s1')

      await sendMessage('s1', 'test message')

      const store = useChatStore()
      expect(store.messages).toHaveLength(1)
      expect(store.messages[0]!.content).toBe('test message')
      expect(mockSessionApi.sendMessage).toHaveBeenCalledWith('s1', 'test message')
    })

    it('resets isThinking on API error', async () => {
      mockSessionApi.getMessages.mockResolvedValue([])
      mockSessionApi.sendMessage.mockRejectedValue(new Error('500'))

      const { connectToSession, sendMessage } = useSession()
      await connectToSession('s1')

      await sendMessage('s1', 'test')

      const store = useChatStore()
      expect(store.isThinking).toBe(false)
    })
  })

  // ========== disconnectSession ==========

  describe('disconnectSession', () => {
    it('unsubscribes SSE and resets state', async () => {
      mockSessionApi.getMessages.mockResolvedValue([])

      const { connectToSession, disconnectSession, connectedSessionId } = useSession()
      await connectToSession('s1')

      disconnectSession()

      expect(mockUnsubscribe).toHaveBeenCalled()
      expect(connectedSessionId.value).toBeNull()

      const store = useChatStore()
      expect(store.connectionStatus).toBe('disconnected')
    })

    it('is safe to call without active connection', () => {
      const { disconnectSession } = useSession()
      // Should not throw
      disconnectSession()
    })
  })
})
