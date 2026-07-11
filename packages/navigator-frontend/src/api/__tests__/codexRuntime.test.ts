import { beforeEach, describe, expect, it, vi } from 'vitest'
import client from '../client'
import {
  getCodexRuntimeAvailability,
  listCodexRuntimes,
  refreshCodexRuntime,
  registerCodexRuntime,
  updateCodexRuntimeRouting,
} from '../codexRuntime'
import type { CodexRuntime } from '@/types/codexRuntime'

vi.mock('../client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
  },
}))

const runtime = {
  runtimeId: 'app/server local',
  revision: 2,
  workerId: 'worker-1',
  runtimeType: 'APP_SERVER',
  endpointConfigured: true,
  enabled: false,
  routingPolicy: 'DARK',
  rolloutPercentage: 0,
  priority: 0,
  routingEpoch: 1,
  readinessStatus: 'PENDING',
  createdAt: '2026-07-10T10:00:00',
  updatedAt: '2026-07-10T10:00:00',
} satisfies CodexRuntime

describe('codexRuntime API', () => {
  beforeEach(() => vi.clearAllMocks())

  it('lists runtimes by physical worker', async () => {
    vi.mocked(client.get).mockResolvedValue({ data: [runtime] })

    await expect(listCodexRuntimes('worker-1')).resolves.toEqual([runtime])
    expect(client.get).toHaveBeenCalledWith('/codex-runtimes', {
      params: { workerId: 'worker-1' },
    })
  })

  it('can suppress client error toasts for background refreshes', async () => {
    vi.mocked(client.get).mockResolvedValue({ data: [runtime] })

    await listCodexRuntimes('worker-1', { suppressErrorMessage: true })

    expect(client.get).toHaveBeenCalledWith('/codex-runtimes', {
      params: { workerId: 'worker-1' },
      suppressErrorMessage: true,
    })
  })

  it('reads only aggregate runtime availability for an accessible worker', async () => {
    const availability = {
      appServerManaged: true,
      ultraAvailable: false,
      blockReason: 'CODEX_ULTRA_RUNTIME_UNAVAILABLE' as const,
    }
    vi.mocked(client.get).mockResolvedValue({ data: availability })

    await expect(getCodexRuntimeAvailability('shared-worker', {
      suppressErrorMessage: true,
    })).resolves.toEqual(availability)
    expect(client.get).toHaveBeenCalledWith('/codex-runtimes/availability', {
      params: { workerId: 'shared-worker' },
      suppressErrorMessage: true,
    })
  })

  it('passes the requested model for model-aware Ultra availability', async () => {
    const availability = {
      appServerManaged: true,
      ultraAvailable: true,
      blockReason: null,
    }
    vi.mocked(client.get).mockResolvedValue({ data: availability })

    await expect(getCodexRuntimeAvailability('shared-worker', {
      model: 'codex-terra:ultra',
      suppressErrorMessage: true,
    })).resolves.toEqual(availability)
    expect(client.get).toHaveBeenCalledWith('/codex-runtimes/availability', {
      params: {
        workerId: 'shared-worker',
        model: 'codex-terra:ultra',
      },
      suppressErrorMessage: true,
    })
  })

  it('registers a dark runtime without changing the request payload', async () => {
    vi.mocked(client.post).mockResolvedValue({ data: runtime })
    const request = {
      runtimeId: 'app/server local',
      workerId: 'worker-1',
      endpointUrl: 'http://localhost:3062',
      authToken: 'one-time-token',
      enabled: false,
      routingPolicy: 'DARK' as const,
      rolloutPercentage: 0,
    }

    await expect(registerCodexRuntime(request)).resolves.toEqual(runtime)
    expect(client.post).toHaveBeenCalledWith('/codex-runtimes', request)
  })

  it('encodes runtime ids in refresh and routing paths', async () => {
    vi.mocked(client.post).mockResolvedValue({ data: runtime })
    vi.mocked(client.put).mockResolvedValue({ data: runtime })

    await refreshCodexRuntime(runtime.runtimeId, runtime.revision)
    await updateCodexRuntimeRouting(runtime.runtimeId, runtime.revision, {
      enabled: true,
      routingPolicy: 'ULTRA_CANARY',
      rolloutPercentage: 10,
      expectedRoutingEpoch: 1,
    })

    const base = '/codex-runtimes/app%2Fserver%20local/revisions/2'
    expect(client.post).toHaveBeenCalledWith(`${base}/refresh`)
    expect(client.put).toHaveBeenCalledWith(`${base}/routing`, {
      enabled: true,
      routingPolicy: 'ULTRA_CANARY',
      rolloutPercentage: 10,
      expectedRoutingEpoch: 1,
    })
  })
})
