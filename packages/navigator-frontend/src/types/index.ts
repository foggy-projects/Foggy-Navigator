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

// ===== Worker 后端类型 =====

export type WorkerBackend = 'CLAUDE_CODE' | 'OPENAI_CODEX' | 'GEMINI_CLI' | 'LANGGRAPH_BIZ'

// ===== Claude Worker 类型 =====

/** Claude Agent Worker */
export interface ClaudeWorker {
  workerId: string
  name: string
  baseUrl: string
  workerBackend?: WorkerBackend
  authMode: 'SUBSCRIPTION' | 'API_KEY' | 'CUSTOM_ENDPOINT'
  status: 'ONLINE' | 'OFFLINE' | 'UNKNOWN'
  hostname?: string
  workerVersion?: string
  lastHeartbeat?: string
  createdAt: string
  sshUsername?: string
  sshPort?: number
  sshPasswordConfigured?: boolean
  codeServerPublicUrl?: string
  codeServerInternalUrl?: string
  codeServerPasswordConfigured?: boolean
  codeServerFolderPrefix?: string
  /** Codex Worker 配置 */
  codexBaseUrl?: string
  codexModel?: string
  codexAuthTokenConfigured?: boolean
  /** Gemini Worker 配置 */
  geminiBaseUrl?: string
  geminiModel?: string
  geminiAuthTokenConfigured?: boolean
}

/** Claude 任务 */
export interface ClaudeTask {
  taskId: string
  sessionId: string
  parentSessionId?: string
  workerId: string
  workerTaskId?: string
  prompt: string
  cwd?: string
  directoryId?: string
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'ABORTED' | 'AWAITING_PERMISSION'
  claudeSessionId?: string
  codexThreadId?: string
  geminiSessionId?: string
  providerType?: string
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

/** Worker 工作目录 */
export interface DirectoryMilestone {
  id: string
  name: string
  status: 'PLANNED' | 'ACTIVE' | 'COMPLETED' | 'ARCHIVED' | string
  docPath?: string
  startAt?: string
  endAt?: string
}

export interface MilestonePageResult {
  content: DirectoryMilestone[]
  total: number
  page: number
  size: number
  sortBy: 'startAt' | 'endAt' | 'name' | 'status' | string
  sortDir: 'asc' | 'desc' | string
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

/** Agent Teams 命名配置 */
export interface AgentTeamsConfig {
  configId: string
  directoryId: string
  name: string
  config: string
  isDefault: boolean
  agentNames: string[]
  createdAt: string
  updatedAt: string
}

/** Worker 上的本地/业务会话 */
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
  authModelConfigId?: string
  baseUrl?: string
  maskedAuthToken?: string
  tags?: string[]
  interactionState?: 'PROCESSING' | 'AWAITING_REPLY' | 'ON_HOLD' | 'ARCHIVED'
  milestoneId?: string
}

// ===== Resync 任务重新同步 =====

export interface MessageCount {
  user: number
  assistant: number
  total: number
}

export interface CliStatus {
  alive: boolean
  workerReachable: boolean
  taskInRegistry: boolean
  source: string
  detail: string
}

export interface MessageSyncReport {
  platformBefore: MessageCount
  workerTotal: MessageCount
  imported: number
  platformAfter: MessageCount
  missingPreview: Array<{ role: string; content: string }>
}

export interface ResyncResult {
  taskId: string
  action: 'RECONNECTED' | 'MESSAGES_SYNCED' | 'ALREADY_ALIGNED' | 'NO_SESSION_DATA' | 'WORKER_UNREACHABLE'
  cliStatus: CliStatus
  messageSync?: MessageSyncReport
  taskStatusAfter: string
}

// ===== A2A 协议类型 =====

export interface A2aPart {
  type: 'text' | 'data' | 'file'
  text?: string
  data?: Record<string, unknown>
}

export interface A2aArtifact {
  artifactId?: string
  name?: string
  parts: A2aPart[]
}

export interface A2aTaskStatus {
  state: 'SUBMITTED' | 'WORKING' | 'INPUT_REQUIRED' | 'COMPLETED' | 'FAILED' | 'CANCELED'
  description?: string
}

export interface A2aTask {
  id: string
  contextId?: string
  status: A2aTaskStatus
  artifacts?: A2aArtifact[]
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

export type ModelAccessScope = 'GLOBAL' | 'RESTRICTED'

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
  scope: ModelAccessScope
  allowedWorkerIds?: string[]
  envVars?: Record<string, string>
  availableModels?: string[]
  workerBackend?: WorkerBackend
  sortOrder: number
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
  scope?: ModelAccessScope
  allowedWorkerIds?: string[]
  envVars?: Record<string, string>
  availableModels?: string[]
  workerBackend?: WorkerBackend
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

// ===== 用户管理类型 =====

export type UserStatus = 'ACTIVE' | 'DISABLED' | 'DELETED'

export interface UserDTO {
  id: string
  tenantId?: string
  username: string
  email?: string
  displayName?: string
  roles?: string
  status: UserStatus
  lastLoginAt?: string
  createdAt?: string
  updatedAt?: string
}

export interface UserRegisterForm {
  tenantId?: string
  username: string
  password: string
  email?: string
  displayName?: string
  roles?: string
}

export interface UserUpdateForm {
  email?: string
  displayName?: string
  roles?: string
  status?: UserStatus
  newPassword?: string
}

export interface ApiKeyDTO {
  id: string
  userId: string
  name: string
  apiKey?: string
  maskedApiKey?: string
  enabled?: boolean
  expiresAt?: string
  lastUsedAt?: string
  createdAt?: string
}

export interface ApiKeyCreateForm {
  name: string
  expiresAt?: string
}

// ===== API 凭证类型 =====

export type AuthType = 'API_KEY' | 'BEARER_TOKEN' | 'BASIC_AUTH' | 'CUSTOM_HEADER'

/** API 凭证配置 */
export interface ApiCredential {
  id: string
  tenantId: string
  name: string
  category: string
  baseUrl?: string
  authType: AuthType
  authHeaderName?: string
  description?: string
  isActive: boolean
  hasApiKey: boolean
  createdAt: string
  updatedAt: string
}

/** API 凭证配置表单 */
export interface ApiCredentialForm {
  name: string
  category: string
  baseUrl?: string
  apiKey: string
  authType: AuthType
  authHeaderName?: string
  extraHeaders?: Record<string, string>
  description?: string
}

// ===== 编程 Agent 类型 =====

export type CodingAgentType = 'LOCAL_CLAUDE_WORKER' | 'LOCAL_CODEX_WORKER' | 'EXTERNAL_A2A'

/** 编程 Agent */
export interface CodingAgent {
  agentId: string
  name: string
  description?: string
  agentType: CodingAgentType
  workerId?: string
  workerName?: string
  defaultDirectoryId?: string
  skills?: string
  defaultBranch?: string
  projectSummary?: string
  defaultModelConfigId?: string
  defaultModelConfigName?: string
  defaultModel?: string
  defaultDirectory?: DirectorySummary
  authorizedDirectories?: DirectorySummary[]
  createdAt: string
  updatedAt: string
}

/** 目录摘要 */
export interface DirectorySummary {
  directoryId: string
  projectName: string
  path?: string
  gitBranch?: string
}

// ===== CLI 进程管理类型 =====

/** Worker 上的 Claude CLI node 进程 */
export interface CliProcessInfo {
  pid: number
  command: string
  memory_mb: number
  started_at: string
  is_orphan: boolean
  process_type?: 'claude' | 'codex' | 'gemini'
  claude_session_id?: string
  codex_thread_id?: string
  gemini_session_id?: string
  foggy_task_id?: string
  foggy_session_id?: string
  /** LLM model used by this process (e.g. claude-sonnet-4-20250514) */
  model?: string
  /** Reconciler 首次发现该进程为孤儿的时间（ISO-8601），null 表示非孤儿 */
  orphan_first_seen_at?: string
  /** 孤儿进程预计自动杀死时间（ISO-8601）= orphan_first_seen_at + 10 min */
  orphan_auto_kill_at?: string
}

/** CLI 进程列表响应 */
export interface CliProcessListResponse {
  processes: CliProcessInfo[]
  active_task_count: number
  total: number
}

/** 终止进程响应 */
export interface KillProcessResponse {
  pid: number
  status: 'killed' | 'not_found' | 'failed'
  message: string
}

// ===== 跨项目任务类型 =====

export type CrossProjectTaskStatus = 'DRAFT' | 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
export type CrossProjectPhaseStatus = 'PENDING' | 'RUNNING' | 'AWAITING_REVIEW' | 'COMPLETED' | 'FAILED' | 'SKIPPED'

/** 跨项目任务 */
export interface CrossProjectTask {
  contextId: string
  title: string
  description?: string
  status: CrossProjectTaskStatus
  currentPhaseIndex?: number
  totalPhases: number
  executionMode?: string
  totalCostUsd?: number
  initialSessionId?: string
  initialDirectoryId?: string
  phases: CrossProjectPhase[]
  createdAt: string
  updatedAt: string
  completedAt?: string
}

/** 跨项目任务阶段 */
export interface CrossProjectPhase {
  phaseId: string
  phaseIndex: number
  phaseName: string
  prompt: string
  agentId?: string
  directoryId?: string
  workerId?: string
  status: CrossProjectPhaseStatus
  claudeTaskId?: string
  phaseSessionId?: string
  claudeSessionId?: string
  worktreeDirectoryId?: string
  worktreeBranch?: string
  handoffArtifact?: string
  incomingContext?: string
  directoryName?: string
  workerName?: string
  agentName?: string
  costUsd?: number
  durationMs?: number
  startedAt?: string
  completedAt?: string
  createdAt?: string
  updatedAt?: string
}

// ===== 会话搜索类型 =====

/** 会话搜索结果项 */
export interface SessionSearchResult {
  sessionId: string
  parentSessionId?: string
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

// ===== ҵ�� Agent �������� =====

/** ҵ�� Worker �� */
export interface BizWorkerPool {
  poolId: string
  tenantId: string
  name: string
  workerBackend: string
  routingPolicy: string
  status: 'ACTIVE' | 'INACTIVE'
  healthStatus?: 'HEALTHY' | 'UNHEALTHY' | 'UNKNOWN'
}

/** �ͻ���Ӧ��ģ����Ȩ */
export interface ClientAppModelConfigGrant {
  id: number
  clientAppId: string
  tenantId: string
  modelConfigId: string
  modelConfigName?: string
  workerBackend?: string
  status: 'ACTIVE' | 'INACTIVE'
  isDefault: boolean
  grantScope?: string
  createdAt: string
  updatedAt: string
}
