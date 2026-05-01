import type { ClaudeTask } from '@/types'
import { isClaudeCodeTask } from '@/utils/workerBackend'

export interface ContinuableTask {
  sessionId?: string | null
  status?: string | null
}

export function getContinuationRef(task?: ContinuableTask | null): string {
  // Resume is routed by the platform session. Provider-native references
  // (Claude session, Codex thread, Gemini session, etc.) are restored server-side.
  return task?.sessionId || ''
}

export function canShowContinuationInput(task?: ContinuableTask | null): boolean {
  return !!task && task.status !== 'PENDING' && !!getContinuationRef(task)
}

export function canEnableRewind(task?: ClaudeTask | null): boolean {
  return !!task?.claudeSessionId && isClaudeCodeTask(task) && task.status !== 'RUNNING' && task.status !== 'PENDING'
}
