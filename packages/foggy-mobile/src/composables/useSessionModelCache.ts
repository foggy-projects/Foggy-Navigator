/**
 * Session model cache — stores per-session modelConfigId + model selections
 * in memory, initialized from latest task, updated on user switch.
 */
import { reactive } from 'vue'
import type { DispatchTask } from '@/api/types'

interface SessionModelState {
  modelConfigId: string
  model: string
}

const cache = reactive<Map<string, SessionModelState>>(new Map())

/**
 * Initialize cache entry from a task (typically the latest task in a session).
 * Does NOT overwrite if already cached (user's manual selection takes priority).
 */
export function initFromTask(task: DispatchTask): void {
  if (!task.sessionId) return
  if (cache.has(task.sessionId)) return
  cache.set(task.sessionId, {
    modelConfigId: task.modelConfigId || '',
    model: task.model || '',
  })
}

/**
 * Force-set cache entry (e.g., when user manually switches model).
 */
export function setSessionModel(sessionId: string, modelConfigId: string, model: string): void {
  cache.set(sessionId, { modelConfigId, model })
}

/**
 * Get cached model state for a session. Returns undefined if not cached.
 */
export function getSessionModel(sessionId: string): SessionModelState | undefined {
  return cache.get(sessionId)
}

/**
 * Clear cache entry for a session.
 */
export function clearSessionModel(sessionId: string): void {
  cache.delete(sessionId)
}

export function useSessionModelCache() {
  return {
    initFromTask,
    setSessionModel,
    getSessionModel,
    clearSessionModel,
  }
}
