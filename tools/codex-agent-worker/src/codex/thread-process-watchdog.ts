import type { TaskEntry } from '../models.js'
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
  abortTask: (taskId: string, reason: string) => boolean
  releaseReservationsForTask: (taskId: string) => string[]
  now?: () => number
}

export type CodexThreadProcessWatchdogReport = {
  scannedTasks: number
  liveTasks: number
  uncertainTasks: number
  suspectedMissingTasks: number
  abortedTasks: string[]
  releasedThreadIds: string[]
}

/**
 * Keeps Worker-owned task/thread state aligned with the actual Codex CLI processes.
 * A missing process must remain absent for a safety window before the task is aborted.
 * Process scan failures never mutate task or reservation state.
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
      abortedTasks: [],
      releasedThreadIds: [],
    }
    if (!taskEntries.some(entry => entry.status === 'running') && reservations.length === 0) {
      return report
    }

    const processes = await (this.dependencies.listProcesses ?? listCodexCliProcesses)()
    const now = (this.dependencies.now ?? Date.now)()
    const startupGraceMs = this.options.startupGraceMs
      ?? Math.max(10_000, this.options.missingGraceMs)

    for (const entry of taskEntries) {
      if (entry.status !== 'running') {
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

      // A new task may not have exposed its child PID yet. Resumed tasks can be
      // reconciled by thread command after the startup window; brand-new tasks
      // without either PID or thread ID remain uncertain rather than guessed dead.
      if (entry.pid === undefined
          && (now - entry.startedAt < startupGraceMs || !entry.threadId)) {
        this.missingSince.delete(entry.taskId)
        report.uncertainTasks += 1
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
      const reason = `Codex CLI process disappeared:${pidDetail || ' no matching process'}`
      if (this.dependencies.abortTask(entry.taskId, reason)) {
        report.abortedTasks.push(entry.taskId)
      }
      this.missingSince.delete(entry.taskId)
    }

    for (const owner of reservations) {
      const task = taskById.get(owner.taskId)
      if (!task || task.status !== 'running') {
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
      if (report.abortedTasks.length > 0 || report.releasedThreadIds.length > 0) {
        console.warn('[codex-thread-watchdog] reconciled stale execution state', report)
      }
    } catch (error) {
      console.warn('[codex-thread-watchdog] process scan failed; state left unchanged', error)
    } finally {
      this.scanInProgress = false
    }
  }
}
