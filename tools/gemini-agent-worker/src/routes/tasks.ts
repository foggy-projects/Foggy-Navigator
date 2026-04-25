import { Router, Request, Response } from 'express'
import { abortTask, getTaskStatus, taskBroadcasts, taskRegistry } from '../gemini/cli-wrapper.js'
import { EventBroadcast } from '../persistence/event-store.js'
import type { WorkerEvent } from '../models.js'
import { getRequiredParam, getSingleValue, startSse, writeSseEvent, writeWorkerEvents } from './sse.js'

const router = Router()

router.get('/api/v1/tasks/:taskId/subscribe', (req: Request, res: Response) => {
  const taskId = getRequiredParam(req.params.taskId)
  const ackSeq = parseInt(getSingleValue(req.query.ack_seq as string | string[] | undefined) || '', 10) || 0

  const broadcast = taskBroadcasts.get(taskId)
  if (!broadcast) {
    const diskBroadcast = new EventBroadcast(taskId)
    const events = diskBroadcast.loadFromDisk()
    if (events.length === 0) {
      res.status(404).json({ error: `Task not found: ${taskId}` })
      return
    }

    startSse(res)
    writeWorkerEvents(res, events.filter(e => (e.seq || 0) > ackSeq))
    res.end()
    return
  }

  startSse(res)

  const syncCheckpoint: WorkerEvent = {
    type: 'assistant_text',
    task_id: taskId,
    subtype: 'sync_checkpoint',
    content: '',
    seq: 0,
  }
  writeSseEvent(res, {
    ...syncCheckpoint,
    latest_seq: broadcast.getLatestSeq(),
    event_count: broadcast.getEventCount(),
  })

  writeWorkerEvents(res, broadcast.getEventsAfter(ackSeq))

  if (broadcast.isClosed()) {
    res.end()
    return
  }

  const unsubscribe = broadcast.subscribe((event: WorkerEvent) => {
    if (!writeSseEvent(res, event)) {
      unsubscribe()
    }
  })

  req.on('close', () => {
    unsubscribe()
  })
})

router.get('/api/v1/tasks/:taskId/status', (req: Request, res: Response) => {
  const taskId = getRequiredParam(req.params.taskId)
  const entry = getTaskStatus(taskId)
  if (!entry) {
    res.status(404).json({ error: `Task not found: ${taskId}` })
    return
  }

  res.json({
    task_id: entry.taskId,
    status: entry.status,
    session_id: entry.sessionId,
    started_at: new Date(entry.startedAt).toISOString(),
    completed_at: entry.completedAt ? new Date(entry.completedAt).toISOString() : null,
    duration_ms: entry.completedAt ? entry.completedAt - entry.startedAt : Date.now() - entry.startedAt,
  })
})

router.post('/api/v1/tasks/:taskId/abort', (req: Request, res: Response) => {
  const taskId = getRequiredParam(req.params.taskId)
  const success = abortTask(taskId)
  if (!success) {
    res.status(404).json({ error: `Task not found or not running: ${taskId}` })
    return
  }
  res.json({ task_id: taskId, status: 'aborted' })
})

router.get('/api/v1/tasks', (_req: Request, res: Response) => {
  res.json(Array.from(taskRegistry.values()))
})

export default router
