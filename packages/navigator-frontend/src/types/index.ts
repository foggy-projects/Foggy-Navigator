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
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'ABORTED'
  claudeSessionId?: string
  costUsd?: number
  inputTokens?: number
  outputTokens?: number
  durationMs?: number
  errorMessage?: string
  createdAt: string
  updatedAt: string
}
