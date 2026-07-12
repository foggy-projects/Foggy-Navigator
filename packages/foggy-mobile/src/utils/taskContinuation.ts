import type { DispatchTask } from '@/api/types'

type ContinuableTask = Pick<DispatchTask, 'status' | 'sessionId'>

const RESUMABLE_STATUSES = new Set<DispatchTask['status']>(['COMPLETED', 'FAILED', 'ABORTED'])

export function getTaskContinuationRef(task?: Partial<ContinuableTask> | null): string {
  // The platform session is the continuation boundary. Provider-native
  // references are restored by the selected provider on the server.
  return task?.sessionId || ''
}

export function canResumeTask(task?: Partial<ContinuableTask> | null): boolean {
  if (!task?.status) return false
  return RESUMABLE_STATUSES.has(task.status as DispatchTask['status']) && !!getTaskContinuationRef(task)
}

export type TaskContinuationResult<T> =
  | { mode: 'cancelled' }
  | { mode: 'created'; task: T }
  | { mode: 'resumed'; task: T }

export async function executeTaskContinuation<T>(options: {
  requiresNewSession: boolean
  confirmNewSession: () => Promise<boolean>
  createNewSession: () => Promise<T>
  resumeSession: () => Promise<T>
}): Promise<TaskContinuationResult<T>> {
  if (options.requiresNewSession) {
    if (!await options.confirmNewSession()) return { mode: 'cancelled' }
    return { mode: 'created', task: await options.createNewSession() }
  }
  return { mode: 'resumed', task: await options.resumeSession() }
}
