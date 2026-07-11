import client from './client'
import type { RX } from '@/types'
import type {
  CodexRuntime,
  CodexRuntimeAvailability,
  CodexRuntimeRateLimits,
  RegisterCodexRuntimeRequest,
  UpdateCodexRuntimeLifecycleRequest,
  UpdateCodexRuntimeRoutingRequest,
} from '@/types/codexRuntime'

export async function getCodexRuntimeRateLimits(
  runtimeId: string,
  revision: number,
  options?: { refresh?: boolean; suppressErrorMessage?: boolean },
): Promise<CodexRuntimeRateLimits> {
  const rx = (await client.get(
    `/codex-runtimes/${encodeURIComponent(runtimeId)}/revisions/${revision}/rate-limits`,
    {
      params: { refresh: options?.refresh === true },
      ...(options?.suppressErrorMessage ? { suppressErrorMessage: true } : {}),
    } as any,
  )) as unknown as RX<CodexRuntimeRateLimits>
  return rx.data
}

export async function getCodexRuntimeAvailability(
  workerId: string,
  options?: { model?: string; suppressErrorMessage?: boolean },
): Promise<CodexRuntimeAvailability> {
  const rx = (await client.get('/codex-runtimes/availability', {
    params: {
      workerId,
      ...(options?.model ? { model: options.model } : {}),
    },
    ...(options?.suppressErrorMessage ? { suppressErrorMessage: true } : {}),
  } as any)) as unknown as RX<CodexRuntimeAvailability>
  return rx.data
}

export async function listCodexRuntimes(
  workerId: string,
  options?: { includeArchived?: boolean; suppressErrorMessage?: boolean },
): Promise<CodexRuntime[]> {
  const rx = (await client.get('/codex-runtimes', {
    params: {
      workerId,
      ...(options?.includeArchived ? { includeArchived: true } : {}),
    },
    ...(options?.suppressErrorMessage ? { suppressErrorMessage: true } : {}),
  } as any)) as unknown as RX<CodexRuntime[]>
  return rx.data
}

export async function archiveCodexRuntime(
  runtimeId: string,
  revision: number,
  request: UpdateCodexRuntimeLifecycleRequest,
): Promise<CodexRuntime> {
  const rx = (await client.post(
    `/codex-runtimes/${encodeURIComponent(runtimeId)}/revisions/${revision}/archive`,
    request,
  )) as unknown as RX<CodexRuntime>
  return rx.data
}

export async function unarchiveCodexRuntime(
  runtimeId: string,
  revision: number,
  request: UpdateCodexRuntimeLifecycleRequest,
): Promise<CodexRuntime> {
  const rx = (await client.post(
    `/codex-runtimes/${encodeURIComponent(runtimeId)}/revisions/${revision}/unarchive`,
    request,
  )) as unknown as RX<CodexRuntime>
  return rx.data
}

export async function registerCodexRuntime(
  request: RegisterCodexRuntimeRequest,
): Promise<CodexRuntime> {
  const rx = (await client.post('/codex-runtimes', request)) as unknown as RX<CodexRuntime>
  return rx.data
}

export async function refreshCodexRuntime(
  runtimeId: string,
  revision: number,
): Promise<CodexRuntime> {
  const rx = (await client.post(
    `/codex-runtimes/${encodeURIComponent(runtimeId)}/revisions/${revision}/refresh`,
  )) as unknown as RX<CodexRuntime>
  return rx.data
}

export async function updateCodexRuntimeRouting(
  runtimeId: string,
  revision: number,
  request: UpdateCodexRuntimeRoutingRequest,
): Promise<CodexRuntime> {
  const rx = (await client.put(
    `/codex-runtimes/${encodeURIComponent(runtimeId)}/revisions/${revision}/routing`,
    request,
  )) as unknown as RX<CodexRuntime>
  return rx.data
}
