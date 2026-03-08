/**
 * Session Module API 类型定义
 */

// ===== RX 统一返回 =====

export interface RXResponse<T = any> {
  code: number;      // 200=成功
  msg?: string;
  data?: T;
  timestamp?: string;
  userTip?: string;
}

// ===== 请求类型 =====

export interface CreateSessionRequest {
  title?: string;
  agentId: string;
  parentSessionId?: string;
}

export interface SendMessageRequest {
  content: string;
}

// ===== 响应类型 =====

export interface Session {
  id: string;
  userId: string;
  tenantId: string;
  agentId: string;
  parentSessionId?: string;
  status: SessionStatus;
  taskName?: string;
  createdAt: string;
  updatedAt: string;
}

export type SessionStatus = 'ACTIVE' | 'PAUSED' | 'COMPLETED' | 'DELEGATED';

export interface Message {
  id: string;
  sessionId: string;
  role: MessageRole;
  content: string;
  metadata?: Record<string, any>;
  createdAt: string;
}

export type MessageRole = 'USER' | 'ASSISTANT' | 'SYSTEM' | 'TOOL';

export interface GuideCard {
  title: string;
  description: string;
  icon: string;
}

// ===== SSE 事件类型 =====

export interface AgentMessage {
  messageId: string;
  sessionId: string;
  agentId: string;
  timestamp: number;
  version: string;
  type: MessageType;
  payload: any;
}

export type MessageType =
  | 'TEXT_CHUNK'
  | 'TEXT_COMPLETE'
  | 'TOOL_CALL_START'
  | 'TOOL_CALL_RESULT'
  | 'TOOL_CALL_ERROR'
  | 'SURFACE_UPDATE'
  | 'DATA_MODEL_UPDATE'
  | 'ROUTE_REQUEST'
  | 'ROUTE_CONFIRM'
  | 'USER_ACTION_REQUEST'
  | 'CONFIRMATION_REQUEST'
  | 'FORM_REQUEST'
  | 'STATE_SYNC'
  | 'THINKING'
  | 'ERROR'
  | 'SESSION_START'
  | 'SESSION_END'
  | 'HEARTBEAT';

// ===== 登录 =====

export interface LoginResultDTO {
  token: string;
  tokenType: string;
  expiresIn: number;
  user: {
    id: string;
    username: string;
  };
}
