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
  role: 'USER' | 'ASSISTANT' | 'TOOL' | 'SYSTEM'
  content: string
  metadata?: Record<string, unknown>
  createdAt: string
}

/** 引导卡片 */
export interface GuideCard {
  title: string
  description: string
  icon: string
}

// ===== Worker 后端类型 =====

export type WorkerBackend = 'CLAUDE_CODE' | 'OPENAI_CODEX'

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

/** 统一任务（对齐 PC 端 DispatchTaskDTO） */
export interface DispatchTask {
  taskId: string
  sessionId: string
  workerId: string
  workerTaskId?: string
  prompt: string
  cwd?: string
  directoryId?: string
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'ABORTED' | 'AWAITING_PERMISSION'
  claudeSessionId?: string
  codexThreadId?: string
  providerType?: string
  agentId?: string
  userId?: string
  contextId?: string
  costUsd?: number
  inputTokens?: number
  outputTokens?: number
  durationMs?: number
  numTurns?: number
  model?: string
  /** 创建任务时使用的平台 LLM 模型配置 ID */
  modelConfigId?: string
  errorMessage?: string
  /** JSON array of checkpoint objects: [{id, turnIndex, timestamp}] */
  checkpoints?: string
  /** Whether file checkpointing was enabled for this task */
  fileCheckpointingEnabled?: boolean
  /** Task source: "PLATFORM" or "SYNCED" */
  source?: string
  /** Session page API: number of tasks in this session */
  sessionTaskCount?: number
  /** Session page API: accumulated cost for this session */
  sessionTotalCostUsd?: number
  /** Session page API: accumulated input tokens for this session */
  sessionInputTokens?: number
  /** Session page API: accumulated output tokens for this session */
  sessionOutputTokens?: number
  /** Session page API: first prompt in this session */
  sessionFirstPrompt?: string
  /** Agent Teams 配置 ID（任务创建时锁定） */
  agentTeamsConfigId?: string
  /** 仅 /active 端点填充：工作目录名称 */
  directoryName?: string
  createdAt: string
  updatedAt: string
}

/**
 * @deprecated 使用 DispatchTask 代替。保留此别名以便渐进式迁移。
 */
export type ClaudeTask = DispatchTask

/** 工作目录里程碑 */
export interface DirectoryMilestone {
  id: string
  name: string
  status: 'PLANNED' | 'ACTIVE' | 'COMPLETED' | 'ARCHIVED' | string
  docPath?: string
  startAt?: string
  endAt?: string
}

/** Rewind 操作结果 */
export interface RewindTaskResult {
  status: string
  checkpointId?: string
  taskId?: string
  userPrompt?: string
  turnIndex?: number
  claudeSessionId?: string
}

/** Worker 工作目录 */
export interface WorkingDirectory {
  directoryId: string
  workerId: string
  projectName: string
  path: string
  /** 关联的 CodingAgent 实体 ID（项目级 Agent，可选） */
  agentId?: string
  /** Agent 名称 */
  agentName?: string
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
  defaultModelConfigId?: string
  milestones?: DirectoryMilestone[]
  lastSyncedAt?: string
  createdAt: string
  updatedAt: string
}

// ===== 平台配置类型 =====

export type LlmModelCategory = 'GENERAL' | 'CODING' | 'REASONING' | 'VISION'

export type ModelAccessScope = 'GLOBAL' | 'RESTRICTED'

/** LLM 模型配置 */
export interface LlmModelConfig {
  id: string
  tenantId?: string
  name: string
  category?: LlmModelCategory
  baseUrl: string
  modelName: string
  hasApiKey: boolean
  isDefault: boolean
  scope?: ModelAccessScope
  allowedWorkerIds?: string[]
  availableModels?: string[]
  workerBackend?: WorkerBackend
  sortOrder?: number
  createdAt: string
  updatedAt: string
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
  credentialConfigured: boolean
  setupComplete: boolean
}

// ===== 会话配置类型 =====

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
  tags?: string[]
  interactionState?: 'PROCESSING' | 'AWAITING_REPLY' | 'ON_HOLD' | 'ARCHIVED'
  milestoneId?: string
}

/** 会话搜索结果项 */
export interface SessionSearchResult {
  sessionId: string
  workerId: string
  directoryId?: string
  firstPrompt: string
  customTitle?: string
  tags?: string[]
  interactionState?: 'PROCESSING' | 'AWAITING_REPLY' | 'ON_HOLD' | 'ARCHIVED'
  milestoneId?: string
  latestTaskId: string
  latestStatus: string
  model?: string
  /** 平台 LLM 模型配置 ID */
  modelConfigId?: string
  cwd?: string
  source?: string
  totalCost?: number
  createdAt: string
  updatedAt: string
}

/** 会话搜索分页响应 */
export interface SessionSearchPage {
  results: SessionSearchResult[]
  total: number
  page: number
  size: number
}

// ===== 分页类型 =====

/** 分页响应（旧格式，兼容） */
export interface PageResult<T> {
  content: T[]
  totalElements: number
  totalPages: number
}

/** 统一任务分页响应（按 session 维度分页） */
export interface SessionPageResult<T> {
  content: T[]
  totalSessions: number
  page: number
  size: number
}

/** 历史消息分页响应 */
export interface PaginatedMessages {
  messages: Message[]
  total: number
  limit: number
  offset: number
  hasMore: boolean
}

// ===== 会话聚合视图模型 =====

/** 移动端会话聚合视图（按 sessionId 聚合任务） */
export interface ConversationGroup {
  sessionId: string
  latestTask: DispatchTask
  config?: ConversationConfig
  firstPrompt: string
  taskCount: number
  totalCost: number
  totalInputTokens: number
  totalOutputTokens: number
  createdAt: string
  updatedAt: string
}
