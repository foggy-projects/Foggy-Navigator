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
  processKind?: 'state' | 'tool_execution' | 'raw'
  /** Debug 模式下聚合后的一次工具执行 */
  toolExecution?: ToolExecutionBlock
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
}

/** Widget 展示模式 */
export type NavigatorChatMode = 'business' | 'debug'

/** 可点击业务动作 */
export interface NavigatorAction {
  id: string
  type: string
  label: string
  url?: string
  payload?: unknown
  raw?: unknown
}

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
  /** 等价调试开关；未显式指定 mode 时，true 等价 mode=debug */
  debugMode?: boolean
  /** 是否展示运行时/连接/状态事件 */
  showRuntimeEvents?: boolean
  /** 是否展示工具调用 */
  showToolCalls?: boolean
  /** 是否展示工具结果 */
  showToolResults?: boolean
  /**
   * 自定义请求函数（用于上游后端代理模式）
   *
   * 提供此函数后，所有 HTTP 请求都通过此函数发出，
   * 而不是直接请求 Navigator。用于避免跨域问题。
   */
  fetch?: (url: string, init: RequestInit) => Promise<Response>
}

export type {
  BusinessSuspensionDecisionPayload,
  BusinessSuspensionDialogModel,
  BusinessSuspensionStatus,
  BusinessSuspensionType,
} from '@foggy/chat'
