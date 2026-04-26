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
