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

export type AppServerRequestId = string | number

export interface UserInputOption {
  label: string
  description: string
}

export interface UserInputQuestion {
  id: string
  header: string
  question: string
  options?: UserInputOption[]
  is_other: boolean
  is_secret: boolean
}

export interface PendingUserInputInteraction {
  contract_version: 1
  request_id: AppServerRequestId
  method: 'item/tool/requestUserInput'
  thread_id: string
  turn_id: string
  item_id: string
  questions: UserInputQuestion[]
  auto_resolution_ms?: number
  runtime_instance_id: string
  created_at: string
}

export interface ResolvedUserInputInteraction {
  contract_version: 1
  request_id: AppServerRequestId
  thread_id: string
  turn_id: string
  runtime_instance_id: string
  state: 'answered' | 'auto_resolved' | 'cleared' | 'failed'
  resolved_at: string
}

export interface UserInputRequestData extends PendingUserInputInteraction {}

export interface UserInputResolvedData {
  contract_version: 1
  request_id: AppServerRequestId
  reason: 'answered' | 'auto_resolved' | 'cleared'
}

export interface GeneratedImageData {
  contract_version: 1
  artifact_id: string
  file_name: string
  local_path: string
  mime_type: string
  size_bytes: number
  sha256: string
  revised_prompt?: string
}

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
  requested_thread_id?: string
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
  pending_interaction?: PendingUserInputInteraction
  last_interaction?: ResolvedUserInputInteraction
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
    | 'user_input_request' | 'user_input_resolved' | 'image_generation'
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
  data?: NativeSubtaskUpdateData | UserInputRequestData | UserInputResolvedData | GeneratedImageData
  seq?: number
}
