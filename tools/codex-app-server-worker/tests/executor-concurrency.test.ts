import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import { StrictAppServerExecutor } from '../src/app-server/executor.js'
import { KeyedExecutionLocks } from '../src/app-server/execution-locks.js'
import { AppServerPool, type PoolRuntimeInstance } from '../src/app-server/pool.js'
import {
  AppServerProcessTreeSafetyError,
  type AppServerTurnResult,
  type PersistentTurnOptions,
} from '../src/app-server/runtime.js'
import { EventBroadcast } from '../src/persistence/event-store.js'
import { tempDirectory, testConfig, waitFor } from './helpers.js'

test('different write threads in the same cwd run concurrently on separate exclusive instances', async t => {
  const fixture = await createFixture(t)
  const first = fixture.execute('write-one', { prompt: 'one', cwd: fixture.cwd, session_id: 'thread-one' })
  const second = fixture.execute('write-two', { prompt: 'two', cwd: fixture.cwd, session_id: 'thread-two' })
  await waitFor(() => fixture.controller.started.length === 2)
  assert.equal(fixture.pool.metrics().busy, 2)
  fixture.controller.complete('write-one')
  fixture.controller.complete('write-two')
  await Promise.all([first, second])
  assert.equal(fixture.pool.metrics().created_total, 2)
})

test('write threads through canonical cwd aliases are not serialized', async t => {
  const fixture = await createFixture(t)
  const alias = path.join(path.dirname(fixture.cwd), 'repo-alias')
  try {
    await fs.symlink(fixture.cwd, alias, process.platform === 'win32' ? 'junction' : 'dir')
  } catch (error) {
    if (error instanceof Error && (error as NodeJS.ErrnoException).code === 'EPERM') {
      t.skip('filesystem link creation is not permitted in this environment')
      return
    }
    throw error
  }
  const first = fixture.execute('canonical-one', { prompt: 'one', cwd: fixture.cwd, session_id: 'canonical-thread-one' })
  const second = fixture.execute('canonical-two', { prompt: 'two', cwd: alias, session_id: 'canonical-thread-two' })
  await waitFor(() => fixture.controller.started.length === 2)
  assert.equal(fixture.pool.metrics().busy, 2)
  fixture.controller.complete('canonical-one')
  fixture.controller.complete('canonical-two')
  await Promise.all([first, second])
})

test('executor serializes the same thread even for read-only turns', async t => {
  const fixture = await createFixture(t)
  const first = fixture.execute('thread-one', {
    prompt: 'one', cwd: fixture.cwdA, session_id: 'shared-thread', sandbox_mode: 'read-only',
  })
  await waitFor(() => fixture.controller.started.includes('thread-one'))
  const second = fixture.execute('thread-two', {
    prompt: 'two', cwd: fixture.cwdB, session_id: 'shared-thread', sandbox_mode: 'read-only',
  })
  await new Promise(resolve => setTimeout(resolve, 20))
  assert.deepEqual(fixture.controller.started, ['thread-one'])
  fixture.controller.complete('thread-one')
  await waitFor(() => fixture.controller.started.includes('thread-two'))
  fixture.controller.complete('thread-two')
  await Promise.all([first, second])
})

test('different read-only threads run concurrently on separate exclusive instances', async t => {
  const fixture = await createFixture(t)
  const first = fixture.execute('parallel-one', {
    prompt: 'one', cwd: fixture.cwd, session_id: 'thread-one', sandbox_mode: 'read-only',
  })
  const second = fixture.execute('parallel-two', {
    prompt: 'two', cwd: fixture.cwd, session_id: 'thread-two', sandbox_mode: 'read-only',
  })
  await waitFor(() => fixture.controller.started.length === 2)
  assert.equal(fixture.pool.metrics().busy, 2)
  fixture.controller.complete('parallel-one')
  fixture.controller.complete('parallel-two')
  await Promise.all([first, second])
})

test('non-retrying provider error forces failed result even if the turn reports completed', async t => {
  const stateDir = await tempDirectory('codex-app-provider-error-')
  const config = testConfig(stateDir)
  const pool = new AppServerPool(config, async () => new ProviderErrorRuntime())
  const executor = new StrictAppServerExecutor(config, pool)
  const broadcast = new EventBroadcast('provider-error', path.join(stateDir, 'events'))
  t.after(async () => {
    await pool.drain(100)
    await fs.rm(stateDir, { recursive: true, force: true })
  })
  const result = await executor.execute({
    taskId: 'provider-error',
    request: { prompt: 'inspect', cwd: process.cwd(), sandbox_mode: 'read-only' },
    signal: new AbortController().signal,
    broadcast,
    callbacks: {
      onInstanceResolved: () => undefined,
      onThreadResolved: () => undefined,
      onExecutionCommitted: () => undefined,
      onTurnStarted: () => undefined,
    },
  })
  assert.equal(result.status, 'failed')
  assert.doesNotMatch(JSON.stringify(broadcast.getEventsAfter(0)), /PROVIDER_SECRET_SENTINEL/)
})

test('failed turn retires its process and continuation resumes on a replacement instance', async t => {
  const stateDir = await tempDirectory('codex-app-completed-error-')
  const config = testConfig(stateDir)
  const failedRuntime = new CompletedFailureRuntime()
  const resumedRuntime = new CompletedSuccessRuntime()
  let creations = 0
  const pool = new AppServerPool(config, async () => {
    creations++
    return creations === 1 ? failedRuntime : resumedRuntime
  })
  const executor = new StrictAppServerExecutor(config, pool)
  const callbacks = {
    onInstanceResolved: () => undefined,
    onThreadResolved: () => undefined,
    onExecutionCommitted: () => undefined,
    onTurnStarted: () => undefined,
  }
  t.after(async () => {
    await pool.drain(100)
    await fs.rm(stateDir, { recursive: true, force: true })
  })

  const failed = await executor.execute({
    taskId: 'completed-error',
    request: { prompt: 'inspect', cwd: process.cwd(), sandbox_mode: 'read-only' },
    signal: new AbortController().signal,
    broadcast: new EventBroadcast('completed-error', path.join(stateDir, 'events')),
    callbacks,
  })
  assert.equal(failed.status, 'failed')
  assert.equal(failed.errorCode, 'CODEX_PROVIDER_INTERNAL_ERROR')
  assert.equal(failedRuntime.closed, true)
  assert.equal(pool.metrics().instances, 0)

  const resumed = await executor.execute({
    taskId: 'completed-error-retry',
    request: {
      prompt: 'continue', cwd: process.cwd(), session_id: failed.threadId, sandbox_mode: 'read-only',
    },
    signal: new AbortController().signal,
    broadcast: new EventBroadcast('completed-error-retry', path.join(stateDir, 'events')),
    callbacks,
  })
  assert.equal(resumed.status, 'completed')
  assert.equal(resumedRuntime.resumedThreadId, failed.threadId)
  assert.equal(creations, 2)
})

test('tool capability-loss refusal fails closed and retires the process', async t => {
  const stateDir = await tempDirectory('codex-app-tool-capability-')
  const config = testConfig(stateDir)
  const runtime = new CapabilityLossRuntime()
  const pool = new AppServerPool(config, async () => runtime)
  const executor = new StrictAppServerExecutor(config, pool)
  t.after(async () => {
    await pool.drain(100)
    await fs.rm(stateDir, { recursive: true, force: true })
  })

  const result = await executor.execute({
    taskId: 'tool-capability-loss',
    request: { prompt: '继续推进', cwd: process.cwd(), session_id: 'existing-thread' },
    signal: new AbortController().signal,
    broadcast: new EventBroadcast('tool-capability-loss', path.join(stateDir, 'events')),
    callbacks: {
      onInstanceResolved: () => undefined,
      onThreadResolved: () => undefined,
      onExecutionCommitted: () => undefined,
      onTurnStarted: () => undefined,
    },
  })

  assert.equal(result.status, 'failed')
  assert.equal(result.errorCode, 'APP_SERVER_TOOL_CAPABILITY_UNAVAILABLE')
  assert.equal(runtime.closed, true)
  assert.equal(pool.metrics().instances, 0)
})

test('executor fails the pool closed before releasing the thread lock after process-tree safety failure', async t => {
  const stateDir = await tempDirectory('codex-app-process-tree-safety-')
  const workspaceRoot = `${stateDir}-workspace`
  const cwd = path.join(workspaceRoot, 'repo')
  await fs.mkdir(cwd, { recursive: true })
  const config = testConfig(stateDir, { allowedCwds: [workspaceRoot] })
  const locks = new KeyedExecutionLocks()
  let locksAtFailClosed: ReturnType<KeyedExecutionLocks['metrics']> | undefined
  const pool = new class extends AppServerPool {
    override failClosed(error: Error): void {
      locksAtFailClosed = locks.metrics()
      super.failClosed(error)
    }
  }(config, async () => new ProcessTreeUnsafeRuntime())
  const executor = new StrictAppServerExecutor(config, pool, locks)
  t.after(async () => {
    await pool.drain(100).catch(() => undefined)
    await fs.rm(stateDir, { recursive: true, force: true })
    await fs.rm(workspaceRoot, { recursive: true, force: true })
  })

  await assert.rejects(executor.execute({
    taskId: 'process-tree-safety',
    request: { prompt: 'inspect', cwd, session_id: 'unsafe-thread' },
    signal: new AbortController().signal,
    broadcast: new EventBroadcast('process-tree-safety', path.join(stateDir, 'events')),
    callbacks: {
      onInstanceResolved: () => undefined,
      onThreadResolved: () => undefined,
      onExecutionCommitted: () => undefined,
      onTurnStarted: () => undefined,
    },
  }), AppServerProcessTreeSafetyError)

  assert.deepEqual(locksAtFailClosed, { active_keys: 1, waiting: 0 })
  assert.deepEqual(locks.metrics(), { active_keys: 0, waiting: 0 })
  assert.equal(pool.isDraining(), true)
})

test('invalid image input removes files materialized before validation failed', async t => {
  const stateDir = await tempDirectory('codex-app-invalid-image-')
  const config = testConfig(stateDir)
  let runtimeCreations = 0
  const pool = new AppServerPool(config, async () => {
    runtimeCreations++
    return new ProviderErrorRuntime()
  })
  const executor = new StrictAppServerExecutor(config, pool)
  const taskId = 'invalid-image-cleanup'
  t.after(async () => {
    await pool.drain(100)
    await fs.rm(stateDir, { recursive: true, force: true })
    await fs.rm(config.codexHome, { recursive: true, force: true })
  })

  await assert.rejects(executor.execute({
    taskId,
    request: {
      prompt: 'inspect',
      cwd: process.cwd(),
      images: [
        { name: 'first.png', data: Buffer.from('first').toString('base64') },
        { name: 'invalid.png', data: '' },
      ],
    },
    signal: new AbortController().signal,
    broadcast: new EventBroadcast(taskId, path.join(stateDir, 'events')),
    callbacks: {
      onInstanceResolved: () => undefined,
      onThreadResolved: () => undefined,
      onExecutionCommitted: () => undefined,
      onTurnStarted: () => undefined,
    },
  }), /INVALID_IMAGE_PAYLOAD/)

  const inputRoot = path.join(
    stateDir,
    'input',
    crypto.createHash('sha256').update(taskId).digest('hex'),
  )
  await assert.rejects(fs.access(inputRoot), /ENOENT/)
  assert.equal(runtimeCreations, 0)
})

async function createFixture(t: test.TestContext) {
  const stateDir = await tempDirectory('codex-app-executor-')
  const workspaceRoot = `${stateDir}-workspace`
  const cwd = path.join(workspaceRoot, 'repo')
  const cwdA = path.join(workspaceRoot, 'repo-a')
  const cwdB = path.join(workspaceRoot, 'repo-b')
  await Promise.all([cwd, cwdA, cwdB].map(directory => fs.mkdir(directory, { recursive: true })))
  const config = testConfig(stateDir, {
    poolMaxInstances: 2,
    poolMaxInstancesPerLane: 2,
    allowedCwds: [workspaceRoot],
  })
  const controller = new TurnController()
  const pool = new AppServerPool(config, async () => new ControlledRuntime(controller))
  const executor = new StrictAppServerExecutor(config, pool, new KeyedExecutionLocks())
  t.after(async () => {
    await pool.drain(100)
    await fs.rm(stateDir, { recursive: true, force: true })
    await fs.rm(workspaceRoot, { recursive: true, force: true })
  })
  return {
    controller,
    pool,
    cwd,
    cwdA,
    cwdB,
    execute: (taskId: string, request: Parameters<StrictAppServerExecutor['execute']>[0]['request']) =>
      executor.execute({
        taskId,
        request,
        signal: new AbortController().signal,
        broadcast: new EventBroadcast(taskId, path.join(stateDir, 'events')),
        callbacks: {
          onInstanceResolved: () => undefined,
          onThreadResolved: () => undefined,
          onExecutionCommitted: () => undefined,
          onTurnStarted: () => undefined,
        },
      }),
  }
}

class TurnController {
  readonly started: string[] = []
  private readonly completions = new Map<string, () => void>()

  wait(taskId: string): Promise<void> {
    this.started.push(taskId)
    return new Promise(resolve => this.completions.set(taskId, resolve))
  }

  complete(taskId: string): void {
    this.completions.get(taskId)?.()
    this.completions.delete(taskId)
  }
}

class ControlledRuntime implements PoolRuntimeInstance {
  readonly pid = 1
  private healthy = true
  private active = false
  constructor(private readonly controller: TurnController) {}
  isHealthy(): boolean { return this.healthy }
  isActive(): boolean { return this.active }
  async runTurn(options: PersistentTurnOptions): Promise<AppServerTurnResult> {
    assert.equal(this.active, false)
    this.active = true
    const threadId = options.threadId || `thread-${options.taskId}`
    await options.onThreadResolved?.(threadId)
    await options.onExecutionCommitted?.(threadId)
    await options.onTurnStarted?.(threadId, `turn-${options.taskId}`)
    await this.controller.wait(options.taskId)
    this.active = false
    return { threadId, turn: { id: `turn-${options.taskId}`, status: 'completed' } }
  }
  async readThread(): Promise<Record<string, unknown>> { return { turns: [] } }
  close(): void { this.healthy = false }
}

class ProviderErrorRuntime implements PoolRuntimeInstance {
  readonly pid = 2
  private healthy = true
  isHealthy(): boolean { return this.healthy }
  isActive(): boolean { return false }
  async runTurn(options: PersistentTurnOptions): Promise<AppServerTurnResult> {
    const threadId = 'provider-error-thread'
    const turnId = 'provider-error-turn'
    await options.onThreadResolved?.(threadId)
    await options.onExecutionCommitted?.(threadId)
    await options.onTurnStarted?.(threadId, turnId)
    options.onNotification({
      method: 'error',
      params: {
        threadId,
        turnId,
        willRetry: false,
        error: { message: 'PROVIDER_SECRET_SENTINEL' },
      },
    })
    return { threadId, turn: { id: turnId, status: 'completed' } }
  }
  async readThread(): Promise<Record<string, unknown>> { return { turns: [] } }
  close(): void { this.healthy = false }
}

class ProcessTreeUnsafeRuntime extends ProviderErrorRuntime {
  override async runTurn(): Promise<AppServerTurnResult> {
    throw new AppServerProcessTreeSafetyError()
  }
}

class CompletedFailureRuntime implements PoolRuntimeInstance {
  readonly pid = 3
  closed = false
  isHealthy(): boolean { return !this.closed }
  isActive(): boolean { return false }
  async runTurn(options: PersistentTurnOptions): Promise<AppServerTurnResult> {
    const threadId = options.threadId || 'completed-error-thread'
    await options.onThreadResolved?.(threadId)
    await options.onExecutionCommitted?.(threadId)
    await options.onTurnStarted?.(threadId, 'completed-error-turn')
    return {
      threadId,
      turn: {
        id: 'completed-error-turn',
        status: 'failed',
        error: { message: 'PROVIDER_SECRET_SENTINEL', codexErrorInfo: 'internalServerError' },
      },
    }
  }
  async readThread(): Promise<Record<string, unknown>> { return { turns: [] } }
  close(): void { this.closed = true }
}

class CompletedSuccessRuntime implements PoolRuntimeInstance {
  readonly pid = 4
  closed = false
  resumedThreadId?: string
  isHealthy(): boolean { return !this.closed }
  isActive(): boolean { return false }
  async runTurn(options: PersistentTurnOptions): Promise<AppServerTurnResult> {
    const threadId = options.threadId || 'unexpected-new-thread'
    this.resumedThreadId = options.threadId
    await options.onThreadResolved?.(threadId)
    await options.onExecutionCommitted?.(threadId)
    await options.onTurnStarted?.(threadId, 'resumed-turn')
    return { threadId, turn: { id: 'resumed-turn', status: 'completed' } }
  }
  async readThread(): Promise<Record<string, unknown>> { return { turns: [] } }
  close(): void { this.closed = true }
}

class CapabilityLossRuntime implements PoolRuntimeInstance {
  readonly pid = 5
  closed = false
  isHealthy(): boolean { return !this.closed }
  isActive(): boolean { return false }
  async runTurn(options: PersistentTurnOptions): Promise<AppServerTurnResult> {
    const threadId = options.threadId || 'tool-capability-thread'
    const turnId = 'tool-capability-turn'
    await options.onThreadResolved?.(threadId)
    await options.onExecutionCommitted?.(threadId)
    await options.onTurnStarted?.(threadId, turnId)
    options.onNotification({
      method: 'item/completed',
      params: {
        threadId,
        turnId,
        item: {
          id: 'message-capability-loss',
          type: 'agentMessage',
          text: 'Shell/文件操作工具 `functions.exec` 不再可用，因此无法继续修改文件。',
        },
      },
    })
    return { threadId, turn: { id: turnId, status: 'completed' } }
  }
  async readThread(): Promise<Record<string, unknown>> { return { turns: [] } }
  close(): void { this.closed = true }
}
