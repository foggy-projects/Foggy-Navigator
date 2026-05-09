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
  /** 耗时（毫秒） */
  durationMs?: number
  /** 费用（USD） */
  costUsd?: number
  /** 错误信息 */
  error?: string
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
