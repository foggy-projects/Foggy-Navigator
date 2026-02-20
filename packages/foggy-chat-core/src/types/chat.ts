import type { AipMessageType, UserQuestionItem } from './aip'

export interface ChatMessage {
  id: string
  type: AipMessageType
  sender: 'user' | 'assistant' | 'system' | 'tool'
  content: string
  timestamp: number
  toolCallId?: string
  toolName?: string
  toolOutput?: string
  thought?: string
  error?: string
  raw?: unknown
  permissionId?: string
  permissionStatus?: 'pending' | 'approved' | 'denied'
  /** Present when this is an AskUserQuestion interaction */
  questions?: UserQuestionItem[]
}

export type ConnectionStatus = 'disconnected' | 'connecting' | 'connected' | 'error'
