export interface ImageAttachment {
  name: string
  data: string
  mime_type?: string
}

export type NavigatorAttachment = Record<string, unknown>

export interface QueryRequest {
  prompt: string
  cwd?: string
  session_id?: string
  model?: string
  model_alias?: string
  max_turns?: number
  images?: ImageAttachment[]
  attachments?: NavigatorAttachment[]
  api_key?: string
  base_url?: string
  env_vars?: Record<string, string>
  skip_trust?: boolean
}

export interface WorkerEvent {
  type: 'assistant_text' | 'tool_use' | 'tool_result' | 'result' | 'error'
  task_id: string
  session_id?: string
  content?: string
  tool?: string
  input?: Record<string, unknown> | string
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

export interface GeminiCliJsonEvent {
  type?: string
  event?: string
  kind?: string
  role?: string
  content?: string
  text?: string
  message?: string
  session_id?: string
  model?: string
  tool?: string
  tool_name?: string
  tool_id?: string
  name?: string
  input?: Record<string, unknown>
  parameters?: Record<string, unknown>
  result?: unknown
  output?: string
  error?: string
  status?: string
  stats?: {
    duration_ms?: number
    input_tokens?: number
    output_tokens?: number
    total_tokens?: number
    input?: number
    models?: Record<string, {
      input_tokens?: number
      output_tokens?: number
      total_tokens?: number
      cached?: number
      input?: number
    }>
  }
  tool_use_id?: string
  id?: string
  is_error?: boolean
  [key: string]: unknown
}

export interface HealthResponse {
  status: string
  hostname: string
  version: string
  worker_name: string
  active_tasks: number
  gemini_cli_available: boolean
  gemini_auth_configured?: boolean
  gemini_auth_mode?: 'env' | 'local_login' | 'none'
  default_model?: string
  model_aliases?: Record<string, string>
}

export interface TaskEntry {
  taskId: string
  status: 'running' | 'completed' | 'failed' | 'aborted'
  abortController?: AbortController
  sessionId?: string
  pid?: number
  model?: string
  startedAt: number
  completedAt?: number
}

export interface SessionInfo {
  session_id: string
  created_at?: string
  last_active?: string
}
