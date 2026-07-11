import { describe, expect, it } from 'vitest'
import {
  ALL_MODEL_OPTIONS,
  getModelOptionsByBackend,
  groupModelOptions,
  isModelConfigCompatibleWithWorker,
  isSelectablePlatformModel,
  normalizeAvailableModelGrants,
  normalizeCodexModelValue,
  resolveModelOptions,
} from '@/utils/llmModelOptions'
import type { ClaudeWorker, LlmModelConfig, LlmModelCategory, ModelAccessScope, WorkerBackend } from '@/types'

function createModelConfig(overrides: Partial<LlmModelConfig> = {}): LlmModelConfig {
  return {
    id: 'cfg-1',
    tenantId: 'tenant-1',
    name: 'test-model',
    category: 'CODING' as LlmModelCategory,
    baseUrl: '',
    modelName: 'opus',
    isDefault: false,
    hasApiKey: false,
    scope: 'GLOBAL' as ModelAccessScope,
    sortOrder: 0,
    createdAt: '2026-04-22T00:00:00Z',
    updatedAt: '2026-04-22T00:00:00Z',
    ...overrides,
  }
}

function createWorker(overrides: Partial<ClaudeWorker> = {}): ClaudeWorker {
  return {
    workerId: 'worker-1',
    name: 'worker-1',
    baseUrl: 'http://127.0.0.1:3050',
    authMode: 'API_KEY',
    status: 'ONLINE',
    createdAt: '2026-04-22T00:00:00Z',
    ...overrides,
  }
}

describe('llmModelOptions', () => {
  it('allows Claude Code subscription configs to appear in session dropdowns', () => {
    const config = createModelConfig({
      workerBackend: 'CLAUDE_CODE' as WorkerBackend,
      hasApiKey: false,
      baseUrl: '',
    })

    expect(isSelectablePlatformModel(config)).toBe(true)
  })

  it('keeps Codex subscription configs selectable', () => {
    const config = createModelConfig({
      workerBackend: 'OPENAI_CODEX' as WorkerBackend,
      hasApiKey: false,
      modelName: 'codex-latest',
    })

    expect(isSelectablePlatformModel(config)).toBe(true)
  })

  it('keeps LangGraph Biz configs selectable without api key', () => {
    const config = createModelConfig({
      workerBackend: 'LANGGRAPH_BIZ' as WorkerBackend,
      hasApiKey: false,
      modelName: 'biz-default',
    })

    expect(isSelectablePlatformModel(config)).toBe(true)
  })

  it('still rejects configs without api key or subscription-capable backend', () => {
    const config = createModelConfig({
      workerBackend: undefined,
      hasApiKey: false,
    })

    expect(isSelectablePlatformModel(config)).toBe(false)
  })

  it('filters available models for Claude subscription configs', () => {
    const config = createModelConfig({
      workerBackend: 'CLAUDE_CODE' as WorkerBackend,
      availableModels: ['opus'],
    })

    expect(resolveModelOptions(config).map((item) => item.value)).toEqual(['opus'])
  })

  it('exposes grouped canonical Codex model and reasoning values', () => {
    const codex = getModelOptionsByBackend('OPENAI_CODEX' as WorkerBackend)
    const values = codex.map((opt) => opt.value)
    expect(values).toHaveLength(17)
    expect(values).toContain('codex-latest:low')
    expect(values).toContain('codex-latest:ultra')
    expect(values).toContain('codex-terra:max')
    expect(values).toContain('codex-terra:ultra')
    expect(values).toContain('codex-luna:xhigh')
    expect(values).not.toContain('codex-luna:ultra')

    expect(groupModelOptions(codex).map((group) => group.label)).toEqual([
      'Codex Sol',
      'Codex Terra',
      'Codex Luna',
    ])
  })

  it('exposes LangGraph Biz aliases for LANGGRAPH_BIZ backend', () => {
    const langgraph = getModelOptionsByBackend('LANGGRAPH_BIZ' as WorkerBackend)
    expect(langgraph.map((opt) => opt.value)).toEqual(['biz-default'])
  })

  it('does not leak real Codex model names (gpt-5.x) into ALL_MODEL_OPTIONS', () => {
    // 1.0.4 alias-only：前端只展示 alias，真实模型名由 Worker 内部解析
    const codexValues = ALL_MODEL_OPTIONS
      .filter((m) => m.backend === 'OPENAI_CODEX')
      .map((m) => m.value)
    for (const v of codexValues) {
      expect(v).not.toMatch(/^gpt-5/)
    }
  })

  it('normalizes legacy aliases to one exact canonical grant', () => {
    expect(normalizeCodexModelValue('codex-latest')).toBe('codex-latest:medium')
    expect(normalizeCodexModelValue('codex-fast')).toBe('codex-latest:low')
    expect(normalizeCodexModelValue('codex-deep')).toBe('codex-latest:high')
    expect(normalizeCodexModelValue('codex-max')).toBe('codex-latest:max')
    expect(normalizeCodexModelValue('codex-terra')).toBe('codex-terra:medium')
    expect(normalizeCodexModelValue('codex-terra:extra-high')).toBe('codex-terra:xhigh')
    expect(normalizeCodexModelValue('codex-fast:high')).toBe('codex-latest:low')
    expect(normalizeCodexModelValue('gpt-5.6-luna:xhigh')).toBe('codex-luna:xhigh')
    expect(normalizeCodexModelValue('gpt-5.7-sol:max')).toBeNull()
  })

  it('resolveModelOptions filters by canonical and legacy grants without opening the whole family', () => {
    const config = createModelConfig({
      workerBackend: 'OPENAI_CODEX' as WorkerBackend,
      availableModels: ['codex-terra', 'codex-luna:max', 'codex-deep'],
    })
    expect(resolveModelOptions(config).map((m) => m.value)).toEqual([
      'codex-latest:high',
      'codex-terra:medium',
      'codex-luna:max',
    ])
  })

  it('requires explicit whitelist grants for Codex Max and Ultra when availableModels is restricted', () => {
    const config = createModelConfig({
      workerBackend: 'OPENAI_CODEX' as WorkerBackend,
      availableModels: ['codex-max', 'codex-ultra'],
    })
    expect(resolveModelOptions(config).map((m) => m.value)).toEqual(['codex-latest:max', 'codex-latest:ultra'])
  })

  it('maps exact GPT-5.6-Sol Max and Ultra grants to their stable aliases', () => {
    const config = createModelConfig({
      workerBackend: 'OPENAI_CODEX' as WorkerBackend,
      availableModels: ['gpt-5.6-sol:max', 'gpt-5.6-sol:ultra'],
    })
    expect(resolveModelOptions(config).map((m) => m.value)).toEqual(['codex-latest:max', 'codex-latest:ultra'])
  })

  it('keeps Max and Ultra gated when legacy availableModels has no alias hit', () => {
    // 历史 codex 配置存的是真实模型名，新前端按 whitelist 过滤会得到空集；
    // 迁移兜底仅开放普通 alias，Max/Ultra 必须由 alias whitelist 显式授权。
    const config = createModelConfig({
      workerBackend: 'OPENAI_CODEX' as WorkerBackend,
      availableModels: ['gpt-5.4', 'gpt-5.5', 'gpt-5.3-codex-spark'],
    })
    const result = resolveModelOptions(config).map((m) => m.value)
    expect(result).toHaveLength(12)
    expect(result).toContain('codex-latest:medium')
    expect(result).toContain('codex-terra:xhigh')
    expect(result).toContain('codex-luna:high')
    expect(result.some((value) => value.endsWith(':max'))).toBe(false)
    expect(result.some((value) => value.endsWith(':ultra'))).toBe(false)
  })

  it('normalizes stored Codex grants when editing a config', () => {
    expect(normalizeAvailableModelGrants(
      ['codex-latest', 'codex-terra:high', 'gpt-5.6-luna:max'],
      'OPENAI_CODEX' as WorkerBackend,
    )).toEqual(['codex-latest:medium', 'codex-terra:high', 'codex-luna:max'])
  })

  it('rejects Codex configs for workers without a Codex endpoint', () => {
    const config = createModelConfig({ workerBackend: 'OPENAI_CODEX' as WorkerBackend })
    const worker = createWorker({ workerBackend: 'CLAUDE_CODE' as WorkerBackend })

    expect(isModelConfigCompatibleWithWorker(config, worker)).toBe(false)
  })

  it('allows Codex configs when the selected worker has a Codex endpoint', () => {
    const config = createModelConfig({ workerBackend: 'OPENAI_CODEX' as WorkerBackend })
    const worker = createWorker({
      workerBackend: 'CLAUDE_CODE' as WorkerBackend,
      codexBaseUrl: 'http://127.0.0.1:3051',
    })

    expect(isModelConfigCompatibleWithWorker(config, worker)).toBe(true)
  })
})
