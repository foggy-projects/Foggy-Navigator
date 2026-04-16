import client from './client'
import {
  cancelTaskUnified,
  createTaskUnified,
  deleteTaskUnified,
  getTaskUnified,
  listTasksByStateUnified,
  listTasksByDirectoryPagedUnified,
  listTasksByDirectoryUnified,
  listTasksPagedUnified,
  listTasksUnified,
  reconnectTaskUnified,
  rewindTaskUnified,
  respondToTaskUnified,
  resumeTaskUnified,
  resyncTaskUnified,
  scanCheckpointsUnified,
  searchSessionsUnified,
  listWorkerSessionsUnified,
  getWorkerSessionMessageCountUnified,
  getWorkerSessionMessagesUnified,
  syncWorkerSessionsUnified,
} from './unifiedTask'
import type { RX, ClaudeWorker, ClaudeTask, WorkingDirectory, SkillInfo, WorkerSession, ConversationConfig, CliProcessListResponse, KillProcessResponse, AgentTeamsConfig, SessionSearchPage, DirectoryMilestone, MilestonePageResult } from '@/types'

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
    { suppressErrorMessage: true } as any,
  )) as unknown as RX<CliProcessListResponse>
  return rx.data
}

export async function listCodexCliProcesses(
  workerId: string,
  options?: { suppressErrorMessage?: boolean },
): Promise<CliProcessListResponse> {
  const rx = (await client.get(
    `/codex-workers/${workerId}/processes`,
    { suppressErrorMessage: options?.suppressErrorMessage } as any,
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

export async function killCodexCliProcess(
  workerId: string,
  pid: number,
  force = false,
): Promise<KillProcessResponse> {
  const rx = (await client.post(
    `/codex-workers/${workerId}/processes/${pid}/kill`,
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

// ===== Milestone CRUD API =====

export async function listMilestones(directoryId: string): Promise<DirectoryMilestone[]> {
  const rx = (await client.get(
    `/working-directories/${directoryId}/milestones`,
  )) as unknown as RX<DirectoryMilestone[]>
  return rx.data
}

export async function listMilestonesPaged(
  directoryId: string,
  params: {
    page: number
    size: number
    sortBy?: 'startAt' | 'endAt' | 'name' | 'status'
    sortDir?: 'asc' | 'desc'
  },
): Promise<MilestonePageResult> {
  const rx = (await client.get(
    `/working-directories/${directoryId}/milestones/paged`,
    { params },
  )) as unknown as RX<MilestonePageResult>
  return rx.data
}

export async function addMilestoneApi(
  directoryId: string,
  form: Partial<DirectoryMilestone>,
): Promise<DirectoryMilestone> {
  const rx = (await client.post(
    `/working-directories/${directoryId}/milestones`,
    form,
  )) as unknown as RX<DirectoryMilestone>
  return rx.data
}

export async function updateMilestoneApi(
  directoryId: string,
  milestoneId: string,
  form: Partial<DirectoryMilestone>,
): Promise<DirectoryMilestone> {
  const rx = (await client.put(
    `/working-directories/${directoryId}/milestones/${milestoneId}`,
    form,
  )) as unknown as RX<DirectoryMilestone>
  return rx.data
}

export interface MilestoneDeleteResult {
  milestoneId: string
  sessionCount: number
  deleted: boolean
}

export async function getMilestoneSessionCount(
  directoryId: string,
  milestoneId: string,
): Promise<{ sessionCount: number }> {
  const rx = (await client.get(
    `/working-directories/${directoryId}/milestones/${milestoneId}/session-count`,
  )) as unknown as RX<{ sessionCount: number }>
  return rx.data
}

export async function deleteMilestoneApi(
  directoryId: string,
  milestoneId: string,
  force = false,
): Promise<MilestoneDeleteResult> {
  const rx = (await client.delete(
    `/working-directories/${directoryId}/milestones/${milestoneId}`,
    { params: { force } },
  )) as unknown as RX<MilestoneDeleteResult>
  return rx.data
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
  const result = await rewindTaskUnified(taskId, body)
  if (!result) {
    throw new Error('回退失败')
  }
  return result
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
  return await getWorkerSessionMessageCountUnified(workerId, sessionId)
}

export async function deleteTask(taskId: string): Promise<{ taskId: string; deleted: boolean }> {
  await deleteTaskUnified(taskId)
  return { taskId, deleted: true }
}

export async function listWorkerSessions(
  workerId: string,
): Promise<WorkerSession[]> {
  return await listWorkerSessionsUnified(workerId) as unknown as WorkerSession[]
}

export async function getWorkerSessionMessages(
  workerId: string,
  sessionId: string,
): Promise<{ role: string; content: string; timestamp: string }[]> {
  return await getWorkerSessionMessagesUnified(workerId, sessionId)
}

export async function getWorkerSessionMessagesPaged(
  workerId: string,
  sessionId: string,
  offset: number,
  limit: number,
): Promise<{ role: string; content: string; timestamp: string }[]> {
  return await getWorkerSessionMessagesUnified(workerId, sessionId, offset, limit)
}

export async function syncWorkerSessions(
  workerId: string,
): Promise<{ synced: number; total: number }> {
  return await syncWorkerSessionsUnified(workerId)
}

export async function listActiveTasks(): Promise<ClaudeTask[]> {
  return await listTasksUnified() as unknown as ClaudeTask[]
}

export async function listAwaitingReplyTasks(): Promise<ClaudeTask[]> {
  return await listTasksByStateUnified('AWAITING_REPLY') as unknown as ClaudeTask[]
}

// ===== Conversation Config API =====

export async function updateConversationTags(
  sessionId: string,
  tags: string[],
): Promise<ConversationConfig> {
  const rx = (await client.patch(`/sessions/${sessionId}/config/tags`, {
    tags,
  })) as unknown as RX<ConversationConfig>
  return rx.data
}

export async function updateConversationPin(
  sessionId: string,
  pinned: boolean,
): Promise<ConversationConfig> {
  const rx = (await client.patch(`/sessions/${sessionId}/config/pin`, {
    pinned,
  })) as unknown as RX<ConversationConfig>
  return rx.data
}

export async function updateConversationTitle(
  sessionId: string,
  title: string,
): Promise<ConversationConfig> {
  const rx = (await client.patch(`/sessions/${sessionId}/config/title`, {
    title,
  })) as unknown as RX<ConversationConfig>
  return rx.data
}

export async function updateConversationMilestone(
  sessionId: string,
  milestoneId?: string,
): Promise<ConversationConfig> {
  const rx = (await client.patch(`/sessions/${sessionId}/config/milestone`, {
    milestoneId,
  })) as unknown as RX<ConversationConfig>
  return rx.data
}

export async function bindConversationAuth(
  sessionId: string,
  form: { authMode: string; authToken: string; baseUrl?: string },
): Promise<ConversationConfig> {
  const rx = (await client.post(
    `/sessions/${sessionId}/config/bind-auth`,
    form,
  )) as unknown as RX<ConversationConfig>
  return rx.data
}

export async function updateConversationAuth(
  sessionId: string,
  form: { authMode: string; authToken: string; baseUrl?: string },
): Promise<ConversationConfig> {
  const rx = (await client.patch(
    `/sessions/${sessionId}/config/auth`,
    form,
  )) as unknown as RX<ConversationConfig>
  return rx.data
}

export async function listConversationConfigs(
  sessionIds: string[],
): Promise<ConversationConfig[]> {
  const rx = (await client.get('/sessions/configs', {
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
    '/sessions/configs/batch-bind-auth',
    form,
  )) as unknown as RX<{ bound: number; total: number }>
  return rx.data
}

export async function archiveConversation(
  sessionId: string,
): Promise<ConversationConfig> {
  const rx = (await client.post(
    `/sessions/${sessionId}/config/archive`,
  )) as unknown as RX<ConversationConfig>
  return rx.data
}

export async function unarchiveConversation(
  sessionId: string,
): Promise<ConversationConfig> {
  const rx = (await client.post(
    `/sessions/${sessionId}/config/unarchive`,
  )) as unknown as RX<ConversationConfig>
  return rx.data
}

export async function holdConversation(
  sessionId: string,
): Promise<ConversationConfig> {
  const rx = (await client.post(
    `/sessions/${sessionId}/config/hold`,
  )) as unknown as RX<ConversationConfig>
  return rx.data
}

export async function unholdConversation(
  sessionId: string,
): Promise<ConversationConfig> {
  const rx = (await client.post(
    `/sessions/${sessionId}/config/unhold`,
  )) as unknown as RX<ConversationConfig>
  return rx.data
}
