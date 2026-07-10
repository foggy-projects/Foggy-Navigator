import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import {
  StrictAppServerExecutor,
  type ExecutionResult,
  type ReconciliationResult,
  type TaskExecutor,
} from '../src/app-server/executor.js'
import { AppServerPool, type PoolRuntimeInstance } from '../src/app-server/pool.js'
import type { AppServerTurnResult, PersistentTurnOptions } from '../src/app-server/runtime.js'
import { AppServerRuntimeError } from '../src/app-server/runtime.js'
import type { StoredTaskRecord } from '../src/models.js'
import { EventBroadcast } from '../src/persistence/event-store.js'
import { TaskStore } from '../src/persistence/task-store.js'
import { TaskManager } from '../src/task-manager.js'
import { tempDirectory, testConfig } from './helpers.js'

test('restart reconciliation restores a proven completed turn without executing the prompt again', async t => {
  const fixture = await seedCommitted(t, 'reconcile-completed')
  const executor = new ReconcileExecutor({
    status: 'completed',
    threadId: 'thread-existing',
    turnId: 'turn-existing',
    assistantText: 'recovered result',
    model: 'gpt-5.6-sol',
    instanceId: 'instance-reconciler',
  })
  const recoveredStore = new TaskStore({ stateDir: fixture.stateDir, encryptionKey: fixture.config.stateEncryptionKey! })
  const manager = new TaskManager(fixture.config, recoveredStore, executor)
  await manager.initialize()

  const record = manager.get('reconcile-completed')
  assert.equal(record?.status, 'terminal')
  assert.equal(record?.outcome, 'completed')
  assert.equal(record?.app_server_instance_id, 'instance-reconciler')
  assert.equal(executor.executeCalls, 0)
  assert.equal(executor.reconcileCalls, 1)
  const events = manager.getBroadcast('reconcile-completed').getEventsAfter(0)
  assert.equal(events.at(-1)?.type, 'result')
  assert.equal(events.at(-1)?.result, 'recovered result')
})

test('ambiguous, active, or missing turns become explicit recovery-unknown terminal failures', async t => {
  const fixture = await seedCommitted(t, 'reconcile-unknown')
  const executor = new ReconcileExecutor({ status: 'unknown', threadId: 'thread-existing' })
  const recoveredStore = new TaskStore({ stateDir: fixture.stateDir, encryptionKey: fixture.config.stateEncryptionKey! })
  const manager = new TaskManager(fixture.config, recoveredStore, executor)
  await manager.initialize()

  const record = manager.get('reconcile-unknown')
  assert.equal(record?.status, 'terminal')
  assert.equal(record?.outcome, 'failed')
  assert.equal(record?.error_code, 'APP_SERVER_RECOVERY_UNKNOWN')
  assert.equal(executor.executeCalls, 0)
  assert.equal(manager.getBroadcast('reconcile-unknown').getEventsAfter(0).at(-1)?.subtype, 'APP_SERVER_RECOVERY_UNKNOWN')
})

test('strict executor proves terminal state from thread/read and extracts assistant result', async t => {
  const stateDir = await tempDirectory('codex-app-reconcile-executor-')
  const config = testConfig(stateDir)
  const runtime = new ReadThreadRuntime({
    id: 'thread-existing',
    turns: [{
      id: 'turn-existing',
      status: 'completed',
      items: [
        { type: 'agentMessage', text: 'first' },
        { type: 'agentMessage', text: ' second' },
      ],
    }],
  })
  const pool = new AppServerPool(config, async () => runtime)
  const executor = new StrictAppServerExecutor(config, pool)
  t.after(async () => {
    await pool.drain(100)
    await fs.rm(stateDir, { recursive: true, force: true })
  })
  const withoutTurnId = record('task-without-turn')
  delete withoutTurnId.turn_id
  const ambiguous = await executor.reconcile({
    taskId: 'task-without-turn',
    request: { prompt: 'must not guess the previous turn', model: 'codex-latest' },
    record: withoutTurnId,
    signal: new AbortController().signal,
  })
  assert.equal(ambiguous.status, 'unknown')
  assert.equal(runtime.readCalls, 0, 'missing turn id must never select the last turn from a resumed thread')

  const result = await executor.reconcile({
    taskId: 'task',
    request: { prompt: 'must not run', model: 'codex-latest' },
    record: record('task'),
    signal: new AbortController().signal,
  })
  assert.equal(result.status, 'completed')
  assert.equal(result.assistantText, 'first second')
  assert.equal(runtime.runCalls, 0)
  assert.equal(runtime.readCalls, 1)
  const mismatched = await executor.reconcile({
    taskId: 'task-mismatch',
    request: { prompt: 'must not run', model: 'codex-latest' },
    record: { ...record('task-mismatch'), app_server_lane_key: 'different-auth-lane' },
    signal: new AbortController().signal,
  })
  assert.equal(mismatched.status, 'unknown')
  assert.equal(runtime.readCalls, 1, 'lane mismatch must not query a thread with different credentials')
})

test('restart preserves abort intent and interrupts the exact committed turn before reconciling', async t => {
  const fixture = await seedCommitted(t, 'reconcile-abort-restart')
  await fixture.store.requestAbort('reconcile-abort-restart')
  const runtime = new InterruptibleReadThreadRuntime('thread-existing', 'turn-existing')
  const pool = new AppServerPool(fixture.config, async () => runtime)
  const executor = new StrictAppServerExecutor(fixture.config, pool)
  const recoveredStore = new TaskStore({
    stateDir: fixture.stateDir,
    encryptionKey: fixture.config.stateEncryptionKey!,
  })
  const manager = new TaskManager(fixture.config, recoveredStore, executor)
  t.after(async () => pool.drain(100))

  await manager.initialize()

  assert.deepEqual(runtime.interruptCalls, [{ threadId: 'thread-existing', turnId: 'turn-existing' }])
  assert.equal(runtime.runCalls, 0)
  assert.equal(manager.get('reconcile-abort-restart')?.status, 'terminal')
  assert.equal(manager.get('reconcile-abort-restart')?.outcome, 'aborted')
  assert.equal(manager.get('reconcile-abort-restart')?.error_code, 'TASK_ABORTED')
})

test('an unproven recovered interrupt stays pending and retires its app-server instance', async t => {
  const stateDir = await tempDirectory('codex-app-reconcile-abort-pending-')
  const config = testConfig(stateDir, { abortWaitTimeoutMs: 100 })
  const runtime = new InterruptibleReadThreadRuntime('thread-existing', 'turn-existing', false)
  const pool = new AppServerPool(config, async () => runtime)
  const executor = new StrictAppServerExecutor(config, pool)
  t.after(async () => {
    await pool.drain(100)
    await fs.rm(stateDir, { recursive: true, force: true })
    await fs.rm(config.codexHome, { recursive: true, force: true })
  })

  const result = await executor.reconcile({
    taskId: 'reconcile-abort-pending',
    request: { prompt: 'must not replay', model: 'codex-latest' },
    record: {
      ...record('reconcile-abort-pending'),
      abort_requested_at: new Date(1).toISOString(),
    },
    signal: new AbortController().signal,
  })

  assert.equal(result.status, 'unknown')
  assert.deepEqual(runtime.interruptCalls, [{ threadId: 'thread-existing', turnId: 'turn-existing' }])
  assert.equal(runtime.isHealthy(), false)
  assert.equal(pool.metrics().retired_total, 1)
})

test('post-commit child failure reconciles the exact turn instead of publishing a generic failure', async t => {
  const stateDir = await tempDirectory('codex-app-live-reconcile-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const config = testConfig(stateDir)
  const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const executor = new UncertainFailureExecutor('crash', {
    status: 'completed',
    threadId: 'thread-uncertain',
    turnId: 'turn-uncertain',
    assistantText: 'proved after crash',
    model: 'gpt-5.6-sol',
  })
  const manager = new TaskManager(config, store, executor)
  await manager.initialize()
  await manager.accept('post-commit-crash', { prompt: 'perform one side effect' })
  await waitUntil(() => manager.get('post-commit-crash')?.status === 'terminal')

  assert.equal(executor.reconcileCalls, 1)
  assert.equal(manager.get('post-commit-crash')?.outcome, 'completed')
  assert.equal(manager.getBroadcast('post-commit-crash').getEventsAfter(0).at(-1)?.result, 'proved after crash')
})

test('ambiguous interrupt remains abort-pending until thread reconciliation proves a terminal state', async t => {
  const stateDir = await tempDirectory('codex-app-abort-reconcile-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const config = testConfig(stateDir)
  const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const executor = new UncertainFailureExecutor('interrupt', {
    status: 'unknown',
    threadId: 'thread-uncertain',
    turnId: 'turn-uncertain',
  })
  const manager = new TaskManager(config, store, executor)
  await manager.initialize()
  await manager.accept('ambiguous-interrupt', { prompt: 'perform one side effect' })
  await waitUntil(() => manager.get('ambiguous-interrupt')?.status === 'running')
  const abort = await manager.abort('ambiguous-interrupt')

  assert.equal(executor.reconcileCalls, 1)
  assert.equal(abort?.abort_status, 'abort_pending')
  assert.equal(manager.get('ambiguous-interrupt')?.status, 'running')
  assert.equal(manager.get('ambiguous-interrupt')?.recovery_required, true)
  assert.ok(manager.get('ambiguous-interrupt')?.abort_requested_at)
  assert.equal(manager.get('ambiguous-interrupt')?.outcome, undefined)
  assert.equal(manager.get('ambiguous-interrupt')?.error_code, undefined)
})

test('a durable terminal event is finalized on the next recovery pass after a state write failure', async t => {
  const fixture = await seedCommitted(t, 'reconcile-write-retry')
  const executor = new ReconcileExecutor({
    status: 'completed',
    threadId: 'thread-existing',
    turnId: 'turn-existing',
    assistantText: 'durable before state',
  })
  const recoveredStore = new TaskStore({
    stateDir: fixture.stateDir,
    encryptionKey: fixture.config.stateEncryptionKey!,
  })
  const manager = new TaskManager(fixture.config, recoveredStore, executor)
  await manager.initialize({ resume: false })
  const transition = recoveredStore.transition.bind(recoveredStore)
  let failTerminalOnce = true
  recoveredStore.transition = async (...args: Parameters<TaskStore['transition']>) => {
    if (args[1] === 'terminal' && failTerminalOnce) {
      failTerminalOnce = false
      throw new Error('simulated terminal journal failure')
    }
    return transition(...args)
  }

  await manager.resumeRecoverableTasks()
  assert.equal(manager.get('reconcile-write-retry')?.status, 'committed')
  assert.equal(manager.get('reconcile-write-retry')?.recovery_required, true)
  assert.equal(executor.reconcileCalls, 1)

  await manager.resumeRecoverableTasks()
  assert.equal(manager.get('reconcile-write-retry')?.status, 'terminal')
  assert.equal(manager.get('reconcile-write-retry')?.outcome, 'completed')
  assert.equal(executor.reconcileCalls, 1, 'the durable result must prevent a second app-server query')
})

test('reconciliation retries with a fresh event journal after a transient event write failure', async t => {
  const fixture = await seedCommitted(t, 'reconcile-event-retry')
  const executor = new ReconcileExecutor({
    status: 'completed',
    threadId: 'thread-existing',
    turnId: 'turn-existing',
    assistantText: 'persist on retry',
  })
  const recoveredStore = new TaskStore({
    stateDir: fixture.stateDir,
    encryptionKey: fixture.config.stateEncryptionKey!,
  })
  const manager = new TaskManager(fixture.config, recoveredStore, executor)
  await manager.initialize({ resume: false })
  const eventsDir = path.join(fixture.stateDir, 'events')
  await fs.rm(eventsDir, { recursive: true, force: true })
  await fs.writeFile(eventsDir, 'temporarily unavailable')

  await manager.resumeRecoverableTasks()
  assert.equal(manager.get('reconcile-event-retry')?.status, 'committed')
  assert.equal(manager.get('reconcile-event-retry')?.recovery_required, true)
  assert.equal(executor.reconcileCalls, 1)

  await fs.rm(eventsDir, { force: true })
  await fs.mkdir(eventsDir)
  await manager.resumeRecoverableTasks()
  assert.equal(manager.get('reconcile-event-retry')?.status, 'terminal')
  assert.equal(manager.get('reconcile-event-retry')?.outcome, 'completed')
  assert.equal(executor.reconcileCalls, 2)
  assert.equal(manager.getBroadcast('reconcile-event-retry').getEventsAfter(0).at(-1)?.result, 'persist on retry')
})

async function seedCommitted(t: test.TestContext, taskId: string) {
  const stateDir = await tempDirectory('codex-app-reconcile-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const config = testConfig(stateDir)
  const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  await store.initialize()
  await store.accept(taskId, { prompt: 'must not replay', model: 'codex-latest' })
  await store.transition(taskId, 'starting')
  await store.transition(taskId, 'committed', {
    thread_id: 'thread-existing',
    turn_id: 'turn-existing',
    app_server_instance_id: 'instance-before-restart',
  })
  return { stateDir, config, store }
}

function record(taskId: string): StoredTaskRecord {
  return {
    schema_version: 1,
    task_id: taskId,
    request_hash: 'hash',
    request_payload: { algorithm: 'aes-256-gcm', iv: '', auth_tag: '', ciphertext: '' },
    status: 'committed',
    thread_id: 'thread-existing',
    turn_id: 'turn-existing',
    created_at: new Date(0).toISOString(),
    updated_at: new Date(0).toISOString(),
  }
}

class ReconcileExecutor implements TaskExecutor {
  executeCalls = 0
  reconcileCalls = 0
  constructor(private readonly result: ReconciliationResult) {}
  async execute(): Promise<ExecutionResult> {
    this.executeCalls++
    throw new Error('execute must not be called during committed reconciliation')
  }
  async reconcile(): Promise<ReconciliationResult> {
    this.reconcileCalls++
    return this.result
  }
}

class ReadThreadRuntime implements PoolRuntimeInstance {
  readonly pid = 1
  runCalls = 0
  readCalls = 0
  private healthy = true
  constructor(private readonly thread: Record<string, unknown>) {}
  isHealthy(): boolean { return this.healthy }
  isActive(): boolean { return false }
  async runTurn(_options: PersistentTurnOptions): Promise<AppServerTurnResult> {
    this.runCalls++
    throw new Error('must not execute')
  }
  async readThread(): Promise<Record<string, unknown>> {
    this.readCalls++
    return this.thread
  }
  close(): void { this.healthy = false }
}

class InterruptibleReadThreadRuntime implements PoolRuntimeInstance {
  readonly pid = 2
  runCalls = 0
  readCalls = 0
  readonly interruptCalls: Array<{ threadId: string; turnId: string }> = []
  private healthy = true
  private interrupted = false

  constructor(
    private readonly threadId: string,
    private readonly turnId: string,
    private readonly proveInterrupted = true,
  ) {}

  isHealthy(): boolean { return this.healthy }
  isActive(): boolean { return false }
  async runTurn(_options: PersistentTurnOptions): Promise<AppServerTurnResult> {
    this.runCalls++
    throw new Error('must not execute')
  }
  async readThread(threadId: string): Promise<Record<string, unknown>> {
    this.readCalls++
    assert.equal(threadId, this.threadId)
    return {
      id: this.threadId,
      turns: [{ id: this.turnId, status: this.interrupted ? 'interrupted' : 'inProgress' }],
    }
  }
  async interruptTurn(threadId: string, turnId: string): Promise<void> {
    this.interruptCalls.push({ threadId, turnId })
    if (this.proveInterrupted) this.interrupted = true
  }
  close(): void { this.healthy = false }
}

class UncertainFailureExecutor implements TaskExecutor {
  reconcileCalls = 0

  constructor(
    private readonly failure: 'crash' | 'interrupt',
    private readonly reconciliation: ReconciliationResult,
  ) {}

  async execute(options: Parameters<TaskExecutor['execute']>[0]): Promise<ExecutionResult> {
    await options.callbacks.onInstanceResolved('instance-uncertain', 'lane-uncertain')
    await options.callbacks.onThreadResolved('thread-uncertain')
    await options.callbacks.onExecutionCommitted('thread-uncertain')
    await options.callbacks.onTurnStarted('thread-uncertain', 'turn-uncertain')
    if (this.failure === 'interrupt') {
      await new Promise<void>(resolve => options.signal.addEventListener('abort', () => resolve(), { once: true }))
    }
    throw new AppServerRuntimeError(
      this.failure === 'crash' ? 'child exited' : 'interrupt outcome unknown',
      {
        executionCommitted: true,
        turnMayHaveStarted: true,
        threadId: 'thread-uncertain',
        turnId: 'turn-uncertain',
        reason: this.failure === 'interrupt' ? 'aborted' : 'runtime',
      },
    )
  }

  async reconcile(): Promise<ReconciliationResult> {
    this.reconcileCalls++
    return this.reconciliation
  }
}

async function waitUntil(predicate: () => boolean, timeoutMs = 2_000): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error('condition timed out')
    await new Promise(resolve => setTimeout(resolve, 10))
  }
}
