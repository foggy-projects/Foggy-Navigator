import type { ClaudeWorker, LlmModelConfig, WorkerBackend } from '@/types'

export type SelectableModelOption = {
  value: string
  label: string
  backend: WorkerBackend
  description?: string
  group?: string
  optionLabel?: string
  reasoningEffort?: string
}

export type SelectableModelOptionGroup = {
  label?: string
  options: SelectableModelOption[]
}

const CLAUDE_MODEL_OPTIONS: SelectableModelOption[] = [
  { value: 'opus[1m]', label: 'Opus (1M)', backend: 'CLAUDE_CODE', description: 'Opus (1M context)' },
  { value: 'opus', label: 'Opus', backend: 'CLAUDE_CODE', description: 'Opus' },
  { value: 'sonnet[1m]', label: 'Sonnet (1M)', backend: 'CLAUDE_CODE', description: 'Sonnet (1M context)' },
  { value: 'sonnet', label: 'Sonnet', backend: 'CLAUDE_CODE', description: 'Sonnet' },
  { value: 'haiku', label: 'Haiku', backend: 'CLAUDE_CODE', description: 'Haiku' },
]

type CodexReasoningLevel = {
  readonly value: string
  readonly label: string
  readonly description?: string
}

const CODEX_REASONING_LEVELS: readonly CodexReasoningLevel[] = [
  { value: 'low', label: 'Low' },
  { value: 'medium', label: 'Medium', description: '默认' },
  { value: 'high', label: 'High' },
  { value: 'xhigh', label: 'Extra High' },
  { value: 'max', label: 'Max' },
] as const

function createCodexFamilyOptions(
  alias: 'codex-latest' | 'codex-terra' | 'codex-luna',
  familyLabel: string,
  backend: 'OPENAI_CODEX' | 'OPENAI_CODEX_APP_SERVER',
  supportsUltra: boolean,
): SelectableModelOption[] {
  const levels = supportsUltra
    ? [...CODEX_REASONING_LEVELS, { value: 'ultra', label: 'Ultra' } as const]
    : CODEX_REASONING_LEVELS
  return levels.map((level) => ({
    value: `${alias}:${level.value}`,
    label: `${familyLabel} · ${level.label}`,
    optionLabel: level.label,
    group: familyLabel,
    backend,
    reasoningEffort: level.value,
    description: level.description
      ? `${familyLabel} · ${level.label}（${level.description}）`
      : `${familyLabel} · ${level.label}`,
  }))
}

/**
 * Codex 使用“稳定模型族 alias + reasoning 后缀”作为产品层规范值。
 * Runtime 仍负责把 alias 解析为真实 GPT 模型，并依据 capability manifest 做最终选路。
 */
const CODEX_SDK_MODEL_OPTIONS: SelectableModelOption[] = [
  ...createCodexFamilyOptions('codex-latest', 'Codex Sol', 'OPENAI_CODEX', false),
  ...createCodexFamilyOptions('codex-terra', 'Codex Terra', 'OPENAI_CODEX', false),
  ...createCodexFamilyOptions('codex-luna', 'Codex Luna', 'OPENAI_CODEX', false),
]

const CODEX_APP_SERVER_MODEL_OPTIONS: SelectableModelOption[] = [
  ...createCodexFamilyOptions('codex-latest', 'Codex Sol', 'OPENAI_CODEX_APP_SERVER', true),
  ...createCodexFamilyOptions('codex-terra', 'Codex Terra', 'OPENAI_CODEX_APP_SERVER', true),
  ...createCodexFamilyOptions('codex-luna', 'Codex Luna', 'OPENAI_CODEX_APP_SERVER', false),
]

const CODEX_CANONICAL_VALUES = new Set(
  [...CODEX_SDK_MODEL_OPTIONS, ...CODEX_APP_SERVER_MODEL_OPTIONS].map((option) => option.value),
)
const CODEX_LEGACY_ALIASES = new Map<string, string>([
  ['codex-latest', 'codex-latest:medium'],
  ['codex-fast', 'codex-latest:low'],
  ['codex-deep', 'codex-latest:high'],
  ['codex-xhigh', 'codex-latest:xhigh'],
  ['codex-max', 'codex-latest:max'],
  ['codex-ultra', 'codex-latest:ultra'],
  ['codex-terra', 'codex-terra:medium'],
  ['codex-luna', 'codex-luna:medium'],
])

const CODEX_REAL_MODEL_FAMILIES = new Map<string, string>([
  ['gpt-5.6-sol', 'codex-latest'],
  ['gpt-5.6-terra', 'codex-terra'],
  ['gpt-5.6-luna', 'codex-luna'],
])

const GEMINI_MODEL_OPTIONS: SelectableModelOption[] = [
  { value: 'gemini-pro', label: 'Gemini Pro (Alias)', backend: 'GEMINI_CLI', description: 'Gemini Pro (Alias -> CLI Auto Gemini 3)' },
  { value: 'gemini-flash', label: 'Gemini Flash (Alias)', backend: 'GEMINI_CLI', description: 'Gemini Flash (Alias -> Gemini 3 Flash Preview)' },
  { value: 'gemini-flash-lite', label: 'Gemini Flash Lite (Alias)', backend: 'GEMINI_CLI', description: 'Gemini Flash Lite (Alias -> Gemini 3.1 Flash Lite Preview)' },
]

const LANGGRAPH_BIZ_MODEL_OPTIONS: SelectableModelOption[] = [
  { value: 'biz-default', label: 'Biz Default', backend: 'LANGGRAPH_BIZ', description: 'Biz Default (Alias) — 由 LangGraph Biz Worker 环境配置解析' },
]

export const ALL_MODEL_OPTIONS: SelectableModelOption[] = [
  ...CLAUDE_MODEL_OPTIONS,
  ...CODEX_SDK_MODEL_OPTIONS,
  ...CODEX_APP_SERVER_MODEL_OPTIONS,
  ...GEMINI_MODEL_OPTIONS,
  ...LANGGRAPH_BIZ_MODEL_OPTIONS,
]

export function normalizeCodexModelValue(value: string | null | undefined): string | null {
  if (!value?.trim()) return null
  const normalized = value.trim().toLowerCase().replace(/\s+/g, '')
  const legacy = CODEX_LEGACY_ALIASES.get(normalized)
  if (legacy) return legacy
  if (CODEX_CANONICAL_VALUES.has(normalized)) return normalized

  const separator = normalized.lastIndexOf(':')
  const base = separator > 0 ? normalized.slice(0, separator) : normalized
  const fixedLegacyAlias = CODEX_LEGACY_ALIASES.get(base)
  if (fixedLegacyAlias && !['codex-latest', 'codex-terra', 'codex-luna'].includes(base)) {
    return fixedLegacyAlias
  }
  const rawEffort = separator > 0 ? normalized.slice(separator + 1) : 'medium'
  const effort = rawEffort === 'extra-high' ? 'xhigh' : rawEffort
  const familyAlias = ['codex-latest', 'codex-terra', 'codex-luna'].includes(base)
    ? base
    : CODEX_REAL_MODEL_FAMILIES.get(base)
  if (!familyAlias) return null
  const canonical = `${familyAlias}:${effort}`
  return CODEX_CANONICAL_VALUES.has(canonical) ? canonical : null
}

export function normalizeModelValueForBackend(
  value: string | null | undefined,
  backend: WorkerBackend | undefined,
): string {
  if (!value) return ''
  if (isCodexBackend(backend)) return normalizeCodexModelValue(value) ?? value
  return value
}

export function normalizeAvailableModelGrants(
  values: readonly string[] | null | undefined,
  backend: WorkerBackend | undefined,
): string[] {
  if (!values) return []
  if (!isCodexBackend(backend)) return [...values]
  return [...new Set(values.map((value) => normalizeCodexModelValue(value) ?? value))]
}

export function groupModelOptions(options: readonly SelectableModelOption[]): SelectableModelOptionGroup[] {
  const groups: SelectableModelOptionGroup[] = []
  const indexes = new Map<string, number>()
  for (const option of options) {
    const key = option.group ?? ''
    let index = indexes.get(key)
    if (index === undefined) {
      index = groups.length
      indexes.set(key, index)
      groups.push({ label: option.group, options: [] })
    }
    groups[index]!.options.push(option)
  }
  return groups
}

function supportsSubscriptionSelection(backend: WorkerBackend | undefined): boolean {
  return backend === 'CLAUDE_CODE'
    || backend === 'OPENAI_CODEX'
    || backend === 'OPENAI_CODEX_APP_SERVER'
    || backend === 'GEMINI_CLI'
    || backend === 'LANGGRAPH_BIZ'
}

function isCodexBackend(backend: WorkerBackend | undefined): boolean {
  return backend === 'OPENAI_CODEX' || backend === 'OPENAI_CODEX_APP_SERVER'
}

export function isSelectablePlatformModel(model: LlmModelConfig): boolean {
  return model.hasApiKey || supportsSubscriptionSelection(model.workerBackend)
}

export function isModelConfigCompatibleWithWorker(
  model: LlmModelConfig,
  worker: ClaudeWorker | null | undefined,
): boolean {
  if (!worker) return true
  const backend = model.workerBackend ?? 'CLAUDE_CODE'
  const workerBackend = worker.workerBackend ?? 'CLAUDE_CODE'

  if (backend === 'CLAUDE_CODE') return workerBackend === 'CLAUDE_CODE'
  if (backend === 'OPENAI_CODEX') return workerBackend === 'OPENAI_CODEX' || Boolean(worker.codexBaseUrl?.trim())
  // App Server capability is owned by Endpoint Profiles rather than a field on
  // the physical Worker DTO. listModelConfigs(workerId) already filters it via
  // the backend capability tester, so a returned config is compatible here.
  if (backend === 'OPENAI_CODEX_APP_SERVER') return true
  if (backend === 'GEMINI_CLI') return workerBackend === 'GEMINI_CLI' || Boolean(worker.geminiBaseUrl?.trim())
  if (backend === 'LANGGRAPH_BIZ') return workerBackend === 'LANGGRAPH_BIZ'
  return false
}

/**
 * 空 availableModels 表示不限制；非空时对已知 Codex 模型按规范值精确过滤。
 * 旧配置若全是未知真实模型名，保留迁移兜底，但不自动开放 Max/Ultra。
 */
export function resolveModelOptions(modelConfig: LlmModelConfig | null | undefined): SelectableModelOption[] {
  const backend = modelConfig?.workerBackend ?? 'CLAUDE_CODE'
  const backendModels = ALL_MODEL_OPTIONS.filter((model) => model.backend === backend)
  const allowed = modelConfig?.availableModels
  if (!allowed || allowed.length === 0) return backendModels

  if (!isCodexBackend(backend)) {
    const allowedSet = new Set(allowed)
    return backendModels.filter((model) => allowedSet.has(model.value))
  }

  const normalizedAllowed = new Set(
    allowed.map((value) => normalizeCodexModelValue(value)).filter((value): value is string => Boolean(value)),
  )
  if (normalizedAllowed.size === 0) {
    return backendModels.filter((model) => model.reasoningEffort !== 'max' && model.reasoningEffort !== 'ultra')
  }
  return backendModels.filter((model) => normalizedAllowed.has(model.value))
}

export function getModelOptionsByBackend(backend: WorkerBackend): SelectableModelOption[] {
  return ALL_MODEL_OPTIONS.filter((model) => model.backend === backend)
}
