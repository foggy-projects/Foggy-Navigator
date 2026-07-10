import type { CodexApprovalPolicy, CodexSandboxMode, CodexWebSearchMode, ImageAttachment, NavigatorAttachment, QueryRequest } from '../models.js'
import { normalizeCodexReasoningEffort } from '../codex/reasoning.js'

const MAX_PROMPT_LENGTH = 200_000
const MAX_PATH_LENGTH = 4_096
const MAX_MODEL_LENGTH = 128
const MAX_SESSION_ID_LENGTH = 256
const MAX_API_KEY_LENGTH = 512
const MAX_CODEX_HOME_KEY_LENGTH = 512
const MAX_DEVELOPER_INSTRUCTIONS_LENGTH = 64_000
const MAX_JSON_OBJECT_LENGTH = 200_000
const MAX_IMAGE_NAME_LENGTH = 255
const MAX_IMAGE_COUNT = 20
const MAX_ATTACHMENT_COUNT = 20
const MAX_ADDITIONAL_DIRECTORY_COUNT = 16
const VALID_SANDBOX_MODES = new Set<CodexSandboxMode>(['read-only', 'workspace-write', 'danger-full-access'])
const VALID_APPROVAL_POLICIES = new Set<CodexApprovalPolicy>(['never', 'on-request', 'on-failure', 'untrusted'])
const VALID_WEB_SEARCH_MODES = new Set<CodexWebSearchMode>(['disabled', 'cached', 'live'])

type ValidationSuccess = {
  ok: true
  value: QueryRequest
}

type ValidationFailure = {
  ok: false
  error: string
}

export type QueryValidationResult = ValidationSuccess | ValidationFailure

function isValidationFailure(value: unknown): value is ValidationFailure {
  return Boolean(
    value &&
    typeof value === 'object' &&
    !Array.isArray(value) &&
    (value as { ok?: unknown }).ok === false &&
    typeof (value as { error?: unknown }).error === 'string'
  )
}

function validateOptionalString(value: unknown, field: string, maxLength: number): string | ValidationFailure | undefined {
  if (value === undefined) return undefined
  if (typeof value !== 'string') {
    return { ok: false, error: `${field} must be a string` }
  }

  const trimmed = value.trim()
  if (!trimmed) {
    return { ok: false, error: `${field} must not be empty` }
  }
  if (trimmed.length > maxLength) {
    return { ok: false, error: `${field} is too long` }
  }

  return trimmed
}

function validateImages(value: unknown): ImageAttachment[] | ValidationFailure | undefined {
  if (value === undefined) return undefined
  if (!Array.isArray(value)) {
    return { ok: false, error: 'images must be an array' }
  }
  if (value.length > MAX_IMAGE_COUNT) {
    return { ok: false, error: 'too many images' }
  }

  const normalized: ImageAttachment[] = []
  for (const [index, item] of value.entries()) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) {
      return { ok: false, error: `images[${index}] must be an object` }
    }
    const record = item as Record<string, unknown>
    if (typeof record.name !== 'string' || !record.name.trim()) {
      return { ok: false, error: `images[${index}].name is required` }
    }
    if (record.name.trim().length > MAX_IMAGE_NAME_LENGTH) {
      return { ok: false, error: `images[${index}].name is too long` }
    }
    if (typeof record.data !== 'string' || !record.data.trim()) {
      return { ok: false, error: `images[${index}].data is required` }
    }
    if (record.mime_type !== undefined && typeof record.mime_type !== 'string') {
      return { ok: false, error: `images[${index}].mime_type must be a string` }
    }
    normalized.push({
      name: record.name.trim(),
      data: record.data.trim(),
      mime_type: typeof record.mime_type === 'string' && record.mime_type.trim()
        ? record.mime_type.trim()
        : undefined,
    })
  }

  return normalized
}

function validateAttachments(value: unknown): NavigatorAttachment[] | ValidationFailure | undefined {
  if (value === undefined) return undefined
  if (!Array.isArray(value)) {
    return { ok: false, error: 'attachments must be an array' }
  }
  if (value.length > MAX_ATTACHMENT_COUNT) {
    return { ok: false, error: 'too many attachments' }
  }

  const normalized: NavigatorAttachment[] = []
  for (const [index, item] of value.entries()) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) {
      return { ok: false, error: `attachments[${index}] must be an object` }
    }
    normalized.push({ ...(item as Record<string, unknown>) })
  }

  return normalized
}

function validateOptionalObject(value: unknown, field: string): Record<string, unknown> | ValidationFailure | undefined {
  if (value === undefined) return undefined
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return { ok: false, error: `${field} must be a JSON object` }
  }
  if (JSON.stringify(value).length > MAX_JSON_OBJECT_LENGTH) {
    return { ok: false, error: `${field} is too large` }
  }
  return { ...(value as Record<string, unknown>) }
}

function validateOptionalEnum<T extends string>(
  value: unknown,
  field: string,
  allowed: Set<T>
): T | ValidationFailure | undefined {
  if (value === undefined) return undefined
  if (typeof value !== 'string') {
    return { ok: false, error: `${field} must be a string` }
  }
  const trimmed = value.trim()
  if (!allowed.has(trimmed as T)) {
    return { ok: false, error: `${field} is not supported` }
  }
  return trimmed as T
}

function validateOptionalBoolean(value: unknown, field: string): boolean | ValidationFailure | undefined {
  if (value === undefined) return undefined
  if (typeof value !== 'boolean') {
    return { ok: false, error: `${field} must be a boolean` }
  }
  return value
}

function validateOptionalStringArray(value: unknown, field: string): string[] | ValidationFailure | undefined {
  if (value === undefined) return undefined
  if (!Array.isArray(value)) {
    return { ok: false, error: `${field} must be an array` }
  }
  if (value.length > MAX_ADDITIONAL_DIRECTORY_COUNT) {
    return { ok: false, error: `${field} contains too many entries` }
  }
  const normalized: string[] = []
  for (const [index, item] of value.entries()) {
    if (typeof item !== 'string' || !item.trim()) {
      return { ok: false, error: `${field}[${index}] must be a non-empty string` }
    }
    const trimmed = item.trim()
    if (trimmed.length > MAX_PATH_LENGTH) {
      return { ok: false, error: `${field}[${index}] is too long` }
    }
    normalized.push(trimmed)
  }
  return normalized
}

export function validateModelString(value: string): true | string {
  const parts = value.split(':')
  if (parts.length > 2) return 'model format must be "<model>" or "<model>:<reasoning_level>"'

  const modelName = parts[0]?.trim()
  if (!modelName) return 'model must include a model name'

  if (parts.length === 2) {
    const reasoningLevel = parts[1]?.trim()
    if (!reasoningLevel) return 'model reasoning level must not be empty'
    if (!normalizeCodexReasoningEffort(reasoningLevel)) {
      return 'unsupported model reasoning level'
    }
  }

  return true
}

export function validateQueryRequest(input: unknown): QueryValidationResult {
  if (!input || typeof input !== 'object' || Array.isArray(input)) {
    return { ok: false, error: 'request body must be a JSON object' }
  }

  const body = input as Record<string, unknown>
  if (typeof body.prompt !== 'string' || !body.prompt.trim()) {
    return { ok: false, error: 'prompt is required' }
  }

  const prompt = body.prompt.trim()
  if (prompt.length > MAX_PROMPT_LENGTH) {
    return { ok: false, error: 'prompt is too long' }
  }

  const cwd = validateOptionalString(body.cwd, 'cwd', MAX_PATH_LENGTH)
  if (cwd && typeof cwd !== 'string') return cwd

  const sessionId = validateOptionalString(body.session_id, 'session_id', MAX_SESSION_ID_LENGTH)
  if (sessionId && typeof sessionId !== 'string') return sessionId

  const apiKey = validateOptionalString(body.api_key, 'api_key', MAX_API_KEY_LENGTH)
  if (apiKey && typeof apiKey !== 'string') return apiKey

  const baseUrl = validateOptionalString(body.base_url, 'base_url', MAX_API_KEY_LENGTH)
  if (baseUrl && typeof baseUrl !== 'string') return baseUrl

  const codexHomeKey = validateOptionalString(body.codex_home_key, 'codex_home_key', MAX_CODEX_HOME_KEY_LENGTH)
  if (codexHomeKey && typeof codexHomeKey !== 'string') return codexHomeKey

  const developerInstructions = validateOptionalString(
    body.developer_instructions,
    'developer_instructions',
    MAX_DEVELOPER_INSTRUCTIONS_LENGTH
  )
  if (developerInstructions && typeof developerInstructions !== 'string') return developerInstructions

  const model = validateOptionalString(body.model, 'model', MAX_MODEL_LENGTH)
  if (model && typeof model !== 'string') return model
  if (typeof model === 'string') {
    const modelValidation = validateModelString(model)
    if (modelValidation !== true) {
      return { ok: false, error: modelValidation }
    }
  }

  const maxTurns = body.max_turns
  if (maxTurns !== undefined && (!Number.isInteger(maxTurns) || typeof maxTurns !== 'number' || maxTurns < 1)) {
    return { ok: false, error: 'max_turns must be a positive integer' }
  }

  const images = validateImages(body.images)
  if (images && !Array.isArray(images)) return images

  const attachments = validateAttachments(body.attachments)
  if (attachments && !Array.isArray(attachments)) return attachments

  const outputSchema = validateOptionalObject(body.output_schema, 'output_schema')
  if (isValidationFailure(outputSchema)) return outputSchema

  const codexConfig = validateOptionalObject(body.codex_config, 'codex_config')
  if (isValidationFailure(codexConfig)) return codexConfig

  const sandboxMode = validateOptionalEnum(body.sandbox_mode, 'sandbox_mode', VALID_SANDBOX_MODES)
  if (sandboxMode && typeof sandboxMode !== 'string') return sandboxMode

  const approvalPolicy = validateOptionalEnum(body.approval_policy, 'approval_policy', VALID_APPROVAL_POLICIES)
  if (approvalPolicy && typeof approvalPolicy !== 'string') return approvalPolicy

  const networkAccessEnabled = validateOptionalBoolean(body.network_access_enabled, 'network_access_enabled')
  if (networkAccessEnabled && typeof networkAccessEnabled !== 'boolean') return networkAccessEnabled

  const webSearchMode = validateOptionalEnum(body.web_search_mode, 'web_search_mode', VALID_WEB_SEARCH_MODES)
  if (webSearchMode && typeof webSearchMode !== 'string') return webSearchMode

  const businessRuntimeContext = validateOptionalObject(body.business_runtime_context, 'business_runtime_context')
  if (isValidationFailure(businessRuntimeContext)) return businessRuntimeContext

  const additionalDirectories = validateOptionalStringArray(body.additional_directories, 'additional_directories')
  if (additionalDirectories && !Array.isArray(additionalDirectories)) return additionalDirectories

  const value: QueryRequest = { prompt }

  if (cwd !== undefined) {
    value.cwd = cwd
  }
  if (sessionId !== undefined) {
    value.session_id = sessionId
  }
  if (model !== undefined) {
    value.model = model
  }
  if (maxTurns !== undefined) {
    value.max_turns = maxTurns
  }
  if (images !== undefined) {
    value.images = images
  }
  if (attachments !== undefined) {
    value.attachments = attachments
  }
  if (apiKey !== undefined) {
    value.api_key = apiKey
  }
  if (baseUrl !== undefined) {
    value.base_url = baseUrl
  }
  if (body.env_vars !== undefined) {
    value.env_vars = body.env_vars as Record<string, string>
  }
  if (codexHomeKey !== undefined) {
    value.codex_home_key = codexHomeKey
  }
  if (developerInstructions !== undefined) {
    value.developer_instructions = developerInstructions
  }
  if (outputSchema !== undefined) {
    value.output_schema = outputSchema as Record<string, unknown>
  }
  if (codexConfig !== undefined) {
    value.codex_config = codexConfig as Record<string, unknown>
  }
  if (sandboxMode !== undefined) {
    value.sandbox_mode = sandboxMode
  }
  if (approvalPolicy !== undefined) {
    value.approval_policy = approvalPolicy
  }
  if (networkAccessEnabled !== undefined) {
    value.network_access_enabled = networkAccessEnabled
  }
  if (webSearchMode !== undefined) {
    value.web_search_mode = webSearchMode
  }
  if (businessRuntimeContext !== undefined) {
    value.business_runtime_context = businessRuntimeContext as Record<string, unknown>
  }
  if (additionalDirectories !== undefined) {
    value.additional_directories = additionalDirectories
  }

  return {
    ok: true,
    value,
  }
}
