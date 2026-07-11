import client from './client'
import type { RX } from '@/types'
import type {
  CodexRuntime,
  CodexRuntimeAvailability,
  RegisterCodexRuntimeRequest,
  UpdateCodexRuntimeRoutingRequest,
} from '@/types/codexRuntime'

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
  options?: { suppressErrorMessage?: boolean },
): Promise<CodexRuntime[]> {
  const rx = (await client.get('/codex-runtimes', {
    params: { workerId },
    ...(options?.suppressErrorMessage ? { suppressErrorMessage: true } : {}),
  } as any)) as unknown as RX<CodexRuntime[]>
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
