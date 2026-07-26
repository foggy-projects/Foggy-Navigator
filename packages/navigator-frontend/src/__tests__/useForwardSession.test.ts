import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import type { ClaudeTask } from '@/types'

const mocks = vi.hoisted(() => ({
  listModelConfigs: vi.fn(),
}))

vi.mock('@/api/platform', () => ({
  listModelConfigs: mocks.listModelConfigs,
}))

import {
  recoveredSourceTaskId,
  useForwardSession,
  type ForwardSessionDeps,
} from '@/composables/useForwardSession'

function makeTask(overrides?: Partial<ClaudeTask>): ClaudeTask {
  return {
    taskId: 'task-source',
    sessionId: 'session-source',
    workerId: 'worker-1',
    prompt: 'source prompt',
    status: 'COMPLETED',
    createdAt: '2026-07-26T10:00:00Z',
    updatedAt: '2026-07-26T10:01:00Z',
    ...overrides,
  }
}

function makeDeps() {
  const forwardSession = vi.fn().mockResolvedValue({
    sourceSessionId: 'session-source',
    sourceMessageId: 'msg-durable',
    targetSessionId: 'session-target',
    task: makeTask({ taskId: 'task-target', sessionId: 'session-target' }),
  })
  const workerState = {
    conversationConfigs: ref(new Map()),
    directories: ref([]),
    tasks: ref([]),
    workers: ref([]),
    loadDirectories: vi.fn().mockResolvedValue(undefined),
    forwardSession,
  }
  const deps = {
    workerState,
    directoryTasks: ref([]),
    groupTasksToConversations: () => [],
    resolveConversationMilestone: () => undefined,
    shortModel: (model: string) => model,
    milestoneStatusLabel: (status: string) => status,
    formatTime: (value: string) => value,
    ALL_MODELS: [],
  } as unknown as ForwardSessionDeps
  return { deps, forwardSession }
}

describe('useForwardSession recovered task result', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.listModelConfigs.mockResolvedValue([])
  })

  it('extracts a source task id only from recovered task result messages', () => {
    expect(recoveredSourceTaskId({
      raw: { taskId: 'task-recovered', recoveredFromTask: true },
    })).toBe('task-recovered')
    expect(recoveredSourceTaskId({
      taskId: 'task-normal',
      raw: { taskId: 'task-normal', recoveredFromTask: false },
    })).toBeUndefined()
    expect(recoveredSourceTaskId({ taskId: 'task-normal' })).toBeUndefined()
  })

  it('submits sourceTaskId for a recovered result and omits it for normal messages', async () => {
    const { deps, forwardSession } = makeDeps()
    const state = useForwardSession(deps)
    const task = makeTask()

    await state.openForwardDialog({
      task,
      messageId: 'task-result-task-source',
      sourceTaskId: 'task-source',
      sourceContent: '任务最终结果',
      selectedWorkerId: 'worker-1',
      defaultModel: '',
      defaultModelConfigId: '',
      defaultPermissionMode: 'bypassPermissions',
    })
    await state.submitForward()

    expect(forwardSession).toHaveBeenLastCalledWith(expect.objectContaining({
      sourceSessionId: 'session-source',
      sourceMessageId: 'task-result-task-source',
      sourceTaskId: 'task-source',
    }))

    await state.openForwardDialog({
      task,
      messageId: 'msg-persisted',
      sourceContent: '普通持久消息',
      selectedWorkerId: 'worker-1',
      defaultModel: '',
      defaultModelConfigId: '',
      defaultPermissionMode: 'bypassPermissions',
    })
    await state.submitForward()

    const normalPayload = forwardSession.mock.calls.at(-1)?.[0]
    expect(normalPayload).toMatchObject({
      sourceSessionId: 'session-source',
      sourceMessageId: 'msg-persisted',
    })
    expect(normalPayload).not.toHaveProperty('sourceTaskId')
  })
})
