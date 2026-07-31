import { Router, Request, Response } from 'express'
import { config } from '../config.js'
import {
  taskBroadcasts,
  confirmTaskProcessExit,
  getTaskStatus,
  requestTaskCancellation,
} from '../codex/sdk-wrapper.js'
import { EventBroadcast } from '../persistence/event-store.js'
import {
  completionReceiptStore,
  type CompletionReceiptStore,
} from '../persistence/completion-receipt.js'
import {
  isTaskExecutionActive,
  isTaskTerminal,
  toTaskLifecycleState,
  type WorkerEvent,
} from '../models.js'
import {
  canonicalizeCodexCliProcessStartedAt,
  listCodexCliProcesses,
} from '../codex/processes.js'
import {
  TerminationOperationReceiptLedger,
  TerminationOperationValidationError,
  toTerminationOperationSummary,
  validateTerminationOperation,
} from '../termination-operation.js'
import { preflightLifecycleCommand } from '../lifecycle/command.js'
import { LIFECYCLE_SCHEMA } from '../lifecycle/store.js'

const SSE_HEARTBEAT_INTERVAL_MS = 15_000

export type TasksRouterDependencies = {
  terminationReplayLedger?: TerminationOperationReceiptLedger
  listProcesses?: typeof listCodexCliProcesses
  completionReceiptStore?: CompletionReceiptStore
}

function startSseHeartbeat(res: Response): () => void {
  const timer = setInterval(() => {
    if (!res.writableEnded && !res.destroyed) {
      res.write(': keepalive\n\n')
    }
  }, SSE_HEARTBEAT_INTERVAL_MS)
  timer.unref()
  return () => clearInterval(timer)
}

function getSingleParam(value: string | string[] | undefined): string {
  if (Array.isArray(value)) return value[0] || ''
  return value || ''
}

function getSingleQuery(value: string | string[] | undefined): string | undefined {
  if (Array.isArray(value)) return value[0]
  return value
}

function taskStatusPayload(entry: NonNullable<ReturnType<typeof getTaskStatus>>) {
  return {
    task_id: entry.taskId,
    status: entry.status,
    lifecycle_state: toTaskLifecycleState(entry.status),
    thread_id: entry.threadId,
    pid: entry.pid ?? null,
    started_at: new Date(entry.startedAt).toISOString(),
    completed_at: entry.completedAt ? new Date(entry.completedAt).toISOString() : null,
    duration_ms: entry.completedAt ? entry.completedAt - entry.startedAt : Date.now() - entry.startedAt,
    attention: entry.attention ?? [],
    attention_status: entry.attention?.at(-1)?.code,
    available_actions: entry.availableActions ?? [],
    termination_operation: entry.terminationOperation,
  }
}

function sendTerminationOperationError(res: Response, error: unknown): boolean {
  if (!(error instanceof TerminationOperationValidationError)) return false
  res.status(error.statusCode).json({ error: error.code, code: error.code })
  return true
}

export function createTasksRouter(dependencies: TasksRouterDependencies = {}): Router {
  const router = Router()
  const listProcesses = dependencies.listProcesses ?? listCodexCliProcesses
  const terminationReplayLedger = dependencies.terminationReplayLedger
    ?? new TerminationOperationReceiptLedger(config.terminationOperationLedgerDir)
  const receipts = dependencies.completionReceiptStore ?? completionReceiptStore

type ReconciliationProof = {
  originalOperation: NonNullable<WorkerEvent['termination_operation']>
  terminalEvent?: WorkerEvent
  processCount: number
}

function findLastEvent(
  events: WorkerEvent[],
  predicate: (event: WorkerEvent) => boolean,
): WorkerEvent | undefined {
  for (let index = events.length - 1; index >= 0; index -= 1) {
    if (predicate(events[index])) return events[index]
  }
  return undefined
}

async function inspectReconciliationEvidence(
  taskId: string,
  originalOperationId: string,
): Promise<ReconciliationProof> {
  if (!originalOperationId || !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(originalOperationId)) {
    throw new TerminationOperationValidationError('TERMINATION_RECONCILIATION_OPERATION_INVALID', 400)
  }
  if (!terminationReplayLedger.hasConsumed(config.navigatorWorkerId, originalOperationId)) {
    throw new TerminationOperationValidationError(
      'TERMINATION_RECONCILIATION_ORIGINAL_RECEIPT_MISSING',
      409,
    )
  }

  const diskBroadcast = new EventBroadcast(taskId)
  const events = diskBroadcast.loadFromDisk()
  const matchingEvents = events.filter(event => (
    event.task_id === taskId
    && event.termination_operation?.operation_id === originalOperationId
    && event.termination_operation?.task_id === taskId
    && event.termination_operation?.worker_id === config.navigatorWorkerId
    && event.termination_operation?.kind === 'REMOTE_CANCEL'
  ))
  const terminalEvent = findLastEvent(matchingEvents, event => (
    event.subtype === 'lifecycle_terminal'
    && event.terminal_observed === true
    && event.terminal_status === 'ABORTED'
  ))
  const unconfirmedEvent = findLastEvent(matchingEvents, event => (
    event.subtype === 'termination_unconfirmed'
    && event.lifecycle_state === 'CANCEL_REQUESTED'
    && event.termination_operation?.status === 'UNCONFIRMED'
  ))
  const originalOperation = terminalEvent?.termination_operation ?? unconfirmedEvent?.termination_operation
  if (!originalOperation) {
    throw new TerminationOperationValidationError(
      'TERMINATION_RECONCILIATION_ORIGINAL_EVENT_MISSING',
      409,
    )
  }

  let processes
  try {
    processes = await listProcesses()
  } catch {
    throw new TerminationOperationValidationError(
      'TERMINATION_RECONCILIATION_PROCESS_SCAN_UNAVAILABLE',
      503,
    )
  }
  if (processes.length !== 0) {
    throw new TerminationOperationValidationError(
      'TERMINATION_RECONCILIATION_WORKER_PROCESS_PRESENT',
      409,
    )
  }
  return { originalOperation, terminalEvent, processCount: processes.length }
}

function reconciliationPayload(
  taskId: string,
  proof: ReconciliationProof,
  reconciliationOperation?: ReturnType<typeof toTerminationOperationSummary>,
) {
  const observedAt = proof.terminalEvent?.occurred_at ?? new Date().toISOString()
  const originalOperation = {
    ...proof.originalOperation,
    status: 'OBSERVED_EXIT' as const,
    observed_at: observedAt,
    result: 'WORKER_WIDE_ZERO_PROCESS_RECONCILED',
  }
  return {
    task_id: taskId,
    worker_id: config.navigatorWorkerId,
    status: 'aborted',
    lifecycle_state: 'ABORTED',
    terminal_observed: true,
    terminal_status: 'ABORTED',
    terminal_source: 'WORKER_WIDE_ZERO_PROCESS_RECONCILIATION',
    provider_status: 'WORKER_WIDE_ZERO_PROCESS_RECONCILED',
    termination_operation: originalOperation,
    reconciliation_operation: reconciliationOperation,
    process_snapshot: {
      total: proof.processCount,
    },
  }
}

/**
 * GET /api/v1/tasks/:taskId/subscribe — Reconnect to existing task's SSE stream
 * Supports ESN-based replay via ?ack_seq=N
 */
router.get('/api/v1/tasks/:taskId/subscribe', (req: Request, res: Response) => {
  const taskId = getSingleParam(req.params.taskId)
  const ackSeq = parseInt(getSingleQuery(req.query.ack_seq as string | string[] | undefined) || '', 10) || 0

  const broadcast = taskBroadcasts.get(taskId)
  if (!broadcast) {
    // Try loading from disk
    const diskBroadcast = new EventBroadcast(taskId)
    const events = diskBroadcast.loadFromDisk()

    if (events.length === 0) {
      res.status(404).json({ error: `Task not found: ${taskId}` })
      return
    }

    // Set SSE headers
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
      'X-Accel-Buffering': 'no',
    })

    // Replay events from disk
    const replayEvents = events.filter(e => (e.seq || 0) > ackSeq)
    for (const event of replayEvents) {
      const data = JSON.stringify(event)
      res.write(`event: message\ndata: ${data}\n\n`)
    }

    res.end()
    return
  }

  // Set SSE headers
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    'Connection': 'keep-alive',
    'X-Accel-Buffering': 'no',
  })

  // First, emit sync_checkpoint
  const syncCheckpoint: WorkerEvent = {
    type: 'assistant_text',
    task_id: taskId,
    subtype: 'sync_checkpoint',
    content: '',
    seq: 0,
  }
  // Add latest_seq and event_count as extra data
  const checkpointData = {
    ...syncCheckpoint,
    latest_seq: broadcast.getLatestSeq(),
    event_count: broadcast.getEventCount(),
  }
  res.write(`event: message\ndata: ${JSON.stringify(checkpointData)}\n\n`)

  let lastSentSeq = ackSeq
  const writeEvent = (event: WorkerEvent) => {
    const seq = event.seq || 0
    if (seq > 0 && seq <= lastSentSeq) return
    try {
      const data = JSON.stringify(event)
      res.write(`event: message\ndata: ${data}\n\n`)
      if (seq > lastSentSeq) lastSentSeq = seq
    } catch {
      // Client disconnected.
    }
  }

  // Replay missed events
  for (const event of broadcast.getEventsAfter(ackSeq)) writeEvent(event)

  // If already closed, end the stream
  if (broadcast.isClosed()) {
    res.end()
    return
  }

  // Subscribe to future events
  const stopHeartbeat = startSseHeartbeat(res)
  let unsubscribe: () => void = () => undefined
  const closeStream = () => {
    unsubscribe()
    stopHeartbeat()
    if (!res.writableEnded) res.end()
  }
  unsubscribe = broadcast.subscribe(writeEvent, closeStream)
  for (const event of broadcast.getEventsAfter(lastSentSeq)) writeEvent(event)

  req.on('close', () => {
    unsubscribe()
    stopHeartbeat()
  })
})

/**
 * GET /api/v1/tasks/:taskId/status — Get task status
 */
router.get('/api/v1/tasks/:taskId/status', (req: Request, res: Response) => {
  const taskId = getSingleParam(req.params.taskId)
  const entry = getTaskStatus(taskId)

  if (!entry) {
    res.status(404).json({ error: `Task not found: ${taskId}` })
    return
  }

  res.json(taskStatusPayload(entry))
})

/**
 * GET /api/v1/tasks/:taskId/completion-readiness
 *
 * Content-free, read-only provider observation. This endpoint never reads the
 * legacy event JSONL or the durable result object.
 */
router.get('/api/v1/tasks/:taskId/completion-readiness', async (req: Request, res: Response) => {
  const taskId = getSingleParam(req.params.taskId)
  const observedAt = new Date().toISOString()
  const entry = getTaskStatus(taskId)

  let receipt = null
  try {
    receipt = await receipts.inspect(taskId)
  } catch {
    res.status(503).json({
      code: 'COMPLETION_RECEIPT_UNAVAILABLE',
      error: 'COMPLETION_RECEIPT_UNAVAILABLE',
    })
    return
  }

  let processes
  try {
    processes = await listProcesses()
  } catch {
    processes = null
  }

  let providerProcessPresent: boolean | null = null
  let providerProcessState: 'PRESENT' | 'ABSENT' | 'UNKNOWN' = 'UNKNOWN'
  if (processes) {
    if (entry?.pid !== undefined) {
      const exact = processes.some(processInfo => (
        processInfo.pid === entry.pid
        && (!entry.processStartedAt
          || canonicalizeCodexCliProcessStartedAt(processInfo.started_at) === entry.processStartedAt)
      ))
      providerProcessPresent = exact
      providerProcessState = exact ? 'PRESENT' : 'ABSENT'
    } else if (processes.length === 0) {
      providerProcessPresent = false
      providerProcessState = 'ABSENT'
    }
  }

  const workerTaskState = entry ? toTaskLifecycleState(entry.status) : 'UNKNOWN'
  const providerTaskTerminal = receipt
    ? true
    : entry
      ? isTaskTerminal(entry.status)
      : null
  const providerTerminalStatus = receipt
    ? receipt.terminal_status
    : entry && isTaskTerminal(entry.status)
      ? toTaskLifecycleState(entry.status)
      : null

  res.json({
    task_id: taskId,
    worker_id: config.navigatorWorkerId || null,
    worker_observed_at: observedAt,
    worker_task_known: Boolean(entry),
    worker_task_state: workerTaskState,
    provider_process_present: providerProcessPresent,
    provider_process_state: providerProcessState,
    provider_active_task_present: entry ? isTaskExecutionActive(entry.status) : null,
    provider_task_terminal: providerTaskTerminal,
    provider_terminal_status: providerTerminalStatus,
    last_heartbeat_at: null,
    last_progress_at: entry?.lastProgressAt
      ? new Date(entry.lastProgressAt).toISOString()
      : null,
    process_exited_at: null,
    final_output_present: receipt?.final_output_present ?? null,
    final_output_durable: receipt?.final_output_durable ?? null,
    final_output_digest: receipt?.final_output_digest ?? null,
    final_output_recorded_at: receipt?.recorded_at ?? null,
    structured_output_present: receipt?.structured_output_present ?? null,
    structured_output_digest: receipt?.structured_output_digest ?? null,
    completion_signal_present: receipt?.completion_signal_present ?? null,
    completion_signal_source: receipt?.completion_signal_source ?? null,
    completion_signal_recorded_at: receipt?.completion_signal_recorded_at ?? null,
    result_recoverable: receipt?.result_recoverable ?? null,
    completion_evidence_schema: receipt?.schema ?? null,
    provider_task_id: receipt?.provider_task_id ?? taskId,
    dispatch_count: receipt?.dispatch_count ?? null,
  })
})

/**
 * POST /api/v1/tasks/:taskId/abort — Request an explicitly authorized cancel.
 * The ACK is non-terminal: the task remains CANCEL_REQUESTED until a provider
 * terminal event or verified process exit is observed.
 */
router.post('/api/v1/tasks/:taskId/abort', (req: Request, res: Response) => {
  const taskId = getSingleParam(req.params.taskId)
  const encodedOperation = Array.isArray(req.headers['x-navigator-termination-operation'])
    ? req.headers['x-navigator-termination-operation'][0] ?? null
    : req.headers['x-navigator-termination-operation'] ?? null
  const lifecycle = preflightLifecycleCommand(
    req,
    res,
    ['TERMINATION_CANCEL'],
    '/api/v1/tasks/{providerTaskId}/abort',
    taskId,
    encodedOperation,
  )
  if (lifecycle === undefined) return
  const entry = getTaskStatus(taskId)
  if (!entry) {
    res.status(404).json({ error: `Task not found: ${taskId}` })
    return
  }

  let claims
  try {
    claims = validateTerminationOperation(
      req.headers['x-navigator-termination-operation'],
      req.headers['x-navigator-termination-signature'],
      {
        workerToken: config.workerToken,
        expectedWorkerId: config.navigatorWorkerId,
        expectedKind: 'REMOTE_CANCEL',
        expectedTaskId: taskId,
        replayLedger: terminationReplayLedger,
        consumeReplayReceipt: lifecycle?.context.ownership_mode !== 'ENFORCED',
      },
    )
  } catch (error) {
    if (sendTerminationOperationError(res, error)) return
    throw error
  }
  if (lifecycle
      && claims.operation_id !== lifecycle.context.termination_operation_id) {
    res.status(409).json({
      schema: LIFECYCLE_SCHEMA,
      code: 'TERMINATION_OPERATION_REPLAY_DETECTED',
    })
    return
  }

  let lifecycleDisposition
  if (lifecycle) {
    try {
      lifecycleDisposition = lifecycle.store.prepareAcceptedTerminationDispatch(
        lifecycle.context,
        lifecycle.binding,
        taskId,
      )
    } catch (error) {
      const code = error instanceof Error
        ? error.message
        : 'WORKER_LIFECYCLE_STORE_UNAVAILABLE'
      res.status(code === 'WORKER_LIFECYCLE_STORE_UNAVAILABLE' ? 503 : 409).json({
        schema: LIFECYCLE_SCHEMA,
        code,
      })
      return
    }
    if (lifecycleDisposition.duplicate
        && lifecycle.context.ownership_mode === 'ENFORCED'
        && lifecycleDisposition.effect_phase !== 'PREPARED') {
      res.status(202).json({
        ...lifecycleDisposition,
        ...taskStatusPayload(entry),
      })
      return
    }
    if (lifecycle.context.ownership_mode === 'ENFORCED') {
      try {
        lifecycleDisposition = lifecycle.store.markEffectStarted(
          lifecycle.context.dispatch_id,
        )
      } catch {
        res.status(503).json({
          schema: LIFECYCLE_SCHEMA,
          code: 'WORKER_LIFECYCLE_STORE_UNAVAILABLE',
        })
        return
      }
    }
  }

  const operation = toTerminationOperationSummary(claims, 'CANCEL_REQUESTED')
  const requested = requestTaskCancellation(taskId, operation)

  if (!requested) {
    res.status(409).json({
      error: 'TASK_CANCELLATION_NOT_ACCEPTED',
      code: 'TASK_CANCELLATION_NOT_ACCEPTED',
      ...taskStatusPayload(entry),
    })
    return
  }

  if (lifecycleDisposition
      && lifecycle?.context.ownership_mode === 'ENFORCED'
      && isTaskTerminal(requested.status)) {
    lifecycleDisposition = lifecycle.store.markResultObserved(
      lifecycle.context.dispatch_id,
      'TASK_PROVIDER_TERMINAL_OBSERVED',
      requested.status === 'aborted' ? 'CANCELLED'
        : requested.status === 'completed' ? 'COMPLETED' : 'FAILED',
      'TERMINATION_PROVIDER_RESULT_OBSERVED',
    )
  }

  res.status(202).json({
    ...(lifecycleDisposition ?? {}),
    ...taskStatusPayload(requested),
    status: 'cancel_requested',
    lifecycle_state: 'CANCEL_REQUESTED',
    termination_operation: operation,
  })
})

router.get('/api/v1/tasks/:taskId/termination-reconciliation-readiness', async (req: Request, res: Response) => {
  const taskId = getSingleParam(req.params.taskId)
  const originalOperationId = getSingleQuery(
    req.query.original_operation_id as string | string[] | undefined,
  ) ?? ''
  try {
    const proof = await inspectReconciliationEvidence(taskId, originalOperationId)
    res.json({
      ...reconciliationPayload(taskId, proof),
      dry_run: true,
      reconciliation_allowed: true,
    })
  } catch (error) {
    if (sendTerminationOperationError(res, error)) return
    throw error
  }
})

router.post('/api/v1/tasks/:taskId/termination-reconcile', async (req: Request, res: Response) => {
  const taskId = getSingleParam(req.params.taskId)
  if (req.body?.lifecycle_context !== undefined) {
    const lifecycle = preflightLifecycleCommand(
      req,
      res,
      ['TERMINATION_CANCEL'],
      '/api/v1/tasks/{providerTaskId}/termination-reconcile',
      taskId,
      null,
    )
    if (lifecycle === undefined) return
    res.status(409).json({
      schema: LIFECYCLE_SCHEMA,
      code: 'LIFECYCLE_COMMAND_NOT_APPLICABLE',
    })
    return
  }
  const originalOperationId = typeof req.body?.original_operation_id === 'string'
    ? req.body.original_operation_id
    : ''
  try {
    const proof = await inspectReconciliationEvidence(taskId, originalOperationId)
    const claims = validateTerminationOperation(
      req.headers['x-navigator-termination-operation'],
      req.headers['x-navigator-termination-signature'],
      {
        workerToken: config.workerToken,
        expectedWorkerId: config.navigatorWorkerId,
        expectedKind: 'RECONCILE_CANCEL',
        expectedTaskId: taskId,
        replayLedger: terminationReplayLedger,
      },
    )
    const reconciliationOperation = toTerminationOperationSummary(
      claims,
      'OBSERVED_EXIT',
      'WORKER_WIDE_ZERO_PROCESS_RECONCILED',
    )
    if (!proof.terminalEvent) {
      const inMemory = confirmTaskProcessExit(
        taskId,
        originalOperationId,
        'WORKER_WIDE_ZERO_PROCESS_RECONCILED',
      )
      if (inMemory) {
        await taskBroadcasts.get(taskId)?.flush()
      } else {
        const diskBroadcast = new EventBroadcast(taskId)
        diskBroadcast.loadFromDisk()
        const occurredAt = new Date().toISOString()
        const terminalEvent: WorkerEvent = {
          type: 'error',
          task_id: taskId,
          subtype: 'lifecycle_terminal',
          error_code: 'CODEX_TURN_CANCELLED',
          error_category: 'CANCELLED',
          runtime_phase: 'TASK_RECONCILIATION',
          recoverable: false,
          occurred_at: occurredAt,
          lifecycle_state: 'ABORTED',
          terminal_observed: true,
          terminal_status: 'ABORTED',
          terminal_source: 'WORKER_WIDE_ZERO_PROCESS_RECONCILIATION',
          provider_status: 'WORKER_WIDE_ZERO_PROCESS_RECONCILED',
          termination_operation: {
            ...proof.originalOperation,
            status: 'OBSERVED_EXIT',
            observed_at: occurredAt,
            result: 'WORKER_WIDE_ZERO_PROCESS_RECONCILED',
          },
          seq: diskBroadcast.nextSeq(),
        }
        await diskBroadcast.emitDurably(terminalEvent)
        proof.terminalEvent = terminalEvent
      }
    }
    res.json(reconciliationPayload(taskId, proof, reconciliationOperation))
  } catch (error) {
    if (sendTerminationOperationError(res, error)) return
    throw error
  }
})

  return router
}

export default createTasksRouter()
