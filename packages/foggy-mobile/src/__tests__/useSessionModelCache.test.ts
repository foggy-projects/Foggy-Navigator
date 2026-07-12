import { afterEach, describe, expect, it } from 'vitest'
import {
  clearSessionModel,
  getSessionModel,
  initFromTask,
  setSessionModel,
} from '@/composables/useSessionModelCache'
import type { DispatchTask } from '@/api/types'

const SESSION_ID = 'session-provider-cache'

afterEach(() => clearSessionModel(SESSION_ID))

describe('useSessionModelCache provider affinity', () => {
  it('preserves providerType when initialized from a task', () => {
    initFromTask({
      taskId: 'task-1',
      sessionId: SESSION_ID,
      workerId: 'worker-1',
      prompt: 'test',
      status: 'COMPLETED',
      modelConfigId: 'app-config',
      model: 'codex-terra:ultra',
      providerType: 'codex-app-server-worker',
      createdAt: '2026-07-12T00:00:00Z',
      updatedAt: '2026-07-12T00:00:00Z',
    } satisfies DispatchTask)

    expect(getSessionModel(SESSION_ID)).toEqual({
      modelConfigId: 'app-config',
      model: 'codex-terra:ultra',
      providerType: 'codex-app-server-worker',
    })
  })

  it('updates provider affinity together with a manual model selection', () => {
    setSessionModel(SESSION_ID, 'sdk-config', 'codex-terra:max', 'codex-worker')

    expect(getSessionModel(SESSION_ID)?.providerType).toBe('codex-worker')
  })
})
