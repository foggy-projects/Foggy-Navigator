import type { LlmModelConfig, WorkerBackend } from '@/types'

export type SelectableModelOption = {
  value: string
  label: string
  backend: WorkerBackend
}

export const ALL_MODEL_OPTIONS: SelectableModelOption[] = [
  { value: 'opus[1m]', label: 'Opus (1M)', backend: 'CLAUDE_CODE' },
  { value: 'opus', label: 'Opus', backend: 'CLAUDE_CODE' },
  { value: 'sonnet[1m]', label: 'Sonnet (1M)', backend: 'CLAUDE_CODE' },
  { value: 'sonnet', label: 'Sonnet', backend: 'CLAUDE_CODE' },
  { value: 'haiku', label: 'Haiku', backend: 'CLAUDE_CODE' },
  { value: 'gpt-5.4:low', label: '5.4 Low', backend: 'OPENAI_CODEX' },
  { value: 'gpt-5.4', label: '5.4 Medium', backend: 'OPENAI_CODEX' },
  { value: 'gpt-5.4:high', label: '5.4 High', backend: 'OPENAI_CODEX' },
  { value: 'gpt-5.4:extra-high', label: '5.4 Extra High', backend: 'OPENAI_CODEX' },
  { value: 'gpt-5.4-mini', label: '5.4 Mini', backend: 'OPENAI_CODEX' },
  { value: 'gpt-5.3-codex', label: '5.3 Codex', backend: 'OPENAI_CODEX' },
  { value: 'gpt-5.3-codex-spark', label: 'Spark', backend: 'OPENAI_CODEX' },
]

export function isSelectablePlatformModel(model: LlmModelConfig): boolean {
  return model.hasApiKey || model.workerBackend === 'OPENAI_CODEX'
}

export function resolveModelOptions(modelConfig: LlmModelConfig | null | undefined): SelectableModelOption[] {
  const backend = modelConfig?.workerBackend ?? 'CLAUDE_CODE'
  const backendModels = ALL_MODEL_OPTIONS.filter((model) => model.backend === backend)
  const allowed = modelConfig?.availableModels
  if (!allowed || allowed.length === 0) {
    return backendModels
  }
  return backendModels.filter((model) => allowed.includes(model.value))
}
