import { Router, Request, Response } from 'express'
import { config } from '../config.js'
import {
  taskBroadcasts,
  confirmTaskProcessExit,
  getTaskStatus,
  requestTaskCancellation,
} from '../codex/sdk-wrapper.js'
import { EventBroadcast } from '../persistence/event-store.js'
import { toTaskLifecycleState, type WorkerEvent } from '../models.js'
import { listCodexCliProcesses } from '../codex/processes.js'
import {
  TerminationOperationReceiptLedger,
  TerminationOperationValidationError,
  toTerminationOperationSummary,
  validateTerminationOperation,
} from '../termination-operation.js'

const SSE_HEARTBEAT_INTERVAL_MS = 15_000

export type TasksRouterDependencies = {
  terminationReplayLedger?: TerminationOperationReceiptLedger
  listProcesses?: typeof listCodexCliProcesses
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
 * POST /api/v1/tasks/:taskId/abort — Request an explicitly authorized cancel.
 * The ACK is non-terminal: the task remains CANCEL_REQUESTED until a provider
 * terminal event or verified process exit is observed.
 */
router.post('/api/v1/tasks/:taskId/abort', (req: Request, res: Response) => {
  const taskId = getSingleParam(req.params.taskId)
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
      },
    )
  } catch (error) {
    if (sendTerminationOperationError(res, error)) return
    throw error
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

  res.status(202).json({
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
