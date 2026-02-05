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
