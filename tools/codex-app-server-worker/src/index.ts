import { createServer, type Server } from 'node:http'
import { pathToFileURL } from 'node:url'
import { createApp } from './app.js'
import { config as defaultConfig, type AppConfig } from './config.js'
import { StrictAppServerExecutor, type TaskExecutor } from './app-server/executor.js'
import { TaskStore } from './persistence/task-store.js'
import { resolveRuntimeReadiness } from './runtime-capabilities.js'
import { TaskManager } from './task-manager.js'
import {
  monitorStopRequest,
  type LifecycleFailureReason,
  writeLifecycleFailureLatch,
  writeShutdownOutcome,
  writeStopFailureLatch,
} from './stop-request.js'
import { assertCodexHomeIsolation } from './path-guards.js'
import {
  acquireStateWriterLease,
  canonicalizeStateDirectory,
  initializeStateIdentity,
  type StateWriterLease,
} from './persistence/state-identity.js'

const RECOVERY_PROBE_INTERVAL_MS = 1_000
const FAILED_BOOTSTRAP_FINALIZER_RETRY_MS = 100
const FAILED_BOOTSTRAP_FINALIZER_MAX_ATTEMPTS = 3

export interface BootstrapOptions {
  executor?: TaskExecutor
  installProcessHandlers?: boolean
  exitProcess?: (code: number) => unknown
}

export interface BootstrapHandle {
  manager: TaskManager
  server: Server
  close: () => Promise<boolean>
}

export async function bootstrap(
  runtimeConfig: AppConfig = defaultConfig,
  options: BootstrapOptions = {},
): Promise<BootstrapHandle> {
  let stateLease: StateWriterLease | undefined
  let manager: TaskManager | undefined
  let server: Server | undefined
  try {
    runtimeConfig.stateDir = await canonicalizeStateDirectory(runtimeConfig.stateDir)
    if (runtimeConfig.codexHome) assertCodexHomeIsolation(runtimeConfig)
    stateLease = await acquireStateWriterLease(runtimeConfig.stateDir)
    runtimeConfig.instanceId = await initializeStateIdentity(runtimeConfig.stateDir, runtimeConfig.instanceId)
    const store = new TaskStore({
      stateDir: runtimeConfig.stateDir,
      encryptionKey: runtimeConfig.stateEncryptionKey || Buffer.alloc(32),
    })
    manager = new TaskManager(
      runtimeConfig,
      store,
      options.executor || new StrictAppServerExecutor(runtimeConfig),
    )
    const readiness = resolveRuntimeReadiness(runtimeConfig)
    await manager.initialize({ resume: readiness.ready })
    const app = createApp(runtimeConfig, manager)
    server = createServer(app)
    await listenHttpServer(server, runtimeConfig.port, runtimeConfig.host)
    console.log(`[codex-app-server] started host=${runtimeConfig.host} port=${runtimeConfig.port} runtime=${runtimeConfig.runtimeId} revision=${runtimeConfig.runtimeRevision} ready=${readiness.ready}`)

    let closing: Promise<boolean> | undefined
    let leaseReleased = false
    let ingressStopped = false
    let serverClosed: Promise<void> | undefined
    let stopMonitoring = (): void => undefined
    const recoveryTimer = setInterval(() => {
      if (!resolveRuntimeReadiness(runtimeConfig).ready) return
      void manager!.resumeRecoverableTasks().catch(() => {
        console.error('[codex-app-server] recoverable_task_resume_failed')
      })
    }, RECOVERY_PROBE_INTERVAL_MS)
    recoveryTimer.unref()

    const onRuntimeServerError = (error: Error): void => {
      console.error(`[codex-app-server] http_server_error type=${error.name}`)
    }
    server.on('error', onRuntimeServerError)

    const stopIngress = (): Promise<void> => {
      if (!ingressStopped) {
        ingressStopped = true
        stopMonitoring()
        clearInterval(recoveryTimer)
        server!.off('error', onRuntimeServerError)
        serverClosed = closeHttpServer(server!)
      }
      return serverClosed || Promise.resolve()
    }

    const close = async (): Promise<boolean> => {
      if (leaseReleased) return true
      if (closing) return closing
      const attempt = (async (): Promise<boolean> => {
        const quiesced = await manager!.shutdown(runtimeConfig.shutdownTimeoutMs)
        if (!quiesced) {
          // Keep control/status endpoints alive. The manager is draining, so
          // no new task is accepted, but an operator can still inspect or
          // explicitly cancel an active task.
          console.error('[codex-app-server] shutdown_not_quiesced active_task_preserved lease=retained')
          return false
        }
        const serverClose = stopIngress()
        server!.closeAllConnections()
        await serverClose
        await stateLease!.release()
        leaseReleased = true
        return true
      })()
      closing = attempt
      try {
        const quiesced = await attempt
        if (!quiesced) closing = undefined
        return quiesced
      } catch (error) {
        closing = undefined
        throw error
      }
    }

    const exitProcess = options.exitProcess || ((code: number): never => process.exit(code))
    let exiting: Promise<void> | undefined
    type ExitTrigger =
      | { kind: 'stop-request'; runDir: string; requestId: string }
      | { kind: 'signal'; runDir?: string }
    const stopForProcessExit = (trigger: ExitTrigger): Promise<void> => {
      if (exiting) return exiting
      exiting = (async () => {
        let exitCode = 1
        let signalFailure: LifecycleFailureReason | undefined
        try {
          const quiesced = await close()
          if (quiesced) {
            if (trigger.kind === 'stop-request') {
              await writeShutdownOutcome(trigger.runDir, 'success', trigger.requestId)
            }
            exitCode = 0
          } else if (trigger.kind === 'stop-request') {
            await writeFailureOutcome(trigger.runDir, trigger.requestId)
          } else {
            signalFailure = 'SIGNAL_SHUTDOWN_NOT_QUIESCED'
          }
        } catch {
          if (trigger.kind === 'stop-request') {
            await writeFailureOutcome(trigger.runDir, trigger.requestId)
          } else {
            signalFailure = 'SIGNAL_SHUTDOWN_ERROR'
          }
        }
        if (signalFailure) {
          await writeSignalFailureOutcome(trigger.runDir, runtimeConfig.stateDir, signalFailure)
        }
        if (exitCode !== 0) {
          // A process exit would implicitly kill this Worker's child tree.
          // Preserve the active CLI and leave an auditable latch instead.
          exiting = undefined
          return
        }
        exitProcess(exitCode)
      })()
      return exiting
    }
    const runDir = process.env.CODEX_APP_SERVER_RUN_DIR?.trim()
    if (runDir) {
      stopMonitoring = monitorStopRequest(
        runDir,
        requestId => stopForProcessExit({ kind: 'stop-request', runDir, requestId }),
      )
    }
    if (options.installProcessHandlers !== false) {
      process.once('SIGTERM', () => { void stopForProcessExit({ kind: 'signal', runDir }) })
      process.once('SIGINT', () => { void stopForProcessExit({ kind: 'signal', runDir }) })
    }
    return { manager, server, close }
  } catch (error) {
    await cleanupFailedBootstrap(server, manager, stateLease, runtimeConfig.shutdownTimeoutMs)
    throw error
  }
}

async function writeFailureOutcome(runDir: string, requestId: string): Promise<void> {
  try {
    await writeShutdownOutcome(runDir, 'failure', requestId)
  } catch {
    console.error('[codex-app-server] shutdown_failure_marker_write_failed')
  }
}

async function writeSignalFailureOutcome(
  runDir: string | undefined,
  stateDir: string,
  reason: LifecycleFailureReason,
): Promise<void> {
  if (runDir) {
    try {
      await writeStopFailureLatch(runDir, reason)
      return
    } catch {
      console.error('[codex-app-server] stop_failure_latch_write_failed fallback=state')
    }
  }
  try {
    await writeLifecycleFailureLatch(stateDir, reason)
  } catch {
    console.error('[codex-app-server] lifecycle_failure_latch_write_failed')
  }
}

async function cleanupFailedBootstrap(
  server: Server | undefined,
  manager: TaskManager | undefined,
  stateLease: StateWriterLease | undefined,
  shutdownTimeoutMs: number,
): Promise<void> {
  const serverClose = server ? closeHttpServer(server) : Promise.resolve()
  let quiesced = manager === undefined
  if (manager) {
    try {
      quiesced = await manager.shutdown(shutdownTimeoutMs)
    } catch {
      quiesced = false
    }
  }
  server?.closeAllConnections()
  await serverClose.catch(() => undefined)
  if (quiesced) {
    await stateLease?.release().catch(() => undefined)
  } else if (stateLease && manager) {
    console.error('[codex-app-server] startup_cleanup_not_quiesced lease=retained finalizer=scheduled')
    scheduleFailedBootstrapFinalizer(manager, stateLease, shutdownTimeoutMs)
  }
}

function scheduleFailedBootstrapFinalizer(
  manager: TaskManager,
  stateLease: StateWriterLease,
  shutdownTimeoutMs: number,
): void {
  void (async () => {
    let quiesced = false
    for (let attempt = 1; attempt <= FAILED_BOOTSTRAP_FINALIZER_MAX_ATTEMPTS; attempt++) {
      try {
        if (await manager.shutdown(shutdownTimeoutMs)) {
          quiesced = true
          break
        }
      } catch {
        if (attempt === 1) {
          console.error('[codex-app-server] startup_cleanup_finalizer_quiescence_failed retrying=true')
        }
      }
      if (attempt < FAILED_BOOTSTRAP_FINALIZER_MAX_ATTEMPTS) {
        await delay(FAILED_BOOTSTRAP_FINALIZER_RETRY_MS)
      }
    }
    if (!quiesced) {
      console.error('[codex-app-server] startup_cleanup_finalizer_exhausted lease=retained')
      return
    }

    for (let attempt = 1; attempt <= FAILED_BOOTSTRAP_FINALIZER_MAX_ATTEMPTS; attempt++) {
      try {
        await stateLease.release()
        console.log('[codex-app-server] startup_cleanup_finalized lease=released')
        return
      } catch {
        if (attempt === 1) {
          console.error('[codex-app-server] startup_cleanup_finalizer_release_failed retrying=true')
        }
        if (attempt < FAILED_BOOTSTRAP_FINALIZER_MAX_ATTEMPTS) {
          await delay(FAILED_BOOTSTRAP_FINALIZER_RETRY_MS)
        }
      }
    }
    console.error('[codex-app-server] startup_cleanup_finalizer_release_exhausted lease=retained')
  })().catch(() => {
    console.error('[codex-app-server] startup_cleanup_finalizer_failed lease=retained')
  })
}

function delay(milliseconds: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, milliseconds))
}

function listenHttpServer(server: Server, port: number, host: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const onError = (error: Error): void => {
      cleanup()
      reject(error)
    }
    const onListening = (): void => {
      cleanup()
      resolve()
    }
    const cleanup = (): void => {
      server.off('error', onError)
      server.off('listening', onListening)
    }
    server.once('error', onError)
    server.once('listening', onListening)
    try {
      server.listen(port, host)
    } catch (error) {
      cleanup()
      reject(error)
    }
  })
}

function closeHttpServer(server: Server): Promise<void> {
  if (!server.listening) return Promise.resolve()
  return new Promise((resolve, reject) => {
    server.close(error => {
      if (!error || (error as NodeJS.ErrnoException).code === 'ERR_SERVER_NOT_RUNNING') resolve()
      else reject(error)
    })
  })
}

const entry = process.argv[1] ? pathToFileURL(process.argv[1]).href : ''
if (import.meta.url === entry) {
  void bootstrap().catch(error => {
    console.error(`[codex-app-server] startup_failed type=${error instanceof Error ? error.name : 'Error'}`)
    process.exitCode = 1
  })
}
