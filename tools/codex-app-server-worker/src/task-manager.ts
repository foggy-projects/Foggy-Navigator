import path from 'node:path'
import type { AppConfig } from './config.js'
import type {
  AppServerRequestId,
  PendingUserInputInteraction,
  ResolvedUserInputInteraction,
  StoredTaskRecord,
  TaskAttention,
  TaskAttentionStatus,
  TaskRequest,
  TerminationOperationSummary,
  WorkerEvent,
} from './models.js'
import type { PoolRateLimitsView } from './app-server/rate-limits.js'
import { parseModelString, resolveSupportedModelAlias } from './model-resolution.js'
import { EventBroadcast } from './persistence/event-store.js'
import { TaskStore } from './persistence/task-store.js'
import {
  cleanupMaterializedInput,
  type ManagedTaskProcessSnapshot,
  type TaskExecutor,
} from './app-server/executor.js'
import { AppServerRuntimeError } from './app-server/runtime.js'
import { GeneratedImageStore } from './generated-image-store.js'
import { classifyErrorCode, safeAppServerMessage } from './diagnostics.js'
import {
  normalizeUserInputAnswers,
  sameRequestId,
  toPendingInteraction,
  type UserInputServerRequest,
  type UserInputWireResponse,
} from './app-server/user-input.js'

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

export class TaskThreadActiveError extends Error {
  readonly code = 'APP_SERVER_THREAD_ACTIVE'
  constructor() {
    super('Codex app-server thread already has a nonterminal task')
    this.name = 'TaskThreadActiveError'
  }
}

export type UserInputResponseErrorCode =
  | 'USER_INPUT_NOT_PENDING'
  | 'USER_INPUT_REQUEST_MISMATCH'
  | 'USER_INPUT_ALREADY_RESPONDED'
  | 'USER_INPUT_RUNTIME_AFFINITY_LOST'

export class UserInputResponseError extends Error {
  constructor(readonly code: UserInputResponseErrorCode) {
    super(code)
    this.name = 'UserInputResponseError'
  }
}

export type UserInputResponseBody = {
  request_id: AppServerRequestId
  answers: unknown
}

type LiveUserInputInteraction = {
  taskId: string
  requestId: AppServerRequestId
  runtimeInstanceId: string
  resolve: (response: UserInputWireResponse) => void
  reject: (error: Error) => void
  timer?: NodeJS.Timeout
  settled: boolean
}

type AbortPreparation =
  | { kind: 'missing' }
  | { kind: 'terminal'; result: AbortTaskResult }
  | { kind: 'active'; record: StoredTaskRecord; controller: AbortController }
  | { kind: 'local'; record: StoredTaskRecord; broadcast: EventBroadcast }
  /**
   * The task may already have reached the provider, but this executor cannot
   * reconcile it after a process restart.  Cancellation intent is durable;
   * a local terminal outcome would be fabricated evidence.
   */
  | { kind: 'unconfirmed'; record: StoredTaskRecord }
  | { kind: 'reconcile'; record: StoredTaskRecord; broadcast: EventBroadcast }

type TerminalTransitionPatch = NonNullable<Parameters<TaskStore['transition']>[2]>

export type AbortTaskResult = {
  record: StoredTaskRecord
  abort_status: 'aborted' | 'cancel_requested' | 'already_terminal'
}

export type ManualPidKillResult = {
  record: StoredTaskRecord
  observed_exit: boolean
  /** A provider terminal event won before this signed operation could signal. */
  provider_terminal_observed?: boolean
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
  /** Coordinates the execute() unwind with the signed manual-PID owner. */
  private readonly manualPidTerminations = new Map<string, Promise<void>>()
  private readonly threadReservations = new Map<string, string>()
  private readonly taskThreadReservations = new Map<string, Set<string>>()
  /** A recovery collision keeps the thread unavailable until it is resolved. */
  private readonly blockedThreadReservations = new Set<string>()
  private readonly liveUserInput = new Map<string, LiveUserInputInteraction>()
  private recoveryRun?: Promise<void>

  constructor(
    private readonly config: AppConfig,
    readonly store: TaskStore,
    private readonly executor: TaskExecutor,
  ) {}

  async initialize(options: { resume?: boolean } = {}): Promise<void> {
    await this.store.initialize()
    if (options.resume !== false) this.store.verifyEncryptionKey()
    const reservationConflicts = this.rebuildThreadReservations()
    for (const snapshot of this.store.list()) {
      await cleanupMaterializedInput(snapshot.task_id, this.config.stateDir)
      const record = this.store.get(snapshot.task_id) || snapshot
      if (record.status === 'terminal') {
        this.releaseThreadReservations(record.task_id)
        if (record.tombstoned_at) {
          await EventBroadcast.purgePersisted(record.task_id, path.join(this.config.stateDir, 'events'))
          new GeneratedImageStore(this.config).cleanup(record.task_id)
        }
        continue
      }
      if (reservationConflicts.has(record.task_id)) {
        await this.finalizeThreadReservationConflict(record, this.getBroadcast(record.task_id))
        continue
      }
      if (record.pending_interaction) {
        await this.finalizeLostUserInput(record, this.getBroadcast(record.task_id))
        continue
      }
      const broadcast = this.getBroadcast(record.task_id)
      const events = broadcast.getEventsAfter(0)
      if (await this.finalizeDurableTerminalEvent(record, broadcast, events)) {
        this.releaseThreadReservations(record.task_id)
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
      if (this.store.get(record.task_id)?.status === 'terminal') {
        this.releaseThreadReservations(record.task_id)
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
    let requestedThreadReserved = false
    if (request.session_id) {
      this.reserveThread(taskId, request.session_id)
      requestedThreadReserved = true
    }
    if (this.active.size + this.queued.size + this.pendingAccepts >= this.config.maxConcurrentTasks + this.config.maxQueuedTasks) {
      if (requestedThreadReserved) this.releaseThreadReservations(taskId)
      throw new TaskQueueFullError()
    }
    this.pendingAccepts++
    const accepting = (async () => {
      const accepted = await this.store.accept(taskId, request)
      this.getBroadcast(taskId)
      if (accepted.created) {
        this.enqueue(taskId)
        queueMicrotask(() => this.pump())
      } else if (accepted.record.status === 'terminal') {
        this.releaseThreadReservations(taskId)
      }
      return accepted
    })()
    this.inFlightAccepts.set(taskId, accepting)
    try {
      return await accepting
    } catch (error) {
      if (!this.store.get(taskId)) this.releaseThreadReservations(taskId)
      throw error
    } finally {
      this.pendingAccepts--
      if (this.inFlightAccepts.get(taskId) === accepting) this.inFlightAccepts.delete(taskId)
    }
  }

  get(taskId: string): StoredTaskRecord | undefined {
    return this.store.get(taskId)
  }

  /**
   * The executor owns the live lease map.  This pass-through intentionally
   * has no reconciliation, signaling, or pool-management side effect: a
   * process snapshot is evidence for a later authorized operation, not an
   * operation by itself.
   */
  async listManagedTaskProcesses(): Promise<readonly ManagedTaskProcessSnapshot[]> {
    const snapshot = await this.executor.listManagedTaskProcesses?.()
    return snapshot || []
  }

  async respondToUserInput(taskId: string, body: UserInputResponseBody): Promise<StoredTaskRecord> {
    return this.withTaskOperation(taskId, async () => {
      const record = this.store.get(taskId)
      if (!record) throw new UserInputResponseError('USER_INPUT_NOT_PENDING')
      const pending = record.pending_interaction
      if (!pending) {
        if (record.last_interaction?.state === 'answered'
            && sameRequestId(record.last_interaction.request_id, body.request_id)) {
          throw new UserInputResponseError('USER_INPUT_ALREADY_RESPONDED')
        }
        throw new UserInputResponseError('USER_INPUT_NOT_PENDING')
      }
      if (!sameRequestId(pending.request_id, body.request_id)) {
        throw new UserInputResponseError('USER_INPUT_REQUEST_MISMATCH')
      }
      const live = this.liveUserInput.get(taskId)
      if (!live
          || !sameRequestId(live.requestId, pending.request_id)
          || live.runtimeInstanceId !== pending.runtime_instance_id
          || record.app_server_instance_id !== pending.runtime_instance_id
          || record.thread_id !== pending.thread_id
          || record.turn_id !== pending.turn_id) {
        await this.failUserInputAffinity(record, pending, this.getBroadcast(taskId))
        throw new UserInputResponseError('USER_INPUT_RUNTIME_AFFINITY_LOST')
      }
      const response = normalizeUserInputAnswers(body.answers, pending)
      const resolved = this.resolvedInteraction(pending, 'answered')
      const updated = await this.store.patch(taskId, {
        pending_interaction: undefined,
        last_interaction: resolved,
      })
      const broadcast = this.getBroadcast(taskId)
      this.emitUserInputResolved(broadcast, taskId, pending, 'answered')
      await broadcast.flush()
      this.settleLiveUserInput(live, { response })
      return updated
    })
  }

  getBroadcast(taskId: string): EventBroadcast {
    let broadcast = this.broadcasts.get(taskId)
    if (!broadcast || ((broadcast.isClosed() || broadcast.hasWriteError())
      && this.store.get(taskId)?.status !== 'terminal')) {
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

  async abort(taskId: string, operation?: TerminationOperationSummary): Promise<AbortTaskResult | undefined> {
    const preparation = await this.withTaskOperation<AbortPreparation>(taskId, async () => {
      let record = this.store.get(taskId)
      if (!record) return { kind: 'missing' }
      if (record.status === 'terminal') {
        return { kind: 'terminal', result: {
          record,
          // This request did not cause the terminal event, including when the
          // historical outcome happened to be ABORTED.
          abort_status: 'already_terminal',
        } }
      }
      record = await this.store.requestAbort(taskId, operation)
      this.queued.delete(taskId)
      const broadcast = this.getBroadcast(taskId)
      if (record.pending_interaction) {
        await this.clearLiveUserInputLocked(taskId, 'cleared', broadcast)
        record = this.store.get(taskId) || record
      }
      const controller = this.abortControllers.get(taskId)
      if (controller) {
        controller.abort('Task aborted')
        return { kind: 'active', record, controller }
      }
      if (record.status === 'accepted' || record.status === 'starting') {
        return { kind: 'local', record, broadcast }
      }
      if (!this.executor.reconcile) return { kind: 'unconfirmed', record }
      return { kind: 'reconcile', record, broadcast }
    })

    if (preparation.kind === 'missing') return undefined
    if (preparation.kind === 'terminal') return preparation.result
    if (preparation.kind === 'active') {
      const deadline = Date.now() + this.config.abortWaitTimeoutMs
      let current = this.store.get(taskId) || preparation.record
      while (current.status !== 'terminal' && Date.now() < deadline) {
        await new Promise(resolve => setTimeout(resolve, 20))
        current = this.store.get(taskId) || current
      }
      return {
        record: current,
        abort_status: current.status !== 'terminal'
          ? 'cancel_requested'
          : current.outcome === 'aborted'
            ? 'aborted'
            : 'already_terminal',
      }
    }
    if (preparation.kind === 'local') {
      const terminal = await this.finalizeLocalAbort(preparation.record, preparation.broadcast)
      return { record: terminal, abort_status: 'aborted' }
    }
    if (preparation.kind === 'unconfirmed') {
      // A restart can leave COMMITTED/RUNNING durable state without a local
      // runtime.  Do not convert a requested cancel into a terminal outcome
      // merely because this executor cannot query the provider.
      await this.recordTerminationResult(taskId, 'CANCEL_RECONCILIATION_UNCONFIRMED')
      await this.markAttention(
        taskId,
        'TERMINATION_UNCONFIRMED',
        'CANCEL_RECONCILIATION_UNCONFIRMED',
      )
      return {
        record: this.store.get(taskId) || preparation.record,
        abort_status: 'cancel_requested',
      }
    }
    let dispatched: Awaited<ReturnType<NonNullable<TaskExecutor['requestExplicitAbort']>>> | undefined
    try {
      dispatched = await this.executor.requestExplicitAbort?.(taskId, preparation.record)
    } catch {
      await this.recordTerminationResult(taskId, 'TERMINATION_DISPATCH_FAILED')
      await this.markAttention(taskId, 'TERMINATION_UNCONFIRMED', 'TERMINATION_DISPATCH_FAILED')
      const current = this.store.get(taskId) || preparation.record
      return { record: current, abort_status: 'cancel_requested' }
    }
    if (dispatched === 'requested') {
      await this.recordTerminationResult(taskId, 'CANCEL_DISPATCHED')
      await this.reconcileCommitted(preparation.record, preparation.broadcast)
    } else {
      await this.recordTerminationResult(taskId, 'TERMINATION_RUNTIME_UNAVAILABLE')
      await this.markAttention(taskId, 'PROCESS_UNVERIFIED', 'CANCEL_REQUESTED_WITHOUT_LIVE_RUNTIME')
    }
    const terminal = this.store.get(taskId) || preparation.record
    return {
      record: terminal,
      abort_status: terminal.status !== 'terminal'
        ? 'cancel_requested'
        : terminal.outcome === 'aborted'
          ? 'aborted'
          : 'already_terminal',
    }
  }

  async manualPidKill(
    taskId: string,
    pid: number,
    operation: TerminationOperationSummary,
  ): Promise<ManualPidKillResult | undefined> {
    const existingCompletion = this.manualPidTerminations.get(taskId)
    let resolveCompletion: (() => void) | undefined
    const completion = existingCompletion || new Promise<void>(resolve => { resolveCompletion = resolve })
    if (!existingCompletion) this.manualPidTerminations.set(taskId, completion)
    try {
      return await this.withTaskOperation(taskId, async () => {
        let record = this.store.get(taskId)
        if (!record) return undefined
        // A freshly authorized manual operation must never inherit a terminal
        // outcome from an earlier execution or operation.  In particular, an
        // already-ABORTED task is evidence about its historical terminal event,
        // not proof that this incoming PID capability observed a process exit.
        // Leave the durable record (and any historical operation) untouched.
        if (record.status === 'terminal') return { record, observed_exit: false }
        const abortRequestedBeforeOperation = record.abort_requested_at
        const terminationOperationBeforeRequest = record.termination_operation
        const ownsNewTerminationOperation = terminationOperationBeforeRequest?.operation_id !== operation.operation_id
        record = await this.store.requestAbort(taskId, operation)
        let result: Awaited<ReturnType<NonNullable<TaskExecutor['manualPidKill']>>> | undefined
        try {
          result = await this.executor.manualPidKill?.(taskId, pid, record, operation)
        } catch {
          await this.recordTerminationResultLocked(taskId, 'MANUAL_PID_KILL_DISPATCH_FAILED')
          const marked = await this.markAttentionLocked(
            taskId,
            'TERMINATION_UNCONFIRMED',
            'MANUAL_PID_KILL_DISPATCH_FAILED',
          )
          return { record: marked || this.store.get(taskId) || record, observed_exit: false }
        }
        if (result?.provider_terminal_observed) {
          // Provider terminal evidence arrived before this capability could
          // signal.  This request must not leave a false CANCEL_REQUESTED
          // marker or claim the provider's natural terminal event as a manual
          // PID result.  The provider result branch is serialized on the same
          // task operation and will publish the sole terminal outcome next.
          const current = this.store.get(taskId)
          if (current
              && current.status !== 'terminal'
              && ownsNewTerminationOperation
              && current.termination_operation?.operation_id === operation.operation_id) {
            const restored = await this.store.patch(taskId, {
              abort_requested_at: abortRequestedBeforeOperation,
              termination_operation: terminationOperationBeforeRequest,
            })
            return {
              record: restored,
              observed_exit: false,
              provider_terminal_observed: true,
            }
          }
          return {
            record: current || record,
            observed_exit: false,
            provider_terminal_observed: true,
          }
        }
        if (!result?.observed_exit) {
          await this.recordTerminationResultLocked(taskId, 'MANUAL_PID_KILL_UNCONFIRMED')
          const marked = await this.markAttentionLocked(
            taskId,
            'TERMINATION_UNCONFIRMED',
            'MANUAL_PID_KILL_UNCONFIRMED',
          )
          return { record: marked || this.store.get(taskId) || record, observed_exit: false }
        }
        const broadcast = this.getBroadcast(taskId)
        this.emitError(
          broadcast,
          taskId,
          record.thread_id,
          'TASK_ABORTED',
          'ABORTED',
          'VERIFIED_MANAGED_PROCESS_EXIT',
        )
        await broadcast.flush()
        const terminal = await this.transitionTerminal(taskId, {
          outcome: 'aborted',
          error_code: 'TASK_ABORTED',
        })
        this.releaseThreadReservations(taskId)
        await this.retireBroadcast(taskId, broadcast)
        return { record: terminal, observed_exit: true }
      })
    } finally {
      if (!existingCompletion) {
        resolveCompletion?.()
        if (this.manualPidTerminations.get(taskId) === completion) {
          this.manualPidTerminations.delete(taskId)
        }
      }
    }
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
        new GeneratedImageStore(this.config).cleanup(taskId)
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
      pending_user_inputs: this.liveUserInput.size,
      reserved_threads: this.threadReservations.size,
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
    const drainingTasks = new Set([
      ...this.active,
      ...this.store.list()
        .filter(record => record.status === 'starting' || record.status === 'committed' || record.status === 'running')
        .map(record => record.task_id),
    ])
    await Promise.all([...drainingTasks].map(taskId => this.markAttention(
      taskId,
      'WORKER_DRAINING_PENDING_DECISION',
      'WORKER_DRAIN_REQUESTED_WHILE_TASK_ACTIVE',
    )))
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
    const unresolvedExecution = this.store.list().some(record => (
      record.status === 'starting' || record.status === 'committed' || record.status === 'running'
    ))
    return this.active.size === 0
      && this.pendingAccepts === 0
      && this.inFlightAccepts.size === 0
      && this.taskOperations.size === 0
      && this.recoveryRun === undefined
      && !unresolvedExecution
  }

  private enqueue(taskId: string): void {
    if (!this.active.has(taskId)) this.queued.add(taskId)
  }

  async readDefaultRateLimits(refresh = false): Promise<PoolRateLimitsView> {
    if (!this.executor.readDefaultRateLimits) {
      return {
        state: 'UNSUPPORTED',
        observed_at_epoch_ms: null,
        stale: false,
        limits: [],
        error_code: 'RATE_LIMITS_UNSUPPORTED',
      }
    }
    return this.executor.readDefaultRateLimits(refresh)
  }

  private async awaitUserInput(
    taskId: string,
    request: UserInputServerRequest,
    runtimeInstanceId: string,
    signal: AbortSignal,
    broadcast: EventBroadcast,
  ): Promise<UserInputWireResponse> {
    const pending = toPendingInteraction(request, runtimeInstanceId, new Date().toISOString())
    let live!: LiveUserInputInteraction
    const response = new Promise<UserInputWireResponse>((resolve, reject) => {
      live = {
        taskId,
        requestId: request.requestId,
        runtimeInstanceId,
        resolve,
        reject,
        settled: false,
      }
    })
    await this.withTaskOperation(taskId, async () => {
      const record = this.store.get(taskId)
      if (!record || record.status !== 'running'
          || record.pending_interaction
          || this.liveUserInput.has(taskId)
          || record.thread_id !== request.threadId
          || record.turn_id !== request.turnId
          || record.app_server_instance_id !== runtimeInstanceId) {
        throw new UserInputResponseError('USER_INPUT_RUNTIME_AFFINITY_LOST')
      }
      await this.store.patch(taskId, { pending_interaction: pending })
      this.liveUserInput.set(taskId, live)
      broadcast.emit({
        type: 'user_input_request',
        subtype: 'request_user_input',
        task_id: taskId,
        session_id: pending.thread_id,
        data: pending,
      })
      await broadcast.flush()
    })

    const abort = (): void => {
      void this.clearLiveUserInput(taskId, 'cleared', broadcast)
    }
    if (signal.aborted) abort()
    else signal.addEventListener('abort', abort, { once: true })
    if (request.autoResolutionMs !== undefined) {
      live.timer = setTimeout(() => {
        void this.autoResolveUserInput(taskId, request.requestId, broadcast)
      }, request.autoResolutionMs)
      live.timer.unref?.()
    }
    try {
      return await response
    } finally {
      signal.removeEventListener('abort', abort)
      if (live.timer) clearTimeout(live.timer)
    }
  }

  private async autoResolveUserInput(
    taskId: string,
    requestId: AppServerRequestId,
    broadcast: EventBroadcast,
  ): Promise<void> {
    await this.withTaskOperation(taskId, async () => {
      const record = this.store.get(taskId)
      const pending = record?.pending_interaction
      const live = this.liveUserInput.get(taskId)
      if (!record || !pending || !live || !sameRequestId(requestId, pending.request_id)
          || !sameRequestId(requestId, live.requestId)) return
      await this.store.patch(taskId, {
        pending_interaction: undefined,
        last_interaction: this.resolvedInteraction(pending, 'auto_resolved'),
      })
      this.emitUserInputResolved(broadcast, taskId, pending, 'auto_resolved')
      await broadcast.flush()
      this.settleLiveUserInput(live, { response: { answers: {} } })
    })
  }

  private async resolveUserInputFromServer(
    taskId: string,
    requestId: AppServerRequestId,
    threadId: string,
    runtimeInstanceId: string,
    broadcast: EventBroadcast,
  ): Promise<void> {
    await this.withTaskOperation(taskId, async () => {
      const record = this.store.get(taskId)
      const pending = record?.pending_interaction
      if (!record) return
      if (!pending) return
      if (!sameRequestId(pending.request_id, requestId)
          || pending.thread_id !== threadId
          || pending.runtime_instance_id !== runtimeInstanceId) {
        return
      }
      const reason = 'cleared' as const
      await this.store.patch(taskId, {
        pending_interaction: undefined,
        last_interaction: this.resolvedInteraction(pending, reason),
      })
      this.emitUserInputResolved(broadcast, taskId, pending, reason)
      await broadcast.flush()
      const live = this.liveUserInput.get(taskId)
      if (live) this.settleLiveUserInput(live, { error: new Error('SERVER_REQUEST_RESOLVED') })
    })
  }

  private async clearLiveUserInput(
    taskId: string,
    reason: 'cleared',
    broadcast: EventBroadcast,
  ): Promise<void> {
    if (!this.store.get(taskId)?.pending_interaction) return
    await this.withTaskOperation(taskId, async () => {
      await this.clearLiveUserInputLocked(taskId, reason, broadcast)
    })
  }

  private async clearLiveUserInputLocked(
    taskId: string,
    reason: 'cleared',
    broadcast: EventBroadcast,
  ): Promise<void> {
    const record = this.store.get(taskId)
    const pending = record?.pending_interaction
    if (!record || !pending) return
    await this.store.patch(taskId, {
      pending_interaction: undefined,
      last_interaction: this.resolvedInteraction(pending, reason),
    })
    this.emitUserInputResolved(broadcast, taskId, pending, reason)
    await broadcast.flush()
    const live = this.liveUserInput.get(taskId)
    if (live) this.settleLiveUserInput(live, { error: new Error('USER_INPUT_CHANNEL_CLEARED') })
  }

  private settleLiveUserInput(
    live: LiveUserInputInteraction,
    outcome: { response: UserInputWireResponse } | { error: Error },
  ): void {
    if (live.settled) return
    live.settled = true
    if (live.timer) clearTimeout(live.timer)
    if (this.liveUserInput.get(live.taskId) === live) this.liveUserInput.delete(live.taskId)
    if ('response' in outcome) live.resolve(outcome.response)
    else live.reject(outcome.error)
  }

  private async failUserInputAffinity(
    record: StoredTaskRecord,
    pending: PendingUserInputInteraction,
    broadcast: EventBroadcast,
  ): Promise<void> {
    this.emitUserInputResolved(broadcast, record.task_id, pending, 'cleared')
    this.emitError(broadcast, record.task_id, pending.thread_id, 'USER_INPUT_CHANNEL_LOST')
    await broadcast.flush()
    await this.store.patch(record.task_id, {
      recovery_required: true,
      pending_interaction: undefined,
      last_interaction: this.resolvedInteraction(pending, 'failed'),
    })
    // Persist the pending-decision marker before waking the execution promise.
    // Otherwise its catch path can race ahead and manufacture a terminal
    // failure from the local user-input channel loss.
    await this.markAttentionLocked(record.task_id, 'PROCESS_UNVERIFIED', 'USER_INPUT_CHANNEL_LOST')
    const live = this.liveUserInput.get(record.task_id)
    if (live) this.settleLiveUserInput(live, { error: new Error('USER_INPUT_CHANNEL_LOST') })
  }

  private async finalizeLostUserInput(record: StoredTaskRecord, broadcast: EventBroadcast): Promise<void> {
    const pending = record.pending_interaction
    if (!pending) return
    this.emitUserInputResolved(broadcast, record.task_id, pending, 'cleared')
    this.emitError(broadcast, record.task_id, pending.thread_id, 'USER_INPUT_CHANNEL_LOST')
    await broadcast.flush()
    await this.store.patch(record.task_id, {
      recovery_required: true,
      pending_interaction: undefined,
      last_interaction: this.resolvedInteraction(pending, 'failed'),
    })
    await this.markAttentionLocked(record.task_id, 'PROCESS_UNVERIFIED', 'USER_INPUT_CHANNEL_LOST')
  }

  private resolvedInteraction(
    pending: PendingUserInputInteraction,
    state: ResolvedUserInputInteraction['state'],
  ): ResolvedUserInputInteraction {
    return {
      contract_version: 1,
      request_id: pending.request_id,
      thread_id: pending.thread_id,
      turn_id: pending.turn_id,
      runtime_instance_id: pending.runtime_instance_id,
      state,
      resolved_at: new Date().toISOString(),
    }
  }

  private emitUserInputResolved(
    broadcast: EventBroadcast,
    taskId: string,
    pending: PendingUserInputInteraction,
    reason: 'answered' | 'auto_resolved' | 'cleared',
  ): void {
    broadcast.emit({
      type: 'user_input_resolved',
      subtype: 'request_user_input_resolved',
      task_id: taskId,
      session_id: pending.thread_id,
      data: { contract_version: 1, request_id: pending.request_id, reason },
    })
  }

  private rebuildThreadReservations(): Set<string> {
    this.threadReservations.clear()
    this.taskThreadReservations.clear()
    this.blockedThreadReservations.clear()
    const conflicts = new Set<string>()
    const records = this.store.list().sort((left, right) => (
      left.created_at.localeCompare(right.created_at) || left.task_id.localeCompare(right.task_id)
    ))
    for (const record of records) {
      if (record.status === 'terminal') continue
      const candidates = new Set<string>()
      if (record.requested_thread_id) candidates.add(record.requested_thread_id)
      if (record.thread_id) candidates.add(record.thread_id)
      if (candidates.size === 0) {
        try {
          const requested = this.store.getRequest(record.task_id).session_id
          if (requested) candidates.add(requested)
        } catch {
          // Legacy records without a readable payload remain conservatively unreserved until recovery.
        }
      }
      try {
        for (const threadId of candidates) this.reserveThread(record.task_id, threadId)
      } catch (error) {
        if (!(error instanceof TaskThreadActiveError)) throw error
        for (const threadId of candidates) this.blockedThreadReservations.add(threadId.trim())
        this.releaseThreadReservations(record.task_id)
        conflicts.add(record.task_id)
      }
    }
    return conflicts
  }

  private reserveThread(taskId: string, threadId: string): void {
    const key = threadId.trim()
    if (!key) return
    const owner = this.threadReservations.get(key)
    if ((owner && owner !== taskId) || (this.blockedThreadReservations.has(key) && owner !== taskId)) {
      throw new TaskThreadActiveError()
    }
    this.threadReservations.set(key, taskId)
    const reservations = this.taskThreadReservations.get(taskId) || new Set<string>()
    reservations.add(key)
    this.taskThreadReservations.set(taskId, reservations)
  }

  private releaseThreadReservations(taskId: string): void {
    const reservations = this.taskThreadReservations.get(taskId)
    if (!reservations) return
    for (const threadId of reservations) {
      if (this.threadReservations.get(threadId) === taskId) this.threadReservations.delete(threadId)
    }
    this.taskThreadReservations.delete(taskId)
  }

  private async finalizeThreadReservationConflict(
    record: StoredTaskRecord,
    broadcast: EventBroadcast,
  ): Promise<void> {
    this.emitError(broadcast, record.task_id, record.thread_id, 'APP_SERVER_THREAD_ACTIVE_RECOVERY')
    await broadcast.flush()
    await this.markAttentionLocked(
      record.task_id,
      'PROCESS_UNVERIFIED',
      'APP_SERVER_THREAD_ACTIVE_RECOVERY',
    )
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
      const resolved = resolveSupportedModelAlias(request.model || this.config.defaultModel, this.config.modelAliases)
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
            this.reserveThread(taskId, threadId)
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
          onUserInputRequest: (request, runtimeInstanceId) => this.awaitUserInput(
            taskId,
            request,
            runtimeInstanceId,
            controller.signal,
            broadcast,
          ),
          onUserInputResolved: (resolution, runtimeInstanceId) => this.resolveUserInputFromServer(
            taskId,
            resolution.requestId,
            resolution.threadId,
            runtimeInstanceId,
            broadcast,
          ),
        },
      })
      // A provider terminal event and a signed manual PID request can arrive
      // during the same event-loop turn.  Publish and persist the provider
      // result under the same per-task operation used by manual PID dispatch,
      // so the losing side cannot emit a contradictory terminal event.
      await this.withTaskOperation(taskId, async () => {
        const current = this.store.get(taskId)
        if (!current || current.status === 'terminal') return
        if (result.status === 'interrupted') {
          this.emitError(broadcast, taskId, result.threadId, 'TASK_ABORTED', 'ABORTED', 'PROVIDER_TERMINAL_EVENT')
          await broadcast.flush()
          await this.transitionTerminal(taskId, { outcome: 'aborted', error_code: 'TASK_ABORTED' })
        } else if (result.status === 'failed') {
          const code = result.errorCode || 'APP_SERVER_TURN_FAILED'
          this.emitError(broadcast, taskId, result.threadId, code, 'FAILED', 'PROVIDER_TERMINAL_EVENT')
          await broadcast.flush()
          await this.transitionTerminal(taskId, { outcome: 'failed', error_code: code })
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
          await this.transitionTerminal(taskId, {
            outcome: 'completed',
            thread_id: result.threadId,
            turn_id: result.turnId,
          })
        }
      })
    } catch (error) {
      const runtimeError = error instanceof AppServerRuntimeError ? error : undefined
      const manualPidTermination = runtimeError?.code === 'APP_SERVER_AUTHORIZED_PROCESS_EXIT'
        || runtimeError?.code === 'APP_SERVER_MANUAL_TERMINATION_UNCONFIRMED'
      if (manualPidTermination) {
        // The manual-PID request owns the durable ABORTED transition.  Wait
        // for it before this concurrently unwinding execution decides whether
        // attention/recovery is needed.  This applies to both verified and
        // unconfirmed exits: the latter owns the single authoritative
        // TERMINATION_UNCONFIRMED marker, rather than racing a second generic
        // PROCESS_UNVERIFIED write under a different task operation lock.
        await this.manualPidTerminations.get(taskId)
      }
      const aborted = controller.signal.aborted
      await this.clearLiveUserInput(taskId, 'cleared', broadcast)
      let latest = this.store.get(taskId)
      if (manualPidTermination && latest) {
        const manualOutcomeRecorded = latest.status === 'terminal'
          || latest.termination_operation?.result_code === 'MANUAL_PID_KILL_UNCONFIRMED'
          || latest.termination_operation?.result_code === 'MANUAL_PID_KILL_DISPATCH_FAILED'
          || latest.attention?.some(item => (
            item.status === 'TERMINATION_UNCONFIRMED'
            && (item.reason_code === 'MANUAL_PID_KILL_UNCONFIRMED'
              || item.reason_code === 'MANUAL_PID_KILL_DISPATCH_FAILED')
          ))
        if (manualOutcomeRecorded) return
      }
      if (runtimeError?.turnId && latest && !latest.turn_id) {
        latest = await this.store.patch(taskId, {
          thread_id: runtimeError.threadId || latest.thread_id,
          turn_id: runtimeError.turnId,
        })
      }
      if (latest && latest.status !== 'terminal' && !aborted && isDeterministicNoExecutionFailure(error)) {
        const code = stableExecutionErrorCode(error)
        this.emitError(broadcast, taskId, latest.thread_id, code, 'FAILED', 'NO_EXECUTION_CONFIRMED')
        await broadcast.flush()
        await this.transitionTerminal(taskId, { outcome: 'failed', error_code: code })
        return
      }
      const mayHaveExecuted = latest?.status === 'running'
        || runtimeError?.turnMayHaveStarted === true
        || (!runtimeError && latest?.status === 'committed')
      const reconciliationUnsafe = runtimeError?.code === 'APP_SERVER_TURN_STALLED'
        || runtimeError?.code === 'APP_SERVER_UNEXPECTED_IMAGE_GENERATION'
        || runtimeError?.code === 'APP_SERVER_ABORT_UNCONFIRMED'
        || runtimeError?.code === 'APP_SERVER_PROCESS_UNVERIFIED'
        || runtimeError?.code === 'APP_SERVER_PROCESS_IDENTITY_MISMATCH'
        || runtimeError?.code === 'APP_SERVER_AUTHORIZED_PROCESS_EXIT'
      const hasPendingDecision = Boolean(latest?.attention?.length)
      if (latest && latest.status !== 'terminal' && (
        reconciliationUnsafe || runtimeError?.turnMayHaveStarted || hasPendingDecision
      )) {
        const code = stableExecutionErrorCode(error)
        this.emitError(broadcast, taskId, latest.thread_id, code)
        await broadcast.flush()
        await this.markAttentionLocked(
          taskId,
          runtimeError?.code === 'APP_SERVER_TURN_STALLED'
            ? 'TIMEOUT_PENDING_DECISION'
            : 'PROCESS_UNVERIFIED',
          code,
          runtimeError?.turnId ? `turn_id=${sanitize(runtimeError.turnId)}` : undefined,
        )
        return
      }
      if (latest && latest.status !== 'terminal' && mayHaveExecuted && this.executor.reconcile && !reconciliationUnsafe) {
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
      // An AbortController signal or a thrown runtime exception is not proof
      // that the provider turn/CLI exited.  In particular, an explicit cancel
      // can race a transport failure.  Keep the durable task pending until a
      // terminal result, verified exit, or reconciliation supplies evidence.
      const code = aborted ? 'CANCEL_RECONCILIATION_UNCONFIRMED' : stableExecutionErrorCode(error)
      if (latest?.status !== 'terminal') {
        this.emitError(broadcast, taskId, latest?.thread_id, code)
        await broadcast.flush()
        await this.markAttentionLocked(
          taskId,
          aborted || Boolean(latest?.abort_requested_at)
            ? 'TERMINATION_UNCONFIRMED'
            : 'PROCESS_UNVERIFIED',
          code,
        )
      }
      console.warn(`[codex-app-server] task_pending_observation task=${sanitize(taskId)} code=${code}`)
    } finally {
      await this.clearLiveUserInput(taskId, 'cleared', broadcast)
      await this.markRecoveryRequired(taskId)
      if (this.store.get(taskId)?.status === 'terminal') await this.retireBroadcast(taskId, broadcast)
      if (this.store.get(taskId)?.status === 'terminal') this.releaseThreadReservations(taskId)
    }
  }

  private emitError(
    broadcast: EventBroadcast,
    taskId: string,
    threadId: string | undefined,
    code: string,
    terminalStatus?: 'FAILED' | 'ABORTED',
    terminalSource?: string,
  ): void {
    const alreadyEmitted = broadcast.getEventsAfter(0).some(event => event.type === 'error' && event.subtype === code)
    if (alreadyEmitted) return
    broadcast.emit({
      type: 'error',
      task_id: taskId,
      session_id: threadId,
      error: code,
      error_code: code,
      error_message: safeAppServerMessage(code),
      error_category: classifyErrorCode(code),
      runtime_phase: 'TURN_EXECUTION',
      recoverable: code !== 'TASK_ABORTED',
      occurred_at: new Date().toISOString(),
      provider_type: 'CODEX',
      runtime_type: 'APP_SERVER',
      provider_status: code,
      subtype: code,
      ...(terminalStatus
        ? {
            terminal_observed: true,
            terminal_status: terminalStatus,
            terminal_source: terminalSource || 'PROVIDER_TERMINAL_EVENT',
          }
        : {}),
    })
  }

  async markAttention(
    taskId: string,
    status: TaskAttentionStatus,
    reasonCode: string,
    activityEvidence?: string,
  ): Promise<StoredTaskRecord | undefined> {
    return this.withTaskOperation(taskId, () => this.markAttentionLocked(
      taskId,
      status,
      reasonCode,
      activityEvidence,
    ))
  }

  private async markAttentionLocked(
    taskId: string,
    status: TaskAttentionStatus,
    reasonCode: string,
    activityEvidence?: string,
  ): Promise<StoredTaskRecord | undefined> {
    const current = this.store.get(taskId)
    if (!current || current.status === 'terminal') return current
    const observedAt = new Date().toISOString()
    const nextAttention: TaskAttention = {
      status,
      reason_code: reasonCode,
      observed_at: observedAt,
      ...(activityEvidence ? { activity_evidence: activityEvidence } : {}),
    }
    const attention = (current.attention || []).filter(item => item.status !== status)
    attention.push(nextAttention)
    const updated = await this.store.patch(taskId, {
      attention,
      recovery_required: true,
    })
    const broadcast = this.getBroadcast(taskId)
    broadcast.emit({
      type: 'lifecycle_update',
      task_id: taskId,
      session_id: updated.thread_id,
      subtype: status,
      data: {
        attention,
        available_actions: availableActions(updated),
        termination_operation: updated.termination_operation,
      },
    })
    await broadcast.flush()
    return updated
  }

  private async reconcileCommitted(record: StoredTaskRecord, broadcast: EventBroadcast): Promise<void> {
    if (await this.finalizeDurableTerminalEvent(record, broadcast)) return
    record = this.store.get(record.task_id) || record
    let result: Awaited<ReturnType<NonNullable<TaskExecutor['reconcile']>>>
    const reconcileController = new AbortController()
    const reconcileTimeout = setTimeout(() => {
      reconcileController.abort(new Error('APP_SERVER_RECONCILIATION_TIMEOUT'))
    }, this.config.poolAcquireTimeoutMs + this.config.abortWaitTimeoutMs + 5_000)
    reconcileTimeout.unref()
    try {
      result = await this.executor.reconcile!({
        taskId: record.task_id,
        request: this.store.getRequest(record.task_id),
        record,
        signal: reconcileController.signal,
      })
    } catch {
      result = { status: 'unknown', threadId: record.thread_id || '' }
    } finally {
      clearTimeout(reconcileTimeout)
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
      await this.transitionTerminal(record.task_id, { ...common, outcome: 'completed' })
    } else if (result.status === 'unknown') {
      await this.store.patch(record.task_id, { ...common, recovery_required: true })
      await this.markAttentionLocked(
        record.task_id,
        'PROCESS_UNVERIFIED',
        record.abort_requested_at ? 'CANCEL_RECONCILIATION_UNCONFIRMED' : 'APP_SERVER_RECOVERY_UNKNOWN',
      )
      return
    } else {
      const code = result.status === 'interrupted'
        ? 'TASK_ABORTED'
        : result.status === 'failed'
          ? result.errorCode || 'APP_SERVER_TURN_FAILED'
          : 'APP_SERVER_RECOVERY_UNKNOWN'
      this.emitError(
        broadcast,
        record.task_id,
        common.thread_id,
        code,
        result.status === 'interrupted' ? 'ABORTED' : 'FAILED',
        'PROVIDER_TERMINAL_EVENT',
      )
      await broadcast.flush()
      await this.transitionTerminal(record.task_id, {
        ...common,
        outcome: result.status === 'interrupted' ? 'aborted' : 'failed',
        error_code: code,
      })
    }
    if (this.store.get(record.task_id)?.status === 'terminal') {
      this.releaseThreadReservations(record.task_id)
    }
    if (this.store.get(record.task_id)?.status === 'terminal') {
      await this.retireBroadcast(record.task_id, broadcast)
    }
  }

  private async finalizeDurableTerminalEvent(
    record: StoredTaskRecord,
    broadcast: EventBroadcast,
    events = broadcast.getEventsAfter(0),
  ): Promise<boolean> {
    // An error event is normally the durable precursor of a terminal state
    // transition.  That is deliberately not true for an uncertain runtime:
    // those errors are diagnostics, followed by a durable attention marker.
    // Never turn that diagnostic into a synthetic provider exit during restart
    // or recovery; only an observed result/terminal error may release the task.
    const pendingDecisionCodes = new Set((record.attention || []).map(item => item.reason_code))
    const terminal = [...events].reverse().find(event => (
      event.type === 'result'
      || (event.type === 'error'
        && event.terminal_observed === true
        && (event.terminal_status === 'FAILED' || event.terminal_status === 'ABORTED')
        && !pendingDecisionCodes.has(event.subtype || event.error_code || event.error || ''))
    ))
    if (!terminal) return false
    const patch = terminal.type === 'result'
      ? { outcome: 'completed' as const }
      : {
          outcome: terminal.terminal_status === 'ABORTED' ? 'aborted' as const : 'failed' as const,
          error_code: terminal.subtype,
        }
    await this.transitionTerminal(record.task_id, patch)
    this.releaseThreadReservations(record.task_id)
    await this.retireBroadcast(record.task_id, broadcast)
    return true
  }

  private async finalizeLocalAbort(
    record: StoredTaskRecord,
    broadcast: EventBroadcast,
  ): Promise<StoredTaskRecord> {
    this.emitError(
      broadcast,
      record.task_id,
      record.thread_id,
      'TASK_ABORTED',
      'ABORTED',
      'NO_EXECUTION_CONFIRMED',
    )
    await broadcast.flush()
    const terminal = await this.transitionTerminal(record.task_id, {
      outcome: 'aborted',
      error_code: 'TASK_ABORTED',
    })
    this.releaseThreadReservations(record.task_id)
    await this.retireBroadcast(record.task_id, broadcast)
    return terminal
  }

  private async transitionTerminal(
    taskId: string,
    patch: TerminalTransitionPatch,
  ): Promise<StoredTaskRecord> {
    const current = this.store.get(taskId)
    const operation = current?.termination_operation
      ? {
          ...current.termination_operation,
          status: 'OBSERVED_EXIT' as const,
          result_code: patch.error_code || patch.outcome?.toUpperCase() || 'OBSERVED_EXIT',
          observed_exit_at: new Date().toISOString(),
        }
      : undefined
    return this.store.transition(taskId, 'terminal', {
      ...patch,
      // An observed terminal event resolves previous lifecycle uncertainty.
      // A terminal task must never continue to advertise a stale attention
      // marker or recovery requirement to clients.
      attention: [],
      recovery_required: false,
      ...(operation ? { termination_operation: operation } : {}),
    })
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

  private async recordTerminationResult(taskId: string, resultCode: string): Promise<void> {
    await this.withTaskOperation(taskId, () => this.recordTerminationResultLocked(taskId, resultCode))
  }

  private async recordTerminationResultLocked(taskId: string, resultCode: string): Promise<void> {
    const operation = this.store.get(taskId)?.termination_operation
    if (!operation || operation.result_code === resultCode) return
    await this.store.patch(taskId, {
      termination_operation: { ...operation, result_code: resultCode },
    })
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
    status: record.pending_interaction && record.status !== 'terminal' ? 'awaiting_input' : record.status,
    lifecycle_status: lifecycleStatus(record),
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
    attention: record.attention || [],
    available_actions: availableActions(record),
    termination_operation: record.termination_operation,
    tombstoned: Boolean(record.tombstoned_at),
    tombstoned_at: record.tombstoned_at,
    pending_interaction: record.pending_interaction,
  })
}

function lifecycleStatus(record: StoredTaskRecord): string {
  if (record.status === 'terminal') return record.outcome?.toUpperCase() || 'TERMINAL'
  if (record.abort_requested_at) return 'CANCEL_REQUESTED'
  if (record.status === 'accepted' || record.status === 'starting') return 'ACCEPTED'
  return 'RUNNING'
}

function availableActions(record: StoredTaskRecord): string[] {
  if (record.status === 'terminal') return []
  const actions = ['CONTINUE_WAIT', 'QUERY_DIAGNOSTICS']
  if (!record.abort_requested_at) actions.push('CANCEL')
  return actions
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
    'APP_SERVER_POOL_SINGLE_INSTANCE_LANE_MISMATCH',
    'APP_SERVER_TURN_STALLED',
    'APP_SERVER_UNEXPECTED_IMAGE_GENERATION',
    'APP_SERVER_ABORT_UNCONFIRMED',
    'APP_SERVER_PROCESS_UNVERIFIED',
    'APP_SERVER_PROCESS_IDENTITY_MISMATCH',
    'APP_SERVER_TOOL_CAPABILITY_UNAVAILABLE',
    'APP_SERVER_PROCESS_TREE_UNSAFE',
    'WORKING_DIRECTORY_NOT_ALLOWED',
    'CODEX_HOME_MISSING',
    'CODEX_HOME_NOT_ISOLATED',
    'UNSUPPORTED_CODEX_MODEL',
    'UNSUPPORTED_CODEX_CONFIG_KEY',
    'INVALID_CODEX_CONFIG_VALUE',
  ].includes(code)
    ? code
    : 'APP_SERVER_RUNTIME_FAILED'
}

function isDeterministicNoExecutionFailure(error: unknown): boolean {
  return error instanceof Error
    && 'code' in error
    && error.code === 'APP_SERVER_POOL_SINGLE_INSTANCE_LANE_MISMATCH'
}
