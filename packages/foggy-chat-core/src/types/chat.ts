import type { AipMessageType } from './aip'

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
}

export type ConnectionStatus = 'disconnected' | 'connecting' | 'connected' | 'error'
