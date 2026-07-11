import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import {
  LIFECYCLE_FAILURE_FILE,
  monitorStopRequest,
  SHUTDOWN_FAILURE_FILE,
  SHUTDOWN_SUCCESS_FILE,
  STOP_FAILURE_FILE,
  STOP_REQUEST_FILE,
  writeLifecycleFailureLatch,
  writeShutdownOutcome,
  writeStopFailureLatch,
} from '../src/stop-request.js'
import { tempDirectory, waitFor } from './helpers.js'

test('local stop request invokes graceful shutdown once and removes the sentinel', async t => {
  const runDir = await tempDirectory('codex-app-stop-request-')
  let calls = 0
  const requestIds: string[] = []
  const stopMonitoring = monitorStopRequest(runDir, async requestId => {
    calls++
    requestIds.push(requestId)
  }, 5)
  t.after(async () => {
    stopMonitoring()
    await fs.rm(runDir, { recursive: true, force: true })
  })

  const stopFile = path.join(runDir, STOP_REQUEST_FILE)
  await fs.writeFile(stopFile, 'request-123\n', 'utf8')
  await waitFor(() => calls === 1)
  assert.deepEqual(requestIds, ['request-123'])
  await assert.rejects(fs.access(stopFile), error => (
    error instanceof Error && (error as NodeJS.ErrnoException).code === 'ENOENT'
  ))
  await new Promise(resolve => setTimeout(resolve, 20))
  assert.equal(calls, 1)
})

test('shutdown outcome atomically replaces stale markers with only the validated request id', async t => {
  const runDir = await tempDirectory('codex-app-shutdown-outcome-')
  t.after(() => fs.rm(runDir, { recursive: true, force: true }))
  const successFile = path.join(runDir, SHUTDOWN_SUCCESS_FILE)
  const failureFile = path.join(runDir, SHUTDOWN_FAILURE_FILE)
  await fs.writeFile(failureFile, 'stale-request', 'utf8')

  await writeShutdownOutcome(runDir, 'success', 'request-safe_123')

  assert.equal(await fs.readFile(successFile, 'utf8'), 'request-safe_123\n')
  await assert.rejects(fs.access(failureFile), error => (
    error instanceof Error && (error as NodeJS.ErrnoException).code === 'ENOENT'
  ))
  const entries = await fs.readdir(runDir)
  assert.deepEqual(entries, [SHUTDOWN_SUCCESS_FILE])
})

test('shutdown outcome rejects request content that could disclose data', async t => {
  const runDir = await tempDirectory('codex-app-shutdown-outcome-invalid-')
  t.after(() => fs.rm(runDir, { recursive: true, force: true }))

  await assert.rejects(
    writeShutdownOutcome(runDir, 'failure', 'token=secret value'),
    /invalid shutdown request id/,
  )
  assert.deepEqual(await fs.readdir(runDir), [])
})

test('signal failure latches use only fixed safe reason codes and atomic marker names', async t => {
  const runDir = await tempDirectory('codex-app-signal-failure-run-')
  const stateDir = await tempDirectory('codex-app-signal-failure-state-')
  t.after(async () => {
    await fs.rm(runDir, { recursive: true, force: true })
    await fs.rm(stateDir, { recursive: true, force: true })
  })

  await writeStopFailureLatch(runDir, 'SIGNAL_SHUTDOWN_NOT_QUIESCED')
  await writeLifecycleFailureLatch(stateDir, 'SIGNAL_SHUTDOWN_ERROR')
  await writeStopFailureLatch(runDir, 'SIGNAL_SHUTDOWN_ERROR')
  await writeLifecycleFailureLatch(stateDir, 'SIGNAL_SHUTDOWN_NOT_QUIESCED')

  assert.equal(
    await fs.readFile(path.join(runDir, STOP_FAILURE_FILE), 'utf8'),
    'SIGNAL_SHUTDOWN_NOT_QUIESCED\n',
  )
  assert.equal(
    await fs.readFile(path.join(stateDir, LIFECYCLE_FAILURE_FILE), 'utf8'),
    'SIGNAL_SHUTDOWN_ERROR\n',
  )
  assert.deepEqual(await fs.readdir(runDir), [STOP_FAILURE_FILE])
  assert.deepEqual(await fs.readdir(stateDir), [LIFECYCLE_FAILURE_FILE])
})

test('signal failure latch rejects an existing non-file marker without replacing it', async t => {
  const runDir = await tempDirectory('codex-app-signal-failure-unsafe-target-')
  const unsafeTarget = path.join(runDir, STOP_FAILURE_FILE)
  await fs.mkdir(unsafeTarget)
  t.after(() => fs.rm(runDir, { recursive: true, force: true }))

  await assert.rejects(
    writeStopFailureLatch(runDir, 'SIGNAL_SHUTDOWN_ERROR'),
    /existing lifecycle failure marker is unsafe/,
  )
  assert.equal((await fs.lstat(unsafeTarget)).isDirectory(), true)
})

test('signal failure latch rejects arbitrary content without creating evidence files', async t => {
  const runDir = await tempDirectory('codex-app-signal-failure-invalid-')
  t.after(() => fs.rm(runDir, { recursive: true, force: true }))

  await assert.rejects(
    writeStopFailureLatch(runDir, 'Bearer SECRET path=C:\\private' as never),
    /invalid lifecycle failure reason/,
  )
  assert.deepEqual(await fs.readdir(runDir), [])
})
