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
    expect(isMobileSelectablePlatformModel(config({
      workerBackend: 'OPENAI_CODEX_APP_SERVER',
    }))).toBe(true)
  })

  it('keeps SDK and App Server catalogs independent with Ultra App-only', () => {
    const sdk = MOBILE_MODEL_OPTIONS.filter(option => option.backend === 'OPENAI_CODEX')
    const appServer = MOBILE_MODEL_OPTIONS.filter(option => option.backend === 'OPENAI_CODEX_APP_SERVER')
    expect(groupMobileModelOptions(sdk).map(group => group.label)).toEqual([
      'Codex Sol',
      'Codex Terra',
      'Codex Luna',
    ])
    expect(groupMobileModelOptions(appServer).map(group => group.label)).toEqual([
      'Codex Sol',
      'Codex Terra',
      'Codex Luna',
    ])
    expect(sdk).toHaveLength(15)
    expect(appServer).toHaveLength(17)
    expect(sdk.some(option => option.reasoningEffort === 'ultra')).toBe(false)
    expect(appServer.some(option => option.value === 'codex-latest:ultra')).toBe(true)
    expect(appServer.some(option => option.value === 'codex-terra:ultra')).toBe(true)
    expect(appServer.some(option => option.value === 'codex-luna:ultra')).toBe(false)
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

  it('does not expose an Ultra grant through an SDK model config', () => {
    const options = resolveMobileModelOptions(config({
      availableModels: ['codex-latest:ultra', 'codex-terra:max'],
    }))
    expect(options.map(option => option.value)).toEqual(['codex-terra:max'])
  })

  it('resolves Ultra grants through an App Server model config', () => {
    const options = resolveMobileModelOptions(config({
      workerBackend: 'OPENAI_CODEX_APP_SERVER',
      availableModels: ['gpt-5.6-sol:ultra', 'gpt-5.6-terra:ultra'],
    }))
    expect(options.map(option => option.value)).toEqual([
      'codex-latest:ultra',
      'codex-terra:ultra',
    ])
  })
})
