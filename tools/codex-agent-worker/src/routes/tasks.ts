import { Router, Request, Response } from 'express'
import { config } from '../config.js'
import {
  taskBroadcasts,
  getTaskStatus,
  requestTaskCancellation,
} from '../codex/sdk-wrapper.js'
import { EventBroadcast } from '../persistence/event-store.js'
import { toTaskLifecycleState, type WorkerEvent } from '../models.js'
import {
  TerminationOperationReceiptLedger,
  TerminationOperationValidationError,
  toTerminationOperationSummary,
  validateTerminationOperation,
} from '../termination-operation.js'

const SSE_HEARTBEAT_INTERVAL_MS = 15_000

export type TasksRouterDependencies = {
  terminationReplayLedger?: TerminationOperationReceiptLedger
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
  const terminationReplayLedger = dependencies.terminationReplayLedger
    ?? new TerminationOperationReceiptLedger(config.terminationOperationLedgerDir)

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

  return router
}

export default createTasksRouter()
