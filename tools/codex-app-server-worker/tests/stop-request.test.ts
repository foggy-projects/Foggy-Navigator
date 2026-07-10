import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import { monitorStopRequest, STOP_REQUEST_FILE } from '../src/stop-request.js'
import { tempDirectory, waitFor } from './helpers.js'

test('local stop request invokes graceful shutdown once and removes the sentinel', async t => {
  const runDir = await tempDirectory('codex-app-stop-request-')
  let calls = 0
  const stopMonitoring = monitorStopRequest(runDir, async () => { calls++ }, 5)
  t.after(async () => {
    stopMonitoring()
    await fs.rm(runDir, { recursive: true, force: true })
  })

  const stopFile = path.join(runDir, STOP_REQUEST_FILE)
  await fs.writeFile(stopFile, 'stop', 'utf8')
  await waitFor(() => calls === 1)
  await assert.rejects(fs.access(stopFile), error => (
    error instanceof Error && (error as NodeJS.ErrnoException).code === 'ENOENT'
  ))
  await new Promise(resolve => setTimeout(resolve, 20))
  assert.equal(calls, 1)
})
