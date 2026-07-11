import { randomUUID } from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'

export const STOP_REQUEST_FILE = 'stop.request'
export const SHUTDOWN_SUCCESS_FILE = 'shutdown.success'
export const SHUTDOWN_FAILURE_FILE = 'shutdown.failure'
export const STOP_FAILURE_FILE = 'stop.failed'
export const LIFECYCLE_FAILURE_FILE = 'lifecycle.failed'

export type ShutdownOutcome = 'success' | 'failure'
export type LifecycleFailureReason = 'SIGNAL_SHUTDOWN_NOT_QUIESCED' | 'SIGNAL_SHUTDOWN_ERROR'

export function monitorStopRequest(
  runDir: string,
  onStop: (requestId: string) => Promise<void>,
  intervalMs = 100,
): () => void {
  const stopFile = path.join(path.resolve(runDir), STOP_REQUEST_FILE)
  let stopping = false
  const timer = setInterval(() => {
    if (stopping) return
    void fs.readFile(stopFile, 'utf8').then(async content => {
      if (stopping) return
      stopping = true
      await fs.rm(stopFile, { force: true })
      let requestId: string
      try {
        requestId = validateRequestId(content)
      } catch {
        console.error('[codex-app-server] stop_request_invalid')
        return
      }
      await onStop(requestId)
    }).catch(error => {
      if (!isNodeError(error, 'ENOENT')) {
        console.error('[codex-app-server] stop_request_check_failed')
      }
    })
  }, intervalMs)
  timer.unref()
  return () => clearInterval(timer)
}

export async function writeShutdownOutcome(
  runDir: string,
  outcome: ShutdownOutcome,
  requestId: string,
): Promise<void> {
  const validatedRequestId = validateRequestId(requestId)
  const resolvedRunDir = path.resolve(runDir)
  const outcomeFileName = outcome === 'success' ? SHUTDOWN_SUCCESS_FILE : SHUTDOWN_FAILURE_FILE
  const outcomeFile = path.join(resolvedRunDir, outcomeFileName)
  const temporaryFile = path.join(
    resolvedRunDir,
    `.${outcomeFileName}.${process.pid}.${randomUUID()}.tmp`,
  )
  let handle: fs.FileHandle | undefined
  try {
    handle = await fs.open(temporaryFile, 'wx', 0o600)
    await handle.writeFile(`${validatedRequestId}\n`, 'utf8')
    await handle.sync()
    await handle.close()
    handle = undefined
    await Promise.all([
      fs.rm(path.join(resolvedRunDir, SHUTDOWN_SUCCESS_FILE), { force: true }),
      fs.rm(path.join(resolvedRunDir, SHUTDOWN_FAILURE_FILE), { force: true }),
    ])
    await fs.rename(temporaryFile, outcomeFile)
    await syncDirectory(resolvedRunDir)
  } finally {
    await handle?.close().catch(() => undefined)
    await fs.rm(temporaryFile, { force: true }).catch(() => undefined)
  }
}

export async function writeStopFailureLatch(
  runDir: string,
  reason: LifecycleFailureReason,
): Promise<void> {
  await writePrivateMarker(runDir, STOP_FAILURE_FILE, `${validateFailureReason(reason)}\n`)
}

export async function writeLifecycleFailureLatch(
  stateDir: string,
  reason: LifecycleFailureReason,
): Promise<void> {
  await writePrivateMarker(stateDir, LIFECYCLE_FAILURE_FILE, `${validateFailureReason(reason)}\n`)
}

function validateRequestId(value: string): string {
  const requestId = value.trim()
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(requestId)) {
    throw new Error('invalid shutdown request id')
  }
  return requestId
}

function validateFailureReason(value: string): LifecycleFailureReason {
  if (value !== 'SIGNAL_SHUTDOWN_NOT_QUIESCED' && value !== 'SIGNAL_SHUTDOWN_ERROR') {
    throw new Error('invalid lifecycle failure reason')
  }
  return value
}

async function syncDirectory(directory: string): Promise<void> {
  let handle: fs.FileHandle | undefined
  try {
    handle = await fs.open(directory, 'r')
    await handle.sync()
  } catch (error) {
    if (!isUnsupportedDirectorySync(error)) throw error
  } finally {
    await handle?.close().catch(() => undefined)
  }
}

async function writePrivateMarker(directory: string, fileName: string, content: string): Promise<void> {
  const resolvedDirectory = path.resolve(directory)
  const target = path.join(resolvedDirectory, fileName)
  const temporary = path.join(resolvedDirectory, `.${fileName}.${process.pid}.${randomUUID()}.tmp`)
  if (await preserveExistingMarker(target)) return
  let handle: fs.FileHandle | undefined
  try {
    handle = await fs.open(temporary, 'wx', 0o600)
    await handle.writeFile(content, 'utf8')
    await handle.sync()
    await handle.close()
    handle = undefined
    try {
      await fs.link(temporary, target)
    } catch (error) {
      if (!isNodeError(error, 'EEXIST') || !await preserveExistingMarker(target)) throw error
    }
    await syncDirectory(resolvedDirectory)
  } finally {
    await handle?.close().catch(() => undefined)
    await fs.rm(temporary, { force: true }).catch(() => undefined)
  }
}

async function preserveExistingMarker(target: string): Promise<boolean> {
  try {
    const stat = await fs.lstat(target)
    if (!stat.isFile() || stat.isSymbolicLink()) {
      throw new Error('existing lifecycle failure marker is unsafe')
    }
    return true
  } catch (error) {
    if (isNodeError(error, 'ENOENT')) return false
    throw error
  }
}

function isUnsupportedDirectorySync(error: unknown): boolean {
  return process.platform === 'win32'
    && ['EACCES', 'EINVAL', 'EISDIR', 'ENOTSUP', 'EPERM'].some(code => isNodeError(error, code))
}

function isNodeError(error: unknown, code: string): error is NodeJS.ErrnoException {
  return error instanceof Error && (error as NodeJS.ErrnoException).code === code
}
