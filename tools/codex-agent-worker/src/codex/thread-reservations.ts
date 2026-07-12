import type { TaskEntry } from '../models.js'
import {
  findCodexCliProcessForThread,
  listCodexCliProcesses,
  type CodexCliProcessInfo,
} from './processes.js'

export type CodexThreadConflictSource = 'reservation' | 'task_registry' | 'process_scan'

export type CodexThreadConflict = {
  threadId: string
  source: CodexThreadConflictSource
  taskId?: string
  pid?: number
}

export class CodexThreadActiveError extends Error {
  readonly code = 'CODEX_THREAD_ACTIVE'

  constructor(readonly conflict: CodexThreadConflict) {
    super('Codex thread already has an active task or process')
    this.name = 'CodexThreadActiveError'
  }
}

export type CodexThreadReservation = {
  threadId: string
  taskId: string
  release: () => void
}

type ReservationOwner = {
  taskId: string
  acquiredAt: number
}

type AcquireOptions = {
  taskEntries?: Iterable<TaskEntry>
  listProcesses?: () => Promise<CodexCliProcessInfo[]>
}

const reservations = new Map<string, ReservationOwner>()

export async function acquireCodexThreadReservation(
  threadId: string,
  taskId: string,
  options: AcquireOptions = {},
): Promise<CodexThreadReservation> {
  const key = threadId.trim()
  if (!key) throw new Error('threadId is required')

  const current = reservations.get(key)
  if (current && current.taskId !== taskId) {
    throw new CodexThreadActiveError({
      threadId: key,
      source: 'reservation',
      taskId: current.taskId,
    })
  }

  reservations.set(key, { taskId, acquiredAt: Date.now() })
  let released = false
  const release = (): void => {
    if (released) return
    released = true
    if (reservations.get(key)?.taskId === taskId) reservations.delete(key)
  }

  try {
    const taskEntries = [...(options.taskEntries || [])]
    const activeEntry = taskEntries.find(entry => (
      entry.taskId !== taskId
      && entry.threadId === key
      && entry.status === 'running'
    ))
    if (activeEntry) {
      throw new CodexThreadActiveError({
        threadId: key,
        source: 'task_registry',
        taskId: activeEntry.taskId,
        pid: activeEntry.pid,
      })
    }

    const processes = await (options.listProcesses || listCodexCliProcesses)()
    const processInfo = findCodexCliProcessForThread(key, processes, taskEntries)
    if (processInfo) {
      const matchedEntry = taskEntries.find(entry => entry.pid === processInfo.pid)
      throw new CodexThreadActiveError({
        threadId: key,
        source: 'process_scan',
        taskId: matchedEntry?.taskId,
        pid: processInfo.pid,
      })
    }

    return { threadId: key, taskId, release }
  } catch (error) {
    release()
    throw error
  }
}

export function getCodexThreadReservations(): ReadonlyMap<string, Readonly<ReservationOwner>> {
  return reservations
}

export function clearCodexThreadReservationsForTests(): void {
  reservations.clear()
}
