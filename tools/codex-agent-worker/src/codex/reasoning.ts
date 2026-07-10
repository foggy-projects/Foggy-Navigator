import type { ModelReasoningEffort } from '@openai/codex-sdk'

export type CodexReasoningEffort = ModelReasoningEffort | 'max' | 'ultra'

export const CODEX_REASONING_EFFORTS = [
  'minimal',
  'low',
  'medium',
  'high',
  'xhigh',
  'max',
  'ultra',
] as const satisfies readonly CodexReasoningEffort[]

const VALID_REASONING_EFFORTS = new Set<string>(CODEX_REASONING_EFFORTS)

export function normalizeCodexReasoningEffort(value: string): CodexReasoningEffort | undefined {
  const trimmed = value.trim()
  const normalized = trimmed === 'extra-high' ? 'xhigh' : trimmed
  return VALID_REASONING_EFFORTS.has(normalized)
    ? normalized as CodexReasoningEffort
    : undefined
}
