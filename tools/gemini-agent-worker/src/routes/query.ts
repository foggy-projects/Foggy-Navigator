import { Router, Request, Response } from 'express'
import { v4 as uuidv4 } from 'uuid'
import { config } from '../config.js'
import { runQuery, taskBroadcasts, cleanupOldTasks, getRunningTaskCount } from '../gemini/cli-wrapper.js'
import { resolveGeminiModelAlias } from '../gemini/model-alias.js'
import type { WorkerEvent } from '../models.js'
import { ensureProjectGeminiContext } from '../startup/skills-link.js'
import { startSse, writeSseEvent, writeWorkerEvents } from './sse.js'
import { validateQueryRequest } from '../validation/query.js'

const router = Router()

router.post('/api/v1/query', async (req: Request, res: Response) => {
  const validation = validateQueryRequest(req.body)
  if (!validation.ok) {
    res.status(400).json({ error: validation.error })
    return
  }
  const body = validation.value

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

  if (cwd) {
    try {
      await ensureProjectGeminiContext(cwd)
    } catch (error) {
      console.warn(`Failed to initialize Gemini project context for ${cwd}:`, error)
    }
  }

  const runningTasks = getRunningTaskCount()
  if (runningTasks >= config.maxConcurrentTasks) {
    res.status(429).json({
      error: `Too many concurrent Gemini tasks: ${runningTasks}/${config.maxConcurrentTasks}`,
      running_tasks: runningTasks,
      max_concurrent_tasks: config.maxConcurrentTasks,
    })
    return
  }

  const taskId = uuidv4()
  cleanupOldTasks()
  const requestedModel = body.model_alias || body.model
  const resolvedModel = resolveGeminiModelAlias(requestedModel)

  startSse(res)

  // runQuery registers the broadcast synchronously (before its first await), so the entry is
  // visible in taskBroadcasts as soon as the call returns the pending promise — no sleep needed.
  const queryPromise = runQuery(
    taskId,
    body.prompt,
    cwd,
    body.session_id,
    resolvedModel,
    body.max_turns,
    body.images,
    body.api_key,
    body.base_url,
    body.env_vars,
    body.skip_trust !== false
  )

  const broadcast = taskBroadcasts.get(taskId)
  if (!broadcast) {
    writeSseEvent(res, {
      type: 'error',
      task_id: taskId,
      error: 'Failed to initialize task broadcast',
      seq: 1,
    })
    res.end()
    return
  }

  const unsubscribe = broadcast.subscribe((event: WorkerEvent) => {
    writeSseEvent(res, event)
  })

  writeWorkerEvents(res, broadcast.getEventsAfter(0))

  req.on('close', () => {
    unsubscribe()
  })

  queryPromise.finally(() => {
    unsubscribe()
    try {
      res.end()
    } catch {
      // ignore
    }
  })
})

export default router
