import client from './client'
import type { RX, ClaudeWorker, ClaudeTask, WorkingDirectory, PageResult } from './types'

// ===== Worker API =====

export async function listWorkers(): Promise<ClaudeWorker[]> {
  const rx = (await client.get('/claude-workers')) as unknown as RX<ClaudeWorker[]>
  return rx.data
}

export async function triggerHealthCheck(workerId: string): Promise<ClaudeWorker> {
  const rx = (await client.post(`/claude-workers/${workerId}/health-check`)) as unknown as RX<ClaudeWorker>
  return rx.data
}

// ===== Working Directory API =====

export async function listDirectoriesByWorker(workerId: string): Promise<WorkingDirectory[]> {
  const rx = (await client.get(`/working-directories/worker/${workerId}`)) as unknown as RX<WorkingDirectory[]>
  return rx.data
}

export async function syncDirectoryGitInfo(directoryId: string): Promise<WorkingDirectory> {
  const rx = (await client.post(`/working-directories/${directoryId}/sync`)) as unknown as RX<WorkingDirectory>
  return rx.data
}

// ===== Task API =====

export async function createTask(form: {
  workerId: string
  prompt: string
  cwd?: string
  directoryId?: string
  model?: string
  maxTurns?: number
}): Promise<ClaudeTask> {
  const rx = (await client.post('/claude-tasks', form)) as unknown as RX<ClaudeTask>
  return rx.data
}

export async function resumeTask(form: {
  workerId: string
  claudeSessionId: string
  prompt: string
  cwd?: string
  directoryId?: string
  sessionId?: string
  model?: string
  maxTurns?: number
}): Promise<ClaudeTask> {
  const rx = (await client.post('/claude-tasks/resume', form)) as unknown as RX<ClaudeTask>
  return rx.data
}

export async function getTask(taskId: string): Promise<ClaudeTask> {
  const rx = (await client.get(`/claude-tasks/${taskId}`)) as unknown as RX<ClaudeTask>
  return rx.data
}

export async function listTasksByDirectoryPaged(
  directoryId: string,
  page: number,
  size: number,
): Promise<PageResult<ClaudeTask>> {
  const rx = (await client.get(`/claude-tasks/directory/${directoryId}/page`, {
    params: { page, size },
  })) as unknown as RX<PageResult<ClaudeTask>>
  return rx.data
}

export async function abortTask(taskId: string): Promise<{ taskId: string; status: string }> {
  const rx = (await client.post(`/claude-tasks/${taskId}/abort`)) as unknown as RX<{ taskId: string; status: string }>
  return rx.data
}

export async function deleteTask(taskId: string): Promise<{ taskId: string; deleted: boolean }> {
  const rx = (await client.delete(`/claude-tasks/${taskId}`)) as unknown as RX<{ taskId: string; deleted: boolean }>
  return rx.data
}
