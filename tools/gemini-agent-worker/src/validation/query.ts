import type { ImageAttachment, QueryRequest } from '../models.js'

const MAX_PROMPT_LENGTH = 200_000
const MAX_PATH_LENGTH = 4_096
const MAX_MODEL_LENGTH = 128
const MAX_SESSION_ID_LENGTH = 256
const MAX_API_KEY_LENGTH = 512
const MAX_IMAGE_NAME_LENGTH = 255
const MAX_IMAGE_COUNT = 20

type ValidationSuccess = {
  ok: true
  value: QueryRequest
}

type ValidationFailure = {
  ok: false
  error: string
}

export type QueryValidationResult = ValidationSuccess | ValidationFailure

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

  const model = validateOptionalString(body.model, 'model', MAX_MODEL_LENGTH)
  if (model && typeof model !== 'string') return model

  const modelAlias = validateOptionalString(body.model_alias, 'model_alias', MAX_MODEL_LENGTH)
  if (modelAlias && typeof modelAlias !== 'string') return modelAlias

  const maxTurns = body.max_turns
  if (maxTurns !== undefined && (!Number.isInteger(maxTurns) || typeof maxTurns !== 'number' || maxTurns < 1)) {
    return { ok: false, error: 'max_turns must be a positive integer' }
  }

  const skipTrust = body.skip_trust
  if (skipTrust !== undefined && typeof skipTrust !== 'boolean') {
    return { ok: false, error: 'skip_trust must be a boolean' }
  }

  const images = validateImages(body.images)
  if (images && !Array.isArray(images)) return images

  const value: QueryRequest = { prompt }
  if (cwd !== undefined) value.cwd = cwd
  if (sessionId !== undefined) value.session_id = sessionId
  if (apiKey !== undefined) value.api_key = apiKey
  if (baseUrl !== undefined) value.base_url = baseUrl
  if (model !== undefined) value.model = model
  if (modelAlias !== undefined) value.model_alias = modelAlias
  if (maxTurns !== undefined) value.max_turns = maxTurns
  if (images !== undefined) value.images = images
  if (body.env_vars !== undefined) value.env_vars = body.env_vars as Record<string, string>
  if (skipTrust !== undefined) value.skip_trust = skipTrust

  return { ok: true, value }
}
