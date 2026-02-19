/** RX<T> 响应包装 */
export interface RX<T> {
  code: number
  message: string
  data: T
}

/** 后端 AgentMessage — SSE event 的 raw payload */
export interface AgentMessage {
  messageId: string
  sessionId: string
  agentId: string
  timestamp: number
  version: string
  type: string
  payload: unknown
}

/** 后端 Session 实体 */
export interface Session {
  id: string
  userId: string
  agentId: string
  status: 'ACTIVE' | 'CLOSED'
  taskName?: string
  createdAt: string
  updatedAt: string
}

/** 后端 Message 实体 */
export interface Message {
  id: string
  sessionId: string
  role: 'USER' | 'ASSISTANT'
  content: string
  createdAt: string
}

/** 引导卡片 */
export interface GuideCard {
  title: string
  description: string
  icon: string
}

/** Claude Agent Worker */
export interface ClaudeWorker {
  workerId: string
  name: string
  baseUrl: string
  authMode: 'SUBSCRIPTION' | 'API_KEY' | 'CUSTOM_ENDPOINT'
  status: 'ONLINE' | 'OFFLINE' | 'UNKNOWN'
  hostname?: string
  workerVersion?: string
  lastHeartbeat?: string
  createdAt: string
}

/** Claude 任务 */
export interface ClaudeTask {
  taskId: string
  sessionId: string
  workerId: string
  prompt: string
  cwd?: string
  directoryId?: string
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'ABORTED'
  claudeSessionId?: string
  costUsd?: number
  inputTokens?: number
  outputTokens?: number
  durationMs?: number
  numTurns?: number
  model?: string
  errorMessage?: string
  createdAt: string
  updatedAt: string
}

/** Worker 工作目录 */
export interface WorkingDirectory {
  directoryId: string
  workerId: string
  projectName: string
  path: string
  gitBranch?: string
  gitRemoteUrl?: string
  gitProvider?: 'GITHUB' | 'GITLAB' | 'GITEE' | 'OTHER'
  gitStatus?: 'clean' | 'dirty' | 'unknown'
  agentTeamsConfig?: string
  directoryType?: 'STANDARD' | 'PROJECT'
  parentProjectId?: string
  projectTaskPrompt?: string
  worktree?: boolean
  sourceDirectoryId?: string
  lastSyncedAt?: string
  createdAt: string
  updatedAt: string
}

/** 初始化状态 */
export interface SetupStatus {
  gitConfigured: boolean
  llmConfigured: boolean
  setupComplete: boolean
}

/** 分页响应 */
export interface PageResult<T> {
  content: T[]
  totalElements: number
  totalPages: number
}
