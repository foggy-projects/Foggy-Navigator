import type { ClaudeWorker, LlmModelConfig, WorkerBackend } from '@/types'

export type SelectableModelOption = {
  value: string
  label: string
  backend: WorkerBackend
  /**
   * 可选详细描述，用于下拉选项的辅助文本（如 "Codex Latest (Alias) — Worker 解析为当前默认 Codex 模型"）。
   * 历史上 SettingsView 通过内联 el-option label 维护细节文案，现在统一收口到此处。
   */
  description?: string
}

const CLAUDE_MODEL_OPTIONS: SelectableModelOption[] = [
  { value: 'opus[1m]', label: 'Opus (1M)', backend: 'CLAUDE_CODE', description: 'Opus (1M context)' },
  { value: 'opus', label: 'Opus', backend: 'CLAUDE_CODE', description: 'Opus' },
  { value: 'sonnet[1m]', label: 'Sonnet (1M)', backend: 'CLAUDE_CODE', description: 'Sonnet (1M context)' },
  { value: 'sonnet', label: 'Sonnet', backend: 'CLAUDE_CODE', description: 'Sonnet' },
  { value: 'haiku', label: 'Haiku', backend: 'CLAUDE_CODE', description: 'Haiku' },
]

/**
 * Codex 稳定 alias 候选。
 *
 * 1.0.4 起：前端 / Java 后端只感知 alias，Worker 在执行任务前把 alias 解析为真实模型。
 * 模型版本升级（如 gpt-5.5 → gpt-5.6）只需修改 Worker 的 CODEX_MODEL_ALIASES 配置，前端无需任何改动。
 *
 * Worker 默认映射（见 tools/codex-agent-worker/src/config.ts → DEFAULT_CODEX_MODEL_ALIASES）：
 *   codex-latest → gpt-5.6-sol
 *   codex-terra  → gpt-5.6-terra
 *   codex-luna   → gpt-5.6-luna
 *   codex-fast   → gpt-5.6-sol:low
 *   codex-deep   → gpt-5.6-sol:high
 *   codex-xhigh  → gpt-5.6-sol:xhigh
 *   codex-max    → gpt-5.6-sol:max
 *   codex-ultra  → gpt-5.6-sol:ultra（仅 codex-app-server-worker）
 *
 * 与 Claude（opus/sonnet/haiku）和 Gemini（gemini-pro/gemini-flash）的命名风格保持一致。
 */
const CODEX_ALIAS_OPTIONS: SelectableModelOption[] = [
  { value: 'codex-latest', label: 'Codex Latest', backend: 'OPENAI_CODEX', description: 'Codex Latest (Alias) — 当前默认 Codex 模型' },
  { value: 'codex-terra', label: 'Codex Terra', backend: 'OPENAI_CODEX', description: 'Codex Terra (Alias) — 平衡速度、成本与能力，默认 Medium' },
  { value: 'codex-luna', label: 'Codex Luna', backend: 'OPENAI_CODEX', description: 'Codex Luna (Alias) — 高吞吐低成本，默认 Medium' },
  { value: 'codex-fast', label: 'Codex Fast', backend: 'OPENAI_CODEX', description: 'Codex Fast (Alias) — 快速轻量推理' },
  { value: 'codex-deep', label: 'Codex Deep', backend: 'OPENAI_CODEX', description: 'Codex Deep (Alias) — 深度推理' },
  { value: 'codex-xhigh', label: 'Codex Extra High', backend: 'OPENAI_CODEX', description: 'Codex Extra High (Alias) — 超高推理' },
  { value: 'codex-max', label: 'Codex Max', backend: 'OPENAI_CODEX', description: 'Codex Max (Alias) — Sol 最大推理深度' },
  { value: 'codex-ultra', label: 'Codex Ultra', backend: 'OPENAI_CODEX', description: 'Codex Ultra (Alias) — 仅由 App Server Worker 执行自动任务委派' },
]

const CODEX_EXPLICIT_GRANT_ALIASES = new Set(['codex-max', 'codex-ultra'])
const CODEX_REAL_MODEL_GRANT_ALIASES = new Map([
  ['gpt-5.6-sol:max', 'codex-max'],
  ['gpt-5.6-sol:ultra', 'codex-ultra'],
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
  ...CODEX_ALIAS_OPTIONS,
  ...GEMINI_MODEL_OPTIONS,
  ...LANGGRAPH_BIZ_MODEL_OPTIONS,
]

function supportsSubscriptionSelection(backend: WorkerBackend | undefined): boolean {
  return backend === 'CLAUDE_CODE' || backend === 'OPENAI_CODEX' || backend === 'GEMINI_CLI' || backend === 'LANGGRAPH_BIZ'
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

  if (backend === 'CLAUDE_CODE') {
    return workerBackend === 'CLAUDE_CODE'
  }
  if (backend === 'OPENAI_CODEX') {
    return workerBackend === 'OPENAI_CODEX' || Boolean(worker.codexBaseUrl?.trim())
  }
  if (backend === 'GEMINI_CLI') {
    return workerBackend === 'GEMINI_CLI' || Boolean(worker.geminiBaseUrl?.trim())
  }
  if (backend === 'LANGGRAPH_BIZ') {
    return workerBackend === 'LANGGRAPH_BIZ'
  }
  return false
}

/**
 * 根据 model 配置计算下拉候选项。
 *
 * 1.0.4 起的兼容兜底：
 * - 标准路径：当 `availableModels` 命中新 alias 或已知 GPT-5.6-Sol grant 时，按 whitelist 过滤
 * - 兼容路径：当 `availableModels` 全部是历史真实模型名（如 `gpt-5.4`、`gpt-5.5`）时，
 *   说明该配置是从旧版本继承下来的存量数据，新前端无法用这些值做有意义的过滤。
 *   此时开放普通 alias 帮助用户迁移，但 Max/Ultra 仍需在 whitelist 中显式授权。
 */
export function resolveModelOptions(modelConfig: LlmModelConfig | null | undefined): SelectableModelOption[] {
  const backend = modelConfig?.workerBackend ?? 'CLAUDE_CODE'
  const backendModels = ALL_MODEL_OPTIONS.filter((model) => model.backend === backend)
  const allowed = modelConfig?.availableModels
  if (!allowed || allowed.length === 0) {
    return backendModels
  }
  const effectiveAllowed = new Set(allowed)
  if (backend === 'OPENAI_CODEX') {
    for (const allowedModel of allowed) {
      if (!allowedModel) continue
      const alias = CODEX_REAL_MODEL_GRANT_ALIASES.get(allowedModel.trim().toLowerCase())
      if (alias) effectiveAllowed.add(alias)
    }
  }
  const filtered = backendModels.filter((model) => effectiveAllowed.has(model.value))
  // OPENAI_CODEX 旧 availableModels（gpt-5.4 等）兼容兜底：高权限 alias 不随迁移兜底开放。
  if (backend === 'OPENAI_CODEX' && filtered.length === 0) {
    return backendModels.filter((model) => !CODEX_EXPLICIT_GRANT_ALIASES.has(model.value))
  }
  return filtered
}

/**
 * 获取指定 backend 的所有候选模型（忽略 availableModels 过滤）。
 * 用于设置页的"可用模型"复选框与模型名称下拉框初始渲染。
 */
export function getModelOptionsByBackend(backend: WorkerBackend): SelectableModelOption[] {
  return ALL_MODEL_OPTIONS.filter((model) => model.backend === backend)
}
