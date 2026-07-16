import {
  isTaskExecutionActive,
  type TaskAttention,
  type TaskEntry,
} from '../models.js'
import {
  findCodexCliProcessForThread,
  listCodexCliProcesses,
  type CodexCliProcessInfo,
} from './processes.js'
import type { CodexThreadReservationOwner } from './thread-reservations.js'

export type CodexThreadProcessWatchdogOptions = {
  intervalMs: number
  missingGraceMs: number
  startupGraceMs?: number
}

export type CodexThreadProcessWatchdogDependencies = {
  getTaskEntries: () => Iterable<TaskEntry>
  getReservations: () => ReadonlyMap<string, Readonly<CodexThreadReservationOwner>>
  listProcesses?: () => Promise<CodexCliProcessInfo[]>
  releaseReservationsForTask: (taskId: string) => string[]
  /** Emits a recoverable lifecycle event in the owning Worker. */
  markTaskAttention?: (taskId: string, attention: TaskAttention) => void
  now?: () => number
}

export type CodexThreadProcessWatchdogReport = {
  scannedTasks: number
  liveTasks: number
  uncertainTasks: number
  suspectedMissingTasks: number
  unverifiedTasks: string[]
  /** @deprecated Kept for status compatibility; watchdogs never abort tasks. */
  abortedTasks: string[]
  releasedThreadIds: string[]
}

/**
 * Keeps Worker-owned task/thread state aligned with the actual Codex CLI processes.
 * A missing process or a failed scan is an observation gap, not authority to
 * terminate a user task.  The watchdog only emits recoverable attention and
 * never calls an abort path or releases an active task reservation.
 */
export class CodexThreadProcessWatchdog {
  private readonly missingSince = new Map<string, number>()
  private timer: NodeJS.Timeout | undefined
  private scanInProgress = false

  constructor(
    private readonly dependencies: CodexThreadProcessWatchdogDependencies,
    private readonly options: CodexThreadProcessWatchdogOptions,
  ) {}

  start(): void {
    if (this.timer) return
    void this.runScheduledScan()
    this.timer = setInterval(() => void this.runScheduledScan(), this.options.intervalMs)
    this.timer.unref()
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer)
    this.timer = undefined
    this.missingSince.clear()
  }

  isRunning(): boolean {
    return this.timer !== undefined
  }

  async runOnce(): Promise<CodexThreadProcessWatchdogReport> {
    const taskEntries = [...this.dependencies.getTaskEntries()]
    const reservations = [...this.dependencies.getReservations().values()]
    const taskById = new Map(taskEntries.map(entry => [entry.taskId, entry]))
    const report: CodexThreadProcessWatchdogReport = {
      scannedTasks: taskEntries.length,
      liveTasks: 0,
      uncertainTasks: 0,
      suspectedMissingTasks: 0,
      unverifiedTasks: [],
      abortedTasks: [],
      releasedThreadIds: [],
    }
    const activeEntries = taskEntries.filter(entry => isTaskExecutionActive(entry.status))
    if (activeEntries.length === 0 && reservations.length === 0) {
      return report
    }

    const now = (this.dependencies.now ?? Date.now)()
    let processes: CodexCliProcessInfo[]
    try {
      processes = await (this.dependencies.listProcesses ?? listCodexCliProcesses)()
    } catch {
      for (const entry of activeEntries) {
        this.markUnverified(entry, 'Codex CLI process scan failed; task remains active pending diagnostics', now)
        report.unverifiedTasks.push(entry.taskId)
      }
      // Do not log the underlying process-enumeration error: platform command
      // output can contain complete CLI command lines and their sensitive args.
      console.warn('[codex-thread-watchdog] process scan failed; task state retained')
      return report
    }
    const startupGraceMs = this.options.startupGraceMs
      ?? Math.max(10_000, this.options.missingGraceMs)

    for (const entry of taskEntries) {
      if (!isTaskExecutionActive(entry.status)) {
        this.missingSince.delete(entry.taskId)
        continue
      }

      const processInfo = this.findProcess(entry, processes, taskEntries)
      if (processInfo) {
        if (entry.pid === undefined) entry.pid = processInfo.pid
        this.missingSince.delete(entry.taskId)
        report.liveTasks += 1
        continue
      }

      // A new task may not have exposed its child PID yet.  That short startup
      // window is expected and must not be treated as a dead task.
      if (entry.pid === undefined && now - entry.startedAt < startupGraceMs) {
        this.missingSince.delete(entry.taskId)
        report.uncertainTasks += 1
        continue
      }

      // Once startup grace expires, the absence of both process identity and
      // thread identity is itself an observable uncertainty.  Keep the task
      // and reservation active; an operator can decide what to do next.
      if (entry.pid === undefined && !entry.threadId) {
        this.markUnverified(
          entry,
          'Codex CLI process and thread identity could not be verified after startup grace',
          now,
        )
        report.unverifiedTasks.push(entry.taskId)
        continue
      }

      const missingSince = this.missingSince.get(entry.taskId)
      if (missingSince === undefined) {
        this.missingSince.set(entry.taskId, now)
        report.suspectedMissingTasks += 1
        continue
      }
      if (now - missingSince < this.options.missingGraceMs) {
        report.suspectedMissingTasks += 1
        continue
      }

      const pidDetail = entry.pid !== undefined ? ` pid=${entry.pid}` : ''
      this.markUnverified(
        entry,
        `Codex CLI process could not be verified:${pidDetail || ' no matching process'}`,
        now,
      )
      report.unverifiedTasks.push(entry.taskId)
      this.missingSince.delete(entry.taskId)
    }

    for (const owner of reservations) {
      const task = taskById.get(owner.taskId)
      if (!task || !isTaskExecutionActive(task.status)) {
        report.releasedThreadIds.push(
          ...this.dependencies.releaseReservationsForTask(owner.taskId),
        )
      }
    }

    return report
  }

  private findProcess(
    entry: TaskEntry,
    processes: readonly CodexCliProcessInfo[],
    taskEntries: readonly TaskEntry[],
  ): CodexCliProcessInfo | undefined {
    if (entry.pid !== undefined) {
      return processes.find(processInfo => processInfo.pid === entry.pid)
    }
    if (!entry.threadId) return undefined
    return findCodexCliProcessForThread(entry.threadId, processes, taskEntries)
  }

  private async runScheduledScan(): Promise<void> {
    if (this.scanInProgress) return
    this.scanInProgress = true
    try {
      const report = await this.runOnce()
      if (report.unverifiedTasks.length > 0 || report.releasedThreadIds.length > 0) {
        console.warn('[codex-thread-watchdog] reconciled lifecycle observations', report)
      }
    } catch {
      // Preserve the no-auto-termination invariant even for an unexpected
      // watchdog implementation failure.
      console.warn('[codex-thread-watchdog] unexpected scan failure; task state retained')
    } finally {
      this.scanInProgress = false
    }
  }

  private markUnverified(entry: TaskEntry, message: string, now: number): void {
    const attention: TaskAttention = {
      code: 'PROCESS_UNVERIFIED',
      message,
      source: 'THREAD_PROCESS_WATCHDOG',
      occurred_at: new Date(now).toISOString(),
      recoverable: true,
    }
    if (this.dependencies.markTaskAttention) {
      this.dependencies.markTaskAttention(entry.taskId, attention)
      return
    }
    const existing = entry.attention ?? []
    if (!existing.some(candidate => candidate.code === attention.code)) {
      entry.attention = [...existing, attention]
    }
    entry.availableActions = ['CONTINUE_WAIT', 'QUERY_DIAGNOSTICS', 'CANCEL']
  }
}
