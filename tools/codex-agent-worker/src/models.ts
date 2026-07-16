export interface ImageAttachment {
  name: string
  data: string
  mime_type?: string
}

export type NavigatorAttachment = Record<string, unknown>

export type CodexSandboxMode = 'read-only' | 'workspace-write' | 'danger-full-access'
export type CodexApprovalPolicy = 'never' | 'on-request' | 'on-failure' | 'untrusted'
export type CodexWebSearchMode = 'disabled' | 'cached' | 'live'

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
  attachments?: NavigatorAttachment[] // URL-backed attachment metadata passed through from Navigator/TMS.
  api_key?: string     // Per-request OpenAI API key override
  base_url?: string    // Per-request OpenAI base URL override
  env_vars?: Record<string, string>  // Extra env vars (includes Codex CLI config like model_context_window)
  codex_home_key?: string // Logical account/actor key; resolved under CODEX_BIZ_HOME_ROOT.
  developer_instructions?: string
  output_schema?: Record<string, unknown>
  codex_config?: Record<string, unknown>
  sandbox_mode?: CodexSandboxMode
  approval_policy?: CodexApprovalPolicy
  network_access_enabled?: boolean
  web_search_mode?: CodexWebSearchMode
  business_runtime_context?: Record<string, unknown>
  additional_directories?: string[]
}

/**
 * Worker SSE event — matches Claude Worker's WorkerEvent JSON format exactly
 */
export interface WorkerEvent {
  type: 'assistant_text' | 'tool_use' | 'tool_result' | 'result' | 'warning' | 'error'
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
  error_code?: string
  error_message?: string
  error_category?: 'AUTHENTICATION' | 'AUTHORIZATION' | 'CONFIGURATION' | 'NETWORK' | 'RATE_LIMIT' | 'RUNTIME' | 'TIMEOUT' | 'CANCELLED' | 'UNKNOWN'
  runtime_phase?: 'REQUEST_VALIDATION' | 'TASK_ACCEPTANCE' | 'SESSION_INITIALIZATION' | 'TURN_EXECUTION' | 'TOOL_EXECUTION' | 'EVENT_STREAM' | 'RESULT_PERSISTENCE' | 'TASK_RECONCILIATION' | 'UNKNOWN'
  recoverable?: boolean
  diagnostic_ref?: string
  occurred_at?: string
  provider_type?: string
  runtime_type?: string
  exception_type?: string
  diagnostic_text?: string
  provider_status?: string
  http_status?: number
  retry_count?: number
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
  ready: boolean
  reasons: string[]
  mode: 'internal-dev' | 'external-enabled'
  external_enabled: boolean
  external_ready: boolean
  auth_configured: boolean
  hostname: string
  version: string
  worker_name: string
  active_tasks: number
  codex_sdk_available: boolean
  codex_sdk_version: string
  codex_sdk_minimum_version: string
  codex_sdk_compatible: boolean
  codex_auth_configured?: boolean
  codex_auth_mode?: 'api_key' | 'codex_login' | 'none'
  codex_biz_home_root_configured?: boolean
  codex_biz_scoped_home_ready?: boolean
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
