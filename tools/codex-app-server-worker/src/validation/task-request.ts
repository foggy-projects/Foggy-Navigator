import type {
  CodexApprovalPolicy,
  CodexSandboxMode,
  CodexWebSearchMode,
  ImageAttachment,
  TaskRequest,
} from '../models.js'
import { validateCodexConfigOverride } from '../codex-config.js'

const MAX_PROMPT = 200_000
const MAX_PATH = 4_096
const MAX_JSON = 200_000
const SANDBOX = new Set<CodexSandboxMode>(['read-only', 'workspace-write', 'danger-full-access'])
const APPROVAL = new Set<CodexApprovalPolicy>(['never'])
const WEB = new Set<CodexWebSearchMode>(['disabled', 'cached', 'live'])
const ENV_VAR_KEYS = new Set([
  'model_context_window',
  'model_auto_compact_token_limit',
  'tool_output_token_limit',
])
const REQUEST_FIELDS = new Set([
  'prompt', 'cwd', 'session_id', 'model', 'max_turns', 'images', 'attachments',
  'api_key', 'base_url', 'env_vars', 'codex_home_key', 'developer_instructions',
  'output_schema', 'codex_config', 'sandbox_mode', 'approval_policy',
  'network_access_enabled', 'web_search_mode', 'business_runtime_context',
  'additional_directories',
])

export type ValidationResult = { ok: true; value: TaskRequest } | { ok: false; error: string }

export function validateTaskRequest(input: unknown): ValidationResult {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return failure('request body must be an object')
  const body = input as Record<string, unknown>
  if (Object.keys(body).some(key => !REQUEST_FIELDS.has(key))) return failure('UNSUPPORTED_REQUEST_FIELD')
  const prompt = requiredString(body.prompt, 'prompt', MAX_PROMPT)
  if (typeof prompt !== 'string') return prompt

  const result: TaskRequest = { prompt }
  const stringFields: Array<[keyof TaskRequest, string, number]> = [
    ['cwd', 'cwd', MAX_PATH],
    ['session_id', 'session_id', 256],
    ['model', 'model', 128],
    ['api_key', 'api_key', 512],
    ['base_url', 'base_url', 2_048],
    ['codex_home_key', 'codex_home_key', 512],
    ['developer_instructions', 'developer_instructions', 64_000],
  ]
  for (const [key, field, max] of stringFields) {
    const value = optionalString(body[field], field, max)
    if (isFailure(value)) return value
    if (value !== undefined) (result as unknown as Record<string, unknown>)[key] = value
  }

  if (body.max_turns !== undefined) {
    if (!Number.isInteger(body.max_turns) || (body.max_turns as number) < 1 || (body.max_turns as number) > 1_000) {
      return failure('max_turns must be an integer between 1 and 1000')
    }
    if ((body.max_turns as number) > 1) return failure('UNSUPPORTED_MAX_TURNS')
    result.max_turns = body.max_turns as number
  }

  const images = validateImages(body.images)
  if (isFailure(images)) return images
  result.images = images

  const attachments = optionalObjectArray(body.attachments, 'attachments', 20)
  if (isFailure(attachments)) return attachments
  result.attachments = attachments

  const objectFields: Array<[keyof TaskRequest, string]> = [
    ['env_vars', 'env_vars'],
    ['output_schema', 'output_schema'],
    ['business_runtime_context', 'business_runtime_context'],
  ]
  for (const [key, field] of objectFields) {
    const value = optionalObject(body[field], field)
    if (isFailure(value)) return value
    if (value !== undefined) (result as unknown as Record<string, unknown>)[key] = value
  }
  if (result.env_vars && Object.values(result.env_vars).some(value => typeof value !== 'string')) {
    return failure('env_vars values must be strings')
  }
  if (result.env_vars && Object.keys(result.env_vars).some(key => !ENV_VAR_KEYS.has(key))) {
    return failure('UNSUPPORTED_ENV_VARS')
  }
  if (body.codex_config !== undefined && JSON.stringify(body.codex_config).length > MAX_JSON) {
    return failure('codex_config is too large')
  }
  const codexConfig = validateCodexConfigOverride(body.codex_config)
  if (!codexConfig.ok) return failure(codexConfig.error)
  if (body.codex_config !== undefined) result.codex_config = codexConfig.value

  const sandbox = optionalEnum(body.sandbox_mode, 'sandbox_mode', SANDBOX)
  if (isFailure(sandbox)) return sandbox
  result.sandbox_mode = sandbox
  if (body.approval_policy !== undefined && body.approval_policy !== 'never') {
    return failure('UNSUPPORTED_APPROVAL_POLICY')
  }
  const approval = optionalEnum(body.approval_policy, 'approval_policy', APPROVAL)
  if (isFailure(approval)) return approval
  result.approval_policy = approval
  const web = optionalEnum(body.web_search_mode, 'web_search_mode', WEB)
  if (isFailure(web)) return web
  result.web_search_mode = web

  if (body.network_access_enabled !== undefined) {
    if (typeof body.network_access_enabled !== 'boolean') return failure('network_access_enabled must be a boolean')
    result.network_access_enabled = body.network_access_enabled
  }
  const directories = optionalStringArray(body.additional_directories, 'additional_directories', 16, MAX_PATH)
  if (isFailure(directories)) return directories
  result.additional_directories = directories

  if (result.attachments?.length) return failure('UNSUPPORTED_ATTACHMENTS')
  if (result.business_runtime_context) return failure('UNSUPPORTED_BUSINESS_RUNTIME_CONTEXT')
  if (result.additional_directories?.length) return failure('UNSUPPORTED_ADDITIONAL_DIRECTORIES')
  return {
    ok: true,
    value: Object.fromEntries(
      Object.entries(result).filter(([, item]) => item !== undefined),
    ) as unknown as TaskRequest,
  }
}

function validateImages(value: unknown): ImageAttachment[] | ReturnType<typeof failure> | undefined {
  if (value === undefined) return undefined
  if (!Array.isArray(value) || value.length > 20) return failure('images must be an array with at most 20 entries')
  const images: ImageAttachment[] = []
  for (const [index, item] of value.entries()) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) return failure(`images[${index}] must be an object`)
    const record = item as Record<string, unknown>
    const name = requiredString(record.name, `images[${index}].name`, 255)
    if (typeof name !== 'string') return name
    const data = requiredString(record.data, `images[${index}].data`, 20_000_000)
    if (typeof data !== 'string') return data
    const mime = optionalString(record.mime_type, `images[${index}].mime_type`, 128)
    if (isFailure(mime)) return mime
    images.push({ name, data, mime_type: mime })
  }
  return images
}

function requiredString(value: unknown, field: string, max: number): string | ReturnType<typeof failure> {
  if (typeof value !== 'string' || !value.trim()) return failure(`${field} is required`)
  const normalized = value.trim()
  return normalized.length <= max ? normalized : failure(`${field} is too long`)
}

function optionalString(value: unknown, field: string, max: number): string | ReturnType<typeof failure> | undefined {
  if (value === undefined) return undefined
  if (typeof value !== 'string' || !value.trim()) return failure(`${field} must be a non-empty string`)
  const normalized = value.trim()
  return normalized.length <= max ? normalized : failure(`${field} is too long`)
}

function optionalObject(value: unknown, field: string): Record<string, unknown> | ReturnType<typeof failure> | undefined {
  if (value === undefined) return undefined
  if (!value || typeof value !== 'object' || Array.isArray(value)) return failure(`${field} must be an object`)
  if (JSON.stringify(value).length > MAX_JSON) return failure(`${field} is too large`)
  return { ...(value as Record<string, unknown>) }
}

function optionalObjectArray(value: unknown, field: string, max: number): Array<Record<string, unknown>> | ReturnType<typeof failure> | undefined {
  if (value === undefined) return undefined
  if (!Array.isArray(value) || value.length > max) return failure(`${field} must be an array with at most ${max} entries`)
  if (value.some(item => !item || typeof item !== 'object' || Array.isArray(item))) return failure(`${field} entries must be objects`)
  return value.map(item => ({ ...(item as Record<string, unknown>) }))
}

function optionalEnum<T extends string>(value: unknown, field: string, allowed: Set<T>): T | ReturnType<typeof failure> | undefined {
  if (value === undefined) return undefined
  return typeof value === 'string' && allowed.has(value as T) ? value as T : failure(`${field} is not supported`)
}

function optionalStringArray(value: unknown, field: string, count: number, length: number): string[] | ReturnType<typeof failure> | undefined {
  if (value === undefined) return undefined
  if (!Array.isArray(value) || value.length > count) return failure(`${field} must be an array with at most ${count} entries`)
  const values: string[] = []
  for (const item of value) {
    const normalized = optionalString(item, field, length)
    if (isFailure(normalized) || normalized === undefined) return isFailure(normalized) ? normalized : failure(`${field} is invalid`)
    values.push(normalized)
  }
  return values
}

function failure(error: string): { ok: false; error: string } {
  return { ok: false, error }
}

function isFailure(value: unknown): value is ReturnType<typeof failure> {
  return Boolean(value && typeof value === 'object' && (value as { ok?: unknown }).ok === false)
}
