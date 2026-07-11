import type { LlmModelConfig } from '@/api/types'

export type MobileModelOption = {
  value: string
  label: string
  backend: string
  group?: string
  optionLabel?: string
  reasoningEffort?: string
}

const BASE_MODELS: MobileModelOption[] = [
  { value: 'opus[1m]', label: 'Opus (1M)', backend: 'CLAUDE_CODE' },
  { value: 'opus', label: 'Opus', backend: 'CLAUDE_CODE' },
  { value: 'sonnet[1m]', label: 'Sonnet (1M)', backend: 'CLAUDE_CODE' },
  { value: 'sonnet', label: 'Sonnet', backend: 'CLAUDE_CODE' },
  { value: 'haiku', label: 'Haiku', backend: 'CLAUDE_CODE' },
  { value: 'gemini-pro', label: 'Gemini Pro (Alias)', backend: 'GEMINI_CLI' },
  { value: 'gemini-flash', label: 'Gemini Flash (Alias)', backend: 'GEMINI_CLI' },
  { value: 'gemini-flash-lite', label: 'Gemini Flash Lite (Alias)', backend: 'GEMINI_CLI' },
]

const REASONING_LEVELS = [
  ['low', 'Low'],
  ['medium', 'Medium'],
  ['high', 'High'],
  ['xhigh', 'Extra High'],
  ['max', 'Max'],
] as const

function codexFamily(alias: string, group: string, supportsUltra: boolean): MobileModelOption[] {
  const levels = supportsUltra ? [...REASONING_LEVELS, ['ultra', 'Ultra'] as const] : REASONING_LEVELS
  return levels.map(([effort, label]) => ({
    value: `${alias}:${effort}`,
    label: `${group} · ${label}`,
    optionLabel: label,
    group,
    backend: 'OPENAI_CODEX',
    reasoningEffort: effort,
  }))
}

export const MOBILE_MODEL_OPTIONS: MobileModelOption[] = [
  ...BASE_MODELS,
  ...codexFamily('codex-latest', 'Codex Sol', true),
  ...codexFamily('codex-terra', 'Codex Terra', true),
  ...codexFamily('codex-luna', 'Codex Luna', false),
]

const CANONICAL_CODEX = new Set(
  MOBILE_MODEL_OPTIONS.filter((option) => option.backend === 'OPENAI_CODEX').map((option) => option.value),
)
const LEGACY_CODEX = new Map([
  ['codex-latest', 'codex-latest:medium'],
  ['codex-fast', 'codex-latest:low'],
  ['codex-deep', 'codex-latest:high'],
  ['codex-xhigh', 'codex-latest:xhigh'],
  ['codex-max', 'codex-latest:max'],
  ['codex-ultra', 'codex-latest:ultra'],
  ['codex-terra', 'codex-terra:medium'],
  ['codex-luna', 'codex-luna:medium'],
])
const REAL_CODEX_FAMILIES = new Map([
  ['gpt-5.6-sol', 'codex-latest'],
  ['gpt-5.6-terra', 'codex-terra'],
  ['gpt-5.6-luna', 'codex-luna'],
])

export function normalizeMobileCodexModel(value: string | null | undefined): string | null {
  if (!value?.trim()) return null
  const normalized = value.trim().toLowerCase().replace(/\s+/g, '')
  const legacy = LEGACY_CODEX.get(normalized)
  if (legacy) return legacy
  if (CANONICAL_CODEX.has(normalized)) return normalized
  const separator = normalized.lastIndexOf(':')
  const base = separator > 0 ? normalized.slice(0, separator) : normalized
  const fixedLegacyAlias = LEGACY_CODEX.get(base)
  if (fixedLegacyAlias && !['codex-latest', 'codex-terra', 'codex-luna'].includes(base)) {
    return fixedLegacyAlias
  }
  const rawEffort = separator > 0 ? normalized.slice(separator + 1) : 'medium'
  const effort = rawEffort === 'extra-high' ? 'xhigh' : rawEffort
  const family = ['codex-latest', 'codex-terra', 'codex-luna'].includes(base)
    ? base
    : REAL_CODEX_FAMILIES.get(base)
  if (!family) return null
  const canonical = `${family}:${effort}`
  return CANONICAL_CODEX.has(canonical) ? canonical : null
}

export function isMobileSelectablePlatformModel(model: LlmModelConfig): boolean {
  return model.hasApiKey || ['CLAUDE_CODE', 'OPENAI_CODEX', 'GEMINI_CLI', 'LANGGRAPH_BIZ'].includes(model.workerBackend || '')
}

export function resolveMobileModelOptions(config: LlmModelConfig | null | undefined): MobileModelOption[] {
  const backend = config?.workerBackend ?? 'CLAUDE_CODE'
  const candidates = MOBILE_MODEL_OPTIONS.filter((option) => option.backend === backend)
  const allowed = config?.availableModels
  if (!allowed?.length) return candidates
  if (backend !== 'OPENAI_CODEX') {
    const allowedSet = new Set(allowed)
    return candidates.filter((option) => allowedSet.has(option.value))
  }
  const normalizedAllowed = new Set(
    allowed.map(normalizeMobileCodexModel).filter((value): value is string => Boolean(value)),
  )
  if (normalizedAllowed.size === 0) {
    return candidates.filter((option) => option.reasoningEffort !== 'max' && option.reasoningEffort !== 'ultra')
  }
  return candidates.filter((option) => normalizedAllowed.has(option.value))
}

export function groupMobileModelOptions(options: readonly MobileModelOption[]) {
  const groups = new Map<string, MobileModelOption[]>()
  for (const option of options) {
    const key = option.group || ''
    groups.set(key, [...(groups.get(key) || []), option])
  }
  return [...groups.entries()].map(([label, groupOptions]) => ({ label, options: groupOptions }))
}
