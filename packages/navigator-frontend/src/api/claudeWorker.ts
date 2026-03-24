import client from './client'
import {
  cancelTaskUnified,
  createTaskUnified,
  deleteTaskUnified,
  getTaskUnified,
  listTasksByDirectoryPagedUnified,
  listTasksByDirectoryUnified,
  listTasksPagedUnified,
  listTasksUnified,
  reconnectTaskUnified,
  respondToTaskUnified,
  resumeTaskUnified,
  resyncTaskUnified,
  scanCheckpointsUnified,
  searchSessionsUnified,
} from './unifiedTask'
import type { RX, ClaudeWorker, ClaudeTask, WorkingDirectory, SkillInfo, WorkerSession, ConversationConfig, CliProcessListResponse, KillProcessResponse, AgentTeamsConfig, SessionSearchPage } from '@/types'

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
  codeServerPublicUrl?: string
  codeServerInternalUrl?: string
  codeServerPassword?: string
  codeServerFolderPrefix?: string
  codexConfig?: { baseUrl?: string; authToken?: string; model?: string }
}): Promise<ClaudeWorker> {
  const rx = (await client.post('/claude-workers', form)) as unknown as RX<ClaudeWorker>
  return rx.data
}

export async function updateWorker(
  workerId: string,
  form: { name?: string; baseUrl?: string; authToken?: string; authMode?: string; sshUsername?: string; sshPort?: number; sshPassword?: string; codeServerPublicUrl?: string; codeServerInternalUrl?: string; codeServerPassword?: string; codeServerFolderPrefix?: string; codexConfig?: { baseUrl?: string; authToken?: string; model?: string } },
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

export async function getCodeServerPassword(workerId: string): Promise<{ password: string }> {
  const rx = (await client.get(`/claude-workers/${workerId}/code-server-password`)) as unknown as RX<{ password: string }>
  return rx.data
}

// ===== Platform Skills API =====

export async function syncWorkerSkills(workerId: string): Promise<{ status: string }> {
  const rx = (await client.post(`/claude-workers/${workerId}/sync-skills`)) as unknown as RX<{ status: string }>
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

// ===== Agent Teams Config API =====

export async function listAgentTeamsConfigs(directoryId: string): Promise<AgentTeamsConfig[]> {
  const rx = (await client.get(
    `/working-directories/${directoryId}/agent-teams-configs`,
  )) as unknown as RX<AgentTeamsConfig[]>
  return rx.data
}

export async function createAgentTeamsConfig(
  directoryId: string,
  form: { name: string; config: string; isDefault?: boolean },
): Promise<AgentTeamsConfig> {
  const rx = (await client.post(
    `/working-directories/${directoryId}/agent-teams-configs`,
    form,
  )) as unknown as RX<AgentTeamsConfig>
  return rx.data
}

export async function updateAgentTeamsConfig(
  directoryId: string,
  configId: string,
  form: { name?: string; config?: string; isDefault?: boolean },
): Promise<AgentTeamsConfig> {
  const rx = (await client.put(
    `/working-directories/${directoryId}/agent-teams-configs/${configId}`,
    form,
  )) as unknown as RX<AgentTeamsConfig>
  return rx.data
}

export async function deleteAgentTeamsConfig(
  directoryId: string,
  configId: string,
): Promise<void> {
  await client.delete(`/working-directories/${directoryId}/agent-teams-configs/${configId}`)
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
  agentTeamsConfigId?: string
  images?: string
  permissionMode?: string
  modelConfigId?: string
}): Promise<ClaudeTask> {
  return await createTaskUnified(form) as unknown as ClaudeTask
}

export async function resumeTask(form: {
  workerId: string
  claudeSessionId?: string
  codexThreadId?: string
  prompt: string
  cwd?: string
  directoryId?: string
  sessionId?: string
  model?: string
  maxTurns?: number
  agentTeamsJson?: string
  agentTeamsConfigId?: string
  images?: string
  permissionMode?: string
  modelConfigId?: string
}): Promise<ClaudeTask> {
  return await resumeTaskUnified(form) as unknown as ClaudeTask
}

export async function getTask(taskId: string): Promise<ClaudeTask> {
  const task = await getTaskUnified(taskId)
  if (!task) {
    throw new Error(`Task not found: ${taskId}`)
  }
  return task as unknown as ClaudeTask
}

export async function listTasks(): Promise<ClaudeTask[]> {
  return await listTasksUnified() as unknown as ClaudeTask[]
}

export async function listTasksPaged(
  page: number,
  size: number,
  state?: string,
): Promise<{ content: ClaudeTask[]; totalSessions: number; page: number; size: number }> {
  return await listTasksPagedUnified(page, size, state)
}

export async function searchSessions(params: {
  keyword?: string
  workerId?: string
  directoryId?: string
  page?: number
  size?: number
}): Promise<SessionSearchPage> {
  return await searchSessionsUnified(
    params.keyword,
    params.workerId,
    params.directoryId,
    params.page,
    params.size,
  ) as SessionSearchPage
}

export async function listTasksByDirectory(
  directoryId: string,
): Promise<ClaudeTask[]> {
  return await listTasksByDirectoryUnified(directoryId) as unknown as ClaudeTask[]
}

export async function listTasksByDirectoryPaged(
  directoryId: string,
  page: number,
  size: number,
  state?: string,
): Promise<{ content: ClaudeTask[]; totalSessions: number; page: number; size: number }> {
  return await listTasksByDirectoryPagedUnified(directoryId, page, size, state)
}

export async function respondToPermission(
  taskId: string,
  form: { permissionId: string; decision: string; denyMessage?: string; scope?: string; answers?: Record<string, string>; planAction?: string },
): Promise<{ taskId: string; permissionId: string; decision: string }> {
  await respondToTaskUnified(taskId, form)
  return {
    taskId,
    permissionId: form.permissionId,
    decision: form.decision,
  }
}

export async function reconnectTask(
  taskId: string,
): Promise<{ taskId: string; status: string }> {
  await reconnectTaskUnified(taskId)
  return { taskId, status: 'RECONNECTED' }
}

export async function resyncTask(
  taskId: string,
): Promise<import('@/types').ResyncResult> {
  return await resyncTaskUnified(taskId) as import('@/types').ResyncResult
}

export async function abortTask(
  taskId: string,
): Promise<{ taskId: string; status: string }> {
  await cancelTaskUnified(taskId)
  return { taskId, status: 'ABORTED' }
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
  if (!rx.data) {
    throw new Error(rx.message || '回退失败')
  }
  return rx.data
}

export async function scanCheckpoints(
  taskId: string,
): Promise<{ taskId: string; checkpoints: string; count: number }> {
  return await scanCheckpointsUnified(taskId)
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
  await deleteTaskUnified(taskId)
  return { taskId, deleted: true }
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

export async function getWorkerSessionMessagesPaged(
  workerId: string,
  sessionId: string,
  offset: number,
  limit: number,
): Promise<{ role: string; content: string; timestamp: string }[]> {
  const rx = (await client.get(
    `/claude-tasks/worker/${workerId}/sessions/${sessionId}/messages`,
    { params: { offset, limit } },
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
  return await listTasksUnified() as unknown as ClaudeTask[]
}

export async function listAwaitingReplyTasks(): Promise<ClaudeTask[]> {
  const rx = (await client.get('/claude-tasks/awaiting-reply')) as unknown as RX<ClaudeTask[]>
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

export async function holdConversation(
  sessionId: string,
): Promise<ConversationConfig> {
  const rx = (await client.post(
    `/claude-tasks/conversations/${sessionId}/hold`,
  )) as unknown as RX<ConversationConfig>
  return rx.data
}

export async function unholdConversation(
  sessionId: string,
): Promise<ConversationConfig> {
  const rx = (await client.post(
    `/claude-tasks/conversations/${sessionId}/unhold`,
  )) as unknown as RX<ConversationConfig>
  return rx.data
}
