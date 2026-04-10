import type { DispatchTask } from '@/api/types'

type ContinuableTask = Pick<DispatchTask, 'status' | 'claudeSessionId' | 'codexThreadId'>

const RESUMABLE_STATUSES = new Set<DispatchTask['status']>(['COMPLETED', 'FAILED', 'ABORTED'])

export function getTaskContinuationRef(task?: Partial<ContinuableTask> | null): string {
  return task?.claudeSessionId || task?.codexThreadId || ''
}

export function canResumeTask(task?: Partial<ContinuableTask> | null): boolean {
  if (!task?.status) return false
  return RESUMABLE_STATUSES.has(task.status as DispatchTask['status']) && !!getTaskContinuationRef(task)
}
