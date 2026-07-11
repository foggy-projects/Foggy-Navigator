import type { CodexReasoningEffort } from './models.js'

const REASONING = new Set<CodexReasoningEffort>([
  'minimal', 'low', 'medium', 'high', 'xhigh', 'max', 'ultra',
])

export const UNSUPPORTED_CODEX_MODEL = 'UNSUPPORTED_CODEX_MODEL'

export class UnsupportedCodexModelError extends Error {
  readonly code = UNSUPPORTED_CODEX_MODEL

  constructor() {
    super(UNSUPPORTED_CODEX_MODEL)
    this.name = 'UnsupportedCodexModelError'
  }
}

export function normalizeReasoningEffort(value: string | undefined): CodexReasoningEffort | undefined {
  if (!value) return undefined
  const normalized = value.trim().toLowerCase() === 'extra-high' ? 'xhigh' : value.trim().toLowerCase()
  return REASONING.has(normalized as CodexReasoningEffort)
    ? normalized as CodexReasoningEffort
    : undefined
}

export function resolveModelAlias(rawModel: string, aliases: Record<string, string>): string {
  const normalizedRawModel = rawModel.toLowerCase()
  if (Object.prototype.hasOwnProperty.call(aliases, normalizedRawModel)) return aliases[normalizedRawModel]!
  const colon = rawModel.indexOf(':')
  if (colon <= 0) return rawModel
  const alias = rawModel.slice(0, colon).trim().toLowerCase()
  const effort = rawModel.slice(colon + 1).trim()
  const resolved = aliases[alias]
  if (!resolved) return rawModel
  return resolved.includes(':') ? resolved : `${resolved}:${effort}`
}

export function resolveSupportedModelAlias(rawModel: string, aliases: Record<string, string>): string {
  const resolved = resolveModelAlias(rawModel, aliases)
  const baseModel = resolved.split(':', 1)[0]?.trim().toLowerCase()
  if (baseModel === 'gpt-5.4-mini') throw new UnsupportedCodexModelError()
  return resolved
}

export function parseModelString(rawModel: string): {
  model: string
  reasoningEffort?: CodexReasoningEffort
} {
  const colon = rawModel.indexOf(':')
  if (colon <= 0) return { model: rawModel.trim() }
  const model = rawModel.slice(0, colon).trim()
  const rawEffort = rawModel.slice(colon + 1).trim()
  const reasoningEffort = normalizeReasoningEffort(rawEffort)
  if (!reasoningEffort) throw new Error(`Unsupported reasoning effort: ${rawEffort}`)
  return { model, reasoningEffort }
}
