import path from 'node:path'
import type { AppConfig } from './config.js'
import type { StoredTaskRecord, TaskRequest, WorkerEvent } from './models.js'
import { parseModelString, resolveModelAlias } from './model-resolution.js'
import { EventBroadcast } from './persistence/event-store.js'
import { TaskStore } from './persistence/task-store.js'
import { cleanupMaterializedInput, type TaskExecutor } from './app-server/executor.js'
import { AppServerRuntimeError } from './app-server/runtime.js'

export class TaskQueueFullError extends Error {
  readonly code = 'APP_SERVER_TASK_QUEUE_FULL'
  constructor() {
    super('Codex app-server task queue is full')
    this.name = 'TaskQueueFullError'
  }
}

export class TaskManagerDrainingError extends Error {
  readonly code = 'APP_SERVER_WORKER_DRAINING'
  constructor() {
    super('Codex app-server Worker is draining')
    this.name = 'TaskManagerDrainingError'
  }
}

export type AbortTaskResult = {
  record: StoredTaskRecord
  abort_status: 'aborted' | 'abort_pending' | 'already_terminal'
}

export class TaskManager {
  private readonly broadcasts = new Map<string, EventBroadcast>()
  private readonly abortControllers = new Map<string, AbortController>()
  private readonly queued = new Set<string>()
  private readonly active = new Set<string>()
  private accepting = true
  private pendingAccepts = 0
  private readonly inFlightAccepts = new Map<string, Promise<{ record: StoredTaskRecord; created: boolean }>>()
  private readonly taskOperations = new Map<string, Promise<void>>()
  private recoveryRun?: Promise<void>

  constructor(
    private readonly config: AppConfig,
    readonly store: TaskStore,
    private readonly executor: TaskExecutor,
  ) {}

  async initialize(options: { resume?: boolean } = {}): Promise<void> {
    await this.store.initialize()
    if (options.resume !== false) this.store.verifyEncryptionKey()
    for (const snapshot of this.store.list()) {
      await cleanupMaterializedInput(snapshot.task_id, this.config.stateDir)
      const record = this.store.get(snapshot.task_id) || snapshot
      if (record.status === 'terminal') {
        if (record.tombstoned_at) {
          await EventBroadcast.purgePersisted(record.task_id, path.join(this.config.stateDir, 'events'))
        }
        continue
      }
      const broadcast = this.getBroadcast(record.task_id)
      const events = broadcast.getEventsAfter(0)
      if (await this.finalizeDurableTerminalEvent(record, broadcast, events)) {
        continue
      }
      if (record.abort_requested_at && (record.status === 'accepted' || record.status === 'starting')) {
        await this.finalizeLocalAbort(record, broadcast)
      } else if (record.status === 'accepted' || record.status === 'starting') {
        if (options.resume !== false) {
          if (record.recovery_required) await this.store.patch(record.task_id, { recovery_required: false })
          this.enqueue(record.task_id)
        } else {
          await this.store.patch(record.task_id, { recovery_required: true })
        }
      } else if (options.resume !== false && this.executor.reconcile) {
        await this.reconcileCommitted(record, broadcast)
      } else {
        await this.store.patch(record.task_id, { recovery_required: true })
      }
    }
    this.pump()
  }

  async accept(taskId: string, request: TaskRequest): Promise<{ record: StoredTaskRecord; created: boolean }> {
    const existing = this.store.get(taskId)
    if (existing) return this.store.accept(taskId, request)
    const inFlight = this.inFlightAccepts.get(taskId)
    if (inFlight) {
      await inFlight.catch(() => undefined)
      return this.store.accept(taskId, request)
    }
    if (!this.isAccepting()) throw new TaskManagerDrainingError()
    if (this.active.size + this.queued.size + this.pendingAccepts >= this.config.maxConcurrentTasks + this.config.maxQueuedTasks) {
      throw new TaskQueueFullError()
    }
    this.pendingAccepts++
    const accepting = (async () => {
      const accepted = await this.store.accept(taskId, request)
      this.getBroadcast(taskId)
      if (accepted.created) {
        this.enqueue(taskId)
        queueMicrotask(() => this.pump())
      }
      return accepted
    })()
    this.inFlightAccepts.set(taskId, accepting)
    try {
      return await accepting
    } finally {
      this.pendingAccepts--
      if (this.inFlightAccepts.get(taskId) === accepting) this.inFlightAccepts.delete(taskId)
    }
  }

  get(taskId: string): StoredTaskRecord | undefined {
    return this.store.get(taskId)
  }

  getBroadcast(taskId: string): EventBroadcast {
    let broadcast = this.broadcasts.get(taskId)
    if (!broadcast || (broadcast.isClosed() && this.store.get(taskId)?.status !== 'terminal')) {
      broadcast = new EventBroadcast(taskId, path.join(this.config.stateDir, 'events'))
      broadcast.loadFromDisk()
      this.broadcasts.set(taskId, broadcast)
    }
    if (this.store.get(taskId)?.status === 'terminal') {
      void this.retireBroadcast(taskId, broadcast).catch(() => {
        console.error(`[codex-app-server] terminal_broadcast_retire_failed task=${sanitize(taskId)}`)
      })
    }
    return broadcast
  }

  async abort(taskId: string): Promise<AbortTaskResult | undefined> {
    return this.withTaskOperation(taskId, async () => {
      let record = this.store.get(taskId)
      if (!record) return undefined
      if (record.status === 'terminal') {
        return {
          record,
          abort_status: record.outcome === 'aborted' ? 'aborted' : 'already_terminal',
        }
      }
      record = await this.store.requestAbort(taskId)
      this.queued.delete(taskId)
      const controller = this.abortControllers.get(taskId)
      if (controller) {
        controller.abort('Task aborted')
        const deadline = Date.now() + this.config.abortWaitTimeoutMs
        let current = this.store.get(taskId)!
        while (current.status !== 'terminal' && Date.now() < deadline) {
          await new Promise(resolve => setTimeout(resolve, 20))
          current = this.store.get(taskId) || current
        }
        return {
          record: current,
          abort_status: current.status !== 'terminal'
            ? 'abort_pending'
            : current.outcome === 'aborted'
              ? 'aborted'
              : 'already_terminal',
        }
      }
      const broadcast = this.getBroadcast(taskId)
      if (record.status === 'accepted' || record.status === 'starting' || !this.executor.reconcile) {
        const terminal = await this.finalizeLocalAbort(record, broadcast)
        return { record: terminal, abort_status: 'aborted' }
      }
      await this.reconcileCommitted(record, broadcast)
      const terminal = this.store.get(taskId) || record
      return {
        record: terminal,
        abort_status: terminal.status !== 'terminal'
          ? 'abort_pending'
          : terminal.outcome === 'aborted'
            ? 'aborted'
            : 'already_terminal',
      }
    })
  }

  async cleanupTerminal(taskId: string): Promise<StoredTaskRecord | undefined> {
    return this.withTaskOperation(taskId, async () => {
      const current = this.store.get(taskId)
      if (!current || current.status !== 'terminal') return undefined
      const tombstone = await this.store.tombstoneTerminal(taskId)
      if (!tombstone) return undefined
      const broadcast = this.broadcasts.get(taskId)
      try {
        if (broadcast) {
          try {
            await broadcast.close()
          } finally {
            await broadcast.purge()
          }
        } else {
          await EventBroadcast.purgePersisted(taskId, path.join(this.config.stateDir, 'events'))
        }
      } finally {
        if (broadcast && this.broadcasts.get(taskId) === broadcast) this.broadcasts.delete(taskId)
        await cleanupMaterializedInput(taskId, this.config.stateDir)
      }
      return tombstone
    })
  }

  activeCount(): number {
    return this.active.size
  }

  queuedCount(): number {
    return this.queued.size
  }

  isAccepting(): boolean {
    return this.accepting && !this.executor.isDraining?.()
  }

  runtimeMetrics(): Record<string, unknown> {
    return {
      active_tasks: this.active.size,
      queued_tasks: this.queued.size,
      pending_accepts: this.pendingAccepts,
      resident_broadcasts: this.broadcasts.size,
      max_queued_tasks: this.config.maxQueuedTasks,
      accepting: this.isAccepting(),
      ...(this.executor.metrics?.() || {}),
    }
  }

  async resumeRecoverableTasks(): Promise<void> {
    if (this.recoveryRun) return this.recoveryRun
    if (!this.isAccepting()) return
    const run = (async () => {
      for (const snapshot of this.store.list()) {
        if (!snapshot.recovery_required || snapshot.status === 'terminal') continue
        try {
          await this.withTaskOperation(snapshot.task_id, async () => {
            const current = this.store.get(snapshot.task_id)
            if (!current || current.status === 'terminal' || !current.recovery_required) return
            const broadcast = this.getBroadcast(snapshot.task_id)
            if (await this.finalizeDurableTerminalEvent(current, broadcast)) return
            if (current.abort_requested_at
                && (current.status === 'accepted' || current.status === 'starting')) {
              await this.finalizeLocalAbort(current, broadcast)
              return
            }
            if (current.status === 'accepted' || current.status === 'starting') {
              await this.store.patch(snapshot.task_id, { recovery_required: false })
              this.enqueue(snapshot.task_id)
              return
            }
            if (this.executor.reconcile) await this.reconcileCommitted(current, broadcast)
          })
        } catch (error) {
          await this.retireBroadcast(snapshot.task_id)
          await this.markRecoveryRequired(snapshot.task_id)
          console.warn(`[codex-app-server] recoverable_task_retry task=${sanitize(snapshot.task_id)} code=${stableExecutionErrorCode(error)}`)
        }
      }
      this.pump()
    })()
    this.recoveryRun = run
    try {
      await run
    } finally {
      if (this.recoveryRun === run) this.recoveryRun = undefined
    }
  }

  async shutdown(timeoutMs: number): Promise<boolean> {
    this.accepting = false
    const deadline = Date.now() + timeoutMs
    const settleReserveMs = Math.min(1_000, Math.floor(timeoutMs / 10))
    let drainCompleted = true
    try {
      await this.executor.drain?.(Math.max(0, deadline - Date.now() - settleReserveMs))
    } catch {
      drainCompleted = false
    }
    while (!this.isQuiesced() && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 25))
    }
    return drainCompleted && this.isQuiesced()
  }

  private isQuiesced(): boolean {
    return this.active.size === 0
      && this.pendingAccepts === 0
      && this.inFlightAccepts.size === 0
      && this.taskOperations.size === 0
      && this.recoveryRun === undefined
  }

  private enqueue(taskId: string): void {
    if (!this.active.has(taskId)) this.queued.add(taskId)
  }

  private pump(): void {
    if (!this.accepting) return
    while (this.active.size < this.config.maxConcurrentTasks) {
      const taskId = this.queued.values().next().value as string | undefined
      if (!taskId) return
      this.queued.delete(taskId)
      this.active.add(taskId)
      void this.execute(taskId).catch(() => {
        console.error(`[codex-app-server] task_state_failure task=${sanitize(taskId)}`)
      }).finally(() => {
        this.active.delete(taskId)
        this.abortControllers.delete(taskId)
        this.pump()
      })
    }
  }

  private async execute(taskId: string): Promise<void> {
    const broadcast = this.getBroadcast(taskId)
    const controller = new AbortController()
    this.abortControllers.set(taskId, controller)
    try {
      const initial = this.store.get(taskId)
      if (!initial || initial.status === 'terminal') return
      const request = this.store.getRequest(taskId)
      if (initial.status === 'accepted') await this.store.transition(taskId, 'starting')
      const resolved = resolveModelAlias(request.model || this.config.defaultModel, this.config.modelAliases)
      const parsed = parseModelString(resolved)
      await this.store.patch(taskId, { model: parsed.model, reasoning_effort: parsed.reasoningEffort })
      if (broadcast.getEventCount() === 0) {
        broadcast.emit({
          type: 'assistant_text',
          task_id: taskId,
          session_id: request.session_id,
          subtype: 'sync_checkpoint',
          content: '',
        })
      }
      const result = await this.executor.execute({
        taskId,
        request,
        broadcast,
        signal: controller.signal,
        callbacks: {
          onInstanceResolved: async (instanceId, laneKey) => {
            await this.store.patch(taskId, {
              app_server_instance_id: instanceId,
              app_server_lane_key: laneKey,
            })
          },
          onThreadResolved: async threadId => {
            await this.store.patch(taskId, { thread_id: threadId })
          },
          onExecutionCommitted: async threadId => {
            await this.store.transition(taskId, 'committed', { thread_id: threadId })
            broadcast.emit({
              type: 'assistant_text',
              task_id: taskId,
              session_id: threadId,
              subtype: 'execution_committed',
              content: '',
            })
            await broadcast.flush()
          },
          onTurnStarted: async (threadId, turnId) => {
            await this.store.transition(taskId, 'running', {
              thread_id: threadId,
              turn_id: turnId,
            })
          },
        },
      })
      if (result.status === 'interrupted') {
        this.emitError(broadcast, taskId, result.threadId, 'TASK_ABORTED')
        await broadcast.flush()
        await this.store.transition(taskId, 'terminal', { outcome: 'aborted', error_code: 'TASK_ABORTED' })
      } else if (result.status === 'failed') {
        this.emitError(broadcast, taskId, result.threadId, 'APP_SERVER_TURN_FAILED')
        await broadcast.flush()
        await this.store.transition(taskId, 'terminal', { outcome: 'failed', error_code: 'APP_SERVER_TURN_FAILED' })
      } else {
        broadcast.emit({
          type: 'result',
          task_id: taskId,
          session_id: result.threadId,
          result: result.assistantText || undefined,
          input_tokens: result.inputTokens,
          output_tokens: result.outputTokens,
          duration_ms: result.durationMs,
          num_turns: 1,
          model: result.model,
        })
        await broadcast.flush()
        await this.store.transition(taskId, 'terminal', {
          outcome: 'completed',
          thread_id: result.threadId,
          turn_id: result.turnId,
        })
      }
    } catch (error) {
      const aborted = controller.signal.aborted
      let latest = this.store.get(taskId)
      const runtimeError = error instanceof AppServerRuntimeError ? error : undefined
      if (runtimeError?.turnId && latest && !latest.turn_id) {
        latest = await this.store.patch(taskId, {
          thread_id: runtimeError.threadId || latest.thread_id,
          turn_id: runtimeError.turnId,
        })
      }
      const mayHaveExecuted = latest?.status === 'running'
        || runtimeError?.turnMayHaveStarted === true
        || (!runtimeError && latest?.status === 'committed')
      if (latest && latest.status !== 'terminal' && mayHaveExecuted && this.executor.reconcile) {
        try {
          await this.reconcileCommitted(latest, broadcast)
          const reconciled = this.store.get(taskId)
          console.warn(`[codex-app-server] task_reconciled task=${sanitize(taskId)} code=${sanitize(reconciled?.error_code || reconciled?.outcome || 'unknown')}`)
          return
        } catch (reconcileError) {
          await this.markRecoveryRequired(taskId)
          throw reconcileError
        }
      }
      const code = aborted ? 'TASK_ABORTED' : stableExecutionErrorCode(error)
      if (latest?.status !== 'terminal') {
        this.emitError(broadcast, taskId, latest?.thread_id, code)
        try {
          await broadcast.flush()
        } finally {
          await this.store.transition(taskId, 'terminal', {
            outcome: aborted ? 'aborted' : 'failed',
            error_code: code,
          })
        }
      }
      console.warn(`[codex-app-server] task_failed task=${sanitize(taskId)} code=${code}`)
    } finally {
      await this.markRecoveryRequired(taskId)
      await this.retireBroadcast(taskId, broadcast)
    }
  }

  private emitError(broadcast: EventBroadcast, taskId: string, threadId: string | undefined, code: string): void {
    const alreadyEmitted = broadcast.getEventsAfter(0).some(event => event.type === 'error' && event.subtype === code)
    if (alreadyEmitted) return
    broadcast.emit({
      type: 'error',
      task_id: taskId,
      session_id: threadId,
      error: code,
      subtype: code,
    })
  }

  private async reconcileCommitted(record: StoredTaskRecord, broadcast: EventBroadcast): Promise<void> {
    if (await this.finalizeDurableTerminalEvent(record, broadcast)) return
    record = this.store.get(record.task_id) || record
    let result: Awaited<ReturnType<NonNullable<TaskExecutor['reconcile']>>>
    try {
      result = await this.executor.reconcile!({
        taskId: record.task_id,
        request: this.store.getRequest(record.task_id),
        record,
        signal: AbortSignal.timeout(
          this.config.poolAcquireTimeoutMs + this.config.abortWaitTimeoutMs + 5_000,
        ),
      })
    } catch {
      result = { status: 'unknown', threadId: record.thread_id || '' }
    }
    const common = {
      thread_id: result.threadId || record.thread_id,
      turn_id: result.turnId || record.turn_id,
      app_server_instance_id: result.instanceId || record.app_server_instance_id,
      app_server_lane_key: result.laneKey || record.app_server_lane_key,
      recovery_required: false,
    }
    if (result.status === 'completed') {
      const latestCanonicalText = [...broadcast.getEventsAfter(0)]
        .reverse()
        .find(isCanonicalAssistantEvent)?.content
      if (result.assistantText && latestCanonicalText !== result.assistantText) {
        broadcast.emit({
          type: 'assistant_text',
          task_id: record.task_id,
          session_id: common.thread_id,
          content: result.assistantText,
          subtype: 'recovered',
        })
      }
      broadcast.emit({
        type: 'result',
        task_id: record.task_id,
        session_id: common.thread_id,
        result: result.assistantText,
        model: result.model || record.model,
        subtype: 'recovered',
      })
      await broadcast.flush()
      await this.store.transition(record.task_id, 'terminal', { ...common, outcome: 'completed' })
    } else if (result.status === 'unknown' && record.abort_requested_at) {
      await this.store.patch(record.task_id, { ...common, recovery_required: true })
      await this.retireBroadcast(record.task_id, broadcast)
      return
    } else {
      const code = result.status === 'interrupted'
        ? 'TASK_ABORTED'
        : result.status === 'failed'
          ? 'APP_SERVER_TURN_FAILED'
          : 'APP_SERVER_RECOVERY_UNKNOWN'
      this.emitError(broadcast, record.task_id, common.thread_id, code)
      await broadcast.flush()
      await this.store.transition(record.task_id, 'terminal', {
        ...common,
        outcome: result.status === 'interrupted' ? 'aborted' : 'failed',
        error_code: code,
      })
    }
    await this.retireBroadcast(record.task_id, broadcast)
  }

  private async finalizeDurableTerminalEvent(
    record: StoredTaskRecord,
    broadcast: EventBroadcast,
    events = broadcast.getEventsAfter(0),
  ): Promise<boolean> {
    const terminal = [...events].reverse().find(event => event.type === 'result' || event.type === 'error')
    if (!terminal) return false
    const patch = terminal.type === 'result'
      ? { outcome: 'completed' as const }
      : {
          outcome: terminal.subtype === 'TASK_ABORTED' ? 'aborted' as const : 'failed' as const,
          error_code: terminal.subtype,
        }
    await this.store.transition(record.task_id, 'terminal', patch)
    await this.retireBroadcast(record.task_id, broadcast)
    return true
  }

  private async finalizeLocalAbort(
    record: StoredTaskRecord,
    broadcast: EventBroadcast,
  ): Promise<StoredTaskRecord> {
    this.emitError(broadcast, record.task_id, record.thread_id, 'TASK_ABORTED')
    await broadcast.flush()
    const terminal = await this.store.transition(record.task_id, 'terminal', {
      outcome: 'aborted',
      error_code: 'TASK_ABORTED',
    })
    await this.retireBroadcast(record.task_id, broadcast)
    return terminal
  }

  private async markRecoveryRequired(taskId: string): Promise<void> {
    try {
      const current = this.store.get(taskId)
      if (current && current.status !== 'terminal' && !current.recovery_required) {
        await this.store.patch(taskId, { recovery_required: true })
      }
    } catch {
      console.error(`[codex-app-server] recovery_marker_failed task=${sanitize(taskId)}`)
    }
  }

  private async retireBroadcast(taskId: string, expected?: EventBroadcast): Promise<void> {
    const broadcast = expected || this.broadcasts.get(taskId)
    if (!broadcast) return
    try {
      await broadcast.close()
    } catch {
      // A fresh broadcast will reload only events that reached durable storage.
    } finally {
      if (this.broadcasts.get(taskId) === broadcast) this.broadcasts.delete(taskId)
    }
  }

  private async withTaskOperation<T>(taskId: string, action: () => Promise<T>): Promise<T> {
    const previous = this.taskOperations.get(taskId) || Promise.resolve()
    let release!: () => void
    const current = new Promise<void>(resolve => { release = resolve })
    const queued = previous.catch(() => undefined).then(() => current)
    this.taskOperations.set(taskId, queued)
    await previous.catch(() => undefined)
    try {
      return await action()
    } finally {
      release()
      if (this.taskOperations.get(taskId) === queued) this.taskOperations.delete(taskId)
    }
  }
}

export function toPublicTask(record: StoredTaskRecord): Record<string, unknown> {
  return compact({
    task_id: record.task_id,
    status: record.status,
    outcome: record.outcome,
    error_code: record.error_code,
    thread_id: record.thread_id,
    turn_id: record.turn_id,
    app_server_instance_id: record.app_server_instance_id,
    model: record.model,
    reasoning_effort: record.reasoning_effort,
    created_at: record.created_at,
    updated_at: record.updated_at,
    terminal_at: record.terminal_at,
    recovery_required: record.recovery_required,
    abort_requested: Boolean(record.abort_requested_at),
    abort_requested_at: record.abort_requested_at,
    tombstoned: Boolean(record.tombstoned_at),
    tombstoned_at: record.tombstoned_at,
  })
}

function compact<T extends Record<string, unknown>>(value: T): T {
  return Object.fromEntries(Object.entries(value).filter(([, item]) => item !== undefined)) as T
}

function sanitize(value: string): string {
  return value.replace(/[^A-Za-z0-9._-]/g, '_').slice(0, 128)
}

function isCanonicalAssistantEvent(event: WorkerEvent): boolean {
  return event.type === 'assistant_text'
    && Boolean(event.content?.trim())
    && event.subtype !== 'text_delta'
    && event.subtype !== 'sync_checkpoint'
    && event.subtype !== 'execution_committed'
}

function stableExecutionErrorCode(error: unknown): string {
  const code = error && typeof error === 'object' && 'code' in error
    ? (error as { code?: unknown }).code
    : undefined
  return typeof code === 'string' && [
    'APP_SERVER_POOL_OVERLOADED',
    'APP_SERVER_POOL_DRAINING',
    'APP_SERVER_POOL_ACQUIRE_TIMEOUT',
    'WORKING_DIRECTORY_NOT_ALLOWED',
    'CODEX_HOME_MISSING',
    'CODEX_HOME_NOT_ISOLATED',
    'UNSUPPORTED_CODEX_CONFIG_KEY',
    'INVALID_CODEX_CONFIG_VALUE',
  ].includes(code)
    ? code
    : 'APP_SERVER_RUNTIME_FAILED'
}
