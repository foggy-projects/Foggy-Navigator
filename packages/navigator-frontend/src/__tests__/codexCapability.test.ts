import { describe, expect, it, vi } from 'vitest'
import type { ClaudeWorker } from '@/types'
import {
  filterModelOptionsByAvailability,
  filterWorkersForCodexBackend,
  resolveAvailableModelValues,
} from '@/utils/codexCapability'
import { getModelOptionsByBackend } from '@/utils/llmModelOptions'

function worker(
  workerId: string,
  overrides: Partial<ClaudeWorker> = {},
): ClaudeWorker {
  return {
    workerId,
    name: workerId,
    baseUrl: `http://${workerId}.local`,
    authMode: 'SUBSCRIPTION',
    status: 'ONLINE',
    createdAt: '2026-07-12T00:00:00Z',
    ...overrides,
  }
}

describe('Codex capability filtering', () => {
  it('keeps only SDK-capable physical Workers for the SDK backend', () => {
    const workers = [
      worker('native-sdk', { workerBackend: 'OPENAI_CODEX', codexBaseUrl: 'http://sdk.local' }),
      worker('claude-with-sdk', { workerBackend: 'CLAUDE_CODE', codexBaseUrl: ' http://codex.local ' }),
      worker('backend-only', { workerBackend: 'OPENAI_CODEX' }),
      worker('claude-only', { workerBackend: 'CLAUDE_CODE' }),
      worker('blank-sdk-config', { workerBackend: 'CLAUDE_CODE', codexBaseUrl: '   ' }),
    ]

    expect(filterWorkersForCodexBackend(workers, 'OPENAI_CODEX', new Set()))
      .toEqual(workers.slice(0, 2))
  })

  it('keeps only Workers owning an App Server Endpoint for the App backend', () => {
    const workers = [worker('app-a'), worker('app-b'), worker('without-endpoint')]

    expect(filterWorkersForCodexBackend(
      workers,
      'OPENAI_CODEX_APP_SERVER',
      new Set(['app-a', 'app-b']),
    )).toEqual(workers.slice(0, 2))
  })

  it('fails closed per App model probe and preserves server-filtered values', async () => {
    const options = getModelOptionsByBackend('OPENAI_CODEX_APP_SERVER')
      .filter((option) => ['codex-latest:high', 'codex-latest:ultra', 'codex-terra:ultra'].includes(option.value))
    const probe = vi.fn(async (model: string) => {
      if (model === 'codex-terra:ultra') throw new Error('runtime unreachable')
      return { modelAvailable: model === 'codex-latest:ultra', ultraAvailable: true }
    })

    const available = await resolveAvailableModelValues(options, probe)

    expect(probe).toHaveBeenCalledTimes(3)
    expect([...available]).toEqual(['codex-latest:ultra'])
    expect(filterModelOptionsByAvailability(options, available).map((option) => option.value))
      .toEqual(['codex-latest:ultra'])
    expect(filterModelOptionsByAvailability(options, null)).toEqual([])
  })

  it('uses the legacy Ultra flag only when modelAvailable is absent', async () => {
    const option = getModelOptionsByBackend('OPENAI_CODEX_APP_SERVER')
      .find((candidate) => candidate.value === 'codex-latest:ultra')!

    await expect(resolveAvailableModelValues([option], async () => ({ ultraAvailable: true })))
      .resolves.toEqual(new Set(['codex-latest:ultra']))
    await expect(resolveAvailableModelValues([option], async () => ({
      modelAvailable: false,
      ultraAvailable: true,
    }))).resolves.toEqual(new Set())
  })
})
