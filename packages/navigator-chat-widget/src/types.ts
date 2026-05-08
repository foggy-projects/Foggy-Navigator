/** 对话消息 */
export interface ChatMessage {
  id: string
  role: 'user' | 'assistant' | 'system'
  content: string
  timestamp: number
  /** 关联的任务 ID */
  taskId?: string
  /** 任务状态 */
  status?: TaskStatus
  /** 耗时（毫秒） */
  durationMs?: number
  /** 费用（USD） */
  costUsd?: number
  /** 错误信息 */
  error?: string
}

/** 任务状态 */
export type TaskStatus = 'SUBMITTED' | 'WORKING' | 'COMPLETED' | 'FAILED' | 'CANCELED' | 'INPUT_REQUIRED'

/** Agent 任务（API 响应） */
export interface AgentTask {
  taskId: string
  agentId: string
  status: TaskStatus
  contextId: string
  result?: string
  errorMessage?: string
  durationMs?: number
  costUsd?: number
  createdAt?: string
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
