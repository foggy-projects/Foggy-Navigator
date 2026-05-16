import type { LlmModelConfig, WorkerBackend } from '@/types'

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
 *   codex-latest → gpt-5.5
 *   codex-fast   → gpt-5.5:low
 *   codex-deep   → gpt-5.5:high
 *   codex-xhigh  → gpt-5.5:xhigh
 *   codex-mini   → gpt-5.4-mini
 *
 * 与 Claude（opus/sonnet/haiku）和 Gemini（gemini-pro/gemini-flash）的命名风格保持一致。
 */
const CODEX_ALIAS_OPTIONS: SelectableModelOption[] = [
  { value: 'codex-latest', label: 'Codex Latest', backend: 'OPENAI_CODEX', description: 'Codex Latest (Alias) — 当前默认 Codex 模型' },
  { value: 'codex-fast', label: 'Codex Fast', backend: 'OPENAI_CODEX', description: 'Codex Fast (Alias) — 快速轻量推理' },
  { value: 'codex-deep', label: 'Codex Deep', backend: 'OPENAI_CODEX', description: 'Codex Deep (Alias) — 深度推理' },
  { value: 'codex-xhigh', label: 'Codex Extra High', backend: 'OPENAI_CODEX', description: 'Codex Extra High (Alias) — 最高推理' },
  { value: 'codex-mini', label: 'Codex Mini', backend: 'OPENAI_CODEX', description: 'Codex Mini (Alias) — 快速 Mini' },
]

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

/**
 * 根据 model 配置计算下拉候选项。
 *
 * 1.0.4 起的兼容兜底：
 * - 标准路径：当 `availableModels` 中至少命中一个新 alias 时，按 whitelist 过滤
 * - 兼容路径：当 `availableModels` 全部是历史真实模型名（如 `gpt-5.4`、`gpt-5.5`）时，
 *   说明该配置是从旧版本继承下来的存量数据，新前端无法用这些值做有意义的过滤——
 *   此时退化为"不限制"，让用户在新 UI 上看到全部 alias 选项，重新勾选保存即可
 *   完成迁移。
 */
export function resolveModelOptions(modelConfig: LlmModelConfig | null | undefined): SelectableModelOption[] {
  const backend = modelConfig?.workerBackend ?? 'CLAUDE_CODE'
  const backendModels = ALL_MODEL_OPTIONS.filter((model) => model.backend === backend)
  const allowed = modelConfig?.availableModels
  if (!allowed || allowed.length === 0) {
    return backendModels
  }
  const filtered = backendModels.filter((model) => allowed.includes(model.value))
  // OPENAI_CODEX 旧 availableModels（gpt-5.4 等）兼容兜底：完全无命中时退为"不限制"
  if (backend === 'OPENAI_CODEX' && filtered.length === 0) {
    return backendModels
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
