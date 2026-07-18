import { Router, type Request, type Response } from 'express'
import path from 'node:path'
import type { AppConfig } from '../config.js'
import { isAllowedWorkingPath, resolveAllowedWorkingPath, workerPrivatePaths } from '../path-guards.js'
import {
  IdempotencyConflictError,
  TaskTerminationOperationPendingError,
} from '../persistence/task-store.js'
import { resolveRuntimeReadiness } from '../runtime-capabilities.js'
import {
  ContextMaintenanceError,
  TaskManager,
  TaskManagerDrainingError,
  TaskQueueFullError,
  TaskThreadActiveError,
  UserInputResponseError,
  toPublicTask,
} from '../task-manager.js'
import { StaleTurnCleanupError } from '../stale-turn-cleanup.js'
import { ContextCompactOperationConflictError } from '../persistence/context-maintenance-store.js'
import { UserInputResponseValidationError } from '../app-server/user-input.js'
import {
  resolveSupportedModelAlias,
  UnsupportedCodexModelError,
} from '../model-resolution.js'
import { validateTaskRequest } from '../validation/task-request.js'
import type { WorkerEvent } from '../models.js'
import { GeneratedImageStore } from '../generated-image-store.js'
import { appServerProcessIdentity } from '../app-server/executor.js'
import {
  TerminationOperationReceiptLedger,
  TerminationOperationValidationError,
  validateTerminationOperation,
  type ValidatedTerminationOperation,
} from '../termination-operation.js'

const IDEMPOTENCY_KEY_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/

export function createTasksRouter(config: AppConfig, manager: TaskManager): Router {
  const router = Router()
  const generatedImages = new GeneratedImageStore(config)
  // Kept under the state-store root, which is already private from task
  // working directories and holds the Writer lease/identity.  Receipts are
  // independent from event persistence and survive a Worker restart.
  const terminationReplayLedger = new TerminationOperationReceiptLedger(
    path.join(config.stateDir, 'termination-operations', 'receipts'),
  )

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

  router.get('/api/v1/tasks/:taskId/context-usage', (req, res) => {
    const taskId = single(req.params.taskId)
    const record = manager.get(taskId)
    if (!record || record.tombstoned_at) {
      res.status(404).json({ error: 'TASK_NOT_FOUND' })
      return
    }
    const usage = manager.getContextUsage(taskId)
    res.json(usage || {
      schema_version: 1,
      thread_id: record.thread_id,
      observed_at: null,
      status: 'unknown',
    })
  })

  router.post('/api/v1/tasks/:taskId/compact-context', async (req, res, next) => {
    try {
      const operationId = readCompactOperationId(req.body)
      if (!operationId) {
        res.status(400).json({ error: 'INVALID_CONTEXT_COMPACT_OPERATION_ID' })
        return
      }
      const operation = await manager.compactContext(single(req.params.taskId), operationId)
      res.status(operation.status === 'running' || operation.status === 'unknown' ? 202 : 200).json(operation)
    } catch (error) {
      if (error instanceof ContextMaintenanceError) {
        const status = error.code === 'TASK_NOT_FOUND' ? 404
          : error.code === 'APP_SERVER_THREAD_ACTIVE' || error.code === 'TASK_NOT_TERMINAL' ? 409
            : 422
        res.status(status).json({ error: error.code })
        return
      }
      if (error instanceof ContextCompactOperationConflictError) {
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

  router.get('/api/v1/tasks/:taskId/compact-context/:operationId', (req, res) => {
    const taskId = single(req.params.taskId)
    if (!manager.get(taskId)) {
      res.status(404).json({ error: 'TASK_NOT_FOUND' })
      return
    }
    const operation = manager.getContextCompactOperation(taskId, single(req.params.operationId))
    if (!operation) {
      res.status(404).json({ error: 'CONTEXT_COMPACT_OPERATION_NOT_FOUND' })
      return
    }
    res.status(operation.status === 'running' || operation.status === 'unknown' ? 202 : 200).json(operation)
  })

  router.get('/api/v1/processes', async (_req, res) => {
    try {
      const snapshots = await manager.listManagedTaskProcesses()
      // This endpoint is a fresh task-to-runtime binding snapshot for the
      // control plane.  Keep the projection deliberately fixed: command
      // lines, working paths, provider output, and environment data are not
      // process identity and must never cross this boundary.
      const processes = snapshots.map(snapshot => ({
        pid: snapshot.pid,
        command: 'codex-app-server',
        process_type: 'codex-app-server',
        is_orphan: false,
        foggy_task_id: snapshot.taskId,
        process_identity: appServerProcessIdentity(snapshot.instanceId),
      }))
      res.json({
        processes,
        active_task_count: processes.length,
        total: processes.length,
      })
    } catch {
      // A transient in-memory snapshot failure is not a reason to reconcile,
      // retire, or signal any task.  Return a stable availability code only.
      console.warn('[codex-app-server] managed_process_snapshot_unavailable')
      res.status(503).json({ error: 'APP_SERVER_PROCESS_SNAPSHOT_UNAVAILABLE' })
    }
  })

  router.get('/api/v1/tasks/:taskId/subscribe', (req, res) => {
    subscribe(manager, req, res)
  })

  router.get('/api/v1/tasks/:taskId/generated-images/:artifactId', (req, res, next) => {
    try {
      const taskId = single(req.params.taskId)
      const record = manager.get(taskId)
      if (!record || record.tombstoned_at) {
        res.status(404).json({ error: 'TASK_NOT_FOUND' })
        return
      }
      const image = generatedImages.read(taskId, single(req.params.artifactId))
      if (!image) {
        res.status(404).json({ error: 'GENERATED_IMAGE_NOT_FOUND' })
        return
      }
      res.set({
        'Content-Type': image.data.mime_type,
        'Content-Length': String(image.data.size_bytes),
        'Content-Disposition': `inline; filename="${image.data.file_name}"`,
        'Cache-Control': 'private, no-store',
        ETag: `"${image.data.sha256}"`,
      })
      res.send(image.bytes)
    } catch (error) {
      next(error)
    }
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
      const operation = validateTerminationOperation(
        req,
        config,
        taskId,
        'REMOTE_CANCEL',
        terminationReplayLedger,
      )
      const aborted = await manager.abort(taskId, toOperationSummary(operation))
      if (!aborted) {
        res.status(404).json({ error: 'TASK_NOT_FOUND' })
        return
      }
      if (aborted.abort_status === 'cancel_requested') {
        res.status(202).json({
          task_id: taskId,
          status: aborted.record.status,
          lifecycle_status: 'CANCEL_REQUESTED',
          abort_status: 'cancel_requested',
          termination_operation: aborted.record.termination_operation,
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
      if (error instanceof TerminationOperationValidationError) {
        sendTerminationOperationError(res, error)
        return
      }
      if (error instanceof TaskTerminationOperationPendingError) {
        res.status(409).json({ error: error.code })
        return
      }
      next(error)
    }
  })

  router.post('/api/v1/tasks/:taskId/stale-turn-cleanup', async (req, res, next) => {
    try {
      // There is intentionally no caller-supplied target identity. An empty
      // object is tolerated for generic HTTP clients; every other body is
      // rejected before the one-use capability is consumed.
      if (!isEmptyBody(req.body)) {
        res.status(400).json({ error: 'STALE_TURN_CLEANUP_BODY_UNSUPPORTED' })
        return
      }
      const taskId = single(req.params.taskId)
      const operation = validateTerminationOperation(
        req,
        config,
        taskId,
        'STALE_TURN_INTERRUPT',
        terminationReplayLedger,
      )
      const result = await manager.cleanupStaleTurn(taskId)
      // This receipt is deliberately fixed. It must not reveal a Thread,
      // Turn, lane, runtime instance, or process identity to the control
      // plane/browser.
      res.status(200).json({
        task_id: taskId,
        operation_id: operation.operation_id,
        status: result.status,
      })
    } catch (error) {
      if (error instanceof TerminationOperationValidationError) {
        sendTerminationOperationError(res, error)
        return
      }
      if (error instanceof StaleTurnCleanupError) {
        res.status(error.httpStatus).json({ error: error.code })
        return
      }
      next(error)
    }
  })

  const killAuthorizedProcess = async (req: Request, res: Response, next: (error: Error) => void): Promise<void> => {
    try {
      const pid = Number(single(req.params.pid))
      if (!Number.isSafeInteger(pid) || pid <= 0) {
        res.status(400).json({ error: 'INVALID_PROCESS_ID' })
        return
      }
      const routeTaskId = req.params.taskId === undefined ? undefined : single(req.params.taskId)
      const operation = validateTerminationOperation(
        req,
        config,
        routeTaskId,
        'MANUAL_PID_KILL',
        terminationReplayLedger,
      )
      if (operation.expected_pid !== pid) {
        res.status(409).json({ error: 'TERMINATION_OPERATION_MISMATCH' })
        return
      }
      const result = await manager.manualPidKill(operation.task_id, pid, toOperationSummary(operation))
      if (!result) {
        res.status(404).json({ error: 'TASK_NOT_FOUND' })
        return
      }
      const providerTerminalObserved = result.provider_terminal_observed === true
      const terminalProviderResult = providerTerminalObserved && result.record.status === 'terminal'
      res.status(result.observed_exit || terminalProviderResult ? 200 : 202).json({
        task_id: result.record.task_id,
        status: result.record.status,
        lifecycle_status: result.observed_exit
          ? 'ABORTED'
          : providerTerminalObserved
            ? String(toPublicTask(result.record).lifecycle_status)
            : 'CANCEL_REQUESTED',
        observed_exit: result.observed_exit,
        ...(providerTerminalObserved ? { provider_terminal_observed: true } : {}),
        termination_operation: result.record.termination_operation,
      })
    } catch (error) {
      if (error instanceof TerminationOperationValidationError) {
        sendTerminationOperationError(res, error)
        return
      }
      if (error instanceof TaskTerminationOperationPendingError) {
        res.status(409).json({ error: error.code })
        return
      }
      next(error as Error)
    }
  }

  router.post('/api/v1/tasks/:taskId/processes/:pid/kill', killAuthorizedProcess)
  router.post('/api/v1/processes/:pid/kill', killAuthorizedProcess)

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

function toOperationSummary(operation: ValidatedTerminationOperation) {
  return {
    operation_id: operation.operation_id,
    task_id: operation.task_id,
    worker_id: operation.worker_id,
    target_worker_id: operation.target_worker_id,
    kind: operation.kind,
    origin: operation.origin,
    actor_id: operation.actor_id,
    actor_type: operation.actor_type,
    authorization_decision_id: operation.authorization_decision_id,
    reason_code: operation.reason_code,
    correlation_id: operation.correlation_id,
    issued_at: operation.issued_at,
    expires_at: operation.expires_at,
    requested_at: new Date().toISOString(),
    status: 'CANCEL_REQUESTED' as const,
    expected_pid: operation.expected_pid,
    expected_process_identity: operation.expected_process_identity,
  }
}

function sendTerminationOperationError(res: Response, error: TerminationOperationValidationError): void {
  const status = error.code === 'TERMINATION_AUTH_UNCONFIGURED'
    || error.code === 'TERMINATION_OPERATION_REPLAY_LEDGER_FULL'
    || error.code === 'TERMINATION_OPERATION_REPLAY_LEDGER_UNAVAILABLE'
    ? 503
    : error.code === 'TERMINATION_OPERATION_REPLAYED' || error.code === 'TERMINATION_OPERATION_MISMATCH'
      ? 409
      : error.code === 'TERMINATION_OPERATION_SIGNATURE_INVALID'
        ? 403
        : 400
  res.status(status).json({ error: error.code })
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

function isEmptyBody(value: unknown): boolean {
  return value === undefined
    || (value !== null && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === 0)
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

function readCompactOperationId(value: unknown): string | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined
  const body = value as Record<string, unknown>
  if (Object.keys(body).some(key => key !== 'operation_id')) return undefined
  const operationId = typeof body.operation_id === 'string' ? body.operation_id.trim() : ''
  return IDEMPOTENCY_KEY_PATTERN.test(operationId) ? operationId : undefined
}
