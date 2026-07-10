const ALLOWED_CODEX_CONFIG_KEYS = new Set([
  'model_context_window',
  'model_auto_compact_token_limit',
  'tool_output_token_limit',
])

const MAX_CONFIG_INTEGER = 10_000_000

export type CodexConfigValidation =
  | { ok: true; value: Record<string, number> }
  | { ok: false; error: 'UNSUPPORTED_CODEX_CONFIG_KEY' | 'INVALID_CODEX_CONFIG_VALUE' }

export function validateCodexConfigOverride(value: unknown): CodexConfigValidation {
  if (value === undefined) return { ok: true, value: {} }
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return { ok: false, error: 'INVALID_CODEX_CONFIG_VALUE' }
  }
  const result: Record<string, number> = {}
  for (const [key, item] of Object.entries(value as Record<string, unknown>)) {
    if (!ALLOWED_CODEX_CONFIG_KEYS.has(key)) {
      return { ok: false, error: 'UNSUPPORTED_CODEX_CONFIG_KEY' }
    }
    if (!Number.isSafeInteger(item) || (item as number) < 1 || (item as number) > MAX_CONFIG_INTEGER) {
      return { ok: false, error: 'INVALID_CODEX_CONFIG_VALUE' }
    }
    result[key] = item as number
  }
  return { ok: true, value: result }
}

export function requireCodexConfigOverride(value: unknown): Record<string, number> {
  const validated = validateCodexConfigOverride(value)
  if (validated.ok) return validated.value
  const error = new Error(validated.error) as Error & { code: string }
  error.code = validated.error
  throw error
}
