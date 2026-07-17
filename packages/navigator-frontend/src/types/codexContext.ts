export type CodexContextUsageStatus = 'known' | 'window_unknown' | 'unknown'

export interface CodexContextUsage {
  taskId: string
  sessionId: string
  codexThreadId: string
  schema_version?: number
  turn_id?: string | null
  observed_at?: string | null
  status: CodexContextUsageStatus
  current_tokens?: number | null
  model_context_window?: number | null
  remaining_tokens?: number | null
}

export type CodexContextCompactStatus = 'running' | 'completed' | 'failed' | 'unknown'

export interface CodexContextCompactOperation {
  taskId: string
  sessionId: string
  codexThreadId: string
  operation_id: string
  status: CodexContextCompactStatus
  turn_id?: string | null
  started_at?: string
  completed_at?: string | null
  error_code?: string | null
}
