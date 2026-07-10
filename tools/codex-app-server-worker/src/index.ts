import { pathToFileURL } from 'node:url'
import { createApp } from './app.js'
import { config } from './config.js'
import { StrictAppServerExecutor } from './app-server/executor.js'
import { TaskStore } from './persistence/task-store.js'
import { resolveRuntimeReadiness } from './runtime-capabilities.js'
import { TaskManager } from './task-manager.js'
import { monitorStopRequest } from './stop-request.js'

const RECOVERY_PROBE_INTERVAL_MS = 1_000

export async function bootstrap(): Promise<{ manager: TaskManager; close: () => Promise<void> }> {
  const store = new TaskStore({
    stateDir: config.stateDir,
    encryptionKey: config.stateEncryptionKey || Buffer.alloc(32),
  })
  const manager = new TaskManager(config, store, new StrictAppServerExecutor(config))
  const readiness = resolveRuntimeReadiness(config)
  await manager.initialize({ resume: readiness.ready })
  const app = createApp(config, manager)
  const server = app.listen(config.port, config.host, () => {
    console.log(`[codex-app-server] started host=${config.host} port=${config.port} runtime=${config.runtimeId} revision=${config.runtimeRevision} ready=${readiness.ready}`)
  })
  let closing: Promise<void> | undefined
  let stopMonitoring = (): void => undefined
  const recoveryTimer = setInterval(() => {
    if (!resolveRuntimeReadiness(config).ready) return
    void manager.resumeRecoverableTasks().catch(() => {
      console.error('[codex-app-server] recoverable_task_resume_failed')
    })
  }, RECOVERY_PROBE_INTERVAL_MS)
  recoveryTimer.unref()
  const close = async (): Promise<void> => {
    if (closing) return closing
    closing = (async () => {
      stopMonitoring()
      clearInterval(recoveryTimer)
      const serverClosed = new Promise<void>((resolve, reject) =>
        server.close(error => error ? reject(error) : resolve()),
      )
      await manager.shutdown(config.shutdownTimeoutMs)
      server.closeAllConnections()
      await serverClosed
    })()
    return closing
  }
  const runDir = process.env.CODEX_APP_SERVER_RUN_DIR?.trim()
  if (runDir) {
    stopMonitoring = monitorStopRequest(runDir, async () => {
      await close().finally(() => process.exit(0))
    })
  }
  process.once('SIGTERM', () => { void close().finally(() => process.exit(0)) })
  process.once('SIGINT', () => { void close().finally(() => process.exit(0)) })
  return { manager, close }
}

const entry = process.argv[1] ? pathToFileURL(process.argv[1]).href : ''
if (import.meta.url === entry) void bootstrap()
