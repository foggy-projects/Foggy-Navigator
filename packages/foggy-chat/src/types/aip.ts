export enum AipMessageType {
  // 文本流
  TEXT_CHUNK = 'TEXT_CHUNK',
  TEXT_COMPLETE = 'TEXT_COMPLETE',

  // 服务端工具调用
  TOOL_CALL_START = 'TOOL_CALL_START',
  TOOL_CALL_RESULT = 'TOOL_CALL_RESULT',
  TOOL_CALL_ERROR = 'TOOL_CALL_ERROR',

  // 客户端工具调用
  CLIENT_TOOL_CALL = 'CLIENT_TOOL_CALL',
  CLIENT_TOOL_RESULT = 'CLIENT_TOOL_RESULT',

  // UI 渲染（A2UI）
  SURFACE_UPDATE = 'SURFACE_UPDATE',
  DATA_MODEL_UPDATE = 'DATA_MODEL_UPDATE',

  // 路由跳转
  ROUTE_REQUEST = 'ROUTE_REQUEST',
  ROUTE_CONFIRM = 'ROUTE_CONFIRM',

  // 用户交互
  USER_ACTION_REQUEST = 'USER_ACTION_REQUEST',
  CONFIRMATION_REQUEST = 'CONFIRMATION_REQUEST',
  FORM_REQUEST = 'FORM_REQUEST',

  // 状态同步
  THINKING = 'THINKING',
  STATE_SYNC = 'STATE_SYNC',
  ERROR = 'ERROR',

  // 任务通知
  TASK_COMPLETED = 'TASK_COMPLETED',

  // 生命周期
  SESSION_START = 'SESSION_START',
  SESSION_END = 'SESSION_END',
  HEARTBEAT = 'HEARTBEAT',
}

export interface AipMessage<T = unknown> {
  messageId: string
  sessionId: string
  timestamp: number
  type: AipMessageType
  payload: T
}

export interface TextPayload {
  content: string
  messageId?: string
}

export interface ToolCallStartPayload {
  toolCallId: string
  toolName: string
  command?: string
  path?: string
  thought?: string
}

export interface ToolCallResultPayload {
  toolCallId: string
  toolName: string
  output?: string
  exitCode?: number
}

export interface ToolCallErrorPayload {
  toolCallId: string
  toolName: string
  errorMessage: string
}

export interface ThinkingPayload {
  thought: string
}

export interface StateSyncPayload {
  status: string
  data?: Record<string, unknown>
}

export interface ErrorPayload {
  error: string
  source?: string
}

export interface TaskCompletedPayload {
  taskId: string
  targetAgentId: string
  taskType: string
  status: string
  resultSummary?: string
}
