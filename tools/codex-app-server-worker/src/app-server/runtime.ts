import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process'
import { createRequire } from 'node:module'
import readline from 'node:readline'
import type { CodexApprovalPolicy, CodexInput, CodexSandboxMode } from '../models.js'
import type { AppServerNotification } from './native-subtask-tracker.js'

const moduleRequire = createRequire(import.meta.url)
const DEFAULT_REQUEST_TIMEOUT_MS = 30_000
export const VALIDATED_APP_SERVER_CLI_VERSION = '0.144.1'

type JsonRpcId = number

type PendingRequest = {
  resolve: (value: Record<string, unknown>) => void
  reject: (error: Error) => void
  timer: NodeJS.Timeout
}

export type AppServerProcess = Pick<
  ChildProcessWithoutNullStreams,
  'stdin' | 'stdout' | 'stderr' | 'pid' | 'killed' | 'kill' | 'on' | 'once'
>

export type SpawnAppServerProcess = (options: {
  cwd?: string
  env: Record<string, string>
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
  signal: AbortSignal
  onNotification: (notification: AppServerNotification) => void
  onThreadResolved?: (threadId: string) => void | Promise<void>
  onExecutionCommitted?: (threadId: string) => void | Promise<void>
  onTurnStarted?: (threadId: string, turnId: string | undefined) => void | Promise<void>
  onProcessStarted?: (pid: number | undefined) => void
  spawnProcess?: SpawnAppServerProcess
  requestTimeoutMs?: number
  interruptTimeoutMs?: number
}

export type PersistentTurnOptions = Omit<
  AppServerTurnOptions,
  'env' | 'spawnProcess' | 'requestTimeoutMs' | 'onProcessStarted'
>

export type AppServerTurnResult = {
  threadId: string
  turn: Record<string, unknown>
}

export class AppServerRuntimeError extends Error {
  readonly executionCommitted: boolean
  readonly turnMayHaveStarted: boolean
  readonly threadId?: string
  readonly turnId?: string
  readonly reason: 'runtime' | 'aborted' | 'unsupported'

  constructor(
    message: string,
    options: {
      executionCommitted: boolean
      turnMayHaveStarted?: boolean
      threadId?: string
      turnId?: string
      reason?: AppServerRuntimeError['reason']
      cause?: unknown
    },
  ) {
    super(message, { cause: options.cause })
    this.name = 'AppServerRuntimeError'
    this.executionCommitted = options.executionCommitted
    this.turnMayHaveStarted = options.turnMayHaveStarted === true
    this.threadId = options.threadId
    this.turnId = options.turnId
    this.reason = options.reason || 'runtime'
  }
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
}): AppServerProcess {
  return spawn(process.execPath, [resolveBundledCodexLauncher(), 'app-server', '--stdio'], {
    cwd: options.cwd,
    env: options.env,
    stdio: ['pipe', 'pipe', 'pipe'],
    windowsHide: true,
  })
}

export class AppServerRuntimeInstance {
  private active = false
  private healthy = true
  private readonly fatalHandlers = new Set<(error: Error) => void>()

  private constructor(private readonly client: AppServerJsonRpcClient) {
    client.onFatal(error => {
      this.healthy = false
      for (const handler of this.fatalHandlers) {
        try {
          handler(error)
        } catch {
          // Fatal observers must not turn a child-process failure into a Worker crash.
        }
      }
    })
  }

  static async start(options: {
    env: Record<string, string>
    cwd?: string
    spawnProcess?: SpawnAppServerProcess
    requestTimeoutMs?: number
    signal?: AbortSignal
  }): Promise<AppServerRuntimeInstance> {
    let client: AppServerJsonRpcClient
    try {
      const child = (options.spawnProcess || spawnBundledAppServer)({ cwd: options.cwd, env: options.env })
      client = new AppServerJsonRpcClient(child, options.requestTimeoutMs || DEFAULT_REQUEST_TIMEOUT_MS)
    } catch (error) {
      throw new AppServerRuntimeError(readErrorMessage(error), {
        executionCommitted: false,
        cause: error,
      })
    }
    const instance = new AppServerRuntimeInstance(client)
    const abortStartup = (): void => { void instance.close(0) }
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
          experimentalApi: false,
          requestAttestation: false,
        },
      })
      client.notify('initialized')
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
    return this.active
  }

  onFatal(handler: (error: Error) => void): () => void {
    this.fatalHandlers.add(handler)
    return () => this.fatalHandlers.delete(handler)
  }

  async readThread(threadId: string, includeTurns = true): Promise<Record<string, unknown>> {
    if (!this.isHealthy()) throw new Error('Codex app-server instance is unavailable')
    const response = await this.client.request('thread/read', { threadId, includeTurns })
    return asRecord(response.thread) || {}
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
    if (!this.isHealthy()) {
      throw new AppServerRuntimeError('Codex app-server instance is unavailable', {
        executionCommitted: false,
        threadId: options.threadId,
      })
    }
    if (this.active) {
      throw new AppServerRuntimeError('Codex app-server instance already has an active root turn', {
        executionCommitted: false,
        threadId: options.threadId,
      })
    }
    this.active = true
    try {
      return await this.executeTurn(options)
    } finally {
      this.active = false
    }
  }

  close(timeoutMs = 2_000): Promise<void> {
    this.healthy = false
    return this.client.close(timeoutMs)
  }

  private async executeTurn(options: PersistentTurnOptions): Promise<AppServerTurnResult> {
    let executionCommitted = false
    let resolvedThreadId = options.threadId
    let resolvedTurnId: string | undefined
    let abortTimer: NodeJS.Timeout | undefined
    let abortRequested = false
    let commitInProgress = false
    let interruptSent = false
    let turnRequestIssued = false
    const terminal = deferred<Record<string, unknown>>()
    const pendingTerminalTurns = new Map<string, Record<string, unknown>>()
    void terminal.promise.catch(() => undefined)
    const unsubscribeNotification = this.client.onNotification(notification => {
      options.onNotification(notification)
      const params = notification.params || {}
      if (notification.method === 'turn/completed' && params.threadId === resolvedThreadId) {
        const turn = asRecord(params.turn) || {}
        const notificationTurnId = readString(turn.id)
        if (notificationTurnId && notificationTurnId === resolvedTurnId) {
          terminal.resolve(turn)
        } else if (notificationTurnId && turnRequestIssued && !resolvedTurnId) {
          pendingTerminalTurns.set(notificationTurnId, turn)
        }
      }
    })
    const unsubscribeFatal = this.client.onFatal(error => terminal.reject(error))

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
          this.healthy = false
          terminal.reject(new Error('Codex app-server did not stop after turn/interrupt'))
          void this.client.close()
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
      const turnResponse = await this.client.request('turn/start', {
        threadId: resolvedThreadId,
        input: toAppServerInput(options.input),
        model: options.model,
        effort: options.reasoningEffort,
        outputSchema: options.outputSchema,
      })
      resolvedTurnId = readString(asRecord(turnResponse.turn)?.id) || resolvedTurnId
      if (!resolvedTurnId) throw new Error('Codex app-server did not return a turn id')
      const pendingTerminal = pendingTerminalTurns.get(resolvedTurnId)
      if (pendingTerminal) terminal.resolve(pendingTerminal)
      await options.onTurnStarted?.(resolvedThreadId, resolvedTurnId)
      if (abortRequested || options.signal.aborted) abort()

      const turn = await terminalPromise
      options.signal.removeEventListener('abort', abort)
      return { threadId: resolvedThreadId, turn }
    } catch (error) {
      if (error instanceof AppServerRuntimeError) throw error
      throw new AppServerRuntimeError(readErrorMessage(error), {
        executionCommitted,
        turnMayHaveStarted: turnRequestIssued,
        threadId: resolvedThreadId,
        turnId: resolvedTurnId,
        reason: options.signal.aborted ? 'aborted' : 'runtime',
        cause: error,
      })
    } finally {
      if (abortTimer) clearTimeout(abortTimer)
      options.signal.removeEventListener('abort', abort)
      unsubscribeNotification()
      unsubscribeFatal()
      if (!this.healthy) void this.client.close()
    }
  }
}

export async function runAppServerTurn(options: AppServerTurnOptions): Promise<AppServerTurnResult> {
  const instance = await AppServerRuntimeInstance.start({
    env: options.env,
    cwd: options.cwd,
    spawnProcess: options.spawnProcess,
    requestTimeoutMs: options.requestTimeoutMs,
  })
  options.onProcessStarted?.(instance.pid)
  try {
    return await instance.runTurn(options)
  } finally {
    await instance.close()
  }
}

function buildThreadParams(options: PersistentTurnOptions, resume: boolean): Record<string, unknown> {
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

class AppServerJsonRpcClient {
  private readonly pending = new Map<JsonRpcId, PendingRequest>()
  private readonly notificationHandlers = new Set<(notification: AppServerNotification) => void>()
  private readonly fatalHandlers = new Set<(error: Error) => void>()
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

  isFailed(): boolean {
    return this.failed || this.closed
  }

  request(method: string, params?: Record<string, unknown>): Promise<Record<string, unknown>> {
    if (this.closed || this.failed) return Promise.reject(new Error('Codex app-server connection is closed'))
    const id = this.nextRequestId++
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id)
        const error = new Error(`Codex app-server request timed out: ${method}`)
        reject(error)
        this.fail(error)
      }, this.requestTimeoutMs)
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
    this.child.stdin.end()
    if (!this.child.killed && !this.exited) this.child.kill('SIGTERM')
    this.closePromise = (async () => {
      const graceMs = Math.max(0, timeoutMs)
      if (this.exited || await settlesBefore(this.exitPromise, graceMs)) return
      console.warn('[codex-app-server] child_force_kill reason=SHUTDOWN_TIMEOUT')
      this.child.kill('SIGKILL')
      await settlesBefore(this.exitPromise, Math.min(500, graceMs))
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
      if (rpcError) pending.reject(new Error(readString(rpcError.message) || 'Codex app-server request failed'))
      else pending.resolve(asRecord(message.result) || {})
      return
    }
    if (method && wireId !== undefined) {
      this.write({ id: wireId, error: { code: -32601, message: `Unsupported server request: ${method}` } })
      return
    }
    if (method) {
      const notification = { method, params: asRecord(message.params) }
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

function throwIfAborted(signal: AbortSignal, threadId: string | undefined): void {
  if (!signal.aborted) return
  throw new AppServerRuntimeError('Codex app-server turn aborted before start', {
    executionCommitted: false,
    threadId,
    reason: 'aborted',
  })
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void; reject: (error: Error) => void } {
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
