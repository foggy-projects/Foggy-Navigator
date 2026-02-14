export enum AipMessageType {
  TEXT_CHUNK = 'TEXT_CHUNK',
  TEXT_COMPLETE = 'TEXT_COMPLETE',
  TOOL_CALL_START = 'TOOL_CALL_START',
  TOOL_CALL_RESULT = 'TOOL_CALL_RESULT',
  TOOL_CALL_ERROR = 'TOOL_CALL_ERROR',
  THINKING = 'THINKING',
  STATE_SYNC = 'STATE_SYNC',
  ERROR = 'ERROR',
  TASK_COMPLETED = 'TASK_COMPLETED',
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
