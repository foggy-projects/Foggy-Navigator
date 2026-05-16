/**
 * Unified Task API (/api/v1/tasks)
 *
 * Agent-agnostic task operations. Frontend should gradually migrate
 * from /api/v1/claude-tasks to these endpoints.
 *
 * Backend now routes by a richer execution context:
 * logical agentId, providerType, sessionId, and modelConfigId may all participate.
 */
import client from './client'
import type { RX, ClaudeTask, SessionSearchResult } from '@/types'

// Re-use ClaudeTask type since DispatchTaskDTO is a superset with the same field names.
// Additional fields (agentId, providerType, codexThreadId) are optional.
export type DispatchTask = ClaudeTask & {
  agentId?: string
  providerType?: string
  userId?: string
  codexThreadId?: string
  contextId?: string
}

type TaskPageResult = {
  content: ClaudeTask[]
  totalSessions: number
  page: number
  size: number
}

const taskPageRequests = new Map<string, Promise<TaskPageResult>>()

export type ForwardTargetMode = 'NEW_SESSION' | 'EXISTING_SESSION'

export interface ForwardSessionForm {
  sourceSessionId: string
  sourceMessageId: string
  targetMode?: ForwardTargetMode
  targetSessionId?: string
  workerId?: string
  directoryId?: string
  cwd?: string
  prompt?: string
  model?: string
  modelConfigId?: string
  permissionMode?: string
  maxTurns?: number
  agentTeamsConfigId?: string
  agentTeamsJson?: string
  milestoneId?: string
  images?: string
}

export interface ForwardSessionResult {
  relationId: number
  targetMode?: ForwardTargetMode
  sourceSessionId: string
  sourceMessageId: string
  targetSessionId: string
  task: DispatchTask
}

export type RewindTaskResult = {
  status: string
  checkpointId?: string
  taskId?: string
  userPrompt?: string
  turnIndex?: number
  claudeSessionId?: string
}

function normalizeImages(images?: string | string[]): string[] | undefined {
  if (images == null) {
    return undefined
  }
  if (Array.isArray(images)) {
    const normalized = images
      .map((item) => item?.trim())
      .filter((item): item is string => !!item)
    return normalized.length > 0 ? normalized : undefined
  }
  const normalized = images.trim()
  if (!normalized) {
    return undefined
  }
  // Compatibility: legacy callers still pass a JSON string payload here.
  // Wrap it as a single list item so Spring can bind List<String>, while the
  // backend continues to forward the original JSON string unchanged.
  return [normalized]
}

function normalizeTaskForm<T extends { images?: string | string[] }>(
  form: T,
): Omit<T, 'images'> & { images?: string[] } {
  const images = normalizeImages(form.images)
  return images ? { ...form, images } : { ...form, images: undefined }
}

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
  images?: string | string[]
  permissionMode?: string
  modelConfigId?: string
  agentId?: string
  contextId?: string
}): Promise<DispatchTask> {
  const rx = (await client.post('/tasks', normalizeTaskForm(form))) as unknown as RX<DispatchTask>
  return rx.data
}

export async function forwardSessionUnified(
  form: ForwardSessionForm,
): Promise<ForwardSessionResult> {
  const rx = (await client.post('/session-relations/forward', form)) as unknown as RX<ForwardSessionResult>
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
 * Unsupported agents return 400 with a clear message.
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

// ── Phase 3: 统一端点扩展 ──

/**
 * Resume a task (continue an existing Claude/Codex session).
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
  images?: string | string[]
  permissionMode?: string
  modelConfigId?: string
  agentId?: string
}): Promise<DispatchTask> {
  const rx = (await client.post('/tasks/resume', normalizeTaskForm(form))) as unknown as RX<DispatchTask>
  return rx.data
}

/**
 * Delete a task.
 */
export async function deleteTaskUnified(taskId: string): Promise<void> {
  await client.delete(`/tasks/${taskId}`)
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
 * List tasks with pagination.
 */
export async function listTasksPagedUnified(
  page: number,
  size: number,
  state?: string,
): Promise<TaskPageResult> {
  const key = `tasks:${page}:${size}:${state || ''}`
  const existing = taskPageRequests.get(key)
  if (existing) return existing

  const request = (async () => {
    const rx = (await client.get('/tasks/page', {
      params: { page, size, ...(state ? { state } : {}) },
    })) as unknown as RX<TaskPageResult>
    return rx.data
  })()
  taskPageRequests.set(key, request)
  request.then(
    () => taskPageRequests.delete(key),
    () => taskPageRequests.delete(key),
  )
  return request
}

/**
 * Load all tasks for sessions in a specific interaction state.
 * The unified `/tasks/page` endpoint paginates by session, so callers that
 * need the complete set must walk all pages.
 */
export async function listTasksByStateUnified(
  state: string,
  pageSize = 100,
): Promise<DispatchTask[]> {
  const tasks: DispatchTask[] = []
  let page = 0
  let totalSessions = 0

  do {
    const result = await listTasksPagedUnified(page, pageSize, state)
    tasks.push(...(result.content as DispatchTask[]))
    totalSessions = result.totalSessions
    page += 1
  } while (page * pageSize < totalSessions)

  return tasks
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

/**
 * List tasks by directory.
 */
export async function listTasksByDirectoryUnified(
  directoryId: string,
): Promise<DispatchTask[]> {
  const rx = (await client.get(`/tasks/directory/${directoryId}`)) as unknown as RX<DispatchTask[]>
  return rx.data
}

/**
 * List tasks by directory with pagination.
 */
export async function listTasksByDirectoryPagedUnified(
  directoryId: string,
  page: number,
  size: number,
  state?: string,
): Promise<TaskPageResult> {
  const key = `directory:${directoryId}:${page}:${size}:${state || ''}`
  const existing = taskPageRequests.get(key)
  if (existing) return existing

  const request = (async () => {
    const rx = (await client.get(`/tasks/directory/${directoryId}/page`, {
      params: { page, size, ...(state ? { state } : {}) },
    })) as unknown as RX<TaskPageResult>
    return rx.data
  })()
  taskPageRequests.set(key, request)
  request.then(
    () => taskPageRequests.delete(key),
    () => taskPageRequests.delete(key),
  )
  return request
}

// ── Worker Session 查询（统一端点） ──

export type WorkerSession = {
  session_id: string
  project: string
  model: string
  [key: string]: unknown
}

/**
 * List sessions on a Worker.
 */
export async function listWorkerSessionsUnified(
  workerId: string,
): Promise<WorkerSession[]> {
  const rx = (await client.get(
    `/tasks/workers/${workerId}/sessions`,
  )) as unknown as RX<WorkerSession[]>
  return rx.data
}

/**
 * Get session message count breakdown.
 */
export async function getWorkerSessionMessageCountUnified(
  workerId: string,
  sessionId: string,
): Promise<{ user_count: number; assistant_count: number; total: number }> {
  const rx = (await client.get(
    `/tasks/workers/${workerId}/sessions/${sessionId}/message-count`,
  )) as unknown as RX<{ user_count: number; assistant_count: number; total: number }>
  return rx.data
}

/**
 * Get session messages with optional pagination.
 */
export async function getWorkerSessionMessagesUnified(
  workerId: string,
  sessionId: string,
  offset?: number,
  limit?: number,
): Promise<{ role: string; content: string; timestamp: string }[]> {
  const params: Record<string, number> = {}
  if (offset != null) params.offset = offset
  if (limit != null) params.limit = limit
  const rx = (await client.get(
    `/tasks/workers/${workerId}/sessions/${sessionId}/messages`,
    { params },
  )) as unknown as RX<{ role: string; content: string; timestamp: string }[]>
  return rx.data
}

/**
 * Trigger Worker to re-scan sessions and sync locally.
 */
export async function syncWorkerSessionsUnified(
  workerId: string,
): Promise<{ synced: number; total: number }> {
  const rx = (await client.post(
    `/tasks/workers/${workerId}/sessions/sync`,
  )) as unknown as RX<{ synced: number; total: number }>
  return rx.data
}
