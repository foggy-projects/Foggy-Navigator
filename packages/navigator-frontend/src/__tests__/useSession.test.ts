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
  ElMessage: { error: vi.fn() },
  ElNotification: vi.fn(),
}))

// Capture SSE client options for testing callbacks
let capturedSseOptions: Record<string, unknown> | null = null
const mockSseClose = vi.fn()

vi.mock('@foggy/chat', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@foggy/chat')>()
  return {
    ...actual,
    createSseClient: vi.fn((opts: Record<string, unknown>) => {
      capturedSseOptions = opts
      return { close: mockSseClose, getEventSource: () => null }
    }),
  }
})

import * as sessionApi from '@/api/session'
import { getToken } from '@/utils/auth'
import router from '@/router'
import { ElMessage, ElNotification } from 'element-plus'
import { useChatStore, AipMessageType } from '@foggy/chat'
import { useSession, setRouteRequestHandler } from '@/composables/useSession'
import type { AgentMessage, RoutePayload } from '@/types'

const mockSessionApi = vi.mocked(sessionApi)
const mockRouter = vi.mocked(router)

describe('useSession', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    capturedSseOptions = null
    setActivePinia(createPinia())
    setRouteRequestHandler(null)
  })

  afterEach(() => {
    // Clean up any active connections
    const { disconnectSession } = useSession()
    disconnectSession()
  })

  // ========== connectToSession ==========

  describe('connectToSession', () => {
    it('loads history messages and creates SSE connection', async () => {
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

      // SSE client created with correct params
      expect(capturedSseOptions).not.toBeNull()
      expect(capturedSseOptions!.url).toBe('/api/v1/sessions/session-abc/stream')
      expect(capturedSseOptions!.sessionId).toBe('session-abc')
      expect((capturedSseOptions!.headers as Record<string, string>)?.Authorization).toBe('Bearer test-token')
    })

    it('handles history load failure gracefully', async () => {
      mockSessionApi.getMessages.mockRejectedValue(new Error('Network error'))

      const { connectToSession } = useSession()
      await connectToSession('s1')

      // Should still create SSE connection
      expect(capturedSseOptions).not.toBeNull()
    })

    it('disconnects previous session before connecting', async () => {
      mockSessionApi.getMessages.mockResolvedValue([])

      const { connectToSession } = useSession()
      await connectToSession('s1')
      const firstClose = mockSseClose

      await connectToSession('s2')
      expect(firstClose).toHaveBeenCalled()
    })

    it('omits auth header when no token', async () => {
      vi.mocked(getToken).mockReturnValue(null as unknown as string)
      mockSessionApi.getMessages.mockResolvedValue([])

      const { connectToSession } = useSession()
      await connectToSession('s1')

      expect(capturedSseOptions!.headers).toBeUndefined()
    })
  })

  // ========== SSE callbacks ==========

  describe('SSE callbacks', () => {
    beforeEach(async () => {
      mockSessionApi.getMessages.mockResolvedValue([])
      const { connectToSession } = useSession()
      await connectToSession('s1')
    })

    it('onConnected sets connection status', () => {
      const onConnected = capturedSseOptions!.onConnected as () => void
      onConnected()

      const store = useChatStore()
      expect(store.connectionStatus).toBe('connected')
    })

    it('onError sets error status', () => {
      const onError = capturedSseOptions!.onError as () => void
      onError()

      const store = useChatStore()
      expect(store.connectionStatus).toBe('error')
    })

    it('onReconnecting sets connecting status', () => {
      const onReconnecting = capturedSseOptions!.onReconnecting as (n: number) => void
      onReconnecting(1)

      const store = useChatStore()
      expect(store.connectionStatus).toBe('connecting')
    })

    it('onMaxRetriesExceeded shows error message', () => {
      const onMax = capturedSseOptions!.onMaxRetriesExceeded as () => void
      onMax()

      expect(ElMessage.error).toHaveBeenCalledWith('连接已断开，请刷新页面重试')
    })

    it('onMessage processes AipMessages', () => {
      const onMessage = capturedSseOptions!.onMessage as (msgs: unknown[]) => void
      onMessage([{
        messageId: 'msg-1',
        sessionId: 's1',
        timestamp: Date.now(),
        type: AipMessageType.TEXT_COMPLETE,
        payload: { content: 'Hello from agent' },
      }])

      const store = useChatStore()
      expect(store.messages).toHaveLength(1)
      expect(store.messages[0]!.content).toBe('Hello from agent')
    })
  })

  // ========== onRawEvent: ROUTE_REQUEST ==========

  describe('onRawEvent ROUTE_REQUEST', () => {
    it('calls routeRequestHandler when set', async () => {
      mockSessionApi.getMessages.mockResolvedValue([])
      const handler = vi.fn()
      setRouteRequestHandler(handler)

      const { connectToSession } = useSession()
      await connectToSession('s1')

      const onRawEvent = capturedSseOptions!.onRawEvent as (raw: AgentMessage) => void
      const routePayload: RoutePayload = {
        action: 'DELEGATE',
        mode: 'REPLACE',
        target: { agentId: 'coding-agent', sessionId: 'child-1' },
      }
      onRawEvent({
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

    it('does nothing when no handler set', async () => {
      mockSessionApi.getMessages.mockResolvedValue([])
      setRouteRequestHandler(null)

      const { connectToSession } = useSession()
      await connectToSession('s1')

      const onRawEvent = capturedSseOptions!.onRawEvent as (raw: AgentMessage) => void
      // Should not throw
      onRawEvent({
        messageId: 'r1',
        sessionId: 's1',
        agentId: 'tutor-agent',
        timestamp: Date.now(),
        version: '1.0',
        type: 'ROUTE_REQUEST',
        payload: {},
      })
    })
  })

  // ========== onRawEvent: TASK_COMPLETED ==========

  describe('onRawEvent TASK_COMPLETED', () => {
    it('shows success notification for completed task', async () => {
      mockSessionApi.getMessages.mockResolvedValue([])
      const { connectToSession } = useSession()
      await connectToSession('s1')

      const onRawEvent = capturedSseOptions!.onRawEvent as (raw: AgentMessage) => void
      onRawEvent({
        messageId: 't1',
        sessionId: 's1',
        agentId: 'tutor-agent',
        timestamp: Date.now(),
        version: '1.0',
        type: 'TASK_COMPLETED',
        payload: {
          taskId: 'task-1',
          targetAgentId: 'coding-agent',
          taskType: 'CODING',
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
      // Check message contains agent name and summary
      const call = vi.mocked(ElNotification).mock.calls[0]![0] as Record<string, unknown>
      expect(call.message).toContain('coding-agent')
      expect(call.message).toContain('All tests passed')
    })

    it('shows error notification for failed task', async () => {
      mockSessionApi.getMessages.mockResolvedValue([])
      const { connectToSession } = useSession()
      await connectToSession('s1')

      const onRawEvent = capturedSseOptions!.onRawEvent as (raw: AgentMessage) => void
      onRawEvent({
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

    it('notification onClick navigates to /tasks', async () => {
      mockSessionApi.getMessages.mockResolvedValue([])
      const { connectToSession } = useSession()
      await connectToSession('s1')

      const onRawEvent = capturedSseOptions!.onRawEvent as (raw: AgentMessage) => void
      onRawEvent({
        messageId: 't3',
        sessionId: 's1',
        agentId: 'tutor-agent',
        timestamp: Date.now(),
        version: '1.0',
        type: 'TASK_COMPLETED',
        payload: { status: 'COMPLETED' },
      })

      // Extract onClick callback and invoke it
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
    it('closes SSE and resets state', async () => {
      mockSessionApi.getMessages.mockResolvedValue([])

      const { connectToSession, disconnectSession, connectedSessionId } = useSession()
      await connectToSession('s1')

      disconnectSession()

      expect(mockSseClose).toHaveBeenCalled()
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
