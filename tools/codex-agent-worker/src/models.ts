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
  /** Observed Codex CLI PID when the Worker can safely associate one. */
  pid?: number
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
  /**
   * Non-terminal lifecycle attention.  These fields are additive so older
   * Navigator clients can continue to consume the existing event shape.
   */
  attention?: TaskAttention[]
  attention_status?: TaskAttentionCode
  available_actions?: TaskAvailableAction[]
  lifecycle_state?: TaskLifecycleState
  /**
   * `error` is ordinarily diagnostic-only.  Java may treat it as terminal
   * only when the Worker explicitly supplies both of these fields from a
   * provider terminal event or a verified process exit.
   */
  terminal_observed?: boolean
  terminal_status?: 'FAILED' | 'ABORTED'
  /** Safe provenance label, such as PROVIDER_TERMINAL_EVENT. */
  terminal_source?: string
  termination_operation?: TerminationOperationSummary
  seq?: number
}

/**
 * A task can be actively executing even when a cancellation request is in
 * flight.  Only a provider terminal event or a verified process exit may
 * transition it to a terminal state.
 */
export type TaskLifecycleState =
  | 'RUNNING'
  | 'CANCEL_REQUESTED'
  | 'COMPLETED'
  | 'FAILED'
  | 'ABORTED'

export type TaskStatus = 'running' | 'cancel_requested' | 'completed' | 'failed' | 'aborted'

export type TaskAttentionCode =
  | 'TIMEOUT_PENDING_DECISION'
  | 'PROCESS_UNVERIFIED'
  | 'WORKER_DRAINING_PENDING_DECISION'
  | 'TERMINATION_UNCONFIRMED'
  | 'CANCELLATION_PENDING_CONFIRMATION'

export type TaskAvailableAction = 'CONTINUE_WAIT' | 'QUERY_DIAGNOSTICS' | 'CANCEL'

export interface TaskAttention {
  code: TaskAttentionCode
  message: string
  source: string
  occurred_at: string
  recoverable: true
}

export type TerminationOperationKind = 'REMOTE_CANCEL' | 'MANUAL_PID_KILL' | 'RECONCILE_CANCEL'
export type TerminationOperationOrigin = 'UPSTREAM_USER' | 'UPSTREAM_SYSTEM' | 'ADMIN_MANUAL'
export type TerminationOperationStatus =
  | 'CANCEL_REQUESTED'
  | 'OBSERVED_EXIT'
  | 'UNCONFIRMED'

/**
 * Safe, non-secret operation summary attached to status and lifecycle SSE.
 * The signed wire payload is validated before this object is ever persisted
 * in task memory.
 */
export interface TerminationOperationSummary {
  operation_id: string
  /** Task id bound by the verified signed operation. */
  task_id: string
  /** Stable Navigator PhysicalWorker identity the signed operation targets. */
  worker_id: string
  kind: TerminationOperationKind
  origin: TerminationOperationOrigin
  actor_id: string
  actor_type: string
  authorization_decision_id: string
  reason_code: string
  correlation_id: string
  expected_pid?: number
  /** Immutable OS process identity paired with expected_pid for manual kill. */
  expected_process_identity?: string
  requested_at: string
  status: TerminationOperationStatus
  observed_at?: string
  result?: string
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
  codex_home_source?: 'worker_config' | 'user_default'
  codex_home_auth_configured?: boolean
  codex_biz_home_root_configured?: boolean
  codex_biz_scoped_home_ready?: boolean
  /** Termination readiness is independent from normal Codex execution readiness. */
  termination_ready: boolean
  termination_reasons: string[]
  termination_worker_id_configured: boolean
  termination_auth_configured: boolean
  termination_replay_ledger_ready: boolean
}

/**
 * Task registry entry
 */
export interface TaskEntry {
  taskId: string
  status: TaskStatus
  abortController?: AbortController
  cancelExecution?: (operation: TerminationOperationSummary) => void
  /** True only after the SDK execution loop has stopped and cannot spawn later work. */
  sdkExecutionSettled?: boolean
  threadId?: string
  pid?: number
  processStartedAt?: string
  model?: string
  startedAt: number
  lastProgressAt?: number
  completedAt?: number
  attention?: TaskAttention[]
  availableActions?: TaskAvailableAction[]
  terminationOperation?: TerminationOperationSummary
}

export function isTaskExecutionActive(status: TaskStatus): boolean {
  return status === 'running' || status === 'cancel_requested'
}

export function isTaskTerminal(status: TaskStatus): boolean {
  return !isTaskExecutionActive(status)
}

export function toTaskLifecycleState(status: TaskStatus): TaskLifecycleState {
  switch (status) {
    case 'cancel_requested':
      return 'CANCEL_REQUESTED'
    case 'completed':
      return 'COMPLETED'
    case 'failed':
      return 'FAILED'
    case 'aborted':
      return 'ABORTED'
    default:
      return 'RUNNING'
  }
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
