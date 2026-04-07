import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { AipMessageType } from '@foggy/chat-core'

const mockGetLatestMessages = vi.fn()
const mockGetTaskUnified = vi.fn()
const mockConvert = vi.fn()
const connected = ref(true)

let capturedSessionCallback: ((msg: any) => void) | null = null
let capturedNotificationCallback: ((eventType: string, msg: any) => void) | null = null
const mockUnsubscribe = vi.fn()

vi.mock('@/api/session', () => ({
  getLatestMessages: mockGetLatestMessages,
}))

vi.mock('@/api/unifiedTask', () => ({
  getTaskUnified: mockGetTaskUnified,
}))

vi.mock('@/adapters/TutorAgentAdapter', () => ({
  tutorAgentAdapter: {
    convert: mockConvert,
  },
}))

vi.mock('@/composables/useUnifiedSse', () => ({
  useUnifiedSse: () => ({
    connected,
    subscribeSession: vi.fn((_sessionId: string, callback: (msg: any) => void) => {
      capturedSessionCallback = callback
      return mockUnsubscribe
    }),
    addNotificationListener: vi.fn((callback: (eventType: string, msg: any) => void) => {
      capturedNotificationCallback = callback
      return vi.fn()
    }),
  }),
}))

describe('useTaskStream', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    capturedSessionCallback = null
    capturedNotificationCallback = null
    connected.value = true
  })

  it('loads DB history and skips SSE subscribe for terminal tasks', async () => {
    mockGetLatestMessages.mockResolvedValue({
      messages: [
        {
          id: 'db-1',
          role: 'USER',
          content: 'hello',
          createdAt: '2026-01-01T00:00:00Z',
        },
      ],
      total: 1,
      limit: 50,
      offset: 0,
      hasMore: false,
    })

    const { useTaskStream } = await import('@/composables/useTaskStream')
    const stream = useTaskStream()
    stream.task.value = {
      taskId: 'task-1',
      sessionId: 'session-1',
      status: 'COMPLETED',
      prompt: 'hello',
    } as any

    await stream.connect('session-1')

    expect(stream.chatState.messages.value).toHaveLength(1)
    expect(stream.chatState.messages.value[0]).toMatchObject({
      id: 'db-1',
      sender: 'user',
      content: 'hello',
    })
    expect(capturedSessionCallback).toBeNull()
  })

  it('deduplicates SSE messages already loaded from DB', async () => {
    mockGetLatestMessages.mockResolvedValue({
      messages: [
        {
          id: 'dup-1',
          role: 'ASSISTANT',
          content: 'from-db',
          createdAt: '2026-01-01T00:00:00Z',
        },
      ],
      total: 1,
      limit: 50,
      offset: 0,
      hasMore: false,
    })
    mockConvert
      .mockReturnValueOnce([
        {
          messageId: 'dup-1',
          sessionId: 'session-1',
          type: AipMessageType.TEXT_COMPLETE,
          timestamp: 1,
          payload: { content: 'from-sse-dup' },
        },
      ])
      .mockReturnValueOnce([
        {
          messageId: 'new-1',
          sessionId: 'session-1',
          type: AipMessageType.TEXT_COMPLETE,
          timestamp: 2,
          payload: { content: 'fresh-message' },
        },
      ])

    const { useTaskStream } = await import('@/composables/useTaskStream')
    const stream = useTaskStream()
    stream.task.value = {
      taskId: 'task-1',
      sessionId: 'session-1',
      status: 'RUNNING',
      prompt: 'hello',
    } as any

    await stream.connect('session-1')
    expect(stream.chatState.messages.value).toHaveLength(1)

    capturedSessionCallback?.({ sessionId: 'session-1', type: 'TEXT_COMPLETE', payload: { taskId: 'task-1' } })
    expect(stream.chatState.messages.value).toHaveLength(1)

    capturedSessionCallback?.({ sessionId: 'session-1', type: 'TEXT_COMPLETE', payload: { taskId: 'task-1' } })
    expect(stream.chatState.messages.value).toHaveLength(2)
    expect(stream.chatState.messages.value[1]).toMatchObject({
      id: 'new-1',
      content: 'fresh-message',
    })
  })

  it('marks matching task as completed and updates model on TEXT_COMPLETE result event', async () => {
    mockGetLatestMessages.mockResolvedValue({
      messages: [],
      total: 0,
      limit: 50,
      offset: 0,
      hasMore: false,
    })
    mockConvert.mockReturnValue([
      {
        messageId: 'evt-1',
        sessionId: 'session-1',
        type: AipMessageType.TEXT_COMPLETE,
        timestamp: 3,
        payload: {
          content: 'FRESH_D',
          isResult: true,
        },
      },
    ])

    const onTaskFinished = vi.fn()
    const { useTaskStream } = await import('@/composables/useTaskStream')
    const stream = useTaskStream(onTaskFinished)
    stream.task.value = {
      taskId: 'task-1',
      sessionId: 'session-1',
      status: 'RUNNING',
      prompt: 'hello',
      model: 'old-model',
    } as any

    await stream.connect('session-1')
    capturedSessionCallback?.({
      sessionId: 'session-1',
      type: 'TEXT_COMPLETE',
      payload: {
        taskId: 'task-1',
        numTurns: 2,
        costUsd: 0.1,
        durationMs: 1234,
        inputTokens: 10,
        outputTokens: 20,
        model: 'sonnet',
      },
    })

    expect(stream.task.value).toMatchObject({
      status: 'COMPLETED',
      model: 'sonnet',
      numTurns: 2,
      costUsd: 0.1,
      durationMs: 1234,
      inputTokens: 10,
      outputTokens: 20,
    })
    expect(onTaskFinished).toHaveBeenCalled()
  })

  it('syncs task status from global notifications for the same session', async () => {
    mockGetLatestMessages.mockResolvedValue({
      messages: [],
      total: 0,
      limit: 50,
      offset: 0,
      hasMore: false,
    })
    mockConvert.mockReturnValue([])

    const { useTaskStream } = await import('@/composables/useTaskStream')
    const stream = useTaskStream()
    stream.task.value = {
      taskId: 'task-1',
      sessionId: 'session-1',
      status: 'RUNNING',
      prompt: 'hello',
    } as any

    await stream.connect('session-1')
    capturedNotificationCallback?.('task_update', {
      sessionId: 'session-1',
      status: 'AWAITING_PERMISSION',
    })

    expect(stream.task.value?.status).toBe('AWAITING_PERMISSION')
  })
})
