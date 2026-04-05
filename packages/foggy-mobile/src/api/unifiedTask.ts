/**
 * Unified Task API (/api/v1/tasks)
 *
 * Agent-agnostic task operations. Replaces legacy /api/v1/claude-tasks endpoints.
 * Aligned with PC frontend: packages/navigator-frontend/src/api/unifiedTask.ts
 */
import client from './client'
import type {
  RX,
  DispatchTask,
  RewindTaskResult,
  SessionSearchResult,
  SessionPageResult,
} from './types'

/**
 * Create a task via unified dispatch.
 */
export async function createTaskUnified(form: {
  workerId: string
  prompt: string
  cwd?: string
  directoryId?: string
  model?: string
  maxTurns?: number
  agentTeamsJson?: string
  agentTeamsConfigId?: string
  images?: string[]
  permissionMode?: string
  modelConfigId?: string
  agentId?: string
  contextId?: string
}): Promise<DispatchTask> {
  const rx = (await client.post('/tasks', form)) as unknown as RX<DispatchTask>
  return rx.data
}

/**
 * Resume a task (continue an existing session).
 */
export async function resumeTaskUnified(form: {
  workerId: string
  prompt: string
  cwd?: string
  directoryId?: string
  sessionId?: string
  model?: string
  maxTurns?: number
  agentTeamsJson?: string
  agentTeamsConfigId?: string
  images?: string[]
  permissionMode?: string
  modelConfigId?: string
  agentId?: string
}): Promise<DispatchTask> {
  const rx = (await client.post('/tasks/resume', form)) as unknown as RX<DispatchTask>
  return rx.data
}

/**
 * Get a single task by ID.
 */
export async function getTaskUnified(taskId: string): Promise<DispatchTask | null> {
  const rx = (await client.get(`/tasks/${taskId}`)) as unknown as RX<DispatchTask>
  return rx.data
}

/**
 * List tasks by session or list active tasks (if no sessionId).
 */
export async function listTasksUnified(sessionId?: string): Promise<DispatchTask[]> {
  const params = sessionId ? { sessionId } : {}
  const rx = (await client.get('/tasks', { params })) as unknown as RX<DispatchTask[]>
  return rx.data
}

/**
 * Cancel a task.
 */
export async function cancelTaskUnified(taskId: string, agentId?: string): Promise<void> {
  await client.post(`/tasks/${taskId}/cancel`, agentId ? { agentId } : {})
}

/**
 * Respond to a permission request / user question.
 */
export async function respondToTaskUnified(
  taskId: string,
  form: {
    permissionId: string
    decision: string
    denyMessage?: string
    scope?: string
    answers?: Record<string, string>
    planAction?: string
  },
): Promise<void> {
  await client.post(`/tasks/${taskId}/respond`, form)
}

/**
 * Reconnect task SSE stream.
 */
export async function reconnectTaskUnified(taskId: string): Promise<void> {
  await client.post(`/tasks/${taskId}/reconnect`)
}

/**
 * Resync task state (smart: SSE reconnect or JSONL repair).
 */
export async function resyncTaskUnified(taskId: string): Promise<unknown> {
  const rx = (await client.post(`/tasks/${taskId}/resync`)) as unknown as RX<unknown>
  return rx.data
}

/**
 * Rewind task to a checkpoint.
 */
export async function rewindTaskUnified(
  taskId: string,
  body: {
    checkpointId?: string
    mode?: string
    turnIndex?: number
  },
): Promise<RewindTaskResult | null> {
  const rx = (await client.post(`/tasks/${taskId}/rewind`, body)) as unknown as RX<RewindTaskResult | null>
  return rx.data
}

/**
 * Delete a task.
 */
export async function deleteTaskUnified(taskId: string): Promise<void> {
  await client.delete(`/tasks/${taskId}`)
}

/**
 * Scan session checkpoints for a task.
 */
export async function scanCheckpointsUnified(
  taskId: string,
): Promise<{ taskId: string; checkpoints: string; count: number }> {
  const rx = (await client.post(`/tasks/${taskId}/scan-checkpoints`)) as unknown as RX<{
    taskId: string
    checkpoints: string
    count: number
  }>
  return rx.data
}

/**
 * List tasks with pagination (by session dimension).
 */
export async function listTasksPagedUnified(
  page: number,
  size: number,
  state?: string,
): Promise<SessionPageResult<DispatchTask>> {
  const rx = (await client.get('/tasks/page', {
    params: { page, size, ...(state ? { state } : {}) },
  })) as unknown as RX<SessionPageResult<DispatchTask>>
  return rx.data
}

/**
 * List tasks by directory with pagination (by session dimension).
 */
export async function listTasksByDirPagedUnified(
  directoryId: string,
  page: number,
  size: number,
  state?: string,
): Promise<SessionPageResult<DispatchTask>> {
  const rx = (await client.get(`/tasks/directory/${directoryId}/page`, {
    params: { page, size, ...(state ? { state } : {}) },
  })) as unknown as RX<SessionPageResult<DispatchTask>>
  return rx.data
}

/**
 * List tasks by directory (non-paginated).
 */
export async function listTasksByDirectoryUnified(
  directoryId: string,
): Promise<DispatchTask[]> {
  const rx = (await client.get(`/tasks/directory/${directoryId}`)) as unknown as RX<DispatchTask[]>
  return rx.data
}

/**
 * Search sessions (keyword + worker/directory filter).
 */
export async function searchSessionsUnified(
  keyword?: string,
  workerId?: string,
  directoryId?: string,
  page = 0,
  size = 20,
): Promise<{ results: SessionSearchResult[]; total: number; page: number; size: number }> {
  const rx = (await client.get('/tasks/search', {
    params: {
      ...(keyword ? { keyword } : {}),
      ...(workerId ? { workerId } : {}),
      ...(directoryId ? { directoryId } : {}),
      page,
      size,
    },
  })) as unknown as RX<{ results: SessionSearchResult[]; total: number; page: number; size: number }>
  return rx.data
}
