import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import type { AddressInfo } from 'node:net'
import fs from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import { StrictAppServerExecutor, type ExecutionResult, type TaskExecutor } from '../src/app-server/executor.js'
import { AppServerPool } from '../src/app-server/pool.js'
import { AppServerRuntimeInstance } from '../src/app-server/runtime.js'
import { bootstrap } from '../src/index.js'
import { acquireStateWriterLease, StateStoreSafetyError } from '../src/persistence/state-identity.js'
import { TaskStore } from '../src/persistence/task-store.js'
import {
  LIFECYCLE_FAILURE_FILE,
  SHUTDOWN_FAILURE_FILE,
  SHUTDOWN_SUCCESS_FILE,
  STOP_FAILURE_FILE,
  STOP_REQUEST_FILE,
} from '../src/stop-request.js'
import { FakeExecutor, tempDirectory, testConfig, waitFor } from './helpers.js'
import { createStubbornProcessTreeFixture, isProcessAlive } from './stubborn-app-server-fixture.js'

test('stop request reports success only after quiescence and writer lease release', async t => {
  const stateDir = await tempDirectory('codex-bootstrap-stop-success-state-')
  const runDir = await tempDirectory('codex-bootstrap-stop-success-run-')
  const config = testConfig(stateDir)
  const exitCodes: number[] = []
  const previousRunDir = process.env.CODEX_APP_SERVER_RUN_DIR
  process.env.CODEX_APP_SERVER_RUN_DIR = runDir
  const handle = await bootstrap(config, {
    executor: new FakeExecutor(),
    installProcessHandlers: false,
    exitProcess: code => { exitCodes.push(code) },
  })
  t.after(async () => {
    if (previousRunDir === undefined) delete process.env.CODEX_APP_SERVER_RUN_DIR
    else process.env.CODEX_APP_SERVER_RUN_DIR = previousRunDir
    await handle.close().catch(() => undefined)
    await fs.rm(stateDir, { recursive: true, force: true })
    await fs.rm(runDir, { recursive: true, force: true })
  })

  const requestId = 'shutdown-success-123'
  await fs.writeFile(path.join(runDir, STOP_REQUEST_FILE), `${requestId}\n`, 'utf8')
  await waitFor(() => exitCodes.length === 1)

  assert.deepEqual(exitCodes, [0])
  assert.equal(await fs.readFile(path.join(runDir, SHUTDOWN_SUCCESS_FILE), 'utf8'), `${requestId}\n`)
  await assert.rejects(fs.access(path.join(runDir, SHUTDOWN_FAILURE_FILE)), hasCode('ENOENT'))
  const replacement = await acquireStateWriterLease(config.stateDir)
  await replacement.release()
})

test('stop request reports failure and retains the writer lease when work cannot quiesce', async t => {
  const stateDir = await tempDirectory('codex-bootstrap-stop-failure-state-')
  const runDir = await tempDirectory('codex-bootstrap-stop-failure-run-')
  const config = testConfig(stateDir, { shutdownTimeoutMs: 50 })
  const executor = new ShutdownBlockingExecutor()
  const exitCodes: number[] = []
  const previousRunDir = process.env.CODEX_APP_SERVER_RUN_DIR
  process.env.CODEX_APP_SERVER_RUN_DIR = runDir
  const handle = await bootstrap(config, {
    executor,
    installProcessHandlers: false,
    exitProcess: code => { exitCodes.push(code) },
  })
  t.after(async () => {
    if (previousRunDir === undefined) delete process.env.CODEX_APP_SERVER_RUN_DIR
    else process.env.CODEX_APP_SERVER_RUN_DIR = previousRunDir
    executor.release()
    await waitFor(() => handle.manager.activeCount() === 0).catch(() => undefined)
    await handle.close().catch(() => undefined)
    await fs.rm(stateDir, { recursive: true, force: true })
    await fs.rm(runDir, { recursive: true, force: true })
  })
  await handle.manager.accept('shutdown-outcome-active-task', { prompt: 'remain active' })
  await executor.started

  const requestId = 'shutdown-failure-123'
  await fs.writeFile(path.join(runDir, STOP_REQUEST_FILE), requestId, 'utf8')
  await waitFor(() => exitCodes.length === 1)

  assert.deepEqual(exitCodes, [1])
  assert.equal(await fs.readFile(path.join(runDir, SHUTDOWN_FAILURE_FILE), 'utf8'), `${requestId}\n`)
  await assert.rejects(fs.access(path.join(runDir, SHUTDOWN_SUCCESS_FILE)), hasCode('ENOENT'))
  await assert.rejects(
    acquireStateWriterLease(config.stateDir),
    error => error instanceof StateStoreSafetyError
      && error.code === 'CODEX_STATE_WRITER_LEASE_ACTIVE',
  )
})

test('signal-triggered shutdown exits without writing stop-request outcome markers', async t => {
  const stateDir = await tempDirectory('codex-bootstrap-signal-state-')
  const runDir = await tempDirectory('codex-bootstrap-signal-run-')
  const config = testConfig(stateDir)
  const exitCodes: number[] = []
  const previousRunDir = process.env.CODEX_APP_SERVER_RUN_DIR
  const beforeTerm = new Set(process.listeners('SIGTERM'))
  const beforeInt = new Set(process.listeners('SIGINT'))
  process.env.CODEX_APP_SERVER_RUN_DIR = runDir
  const handle = await bootstrap(config, {
    executor: new FakeExecutor(),
    exitProcess: code => { exitCodes.push(code) },
  })
  const termHandler = process.listeners('SIGTERM').find(listener => !beforeTerm.has(listener))
  const intHandler = process.listeners('SIGINT').find(listener => !beforeInt.has(listener))
  assert.ok(termHandler)
  assert.ok(intHandler)
  t.after(async () => {
    if (previousRunDir === undefined) delete process.env.CODEX_APP_SERVER_RUN_DIR
    else process.env.CODEX_APP_SERVER_RUN_DIR = previousRunDir
    process.removeListener('SIGTERM', termHandler)
    process.removeListener('SIGINT', intHandler)
    await handle.close().catch(() => undefined)
    await fs.rm(stateDir, { recursive: true, force: true })
    await fs.rm(runDir, { recursive: true, force: true })
  })

  termHandler('SIGTERM')
  await waitFor(() => exitCodes.length === 1)

  assert.deepEqual(exitCodes, [0])
  await assert.rejects(fs.access(path.join(runDir, SHUTDOWN_SUCCESS_FILE)), hasCode('ENOENT'))
  await assert.rejects(fs.access(path.join(runDir, SHUTDOWN_FAILURE_FILE)), hasCode('ENOENT'))
  await assert.rejects(fs.access(path.join(runDir, STOP_FAILURE_FILE)), hasCode('ENOENT'))
  await assert.rejects(fs.access(path.join(stateDir, LIFECYCLE_FAILURE_FILE)), hasCode('ENOENT'))
})

test('signal-triggered nonquiescent shutdown latches stop.failed and exits nonzero', async t => {
  const stateDir = await tempDirectory('codex-bootstrap-signal-failure-state-')
  const runDir = await tempDirectory('codex-bootstrap-signal-failure-run-')
  const config = testConfig(stateDir, { shutdownTimeoutMs: 50 })
  const executor = new ShutdownBlockingExecutor()
  const exitCodes: number[] = []
  const previousRunDir = process.env.CODEX_APP_SERVER_RUN_DIR
  const beforeTerm = new Set(process.listeners('SIGTERM'))
  const beforeInt = new Set(process.listeners('SIGINT'))
  process.env.CODEX_APP_SERVER_RUN_DIR = runDir
  const handle = await bootstrap(config, {
    executor,
    exitProcess: code => { exitCodes.push(code) },
  })
  const termHandler = process.listeners('SIGTERM').find(listener => !beforeTerm.has(listener))
  const intHandler = process.listeners('SIGINT').find(listener => !beforeInt.has(listener))
  assert.ok(termHandler)
  assert.ok(intHandler)
  t.after(async () => {
    if (previousRunDir === undefined) delete process.env.CODEX_APP_SERVER_RUN_DIR
    else process.env.CODEX_APP_SERVER_RUN_DIR = previousRunDir
    process.removeListener('SIGTERM', termHandler)
    process.removeListener('SIGINT', intHandler)
    executor.release()
    await waitFor(() => handle.manager.activeCount() === 0).catch(() => undefined)
    await handle.close().catch(() => undefined)
    await fs.rm(stateDir, { recursive: true, force: true })
    await fs.rm(runDir, { recursive: true, force: true })
  })
  await handle.manager.accept('signal-shutdown-active-task', { prompt: 'remain active' })
  await executor.started

  termHandler('SIGTERM')
  await waitFor(() => exitCodes.length === 1)

  assert.deepEqual(exitCodes, [1])
  assert.equal(
    await fs.readFile(path.join(runDir, STOP_FAILURE_FILE), 'utf8'),
    'SIGNAL_SHUTDOWN_NOT_QUIESCED\n',
  )
  await assert.rejects(fs.access(path.join(stateDir, LIFECYCLE_FAILURE_FILE)), hasCode('ENOENT'))
  await assert.rejects(
    acquireStateWriterLease(config.stateDir),
    error => error instanceof StateStoreSafetyError
      && error.code === 'CODEX_STATE_WRITER_LEASE_ACTIVE',
  )
})

test('signal failure falls back to a durable state lifecycle latch when run dir is unavailable', async t => {
  const stateDir = await tempDirectory('codex-bootstrap-signal-fallback-state-')
  const runDir = await tempDirectory('codex-bootstrap-signal-fallback-run-')
  const config = testConfig(stateDir, { shutdownTimeoutMs: 50 })
  const executor = new RecoverableDrainFailureExecutor()
  const exitCodes: number[] = []
  const previousRunDir = process.env.CODEX_APP_SERVER_RUN_DIR
  const beforeTerm = new Set(process.listeners('SIGTERM'))
  const beforeInt = new Set(process.listeners('SIGINT'))
  process.env.CODEX_APP_SERVER_RUN_DIR = runDir
  const handle = await bootstrap(config, {
    executor,
    exitProcess: code => { exitCodes.push(code) },
  })
  const termHandler = process.listeners('SIGTERM').find(listener => !beforeTerm.has(listener))
  const intHandler = process.listeners('SIGINT').find(listener => !beforeInt.has(listener))
  assert.ok(termHandler)
  assert.ok(intHandler)
  await fs.rm(runDir, { recursive: true, force: true })
  await fs.writeFile(runDir, 'run-directory-unavailable', 'utf8')
  t.after(async () => {
    if (previousRunDir === undefined) delete process.env.CODEX_APP_SERVER_RUN_DIR
    else process.env.CODEX_APP_SERVER_RUN_DIR = previousRunDir
    process.removeListener('SIGTERM', termHandler)
    process.removeListener('SIGINT', intHandler)
    executor.recover()
    await handle.close().catch(() => undefined)
    await fs.rm(stateDir, { recursive: true, force: true })
    await fs.rm(runDir, { recursive: true, force: true })
  })

  termHandler('SIGTERM')
  await waitFor(() => exitCodes.length === 1)

  assert.deepEqual(exitCodes, [1])
  assert.equal(
    await fs.readFile(path.join(stateDir, LIFECYCLE_FAILURE_FILE), 'utf8'),
    'SIGNAL_SHUTDOWN_NOT_QUIESCED\n',
  )
})

test('signal drain failure retains real descendant evidence and latches restart', async t => {
  const fixture = await createStubbornProcessTreeFixture(t, 'turn')
  const runDir = await tempDirectory('codex-bootstrap-signal-tree-run-')
  const config = testConfig(fixture.stateDir, { shutdownTimeoutMs: 200 })
  const pool = new AppServerPool(config, (requestedLane, signal) => AppServerRuntimeInstance.start({
    env: requestedLane.env,
    signal,
    spawnProcess: fixture.spawnProcess,
    processTreeStateDir: fixture.stateDir,
    processTreeEntry: fixture.entry,
    requestTimeoutMs: 1_000,
  }))
  const executor = new StrictAppServerExecutor(config, pool)
  const exitCodes: number[] = []
  const previousRunDir = process.env.CODEX_APP_SERVER_RUN_DIR
  const beforeTerm = new Set(process.listeners('SIGTERM'))
  const beforeInt = new Set(process.listeners('SIGINT'))
  process.env.CODEX_APP_SERVER_RUN_DIR = runDir
  const handle = await bootstrap(config, {
    executor,
    exitProcess: code => { exitCodes.push(code) },
  })
  const termHandler = process.listeners('SIGTERM').find(listener => !beforeTerm.has(listener))
  const intHandler = process.listeners('SIGINT').find(listener => !beforeInt.has(listener))
  assert.ok(termHandler)
  assert.ok(intHandler)
  t.after(async () => {
    if (previousRunDir === undefined) delete process.env.CODEX_APP_SERVER_RUN_DIR
    else process.env.CODEX_APP_SERVER_RUN_DIR = previousRunDir
    process.removeListener('SIGTERM', termHandler)
    process.removeListener('SIGINT', intHandler)
    await pool.drain(100).catch(() => undefined)
    await fs.rm(runDir, { recursive: true, force: true })
  })

  await handle.manager.accept('signal-tree-active-task', {
    prompt: 'hold the real app-server turn',
    cwd: process.cwd(),
  })
  await fixture.waitForTurnStart()
  const descendantPid = await fixture.readDescendantPid()
  const processTreeRoot = path.join(fixture.stateDir, 'runtime-process-trees')
  const snapshotDirectory = (await fs.readdir(processTreeRoot, { withFileTypes: true }))
    .find(entry => entry.isDirectory())
  assert.ok(snapshotDirectory)
  await fs.rm(path.join(processTreeRoot, snapshotDirectory.name, 'tree.json'))

  termHandler('SIGTERM')
  await waitFor(() => exitCodes.length === 1, 5_000)
  const cleanupFailure = path.join(processTreeRoot, snapshotDirectory.name, 'cleanup.failure')
  await waitForFile(cleanupFailure, 5_000)

  assert.deepEqual(exitCodes, [1])
  assert.equal(await fs.readFile(path.join(runDir, STOP_FAILURE_FILE), 'utf8'), 'SIGNAL_SHUTDOWN_NOT_QUIESCED\n')
  assert.equal(isProcessAlive(descendantPid), true)
  assert.equal(
    (JSON.parse(await fs.readFile(cleanupFailure, 'utf8')) as Record<string, unknown>).reason,
    'CLOSE_CLEANUP_UNPROVEN',
  )
  assert.equal(pool.isDraining(), true)
  await assert.rejects(
    acquireStateWriterLease(config.stateDir),
    error => error instanceof StateStoreSafetyError
      && error.code === 'CODEX_STATE_WRITER_LEASE_ACTIVE',
  )
})

test('bootstrap retains the writer lease when an active task misses the shutdown deadline', async t => {
  const stateDir = await tempDirectory('codex-bootstrap-active-shutdown-')
  const config = testConfig(stateDir, { shutdownTimeoutMs: 50 })
  const executor = new ShutdownBlockingExecutor()
  const handle = await bootstrap(config, { executor, installProcessHandlers: false })
  t.after(async () => {
    executor.release()
    await waitFor(() => handle.manager.activeCount() === 0).catch(() => undefined)
    await handle.close().catch(() => undefined)
    await fs.rm(config.stateDir, { recursive: true, force: true })
  })
  assert.equal(handle.server.listening, true)

  await handle.manager.accept('shutdown-active-task', { prompt: 'hold a durable writer open' })
  await executor.started

  assert.equal(await handle.close(), false)
  assert.equal(handle.server.listening, false)
  await assert.rejects(
    acquireStateWriterLease(config.stateDir),
    error => error instanceof StateStoreSafetyError
      && error.code === 'CODEX_STATE_WRITER_LEASE_ACTIVE',
  )

  executor.release()
  await waitFor(() => handle.manager.activeCount() === 0)
  assert.equal(await handle.close(), true)
  const replacement = await acquireStateWriterLease(config.stateDir)
  await replacement.release()
})

test('bootstrap awaits listening and releases its writer lease after EADDRINUSE', async t => {
  const occupied = createServer()
  occupied.listen(0, '127.0.0.1')
  await new Promise<void>((resolve, reject) => {
    occupied.once('listening', resolve)
    occupied.once('error', reject)
  })
  t.after(() => new Promise<void>(resolve => occupied.close(() => resolve())))
  const port = (occupied.address() as AddressInfo).port
  const stateDir = await tempDirectory('codex-bootstrap-address-in-use-')
  const config = testConfig(stateDir, { port, shutdownTimeoutMs: 100 })
  t.after(() => fs.rm(config.stateDir, { recursive: true, force: true }))

  await assert.rejects(
    bootstrap(config, { executor: new FakeExecutor(), installProcessHandlers: false }),
    error => (error as NodeJS.ErrnoException).code === 'EADDRINUSE',
  )

  const replacement = await acquireStateWriterLease(config.stateDir)
  await replacement.release()
})

test('failed bind releases its retained writer lease only after recovered work quiesces', async t => {
  const occupied = createServer()
  occupied.listen(0, '127.0.0.1')
  await new Promise<void>((resolve, reject) => {
    occupied.once('listening', resolve)
    occupied.once('error', reject)
  })
  t.after(() => new Promise<void>(resolve => occupied.close(() => resolve())))
  const port = (occupied.address() as AddressInfo).port
  const stateDir = await tempDirectory('codex-bootstrap-active-address-in-use-')
  const config = testConfig(stateDir, { port, shutdownTimeoutMs: 50 })
  const seed = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  await seed.initialize()
  await seed.accept('recovered-active-task', { prompt: 'finish before releasing the writer lease' })
  const executor = new ShutdownBlockingExecutor()
  t.after(async () => {
    executor.release()
    await waitForLeaseRemoval(config.stateDir).catch(() => undefined)
    await fs.rm(config.stateDir, { recursive: true, force: true })
  })

  await assert.rejects(
    bootstrap(config, { executor, installProcessHandlers: false }),
    error => (error as NodeJS.ErrnoException).code === 'EADDRINUSE',
  )
  await executor.started
  await assert.rejects(
    acquireStateWriterLease(config.stateDir),
    error => error instanceof StateStoreSafetyError
      && error.code === 'CODEX_STATE_WRITER_LEASE_ACTIVE',
  )

  executor.release()
  await waitForLeaseRemoval(config.stateDir)
  const replacement = await acquireStateWriterLease(config.stateDir)
  await replacement.release()
})

test('failed bootstrap finalizer stops retrying after a permanent drain failure', async t => {
  const occupied = createServer()
  occupied.listen(0, '127.0.0.1')
  await new Promise<void>((resolve, reject) => {
    occupied.once('listening', resolve)
    occupied.once('error', reject)
  })
  t.after(() => new Promise<void>(resolve => occupied.close(() => resolve())))
  const port = (occupied.address() as AddressInfo).port
  const stateDir = await tempDirectory('codex-bootstrap-permanent-drain-failure-')
  const config = testConfig(stateDir, { port, shutdownTimeoutMs: 50 })
  const executor = new PermanentDrainFailureExecutor()
  t.after(() => fs.rm(config.stateDir, { recursive: true, force: true }))

  await assert.rejects(
    bootstrap(config, { executor, installProcessHandlers: false }),
    error => (error as NodeJS.ErrnoException).code === 'EADDRINUSE',
  )

  await waitFor(() => executor.drainCalls === 4)
  await new Promise(resolve => setTimeout(resolve, 250))
  assert.equal(executor.drainCalls, 4)
  await assert.rejects(
    acquireStateWriterLease(config.stateDir),
    error => error instanceof StateStoreSafetyError
      && error.code === 'CODEX_STATE_WRITER_LEASE_ACTIVE',
  )
})

class ShutdownBlockingExecutor implements TaskExecutor {
  private releaseExecution!: () => void
  private markStarted!: () => void
  private readonly executionGate = new Promise<void>(resolve => { this.releaseExecution = resolve })
  readonly started = new Promise<void>(resolve => { this.markStarted = resolve })

  release(): void {
    this.releaseExecution()
  }

  async execute(options: Parameters<TaskExecutor['execute']>[0]): Promise<ExecutionResult> {
    await options.callbacks.onInstanceResolved('shutdown-instance', 'shutdown-lane')
    await options.callbacks.onThreadResolved('shutdown-thread')
    await options.callbacks.onExecutionCommitted('shutdown-thread')
    await options.callbacks.onTurnStarted('shutdown-thread', 'shutdown-turn')
    this.markStarted()
    await this.executionGate
    return {
      threadId: 'shutdown-thread',
      turnId: 'shutdown-turn',
      status: 'completed',
      assistantText: 'done',
      inputTokens: 1,
      outputTokens: 1,
      model: 'gpt-5.6-sol',
      durationMs: 1,
    }
  }
}

class PermanentDrainFailureExecutor extends FakeExecutor {
  drainCalls = 0

  async drain(): Promise<void> {
    this.drainCalls++
    throw new Error('permanent drain failure')
  }
}

class RecoverableDrainFailureExecutor extends FakeExecutor {
  private failing = true

  recover(): void {
    this.failing = false
  }

  async drain(): Promise<void> {
    if (this.failing) throw new Error('recoverable drain failure')
  }
}

async function waitForLeaseRemoval(stateDir: string, timeoutMs = 2_000): Promise<void> {
  const leaseFile = path.join(stateDir, '.codex-store-writer-lease.json')
  const deadline = Date.now() + timeoutMs
  while (true) {
    try {
      await fs.access(leaseFile)
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ENOENT') return
      throw error
    }
    if (Date.now() >= deadline) throw new Error('writer lease removal timed out')
    await new Promise(resolve => setTimeout(resolve, 10))
  }
}

async function waitForFile(file: string, timeoutMs: number): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (true) {
    try {
      await fs.access(file)
      return
    } catch (error) {
      if (!hasCode('ENOENT')(error)) throw error
    }
    if (Date.now() >= deadline) throw new Error('expected lifecycle evidence was not written')
    await new Promise(resolve => setTimeout(resolve, 10))
  }
}

function hasCode(code: string): (error: unknown) => boolean {
  return error => error instanceof Error && (error as NodeJS.ErrnoException).code === code
}
