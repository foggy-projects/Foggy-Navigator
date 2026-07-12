import { describe, expect, it } from 'vitest'
import type { DispatchTask } from '@/api/types'
import {
  inferTaskProviderType,
  providerTypeFromWorkerBackend,
  providerTypeShortLabel,
  requiresNewSessionForProvider,
  workerBackendFromProviderType,
} from '@/utils/workerBackend'

function task(overrides: Partial<DispatchTask> = {}): DispatchTask {
  return {
    taskId: 'task-1',
    sessionId: 'session-1',
    workerId: 'worker-1',
    prompt: 'test',
    status: 'COMPLETED',
    createdAt: '2026-07-12T00:00:00Z',
    updatedAt: '2026-07-12T00:00:00Z',
    ...overrides,
  }
}

describe('mobile worker backend mapping', () => {
  it('maps Codex SDK and App Server to independent providers and labels', () => {
    expect(providerTypeFromWorkerBackend('OPENAI_CODEX')).toBe('codex-worker')
    expect(providerTypeFromWorkerBackend('OPENAI_CODEX_APP_SERVER')).toBe('codex-app-server-worker')
    expect(workerBackendFromProviderType('codex-worker')).toBe('OPENAI_CODEX')
    expect(workerBackendFromProviderType('codex-app-server-worker')).toBe('OPENAI_CODEX_APP_SERVER')
    expect(providerTypeShortLabel('codex-worker')).toBe('Codex SDK')
    expect(providerTypeShortLabel('codex-app-server-worker')).toBe('Codex App Server')
  })

  it('uses the persisted provider type before legacy inference', () => {
    expect(inferTaskProviderType(task({
      providerType: 'codex-app-server-worker',
      codexThreadId: 'thread-1',
      model: 'codex-terra:medium',
    }))).toBe('codex-app-server-worker')
  })

  it('treats legacy Ultra tasks as App Server tasks', () => {
    expect(inferTaskProviderType(task({
      codexThreadId: 'thread-1',
      model: 'gpt-5.6-sol:ultra',
    }))).toBe('codex-app-server-worker')
  })

  it('requires a new session for either Codex provider boundary direction', () => {
    expect(requiresNewSessionForProvider('codex-worker', 'OPENAI_CODEX_APP_SERVER')).toBe(true)
    expect(requiresNewSessionForProvider('codex-app-server-worker', 'OPENAI_CODEX')).toBe(true)
    expect(requiresNewSessionForProvider('codex-worker', 'OPENAI_CODEX')).toBe(false)
  })
})
