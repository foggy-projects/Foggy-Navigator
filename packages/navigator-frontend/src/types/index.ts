/** 后端 AgentMessage — SSE event 的 raw payload */
export interface AgentMessage {
  messageId: string
  sessionId: string
  agentId: string
  timestamp: number
  version: string
  type: string // TEXT_CHUNK | TEXT_COMPLETE | ERROR | ...
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

/** RX<T> 响应包装 */
export interface RX<T> {
  code: number
  message: string
  data: T
}

/** 路由动作 */
export type RouteAction = 'DELEGATE' | 'RETURN' | 'NAVIGATE'

/** 路由模式 */
export type RouteMode = 'REPLACE' | 'NEW_TAB'

/** 路由目标 */
export interface RouteTarget {
  agentId?: string
  sessionId?: string
  url?: string
}

/** ROUTE_REQUEST 事件的 payload */
export interface RoutePayload {
  action: RouteAction
  mode: RouteMode
  target: RouteTarget
  context?: Record<string, unknown>
}

// ===== Claude Worker 类型 =====

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
  defaultAuthMode?: 'API_KEY' | 'CUSTOM_ENDPOINT' | 'SUBSCRIPTION'
  defaultAuthConfigured?: boolean
  defaultBaseUrl?: string
  maskedDefaultAuthToken?: string
  lastSyncedAt?: string
  createdAt: string
  updatedAt: string
}

/** Worker 上的 Claude Code 本地会话 */
export interface WorkerSession {
  session_id: string
  cwd: string
  created_at: string
  updated_at: string
  slug?: string
  git_branch?: string
}

/** Claude Code 技能 */
export interface SkillInfo {
  name: string
  description: string
  scope: 'project' | 'user'
}

/** 会话级配置（置顶、标题、Auth 绑定） */
export interface ConversationConfig {
  sessionId: string
  pinned: boolean
  pinnedAt?: string
  customTitle?: string
  authMode?: string
  authBound: boolean
  baseUrl?: string
  maskedAuthToken?: string
}

// ===== 跨 Agent 任务类型 =====

export type AgentTaskType = 'CODING' | 'CLAUDE_WORKER' | 'DELEGATION'

export type AgentTaskStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'

/** 跨 Agent 任务 */
export interface AgentTask {
  taskId: string
  parentSessionId: string
  userId: string
  sourceAgentId: string
  targetAgentId: string
  taskType: AgentTaskType
  status: AgentTaskStatus
  prompt: string
  resultSummary?: string
  externalTaskId?: string
  createdAt: string
  completedAt?: string
}

// ===== 平台配置类型 =====

export type GitProviderType = 'GITHUB' | 'GITLAB' | 'GITEE'

export type LlmModelCategory = 'GENERAL' | 'CODING' | 'REASONING' | 'VISION'

/** Git 提供者配置 */
export interface GitProviderConfig {
  id: string
  tenantId: string
  providerType: GitProviderType
  baseUrl?: string
  username?: string
  isActive: boolean
  hasToken: boolean
  createdAt: string
  updatedAt: string
}

/** Git 提供者配置表单 */
export interface GitProviderConfigForm {
  providerType: GitProviderType
  baseUrl?: string
  accessToken: string
  username?: string
}

/** LLM 模型配置 */
export interface LlmModelConfig {
  id: string
  tenantId: string
  name: string
  category: LlmModelCategory
  baseUrl: string
  modelName: string
  isDefault: boolean
  hasApiKey: boolean
  createdAt: string
  updatedAt: string
}

/** LLM 模型配置表单 */
export interface LlmModelConfigForm {
  name: string
  category: LlmModelCategory
  baseUrl: string
  modelName: string
  apiKey: string
  isDefault?: boolean
}

/** Agent 模型覆盖 */
export interface AgentModelOverride {
  agentId: string
  modelConfigId: string
}

/** 初始化状态 */
export interface SetupStatus {
  gitConfigured: boolean
  llmConfigured: boolean
  setupComplete: boolean
}

// ===== 用户记忆类型 =====

export type UserMemoryCategory = 'PREFERENCE' | 'FACT' | 'NOTE'

export type UserMemorySource = 'AUTO' | 'MANUAL'

/** 用户记忆 */
export interface UserMemory {
  id: string
  userId: string
  category: UserMemoryCategory
  content: string
  source: UserMemorySource
  createdAt: string
  updatedAt: string
}

/** 用户记忆表单 */
export interface UserMemoryForm {
  category?: UserMemoryCategory
  content: string
}
