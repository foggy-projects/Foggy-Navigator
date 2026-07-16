import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import type { AppConfig } from '../config.js'
import { requireCodexConfigOverride } from '../codex-config.js'
import type { CodexInput, StoredTaskRecord, TaskRequest, TerminationOperationSummary } from '../models.js'
import { parseModelString, resolveSupportedModelAlias } from '../model-resolution.js'
import { EventBroadcast } from '../persistence/event-store.js'
import { GeneratedImageStore } from '../generated-image-store.js'
import { syncParentDirectory } from '../persistence/jsonl-durability.js'
import {
  assertCodexHomeIsolation,
  resolveAllowedWorkingPath,
  resolveContainedHomePath,
  workerPrivatePaths,
} from '../path-guards.js'
import {
  AppServerEventBridge,
  detectToolCapabilityFailure,
  shouldRetireAppServerAfterTurnFailure,
  stableAppServerTurnErrorCode,
} from './event-bridge.js'
import { KeyedExecutionLocks } from './execution-locks.js'
import { buildAppServerLane } from './lane.js'
import { AppServerPool } from './pool.js'
import type { PoolRateLimitsView } from './rate-limits.js'
import type { UserInputServerRequest, UserInputWireResponse } from './user-input.js'
import {
  AppServerRuntimeError,
  isAppServerProcessTreeSafetyError,
  VALIDATED_APP_SERVER_CLI_VERSION,
} from './runtime.js'

export type ExecutionCallbacks = {
  onInstanceResolved: (instanceId: string, laneKey: string) => void | Promise<void>
  onThreadResolved: (threadId: string) => void | Promise<void>
  onExecutionCommitted: (threadId: string) => void | Promise<void>
  onTurnStarted: (threadId: string, turnId: string | undefined) => void | Promise<void>
  onUserInputRequest: (request: UserInputServerRequest, runtimeInstanceId: string) => Promise<UserInputWireResponse>
  onUserInputResolved: (
    resolution: { requestId: string | number; threadId: string },
    runtimeInstanceId: string,
  ) => void | Promise<void>
}

export type ExecutionResult = {
  threadId: string
  turnId?: string
  status: 'completed' | 'failed' | 'interrupted'
  assistantText: string
  inputTokens: number
  outputTokens: number
  model: string
  durationMs: number
  errorCode?: string
}

export type ReconciliationResult = {
  status: 'completed' | 'failed' | 'interrupted' | 'unknown'
  threadId: string
  turnId?: string
  assistantText?: string
  model?: string
  instanceId?: string
  laneKey?: string
  errorCode?: string
}

export type ExplicitAbortDispatchResult = 'requested' | 'unavailable'
export type ManualPidKillResult = {
  observed_exit: boolean
  /**
   * A matching provider terminal event was already observed.  No signal was
   * sent and TaskManager must let the natural terminal path own provenance.
   */
  provider_terminal_observed?: boolean
}

/**
 * A deliberately minimal projection of a lease currently owned by a task.
 * It is used only to bind a human-authorized manual PID operation; callers
 * must not treat it as a general process-inspection API.
 */
export type ManagedTaskProcessSnapshot = {
  taskId: string
  pid: number
  instanceId: string
}

export interface TaskExecutor {
  execute(options: {
    taskId: string
    request: TaskRequest
    broadcast: EventBroadcast
    signal: AbortSignal
    callbacks: ExecutionCallbacks
  }): Promise<ExecutionResult>
  reconcile?(options: {
    taskId: string
    request: TaskRequest
    record: StoredTaskRecord
    signal: AbortSignal
  }): Promise<ReconciliationResult>
  metrics?(): Record<string, unknown>
  isDraining?(): boolean
  drain?(timeoutMs: number): Promise<void>
  readDefaultRateLimits?(refresh?: boolean): Promise<PoolRateLimitsView>
  requestExplicitAbort?(taskId: string, record: StoredTaskRecord): Promise<ExplicitAbortDispatchResult>
  listManagedTaskProcesses?(): Promise<readonly ManagedTaskProcessSnapshot[]> | readonly ManagedTaskProcessSnapshot[]
  manualPidKill?(
    taskId: string,
    pid: number,
    record: StoredTaskRecord,
    operation: TerminationOperationSummary,
  ): Promise<ManualPidKillResult>
}

type TaskRuntimeLease = {
  lease: Awaited<ReturnType<AppServerPool['acquire']>>
  threadId?: string
  turnId?: string
  retainLease: boolean
}

export class StrictAppServerExecutor implements TaskExecutor {
  private readonly pool: AppServerPool
  private readonly locks: KeyedExecutionLocks
  /** A retained lease prevents a failed observer from being recycled over a live turn. */
  private readonly taskRuntimeLeases = new Map<string, TaskRuntimeLease>()

  constructor(
    private readonly config: AppConfig,
    pool?: AppServerPool,
    locks?: KeyedExecutionLocks,
  ) {
    this.pool = pool || new AppServerPool(config)
    this.locks = locks || new KeyedExecutionLocks()
  }

  async execute(options: {
    taskId: string
    request: TaskRequest
    broadcast: EventBroadcast
    signal: AbortSignal
    callbacks: ExecutionCallbacks
  }): Promise<ExecutionResult> {
    const startedAt = Date.now()
    const context = await this.buildContext(options.request)
    const releases = await this.acquireExecutionLocks(options.request, options.signal)
    let inputFiles: Awaited<ReturnType<typeof materializeInput>> | undefined
    let lease: Awaited<ReturnType<AppServerPool['acquire']>> | undefined
    let retireLease = false
    let taskRuntime: TaskRuntimeLease | undefined
    try {
      inputFiles = await materializeInput(options.taskId, options.request, this.config.stateDir)
      this.assertCanonicalCwdUnchanged(context.cwd)
      lease = options.request.session_id
        ? await this.pool.acquireForThread(context.lane, options.request.session_id, options.signal)
        : await this.pool.acquire(context.lane, options.signal)
      const runtimeInstanceId = lease.instanceId
      taskRuntime = { lease, retainLease: false }
      this.taskRuntimeLeases.set(options.taskId, taskRuntime)
      await options.callbacks.onInstanceResolved(runtimeInstanceId, context.lane.key)
      const bridge = new AppServerEventBridge({
        taskId: options.taskId,
        broadcast: options.broadcast,
        rootThreadId: options.request.session_id,
        generatedImageStore: this.config.imageGenerationMode === 'local'
          ? new GeneratedImageStore(this.config)
          : undefined,
      })
      const result = await lease.runtime.runTurn({
        taskId: options.taskId,
        model: context.model,
        reasoningEffort: context.reasoningEffort,
        cwd: context.cwd,
        threadId: options.request.session_id,
        approvalPolicy: 'never',
        sandboxMode: options.request.sandbox_mode || 'danger-full-access',
        codexConfig: context.codexConfig,
        developerInstructions: options.request.developer_instructions,
        outputSchema: options.request.output_schema,
        input: inputFiles.input,
        signal: options.signal,
        turnStallTimeoutMs: this.config.turnStallTimeoutMs,
        onThreadResolved: async threadId => {
          if (taskRuntime) taskRuntime.threadId = threadId
          bridge.setRootThreadId(threadId)
          await options.callbacks.onThreadResolved(threadId)
        },
        onExecutionCommitted: options.callbacks.onExecutionCommitted,
        onTurnStarted: async (threadId, turnId) => {
          if (taskRuntime) {
            taskRuntime.threadId = threadId
            taskRuntime.turnId = turnId
          }
          if (turnId) bridge.setRootTurnId(turnId)
          await options.callbacks.onTurnStarted(threadId, turnId)
        },
        onNotification: notification => bridge.handle(notification),
        onUserInputRequest: request => options.callbacks.onUserInputRequest(request, runtimeInstanceId),
        onUserInputResolved: resolution => options.callbacks.onUserInputResolved(resolution, runtimeInstanceId),
      })
      const turnId = readString(result.turn.id)
      const bridged = bridge.getResult()
      const reportedStatus = normalizeTurnStatus(result.turn.status)
      const completedFailure = reportedStatus === 'failed'
        ? stableAppServerTurnErrorCode(result.turn.error)
        : undefined
      const capabilityFailure = reportedStatus === 'completed'
        ? detectToolCapabilityFailure(bridged.assistantText)
        : undefined
      const errorCode = preferredTurnFailure(
        capabilityFailure,
        preferredTurnFailure(bridged.terminalFailure, completedFailure),
      )
      const status = errorCode ? 'failed' : reportedStatus
      retireLease = Boolean(errorCode && shouldRetireAppServerAfterTurnFailure(errorCode))
      return {
        threadId: result.threadId,
        turnId,
        status,
        assistantText: bridged.assistantText,
        inputTokens: bridged.inputTokens,
        outputTokens: bridged.outputTokens,
        model: context.model,
        durationMs: Date.now() - startedAt,
        errorCode,
      }
    } catch (error) {
      if (taskRuntime && (shouldRetainLeaseForUnverifiedTurn(error)
          || isAppServerProcessTreeSafetyError(error)
          || taskRuntime.lease.runtime.requiresAttention?.())) {
        taskRuntime.retainLease = true
      }
      if (isAppServerProcessTreeSafetyError(error)) {
        this.pool.failClosed(error instanceof Error ? error : new Error(String(error)))
      }
      throw error
    } finally {
      if (lease && taskRuntime?.retainLease) {
        // Deliberately retain the app-server process and its exact lane.  A
        // timeout/protocol failure is not evidence that the underlying turn
        // stopped, so pool retirement must not call runtime.close().
      } else if (lease) {
        lease.runtime.markObservedTerminal?.()
        lease.release(lease.runtime.isHealthy() && !retireLease)
        if (this.taskRuntimeLeases.get(options.taskId) === taskRuntime) {
          this.taskRuntimeLeases.delete(options.taskId)
        }
      }
      await inputFiles?.cleanup()
      for (const release of releases.reverse()) release()
    }
  }

  async requestExplicitAbort(taskId: string, record: StoredTaskRecord): Promise<ExplicitAbortDispatchResult> {
    const retained = this.taskRuntimeLeases.get(taskId)
    if (!retained || !retained.threadId || !retained.turnId || !retained.lease.runtime.interruptTurn) {
      return 'unavailable'
    }
    if (record.thread_id && record.thread_id !== retained.threadId) return 'unavailable'
    if (record.turn_id && record.turn_id !== retained.turnId) return 'unavailable'
    await retained.lease.runtime.interruptTurn(retained.threadId, retained.turnId)
    return 'requested'
  }

  /**
   * Returns only task-owned, retained runtime leases.  It never probes the
   * operating system or touches the app-server transport, so taking this
   * snapshot cannot interrupt, release, or otherwise alter a running turn.
   */
  listManagedTaskProcesses(): ManagedTaskProcessSnapshot[] {
    return [...this.taskRuntimeLeases.entries()]
      .flatMap(([taskId, retained]) => {
        const pid = retained.lease.runtime.pid
        // A lease can briefly be assigned before runTurn starts.  Do not
        // publish that idle process as task-bound; retain an unverified lease
        // only because it may still own an indeterminate live turn.
        if (!retained.lease.runtime.isActive() && !retained.retainLease) return []
        if (typeof pid !== 'number' || !Number.isSafeInteger(pid) || pid < 1) return []
        return [{
          taskId,
          pid,
          instanceId: retained.lease.instanceId,
        }]
      })
      .sort((left, right) => left.taskId.localeCompare(right.taskId) || left.pid - right.pid)
  }

  async manualPidKill(
    taskId: string,
    pid: number,
    record: StoredTaskRecord,
    operation: TerminationOperationSummary,
  ): Promise<ManualPidKillResult> {
    const retained = this.taskRuntimeLeases.get(taskId)
    if (!retained || retained.lease.runtime.pid !== pid || !retained.lease.runtime.forceTerminateForAuthorizedOperation) {
      return { observed_exit: false }
    }
    // PID alone is not a durable identity: an OS can recycle it between the
    // Java controller's fresh snapshot and this dispatch.  The pool instance
    // id is created when this exact runtime process is registered and is
    // persisted on the task, so it is the app-server equivalent of a process
    // start timestamp.
    if (operation.expected_process_identity !== appServerProcessIdentity(retained.lease.instanceId)) {
      return { observed_exit: false }
    }
    if (record.app_server_instance_id && record.app_server_instance_id !== retained.lease.instanceId) {
      return { observed_exit: false }
    }
    if (record.thread_id && record.thread_id !== retained.threadId) {
      return { observed_exit: false }
    }
    if (record.turn_id && record.turn_id !== retained.turnId) {
      return { observed_exit: false }
    }
    if (retained.lease.runtime.hasProviderTerminalObserved?.()) {
      return { observed_exit: false, provider_terminal_observed: true }
    }
    // The original execute() call can unwind as close() rejects its pending
    // RPC.  Retain before signaling so that unwind cannot recycle or release
    // this lease while the signed operation is still classifying the exit.
    const retainLeaseBeforeManualDispatch = retained.retainLease
    retained.retainLease = true
    let observedExit: boolean | void
    try {
      observedExit = await retained.lease.runtime.forceTerminateForAuthorizedOperation(pid)
    } catch (error) {
      if (error instanceof AppServerRuntimeError
          && error.code === 'APP_SERVER_PROVIDER_TERMINAL_OBSERVED') {
        // The provider event arrived after the preflight above but before the
        // runtime could signal.  Restore ordinary lease release so the
        // naturally completed turn can finish its normal cleanup.
        retained.retainLease = retainLeaseBeforeManualDispatch
        return { observed_exit: false, provider_terminal_observed: true }
      }
      throw error
    }
    // An authorized signal dispatch is not proof that the app-server child
    // exited.  Keep the retained lease for reconciliation unless the runtime
    // explicitly observed its process exit event.
    if (observedExit !== true) return { observed_exit: false }
    retained.lease.runtime.markObservedTerminal?.()
    retained.lease.release(false)
    if (this.taskRuntimeLeases.get(taskId) === retained) this.taskRuntimeLeases.delete(taskId)
    return { observed_exit: true }
  }

  async reconcile(options: {
    taskId: string
    request: TaskRequest
    record: StoredTaskRecord
    signal: AbortSignal
  }): Promise<ReconciliationResult> {
    const threadId = options.record.thread_id
    if (!threadId) return { status: 'unknown', threadId: '' }
    if (!options.record.turn_id) return { status: 'unknown', threadId }
    const context = await this.buildContext(options.request)
    if (options.record.app_server_lane_key && options.record.app_server_lane_key !== context.lane.key) {
      return { status: 'unknown', threadId }
    }
    const releaseThread = await this.locks.acquire(`thread:${threadId}`, options.signal)
    let lease: Awaited<ReturnType<AppServerPool['acquire']>> | undefined
    try {
      const retained = this.taskRuntimeLeases.get(options.taskId)
      if (retained && retained.threadId === threadId && retained.turnId === options.record.turn_id) {
        const result = await this.reconcileThread(
          retained.lease.runtime,
          threadId,
          options.record.turn_id,
          context.model,
          retained.lease.instanceId,
          context.lane.key,
        )
        if (result.status !== 'unknown') {
          retained.lease.runtime.markObservedTerminal?.()
          retained.lease.release(retained.lease.runtime.isHealthy())
          if (this.taskRuntimeLeases.get(options.taskId) === retained) this.taskRuntimeLeases.delete(options.taskId)
        }
        return result
      }
      // Persisted threads are app-server state, not process-local state. Any healthy instance in
      // the same credential/config lane may inspect the thread after a Worker or child restart.
      lease = await this.pool.acquireForThread(context.lane, threadId, options.signal)
      return await this.reconcileThread(
        lease.runtime,
        threadId,
        options.record.turn_id,
        context.model,
        lease.instanceId,
        context.lane.key,
      )
    } catch {
      return { status: 'unknown', threadId }
    } finally {
      lease?.release(lease.runtime.isHealthy())
      releaseThread()
    }
  }

  metrics(): Record<string, unknown> {
    return {
      pool: this.pool.metrics(),
      execution_locks: this.locks.metrics(),
    }
  }

  isDraining(): boolean {
    return this.pool.isDraining()
  }

  async drain(timeoutMs: number): Promise<void> {
    await this.pool.drain(timeoutMs)
  }

  private async reconcileThread(
    runtime: Awaited<ReturnType<AppServerPool['acquire']>>['runtime'],
    threadId: string,
    turnId: string,
    model: string,
    instanceId: string,
    laneKey: string,
  ): Promise<ReconciliationResult> {
    const thread = await runtime.readThread(threadId, true)
    const turns = Array.isArray(thread.turns)
      ? thread.turns.filter(isRecord)
      : []
    const turn = turns.find(candidate => readString(candidate.id) === turnId)
    if (!turn) return { status: 'unknown', threadId }
    const status = reconcileTurnStatus(turn.status)
    if (status === 'unknown') return { status, threadId, turnId: readString(turn.id) }
    const assistantText = status === 'completed' ? extractAssistantText(turn) : undefined
    const capabilityFailure = status === 'completed'
      ? detectToolCapabilityFailure(assistantText)
      : undefined
    return {
      status: capabilityFailure ? 'failed' : status,
      threadId,
      turnId: readString(turn.id),
      assistantText: capabilityFailure ? undefined : assistantText,
      model,
      instanceId,
      laneKey,
      errorCode: capabilityFailure || (status === 'failed' ? stableAppServerTurnErrorCode(turn.error) : undefined),
    }
  }

  async readDefaultRateLimits(refresh = false): Promise<PoolRateLimitsView> {
    const codexHome = await this.resolveCodexHome(undefined)
    const lane = await buildAppServerLane({
      cliVersion: VALIDATED_APP_SERVER_CLI_VERSION,
      codexHome,
    })
    return this.pool.readRateLimits(lane, refresh)
  }

  private async buildContext(request: TaskRequest): Promise<{
    model: string
    reasoningEffort?: string
    codexConfig: Record<string, unknown>
    cwd: string
    lane: Awaited<ReturnType<typeof buildAppServerLane>>
  }> {
    const effectiveCwd = request.cwd || process.cwd()
    const cwd = resolveAllowedWorkingPath(effectiveCwd, this.config.allowedCwds, workerPrivatePaths(this.config))
    if (!cwd) throw workingDirectoryNotAllowed()
    const resolved = resolveSupportedModelAlias(request.model || this.config.defaultModel, this.config.modelAliases)
    const parsed = parseModelString(resolved)
    const codexHome = await this.resolveCodexHome(request.codex_home_key)
    const apiKey = request.api_key || this.config.openaiApiKey || undefined
    const baseUrl = request.base_url || this.config.openaiBaseUrl || undefined
    const codexConfig = buildCodexConfig(
      request,
      parsed.reasoningEffort,
      this.config.imageGenerationMode === 'local',
    )
    if (baseUrl) codexConfig.openai_base_url = baseUrl
    const lane = await buildAppServerLane({
      cliVersion: VALIDATED_APP_SERVER_CLI_VERSION,
      apiKey,
      baseUrl,
      codexHome,
    })
    return { model: parsed.model, reasoningEffort: parsed.reasoningEffort, codexConfig, cwd, lane }
  }

  private async acquireExecutionLocks(request: TaskRequest, signal: AbortSignal): Promise<Array<() => void>> {
    const releases: Array<() => void> = []
    try {
      if (request.session_id) releases.push(await this.locks.acquire(`thread:${request.session_id}`, signal))
      return releases
    } catch (error) {
      for (const release of releases.reverse()) release()
      throw error
    }
  }

  private assertCanonicalCwdUnchanged(cwd: string): void {
    const resolved = resolveAllowedWorkingPath(cwd, this.config.allowedCwds, workerPrivatePaths(this.config))
    if (!resolved || normalizeCwd(resolved) !== normalizeCwd(cwd)) throw workingDirectoryNotAllowed()
  }

  private async resolveCodexHome(key: string | undefined): Promise<string> {
    assertCodexHomeIsolation(this.config)
    if (!key) {
      if (!this.config.codexHome) {
        const error = new Error('An isolated service CODEX_HOME is required') as Error & { code: string }
        error.code = 'CODEX_HOME_MISSING'
        throw error
      }
      await fs.mkdir(this.config.codexHome, { recursive: true, mode: 0o700 })
      await fs.chmod(this.config.codexHome, 0o700)
      assertCodexHomeIsolation(this.config)
      return fs.realpath(this.config.codexHome)
    }
    if (!this.config.codexBizHomeRoot) throw new Error('CODEX_BIZ_HOME_ROOT_REQUIRED')
    await fs.mkdir(this.config.codexBizHomeRoot, { recursive: true, mode: 0o700 })
    await fs.chmod(this.config.codexBizHomeRoot, 0o700)
    assertCodexHomeIsolation(this.config)
    const canonicalRoot = await fs.realpath(this.config.codexBizHomeRoot)
    const prefix = key.replace(/[^A-Za-z0-9._-]+/g, '_').replace(/^_+|_+$/g, '').slice(0, 80) || 'codex-home'
    const digest = crypto.createHash('sha256').update(key).digest('hex').slice(0, 16)
    const candidate = path.join(canonicalRoot, `${prefix}-${digest}`)
    await fs.mkdir(candidate, { recursive: true, mode: 0o700 })
    await fs.chmod(candidate, 0o700)
    const contained = resolveContainedHomePath(canonicalRoot, candidate)
    if (!contained) {
      const error = new Error('Codex Biz home escaped its configured root') as Error & { code: string }
      error.code = 'CODEX_HOME_NOT_ISOLATED'
      throw error
    }
    return contained
  }
}

export function buildCodexConfig(
  request: TaskRequest,
  effort: string | undefined,
  imageGenerationEnabled = false,
): Record<string, unknown> {
  const result: Record<string, unknown> = {
    tool_output_token_limit: 10_000,
    model_auto_compact_token_limit: 140_000,
    ...requireCodexConfigOverride(request.codex_config),
    approval_policy: 'never',
    'features.default_mode_request_user_input': true,
    // Request payloads cannot turn this capability on. It is controlled only by the Worker
    // operator because image results require local persistence and a bounded metadata bridge.
    'features.image_generation': imageGenerationEnabled,
    'notice.hide_rate_limit_model_nudge': true,
  }
  for (const key of ['model_context_window', 'model_auto_compact_token_limit', 'tool_output_token_limit']) {
    const value = request.env_vars?.[key]
    if (value === undefined || value === '') continue
    const numeric = Number(value)
    result[key] = Number.isNaN(numeric) ? value : numeric
  }
  if (effort) result.model_reasoning_effort = effort
  if (request.network_access_enabled !== undefined) {
    const existing = asRecord(result.sandbox_workspace_write) || {}
    result.sandbox_workspace_write = { ...existing, network_access: request.network_access_enabled }
  }
  if (request.web_search_mode) result.web_search = request.web_search_mode
  return result
}

async function materializeInput(taskId: string, request: TaskRequest, stateDir: string): Promise<{
  input: CodexInput
  cleanup: () => Promise<void>
}> {
  if (!request.images?.length) return { input: request.prompt, cleanup: async () => undefined }
  const root = materializedInputRoot(taskId, stateDir)
  await fs.mkdir(root, { recursive: true, mode: 0o700 })
  await fs.chmod(root, 0o700)
  try {
    const input: Exclude<CodexInput, string> = [{ type: 'text', text: request.prompt }]
    for (const [index, image] of request.images.entries()) {
      const extension = extensionFor(image.name, image.mime_type)
      const file = path.join(root, `${index}${extension}`)
      const payload = image.data.includes(',') && image.data.startsWith('data:')
        ? image.data.slice(image.data.indexOf(',') + 1)
        : image.data
      const bytes = Buffer.from(payload, 'base64')
      if (bytes.length === 0 || bytes.length > 15 * 1024 * 1024) throw new Error('INVALID_IMAGE_PAYLOAD')
      await fs.writeFile(file, bytes, { mode: 0o600 })
      input.push({ type: 'local_image', path: file })
    }
    return { input, cleanup: async () => cleanupMaterializedInput(taskId, stateDir) }
  } catch (error) {
    await cleanupMaterializedInput(taskId, stateDir)
    throw error
  }
}

export async function cleanupMaterializedInput(taskId: string, stateDir: string): Promise<void> {
  const root = materializedInputRoot(taskId, stateDir)
  try {
    await fs.access(root)
  } catch {
    return
  }
  await fs.rm(root, { recursive: true, force: true })
  await syncParentDirectory(root)
}

function materializedInputRoot(taskId: string, stateDir: string): string {
  return path.join(stateDir, 'input', crypto.createHash('sha256').update(taskId).digest('hex'))
}

function extensionFor(name: string, mime: string | undefined): string {
  const extension = path.extname(name).toLowerCase()
  if (/^\.[a-z0-9]{1,8}$/.test(extension)) return extension
  if (mime === 'image/jpeg') return '.jpg'
  if (mime === 'image/webp') return '.webp'
  if (mime === 'image/gif') return '.gif'
  return '.png'
}

function normalizeCwd(cwd: string): string {
  const normalized = path.resolve(cwd)
  return process.platform === 'win32' ? normalized.toLowerCase() : normalized
}

function workingDirectoryNotAllowed(): Error & { code: string } {
  const error = new Error('Working directory is outside the configured allowlist') as Error & { code: string }
  error.code = 'WORKING_DIRECTORY_NOT_ALLOWED'
  return error
}

function normalizeTurnStatus(value: unknown): ExecutionResult['status'] {
  return value === 'completed' || value === 'failed' || value === 'interrupted' ? value : 'failed'
}

function shouldRetainLeaseForUnverifiedTurn(error: unknown): boolean {
  return error instanceof AppServerRuntimeError && error.turnMayHaveStarted
}

function preferredTurnFailure(primary: string | undefined, secondary: string | undefined): string | undefined {
  if (primary && primary !== 'APP_SERVER_TURN_FAILED') return primary
  if (secondary && secondary !== 'APP_SERVER_TURN_FAILED') return secondary
  return primary || secondary
}

function reconcileTurnStatus(value: unknown): ReconciliationResult['status'] {
  if (value === 'completed' || value === 'failed' || value === 'interrupted') return value
  return 'unknown'
}

function extractAssistantText(turn: Record<string, unknown>): string | undefined {
  const items = Array.isArray(turn.items) ? turn.items.filter(isRecord) : []
  for (let index = items.length - 1; index >= 0; index--) {
    const item = items[index]
    if (item?.type === 'agentMessage' && typeof item.text === 'string' && item.text.trim()) {
      return item.text
    }
  }
  return undefined
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return isRecord(value) ? value : undefined
}

function readString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined
}

/**
 * Stable, non-secret identity of the exact pool runtime that owns a task.
 * It supplements the OS PID so a recycled PID cannot satisfy a previously
 * authorized manual termination operation.
 */
export function appServerProcessIdentity(instanceId: string): string {
  return `app-server-instance:${instanceId}`
}
