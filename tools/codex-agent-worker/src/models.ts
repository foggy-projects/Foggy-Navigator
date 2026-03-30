export interface ImageAttachment {
  name: string
  data: string
  mime_type?: string
}

/**
 * Query request body
 */
export interface QueryRequest {
  prompt: string
  cwd?: string
  session_id?: string  // Codex thread ID for session resume
  model?: string
  max_turns?: number
  images?: ImageAttachment[] // Historical field name; payload may include non-image attachments too.
  api_key?: string     // Per-request OpenAI API key override
  base_url?: string    // Per-request OpenAI base URL override
}

/**
 * Worker SSE event — matches Claude Worker's WorkerEvent JSON format exactly
 */
export interface WorkerEvent {
  type: 'assistant_text' | 'tool_use' | 'tool_result' | 'result' | 'error'
  task_id: string
  session_id?: string    // Codex thread ID
  content?: string
  tool?: string
  input?: Record<string, unknown>
  output?: string
  result?: string
  cost_usd?: number
  duration_ms?: number
  input_tokens?: number
  output_tokens?: number
  num_turns?: number
  model?: string
  error?: string
  tool_use_id?: string
  is_error?: boolean
  subtype?: string
  seq?: number
}

/**
 * Health check response
 */
export interface HealthResponse {
  status: string
  hostname: string
  version: string
  worker_name: string
  active_tasks: number
  codex_sdk_available: boolean
  codex_auth_configured?: boolean
  codex_auth_mode?: 'api_key' | 'codex_login' | 'none'
}

/**
 * Task registry entry
 */
export interface TaskEntry {
  taskId: string
  status: 'running' | 'completed' | 'failed' | 'aborted'
  abortController?: AbortController
  threadId?: string
  pid?: number
  model?: string
  startedAt: number
  completedAt?: number
}

export interface CliProcessInfo {
  pid: number
  command: string
  memory_mb: number
  started_at: string
  is_orphan: boolean
  process_type: 'codex'
  codex_thread_id?: string
  model?: string
}

/**
 * Session info from Codex
 */
export interface SessionInfo {
  session_id: string
  thread_id?: string
  created_at?: string
  last_active?: string
}
