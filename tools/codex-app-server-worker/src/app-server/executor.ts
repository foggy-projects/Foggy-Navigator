import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import type { AppConfig } from '../config.js'
import { requireCodexConfigOverride } from '../codex-config.js'
import type { CodexInput, StoredTaskRecord, TaskRequest } from '../models.js'
import { parseModelString, resolveModelAlias } from '../model-resolution.js'
import { EventBroadcast } from '../persistence/event-store.js'
import { syncParentDirectory } from '../persistence/jsonl-durability.js'
import {
  assertCodexHomeIsolation,
  resolveAllowedWorkingPath,
  resolveContainedHomePath,
} from '../path-guards.js'
import { AppServerEventBridge } from './event-bridge.js'
import { KeyedExecutionLocks } from './execution-locks.js'
import { buildAppServerLane } from './lane.js'
import { AppServerPool, type PoolMetrics } from './pool.js'
import {
  isAppServerProcessTreeSafetyError,
  VALIDATED_APP_SERVER_CLI_VERSION,
} from './runtime.js'

export type ExecutionCallbacks = {
  onInstanceResolved: (instanceId: string, laneKey: string) => void | Promise<void>
  onThreadResolved: (threadId: string) => void | Promise<void>
  onExecutionCommitted: (threadId: string) => void | Promise<void>
  onTurnStarted: (threadId: string, turnId: string | undefined) => void | Promise<void>
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
}

export type ReconciliationResult = {
  status: 'completed' | 'failed' | 'interrupted' | 'unknown'
  threadId: string
  turnId?: string
  assistantText?: string
  model?: string
  instanceId?: string
  laneKey?: string
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
}

export class StrictAppServerExecutor implements TaskExecutor {
  private readonly pool: AppServerPool
  private readonly locks: KeyedExecutionLocks

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
    const releases = await this.acquireExecutionLocks(options.request, context.cwd, options.signal)
    let inputFiles: Awaited<ReturnType<typeof materializeInput>> | undefined
    let lease: Awaited<ReturnType<AppServerPool['acquire']>> | undefined
    try {
      inputFiles = await materializeInput(options.taskId, options.request, this.config.stateDir)
      this.assertCanonicalCwdUnchanged(context.cwd)
      lease = await this.pool.acquire(context.lane, options.signal)
      await options.callbacks.onInstanceResolved(lease.instanceId, context.lane.key)
      const bridge = new AppServerEventBridge({
        taskId: options.taskId,
        broadcast: options.broadcast,
        rootThreadId: options.request.session_id,
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
        onThreadResolved: async threadId => {
          bridge.setRootThreadId(threadId)
          await options.callbacks.onThreadResolved(threadId)
        },
        onExecutionCommitted: options.callbacks.onExecutionCommitted,
        onTurnStarted: async (threadId, turnId) => {
          if (turnId) bridge.setRootTurnId(turnId)
          await options.callbacks.onTurnStarted(threadId, turnId)
        },
        onNotification: notification => bridge.handle(notification),
      })
      const turnId = readString(result.turn.id)
      const bridged = bridge.getResult()
      const status = bridged.terminalFailure ? 'failed' : normalizeTurnStatus(result.turn.status)
      return {
        threadId: result.threadId,
        turnId,
        status,
        assistantText: bridged.assistantText,
        inputTokens: bridged.inputTokens,
        outputTokens: bridged.outputTokens,
        model: context.model,
        durationMs: Date.now() - startedAt,
      }
    } catch (error) {
      if (isAppServerProcessTreeSafetyError(error)) {
        this.pool.failClosed(error instanceof Error ? error : new Error(String(error)))
      }
      throw error
    } finally {
      lease?.release(lease.runtime.isHealthy())
      await inputFiles?.cleanup()
      for (const release of releases.reverse()) release()
    }
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
    let retireLease = false
    try {
      lease = await this.pool.acquire(context.lane, options.signal)
      const thread = await lease.runtime.readThread(threadId, true)
      const turns = Array.isArray(thread.turns)
        ? thread.turns.filter(isRecord)
        : []
      const turn = turns.find(candidate => readString(candidate.id) === options.record.turn_id)
      if (!turn) return { status: 'unknown', threadId }
      let status = reconcileTurnStatus(turn.status)
      let reconciledTurn = turn
      if (status === 'unknown' && options.record.abort_requested_at) {
        if (!lease.runtime.interruptTurn) return { status: 'unknown', threadId, turnId: readString(turn.id) }
        retireLease = true
        await lease.runtime.interruptTurn(threadId, options.record.turn_id)
        const deadline = Date.now() + this.config.abortWaitTimeoutMs
        while (status === 'unknown' && Date.now() < deadline) {
          await abortableDelay(100, options.signal)
          const refreshed = await lease.runtime.readThread(threadId, true)
          const refreshedTurns = Array.isArray(refreshed.turns) ? refreshed.turns.filter(isRecord) : []
          const exact = refreshedTurns.find(candidate => readString(candidate.id) === options.record.turn_id)
          if (!exact) break
          reconciledTurn = exact
          status = reconcileTurnStatus(exact.status)
        }
      }
      if (status === 'unknown') return { status, threadId, turnId: readString(turn.id) }
      retireLease = false
      return {
        status,
        threadId,
        turnId: readString(reconciledTurn.id),
        assistantText: status === 'completed' ? extractAssistantText(reconciledTurn) : undefined,
        model: context.model,
        instanceId: lease.instanceId,
        laneKey: context.lane.key,
      }
    } catch {
      return { status: 'unknown', threadId }
    } finally {
      lease?.release(lease.runtime.isHealthy() && !retireLease)
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

  private async buildContext(request: TaskRequest): Promise<{
    model: string
    reasoningEffort?: string
    codexConfig: Record<string, unknown>
    cwd: string
    lane: Awaited<ReturnType<typeof buildAppServerLane>>
  }> {
    const effectiveCwd = request.cwd || process.cwd()
    const cwd = resolveAllowedWorkingPath(effectiveCwd, this.config.allowedCwds)
    if (!cwd) throw workingDirectoryNotAllowed()
    const resolved = resolveModelAlias(request.model || this.config.defaultModel, this.config.modelAliases)
    const parsed = parseModelString(resolved)
    const codexHome = await this.resolveCodexHome(request.codex_home_key)
    const apiKey = request.api_key || this.config.openaiApiKey || undefined
    const baseUrl = request.base_url || this.config.openaiBaseUrl || undefined
    const codexConfig = buildCodexConfig(request, parsed.reasoningEffort)
    if (baseUrl) codexConfig.openai_base_url = baseUrl
    const lane = await buildAppServerLane({
      cliVersion: VALIDATED_APP_SERVER_CLI_VERSION,
      apiKey,
      baseUrl,
      codexHome,
    })
    return { model: parsed.model, reasoningEffort: parsed.reasoningEffort, codexConfig, cwd, lane }
  }

  private async acquireExecutionLocks(request: TaskRequest, cwd: string, signal: AbortSignal): Promise<Array<() => void>> {
    const releases: Array<() => void> = []
    try {
      if (request.session_id) releases.push(await this.locks.acquire(`thread:${request.session_id}`, signal))
      if ((request.sandbox_mode || 'danger-full-access') !== 'read-only') {
        releases.push(await this.locks.acquire(`cwd:${normalizeCwd(cwd)}`, signal))
      }
      return releases
    } catch (error) {
      for (const release of releases.reverse()) release()
      throw error
    }
  }

  private assertCanonicalCwdUnchanged(cwd: string): void {
    const resolved = resolveAllowedWorkingPath(cwd, this.config.allowedCwds)
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

function buildCodexConfig(request: TaskRequest, effort: string | undefined): Record<string, unknown> {
  const result: Record<string, unknown> = {
    tool_output_token_limit: 10_000,
    model_auto_compact_token_limit: 140_000,
    ...requireCodexConfigOverride(request.codex_config),
    approval_policy: 'never',
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

function abortableDelay(delayMs: number, signal: AbortSignal): Promise<void> {
  if (signal.aborted) return Promise.reject(abortError())
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', abort)
      resolve()
    }, delayMs)
    const abort = (): void => {
      clearTimeout(timer)
      reject(abortError())
    }
    signal.addEventListener('abort', abort, { once: true })
  })
}

function abortError(): Error {
  const error = new Error('Reconciliation aborted')
  error.name = 'AbortError'
  return error
}
