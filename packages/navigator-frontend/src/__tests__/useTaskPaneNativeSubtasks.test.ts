import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'

const mocks = vi.hoisted(() => ({
  getLatestMessages: vi.fn(),
  getNativeSubtasks: vi.fn(),
  subscribeSession: vi.fn(),
  unsubscribe: vi.fn(),
  sessionCallbacks: new Map<string, (message: any) => void>(),
  subscribedCallbacks: new Map<string, () => void>(),
}))

vi.mock('@/api/session', () => ({
  getLatestMessages: mocks.getLatestMessages,
  getMessages: vi.fn(),
}))

vi.mock('@/api/claudeWorker', () => ({
  getWorkerSessionMessageCount: vi.fn(),
  getWorkerSessionMessagesPaged: vi.fn(),
}))

vi.mock('@/api/nativeSubtasks', () => ({
  getNativeSubtasks: mocks.getNativeSubtasks,
}))

vi.mock('@/api/unifiedTask', () => ({
  getTaskUnified: vi.fn(),
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
      providerType: 'codex-worker',
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
      taskId: 'task-1', sessionId: 'session-1', workerId: 'worker-1', providerType: 'codex-worker',
      prompt: 'first', status: 'RUNNING', createdAt: '', updatedAt: '',
    }
    second.task.value = {
      taskId: 'task-2', sessionId: 'session-2', workerId: 'worker-1', providerType: 'codex-worker',
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
      taskId: 'task-retry', sessionId: 'session-retry', workerId: 'worker-1', providerType: 'codex-worker',
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
      taskId: `task-${status}`, sessionId: `session-${status}`, workerId: 'worker-1', providerType: 'codex-worker',
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
      workerId: 'worker-1', providerType: 'codex-worker', prompt: 'unsupported snapshot',
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
      taskId: 'task-old', sessionId: 'session-next-epoch', workerId: 'worker-1', providerType: 'codex-worker',
      prompt: 'old task', status: 'RUNNING', createdAt: '', updatedAt: '',
    }
    await pane.connect('session-next-epoch')
    await flushAsyncWork()
    expect(mocks.getNativeSubtasks).toHaveBeenCalledTimes(1)

    pane.task.value = {
      taskId: 'task-later', sessionId: 'session-next-epoch', workerId: 'worker-1', providerType: 'codex-worker',
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
      providerType: 'codex-worker', prompt: 'application error', status: 'RUNNING', createdAt: '', updatedAt: '',
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
      taskId: 'task-old', sessionId: 'session-switch', workerId: 'worker-1', providerType: 'codex-worker',
      prompt: 'old', status: 'RUNNING', createdAt: '', updatedAt: '',
    }
    await pane.connect('session-switch')
    await flushAsyncWork()

    pane.task.value = {
      taskId: 'task-new', sessionId: 'session-switch', workerId: 'worker-1', providerType: 'codex-worker',
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
      providerType: 'codex-worker',
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
