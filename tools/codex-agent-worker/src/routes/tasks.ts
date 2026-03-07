import { Router, Request, Response } from 'express'
import { taskRegistry, taskBroadcasts, abortTask, getTaskStatus } from '../codex/sdk-wrapper.js'
import { EventBroadcast } from '../persistence/event-store.js'
import type { WorkerEvent } from '../models.js'

const router = Router()

/**
 * GET /api/v1/tasks/:taskId/subscribe — Reconnect to existing task's SSE stream
 * Supports ESN-based replay via ?ack_seq=N
 */
router.get('/api/v1/tasks/:taskId/subscribe', (req: Request, res: Response) => {
  const { taskId } = req.params
  const ackSeq = parseInt(req.query.ack_seq as string) || 0

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

  // Replay missed events
  const missedEvents = broadcast.getEventsAfter(ackSeq)
  for (const event of missedEvents) {
    try {
      const data = JSON.stringify(event)
      res.write(`event: message\ndata: ${data}\n\n`)
    } catch {
      break
    }
  }

  // If already closed, end the stream
  if (broadcast.isClosed()) {
    res.end()
    return
  }

  // Subscribe to future events
  const unsubscribe = broadcast.subscribe((event: WorkerEvent) => {
    try {
      const data = JSON.stringify(event)
      res.write(`event: message\ndata: ${data}\n\n`)
    } catch {
      unsubscribe()
    }
  })

  req.on('close', () => {
    unsubscribe()
  })
})

/**
 * GET /api/v1/tasks/:taskId/status — Get task status
 */
router.get('/api/v1/tasks/:taskId/status', (req: Request, res: Response) => {
  const { taskId } = req.params
  const entry = getTaskStatus(taskId)

  if (!entry) {
    res.status(404).json({ error: `Task not found: ${taskId}` })
    return
  }

  res.json({
    task_id: entry.taskId,
    status: entry.status,
    thread_id: entry.threadId,
    started_at: new Date(entry.startedAt).toISOString(),
    completed_at: entry.completedAt ? new Date(entry.completedAt).toISOString() : null,
    duration_ms: entry.completedAt ? entry.completedAt - entry.startedAt : Date.now() - entry.startedAt,
  })
})

/**
 * POST /api/v1/tasks/:taskId/abort — Abort a running task
 */
router.post('/api/v1/tasks/:taskId/abort', (req: Request, res: Response) => {
  const { taskId } = req.params
  const success = abortTask(taskId)

  if (!success) {
    res.status(404).json({ error: `Task not found or not running: ${taskId}` })
    return
  }

  res.json({ task_id: taskId, status: 'aborted' })
})

export default router
