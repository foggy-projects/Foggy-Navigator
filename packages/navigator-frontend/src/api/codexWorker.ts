import client from './client'
import type { RX, CodexWorker, CodexTask } from '@/types'

// ===== Codex Worker API =====

export async function listCodexWorkers(): Promise<CodexWorker[]> {
  const rx = (await client.get('/codex-workers')) as unknown as RX<CodexWorker[]>
  return rx.data
}

export async function getCodexWorker(workerId: string): Promise<CodexWorker> {
  const rx = (await client.get(`/codex-workers/${workerId}`)) as unknown as RX<CodexWorker>
  return rx.data
}

export async function registerCodexWorker(form: {
  name: string
  baseUrl: string
  authToken?: string
}): Promise<CodexWorker> {
  const rx = (await client.post('/codex-workers', form)) as unknown as RX<CodexWorker>
  return rx.data
}

export async function updateCodexWorker(
  workerId: string,
  form: { name?: string; baseUrl?: string; authToken?: string },
): Promise<CodexWorker> {
  const rx = (await client.put(`/codex-workers/${workerId}`, form)) as unknown as RX<CodexWorker>
  return rx.data
}

export async function deleteCodexWorker(workerId: string): Promise<void> {
  await client.delete(`/codex-workers/${workerId}`)
}

export async function triggerCodexHealthCheck(workerId: string): Promise<CodexWorker> {
  const rx = (await client.post(
    `/codex-workers/${workerId}/health-check`,
  )) as unknown as RX<CodexWorker>
  return rx.data
}

// ===== Codex Task API =====

export async function createCodexTask(form: {
  workerId: string
  prompt: string
  cwd?: string
  directoryId?: string
  model?: string
  maxTurns?: number
  codexThreadId?: string
  modelConfigId?: string
}): Promise<CodexTask> {
  const rx = (await client.post('/codex-tasks', form)) as unknown as RX<CodexTask>
  return rx.data
}

export async function getCodexTask(taskId: string): Promise<CodexTask> {
  const rx = (await client.get(`/codex-tasks/${taskId}`)) as unknown as RX<CodexTask>
  return rx.data
}

export async function listCodexTasks(): Promise<CodexTask[]> {
  const rx = (await client.get('/codex-tasks')) as unknown as RX<CodexTask[]>
  return rx.data
}

export async function abortCodexTask(
  taskId: string,
): Promise<{ taskId: string; status: string }> {
  const rx = (await client.post(`/codex-tasks/${taskId}/abort`)) as unknown as RX<{
    taskId: string
    status: string
  }>
  return rx.data
}

export async function reconnectCodexTask(
  taskId: string,
): Promise<{ taskId: string; status: string }> {
  const rx = (await client.post(`/codex-tasks/${taskId}/reconnect`)) as unknown as RX<{
    taskId: string
    status: string
  }>
  return rx.data
}
