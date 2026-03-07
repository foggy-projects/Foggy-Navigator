import { Router, Request, Response } from 'express'
import { v4 as uuidv4 } from 'uuid'
import { config } from '../config.js'
import { runQuery, taskBroadcasts, cleanupOldTasks } from '../codex/sdk-wrapper.js'
import type { QueryRequest, WorkerEvent } from '../models.js'

const router = Router()

/**
 * POST /api/v1/query — Start a Codex query and stream results as SSE
 */
router.post('/api/v1/query', async (req: Request, res: Response) => {
  const body = req.body as QueryRequest

  if (!body.prompt) {
    res.status(400).json({ error: 'prompt is required' })
    return
  }

  // Validate working directory
  const cwd = body.cwd
  if (cwd && config.allowedCwds.length > 0) {
    const allowed = config.allowedCwds.some(acwd =>
      cwd.startsWith(acwd) || cwd.replace(/\\/g, '/').startsWith(acwd.replace(/\\/g, '/'))
    )
    if (!allowed) {
      res.status(403).json({ error: `Working directory not allowed: ${cwd}` })
      return
    }
  }

  const taskId = uuidv4()

  // Clean up old tasks periodically
  cleanupOldTasks()

  // Set SSE headers
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    'Connection': 'keep-alive',
    'X-Accel-Buffering': 'no',
  })

  // Start query in background
  const queryPromise = runQuery(
    taskId,
    body.prompt,
    cwd,
    body.session_id,
    body.model,
    body.max_turns,
    body.api_key
  )

  // Wait a tick for broadcast to be registered
  await new Promise(resolve => setTimeout(resolve, 10))

  const broadcast = taskBroadcasts.get(taskId)
  if (!broadcast) {
    const errorData = JSON.stringify({
      type: 'error',
      task_id: taskId,
      error: 'Failed to initialize task broadcast',
      seq: 1,
    })
    res.write(`event: message\ndata: ${errorData}\n\n`)
    res.end()
    return
  }

  // Subscribe to events and forward as SSE
  const unsubscribe = broadcast.subscribe((event: WorkerEvent) => {
    try {
      const data = JSON.stringify(event)
      res.write(`event: message\ndata: ${data}\n\n`)
    } catch (e) {
      // Client disconnected
    }
  })

  // Also replay any events that were emitted before our subscription
  const missedEvents = broadcast.getEventsAfter(0)
  for (const event of missedEvents) {
    try {
      const data = JSON.stringify(event)
      res.write(`event: message\ndata: ${data}\n\n`)
    } catch {
      break
    }
  }

  // Handle client disconnect
  req.on('close', () => {
    unsubscribe()
  })

  // Wait for query to complete, then close SSE
  queryPromise.finally(() => {
    unsubscribe()
    try {
      res.end()
    } catch {
      // Already closed
    }
  })
})

export default router
