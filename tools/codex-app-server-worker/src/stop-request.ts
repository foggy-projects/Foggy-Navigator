import fs from 'node:fs/promises'
import path from 'node:path'

export const STOP_REQUEST_FILE = 'stop.request'

export function monitorStopRequest(
  runDir: string,
  onStop: () => Promise<void>,
  intervalMs = 100,
): () => void {
  const stopFile = path.join(path.resolve(runDir), STOP_REQUEST_FILE)
  let stopping = false
  const timer = setInterval(() => {
    if (stopping) return
    void fs.access(stopFile).then(async () => {
      if (stopping) return
      stopping = true
      await fs.rm(stopFile, { force: true })
      await onStop()
    }).catch(error => {
      if (!isNodeError(error, 'ENOENT')) {
        console.error('[codex-app-server] stop_request_check_failed')
      }
    })
  }, intervalMs)
  timer.unref()
  return () => clearInterval(timer)
}

function isNodeError(error: unknown, code: string): error is NodeJS.ErrnoException {
  return error instanceof Error && (error as NodeJS.ErrnoException).code === code
}
