import { beforeEach, describe, expect, it, vi } from 'vitest'
import client from '../client'
import {
  archiveCodexRuntime,
  createCodexAppServerEndpoint,
  deleteCodexAppServerEndpoint,
  getCodexRuntimeAvailability,
  getCodexRuntimeRateLimits,
  listCodexRuntimes,
  listCodexAppServerEndpoints,
  refreshCodexRuntime,
  registerCodexRuntime,
  syncCodexAppServerEndpoint,
  unarchiveCodexRuntime,
  updateCodexAppServerEndpoint,
  updateCodexRuntimeRouting,
} from '../codexRuntime'
import type { CodexRuntime, CodexRuntimeRateLimits } from '@/types/codexRuntime'

vi.mock('../client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
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

  it('manages endpoint profiles separately from runtime revisions', async () => {
    const endpoint = {
      endpointId: 'endpoint/a b',
      workerId: 'worker-1',
      endpointUrl: 'http://127.0.0.1:3071',
      endpointDisplay: 'http://127.0.0.1:3071',
      tokenConfigured: true,
      configurationVersion: 1,
      lastSyncStatus: 'PENDING',
      createdAt: '2026-07-10T10:00:00',
      updatedAt: '2026-07-10T10:00:00',
    }
    vi.mocked(client.get).mockResolvedValue({ data: [endpoint] })
    vi.mocked(client.post).mockResolvedValue({ data: endpoint })
    vi.mocked(client.put).mockResolvedValue({ data: endpoint })

    await expect(listCodexAppServerEndpoints('worker-1')).resolves.toEqual([endpoint])
    await expect(createCodexAppServerEndpoint({
      workerId: 'worker-1', endpointUrl: endpoint.endpointUrl, authToken: 'secret',
    })).resolves.toEqual(endpoint)
    await expect(updateCodexAppServerEndpoint(endpoint.endpointId, {
      endpointUrl: endpoint.endpointUrl, clearAuthToken: true,
    })).resolves.toEqual(endpoint)
    await syncCodexAppServerEndpoint(endpoint.endpointId)
    await deleteCodexAppServerEndpoint(endpoint.endpointId)

    expect(client.get).toHaveBeenCalledWith('/codex-app-server-endpoints', {
      params: { workerId: 'worker-1' },
    })
    const base = '/codex-app-server-endpoints/endpoint%2Fa%20b'
    expect(client.put).toHaveBeenCalledWith(`${base}`, {
      endpointUrl: endpoint.endpointUrl, clearAuthToken: true,
    })
    expect(client.post).toHaveBeenCalledWith(`${base}/sync`)
    expect(client.delete).toHaveBeenCalledWith(base)
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

  it('can include archived runtime revisions for owner lifecycle management', async () => {
    vi.mocked(client.get).mockResolvedValue({ data: [runtime] })

    await listCodexRuntimes('worker-1', {
      includeArchived: true,
      suppressErrorMessage: true,
    })

    expect(client.get).toHaveBeenCalledWith('/codex-runtimes', {
      params: { workerId: 'worker-1', includeArchived: true },
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

  it('reads owner-only rate limits with an explicit refresh flag', async () => {
    const snapshot = {
      contractVersion: 1,
      runtimeId: runtime.runtimeId,
      runtimeRevision: runtime.revision,
      instanceId: 'instance-a',
      scope: 'DEFAULT_CODEX_HOME',
      state: 'AVAILABLE',
      observedAtEpochMs: 1_783_728_000_000,
      stale: false,
      limits: [],
      errorCode: null,
    } satisfies CodexRuntimeRateLimits
    vi.mocked(client.get).mockResolvedValue({ data: snapshot })

    await expect(getCodexRuntimeRateLimits(runtime.runtimeId, runtime.revision, {
      refresh: true,
      suppressErrorMessage: true,
    })).resolves.toEqual(snapshot)

    expect(client.get).toHaveBeenCalledWith(
      '/codex-runtimes/app%2Fserver%20local/revisions/2/rate-limits',
      {
        params: { refresh: true },
        suppressErrorMessage: true,
      },
    )
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

  it('archives and restores an encoded runtime revision with a CAS token', async () => {
    vi.mocked(client.post).mockResolvedValue({ data: runtime })
    const request = { expectedRoutingEpoch: 7 }

    await archiveCodexRuntime(runtime.runtimeId, runtime.revision, request)
    await unarchiveCodexRuntime(runtime.runtimeId, runtime.revision, request)

    const base = '/codex-runtimes/app%2Fserver%20local/revisions/2'
    expect(client.post).toHaveBeenNthCalledWith(1, `${base}/archive`, request)
    expect(client.post).toHaveBeenNthCalledWith(2, `${base}/unarchive`, request)
  })
})
