import client from './client'
import type { RX, ClaudeWorker, ClaudeTask } from '@/types'

// ===== Worker API =====

export async function listWorkers(): Promise<ClaudeWorker[]> {
  const rx = (await client.get('/claude-workers')) as unknown as RX<ClaudeWorker[]>
  return rx.data
}

export async function getWorker(workerId: string): Promise<ClaudeWorker> {
  const rx = (await client.get(`/claude-workers/${workerId}`)) as unknown as RX<ClaudeWorker>
  return rx.data
}

export async function registerWorker(form: {
  name: string
  baseUrl: string
  authToken: string
  authMode?: string
}): Promise<ClaudeWorker> {
  const rx = (await client.post('/claude-workers', form)) as unknown as RX<ClaudeWorker>
  return rx.data
}

export async function updateWorker(
  workerId: string,
  form: { name?: string; baseUrl?: string; authToken?: string; authMode?: string },
): Promise<ClaudeWorker> {
  const rx = (await client.put(`/claude-workers/${workerId}`, form)) as unknown as RX<ClaudeWorker>
  return rx.data
}

export async function deleteWorker(workerId: string): Promise<void> {
  await client.delete(`/claude-workers/${workerId}`)
}

export async function triggerHealthCheck(workerId: string): Promise<ClaudeWorker> {
  const rx = (await client.post(
    `/claude-workers/${workerId}/health-check`,
  )) as unknown as RX<ClaudeWorker>
  return rx.data
}

// ===== Task API =====

export async function createTask(form: {
  workerId: string
  prompt: string
  cwd?: string
}): Promise<ClaudeTask> {
  const rx = (await client.post('/claude-tasks', form)) as unknown as RX<ClaudeTask>
  return rx.data
}

export async function resumeTask(form: {
  workerId: string
  claudeSessionId: string
  prompt: string
  cwd?: string
}): Promise<ClaudeTask> {
  const rx = (await client.post('/claude-tasks/resume', form)) as unknown as RX<ClaudeTask>
  return rx.data
}

export async function getTask(taskId: string): Promise<ClaudeTask> {
  const rx = (await client.get(`/claude-tasks/${taskId}`)) as unknown as RX<ClaudeTask>
  return rx.data
}

export async function listTasks(): Promise<ClaudeTask[]> {
  const rx = (await client.get('/claude-tasks')) as unknown as RX<ClaudeTask[]>
  return rx.data
}

export async function listTasksPaged(
  page: number,
  size: number,
): Promise<{ content: ClaudeTask[]; totalElements: number; totalPages: number }> {
  const rx = (await client.get('/claude-tasks/page', {
    params: { page, size },
  })) as unknown as RX<{ content: ClaudeTask[]; totalElements: number; totalPages: number }>
  return rx.data
}

export async function abortTask(
  taskId: string,
): Promise<{ taskId: string; status: string }> {
  const rx = (await client.post(`/claude-tasks/${taskId}/abort`)) as unknown as RX<{
    taskId: string
    status: string
  }>
  return rx.data
}

export async function listWorkerSessions(
  workerId: string,
): Promise<Record<string, unknown>[]> {
  const rx = (await client.get(
    `/claude-tasks/worker/${workerId}/sessions`,
  )) as unknown as RX<Record<string, unknown>[]>
  return rx.data
}
