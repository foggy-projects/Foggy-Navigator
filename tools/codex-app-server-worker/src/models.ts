export type CodexSandboxMode = 'read-only' | 'workspace-write' | 'danger-full-access'
export type CodexApprovalPolicy = 'never' | 'on-request' | 'untrusted'
export type CodexWebSearchMode = 'disabled' | 'cached' | 'live'
export type CodexReasoningEffort = 'minimal' | 'low' | 'medium' | 'high' | 'xhigh' | 'max' | 'ultra'

export type CodexInput = string | Array<
  | { type: 'text'; text: string }
  | { type: 'local_image'; path: string }
>

export interface ImageAttachment {
  name: string
  data: string
  mime_type?: string
}

export interface TaskRequest {
  prompt: string
  cwd?: string
  session_id?: string
  model?: string
  max_turns?: number
  images?: ImageAttachment[]
  attachments?: Array<Record<string, unknown>>
  api_key?: string
  base_url?: string
  env_vars?: Record<string, string>
  codex_home_key?: string
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

export type TaskPhase = 'accepted' | 'starting' | 'committed' | 'running' | 'terminal'
export type TaskOutcome = 'completed' | 'failed' | 'aborted'

export interface EncryptedPayload {
  algorithm: 'aes-256-gcm'
  iv: string
  auth_tag: string
  ciphertext: string
}

export interface StoredTaskRecord {
  schema_version: 1
  task_id: string
  request_hash: string
  request_payload?: EncryptedPayload
  status: TaskPhase
  outcome?: TaskOutcome
  error_code?: string
  thread_id?: string
  turn_id?: string
  app_server_instance_id?: string
  app_server_lane_key?: string
  model?: string
  reasoning_effort?: CodexReasoningEffort
  created_at: string
  updated_at: string
  terminal_at?: string
  recovery_required?: boolean
  abort_requested_at?: string
  tombstoned_at?: string
}

export type NativeSubtaskStatus = 'pending' | 'running' | 'completed' | 'failed' | 'interrupted'
export const NATIVE_SUBTASK_FAILURE_CODE = 'NATIVE_SUBTASK_FAILED' as const

export interface NativeSubtaskUpdateData {
  contract_version: 1
  subtask_id: string
  parent_subtask_id?: string
  depth?: number
  label?: string
  role?: string
  status: NativeSubtaskStatus
  activity?: 'started' | 'interacted' | 'interrupted'
  message?: typeof NATIVE_SUBTASK_FAILURE_CODE
  started_at?: string
  updated_at?: string
  completed_at?: string
}

export interface WorkerEvent {
  type: 'assistant_text' | 'tool_use' | 'tool_result' | 'result' | 'error' | 'native_subtask_update'
  task_id: string
  session_id?: string
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
  data?: NativeSubtaskUpdateData
  seq?: number
}
