import { Router, Request, Response } from 'express'
import { stat } from 'node:fs/promises'
import { v4 as uuidv4 } from 'uuid'
import { config } from '../config.js'
import {
  assertSdkCodexConfigSupported,
  CODEX_NAVIGATOR_WORKER_CREDENTIAL_FORWARDING_UNREADY,
  CODEX_ULTRA_APP_SERVER_REQUIRED,
  CODEX_BIZ_HOME_ROOT_REQUIRED_ERROR,
  CodexUltraAppServerRequiredError,
  resolveSupportedModelAlias,
  runQuery,
  taskBroadcasts,
  taskRegistry,
  cleanupOldTasks,
  getTaskStatus,
  getRunningTaskCount,
  inspectCodexResumeTarget,
  UnsupportedCodexModelError,
  UNSUPPORTED_CODEX_MODEL,
} from '../codex/sdk-wrapper.js'
import { isNavigatorBusinessMcpEnabled } from '../business-mcp/navigator-business-mcp-server.js'
import { isTaskTerminal, type WorkerEvent } from '../models.js'
import { safeSdkError } from '../diagnostics.js'

const SSE_HEARTBEAT_INTERVAL_MS = 15_000

function startSseHeartbeat(res: Response): () => void {
  const timer = setInterval(() => {
    if (!res.writableEnded && !res.destroyed) {
      res.write(': keepalive\n\n')
    }
  }, SSE_HEARTBEAT_INTERVAL_MS)
  timer.unref()
  return () => clearInterval(timer)
}
import { validateQueryRequest } from '../validation/query.js'
import { isPathWithinAllowedCwd } from '../path-guards.js'
import {
  acquireCodexThreadReservation,
  CodexThreadActiveError,
  type CodexThreadReservation,
} from '../codex/thread-reservations.js'
import { preflightLifecycleCommand } from '../lifecycle/command.js'
import {
  LIFECYCLE_SCHEMA,
  type NeverAcceptedReasonCode,
  type LifecycleDisposition,
} from '../lifecycle/store.js'

export { isPathWithinAllowedCwd }
export { CODEX_ULTRA_APP_SERVER_REQUIRED }
export const CODEX_WORKING_DIRECTORY_UNAVAILABLE = 'CODEX_WORKING_DIRECTORY_UNAVAILABLE'

async function isExistingDirectory(candidate: string): Promise<boolean> {
  try {
    return (await stat(candidate)).isDirectory()
  } catch {
    return false
  }
}

export function isUnsupportedCodexModelRequest(
  model: unknown,
  defaultModel: string = config.defaultModel,
  aliases: Record<string, string> = config.modelAliases
): boolean {
  if (model !== undefined && typeof model !== 'string') return false
  const requestedModel = model?.trim() || defaultModel
  try {
    resolveSupportedModelAlias(requestedModel, aliases)
    return false
  } catch (error) {
    if (error instanceof UnsupportedCodexModelError) return true
    if (error instanceof CodexUltraAppServerRequiredError) return false
    throw error
  }
}

export function requiresAppServerForUltra(
  model: unknown,
  defaultModel: string = config.defaultModel,
  aliases: Record<string, string> = config.modelAliases,
  codexConfig?: unknown
): boolean {
  // Keep malformed non-string values on the normal 400 validation path.
  if (model !== undefined && typeof model !== 'string') return false

  const requestedModel = model?.trim() || defaultModel
  try {
    resolveSupportedModelAlias(requestedModel, aliases)
    assertSdkCodexConfigSupported(codexConfig)
    return false
  } catch (error) {
    if (error instanceof CodexUltraAppServerRequiredError) return true
    if (error instanceof UnsupportedCodexModelError) return false
    throw error
  }
}

export function resolveNavigatorBusinessMcpPreflightError(
  context: Record<string, unknown> | undefined,
  localWorkerId: string = config.navigatorWorkerId,
  credentialConfigured: boolean = Boolean(config.navigatorWorkerCredential),
): string | undefined {
  if (!isNavigatorBusinessMcpEnabled(context)) return undefined
  if (!localWorkerId && !credentialConfigured) return undefined
  return CODEX_NAVIGATOR_WORKER_CREDENTIAL_FORWARDING_UNREADY
}

const router = Router()

function rejectEnforcedBeforeEffect(
  res: Response,
  lifecycle: NonNullable<ReturnType<typeof preflightLifecycleCommand>>,
  reason: NeverAcceptedReasonCode,
): boolean {
  if (lifecycle.context.ownership_mode !== 'ENFORCED') return false
  try {
    const disposition = lifecycle.store.rejectBeforeEffect(
      lifecycle.context,
      lifecycle.binding,
      reason,
    )
    res.status(409).json(disposition)
  } catch {
    res.status(503).json({
      schema: LIFECYCLE_SCHEMA,
      code: 'WORKER_LIFECYCLE_STORE_UNAVAILABLE',
    })
  }
  return true
}

/**
 * POST /api/v1/query — Start a Codex query and stream results as SSE
 */
router.post('/api/v1/query', async (req: Request, res: Response) => {
  const lifecycle = preflightLifecycleCommand(
    req,
    res,
    ['TASK_CREATE', 'TASK_RESUME'],
    '/api/v1/query',
    null,
    null,
  )
  if (lifecycle === undefined) return
  if (isUnsupportedCodexModelRequest(req.body?.model)) {
    res.status(400).json({ error: UNSUPPORTED_CODEX_MODEL })
    return
  }
  if (requiresAppServerForUltra(
    req.body?.model,
    config.defaultModel,
    config.modelAliases,
    req.body?.codex_config,
  )) {
    res.status(409).json({
      code: CODEX_ULTRA_APP_SERVER_REQUIRED,
      error: CODEX_ULTRA_APP_SERVER_REQUIRED,
    })
    return
  }
  const validation = validateQueryRequest(req.body)
  if (!validation.ok) {
    res.status(400).json({ error: validation.error })
    return
  }
  const body = validation.value
  if (lifecycle
      && ((lifecycle.context.command_kind === 'TASK_RESUME' && !body.session_id)
        || (lifecycle.context.command_kind === 'TASK_CREATE' && Boolean(body.session_id)))) {
    res.status(409).json({
      schema: LIFECYCLE_SCHEMA,
      code: 'LIFECYCLE_COMMAND_KIND_MISMATCH',
    })
    return
  }
  const businessMcpPreflightError = resolveNavigatorBusinessMcpPreflightError(
    body.business_runtime_context,
  )
  if (businessMcpPreflightError) {
    res.status(503).json({
      code: businessMcpPreflightError,
      error: businessMcpPreflightError,
    })
    return
  }
  console.log(
    `[query] received request: cwd=${body.cwd ?? ''} session_id=${body.session_id ?? ''} model=${body.model ?? ''} has_api_key=${Boolean(body.api_key)} has_base_url=${Boolean(body.base_url)} env_var_keys=${body.env_vars ? Object.keys(body.env_vars).join(',') : ''} images=${body.images?.length ?? 0}`
  )

  const isAllowedPath = (candidate: string): boolean => {
    if (config.allowedCwds.length === 0) return true
    return config.allowedCwds.some(acwd => isPathWithinAllowedCwd(candidate, acwd))
  }

  // Validate working directories
  const cwd = body.cwd
  if (cwd && !isAllowedPath(cwd)) {
    res.status(403).json({ error: `Working directory not allowed: ${cwd}` })
    return
  }
  if (cwd && !await isExistingDirectory(cwd)) {
    res.status(409).json({
      code: CODEX_WORKING_DIRECTORY_UNAVAILABLE,
      error: CODEX_WORKING_DIRECTORY_UNAVAILABLE,
    })
    return
  }

  if (body.additional_directories) {
    for (const directory of body.additional_directories) {
      if (!isAllowedPath(directory)) {
        res.status(403).json({ error: `Additional directory not allowed: ${directory}` })
        return
      }
      if (!await isExistingDirectory(directory)) {
        res.status(409).json({
          code: CODEX_WORKING_DIRECTORY_UNAVAILABLE,
          error: CODEX_WORKING_DIRECTORY_UNAVAILABLE,
        })
        return
      }
    }
  }

  if (body.codex_home_key && !config.codexBizHomeRoot) {
    res.status(403).json({ error: CODEX_BIZ_HOME_ROOT_REQUIRED_ERROR })
    return
  }

  let priorDisposition: LifecycleDisposition | undefined
  if (lifecycle) {
    try {
      priorDisposition = lifecycle.store.getDispatch(
        lifecycle.context.dispatch_id,
        lifecycle.context.ownership_mode,
        lifecycle.binding,
      )
    } catch (error) {
      const code = error instanceof Error
        ? error.message
        : 'LIFECYCLE_DISPATCH_BINDING_MISMATCH'
      res.status(409).json({ schema: LIFECYCLE_SCHEMA, code })
      return
    }
    if (priorDisposition
        && lifecycle.context.ownership_mode === 'ENFORCED'
        && priorDisposition.effect_phase !== 'PREPARED') {
      if (priorDisposition.acceptance_disposition === 'REJECTED'
          && priorDisposition.effect_phase === 'PRE_EFFECT') {
        res.status(409).json({
          ...priorDisposition,
          duplicate: true,
          request_delivery_attempt: lifecycle.context.delivery_attempt,
        })
        return
      }
      res.writeHead(200, {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
        Connection: 'close',
      })
      res.write(`event: lifecycle_disposition\ndata: ${JSON.stringify({
        ...priorDisposition,
        duplicate: true,
        request_delivery_attempt: lifecycle.context.delivery_attempt,
      })}\n\n`)
      res.end()
      return
    }
  }

  if (lifecycle?.context.ownership_mode === 'ENFORCED'
      && lifecycle.context.command_kind === 'TASK_RESUME') {
    const resumeTarget = await inspectCodexResumeTarget(
      body.session_id as string,
      body.codex_home_key,
    )
    if (resumeTarget === 'ABSENT') {
      rejectEnforcedBeforeEffect(
        res,
        lifecycle,
        'WORKER_TASK_RESUME_TARGET_NOT_FOUND',
      )
      return
    }
    if (resumeTarget !== 'PRESENT') {
      res.status(503).json({
        schema: LIFECYCLE_SCHEMA,
        code: 'WORKER_TASK_RESUME_TARGET_VERIFICATION_UNAVAILABLE',
      })
      return
    }
  }

  // PREPARED is durable admission but not provider-effect authorization. A
  // redelivery after a crash may safely continue with the originally allocated
  // provider task id. EFFECT_STARTED and RESULT_OBSERVED are returned above and
  // never invoke the provider twice.
  const taskId = priorDisposition?.provider_task_id ?? uuidv4()

  let threadReservation: CodexThreadReservation | undefined
  if (body.session_id) {
    try {
      threadReservation = await acquireCodexThreadReservation(body.session_id, taskId, {
        taskEntries: taskRegistry.values(),
      })
    } catch (error) {
      if (error instanceof CodexThreadActiveError) {
        if (lifecycle && rejectEnforcedBeforeEffect(
          res,
          lifecycle,
          'WORKER_TASK_ADMISSION_THREAD_CONFLICT',
        )) return
        res.status(409).json({
          code: error.code,
          error: error.code,
          session_id: error.conflict.threadId,
          active_task_id: error.conflict.taskId,
          active_pid: error.conflict.pid,
          conflict_source: error.conflict.source,
        })
        return
      }
      res.status(503).json({ error: 'CODEX_THREAD_LIVENESS_CHECK_FAILED' })
      return
    }
  }

  const runningTasks = getRunningTaskCount()
  if (runningTasks >= config.maxConcurrentTasks) {
    threadReservation?.release()
    if (lifecycle && rejectEnforcedBeforeEffect(
      res,
      lifecycle,
      'WORKER_TASK_ADMISSION_CAPACITY_REJECTED',
    )) return
    res.status(429).json({
      error: `Too many concurrent Codex tasks: ${runningTasks}/${config.maxConcurrentTasks}`,
      running_tasks: runningTasks,
      max_concurrent_tasks: config.maxConcurrentTasks,
    })
    return
  }

  // Clean up old tasks periodically
  cleanupOldTasks()

  let lifecycleDisposition = priorDisposition
  if (lifecycle && !lifecycleDisposition) {
    try {
      lifecycleDisposition = lifecycle.store.prepareAcceptedDispatch(
        lifecycle.context,
        lifecycle.binding,
        () => taskId,
      )
    } catch (error) {
      threadReservation?.release()
      const code = error instanceof Error
        ? error.message
        : 'WORKER_LIFECYCLE_STORE_UNAVAILABLE'
      const status = code === 'WORKER_LIFECYCLE_STORE_UNAVAILABLE' ? 503 : 409
      res.status(status).json({ schema: LIFECYCLE_SCHEMA, code })
      return
    }
  }

  if (lifecycleDisposition
      && lifecycle?.context.ownership_mode === 'ENFORCED') {
    try {
      lifecycleDisposition = lifecycle.store.markEffectStarted(
        lifecycle.context.dispatch_id,
      )
    } catch {
      threadReservation?.release()
      res.status(503).json({
        schema: LIFECYCLE_SCHEMA,
        code: 'WORKER_LIFECYCLE_STORE_UNAVAILABLE',
      })
      return
    }
  }

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
    body.images,
    body.api_key,
    body.base_url,
    body.env_vars,
    {
      codexHomeKey: body.codex_home_key,
      developerInstructions: body.developer_instructions,
      outputSchema: body.output_schema,
      codexConfig: body.codex_config,
      sandboxMode: body.sandbox_mode,
      approvalPolicy: body.approval_policy,
      networkAccessEnabled: body.network_access_enabled,
      webSearchMode: body.web_search_mode,
      businessRuntimeContext: body.business_runtime_context,
      additionalDirectories: body.additional_directories,
    }
  ).finally(() => {
    const entry = getTaskStatus(taskId)
    // A CANCEL_REQUESTED task can still own a live CLI process.  Keep the
    // reservation until an observed terminal state releases it centrally.
    if (!entry || isTaskTerminal(entry.status)) {
      threadReservation?.release()
    }
    if (entry && isTaskTerminal(entry.status)
        && lifecycleDisposition
        && lifecycle?.context.ownership_mode === 'ENFORCED') {
      const terminalOutcome = entry.status === 'completed'
        ? 'COMPLETED'
        : entry.status === 'aborted'
          ? 'CANCELLED'
          : 'FAILED'
      lifecycle.store.markResultObserved(
        lifecycle.context.dispatch_id,
        'TASK_PROVIDER_TERMINAL_OBSERVED',
        terminalOutcome,
        'PROVIDER_RESULT_OBSERVED',
      )
    }
  })
  if (lifecycleDisposition) {
    res.write(`event: lifecycle_disposition\ndata: ${JSON.stringify({
      ...lifecycleDisposition,
      duplicate: Boolean(priorDisposition),
      request_delivery_attempt: lifecycle?.context.delivery_attempt ?? null,
      code: priorDisposition ? 'SHADOW_WOULD_DEDUPE' : undefined,
    })}\n\n`)
  }

  // Wait a tick for broadcast to be registered
  await new Promise(resolve => setTimeout(resolve, 10))

  const broadcast = taskBroadcasts.get(taskId)
  if (!broadcast) {
    const errorData = JSON.stringify({
      type: 'error',
      task_id: taskId,
      ...safeSdkError('Failed to initialize task broadcast'),
      seq: 1,
    })
    res.write(`event: message\ndata: ${errorData}\n\n`)
    res.end()
    return
  }

  // Replay first, then subscribe, then replay the small hand-off gap. This keeps
  // fast tasks from closing the stream before their persisted final event is sent.
  const stopHeartbeat = startSseHeartbeat(res)
  let unsubscribe: () => void = () => undefined
  let lastSentSeq = 0
  const closeStream = () => {
    unsubscribe()
    stopHeartbeat()
    if (!res.writableEnded) res.end()
  }
  const writeEvent = (event: WorkerEvent) => {
    const seq = event.seq || 0
    if (seq > 0 && seq <= lastSentSeq) return
    try {
      const data = JSON.stringify(event)
      res.write(`event: message\ndata: ${data}\n\n`)
      if (seq > lastSentSeq) lastSentSeq = seq
    } catch (e) {
      // Client disconnected
    }
  }

  for (const event of broadcast.getEventsAfter(0)) writeEvent(event)
  if (broadcast.isClosed()) {
    closeStream()
    return
  }

  unsubscribe = broadcast.subscribe(writeEvent, closeStream)
  for (const event of broadcast.getEventsAfter(lastSentSeq)) writeEvent(event)

  // Handle client disconnect
  req.on('close', () => {
    unsubscribe()
    stopHeartbeat()
  })

  // Wait for query to complete, then close SSE
  queryPromise.finally(() => {
    const entry = getTaskStatus(taskId)
    if (!entry || isTaskTerminal(entry.status)) {
      closeStream()
    }
  })
})

export default router
