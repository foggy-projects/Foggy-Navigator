import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process'
import { createHash, randomUUID } from 'node:crypto'
import fs from 'node:fs/promises'
import { createRequire } from 'node:module'
import path from 'node:path'
import readline from 'node:readline'
import { fileURLToPath } from 'node:url'
import type { CodexApprovalPolicy, CodexInput, CodexSandboxMode } from '../models.js'
import type { AppServerNotification } from './native-subtask-tracker.js'
import {
  isAccountRateLimitsUpdated,
  parseAccountRateLimitsRead,
  type SafeAccountRateLimits,
} from './rate-limits.js'
import {
  parseUserInputServerRequest,
  requestIdKey,
  USER_INPUT_SERVER_METHOD,
  type AppServerServerRequest,
  type UserInputServerRequest,
  type UserInputWireResponse,
} from './user-input.js'

const moduleRequire = createRequire(import.meta.url)
const DEFAULT_REQUEST_TIMEOUT_MS = 30_000
const DEFAULT_TURN_STALL_TIMEOUT_MS = 15 * 60_000
const RATE_LIMITS_REQUEST_TIMEOUT_MS = 5_000
const LOADED_THREADS_REQUEST_TIMEOUT_MS = 2_000
const THREAD_UNSUBSCRIBE_TIMEOUT_MS = 5_000
const MAX_PENDING_TURN_NOTIFICATIONS = 10_000
const PROCESS_TREE_HELPER_TIMEOUT_MS = 15_000
const PROCESS_TREE_POLL_INTERVAL_MS = 25
const PROCESS_TREE_EXIT_CLEAN = 0
const PROCESS_TREE_EXIT_ALIVE = 10
const PROCESS_TREE_ROOT_EXIT_TIMEOUT_MS = 500
const PROCESS_TREE_GRACEFUL_EXIT_MAX_MS = 2_000
const processTreeHelper = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
  'scripts',
  'process-tree.mjs',
)
export const VALIDATED_APP_SERVER_CLI_VERSION = '0.144.3'

type JsonRpcId = number

type PendingRequest = {
  resolve: (value: Record<string, unknown>) => void
  reject: (error: Error) => void
  timer: NodeJS.Timeout
}

type Deferred<T> = {
  promise: Promise<T>
  resolve: (value: T) => void
  reject: (error: Error) => void
}

/**
 * An authorized close has two separate facts: signals may have been sent, and
 * the child may (or may not) have emitted an exit event.  Keeping that result
 * available to the active turn prevents close()'s generic connection error
 * from racing ahead of the evidence classification.
 */
type AuthorizedProcessTerminationOutcome = {
  observedExit: boolean
}

type AuthorizedProcessTermination = {
  completion: Deferred<AuthorizedProcessTerminationOutcome>
}

type ActiveTurnContext = {
  id: string
  threadId?: string
  turnId?: string
  turnRequestIssued: boolean
  providerTerminalObserved: boolean
  onNotification?: (notification: AppServerNotification) => void
  onServerRequest?: (request: UserInputServerRequest) => Promise<UserInputWireResponse>
  onFatal?: (error: Error) => void
  settleAuthorizedTermination?: (outcome: AuthorizedProcessTerminationOutcome) => void
}

type UnverifiedTurn = {
  threadId?: string
  turnId?: string
}

type RequestOptions = {
  timeoutMs?: number
  fatalOnTimeout?: boolean
}

export type AppServerProcess = Pick<
  ChildProcessWithoutNullStreams,
  'stdin' | 'stdout' | 'stderr' | 'pid' | 'killed' | 'kill' | 'on' | 'once'
>

export type SpawnAppServerProcess = (options: {
  cwd?: string
  env: Record<string, string>
  ephemeralApiKeyAuth: boolean
}) => AppServerProcess

export type AppServerTurnOptions = {
  taskId: string
  model: string
  reasoningEffort?: string
  cwd?: string
  threadId?: string
  approvalPolicy?: CodexApprovalPolicy
  sandboxMode: CodexSandboxMode
  codexConfig: Record<string, unknown>
  developerInstructions?: string
  outputSchema?: Record<string, unknown>
  input: CodexInput
  env: Record<string, string>
  apiKey?: string
  signal: AbortSignal
  onNotification: (notification: AppServerNotification) => void
  onUserInputRequest?: (request: UserInputServerRequest) => Promise<UserInputWireResponse>
  onUserInputResolved?: (resolution: { requestId: string | number; threadId: string }) => void | Promise<void>
  onThreadResolved?: (threadId: string) => void | Promise<void>
  onExecutionCommitted?: (threadId: string) => void | Promise<void>
  onTurnStarted?: (threadId: string, turnId: string | undefined) => void | Promise<void>
  onProcessStarted?: (pid: number | undefined) => void
  spawnProcess?: SpawnAppServerProcess
  requestTimeoutMs?: number
  interruptTimeoutMs?: number
  turnStallTimeoutMs?: number
}

export type PersistentTurnOptions = Omit<
  AppServerTurnOptions,
  'env' | 'apiKey' | 'spawnProcess' | 'requestTimeoutMs' | 'onProcessStarted'
>

export type AppServerCompactOptions = Pick<
  PersistentTurnOptions,
  | 'model'
  | 'cwd'
  | 'approvalPolicy'
  | 'sandboxMode'
  | 'codexConfig'
  | 'developerInstructions'
  | 'signal'
  | 'onNotification'
> & {
  threadId: string
  interruptTimeoutMs?: number
}

export type AppServerTurnResult = {
  threadId: string
  turn: Record<string, unknown>
}

export class AppServerRuntimeError extends Error {
  readonly code: string
  readonly executionCommitted: boolean
  readonly turnMayHaveStarted: boolean
  readonly threadId?: string
  readonly turnId?: string
  readonly reason: 'runtime' | 'aborted' | 'unsupported' | 'stalled' | 'protocol'

  constructor(
    message: string,
    options: {
      executionCommitted: boolean
      turnMayHaveStarted?: boolean
      threadId?: string
      turnId?: string
      reason?: AppServerRuntimeError['reason']
      code?: string
      cause?: unknown
    },
  ) {
    super(message, { cause: options.cause })
    this.name = 'AppServerRuntimeError'
    this.code = options.code || 'APP_SERVER_RUNTIME_FAILED'
    this.executionCommitted = options.executionCommitted
    this.turnMayHaveStarted = options.turnMayHaveStarted === true
    this.threadId = options.threadId
    this.turnId = options.turnId
    this.reason = options.reason || 'runtime'
  }
}

export class AppServerProcessTreeSafetyError extends Error {
  readonly code = 'APP_SERVER_PROCESS_TREE_UNSAFE'

  constructor(message = 'Codex app-server process-tree cleanup could not be proven', options?: ErrorOptions) {
    super(message, options)
    this.name = 'AppServerProcessTreeSafetyError'
  }
}

export class AppServerRpcError extends Error {
  constructor(
    readonly code: number,
    message: string,
    readonly data?: unknown,
  ) {
    super(message)
    this.name = 'AppServerRpcError'
  }
}

export function isAppServerProcessTreeSafetyError(error: unknown): boolean {
  const visited = new Set<unknown>()
  const pending: unknown[] = [error]
  while (pending.length > 0) {
    const current = pending.pop()
    if (current === undefined || current === null || visited.has(current)) continue
    visited.add(current)
    if (current instanceof AppServerProcessTreeSafetyError) return true
    if (current instanceof AggregateError) pending.push(...current.errors)
    if (current instanceof Error && current.cause !== undefined) pending.push(current.cause)
  }
  return false
}

export function isPreTurnAppServerFailure(error: unknown): error is AppServerRuntimeError {
  return error instanceof AppServerRuntimeError
    && !error.executionCommitted
    && error.reason !== 'aborted'
}

export function resolveBundledCodexLauncher(): string {
  return moduleRequire.resolve('@openai/codex/bin/codex.js')
}

export function resolveBundledCodexVersion(): string | undefined {
  try {
    const packageJson = moduleRequire('@openai/codex/package.json') as { version?: unknown }
    return typeof packageJson.version === 'string' ? packageJson.version : undefined
  } catch {
    return undefined
  }
}

export function isValidatedAppServerVersion(version: string | undefined): boolean {
  return version === VALIDATED_APP_SERVER_CLI_VERSION
}

export function spawnBundledAppServer(options: {
  cwd?: string
  env: Record<string, string>
  ephemeralApiKeyAuth: boolean
}): AppServerProcess {
  return spawn(process.execPath, buildBundledAppServerArgs(options.ephemeralApiKeyAuth), {
    cwd: options.cwd,
    env: options.env,
    stdio: ['pipe', 'pipe', 'pipe'],
    windowsHide: true,
  })
}

export function buildBundledAppServerArgs(ephemeralApiKeyAuth: boolean): string[] {
  const args = [resolveBundledCodexLauncher(), 'app-server', '--stdio']
  if (ephemeralApiKeyAuth) {
    args.push('--config', 'cli_auth_credentials_store="ephemeral"')
  }
  return args
}

export class AppServerRuntimeInstance {
  private healthy = true
  private readonly activeTurns = new Set<ActiveTurnContext>()
  private readonly activeThreadIds = new Set<string>()
  /** A turn may still be running even though this Worker lost observability. */
  private readonly unverifiedTurns = new Map<string, UnverifiedTurn>()
  /** Provider-terminal evidence remains addressable until task cleanup consumes it. */
  private readonly observedProviderTerminals = new Map<string, UnverifiedTurn>()
  private readonly serverRequestOwners = new Map<string, ActiveTurnContext>()
  private readonly fatalHandlers = new Set<(error: Error) => void>()
  private readonly rateLimitsUpdatedHandlers = new Set<() => void>()
  private authorizedTermination?: AuthorizedProcessTermination

  private constructor(private readonly client: AppServerJsonRpcClient) {
    client.onFatal(error => this.markFatal(error))
    client.onNotification(notification => {
      if (isAccountRateLimitsUpdated(notification.method, notification.params)) {
        this.emitRateLimitsUpdated()
      }
      this.routeNotification(notification)
    })
    client.onServerRequest(request => this.routeServerRequest(request))
  }

  static async start(options: {
    env: Record<string, string>
    apiKey?: string
    cwd?: string
    spawnProcess?: SpawnAppServerProcess
    requestTimeoutMs?: number
    signal?: AbortSignal
    processTreeStateDir?: string
    processTreeEntry?: string
  }): Promise<AppServerRuntimeInstance> {
    let client: AppServerJsonRpcClient
    try {
      const child = (options.spawnProcess || spawnBundledAppServer)({
        cwd: options.cwd,
        env: options.env,
        ephemeralApiKeyAuth: Boolean(options.apiKey),
      })
      const processTree = options.processTreeStateDir
        ? await AppServerProcessTree.capture({
          child,
          entry: options.processTreeEntry || resolveBundledCodexLauncher(),
          stateDir: options.processTreeStateDir,
        })
        : undefined
      client = new AppServerJsonRpcClient(
        child,
        options.requestTimeoutMs || DEFAULT_REQUEST_TIMEOUT_MS,
        processTree,
      )
    } catch (error) {
      throw new AppServerRuntimeError(readErrorMessage(error), {
        executionCommitted: false,
        cause: error,
      })
    }
    const instance = new AppServerRuntimeInstance(client)
    const abortStartup = (): void => { void instance.close(0).catch(() => undefined) }
    if (options.signal?.aborted) abortStartup()
    else options.signal?.addEventListener('abort', abortStartup, { once: true })
    try {
      await client.request('initialize', {
        clientInfo: {
          name: 'foggy_navigator_codex_app_server_worker',
          title: 'Foggy Navigator Codex App Server Worker',
          version: '1',
        },
        capabilities: {
          experimentalApi: true,
          requestAttestation: false,
        },
      })
      client.notify('initialized')
      if (options.apiKey) await loginWithEphemeralApiKey(client, options.apiKey)
      return instance
    } catch (error) {
      instance.healthy = false
      await instance.close(options.signal?.aborted ? 0 : undefined)
      throw new AppServerRuntimeError(readErrorMessage(error), {
        executionCommitted: false,
        reason: options.signal?.aborted ? 'aborted' : 'runtime',
        cause: error,
      })
    } finally {
      options.signal?.removeEventListener('abort', abortStartup)
    }
  }

  get pid(): number | undefined {
    return this.client.pid
  }

  isHealthy(): boolean {
    return this.healthy && !this.client.isFailed()
  }

  isActive(): boolean {
    return this.activeTurns.size > 0
  }

  requiresAttention(): boolean {
    return this.unverifiedTurns.size > 0
  }

  hasProviderTerminalObserved(threadId?: string, turnId?: string): boolean {
    return [...this.activeTurns].some(context => (
      context.providerTerminalObserved
      && (!threadId || context.threadId === threadId)
      && (!turnId || context.turnId === turnId)
    )) || [...this.observedProviderTerminals.values()].some(observed => (
      (!threadId || observed.threadId === threadId)
      && (!turnId || observed.turnId === turnId)
    ))
  }

  /** Called only after thread/read or a verified manual kill proves the turn ended. */
  markObservedTerminal(threadId?: string, turnId?: string): void {
    if (!threadId && !turnId && this.unverifiedTurns.size === 1) {
      this.unverifiedTurns.clear()
    }
    for (const [key, unverified] of this.unverifiedTurns) {
      if ((!threadId || unverified.threadId === threadId) && (!turnId || unverified.turnId === turnId)) {
        this.unverifiedTurns.delete(key)
      }
    }
    for (const [key, observed] of this.observedProviderTerminals) {
      if ((!threadId || observed.threadId === threadId) && (!turnId || observed.turnId === turnId)) {
        this.observedProviderTerminals.delete(key)
      }
    }
  }

  onFatal(handler: (error: Error) => void): () => void {
    this.fatalHandlers.add(handler)
    return () => this.fatalHandlers.delete(handler)
  }

  onRateLimitsUpdated(handler: () => void): () => void {
    this.rateLimitsUpdatedHandlers.add(handler)
    return () => this.rateLimitsUpdatedHandlers.delete(handler)
  }

  async readAccountRateLimits(timeoutMs = RATE_LIMITS_REQUEST_TIMEOUT_MS): Promise<SafeAccountRateLimits> {
    if (!this.isHealthy()) throw new Error('Codex app-server instance is unavailable')
    const response = await this.client.request('account/rateLimits/read', undefined, {
      timeoutMs,
      fatalOnTimeout: false,
    })
    return parseAccountRateLimitsRead(response)
  }

  private markFatal(error: Error): void {
    this.healthy = false
    for (const context of this.activeTurns) context.onFatal?.(error)
    for (const handler of this.fatalHandlers) {
      try {
        handler(error)
      } catch {
        // Fatal observers must not turn a child-process failure into a Worker crash.
      }
    }
  }

  private emitRateLimitsUpdated(): void {
    for (const handler of this.rateLimitsUpdatedHandlers) {
      try {
        handler()
      } catch {
        // Account observability must never fail the runtime transport.
      }
    }
  }

  private routeNotification(notification: AppServerNotification): void {
    const params = notification.params || {}
    if (notification.method === 'serverRequest/resolved') {
      const requestId = params.requestId
      if (typeof requestId !== 'string' && !Number.isSafeInteger(requestId)) return
      const key = requestIdKey(requestId as string | number)
      const owner = this.serverRequestOwners.get(key)
      if (!owner || params.threadId !== owner.threadId) return
      owner.onNotification?.(notification)
      this.serverRequestOwners.delete(key)
      return
    }

    const threadId = readString(params.threadId)
    if (!threadId) return
    const turnId = readString(params.turnId) || readString(asRecord(params.turn)?.id)
    for (const context of this.activeTurns) {
      if (context.threadId !== threadId) continue
      if (turnId && context.turnId && context.turnId !== turnId) continue
      if (turnId && !context.turnId && !context.turnRequestIssued) continue
      context.onNotification?.(notification)
    }
  }

  private async routeServerRequest(request: AppServerServerRequest): Promise<Record<string, unknown>> {
    const parsed = parseUserInputServerRequest(request)
    let owner = [...this.activeTurns].find(context => (
      context.threadId === parsed.threadId && context.turnId === parsed.turnId
    ))
    if (!owner) {
      const pending = [...this.activeTurns].filter(context => (
        context.threadId === parsed.threadId && !context.turnId && context.turnRequestIssued
      ))
      if (pending.length === 1) owner = pending[0]
    }
    if (!owner?.onServerRequest) throw new Error('USER_INPUT_REQUEST_AFFINITY_MISMATCH')
    this.serverRequestOwners.set(requestIdKey(parsed.requestId), owner)
    try {
      return await owner.onServerRequest(parsed)
    } catch (error) {
      this.serverRequestOwners.delete(requestIdKey(parsed.requestId))
      throw error
    }
  }

  private bindContextThread(context: ActiveTurnContext, threadId: string): void {
    if (context.threadId === threadId) return
    if (this.activeThreadIds.has(threadId)) {
      throw new AppServerRuntimeError('Codex app-server thread already has an active root turn', {
        executionCommitted: false,
        threadId,
        code: 'APP_SERVER_THREAD_ALREADY_ACTIVE',
      })
    }
    if (context.threadId) this.activeThreadIds.delete(context.threadId)
    context.threadId = threadId
    this.activeThreadIds.add(threadId)
  }

  async readThread(threadId: string, includeTurns = true): Promise<Record<string, unknown>> {
    if (!this.isHealthy()) throw new Error('Codex app-server instance is unavailable')
    const response = await this.client.request('thread/read', { threadId, includeTurns })
    return asRecord(response.thread) || {}
  }

  async listLoadedThreads(): Promise<string[]> {
    if (!this.isHealthy()) throw new Error('Codex app-server instance is unavailable')
    const response = await this.client.request('thread/loaded/list', undefined, {
      timeoutMs: LOADED_THREADS_REQUEST_TIMEOUT_MS,
      fatalOnTimeout: false,
    })
    if (!Array.isArray(response.data)) return []
    return response.data.map(readString).filter((threadId): threadId is string => Boolean(threadId))
  }

  async interruptTurn(threadId: string, turnId: string): Promise<void> {
    if (!this.isHealthy()) throw new Error('Codex app-server instance is unavailable')
    try {
      await this.client.request('turn/interrupt', { threadId, turnId })
    } catch (error) {
      this.healthy = false
      throw error
    }
  }

  async runTurn(options: PersistentTurnOptions): Promise<AppServerTurnResult> {
    if (this.requiresAttention()) {
      throw new AppServerRuntimeError('Codex app-server runtime has an unverified prior turn', {
        executionCommitted: true,
        turnMayHaveStarted: true,
        threadId: options.threadId,
        reason: 'runtime',
        code: 'APP_SERVER_PROCESS_UNVERIFIED',
      })
    }
    if (!this.isHealthy()) {
      throw new AppServerRuntimeError('Codex app-server instance is unavailable', {
        executionCommitted: false,
        threadId: options.threadId,
      })
    }
    if (options.threadId && this.activeThreadIds.has(options.threadId)) {
      throw new AppServerRuntimeError('Codex app-server thread already has an active root turn', {
        executionCommitted: false,
        threadId: options.threadId,
        code: 'APP_SERVER_THREAD_ALREADY_ACTIVE',
      })
    }
    const context: ActiveTurnContext = {
      id: randomUUID(),
      threadId: options.threadId,
      turnRequestIssued: false,
      providerTerminalObserved: false,
    }
    this.activeTurns.add(context)
    if (context.threadId) this.activeThreadIds.add(context.threadId)
    try {
      return await this.executeTurn(options, context)
    } finally {
      this.activeTurns.delete(context)
      if (context.threadId) this.activeThreadIds.delete(context.threadId)
      for (const [requestId, owner] of this.serverRequestOwners) {
        if (owner === context) this.serverRequestOwners.delete(requestId)
      }
      // The turn has consumed any authorized-close outcome.  A later manual
      // operation must create fresh evidence rather than inherit this one.
      if (this.activeTurns.size === 0) this.authorizedTermination = undefined
    }
  }

  async compactThread(options: AppServerCompactOptions): Promise<AppServerTurnResult> {
    if (this.requiresAttention()) {
      throw new AppServerRuntimeError('Codex app-server runtime has an unverified prior turn', {
        executionCommitted: true,
        turnMayHaveStarted: true,
        threadId: options.threadId,
        code: 'APP_SERVER_PROCESS_UNVERIFIED',
      })
    }
    if (!this.isHealthy()) {
      throw new AppServerRuntimeError('Codex app-server instance is unavailable', {
        executionCommitted: false,
        threadId: options.threadId,
      })
    }
    if (this.activeThreadIds.has(options.threadId)) {
      throw new AppServerRuntimeError('Codex app-server thread already has an active root turn', {
        executionCommitted: false,
        threadId: options.threadId,
        code: 'APP_SERVER_THREAD_ALREADY_ACTIVE',
      })
    }
    const context: ActiveTurnContext = {
      id: randomUUID(),
      threadId: options.threadId,
      turnRequestIssued: false,
      providerTerminalObserved: false,
    }
    this.activeTurns.add(context)
    this.activeThreadIds.add(options.threadId)
    try {
      return await this.executeCompact(options, context)
    } finally {
      this.activeTurns.delete(context)
      this.activeThreadIds.delete(options.threadId)
      for (const [requestId, owner] of this.serverRequestOwners) {
        if (owner === context) this.serverRequestOwners.delete(requestId)
      }
    }
  }

  close(timeoutMs = 2_000): Promise<void> {
    if (this.isActive() || this.requiresAttention()) {
      return Promise.reject(new AppServerRuntimeError(
        'Refusing to close an app-server runtime with an active or unverified turn',
        {
          executionCommitted: this.isActive() || this.requiresAttention(),
          turnMayHaveStarted: this.isActive() || this.requiresAttention(),
          reason: 'runtime',
          code: 'APP_SERVER_PROCESS_UNVERIFIED',
        },
      ))
    }
    this.healthy = false
    return this.client.close(timeoutMs)
  }

  /**
   * This is intentionally not exposed through ordinary pool retirement.  It
   * is reached only by the signed MANUAL_PID_KILL operation after its target
   * pid was matched to the active task.
   */
  async forceTerminateForAuthorizedOperation(
    expectedPid: number,
    verificationTimeoutMs = 2_000,
    threadId?: string,
    turnId?: string,
  ): Promise<boolean> {
    if (!this.client.pid || this.client.pid !== expectedPid) {
      throw new AppServerRuntimeError('Authorized PID did not match this app-server runtime', {
        executionCommitted: this.isActive() || this.requiresAttention(),
        turnMayHaveStarted: this.isActive() || this.requiresAttention(),
        reason: 'runtime',
        code: 'APP_SERVER_PROCESS_IDENTITY_MISMATCH',
      })
    }
    if (this.activeTurns.size > 1) {
      throw new AppServerRuntimeError('Refusing task-scoped PID termination for a shared app-server child', {
        executionCommitted: true,
        turnMayHaveStarted: true,
        threadId,
        turnId,
        reason: 'runtime',
        code: 'APP_SERVER_SHARED_PROCESS_ACTIVE',
      })
    }
    if (this.hasProviderTerminalObserved(threadId, turnId)) {
      // The provider terminal event won the race.  Do not dispatch SIGTERM or
      // SIGKILL merely because the task-manager terminal transition has not
      // finished persisting yet.
      throw new AppServerRuntimeError('Provider terminal event already observed for this app-server turn', {
        executionCommitted: true,
        turnMayHaveStarted: true,
        reason: 'runtime',
        code: 'APP_SERVER_PROVIDER_TERMINAL_OBSERVED',
      })
    }
    const existing = this.authorizedTermination
    if (existing) return (await existing.completion.promise).observedExit

    const termination: AuthorizedProcessTermination = { completion: deferred<AuthorizedProcessTerminationOutcome>() }
    this.authorizedTermination = termination
    // Install this before signaling.  If close() rejects a pending turn/start
    // RPC, executeTurn will wait for this authoritative outcome instead of
    // treating the generic transport error as a separate observation.
    const activeContext = [...this.activeTurns][0]
    if (activeContext) this.markAttention(new Error('Authorized process termination pending'), activeContext)
    this.healthy = false
    const settle = (outcome: AuthorizedProcessTerminationOutcome): void => {
      termination.completion.resolve(outcome)
      activeContext?.settleAuthorizedTermination?.(outcome)
      if (!this.isActive() && this.authorizedTermination === termination) {
        this.authorizedTermination = undefined
      }
    }
    try {
      // `close()` may only be able to dispatch SIGTERM/SIGKILL.  It is not a
      // terminal observation until the child exit event has actually arrived.
      // Preserve the indeterminate lease/task when that event is absent.
      await this.client.close(verificationTimeoutMs)
      const outcome = { observedExit: this.client.hasExited() }
      if (outcome.observedExit) {
        this.unverifiedTurns.clear()
        this.markObservedTerminal(threadId, turnId)
      }
      settle(outcome)
      return outcome.observedExit
    } catch (error) {
      // Process-tree cleanup or a close transport failure is not proof that
      // the task-owned execution stopped, even if a root signal was sent.
      settle({ observedExit: false })
      throw error
    }
  }

  private async executeTurn(options: PersistentTurnOptions, context: ActiveTurnContext): Promise<AppServerTurnResult> {
    let executionCommitted = false
    let resolvedThreadId = options.threadId
    let resolvedTurnId: string | undefined
    let abortTimer: NodeJS.Timeout | undefined
    let stallTimer: NodeJS.Timeout | undefined
    let abortRequested = false
    let commitInProgress = false
    let interruptSent = false
    let turnRequestIssued = false
    let turnNotificationsReady = false
    let turnWatchdogStarted = false
    let watchdogPauseCount = 0
    const terminal = deferred<Record<string, unknown>>()
    const settleAuthorizedTermination = (outcome: AuthorizedProcessTerminationOutcome): void => {
      terminal.reject(new AppServerRuntimeError(
        outcome.observedExit
          ? 'Authorized manual process termination observed child exit'
          : 'Authorized manual process termination could not confirm child exit',
        {
          executionCommitted,
          turnMayHaveStarted: !outcome.observedExit && (executionCommitted || turnRequestIssued),
          threadId: resolvedThreadId,
          turnId: resolvedTurnId,
          reason: outcome.observedExit ? 'aborted' : 'runtime',
          code: outcome.observedExit
            ? 'APP_SERVER_AUTHORIZED_PROCESS_EXIT'
            : 'APP_SERVER_MANUAL_TERMINATION_UNCONFIRMED',
        },
      ))
    }
    context.settleAuthorizedTermination = settleAuthorizedTermination
    const turnCorrelation = deferred<void>()
    void turnCorrelation.promise.catch(() => undefined)
    let notificationSideEffects = Promise.resolve()
    const pendingTurnNotifications: AppServerNotification[] = []
    const isCurrentTurnCompleted = (notification: AppServerNotification): boolean => {
      if (notification.method !== 'turn/completed') return false
      const params = notification.params || {}
      if (params.threadId !== resolvedThreadId) return false
      const turn = asRecord(params.turn) || {}
      const notificationTurnId = readString(turn.id)
      return Boolean(notificationTurnId && notificationTurnId === resolvedTurnId)
    }
    const observeProviderTerminal = (): void => {
      context.providerTerminalObserved = true
      this.observedProviderTerminals.set(context.id, {
        threadId: resolvedThreadId,
        turnId: resolvedTurnId,
      })
    }
    void terminal.promise.catch(() => undefined)
    const clearStallTimer = (): void => {
      if (!stallTimer) return
      clearTimeout(stallTimer)
      stallTimer = undefined
    }
    const armStallTimer = (): void => {
      clearStallTimer()
      if (!turnWatchdogStarted || watchdogPauseCount > 0) return
      stallTimer = setTimeout(() => {
        const error = new AppServerRuntimeError('Codex app-server turn made no observable progress', {
          executionCommitted: true,
          turnMayHaveStarted: true,
          threadId: resolvedThreadId,
          turnId: resolvedTurnId,
          reason: 'stalled',
          code: 'APP_SERVER_TURN_STALLED',
        })
        this.markAttention(error, context)
        terminal.reject(error)
      }, options.turnStallTimeoutMs ?? DEFAULT_TURN_STALL_TIMEOUT_MS)
    }
    const pauseStallTimer = (): void => {
      watchdogPauseCount++
      clearStallTimer()
    }
    const resumeStallTimer = (): void => {
      watchdogPauseCount = Math.max(0, watchdogPauseCount - 1)
      armStallTimer()
    }
    const dispatchNotification = (notification: AppServerNotification): void => {
      const params = notification.params || {}
      if (isDisabledImageGenerationNotification(notification, options.codexConfig)) {
        const error = new AppServerRuntimeError(
          'Codex app-server emitted image generation while the capability was disabled',
          {
            executionCommitted: true,
            turnMayHaveStarted: true,
            threadId: resolvedThreadId,
            turnId: resolvedTurnId,
            reason: 'protocol',
            code: 'APP_SERVER_UNEXPECTED_IMAGE_GENERATION',
          },
        )
        this.markAttention(error, context)
        terminal.reject(error)
        return
      }
      if (isMeaningfulTurnProgress(notification, resolvedThreadId, resolvedTurnId)) armStallTimer()
      options.onNotification(notification)
      if (notification.method === 'serverRequest/resolved'
          && params.threadId === resolvedThreadId
          && (typeof params.requestId === 'string' || Number.isSafeInteger(params.requestId))) {
        const requestId = params.requestId as string | number
        notificationSideEffects = notificationSideEffects.then(async () => {
          await options.onUserInputResolved?.({ requestId, threadId: resolvedThreadId! })
        })
      }
      if (isCurrentTurnCompleted(notification)) {
        // Set the fence before user-facing notification handlers or promise
        // continuations can interleave with a signed manual PID request.
        observeProviderTerminal()
        const turn = asRecord(params.turn) || {}
        void notificationSideEffects.then(
          () => terminal.resolve(turn),
          error => terminal.reject(error instanceof Error ? error : new Error('Server request resolution failed')),
        )
      }
    }
    context.onNotification = notification => {
      if (turnRequestIssued && !turnNotificationsReady) {
        // `turn/start` responses and notifications can arrive in the same
        // stream batch.  Once the response has supplied the turn id, a
        // matching buffered terminal event must fence manual PID dispatch even
        // before the normal notification replay reaches terminal.resolve().
        if (isCurrentTurnCompleted(notification)) observeProviderTerminal()
        if (pendingTurnNotifications.length >= MAX_PENDING_TURN_NOTIFICATIONS) {
          throw new Error('Codex app-server emitted too many notifications before turn correlation')
        }
        pendingTurnNotifications.push(notification)
        return
      }
      dispatchNotification(notification)
    }
    context.onServerRequest = async parsed => {
      if (!options.onUserInputRequest) {
        throw new Error('UNSUPPORTED_SERVER_REQUEST')
      }
      await turnCorrelation.promise
      if (parsed.threadId !== resolvedThreadId || parsed.turnId !== resolvedTurnId) {
        throw new Error('USER_INPUT_REQUEST_AFFINITY_MISMATCH')
      }
      pauseStallTimer()
      try {
        return await options.onUserInputRequest(parsed)
      } finally {
        resumeStallTimer()
      }
    }
    context.onFatal = error => terminal.reject(error)

    const abort = (): void => {
      abortRequested = true
      if (!executionCommitted) {
        if (commitInProgress) return
        terminal.reject(new AppServerRuntimeError('Codex app-server turn aborted before start', {
          executionCommitted: false,
          threadId: resolvedThreadId,
          reason: 'aborted',
        }))
        return
      }
      if (resolvedThreadId && resolvedTurnId && !interruptSent) {
        interruptSent = true
        void this.client.request('turn/interrupt', {
          threadId: resolvedThreadId,
          turnId: resolvedTurnId,
        }).catch(error => {
          this.healthy = false
          terminal.reject(error)
        })
      }
      if (!abortTimer) {
        abortTimer = setTimeout(() => {
          const error = new AppServerRuntimeError('Codex app-server did not report a terminal turn after turn/interrupt', {
            executionCommitted: true,
            turnMayHaveStarted: true,
            threadId: resolvedThreadId,
            turnId: resolvedTurnId,
            reason: 'runtime',
            code: 'APP_SERVER_ABORT_UNCONFIRMED',
          })
          this.markAttention(error, context)
          terminal.reject(error)
        }, options.interruptTimeoutMs ?? 5_000)
      }
    }
    options.signal.addEventListener('abort', abort, { once: true })

    try {
      throwIfAborted(options.signal, resolvedThreadId)
      const threadResponse = options.threadId
        ? await this.client.request('thread/resume', buildThreadParams(options, true))
        : await this.client.request('thread/start', buildThreadParams(options, false))
      resolvedThreadId = readString(asRecord(threadResponse.thread)?.id) || options.threadId
      if (!resolvedThreadId) throw new Error('Codex app-server did not return a thread id')
      this.bindContextThread(context, resolvedThreadId)
      await options.onThreadResolved?.(resolvedThreadId)
      throwIfAborted(options.signal, resolvedThreadId)

      const terminalPromise = terminal.promise
      commitInProgress = true
      try {
        await options.onExecutionCommitted?.(resolvedThreadId)
        executionCommitted = true
      } finally {
        commitInProgress = false
      }
      if (abortRequested || options.signal.aborted) {
        throw new AppServerRuntimeError('Codex app-server turn aborted after durable commit but before start', {
          executionCommitted: true,
          turnMayHaveStarted: false,
          threadId: resolvedThreadId,
          reason: 'aborted',
        })
      }
      turnRequestIssued = true
      context.turnRequestIssued = true
      const turnStartRequest = this.client.request('turn/start', {
        threadId: resolvedThreadId,
        input: toAppServerInput(options.input),
        model: options.model,
        effort: options.reasoningEffort,
        outputSchema: options.outputSchema,
      })
      // A turn/start RPC can itself become unresponsive after the provider
      // received it.  Let an explicit abort timeout surface the resulting
      // uncertainty promptly, but do not close or cancel the underlying RPC:
      // it may still have created a live turn.
      const turnResponse = await Promise.race([
        turnStartRequest,
        terminalPromise.then(
          () => new Promise<Record<string, unknown>>(() => undefined),
          error => Promise.reject(error),
        ),
      ])
      resolvedTurnId = readString(asRecord(turnResponse.turn)?.id) || resolvedTurnId
      if (!resolvedTurnId) throw new Error('Codex app-server did not return a turn id')
      context.turnId = resolvedTurnId
      // Notifications received before the RPC continuation did not yet have a
      // turn id to compare.  Recheck them now, before onTurnStarted can yield
      // to a concurrent signed manual-PID route.
      if (pendingTurnNotifications.some(isCurrentTurnCompleted)) observeProviderTerminal()
      await options.onTurnStarted?.(resolvedThreadId, resolvedTurnId)
      turnCorrelation.resolve()
      turnNotificationsReady = true
      turnWatchdogStarted = true
      armStallTimer()
      try {
        for (const notification of pendingTurnNotifications.splice(0)) {
          dispatchNotification(notification)
        }
      } catch (cause) {
        const error = new Error('Codex app-server notification handler failed', { cause })
        this.markFatal(error)
        terminal.reject(error)
      }
      if (abortRequested || options.signal.aborted) abort()

      const turn = await terminalPromise
      turnWatchdogStarted = false
      clearStallTimer()
      options.signal.removeEventListener('abort', abort)
      // A completed root turn no longer needs this connection's live notification subscription.
      // The thread remains persisted by app-server and can be resumed from any compatible process.
      await this.client.request('thread/unsubscribe', {
        threadId: resolvedThreadId,
      }, {
        timeoutMs: THREAD_UNSUBSCRIBE_TIMEOUT_MS,
        fatalOnTimeout: false,
      }).catch(() => undefined)
      return { threadId: resolvedThreadId, turn }
    } catch (error) {
      const authorizedTermination = this.authorizedTermination
      const authorizedOutcome = authorizedTermination
        ? await authorizedTermination.completion.promise
        : undefined
      const runtimeError = authorizedOutcome
        ? new AppServerRuntimeError(
          authorizedOutcome.observedExit
            ? 'Authorized manual process termination observed child exit'
            : 'Authorized manual process termination could not confirm child exit',
          {
            executionCommitted,
            turnMayHaveStarted: !authorizedOutcome.observedExit && (executionCommitted || turnRequestIssued),
            threadId: resolvedThreadId,
            turnId: resolvedTurnId,
            reason: authorizedOutcome.observedExit ? 'aborted' : 'runtime',
            code: authorizedOutcome.observedExit
              ? 'APP_SERVER_AUTHORIZED_PROCESS_EXIT'
              : 'APP_SERVER_MANUAL_TERMINATION_UNCONFIRMED',
            cause: error,
          },
        )
        : error instanceof AppServerRuntimeError
          ? error
          : new AppServerRuntimeError(readErrorMessage(error), {
            executionCommitted,
            turnMayHaveStarted: turnRequestIssued,
            threadId: resolvedThreadId,
            turnId: resolvedTurnId,
            reason: options.signal.aborted ? 'aborted' : 'runtime',
            cause: error,
          })
      turnCorrelation.reject(runtimeError)
      if (requiresAttentionError(runtimeError)) this.markAttention(runtimeError, context)
      else if (turnRequestIssued && this.healthy) this.markFatal(runtimeError)
      throw runtimeError
    } finally {
      if (abortTimer) clearTimeout(abortTimer)
      turnWatchdogStarted = false
      clearStallTimer()
      options.signal.removeEventListener('abort', abort)
      context.onNotification = undefined
      context.onServerRequest = undefined
      context.onFatal = undefined
      if (context.settleAuthorizedTermination === settleAuthorizedTermination) {
        context.settleAuthorizedTermination = undefined
      }
      // Do not close here. A transport/stall/protocol failure after turn/start
      // is not proof that the CLI stopped, and close() would SIGTERM/SIGKILL a
      // potentially active task. Pool retirement is allowed only after an
      // observed terminal state.
    }
  }

  private async executeCompact(
    options: AppServerCompactOptions,
    context: ActiveTurnContext,
  ): Promise<AppServerTurnResult> {
    let turnId: string | undefined
    let requestIssued = false
    let notificationsReady = false
    let interruptSent = false
    let abortTimer: NodeJS.Timeout | undefined
    const terminal = deferred<Record<string, unknown>>()
    void terminal.promise.catch(() => undefined)
    const pending: AppServerNotification[] = []
    const isMatching = (notification: AppServerNotification): boolean => {
      if (notification.params?.threadId !== options.threadId || !turnId) return false
      const notificationTurnId = readString(notification.params.turnId)
        || readString(asRecord(notification.params.turn)?.id)
      return notificationTurnId === turnId
    }
    const dispatch = (notification: AppServerNotification): void => {
      if (!isMatching(notification)) return
      options.onNotification(notification)
      if (notification.method === 'turn/completed') {
        context.providerTerminalObserved = true
        this.observedProviderTerminals.set(context.id, { threadId: options.threadId, turnId })
        terminal.resolve(asRecord(notification.params?.turn) || {})
      }
    }
    context.onNotification = notification => {
      if (requestIssued && !notificationsReady) {
        if (pending.length >= MAX_PENDING_TURN_NOTIFICATIONS) {
          throw new Error('Codex app-server emitted too many notifications before compact turn correlation')
        }
        pending.push(notification)
        return
      }
      dispatch(notification)
    }
    context.onFatal = error => terminal.reject(error)
    const abort = (): void => {
      if (!requestIssued) {
        terminal.reject(new AppServerRuntimeError('Codex app-server compaction aborted before start', {
          executionCommitted: false,
          threadId: options.threadId,
          reason: 'aborted',
        }))
        return
      }
      if (turnId && !interruptSent) {
        interruptSent = true
        void this.client.request('turn/interrupt', { threadId: options.threadId, turnId }).catch(error => {
          this.healthy = false
          terminal.reject(error)
        })
      }
      if (!abortTimer) {
        abortTimer = setTimeout(() => {
          const error = new AppServerRuntimeError(
            'Codex app-server did not report a terminal compact turn after turn/interrupt',
            {
              executionCommitted: true,
              turnMayHaveStarted: true,
              threadId: options.threadId,
              turnId,
              code: 'APP_SERVER_COMPACT_ABORT_UNCONFIRMED',
            },
          )
          this.markAttention(error, context)
          terminal.reject(error)
        }, options.interruptTimeoutMs ?? 5_000)
      }
    }
    options.signal.addEventListener('abort', abort, { once: true })
    try {
      throwIfAborted(options.signal, options.threadId)
      const resumed = await this.client.request('thread/resume', buildThreadParams(options, true))
      const resumedThreadId = readString(asRecord(resumed.thread)?.id)
      if (resumedThreadId !== options.threadId) {
        throw new AppServerRuntimeError('Codex app-server resumed a different thread for compaction', {
          executionCommitted: false,
          threadId: options.threadId,
          reason: 'protocol',
          code: 'APP_SERVER_COMPACT_THREAD_MISMATCH',
        })
      }
      throwIfAborted(options.signal, options.threadId)
      requestIssued = true
      context.turnRequestIssued = true
      const response = await Promise.race([
        this.client.request('thread/compact/start', { threadId: options.threadId }),
        terminal.promise.then(
          () => new Promise<Record<string, unknown>>(() => undefined),
          error => Promise.reject(error),
        ),
      ])
      turnId = readString(asRecord(response.turn)?.id)
      if (!turnId) throw new Error('Codex app-server did not return a compact turn id')
      context.turnId = turnId
      notificationsReady = true
      for (const notification of pending.splice(0)) dispatch(notification)
      if (options.signal.aborted) abort()
      const turn = await terminal.promise
      await this.client.request('thread/unsubscribe', { threadId: options.threadId }, {
        timeoutMs: THREAD_UNSUBSCRIBE_TIMEOUT_MS,
        fatalOnTimeout: false,
      }).catch(() => undefined)
      return { threadId: options.threadId, turn }
    } catch (error) {
      const runtimeError = error instanceof AppServerRuntimeError
        ? error
        : new AppServerRuntimeError(readErrorMessage(error), {
          executionCommitted: requestIssued,
          turnMayHaveStarted: requestIssued,
          threadId: options.threadId,
          turnId,
          reason: options.signal.aborted ? 'aborted' : 'runtime',
          cause: error,
        })
      if (requiresAttentionError(runtimeError)) this.markAttention(runtimeError, context)
      else if (requestIssued && this.healthy) this.markFatal(runtimeError)
      throw runtimeError
    } finally {
      if (abortTimer) clearTimeout(abortTimer)
      options.signal.removeEventListener('abort', abort)
      context.onNotification = undefined
      context.onFatal = undefined
    }
  }

  private markAttention(_error: Error, context: ActiveTurnContext): void {
    this.unverifiedTurns.set(context.id, {
      threadId: context.threadId,
      turnId: context.turnId,
    })
  }
}

function isDisabledImageGenerationNotification(
  notification: AppServerNotification,
  codexConfig: Record<string, unknown>,
): boolean {
  if (codexConfig['features.image_generation'] !== false) return false
  if (notification.method !== 'item/started' && notification.method !== 'item/completed') return false
  return asRecord(notification.params?.item)?.type === 'imageGeneration'
}

function isMeaningfulTurnProgress(
  notification: AppServerNotification,
  threadId: string | undefined,
  turnId: string | undefined,
): boolean {
  if (!threadId || notification.params?.threadId !== threadId) return false
  if (notification.method === 'turn/completed') {
    return readString(asRecord(notification.params.turn)?.id) === turnId
  }
  if (notification.method === 'serverRequest/resolved') return true
  if (notification.method === 'error') {
    const notificationTurnId = readString(notification.params.turnId)
    return !notificationTurnId || notificationTurnId === turnId
  }
  if (!notification.method.startsWith('item/')) return false
  const notificationTurnId = readString(notification.params.turnId)
  return !notificationTurnId || notificationTurnId === turnId
}

function requiresAttentionError(error: AppServerRuntimeError): boolean {
  if (error.code === 'APP_SERVER_AUTHORIZED_PROCESS_EXIT') return false
  // Once a provider turn may have started, even a failed explicit interrupt
  // leaves process state unverified.  The caller must observe its terminal
  // state (or perform a separately authorized PID kill) before recycling it.
  return error.turnMayHaveStarted
}

export async function runAppServerTurn(options: AppServerTurnOptions): Promise<AppServerTurnResult> {
  const instance = await AppServerRuntimeInstance.start({
    env: options.env,
    apiKey: options.apiKey,
    cwd: options.cwd,
    spawnProcess: options.spawnProcess,
    requestTimeoutMs: options.requestTimeoutMs,
  })
  options.onProcessStarted?.(instance.pid)
  let completed = false
  try {
    const result = await instance.runTurn(options)
    completed = true
    return result
  } finally {
    if (completed || !instance.requiresAttention()) await instance.close()
  }
}

async function loginWithEphemeralApiKey(client: AppServerJsonRpcClient, apiKey: string): Promise<void> {
  try {
    const response = await client.request('account/login/start', { type: 'apiKey', apiKey })
    if (response.type !== 'apiKey') throw new Error('Unexpected API-key login response')
  } catch (error) {
    const cause = error instanceof AppServerRpcError
      ? new AppServerRpcError(error.code, 'Codex app-server API-key login RPC failed')
      : new Error('Codex app-server API-key login protocol failed')
    throw new Error('Codex app-server ephemeral API-key login failed', { cause })
  }
}

function buildThreadParams(
  options: Pick<
    PersistentTurnOptions,
    'model' | 'cwd' | 'approvalPolicy' | 'sandboxMode' | 'codexConfig' | 'developerInstructions'
  > & { threadId?: string },
  resume: boolean,
): Record<string, unknown> {
  const params: Record<string, unknown> = {
    model: options.model,
    cwd: options.cwd,
    approvalPolicy: options.approvalPolicy,
    sandbox: options.sandboxMode,
    config: options.codexConfig,
    developerInstructions: options.developerInstructions,
  }
  if (resume) params.threadId = options.threadId
  else params.serviceName = 'foggy_navigator_codex_app_server_worker'
  return compactUndefined(params)
}

export function toAppServerInput(input: CodexInput): Array<Record<string, unknown>> {
  if (typeof input === 'string') return [{ type: 'text', text: input, text_elements: [] }]
  return input.map(item => item.type === 'text'
    ? { type: 'text', text: item.text, text_elements: [] }
    : { type: 'localImage', path: item.path })
}

type ProcessTreeAction = 'snapshot' | 'extend' | 'poll' | 'verify' | 'kill'

class AppServerProcessTree {
  private cleaned = false
  private operationTail: Promise<void> = Promise.resolve()

  private constructor(
    private readonly directory: string,
    private readonly snapshot: string,
  ) {}

  static async capture(options: {
    child: AppServerProcess
    entry: string
    stateDir: string
  }): Promise<AppServerProcessTree> {
    let tracker: AppServerProcessTree | undefined
    let rootExit: Promise<void> | undefined
    try {
      if (!options.child.pid) throw new Error('Codex app-server process identity is unavailable')
      rootExit = new Promise(resolve => options.child.once('exit', () => resolve()))
      const root = path.join(path.resolve(options.stateDir), 'runtime-process-trees')
      await fs.mkdir(root, { recursive: true, mode: 0o700 })
      const rootStat = await fs.lstat(root)
      if (!rootStat.isDirectory() || rootStat.isSymbolicLink()) {
        throw new Error('Codex app-server process-tree state directory is unsafe')
      }
      await fs.chmod(root, 0o700)
      const directory = await fs.mkdtemp(path.join(root, 'instance-'))
      await fs.chmod(directory, 0o700)
      tracker = new AppServerProcessTree(directory, path.join(directory, 'tree.json'))
      await tracker.run('snapshot', [
        '--pid', String(options.child.pid),
        '--entry', path.resolve(options.entry),
      ], [PROCESS_TREE_EXIT_CLEAN])
      return tracker
    } catch (error) {
      let cleanupProven = false
      let snapshotAvailable = false
      if (tracker) {
        try {
          snapshotAvailable = await tracker.hasSnapshot()
        } catch {
          snapshotAvailable = false
        }
      }
      if (tracker && snapshotAvailable) {
        try {
          await tracker.killAndVerify()
          cleanupProven = true
        } catch {
          // The original capture error is retained as the public cause below.
        }
      }
      if (cleanupProven) {
        await tracker?.cleanup().catch(() => undefined)
        throw error
      }
      try {
        if (!options.child.killed) options.child.kill('SIGKILL')
      } catch {
        // Without a valid tree snapshot, root termination is only best effort.
      }
      if (rootExit) await settlesBefore(rootExit, PROCESS_TREE_ROOT_EXIT_TIMEOUT_MS)
      await tracker?.writeCaptureFailure(options.child.pid, options.entry).catch(() => undefined)
      throw new AppServerProcessTreeSafetyError(undefined, { cause: error })
    }
  }

  private async hasSnapshot(): Promise<boolean> {
    try {
      const stat = await fs.lstat(this.snapshot)
      return stat.isFile() && !stat.isSymbolicLink()
    } catch (error) {
      if (isFilesystemError(error, 'ENOENT')) return false
      throw error
    }
  }

  private async writeCaptureFailure(pid: number | undefined, entry: string): Promise<void> {
    const evidence = {
      schema_version: 1,
      captured_at: new Date().toISOString(),
      root_pid: pid,
      entry_sha256: createHash('sha256').update(normalizeEntryForHash(entry)).digest('hex'),
      reason: 'INITIAL_CAPTURE_FAILED',
      cleanup_proven: false,
    }
    await this.writeFailureEvidence('capture.failure', evidence)
  }

  async writeCleanupFailure(): Promise<void> {
    await this.writeFailureEvidence('cleanup.failure', {
      schema_version: 1,
      captured_at: new Date().toISOString(),
      reason: 'CLOSE_CLEANUP_UNPROVEN',
      cleanup_proven: false,
    })
  }

  private async writeFailureEvidence(
    fileName: 'capture.failure' | 'cleanup.failure',
    evidence: Record<string, unknown>,
  ): Promise<void> {
    const failureFile = path.join(this.directory, fileName)
    const temporaryFile = path.join(this.directory, `.${fileName}.${process.pid}.${randomUUID()}.tmp`)
    let handle: fs.FileHandle | undefined
    try {
      handle = await fs.open(temporaryFile, 'wx', 0o600)
      await handle.writeFile(`${JSON.stringify(evidence)}\n`, 'utf8')
      await handle.sync()
      await handle.close()
      handle = undefined
      await fs.rename(temporaryFile, failureFile)
    } finally {
      await handle?.close().catch(() => undefined)
      await fs.rm(temporaryFile, { force: true }).catch(() => undefined)
    }
  }

  async extend(): Promise<void> {
    await this.enqueue(() => this.run('extend', [], [PROCESS_TREE_EXIT_CLEAN]))
  }

  async waitForClean(timeoutMs: number): Promise<boolean> {
    return this.enqueue(async () => {
      const deadline = Date.now() + Math.max(0, timeoutMs)
      do {
        const code = await this.run('poll', [], [PROCESS_TREE_EXIT_CLEAN, PROCESS_TREE_EXIT_ALIVE])
        if (code === PROCESS_TREE_EXIT_CLEAN) return true
        if (Date.now() >= deadline) return false
        await delay(Math.min(PROCESS_TREE_POLL_INTERVAL_MS, Math.max(0, deadline - Date.now())))
      } while (true)
    })
  }

  async verify(): Promise<void> {
    await this.enqueue(() => this.run('verify', [], [PROCESS_TREE_EXIT_CLEAN]))
  }

  async killAndVerify(): Promise<void> {
    await this.enqueue(async () => {
      await this.run('kill', [], [PROCESS_TREE_EXIT_CLEAN])
      await this.run('verify', [], [PROCESS_TREE_EXIT_CLEAN])
    })
  }

  async cleanup(): Promise<void> {
    await this.enqueue(async () => {
      if (this.cleaned) return
      this.cleaned = true
      await fs.rm(this.directory, { recursive: true, force: true })
      try {
        await fs.rmdir(path.dirname(this.directory))
      } catch (error) {
        if (!isFilesystemError(error, 'ENOENT') && !isFilesystemError(error, 'ENOTEMPTY')) throw error
      }
    })
  }

  private enqueue<T>(operation: () => Promise<T>): Promise<T> {
    const result = this.operationTail.then(operation, operation)
    this.operationTail = result.then(() => undefined, () => undefined)
    return result
  }

  private run(
    action: ProcessTreeAction,
    args: string[],
    allowedExitCodes: number[],
  ): Promise<number> {
    return new Promise((resolve, reject) => {
      const helper = spawn(process.execPath, [
        processTreeHelper,
        action,
        ...args,
        '--output',
        this.snapshot,
      ], {
        stdio: 'ignore',
        windowsHide: true,
      })
      let settled = false
      const timer = setTimeout(() => {
        if (settled) return
        settled = true
        helper.kill('SIGKILL')
        reject(new Error(`Codex app-server process-tree ${action} timed out`))
      }, PROCESS_TREE_HELPER_TIMEOUT_MS)
      helper.once('error', error => {
        if (settled) return
        settled = true
        clearTimeout(timer)
        reject(new Error(`Codex app-server process-tree ${action} failed`, { cause: error }))
      })
      helper.once('exit', code => {
        if (settled) return
        settled = true
        clearTimeout(timer)
        if (code !== null && allowedExitCodes.includes(code)) resolve(code)
        else reject(new Error(`Codex app-server process-tree ${action} failed`))
      })
    })
  }
}

class AppServerJsonRpcClient {
  private readonly pending = new Map<JsonRpcId, PendingRequest>()
  private readonly notificationHandlers = new Set<(notification: AppServerNotification) => void>()
  private readonly fatalHandlers = new Set<(error: Error) => void>()
  private serverRequestHandler?: (request: AppServerServerRequest) => Promise<Record<string, unknown>>
  private readonly inboundServerRequests = new Map<string, { resolved: boolean }>()
  private readonly lines: readline.Interface
  private nextRequestId = 1
  private closed = false
  private failed = false
  private stderrObserved = false
  private exited = false
  private readonly exitPromise: Promise<void>
  private closePromise?: Promise<void>

  constructor(
    private readonly child: AppServerProcess,
    private readonly requestTimeoutMs: number,
    private readonly processTree?: AppServerProcessTree,
  ) {
    this.exitPromise = new Promise(resolve => {
      child.once('exit', () => {
        this.exited = true
        resolve()
      })
    })
    this.lines = readline.createInterface({ input: child.stdout, crlfDelay: Infinity })
    this.lines.on('line', line => this.handleLine(line))
    child.stdin.on('error', error => this.fail(error))
    child.once('error', error => this.fail(error))
    child.once('exit', (code, signal) => {
      if (!this.closed) this.fail(new Error(`Codex app-server exited (${signal || `code ${code ?? 1}`})`))
    })
    child.stderr.on('data', chunk => {
      if (!this.stderrObserved && String(chunk).length > 0) {
        this.stderrObserved = true
        console.warn('[codex-app-server] stderr output suppressed')
      }
    })
  }

  get pid(): number | undefined {
    return this.child.pid
  }

  /** True only after the child process emitted its exit event. */
  hasExited(): boolean {
    return this.exited
  }

  isFailed(): boolean {
    return this.failed || this.closed
  }

  async request(
    method: string,
    params?: Record<string, unknown>,
    options: RequestOptions = {},
  ): Promise<Record<string, unknown>> {
    if (this.closed || this.failed) return Promise.reject(new Error('Codex app-server connection is closed'))
    if (this.processTree && (method === 'turn/start' || method === 'turn/interrupt')) {
      try {
        await this.processTree.extend()
      } catch (error) {
        this.fail(new Error('Codex app-server process-tree extension failed', { cause: error }))
        throw error
      }
      if (this.closed || this.failed) throw new Error('Codex app-server connection is closed')
    }
    const id = this.nextRequestId++
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id)
        const error = new Error(`Codex app-server request timed out: ${method}`)
        reject(error)
        if (options.fatalOnTimeout !== false) this.fail(error)
      }, options.timeoutMs ?? this.requestTimeoutMs)
      this.pending.set(id, { resolve, reject, timer })
      this.write(compactUndefined({ id, method, params }))
    })
  }

  notify(method: string, params?: Record<string, unknown>): void {
    this.write(compactUndefined({ method, params }))
  }

  onNotification(handler: (notification: AppServerNotification) => void): () => void {
    this.notificationHandlers.add(handler)
    return () => this.notificationHandlers.delete(handler)
  }

  onServerRequest(handler: (request: AppServerServerRequest) => Promise<Record<string, unknown>>): () => void {
    if (this.serverRequestHandler) throw new Error('Codex app-server already has a server request handler')
    this.serverRequestHandler = handler
    return () => {
      if (this.serverRequestHandler === handler) this.serverRequestHandler = undefined
    }
  }

  onFatal(handler: (error: Error) => void): () => void {
    this.fatalHandlers.add(handler)
    return () => this.fatalHandlers.delete(handler)
  }

  close(timeoutMs = 2_000): Promise<void> {
    if (this.closePromise) return this.closePromise
    this.closed = true
    this.lines.close()
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer)
      pending.reject(new Error('Codex app-server connection closed'))
    }
    this.pending.clear()
    this.closePromise = (async () => {
      const timeoutBudgetMs = Math.max(0, timeoutMs)
      if (!this.processTree) {
        this.child.stdin.end()
        if (!this.child.killed && !this.exited) this.child.kill('SIGTERM')
        if (this.exited || await settlesBefore(this.exitPromise, timeoutBudgetMs)) return
        console.warn('[codex-app-server] child_force_kill reason=SHUTDOWN_TIMEOUT')
        this.child.kill('SIGKILL')
        await settlesBefore(this.exitPromise, Math.min(500, timeoutBudgetMs))
        return
      }

      // Pool drain passes its remaining total deadline here. Reserve most of that
      // budget for exact descendant kill/verify instead of spending it all on a
      // root process that may ignore SIGTERM.
      const gracefulExitMs = Math.min(PROCESS_TREE_GRACEFUL_EXIT_MAX_MS, timeoutBudgetMs)
      const failures: Error[] = []
      let finalExtensionSucceeded = false
      let treeCleanupProven = false
      try {
        try {
          await this.processTree.extend()
          finalExtensionSucceeded = true
        } catch (error) {
          failures.push(asError(error))
        }
        this.child.stdin.end()
        if (!this.child.killed && !this.exited) this.child.kill('SIGTERM')

        let clean = false
        try {
          if (!this.exited) await settlesBefore(this.exitPromise, gracefulExitMs)
          clean = await this.processTree.waitForClean(0)
          if (clean) {
            await this.processTree.verify()
            if (finalExtensionSucceeded) {
              treeCleanupProven = true
              failures.length = 0
            }
          }
        } catch (error) {
          failures.push(asError(error))
        }
        if (!clean) {
          console.warn('[codex-app-server] child_tree_force_kill reason=SHUTDOWN_TIMEOUT')
          try {
            await this.processTree.killAndVerify()
            if (finalExtensionSucceeded) {
              treeCleanupProven = true
              failures.length = 0
            }
          } catch (error) {
            failures.push(asError(error))
          }
        }
      } catch (error) {
        failures.push(asError(error))
      } finally {
        if (treeCleanupProven) {
          try {
            await this.processTree.cleanup()
          } catch (error) {
            failures.push(asError(error))
          }
        }
      }
      if (failures.length > 0) {
        this.failed = true
        await this.processTree.writeCleanupFailure().catch(() => undefined)
        throw new AppServerProcessTreeSafetyError(undefined, {
          cause: new AggregateError(failures, 'Codex app-server process-tree cleanup failed'),
        })
      }
      if (!treeCleanupProven) {
        this.failed = true
        await this.processTree.writeCleanupFailure().catch(() => undefined)
        throw new AppServerProcessTreeSafetyError()
      }
    })()
    return this.closePromise
  }

  private handleLine(line: string): void {
    let message: Record<string, unknown>
    try {
      message = JSON.parse(line) as Record<string, unknown>
    } catch {
      this.fail(new Error('Codex app-server emitted invalid JSON'))
      return
    }
    const wireId = typeof message.id === 'number' || typeof message.id === 'string' ? message.id : undefined
    const method = readString(message.method)
    if (typeof wireId === 'number' && !method) {
      const pending = this.pending.get(wireId)
      if (!pending) return
      this.pending.delete(wireId)
      clearTimeout(pending.timer)
      const rpcError = asRecord(message.error)
      if (rpcError) {
        const code = typeof rpcError.code === 'number' && Number.isSafeInteger(rpcError.code)
          ? rpcError.code
          : -32603
        pending.reject(new AppServerRpcError(
          code,
          readString(rpcError.message) || 'Codex app-server request failed',
          rpcError.data,
        ))
      }
      else pending.resolve(asRecord(message.result) || {})
      return
    }
    if (method && wireId !== undefined) {
      if (method !== USER_INPUT_SERVER_METHOD || !this.serverRequestHandler) {
        this.write({ id: wireId, error: { code: -32601, message: 'Unsupported server request' } })
        return
      }
      const requestId = wireId as string | number
      const key = requestIdKey(requestId)
      if (this.inboundServerRequests.has(key)) {
        this.write({ id: wireId, error: { code: -32600, message: 'Duplicate server request id' } })
        return
      }
      const state = { resolved: false }
      this.inboundServerRequests.set(key, state)
      const request: AppServerServerRequest = {
        id: requestId,
        method,
        params: asRecord(message.params) || {},
      }
      void this.serverRequestHandler(request).then(result => {
        if (!state.resolved) this.write({ id: requestId, result })
      }, () => {
        if (!state.resolved) {
          this.write({ id: requestId, error: { code: -32001, message: 'User input request rejected' } })
        }
      }).catch(error => this.fail(error instanceof Error ? error : new Error('Server request response failed')))
        .finally(() => this.inboundServerRequests.delete(key))
      return
    }
    if (method) {
      const notification = { method, params: asRecord(message.params) }
      if (method === 'serverRequest/resolved') {
        const requestId = notification.params?.requestId
        if (typeof requestId === 'string' || Number.isSafeInteger(requestId)) {
          const state = this.inboundServerRequests.get(requestIdKey(requestId as string | number))
          if (state) state.resolved = true
        }
      }
      for (const handler of this.notificationHandlers) {
        try {
          handler(notification)
        } catch {
          this.fail(new Error('Codex app-server notification handler failed'))
          break
        }
      }
    }
  }

  private write(message: Record<string, unknown>): void {
    if (this.closed || this.failed) throw new Error('Codex app-server connection is closed')
    this.child.stdin.write(`${JSON.stringify(message)}\n`)
  }

  private fail(error: Error): void {
    if (this.closed || this.failed) return
    this.failed = true
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer)
      pending.reject(error)
    }
    this.pending.clear()
    for (const handler of this.fatalHandlers) {
      try {
        handler(error)
      } catch {
        // Fatal observers are isolated from the transport loop.
      }
    }
  }
}

async function settlesBefore(promise: Promise<void>, timeoutMs: number): Promise<boolean> {
  if (timeoutMs <= 0) return false
  let timer: NodeJS.Timeout | undefined
  try {
    return await Promise.race([
      promise.then(() => true),
      new Promise<false>(resolve => {
        timer = setTimeout(() => resolve(false), timeoutMs)
      }),
    ])
  } finally {
    if (timer) clearTimeout(timer)
  }
}

function delay(timeoutMs: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, timeoutMs))
}

function normalizeEntryForHash(entry: string): string {
  const resolved = path.resolve(entry)
  return process.platform === 'win32' ? resolved.toLowerCase() : resolved
}

function isFilesystemError(error: unknown, code: string): error is NodeJS.ErrnoException {
  return error instanceof Error && 'code' in error && (error as NodeJS.ErrnoException).code === code
}

function asError(error: unknown): Error {
  return error instanceof Error ? error : new Error(String(error))
}

function throwIfAborted(signal: AbortSignal, threadId: string | undefined): void {
  if (!signal.aborted) return
  throw new AppServerRuntimeError('Codex app-server turn aborted before start', {
    executionCommitted: false,
    threadId,
    reason: 'aborted',
  })
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

function compactUndefined<T extends Record<string, unknown>>(value: T): T {
  return Object.fromEntries(Object.entries(value).filter(([, item]) => item !== undefined)) as T
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : undefined
}

function readString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined
}

function readErrorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}
