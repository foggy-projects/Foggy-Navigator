import { Router, Request, Response } from 'express'
import { config } from '../config.js'
import {
  confirmTaskProcessExit,
  markTaskTerminationUnconfirmed,
  requestTaskProcessKill,
  taskRegistry,
} from '../codex/sdk-wrapper.js'
import { isTaskExecutionActive, type TaskEntry } from '../models.js'
import {
  canonicalizeCodexCliProcessStartedAt,
  CodexProcessKillError,
  codexCliProcessIdentity,
  extractResumedThreadId,
  killCodexCliProcess,
  listCodexCliProcesses,
} from '../codex/processes.js'
import {
  TerminationOperationReceiptLedger,
  TerminationOperationValidationError,
  toTerminationOperationSummary,
  validateTerminationOperation,
} from '../termination-operation.js'

export type ProcessRouteDependencies = {
  listProcesses?: typeof listCodexCliProcesses
  killProcess?: typeof killCodexCliProcess
  terminationReplayLedger?: TerminationOperationReceiptLedger
}

type ProcessBoundTask = Pick<TaskEntry, 'taskId' | 'pid' | 'status'>

type SafeKillAttemptMetadata = {
  attempt_count: number
  exit_codes: number[]
}

export function findActiveTaskBoundToProcess(
  pid: number,
  taskEntries: Iterable<ProcessBoundTask>,
): ProcessBoundTask | undefined {
  // A PID is safe to signal only when it maps to exactly one active Worker
  // task.  Selecting the first match would permit a signed task operation to
  // affect an ambiguous process binding.
  const boundTasks = [...taskEntries].filter(
    entry => entry.pid === pid && isTaskExecutionActive(entry.status),
  )
  return boundTasks.length === 1 ? boundTasks[0] : undefined
}

function sendTerminationOperationError(res: Response, error: unknown): boolean {
  if (!(error instanceof TerminationOperationValidationError)) return false
  res.status(error.statusCode).json({ error: error.code, code: error.code })
  return true
}

function safeExceptionType(error: unknown): string | undefined {
  if (!(error instanceof Error)) return undefined
  return ['Error', 'TypeError', 'RangeError', 'AbortError', 'TimeoutError'].includes(error.name)
    ? error.name
    : undefined
}

function safeKillAttemptMetadata(error: CodexProcessKillError): SafeKillAttemptMetadata {
  return {
    attempt_count: error.attempts.length,
    exit_codes: error.attempts
      .map(attempt => attempt.exitCode)
      .filter((exitCode): exitCode is number => Number.isInteger(exitCode)),
  }
}

function safeIdentifier(value: string | undefined): string | undefined {
  if (!value || !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$/.test(value)) return undefined
  return value
}

export function createProcessesRouter(dependencies: ProcessRouteDependencies = {}): Router {
  const router = Router()
  const listProcesses = dependencies.listProcesses ?? listCodexCliProcesses
  const killProcess = dependencies.killProcess ?? killCodexCliProcess
  const terminationReplayLedger = dependencies.terminationReplayLedger
    ?? new TerminationOperationReceiptLedger(config.terminationOperationLedgerDir)

  router.get('/api/v1/processes', async (_req: Request, res: Response) => {
    try {
      const processes = await listProcesses()
      const taskEntries = Array.from(taskRegistry.values())

      const payload = processes.map(processInfo => {
        const matchedEntry = taskEntries.find(entry => entry.pid === processInfo.pid)
        const activeBoundTask = findActiveTaskBoundToProcess(processInfo.pid, taskEntries)
        const startedAt = canonicalizeCodexCliProcessStartedAt(processInfo.started_at)
        const threadId = safeIdentifier(
          matchedEntry?.threadId || extractResumedThreadId(processInfo.command),
        )
        // A process identity is an authorization input, not general process
        // metadata. Only issue it for the one current task that can safely be
        // signalled and when the OS creation time has a canonical form shared
        // with the pre-kill verification scan.
        const processIdentity = activeBoundTask && startedAt
          ? codexCliProcessIdentity(processInfo.pid, startedAt)
          : undefined
        return {
          // Do not return the raw command line: it can contain workspace paths,
          // arguments, or provider credentials. This endpoint is an operation
          // binding snapshot, not a process-debugging endpoint.
          pid: processInfo.pid,
          command: 'codex',
          memory_mb: Number.isFinite(processInfo.memory_mb) ? processInfo.memory_mb : 0,
          // Only the canonical timestamp can safely participate in a signed
          // PID identity. An unavailable OS creation time is omitted rather
          // than exposed as an alternative representation.
          started_at: startedAt,
          process_type: 'codex',
          is_orphan: !matchedEntry,
          // The control plane must confirm the Navigator task ↔ PID binding
          // from a fresh Worker snapshot before it mints a manual-kill capability.
          // Do not expose an id for an untracked/orphaned process.
          foggy_task_id: activeBoundTask?.taskId ?? matchedEntry?.taskId,
          // Omitted for inactive, ambiguous, orphaned, or creation-time-free
          // processes so it cannot be used to authorize a PID signal.
          process_identity: processIdentity,
          codex_thread_id: threadId,
          model: safeIdentifier(matchedEntry?.model),
        }
      })

      res.json({
        processes: payload,
        active_task_count: taskEntries.filter(entry => isTaskExecutionActive(entry.status)).length,
        total: payload.length,
      })
    } catch (error) {
      const exceptionType = safeExceptionType(error)
      console.warn('Codex CLI process listing unavailable', {
        code: 'CODEX_PROCESS_LIST_UNAVAILABLE',
        ...(exceptionType ? { exception_type: exceptionType } : {}),
      })
      res.status(503).json({
        error: 'CODEX_PROCESS_LIST_UNAVAILABLE',
        code: 'CODEX_PROCESS_LIST_UNAVAILABLE',
      })
    }
  })

/**
 * POST /api/v1/processes/:pid/kill
 *
 * A physical process signal is an ADMIN_MANUAL operation only.  This endpoint
 * never infers task abortion from the signal itself; it reports either a
 * verified exit or an explicitly unconfirmed outcome.
 */
router.post('/api/v1/processes/:pid/kill', async (req: Request, res: Response) => {
  const pid = Number(req.params.pid)
  if (!Number.isInteger(pid) || pid <= 0) {
    res.status(400).json({ error: 'INVALID_PID', code: 'INVALID_PID' })
    return
  }

  const boundTask = findActiveTaskBoundToProcess(pid, taskRegistry.values())
  if (!boundTask) {
    res.status(409).json({
      error: 'TERMINATION_OPERATION_TASK_BINDING_UNVERIFIED',
      code: 'TERMINATION_OPERATION_TASK_BINDING_UNVERIFIED',
      pid,
    })
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
        expectedKind: 'MANUAL_PID_KILL',
        expectedTaskId: boundTask.taskId,
        expectedPid: pid,
        replayLedger: terminationReplayLedger,
      },
    )
  } catch (error) {
    if (sendTerminationOperationError(res, error)) return
    throw error
  }

  const operation = toTerminationOperationSummary(claims, 'CANCEL_REQUESTED')
  const requestTermination = () => {
    const requested = requestTaskProcessKill(boundTask.taskId, operation)
    if (requested) return true
    res.status(409).json({
      error: 'TASK_TERMINATION_NOT_ACCEPTED',
      code: 'TASK_TERMINATION_NOT_ACCEPTED',
      task_id: boundTask.taskId,
      pid,
    })
    return false
  }

  const force = req.body?.force === true
  let beforeKill
  try {
    beforeKill = await listProcesses()
  } catch {
    if (!requestTermination()) return
    const unconfirmed = markTaskTerminationUnconfirmed(
      boundTask.taskId,
      operation.operation_id,
      'PROCESS_SCAN_FAILED_BEFORE_MANUAL_KILL',
    )
    res.status(503).json({
      pid,
      task_id: boundTask.taskId,
      status: 'unconfirmed',
      observed_exit: false,
      termination_operation: unconfirmed?.terminationOperation ?? operation,
    })
    return
  }

  const preKillProcess = beforeKill.find(processInfo => processInfo.pid === pid)
  if (!preKillProcess) {
    // A missing PID in the first scan is not proof that this Worker observed
    // the *bound* process exit. It could have disappeared before this manual
    // operation was dispatched or the process snapshot could be incomplete.
    if (!requestTermination()) return
    const unconfirmed = markTaskTerminationUnconfirmed(
      boundTask.taskId,
      operation.operation_id,
      'PROCESS_ABSENT_BEFORE_MANUAL_KILL_UNCONFIRMED',
    )
    res.status(202).json({
      pid,
      task_id: boundTask.taskId,
      status: 'unconfirmed',
      observed_exit: false,
      code: 'PROCESS_ABSENT_BEFORE_MANUAL_KILL_UNCONFIRMED',
      termination_operation: unconfirmed?.terminationOperation ?? operation,
    })
    return
  }

  // A PID can be recycled between the control-plane snapshot and this
  // dispatch.  Only signal the process when its fresh, canonical creation
  // identity is exactly the one signed by the authorized operation.
  const observedStartedAt = canonicalizeCodexCliProcessStartedAt(preKillProcess.started_at)
  const observedProcessIdentity = observedStartedAt
    ? codexCliProcessIdentity(pid, observedStartedAt)
    : undefined
  if (!observedProcessIdentity || observedProcessIdentity !== claims.expected_process_identity) {
    res.status(409).json({
      error: 'TERMINATION_OPERATION_PROCESS_IDENTITY_MISMATCH',
      code: 'TERMINATION_OPERATION_PROCESS_IDENTITY_MISMATCH',
      task_id: boundTask.taskId,
      pid,
    })
    return
  }

  if (!requestTermination()) return

  let killError: unknown
  try {
    await killProcess(pid, force)
  } catch (error) {
    killError = error
  }

  try {
    const remainingProcesses = await listProcesses()
    if (!remainingProcesses.some(processInfo => processInfo.pid === pid)) {
      const observed = confirmTaskProcessExit(
        boundTask.taskId,
        operation.operation_id,
        killError ? 'PROCESS_EXIT_VERIFIED_AFTER_KILL_ERROR' : 'PROCESS_EXIT_VERIFIED',
      )
      res.json({
        pid,
        task_id: boundTask.taskId,
        status: 'observed_exit',
        observed_exit: true,
        termination_operation: observed?.terminationOperation ?? operation,
      })
      return
    }
  } catch {
    // Fall through to the unconfirmed result below.  A failed verification is
    // never converted into a task terminal state.
  }

  const unconfirmed = markTaskTerminationUnconfirmed(
    boundTask.taskId,
    operation.operation_id,
    killError ? 'MANUAL_KILL_DISPATCH_FAILED_OR_UNVERIFIED' : 'MANUAL_KILL_DISPATCHED_EXIT_UNVERIFIED',
  )
  const payload = {
    pid,
    task_id: boundTask.taskId,
    status: 'unconfirmed',
    observed_exit: false,
    termination_operation: unconfirmed?.terminationOperation ?? operation,
  }
  if (killError instanceof CodexProcessKillError) {
    const metadata = safeKillAttemptMetadata(killError)
    console.warn('Codex CLI manual kill remains unconfirmed', {
      pid,
      code: 'CODEX_PROCESS_KILL_UNCONFIRMED',
      ...metadata,
    })
    res.status(502).json({
      ...payload,
      error: 'CODEX_PROCESS_KILL_UNCONFIRMED',
      code: 'CODEX_PROCESS_KILL_UNCONFIRMED',
      ...metadata,
    })
    return
  }
  if (killError) {
    const exceptionType = safeExceptionType(killError)
    console.warn('Codex CLI manual kill remains unconfirmed', {
      pid,
      code: 'CODEX_PROCESS_KILL_UNCONFIRMED',
      ...(exceptionType ? { exception_type: exceptionType } : {}),
    })
    res.status(502).json({
      ...payload,
      error: 'CODEX_PROCESS_KILL_UNCONFIRMED',
      code: 'CODEX_PROCESS_KILL_UNCONFIRMED',
    })
    return
  }
  res.status(202).json(payload)
})

  return router
}

const router = createProcessesRouter()

export default router
