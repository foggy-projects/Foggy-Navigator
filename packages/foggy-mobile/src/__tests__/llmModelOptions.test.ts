import { describe, expect, it } from 'vitest'
import type { LlmModelConfig } from '@/api/types'
import {
  MOBILE_MODEL_OPTIONS,
  groupMobileModelOptions,
  isMobileSelectablePlatformModel,
  normalizeMobileCodexModel,
  resolveMobileModelOptions,
} from '@/utils/llmModelOptions'

function config(overrides: Partial<LlmModelConfig> = {}): LlmModelConfig {
  return {
    id: 'cfg-1',
    name: 'Codex subscription',
    category: 'CODING',
    baseUrl: '',
    modelName: 'codex-latest',
    isDefault: false,
    hasApiKey: false,
    scope: 'GLOBAL',
    sortOrder: 0,
    createdAt: '',
    updatedAt: '',
    workerBackend: 'OPENAI_CODEX',
    ...overrides,
  }
}

describe('mobile llm model options', () => {
  it('keeps subscription Codex configs selectable without an API key', () => {
    expect(isMobileSelectablePlatformModel(config())).toBe(true)
  })

  it('mirrors the three Codex groups and omits Luna Ultra', () => {
    const codex = MOBILE_MODEL_OPTIONS.filter(option => option.backend === 'OPENAI_CODEX')
    expect(groupMobileModelOptions(codex).map(group => group.label)).toEqual([
      'Codex Sol',
      'Codex Terra',
      'Codex Luna',
    ])
    expect(codex).toHaveLength(17)
    expect(codex.some(option => option.value === 'codex-terra:ultra')).toBe(true)
    expect(codex.some(option => option.value === 'codex-luna:ultra')).toBe(false)
  })

  it('normalizes legacy aliases and exact real GPT-5.6 models', () => {
    expect(normalizeMobileCodexModel('codex-deep')).toBe('codex-latest:high')
    expect(normalizeMobileCodexModel('codex-terra')).toBe('codex-terra:medium')
    expect(normalizeMobileCodexModel('codex-terra:extra-high')).toBe('codex-terra:xhigh')
    expect(normalizeMobileCodexModel('gpt-5.6-luna:max')).toBe('codex-luna:max')
  })

  it('filters exact family reasoning grants without widening the group', () => {
    const options = resolveMobileModelOptions(config({
      availableModels: ['codex-terra', 'codex-luna:max', 'codex-deep'],
    }))
    expect(options.map(option => option.value)).toEqual([
      'codex-latest:high',
      'codex-terra:medium',
      'codex-luna:max',
    ])
  })
})
