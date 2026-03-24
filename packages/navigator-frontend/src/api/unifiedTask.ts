/**
 * Unified Task API (/api/v1/tasks)
 *
 * Agent-agnostic task operations. Frontend should gradually migrate
 * from /api/v1/claude-tasks to these endpoints.
 *
 * Backend resolves agentId from modelConfigId automatically,
 * so frontend does NOT need to know if it's Claude or Codex.
 */
import client from './client'
import type { RX } from '@/types'
import type { ClaudeTask } from '@/types'

// Re-use ClaudeTask type since DispatchTaskDTO is a superset with the same field names.
// Additional fields (agentId, providerType, codexThreadId) are optional.
export type DispatchTask = ClaudeTask & {
  agentId?: string
  providerType?: string
  userId?: string
  codexThreadId?: string
  contextId?: string
}

/**
 * Create a task via unified dispatch.
 * Backend automatically resolves agentId from modelConfigId.
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
  images?: string
  permissionMode?: string
  modelConfigId?: string
  agentId?: string
  contextId?: string
  codexThreadId?: string
}): Promise<DispatchTask> {
  const rx = (await client.post('/tasks', form)) as unknown as RX<DispatchTask>
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
