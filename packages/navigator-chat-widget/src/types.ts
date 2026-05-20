/** 对话消息 */
export interface ChatMessage {
  id: string
  role: 'user' | 'assistant' | 'system'
  content: string
  timestamp: number
  /** Navigator OpenAPI 消息类型 */
  messageType?: OpenTaskMessageType
  /** 关联的任务 ID */
  taskId?: string
  /** 任务状态 */
  status?: TaskStatus
  /** 是否为终态消息 */
  terminal?: boolean
  /** 终态状态 */
  terminalStatus?: TerminalStatus | null
  /** 是否为过程/调试消息 */
  process?: boolean
  /** 过程消息展示类型 */
  processKind?: 'state' | 'tool_execution' | 'skill_frame' | 'raw'
  /** Debug 模式下聚合后的一次工具执行 */
  toolExecution?: ToolExecutionBlock
  /** Debug 模式下聚合后的 Skill Frame */
  skillFrame?: SkillFrameBlock
  /** 业务动作按钮或链接 */
  actions?: NavigatorAction[]
  /** 临时进度提示，任务结束后不保留 */
  transient?: boolean
  /** 耗时（毫秒） */
  durationMs?: number
  /** 费用（USD） */
  costUsd?: number
  /** 错误信息 */
  error?: string
  /** Navigator frame execution report 引用 */
  executionReportRef?: string
  /** Navigator frame execution report 摘要 */
  executionReportDigest?: ExecutionReportDigest
  /** 已随用户消息提交的附件 */
  attachments?: NavigatorAttachmentResult[]
}

/** Widget 展示模式 */
export type NavigatorChatMode = 'business' | 'details' | 'debug'

/** 可点击业务动作 */
export interface NavigatorAction {
  id: string
  type: string
  label: string
  url?: string
  payload?: unknown
  raw?: unknown
}

/** Navigator Frame Execution Report 精简摘要 */
export interface ExecutionReportDigest {
  status?: string
  summary?: string
  error?: string | null
  reportRef?: string
  taskId?: string
  frameId?: string
  skillId?: string
  frameKind?: string
  generatedAt?: string
  [key: string]: unknown
}

export interface ExecutionReportMarkdownPayload {
  ok?: boolean
  mode?: string
  reportRef?: string
  report_ref?: string
  markdown?: string
  markdownExcerpt?: string
  markdown_excerpt?: string
  content?: string
  text?: string
  error?: string
  message?: string
  truncated?: boolean
  [key: string]: unknown
}

export type ExecutionReportMarkdownLoader = (
  reportRef: string,
) => Promise<string | ExecutionReportMarkdownPayload>

/** Debug 模式下的一次工具执行块 */
export interface ToolExecutionBlock {
  toolCallId: string
  toolName: string
  functionName?: string
  functionId?: string
  displayName: string
  status: 'running' | 'success' | 'failed' | 'skipped' | 'cancelled'
  startedAt?: number
  finishedAt?: number
  durationMs?: number
  args?: unknown
  result?: unknown
  error?: unknown
  rawCall?: unknown
  rawResult?: unknown
  summary: string[]
  trace: Record<string, unknown>
  executionReportRef?: string
  executionReportDigest?: ExecutionReportDigest
}

/** Debug 模式下的 Skill Frame 执行块 */
export interface SkillFrameBlock {
  frameId: string
  parentFrameId?: string
  skillId?: string
  displayName: string
  status: 'running' | 'success' | 'failed'
  openedAt?: number
  closedAt?: number
  durationMs?: number
  openContent?: string
  closeContent?: string
  rawOpen?: unknown
  rawClose?: unknown
  toolExecutions: ToolExecutionBlock[]
  children: SkillFrameBlock[]
  trace: Record<string, unknown>
  executionReportRef?: string
  executionReportDigest?: ExecutionReportDigest
}

/** 任务状态 */
export type TaskStatus = 'SUBMITTED' | 'WORKING' | 'COMPLETED' | 'FAILED' | 'CANCELED' | 'INPUT_REQUIRED'

/** Navigator OpenAPI 增量消息类型 */
export type OpenTaskMessageType = 'USER' | 'TEXT' | 'TOOL_CALL' | 'TOOL_RESULT' | 'RESULT' | 'STATE' | 'ERROR'

/** Navigator OpenAPI 终态状态 */
export type TerminalStatus = 'COMPLETED' | 'FAILED' | string

/** Navigator OpenAPI 任务增量消息 */
export interface OpenTaskMessage {
  id?: string
  messageId?: string
  type: OpenTaskMessageType
  content?: unknown
  terminal?: boolean
  terminalStatus?: TerminalStatus | null
  createdAt?: string
  timestamp?: string | number
  status?: TaskStatus | string
  metadata?: Record<string, unknown>
  toolCallId?: string
  callId?: string
  invocationId?: string
  runId?: string
  toolName?: string
  functionName?: string
  functionId?: string
  args?: unknown
  result?: unknown
  error?: unknown
  startedAt?: string
  finishedAt?: string
  [key: string]: unknown
}

/** Agent 任务（API 响应） */
export interface AgentTask {
  taskId: string
  agentId: string
  status: TaskStatus
  contextId: string | null
  messages?: OpenTaskMessage[]
  nextCursor?: string | null
  hasMore?: boolean
  terminal?: boolean
  terminalStatus?: TerminalStatus | null
  result?: string
  errorMessage?: string
  durationMs?: number
  costUsd?: number
  createdAt?: string
}

export interface NavigatorSendOptions {
  /** Client App 透明会话上下文，仅保存到会话摘要 */
  clientContext?: Record<string, unknown>
  /** Agent 执行链路元数据 */
  metadata?: Record<string, unknown>
  /** TMS/BFF 上传后返回并透传给 Navigator OpenAPI 的附件 */
  attachments?: NavigatorAttachmentResult[]
}

export type NavigatorAttachmentKind =
  | 'image'
  | 'pdf'
  | 'text'
  | 'spreadsheet'
  | 'document'
  | 'archive'
  | 'file'

/** 上游附件上传接口返回并传给 Navigator 的通用附件元数据 */
export interface NavigatorAttachmentResult {
  id?: string
  name?: string
  mimeType?: string
  size?: number
  kind?: NavigatorAttachmentKind | string
  url?: string
  thumbnailUrl?: string
  provider?: string
  metadata?: Record<string, unknown>
  [key: string]: unknown
}

export type UploadAttachmentHook = (file: File) => Promise<NavigatorAttachmentResult>

export type PendingAttachmentStatus = 'pending' | 'uploading' | 'uploaded' | 'error'

/** 浏览器侧尚未或刚刚上传的本地附件 */
export interface PendingNavigatorAttachment {
  id: string
  file: File
  name: string
  mimeType: string
  size: number
  kind: NavigatorAttachmentKind
  isImage: boolean
  previewUrl?: string
  status: PendingAttachmentStatus
  error?: string
  uploaded?: NavigatorAttachmentResult
}

export interface PaginationOptions {
  cursor?: string | null
  limit?: number
}

export interface SessionSummary {
  contextId: string
  agentId?: string
  title?: string | null
  status?: string
  latestTaskId?: string | null
  /** C 端历史列表展示用：该会话的用户/助手交互轮数 */
  turnCount?: number
  /** C 端历史列表展示用：最后一条可读消息摘要 */
  lastMessagePreview?: string | null
  clientContext?: Record<string, unknown> | null
  createdAt?: string
  updatedAt?: string
}

export interface SessionMessage {
  messageId: string
  contextId?: string | null
  taskId?: string | null
  role?: string
  type?: string
  content?: unknown
  metadata?: Record<string, unknown>
  createdAt?: string
}

export interface SessionListPage {
  sessions: SessionSummary[]
  nextCursor?: string | null
  hasMore?: boolean
}

export interface SessionMessagesPage {
  contextId: string
  messages: SessionMessage[]
  nextCursor?: string | null
  hasMore?: boolean
}

/** Navigator OpenAPI 任务消息增量页 */
export interface TaskMessagesPage {
  taskId: string
  contextId: string | null
  status: TaskStatus | string
  messages: OpenTaskMessage[]
  nextCursor?: string | null
  hasMore?: boolean
  terminal?: boolean
  terminalStatus?: TerminalStatus | null
}

/** Widget 配置 */
export interface NavigatorChatConfig {
  /** Navigator Open API 基地址 */
  baseUrl: string
  /** API Key（如果通过上游后端代理，则不需要） */
  apiKey?: string
  /** Agent ID */
  agentId: string
  /** 轮询间隔（毫秒，默认 2000；TMS 初始接入建议显式设置为 4000） */
  pollInterval?: number
  /** 超时时间（毫秒，默认 300000 = 5 分钟） */
  timeout?: number
  /** 最大交互轮数（默认 3） */
  maxTurns?: number
  /** 展示模式，默认 business */
  mode?: NavigatorChatMode
  /** 是否在组件头部显示展示模式切换入口；启用后会保留过程消息以支持运行中随时切换 */
  showDisplayModeSwitcher?: boolean
  /** 展示模式切换入口可选项，默认 business/details/debug */
  displayModeOptions?: NavigatorChatMode[]
  /** 等价调试开关；未显式指定 mode 时，true 等价 mode=debug */
  debugMode?: boolean
  /** 是否展示运行时/连接/状态事件 */
  showRuntimeEvents?: boolean
  /** 是否展示工具调用 */
  showToolCalls?: boolean
  /** 是否展示工具结果 */
  showToolResults?: boolean
  /** 完整 Frame 执行报告 Markdown 加载器；通常由上游 BFF 代理 Worker 只读接口 */
  executionReportMarkdownLoader?: ExecutionReportMarkdownLoader
  /** 发送前上传附件；TMS 场景由宿主注入并调用自己的上传接口 */
  uploadAttachment?: UploadAttachmentHook
  /** 是否启用默认附件入口；默认在提供 uploadAttachment 时启用 */
  enableAttachments?: boolean
  /** 单条消息最多附件数，默认 6 */
  maxAttachments?: number
  /** 单个附件最大字节数，默认 20MB */
  maxAttachmentSize?: number
  /** 可接受的附件类型，支持 MIME、image/* 和 .pdf 这类扩展名 */
  acceptedAttachmentTypes?: string[]
  /**
   * 自定义请求函数（用于上游后端代理模式）
   *
   * 提供此函数后，所有 HTTP 请求都通过此函数发出，
   * 而不是直接请求 Navigator。用于避免跨域问题。
   */
  fetch?: (url: string, init: RequestInit) => Promise<Response>
}

export type {
  BusinessSuspensionDecision,
  BusinessSuspensionDecisionPayload,
  BusinessSuspensionDialogModel,
  BusinessSuspensionStatus,
  BusinessSuspensionType,
} from '@foggy/chat'
