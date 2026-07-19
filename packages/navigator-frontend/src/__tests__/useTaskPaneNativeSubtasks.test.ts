import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'

const mocks = vi.hoisted(() => ({
  getLatestMessages: vi.fn(),
  getMessages: vi.fn(),
  getTaskUnified: vi.fn(),
  getNativeSubtasks: vi.fn(),
  subscribeSession: vi.fn(),
  unsubscribe: vi.fn(),
  sessionCallbacks: new Map<string, (message: any) => void>(),
  subscribedCallbacks: new Map<string, () => void>(),
}))

vi.mock('@/api/session', () => ({
  getLatestMessages: mocks.getLatestMessages,
  getMessages: mocks.getMessages,
}))

vi.mock('@/api/claudeWorker', () => ({
  getWorkerSessionMessageCount: vi.fn(),
  getWorkerSessionMessagesPaged: vi.fn(),
}))

vi.mock('@/api/nativeSubtasks', () => ({
  getNativeSubtasks: mocks.getNativeSubtasks,
}))

vi.mock('@/api/unifiedTask', () => ({
  getTaskUnified: mocks.getTaskUnified,
}))

vi.mock('@/composables/useUnifiedSse', () => ({
  useUnifiedSse: () => ({
    connected: { value: true },
    subscribeSession: mocks.subscribeSession,
  }),
}))

import { useTaskPane } from '@/composables/useTaskPane'

describe('useTaskPane native subtasks', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.getTaskUnified.mockReset()
    mocks.sessionCallbacks.clear()
    mocks.subscribedCallbacks.clear()
    mocks.subscribeSession.mockImplementation((_sessionId, callback, options) => {
      mocks.sessionCallbacks.set(_sessionId, callback)
      if (options?.onSubscribed) mocks.subscribedCallbacks.set(_sessionId, options.onSubscribed)
      queueMicrotask(() => options?.onSubscribed?.())
      return mocks.unsubscribe
    })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('falls back to a smaller initial history page after a truncated response', async () => {
    mocks.getLatestMessages
      .mockRejectedValueOnce(new Error('transfer closed with outstanding read data remaining'))
      .mockResolvedValueOnce({
        messages: [{
          id: 'message-1', sessionId: 'session-history', role: 'USER', content: 'visible history',
          createdAt: '2026-07-19T10:00:00Z',
        }],
        total: 4565,
        hasMore: true,
      })

    const pane = useTaskPane('pane-history-fallback')
    await pane.connect('session-history')

    expect(mocks.getLatestMessages).toHaveBeenNthCalledWith(1, 'session-history', 100, 0)
    expect(mocks.getLatestMessages).toHaveBeenNthCalledWith(2, 'session-history', 50, 0)
    expect(pane.chatState.messages.value).toEqual(expect.arrayContaining([
      expect.objectContaining({ id: 'message-1', content: 'visible history' }),
    ]))
    expect(pane.totalMessages.value).toBe(4565)
    expect(pane.historyLoadError.value).toBe('')
    pane.dispose()
  })

  it('keeps the pane usable and retries history after all initial transports fail', async () => {
    mocks.getLatestMessages
      .mockRejectedValueOnce(new Error('limit 100 failed'))
      .mockRejectedValueOnce(new Error('limit 50 failed'))
      .mockRejectedValueOnce(new Error('limit 20 failed'))
      .mockResolvedValueOnce({
        messages: [{
          id: 'message-retry', sessionId: 'session-history-retry', role: 'ASSISTANT',
          content: 'recovered history', createdAt: '2026-07-19T10:01:00Z',
        }],
        total: 1,
        hasMore: false,
      })

    const pane = useTaskPane('pane-history-retry')
    await pane.connect('session-history-retry')

    expect(pane.historyLoadError.value).toContain('消息加载失败')
    expect(pane.chatState.messages.value.some(message => (
      (message.raw as Record<string, unknown> | undefined)?.subtype === 'history_load_failed'
    ))).toBe(true)

    await pane.retryHistory()

    expect(mocks.getLatestMessages).toHaveBeenLastCalledWith('session-history-retry', 100, 0)
    expect(pane.historyLoadError.value).toBe('')
    expect(pane.chatState.messages.value).toEqual(expect.arrayContaining([
      expect.objectContaining({ id: 'message-retry', content: 'recovered history' }),
    ]))
    pane.dispose()
  })

  it('isolates one persisted message conversion failure and keeps surrounding messages', async () => {
    mocks.getLatestMessages.mockResolvedValue({
      messages: [
        {
          id: 'message-before', sessionId: 'session-isolation', role: 'USER', content: 'before',
          createdAt: '2026-07-19T10:00:00Z',
        },
        {
          id: 'message-bad', sessionId: 'session-isolation', role: 'ASSISTANT', content: '',
          metadata: { type: 'STATE_SYNC', subtype: 'bad-fixture' },
          createdAt: '2026-07-19T10:00:01Z',
        },
        {
          id: 'message-after', sessionId: 'session-isolation', role: 'ASSISTANT', content: 'after',
          createdAt: '2026-07-19T10:00:02Z',
        },
      ],
      total: 3,
      hasMore: false,
    })

    const pane = useTaskPane('pane-history-isolation')
    vi.spyOn(pane.chatState, 'processAipMessage').mockImplementationOnce(() => {
      throw new Error('unsupported persisted payload')
    })
    await pane.connect('session-isolation')

    expect(pane.chatState.messages.value).toEqual(expect.arrayContaining([
      expect.objectContaining({ id: 'message-before', content: 'before' }),
      expect.objectContaining({ id: 'message-after', content: 'after' }),
    ]))
    expect(pane.historyLoadError.value).toContain('1 条历史消息解析失败')
    pane.dispose()
  })

  it('initializes a delayed task id and keeps a newer SSE update over an older snapshot', async () => {
    let resolveSnapshot!: (value: any) => void
    mocks.getLatestMessages.mockResolvedValue({ messages: [], total: 0, hasMore: false })
    mocks.getNativeSubtasks.mockReturnValue(new Promise((resolve) => { resolveSnapshot = resolve }))

    const pane = useTaskPane('pane-native')
    await pane.connect('session-1')
    pane.task.value = {
      taskId: 'task-1',
      sessionId: 'session-1',
      workerId: 'worker-1',
      providerType: 'codex-app-server-worker',
      prompt: 'delegate',
      status: 'RUNNING',
      createdAt: '2026-07-10T00:00:00Z',
      updatedAt: '2026-07-10T00:00:00Z',
    }
    await nextTick()
    expect(mocks.getNativeSubtasks).toHaveBeenCalledWith('task-1')

    mocks.sessionCallbacks.get('session-1')?.({
      type: 'NATIVE_SUBTASK_UPDATE',
      sessionId: 'session-1',
      payload: {
        data: {
          taskId: 'task-1',
          lastEventSeq: 5,
          subtask: { subtaskId: 'sub-1', status: 'completed', label: 'Live result' },
        },
      },
    })
    resolveSnapshot({
      taskId: 'task-1',
      subtasks: [{ subtaskId: 'sub-1', status: 'running', label: 'Old snapshot', lastEventSeq: 4 }],
    })
    await Promise.resolve()
    await Promise.resolve()

    expect(pane.nativeSubtasks.value).toHaveLength(1)
    expect(pane.nativeSubtasks.value[0]).toMatchObject({
      subtaskId: 'sub-1',
      status: 'COMPLETED',
      label: 'Live result',
      lastEventSeq: 5,
    })
    pane.dispose()
    expect(pane.nativeSubtasks.value).toEqual([])
  })

  it('keeps native state isolated when one of multiple Panes is disposed', async () => {
    mocks.getLatestMessages.mockResolvedValue({ messages: [], total: 0, hasMore: false })
    mocks.getNativeSubtasks.mockImplementation(async (taskId: string) => ({
      taskId,
      subtasks: [{ subtaskId: `sub-${taskId}`, status: 'running', lastEventSeq: 1 }],
    }))

    const first = useTaskPane('pane-1')
    const second = useTaskPane('pane-2')
    first.task.value = {
      taskId: 'task-1', sessionId: 'session-1', workerId: 'worker-1', providerType: 'codex-app-server-worker',
      prompt: 'first', status: 'RUNNING', createdAt: '', updatedAt: '',
    }
    second.task.value = {
      taskId: 'task-2', sessionId: 'session-2', workerId: 'worker-1', providerType: 'codex-app-server-worker',
      prompt: 'second', status: 'RUNNING', createdAt: '', updatedAt: '',
    }

    await Promise.all([first.connect('session-1'), second.connect('session-2')])
    await Promise.resolve()
    expect(first.nativeSubtasks.value[0]?.subtaskId).toBe('sub-task-1')
    expect(second.nativeSubtasks.value[0]?.subtaskId).toBe('sub-task-2')

    first.dispose()
    expect(first.nativeSubtasks.value).toEqual([])
    expect(second.nativeSubtasks.value[0]?.subtaskId).toBe('sub-task-2')
    second.dispose()
  })

  it('does not query or project native subtasks for the SDK provider', async () => {
    mocks.getLatestMessages.mockResolvedValue({ messages: [], total: 0, hasMore: false })
    mocks.getNativeSubtasks.mockResolvedValue({
      taskId: 'task-sdk',
      subtasks: [{ subtaskId: 'unexpected', status: 'running', lastEventSeq: 1 }],
    })

    const pane = useTaskPane('pane-sdk')
    pane.task.value = {
      taskId: 'task-sdk',
      sessionId: 'session-sdk',
      workerId: 'worker-1',
      providerType: 'codex-worker',
      prompt: 'SDK task',
      status: 'RUNNING',
      createdAt: '',
      updatedAt: '',
    }
    await pane.connect('session-sdk')
    await flushAsyncWork()

    mocks.sessionCallbacks.get('session-sdk')?.({
      type: 'NATIVE_SUBTASK_UPDATE',
      sessionId: 'session-sdk',
      payload: {
        data: {
          taskId: 'task-sdk',
          lastEventSeq: 2,
          subtask: { subtaskId: 'unexpected', status: 'completed' },
        },
      },
    })

    expect(mocks.getNativeSubtasks).not.toHaveBeenCalled()
    expect(pane.nativeSubtasks.value).toEqual([])
    pane.dispose()
  })

  it('recovers the final result for a completed legacy task with no persisted final message', async () => {
    mocks.getLatestMessages.mockResolvedValue({ messages: [], total: 0, hasMore: false })

    const pane = useTaskPane('pane-result-recovery')
    pane.task.value = {
      taskId: 'task-result-recovery',
      sessionId: 'session-result-recovery',
      workerId: 'worker-1',
      providerType: 'codex-worker',
      prompt: 'finish the work',
      status: 'COMPLETED',
      resultText: 'FINAL_RECOVERED',
      createdAt: '2026-07-12T06:00:00Z',
      updatedAt: '2026-07-12T06:01:00Z',
    }

    await pane.connect('session-result-recovery')

    expect(pane.chatState.messages.value).toHaveLength(1)
    expect(pane.chatState.messages.value[0]).toMatchObject({
      sender: 'assistant',
      content: 'FINAL_RECOVERED',
      raw: {
        taskId: 'task-result-recovery',
        isResult: true,
        recoveredFromTask: true,
      },
    })
    pane.dispose()
  })

  it('does not add a task-result recovery beside an existing final message for that task', async () => {
    mocks.getLatestMessages.mockResolvedValue({
      messages: [{
        id: 'persisted-final', sessionId: 'session-result-existing', role: 'ASSISTANT',
        content: 'The streamed final has presentation-specific formatting.',
        metadata: {
          type: 'TEXT_COMPLETE',
          taskId: 'task-result-existing',
          content: 'The streamed final has presentation-specific formatting.',
        },
        createdAt: '2026-07-12T06:01:00Z',
      }],
      total: 1,
      hasMore: false,
    })

    const pane = useTaskPane('pane-result-existing')
    pane.task.value = {
      taskId: 'task-result-existing',
      sessionId: 'session-result-existing',
      workerId: 'worker-1',
      providerType: 'codex-worker',
      prompt: 'finish the work',
      status: 'COMPLETED',
      resultText: 'The task projection normalized this text differently.',
      createdAt: '2026-07-12T06:00:00Z',
      updatedAt: '2026-07-12T06:01:00Z',
    }

    await pane.connect('session-result-existing')

    expect(pane.chatState.messages.value).toHaveLength(1)
    expect(pane.chatState.messages.value[0]).toMatchObject({
      id: 'persisted-final',
      content: 'The streamed final has presentation-specific formatting.',
    })
    pane.dispose()
  })

  it('returns complete record history independently of visible-pane deduplication', async () => {
    mocks.getLatestMessages.mockResolvedValue({
      messages: [{
        id: 'visible-answer', sessionId: 'session-records', role: 'ASSISTANT', content: 'latest answer',
        metadata: { type: 'TEXT_COMPLETE', messageId: 'event-latest', content: 'latest answer' },
        createdAt: '2026-07-12T06:01:00Z',
      }],
      total: 2,
      hasMore: true,
    })
    mocks.getMessages.mockResolvedValue([
      {
        id: 'older-question', sessionId: 'session-records', role: 'USER', content: 'first question',
        createdAt: '2026-07-12T06:00:00Z',
      },
      {
        id: 'visible-answer', sessionId: 'session-records', role: 'ASSISTANT', content: 'latest answer',
        metadata: { type: 'TEXT_COMPLETE', messageId: 'event-latest', content: 'latest answer' },
        createdAt: '2026-07-12T06:01:00Z',
      },
    ])

    const pane = useTaskPane('pane-records')
    pane.task.value = {
      taskId: 'task-records', sessionId: 'session-records', workerId: 'worker-1', providerType: 'codex-worker',
      prompt: 'show records', status: 'COMPLETED', createdAt: '', updatedAt: '',
    }

    await pane.connect('session-records')
    const records = await pane.getAllHistoryMessages()

    expect(records.map(message => message.id)).toEqual(['older-question', 'visible-answer'])
    pane.dispose()
  })

  it('preserves visible messages when loading complete history fails', async () => {
    mocks.getLatestMessages.mockResolvedValue({
      messages: [{
        id: 'visible-answer', sessionId: 'session-load-all-failure', role: 'ASSISTANT',
        content: 'keep this visible', createdAt: '2026-07-19T06:01:00Z',
      }],
      total: 4565,
      hasMore: true,
    })
    mocks.getMessages.mockRejectedValue(new Error('truncated complete history response'))

    const pane = useTaskPane('pane-load-all-failure')
    await pane.connect('session-load-all-failure')
    await pane.loadAllHistory()

    expect(pane.chatState.messages.value).toEqual(expect.arrayContaining([
      expect.objectContaining({ id: 'visible-answer', content: 'keep this visible' }),
    ]))
    expect(pane.historyLoadError.value).toContain('当前消息已保留')
    pane.dispose()
  })

  it('recovers the final result after the subscribed task status refresh completes', async () => {
    mocks.getLatestMessages.mockResolvedValue({ messages: [], total: 0, hasMore: false })
    mocks.getTaskUnified.mockResolvedValue({
      taskId: 'task-result-refresh',
      sessionId: 'session-result-refresh',
      workerId: 'worker-1',
      providerType: 'codex-worker',
      prompt: 'finish the work',
      status: 'COMPLETED',
      resultText: 'FINAL_FROM_REFRESH',
      createdAt: '2026-07-12T06:00:00Z',
      updatedAt: '2026-07-12T06:01:00Z',
    })

    const pane = useTaskPane('pane-result-refresh')
    pane.task.value = {
      taskId: 'task-result-refresh',
      sessionId: 'session-result-refresh',
      workerId: 'worker-1',
      providerType: 'codex-worker',
      prompt: 'finish the work',
      status: 'RUNNING',
      createdAt: '2026-07-12T06:00:00Z',
      updatedAt: '2026-07-12T06:00:00Z',
    }

    await pane.connect('session-result-refresh')
    await flushAsyncWork()

    expect(pane.chatState.messages.value.at(-1)).toMatchObject({
      sender: 'assistant',
      content: 'FINAL_FROM_REFRESH',
    })
    pane.dispose()
  })

  it('retries transient HTTP and network failures and stops retrying after dispose', async () => {
    vi.useFakeTimers()
    mocks.getLatestMessages.mockResolvedValue({ messages: [], total: 0, hasMore: false })
    mocks.getNativeSubtasks
      .mockRejectedValueOnce(httpError(503))
      .mockRejectedValueOnce(networkError())
      .mockResolvedValueOnce({
        taskId: 'task-retry',
        subtasks: [{ subtaskId: 'sub-retry', status: 'running', lastEventSeq: 3 }],
      })

    const pane = useTaskPane('pane-retry')
    pane.task.value = {
      taskId: 'task-retry', sessionId: 'session-retry', workerId: 'worker-1', providerType: 'codex-app-server-worker',
      prompt: 'retry snapshot', status: 'RUNNING', createdAt: '', updatedAt: '',
    }
    await pane.connect('session-retry')
    await flushAsyncWork()
    expect(mocks.getNativeSubtasks).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(1000)
    await flushAsyncWork()
    expect(mocks.getNativeSubtasks).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(2000)
    await flushAsyncWork()
    expect(mocks.getNativeSubtasks).toHaveBeenCalledTimes(3)
    expect(pane.nativeSubtasks.value[0]).toMatchObject({
      subtaskId: 'sub-retry',
      status: 'RUNNING',
      lastEventSeq: 3,
    })

    mocks.getNativeSubtasks.mockRejectedValueOnce(networkError())
    pane.disconnect()
    pane.reconnectSse()
    await flushAsyncWork()
    pane.dispose()
    const callsAtDispose = mocks.getNativeSubtasks.mock.calls.length
    await vi.advanceTimersByTimeAsync(60000)
    expect(mocks.getNativeSubtasks).toHaveBeenCalledTimes(callsAtDispose)
  })

  it.each([408, 429, 500, 599])('retries retryable HTTP %s responses', async (status) => {
    vi.useFakeTimers()
    mocks.getLatestMessages.mockResolvedValue({ messages: [], total: 0, hasMore: false })
    mocks.getNativeSubtasks
      .mockRejectedValueOnce(httpError(status))
      .mockResolvedValueOnce({ taskId: `task-${status}`, subtasks: [] })

    const pane = useTaskPane(`pane-${status}`)
    pane.task.value = {
      taskId: `task-${status}`, sessionId: `session-${status}`, workerId: 'worker-1', providerType: 'codex-app-server-worker',
      prompt: 'retry snapshot', status: 'RUNNING', createdAt: '', updatedAt: '',
    }
    await pane.connect(`session-${status}`)
    await flushAsyncWork()
    expect(mocks.getNativeSubtasks).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(1000)
    await flushAsyncWork()
    expect(mocks.getNativeSubtasks).toHaveBeenCalledTimes(2)
    pane.dispose()
  })

  it.each([404, 405, 501])('requests unsupported HTTP %s once per task connection epoch', async (status) => {
    vi.useFakeTimers()
    mocks.getLatestMessages.mockResolvedValue({ messages: [], total: 0, hasMore: false })
    mocks.getNativeSubtasks.mockRejectedValue(httpError(status))

    const pane = useTaskPane(`pane-unsupported-${status}`)
    pane.task.value = {
      taskId: `task-unsupported-${status}`, sessionId: `session-unsupported-${status}`,
      workerId: 'worker-1', providerType: 'codex-app-server-worker', prompt: 'unsupported snapshot',
      status: 'RUNNING', createdAt: '', updatedAt: '',
    }
    await pane.connect(`session-unsupported-${status}`)
    await flushAsyncWork()

    mocks.subscribedCallbacks.get(`session-unsupported-${status}`)?.()
    mocks.subscribedCallbacks.get(`session-unsupported-${status}`)?.()
    await flushAsyncWork()
    await vi.advanceTimersByTimeAsync(60000)

    expect(mocks.getNativeSubtasks).toHaveBeenCalledTimes(1)
    expect(pane.nativeSubtasksLoading.value).toBe(false)
    expect(pane.nativeSubtasks.value).toEqual([])
    pane.dispose()
  })

  it('allows a later task and connection epoch to probe after unsupported responses', async () => {
    mocks.getLatestMessages.mockResolvedValue({ messages: [], total: 0, hasMore: false })
    mocks.getNativeSubtasks
      .mockRejectedValueOnce(httpError(404))
      .mockRejectedValueOnce(httpError(405))
      .mockResolvedValueOnce({
        taskId: 'task-later',
        subtasks: [{ subtaskId: 'sub-later', status: 'running', lastEventSeq: 7 }],
      })

    const pane = useTaskPane('pane-next-epoch')
    pane.task.value = {
      taskId: 'task-old', sessionId: 'session-next-epoch', workerId: 'worker-1', providerType: 'codex-app-server-worker',
      prompt: 'old task', status: 'RUNNING', createdAt: '', updatedAt: '',
    }
    await pane.connect('session-next-epoch')
    await flushAsyncWork()
    expect(mocks.getNativeSubtasks).toHaveBeenCalledTimes(1)

    pane.task.value = {
      taskId: 'task-later', sessionId: 'session-next-epoch', workerId: 'worker-1', providerType: 'codex-app-server-worker',
      prompt: 'later task', status: 'RUNNING', createdAt: '', updatedAt: '',
    }
    await nextTick()
    await flushAsyncWork()
    expect(mocks.getNativeSubtasks).toHaveBeenCalledTimes(2)

    pane.disconnect()
    pane.reconnectSse()
    await flushAsyncWork()
    expect(mocks.getNativeSubtasks).toHaveBeenCalledTimes(3)
    expect(pane.nativeSubtasks.value[0]?.subtaskId).toBe('sub-later')
    pane.dispose()
  })

  it('does not retry non-transient application errors', async () => {
    vi.useFakeTimers()
    mocks.getLatestMessages.mockResolvedValue({ messages: [], total: 0, hasMore: false })
    mocks.getNativeSubtasks.mockRejectedValue(new Error('invalid application response'))

    const pane = useTaskPane('pane-application-error')
    pane.task.value = {
      taskId: 'task-application-error', sessionId: 'session-application-error', workerId: 'worker-1',
      providerType: 'codex-app-server-worker', prompt: 'application error', status: 'RUNNING', createdAt: '', updatedAt: '',
    }
    await pane.connect('session-application-error')
    await flushAsyncWork()
    await vi.advanceTimersByTimeAsync(60000)

    expect(mocks.getNativeSubtasks).toHaveBeenCalledTimes(1)
    expect(pane.nativeSubtasksLoading.value).toBe(false)
    pane.dispose()
  })

  it('does not let an old task snapshot overwrite a newer task', async () => {
    let resolveOldSnapshot!: (value: any) => void
    mocks.getLatestMessages.mockResolvedValue({ messages: [], total: 0, hasMore: false })
    mocks.getNativeSubtasks.mockImplementation((taskId: string) => {
      if (taskId === 'task-old') {
        return new Promise((resolve) => { resolveOldSnapshot = resolve })
      }
      return Promise.resolve({
        taskId,
        subtasks: [{ subtaskId: 'sub-new', status: 'running', lastEventSeq: 2 }],
      })
    })

    const pane = useTaskPane('pane-task-switch')
    pane.task.value = {
      taskId: 'task-old', sessionId: 'session-switch', workerId: 'worker-1', providerType: 'codex-app-server-worker',
      prompt: 'old', status: 'RUNNING', createdAt: '', updatedAt: '',
    }
    await pane.connect('session-switch')
    await flushAsyncWork()

    pane.task.value = {
      taskId: 'task-new', sessionId: 'session-switch', workerId: 'worker-1', providerType: 'codex-app-server-worker',
      prompt: 'new', status: 'RUNNING', createdAt: '', updatedAt: '',
    }
    await nextTick()
    await flushAsyncWork()
    resolveOldSnapshot({
      taskId: 'task-old',
      subtasks: [{ subtaskId: 'sub-old', status: 'completed', lastEventSeq: 99 }],
    })
    await flushAsyncWork()

    expect(pane.nativeSubtasks.value).toHaveLength(1)
    expect(pane.nativeSubtasks.value[0]?.subtaskId).toBe('sub-new')
    pane.dispose()
  })

  it('projects a Codex question event to AWAITING_INPUT and keeps reconnectable stream errors non-terminal', async () => {
    mocks.getLatestMessages.mockResolvedValue({ messages: [], total: 0, hasMore: false })
    mocks.getNativeSubtasks.mockResolvedValue({ taskId: 'task-input', subtasks: [] })

    const pane = useTaskPane('pane-input')
    pane.task.value = {
      taskId: 'task-input',
      sessionId: 'session-input',
      workerId: 'worker-1',
      providerType: 'codex-app-server-worker',
      prompt: 'interactive task',
      status: 'RUNNING',
      createdAt: '',
      updatedAt: '',
    }
    await pane.connect('session-input')

    mocks.sessionCallbacks.get('session-input')?.({
      messageId: 'message-input',
      type: 'CONFIRMATION_REQUEST',
      sessionId: 'session-input',
      timestamp: 1,
      payload: {
        taskId: 'task-input',
        permissionId: 'permission-input',
        questions: [{
          id: 'target',
          header: 'Target',
          question: 'Choose target',
          options: [{ label: 'Staging', description: '' }],
          multiSelect: false,
          isOther: false,
          isSecret: false,
        }],
      },
    })

    expect(pane.task.value?.status).toBe('AWAITING_INPUT')
    expect(pane.chatState.messages.value.at(-1)).toMatchObject({
      permissionId: 'permission-input',
      permissionStatus: 'pending',
    })

    mocks.sessionCallbacks.get('session-input')?.({
      messageId: 'message-disconnect',
      type: 'ERROR',
      sessionId: 'session-input',
      timestamp: 2,
      payload: {
        taskId: 'task-input',
        error: 'worker stream disconnected',
        reconnectable: true,
      },
    })

    expect(pane.task.value?.status).toBe('AWAITING_INPUT')
    expect(pane.chatState.messages.value.at(-1)?.reconnectable).toBe(true)
    pane.dispose()
  })

  it('does not render an SSE replay twice and ignores another task in the same session', async () => {
    const finalPayload = {
      type: 'TEXT_COMPLETE',
      content: 'final answer',
      taskId: 'task-current',
      streamId: 'item-current',
    }
    mocks.getLatestMessages.mockResolvedValue({
      messages: [{
        id: 'codex-event:task-current:2',
        sessionId: 'session-shared',
        role: 'ASSISTANT',
        content: 'final answer',
        metadata: finalPayload,
        createdAt: '2026-07-13T00:00:00Z',
      }],
      total: 1,
      hasMore: false,
    })

    const pane = useTaskPane('pane-message-replay')
    pane.task.value = {
      taskId: 'task-current', sessionId: 'session-shared', workerId: 'worker-1', providerType: 'codex-app-server-worker',
      prompt: 'current', status: 'RUNNING', createdAt: '', updatedAt: '',
    }
    await pane.connect('session-shared')

    const callback = mocks.sessionCallbacks.get('session-shared')
    callback?.({
      messageId: 'codex-event:task-current:2',
      type: 'TEXT_COMPLETE',
      sessionId: 'session-shared',
      timestamp: 2,
      payload: finalPayload,
    })
    callback?.({
      messageId: 'codex-event:task-previous:9',
      type: 'TEXT_COMPLETE',
      sessionId: 'session-shared',
      timestamp: 3,
      payload: { type: 'TEXT_COMPLETE', content: 'old task output', taskId: 'task-previous', streamId: 'item-old' },
    })

    expect(pane.chatState.messages.value).toHaveLength(1)
    expect(pane.chatState.messages.value[0]).toMatchObject({
      content: 'final answer', taskId: 'task-current', streamId: 'item-current',
    })
    pane.dispose()
  })
})

async function flushAsyncWork(): Promise<void> {
  for (let i = 0; i < 6; i++) await Promise.resolve()
}

function httpError(status: number): Error {
  return Object.assign(new Error(`HTTP ${status}`), {
    isAxiosError: true,
    response: { status },
  })
}

function networkError(): Error {
  return Object.assign(new Error('Network Error'), {
    isAxiosError: true,
    request: {},
  })
}
