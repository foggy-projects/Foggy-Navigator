/**
 * API 类型定义
 */

export interface CreateConversationRequest {
  userId: string;
  projectId: string;
  gitRepoUrl: string;
  branchName: string;
  gitCredentials?: {
    username: string;
    token: string;
  };
  initialMessage?: string;
}

export interface Conversation {
  conversationId: string;
  sandboxId?: string;
  ohConversationId?: string;
  userId: string;
  projectId: string;
  status: ConversationStatus;
  namespace?: string;
  gitRepoUrl: string;
  branchName: string;
  createdAt: string;
  updatedAt?: string;
}

export type ConversationStatus =
  | 'STARTING'
  | 'WAITING_FOR_SANDBOX'
  | 'PREPARING_REPOSITORY'
  | 'READY'
  | 'RUNNING'
  | 'IDLE'
  | 'PAUSED'
  | 'ERROR'
  | 'STOPPED';

export interface SendMessageRequest {
  content: string;
}

export interface Message {
  messageId: string;
  conversationId: string;
  content: string;
  timestamp: string;
}

export interface Event {
  id: string;
  conversationId: string;
  kind: EventKind;
  timestamp: string;
  data: Record<string, any>;
}

export type EventKind =
  | 'CONVERSATION_STATUS'
  | 'MESSAGE_SENT'
  | 'AGENT_ACTION'
  | 'AGENT_OBSERVATION'
  | 'VALIDATION_TRIGGERED'
  | 'VALIDATION_RESULT'
  | 'ERROR';

export interface ApiResponse<T = any> {
  success: boolean;
  message?: string;
  data?: T;
}

export interface PaginatedResponse<T> {
  success: boolean;
  data: {
    items: T[];
    pagination?: {
      page: number;
      size: number;
      total: number;
    };
  };
}
