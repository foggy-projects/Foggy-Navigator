import { Router, type Request, type Response } from 'express'
import type { AppConfig } from '../config.js'
import { isAllowedWorkingPath, resolveAllowedWorkingPath, workerPrivatePaths } from '../path-guards.js'
import { IdempotencyConflictError } from '../persistence/task-store.js'
import { resolveRuntimeReadiness } from '../runtime-capabilities.js'
import {
  TaskManager,
  TaskManagerDrainingError,
  TaskQueueFullError,
  TaskThreadActiveError,
  UserInputResponseError,
  toPublicTask,
} from '../task-manager.js'
import { UserInputResponseValidationError } from '../app-server/user-input.js'
import {
  resolveSupportedModelAlias,
  UnsupportedCodexModelError,
} from '../model-resolution.js'
import { validateTaskRequest } from '../validation/task-request.js'
import type { WorkerEvent } from '../models.js'

const IDEMPOTENCY_KEY_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/

export function createTasksRouter(config: AppConfig, manager: TaskManager): Router {
  const router = Router()

  router.post('/api/v1/tasks', async (req, res, next) => {
    try {
      const taskId = readIdempotencyKey(req)
      if (!taskId) {
        res.status(400).json({ error: 'Idempotency-Key header is required and must be a Navigator task id' })
        return
      }
      const validation = validateTaskRequest(req.body)
      if (!validation.ok) {
        res.status(400).json({ error: validation.error })
        return
      }
      try {
        resolveSupportedModelAlias(
          validation.value.model || config.defaultModel,
          config.modelAliases,
        )
      } catch (error) {
        if (error instanceof UnsupportedCodexModelError) {
          res.status(400).json({ error: error.code })
          return
        }
        throw error
      }
      const effectiveCwd = validation.value.cwd || process.cwd()
      const privatePaths = workerPrivatePaths(config)
      const canonicalCwd = resolveAllowedWorkingPath(effectiveCwd, config.allowedCwds, privatePaths)
      validation.value.cwd = canonicalCwd || effectiveCwd
      if (manager.get(taskId)) {
        const accepted = await manager.accept(taskId, validation.value)
        const current = manager.get(taskId) || accepted.record
        res.status(202).json({ task_id: current.task_id, status: toPublicTask(current).status })
        return
      }
      const readiness = resolveRuntimeReadiness(config)
      if (!readiness.ready) {
        res.status(503).json({ error: 'APP_SERVER_RUNTIME_NOT_READY', reasons: readiness.reasons })
        return
      }
      if (!manager.isAccepting()) {
        res.status(503).json({ error: 'APP_SERVER_WORKER_DRAINING' })
        return
      }
      if (!canonicalCwd) {
        res.status(403).json({ error: 'WORKING_DIRECTORY_NOT_ALLOWED' })
        return
      }
      for (const directory of validation.value.additional_directories || []) {
        if (!isAllowedWorkingPath(directory, config.allowedCwds, privatePaths)) {
          res.status(403).json({ error: 'ADDITIONAL_DIRECTORY_NOT_ALLOWED' })
          return
        }
      }
      const accepted = await manager.accept(taskId, validation.value)
      const current = manager.get(taskId) || accepted.record
      res.status(202).json({
        task_id: current.task_id,
        status: accepted.created ? 'accepted' : current.status,
      })
    } catch (error) {
      if (error instanceof IdempotencyConflictError) {
        res.status(409).json({ error: 'IDEMPOTENCY_KEY_CONFLICT', task_id: error.taskId })
        return
      }
      if (error instanceof TaskQueueFullError) {
        res.status(429).json({ error: error.code })
        return
      }
      if (error instanceof TaskThreadActiveError) {
        res.status(409).json({ error: error.code })
        return
      }
      if (error instanceof TaskManagerDrainingError) {
        res.status(503).json({ error: error.code })
        return
      }
      next(error)
    }
  })

  router.get('/api/v1/tasks/:taskId/status', (req, res) => {
    const record = manager.get(single(req.params.taskId))
    if (!record) {
      res.status(404).json({ error: 'TASK_NOT_FOUND' })
      return
    }
    res.json(toPublicTask(record))
  })

  router.get('/api/v1/tasks/:taskId/subscribe', (req, res) => {
    subscribe(manager, req, res)
  })

  router.post('/api/v1/tasks/:taskId/respond', async (req, res, next) => {
    try {
      const taskId = single(req.params.taskId)
      if (!manager.get(taskId)) {
        res.status(404).json({ error: 'TASK_NOT_FOUND' })
        return
      }
      const body = validateUserInputResponseBody(req.body)
      if (!body) {
        res.status(400).json({ error: 'INVALID_USER_INPUT_RESPONSE' })
        return
      }
      const record = await manager.respondToUserInput(taskId, body)
      res.status(200).json({
        task_id: taskId,
        status: toPublicTask(record).status,
        request_id: body.request_id,
      })
    } catch (error) {
      if (error instanceof UserInputResponseValidationError) {
        res.status(400).json({ error: error.code })
        return
      }
      if (error instanceof UserInputResponseError) {
        res.status(409).json({ error: error.code })
        return
      }
      next(error)
    }
  })

  router.post('/api/v1/tasks/:taskId/abort', async (req, res, next) => {
    try {
      const taskId = single(req.params.taskId)
      const aborted = await manager.abort(taskId)
      if (!aborted) {
        res.status(404).json({ error: 'TASK_NOT_FOUND' })
        return
      }
      if (aborted.abort_status === 'abort_pending') {
        res.status(409).json({
          error: 'ABORT_PENDING',
          task_id: taskId,
          status: aborted.record.status,
          abort_status: aborted.abort_status,
        })
        return
      }
      if (aborted.abort_status === 'already_terminal') {
        res.status(200).json({
          task_id: taskId,
          status: aborted.record.status,
          outcome: aborted.record.outcome,
          abort_status: aborted.abort_status,
        })
        return
      }
      res.status(200).json({
        task_id: taskId,
        status: aborted.record.status,
        outcome: aborted.record.outcome,
        abort_status: aborted.abort_status,
      })
    } catch (error) {
      next(error)
    }
  })

  router.delete('/api/v1/tasks/:taskId', async (req, res, next) => {
    try {
      const taskId = single(req.params.taskId)
      const current = manager.get(taskId)
      if (!current) {
        res.status(404).json({ error: 'TASK_NOT_FOUND' })
        return
      }
      if (current.status !== 'terminal') {
        res.status(409).json({ error: 'TASK_NOT_TERMINAL', task_id: taskId, status: current.status })
        return
      }
      const tombstone = await manager.cleanupTerminal(taskId)
      if (!tombstone) {
        res.status(409).json({ error: 'TASK_NOT_TERMINAL', task_id: taskId })
        return
      }
      res.json({
        task_id: taskId,
        status: tombstone.status,
        outcome: tombstone.outcome,
        tombstoned: true,
      })
    } catch (error) {
      next(error)
    }
  })
  return router
}

function subscribe(manager: TaskManager, req: Request, res: Response): void {
  const taskId = single(req.params.taskId)
  const record = manager.get(taskId)
  if (!record) {
    res.status(404).json({ error: 'TASK_NOT_FOUND' })
    return
  }
  const rawAck = singleQuery(req.query.ack_seq)
  if (rawAck !== undefined && !/^\d+$/.test(rawAck)) {
    res.status(400).json({ error: 'ack_seq must be a non-negative integer' })
    return
  }
  const ackSeq = rawAck ? Number(rawAck) : 0
  const broadcast = manager.getBroadcast(taskId)
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
    'X-Accel-Buffering': 'no',
  })
  writeEvent(res, {
    type: 'assistant_text',
    task_id: taskId,
    subtype: 'sync_checkpoint',
    content: '',
    seq: 0,
    ...({ latest_seq: broadcast.getLatestSeq(), event_count: broadcast.getEventCount() } as Record<string, unknown>),
  } as WorkerEvent)
  const unsubscribe = broadcast.subscribeAfter(ackSeq, event => writeEvent(res, event), () => res.end())
  if (record.status === 'terminal' || record.recovery_required || broadcast.isClosed()) {
    unsubscribe()
    res.end()
    return
  }
  req.on('close', unsubscribe)
}

function writeEvent(res: Response, event: WorkerEvent): void {
  res.write(`event: message\ndata: ${JSON.stringify(event)}\n\n`)
}

function readIdempotencyKey(req: Request): string | undefined {
  const value = req.header('Idempotency-Key')?.trim()
  return value && IDEMPOTENCY_KEY_PATTERN.test(value) ? value : undefined
}

function single(value: string | string[] | undefined): string {
  return Array.isArray(value) ? value[0] || '' : value || ''
}

function singleQuery(value: unknown): string | undefined {
  if (Array.isArray(value)) return typeof value[0] === 'string' ? value[0] : undefined
  return typeof value === 'string' ? value : undefined
}

function validateUserInputResponseBody(value: unknown): { request_id: string | number; answers: unknown } | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined
  const body = value as Record<string, unknown>
  if (Object.keys(body).some(key => key !== 'request_id' && key !== 'answers')) return undefined
  const requestId = body.request_id
  if (!((typeof requestId === 'string' && requestId.length > 0 && requestId.length <= 256)
      || (typeof requestId === 'number' && Number.isSafeInteger(requestId)))) return undefined
  if (!body.answers || typeof body.answers !== 'object' || Array.isArray(body.answers)) return undefined
  return { request_id: requestId, answers: body.answers }
}
