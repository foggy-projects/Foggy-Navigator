import type { QueryRequest } from '../models.js'

const MAX_PROMPT_LENGTH = 200_000
const MAX_PATH_LENGTH = 4_096
const MAX_MODEL_LENGTH = 128
const MAX_SESSION_ID_LENGTH = 256
const MAX_API_KEY_LENGTH = 512
const VALID_REASONING_LEVELS = new Set(['minimal', 'low', 'medium', 'high', 'xhigh', 'extra-high'])

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

export function validateModelString(value: string): true | string {
  const parts = value.split(':')
  if (parts.length > 2) return 'model format must be "<model>" or "<model>:<reasoning_level>"'

  const modelName = parts[0]?.trim()
  if (!modelName) return 'model must include a model name'

  if (parts.length === 2) {
    const reasoningLevel = parts[1]?.trim()
    if (!reasoningLevel) return 'model reasoning level must not be empty'
    if (!VALID_REASONING_LEVELS.has(reasoningLevel)) {
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

  return {
    ok: true,
    value: {
      prompt,
      cwd,
      session_id: sessionId,
      model,
      max_turns: maxTurns as number | undefined,
      api_key: apiKey,
    },
  }
}
