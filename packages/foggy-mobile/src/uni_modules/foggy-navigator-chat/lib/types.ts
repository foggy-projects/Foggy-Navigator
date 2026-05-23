export type FoggyNavigatorChatMode = 'business' | 'details' | 'debug'

export type FoggyNavigatorTaskStatus =
  | 'SUBMITTED'
  | 'WORKING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELED'
  | 'INPUT_REQUIRED'
  | string

export type FoggyNavigatorOpenMessageType =
  | 'USER'
  | 'TEXT'
  | 'TOOL_CALL'
  | 'TOOL_RESULT'
  | 'RESULT'
  | 'STATE'
  | 'ERROR'
  | string

export interface ExecutionReportDigest {
  status?: string
  summary?: string
  error?: string
  reportRef?: string
  taskId?: string
  frameId?: string
  skillId?: string
  frameKind?: string
  generatedAt?: string
  [key: string]: unknown
}

export interface ExecutionReportMarkdownPayload {
  markdown: string
  title?: string
  updatedAt?: string
  digest?: ExecutionReportDigest
}

export type ExecutionReportMarkdownLoader = (
  reportRef: string,
) => Promise<string | ExecutionReportMarkdownPayload>

export interface FoggyNavigatorAction {
  id: string
  type: string
  label: string
  url?: string
  payload?: Record<string, unknown>
  raw?: unknown
}

export interface ToolExecutionBlock {
  toolCallId: string
  toolName: string
  functionName?: string
  functionId?: string
  displayName: string
  status: 'running' | 'success' | 'failed' | 'skipped' | 'cancelled'
  startedAt?: number
  finishedAt?: number
  durationMs?: number
  args?: unknown
  result?: unknown
  error?: unknown
  summary: string[]
  executionReportRef?: string
  executionReportDigest?: ExecutionReportDigest
}

export interface FoggyNavigatorAttachment {
  id?: string
  name?: string
  url?: string
  mimeType?: string
  size?: number
  kind?: string
  [key: string]: unknown
}

export interface FoggyNavigatorChatMessage {
  id: string
  role: 'user' | 'assistant' | 'system'
  content: string
  timestamp: number
  taskId?: string
  status?: FoggyNavigatorTaskStatus
  messageType?: FoggyNavigatorOpenMessageType
  terminal?: boolean
  terminalStatus?: string | null
  transient?: boolean
  error?: string
  process?: boolean
  processKind?: 'state' | 'network' | 'system'
  actions?: FoggyNavigatorAction[]
  attachments?: FoggyNavigatorAttachment[]
  toolExecution?: ToolExecutionBlock
  executionReportRef?: string
  executionReportDigest?: ExecutionReportDigest
}

export interface FoggyNavigatorOpenMessage {
  id?: string
  messageId?: string
  type: FoggyNavigatorOpenMessageType
  content?: unknown
  payload?: unknown
  metadata?: Record<string, unknown>
  args?: unknown
  result?: unknown
  timestamp?: number | string
  createdAt?: string
  taskId?: string
  terminal?: boolean
  terminalStatus?: string | null
  [key: string]: unknown
}

export interface FoggyNavigatorTask {
  taskId: string
  contextId?: string
  status: FoggyNavigatorTaskStatus
  terminal?: boolean
  terminalStatus?: string | null
  nextCursor?: string | null
  messages?: FoggyNavigatorOpenMessage[]
  result?: string
  errorMessage?: string
  durationMs?: number
  costUsd?: number
}

export interface PaginationOptions {
  cursor?: string | null
  limit?: number
}

export interface FoggyNavigatorTaskMessagesPage {
  taskId: string
  contextId?: string
  status?: FoggyNavigatorTaskStatus
  terminal?: boolean
  terminalStatus?: string | null
  nextCursor?: string | null
  messages: FoggyNavigatorOpenMessage[]
}

export interface FoggyNavigatorSessionSummary {
  contextId: string
  title?: string
  preview?: string
  status?: FoggyNavigatorTaskStatus
  createdAt?: string
  updatedAt?: string
  messageCount?: number
  turnCount?: number
  [key: string]: unknown
}

export interface FoggyNavigatorSessionMessage {
  messageId?: string
  id?: string
  role?: string
  type?: string
  content?: unknown
  metadata?: Record<string, unknown>
  taskId?: string
  createdAt?: string
  timestamp?: number | string
  [key: string]: unknown
}

export interface FoggyNavigatorSessionPage {
  sessions: FoggyNavigatorSessionSummary[]
  nextCursor?: string | null
  hasMore?: boolean
}

export interface FoggyNavigatorSessionMessagesPage {
  contextId?: string
  messages: FoggyNavigatorSessionMessage[]
  nextCursor?: string | null
  hasMore?: boolean
}

export interface FoggyNavigatorSendOptions {
  metadata?: Record<string, unknown>
  clientContext?: Record<string, unknown>
  attachments?: FoggyNavigatorAttachment[]
}

export type BusinessSuspensionDecision = 'approved' | 'rejected'

export interface BusinessSuspensionDecisionPayload {
  suspendId: string
  suspensionType: string
  decision: BusinessSuspensionDecision
  comment?: string
}

export interface BusinessSuspensionDisplayField {
  label: string
  value: unknown
}

export interface BusinessSuspensionDialogModel {
  suspendId: string
  suspensionType: string
  title?: string
  summary?: string
  functionId?: string
  functionDisplayName?: string
  version?: string
  riskLevel?: string
  expiresAt?: string
  displayFields?: BusinessSuspensionDisplayField[]
  approveLabel?: string
  rejectLabel?: string
  commentPlaceholder?: string
}

export interface FoggyNavigatorChatClient {
  ask: (
    question: string,
    contextId?: string,
    options?: FoggyNavigatorSendOptions,
  ) => Promise<FoggyNavigatorTask>
  getTaskMessages: (
    taskId: string,
    cursor?: string | null,
    limit?: number,
  ) => Promise<FoggyNavigatorTaskMessagesPage>
  cancelTask?: (taskId: string) => Promise<void>
  listSessions?: (options?: PaginationOptions) => Promise<FoggyNavigatorSessionPage>
  getSessionMessages?: (
    contextId: string,
    options?: PaginationOptions,
  ) => Promise<FoggyNavigatorSessionMessagesPage>
  deleteSession?: (contextId: string) => Promise<void>
  loadExecutionReportMarkdown?: ExecutionReportMarkdownLoader
  submitSuspensionDecision?: (payload: BusinessSuspensionDecisionPayload) => Promise<void>
}

export interface FoggyNavigatorChatConfig {
  baseUrl: string
  agentId: string
  pollInterval?: number
  timeout?: number
  maxTurns?: number
  headers?: Record<string, string> | (() => Record<string, string> | Promise<Record<string, string>>)
  mode?: FoggyNavigatorChatMode
  showToolCalls?: boolean
  showToolResults?: boolean
  showRuntimeEvents?: boolean
  executionReportMarkdownLoader?: ExecutionReportMarkdownLoader
}
