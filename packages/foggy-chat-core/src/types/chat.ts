import type { AipMessageType, UserQuestionItem, AllowedPrompt } from './aip'

export type NavigatorUiArtifactKind = 'route' | 'iframe' | 'link'
export type NavigatorUiArtifactOpenMode = 'side_panel' | 'dialog' | 'new_tab' | 'current_page'

export interface NavigatorUiArtifact {
  kind: NavigatorUiArtifactKind
  id?: string
  title?: string
  uri?: string
  routeName?: string
  routePath?: string
  openMode?: NavigatorUiArtifactOpenMode
  fallbackUrl?: string
  sandbox?: string
  [key: string]: unknown
}

export interface NavigatorUiAction {
  type: string
  label?: string
  artifact: NavigatorUiArtifact
  context?: Record<string, unknown>
  raw?: unknown
}

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
  /** Present when this is a Skill approval request from langgraph-biz-worker */
  approvalStatus?: 'pending' | 'approved' | 'rejected'
  /** Present when this is an AskUserQuestion interaction */
  questions?: UserQuestionItem[]
  /** Present when this is an ExitPlanMode plan review */
  planReview?: boolean
  /** Allowed tool prompts from ExitPlanMode */
  allowedPrompts?: AllowedPrompt[]
  /** Execution plan content (Markdown) from ExitPlanMode */
  plan?: string
  /** Populated from CONFIRMATION_RESPONSE: maps question index → answered value string */
  answeredValues?: Record<number, string>
  /** Whether this error supports manual reconnection */
  reconnectable?: boolean
  /** Image attachments (preview URLs) for user messages */
  images?: Array<{ name: string; url: string }>
  /** Stable Frame Execution Report reference, never parsed as a business ID */
  executionReportRef?: string
  /** Compact Frame Execution Report digest for audit/debug display */
  executionReportDigest?: ExecutionReportDigest
  /** Generic business UI actions returned by tools or agents. */
  uiActions?: NavigatorUiAction[]
}

export interface ExecutionReportDigest {
  status?: string
  summary?: string
  error?: string | null
  reportRef?: string
  taskId?: string
  frameId?: string
  skillId?: string
  frameKind?: string
  generatedAt?: string
  [key: string]: unknown
}

export type ConnectionStatus = 'disconnected' | 'connecting' | 'connected' | 'error'
