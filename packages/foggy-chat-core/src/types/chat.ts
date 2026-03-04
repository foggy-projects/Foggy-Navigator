import type { AipMessageType, UserQuestionItem, AllowedPrompt } from './aip'

export interface ChatMessage {
  id: string
  type: AipMessageType
  sender: 'user' | 'assistant' | 'system' | 'tool'
  content: string
  timestamp: number
  toolCallId?: string
  toolName?: string
  toolOutput?: string
  toolSuccess?: boolean
  thought?: string
  error?: string
  raw?: unknown
  permissionId?: string
  permissionStatus?: 'pending' | 'approved' | 'denied'
  /** Present when this is an AskUserQuestion interaction */
  questions?: UserQuestionItem[]
  /** Present when this is an ExitPlanMode plan review */
  planReview?: boolean
  /** Allowed tool prompts from ExitPlanMode */
  allowedPrompts?: AllowedPrompt[]
  /** Populated from CONFIRMATION_RESPONSE: maps question index → answered value string */
  answeredValues?: Record<number, string>
  /** Whether this error supports manual reconnection */
  reconnectable?: boolean
}

export type ConnectionStatus = 'disconnected' | 'connecting' | 'connected' | 'error'
