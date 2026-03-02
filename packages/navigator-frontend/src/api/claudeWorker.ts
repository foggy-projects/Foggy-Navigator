import client from './client'
import type { RX, ClaudeWorker, ClaudeTask, WorkingDirectory, SkillInfo, WorkerSession, ConversationConfig, CliProcessListResponse, KillProcessResponse } from '@/types'

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
  sshUsername?: string
  sshPort?: number
  sshPassword?: string
}): Promise<ClaudeWorker> {
  const rx = (await client.post('/claude-workers', form)) as unknown as RX<ClaudeWorker>
  return rx.data
}

export async function updateWorker(
  workerId: string,
  form: { name?: string; baseUrl?: string; authToken?: string; authMode?: string; sshUsername?: string; sshPort?: number; sshPassword?: string },
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

// ===== CLI Process Management API =====

export async function listCliProcesses(workerId: string): Promise<CliProcessListResponse> {
  const rx = (await client.get(
    `/claude-workers/${workerId}/processes`,
  )) as unknown as RX<CliProcessListResponse>
  return rx.data
}

export async function killCliProcess(
  workerId: string,
  pid: number,
  force = false,
): Promise<KillProcessResponse> {
  const rx = (await client.post(
    `/claude-workers/${workerId}/processes/${pid}/kill`,
    { force },
  )) as unknown as RX<KillProcessResponse>
  return rx.data
}

// ===== Working Directory API =====

export async function listDirectoriesByWorker(workerId: string): Promise<WorkingDirectory[]> {
  const rx = (await client.get(
    `/working-directories/worker/${workerId}`,
  )) as unknown as RX<WorkingDirectory[]>
  return rx.data
}

export async function createDirectory(form: {
  workerId: string
  projectName: string
  path: string
  directoryType?: string
  parentProjectId?: string
  defaultAuthMode?: string
  defaultAuthToken?: string
  defaultBaseUrl?: string
}): Promise<WorkingDirectory> {
  const rx = (await client.post('/working-directories', form)) as unknown as RX<WorkingDirectory>
  return rx.data
}

export async function updateDirectory(
  directoryId: string,
  form: Record<string, string | undefined>,
): Promise<WorkingDirectory> {
  const rx = (await client.put(
    `/working-directories/${directoryId}`,
    form,
  )) as unknown as RX<WorkingDirectory>
  return rx.data
}

export async function deleteDirectory(directoryId: string): Promise<void> {
  await client.delete(`/working-directories/${directoryId}`)
}

export async function syncDirectoryGitInfo(directoryId: string): Promise<WorkingDirectory> {
  const rx = (await client.post(
    `/working-directories/${directoryId}/sync`,
  )) as unknown as RX<WorkingDirectory>
  return rx.data
}

export async function listSkills(directoryId: string): Promise<SkillInfo[]> {
  const rx = (await client.get(
    `/working-directories/${directoryId}/skills`,
  )) as unknown as RX<SkillInfo[]>
  return rx.data
}

export async function listChildDirectories(directoryId: string): Promise<WorkingDirectory[]> {
  const rx = (await client.get(
    `/working-directories/${directoryId}/children`,
  )) as unknown as RX<WorkingDirectory[]>
  return rx.data
}

export async function createWorktree(directoryId: string, branch: string): Promise<WorkingDirectory> {
  const rx = (await client.post(
    `/working-directories/${directoryId}/worktree`,
    { branch },
  )) as unknown as RX<WorkingDirectory>
  return rx.data
}

export async function removeWorktree(directoryId: string): Promise<void> {
  await client.delete(`/working-directories/${directoryId}/worktree`)
}

// ===== Task API =====

export async function createTask(form: {
  workerId: string
  prompt: string
  cwd?: string
  directoryId?: string
  model?: string
  maxTurns?: number
  agentTeamsJson?: string
  images?: string
  permissionMode?: string
  modelConfigId?: string
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
  agentTeamsJson?: string
  images?: string
  permissionMode?: string
  modelConfigId?: string
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
): Promise<{ content: ClaudeTask[]; totalSessions: number; page: number; size: number }> {
  const rx = (await client.get('/claude-tasks/page', {
    params: { page, size },
  })) as unknown as RX<{ content: ClaudeTask[]; totalSessions: number; page: number; size: number }>
  return rx.data
}

export async function listTasksByDirectory(
  directoryId: string,
): Promise<ClaudeTask[]> {
  const rx = (await client.get(
    `/claude-tasks/directory/${directoryId}`,
  )) as unknown as RX<ClaudeTask[]>
  return rx.data
}

export async function listTasksByDirectoryPaged(
  directoryId: string,
  page: number,
  size: number,
): Promise<{ content: ClaudeTask[]; totalSessions: number; page: number; size: number }> {
  const rx = (await client.get(`/claude-tasks/directory/${directoryId}/page`, {
    params: { page, size },
  })) as unknown as RX<{ content: ClaudeTask[]; totalSessions: number; page: number; size: number }>
  return rx.data
}

export async function respondToPermission(
  taskId: string,
  form: { permissionId: string; decision: string; denyMessage?: string; scope?: string; answers?: Record<string, string>; planAction?: string },
): Promise<{ taskId: string; permissionId: string; decision: string }> {
  const rx = (await client.post(
    `/claude-tasks/${taskId}/respond`,
    form,
  )) as unknown as RX<{ taskId: string; permissionId: string; decision: string }>
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

export async function rewindTask(
  taskId: string,
  checkpointId: string | null,
  mode: 'file_rewind' | 'conversation_fork' = 'file_rewind',
  turnIndex?: number,
): Promise<{ status: string; checkpointId?: string; taskId?: string; userPrompt?: string }> {
  const body: Record<string, unknown> = { mode }
  if (checkpointId) body.checkpointId = checkpointId
  if (turnIndex != null) body.turnIndex = turnIndex
  const rx = (await client.post(`/claude-tasks/${taskId}/rewind`, body)) as unknown as RX<{
    status: string; checkpointId?: string; taskId?: string; userPrompt?: string
  }>
  return rx.data
}

export async function scanCheckpoints(
  taskId: string,
): Promise<{ taskId: string; checkpoints: string; count: number }> {
  const rx = (await client.post(
    `/claude-tasks/${taskId}/scan-checkpoints`,
  )) as unknown as RX<{ taskId: string; checkpoints: string; count: number }>
  return rx.data
}

export async function getWorkerSessionMessageCount(
  workerId: string,
  sessionId: string,
): Promise<{ user_count: number; assistant_count: number; total: number }> {
  const rx = (await client.get(
    `/claude-tasks/worker/${workerId}/sessions/${sessionId}/message-count`,
  )) as unknown as RX<{ user_count: number; assistant_count: number; total: number }>
  return rx.data
}

export async function deleteTask(taskId: string): Promise<{ taskId: string; deleted: boolean }> {
  const rx = (await client.delete(`/claude-tasks/${taskId}`)) as unknown as RX<{
    taskId: string
    deleted: boolean
  }>
  return rx.data
}

export async function listWorkerSessions(
  workerId: string,
): Promise<WorkerSession[]> {
  const rx = (await client.get(
    `/claude-tasks/worker/${workerId}/sessions`,
  )) as unknown as RX<WorkerSession[]>
  return rx.data
}

export async function getWorkerSessionMessages(
  workerId: string,
  sessionId: string,
): Promise<{ role: string; content: string; timestamp: string }[]> {
  const rx = (await client.get(
    `/claude-tasks/worker/${workerId}/sessions/${sessionId}/messages`,
  )) as unknown as RX<{ role: string; content: string; timestamp: string }[]>
  return rx.data
}

export async function syncWorkerSessions(
  workerId: string,
): Promise<{ synced: number; total: number }> {
  const rx = (await client.post(
    `/claude-tasks/worker/${workerId}/sessions/sync`,
  )) as unknown as RX<{ synced: number; total: number }>
  return rx.data
}

export async function listActiveTasks(): Promise<ClaudeTask[]> {
  const rx = (await client.get('/claude-tasks/active')) as unknown as RX<ClaudeTask[]>
  return rx.data
}

// ===== Conversation Config API =====

export async function updateConversationTags(
  sessionId: string,
  tags: string[],
): Promise<ConversationConfig> {
  const rx = (await client.patch(`/claude-tasks/conversations/${sessionId}/tags`, {
    tags,
  })) as unknown as RX<ConversationConfig>
  return rx.data
}

export async function updateConversationPin(
  sessionId: string,
  pinned: boolean,
): Promise<ConversationConfig> {
  const rx = (await client.patch(`/claude-tasks/conversations/${sessionId}/pin`, {
    pinned,
  })) as unknown as RX<ConversationConfig>
  return rx.data
}

export async function updateConversationTitle(
  sessionId: string,
  title: string,
): Promise<ConversationConfig> {
  const rx = (await client.patch(`/claude-tasks/conversations/${sessionId}/title`, {
    title,
  })) as unknown as RX<ConversationConfig>
  return rx.data
}

export async function bindConversationAuth(
  sessionId: string,
  form: { authMode: string; authToken: string; baseUrl?: string },
): Promise<ConversationConfig> {
  const rx = (await client.post(
    `/claude-tasks/conversations/${sessionId}/bind-auth`,
    form,
  )) as unknown as RX<ConversationConfig>
  return rx.data
}

export async function updateConversationAuth(
  sessionId: string,
  form: { authMode: string; authToken: string; baseUrl?: string },
): Promise<ConversationConfig> {
  const rx = (await client.patch(
    `/claude-tasks/conversations/${sessionId}/auth`,
    form,
  )) as unknown as RX<ConversationConfig>
  return rx.data
}

export async function listConversationConfigs(
  sessionIds: string[],
): Promise<ConversationConfig[]> {
  const rx = (await client.get('/claude-tasks/conversation-configs', {
    params: { sessionIds: sessionIds.join(',') },
  })) as unknown as RX<ConversationConfig[]>
  return rx.data
}

export async function batchBindConversationAuth(form: {
  sessionIds: string[]
  authMode: string
  authToken: string
  baseUrl?: string
  skipExisting?: boolean
  modelConfigId?: string
}): Promise<{ bound: number; total: number }> {
  const rx = (await client.post(
    '/claude-tasks/conversations/batch-bind-auth',
    form,
  )) as unknown as RX<{ bound: number; total: number }>
  return rx.data
}

export async function archiveConversation(
  sessionId: string,
): Promise<ConversationConfig> {
  const rx = (await client.post(
    `/claude-tasks/conversations/${sessionId}/archive`,
  )) as unknown as RX<ConversationConfig>
  return rx.data
}

export async function unarchiveConversation(
  sessionId: string,
): Promise<ConversationConfig> {
  const rx = (await client.post(
    `/claude-tasks/conversations/${sessionId}/unarchive`,
  )) as unknown as RX<ConversationConfig>
  return rx.data
}
