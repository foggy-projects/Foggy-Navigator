import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import { StrictAppServerExecutor } from '../src/app-server/executor.js'
import { AppServerPool, type PoolRuntimeInstance } from '../src/app-server/pool.js'
import type { AppServerTurnResult, PersistentTurnOptions } from '../src/app-server/runtime.js'
import type { StoredTaskRecord, TerminationOperationSummary } from '../src/models.js'
import { EventBroadcast } from '../src/persistence/event-store.js'
import { tempDirectory, testConfig, waitFor } from './helpers.js'

test('managed process snapshot is read-only and a mismatched identity cannot dispatch PID termination', async t => {
  const stateDir = await tempDirectory('codex-app-managed-process-lease-')
  const workspace = `${stateDir}-workspace`
  await fs.mkdir(workspace, { recursive: true })
  const config = testConfig(stateDir, { allowedCwds: [workspace] })
  const runtime = new BlockingManagedRuntime()
  const pool = new AppServerPool(config, async () => runtime)
  const executor = new StrictAppServerExecutor(config, pool)
  t.after(async () => {
    await pool.drain(100).catch(() => undefined)
    await fs.rm(stateDir, { recursive: true, force: true })
    await fs.rm(workspace, { recursive: true, force: true })
  })

  const taskId = 'managed-process-task'
  const execution = executor.execute({
    taskId,
    request: { prompt: 'remain running', cwd: workspace },
    signal: new AbortController().signal,
    broadcast: new EventBroadcast(taskId, path.join(stateDir, 'events')),
    callbacks: {
      onInstanceResolved: () => undefined,
      onThreadResolved: () => undefined,
      onExecutionCommitted: () => undefined,
      onTurnStarted: () => undefined,
      onUserInputRequest: async () => ({ answers: {} }),
      onUserInputResolved: () => undefined,
    },
  })
  await waitFor(() => runtime.active)

  const snapshot = executor.listManagedTaskProcesses()
  assert.equal(snapshot.length, 1)
  assert.equal(snapshot[0]?.taskId, taskId)
  assert.equal(snapshot[0]?.pid, runtime.pid)
  assert.match(snapshot[0]?.instanceId || '', /^[0-9a-f-]{36}$/)
  assert.equal(runtime.active, true)
  assert.equal(runtime.closeCalls, 0)
  assert.deepEqual(runtime.forceTerminateCalls, [])

  const record: StoredTaskRecord = {
    schema_version: 1,
    task_id: taskId,
    request_hash: 'request-hash',
    status: 'running',
    thread_id: 'thread-managed',
    turn_id: 'turn-managed',
    created_at: new Date(0).toISOString(),
    updated_at: new Date(0).toISOString(),
  }
  const result = await executor.manualPidKill(
    taskId,
    runtime.pid,
    record,
    manualOperation(taskId, 'app-server-instance:another-runtime'),
  )
  assert.deepEqual(result, { observed_exit: false })
  assert.equal(runtime.active, true)
  assert.equal(runtime.closeCalls, 0)
  assert.deepEqual(runtime.forceTerminateCalls, [])

  runtime.complete()
  await execution
  assert.deepEqual(executor.listManagedTaskProcesses(), [])
})

test('manual PID termination keeps a managed task pending when the runtime cannot observe exit', async t => {
  const stateDir = await tempDirectory('codex-app-managed-process-unconfirmed-')
  const workspace = `${stateDir}-workspace`
  await fs.mkdir(workspace, { recursive: true })
  const config = testConfig(stateDir, { allowedCwds: [workspace] })
  const runtime = new BlockingManagedRuntime(false)
  const pool = new AppServerPool(config, async () => runtime)
  const executor = new StrictAppServerExecutor(config, pool)
  t.after(async () => {
    runtime.complete()
    await pool.drain(100).catch(() => undefined)
    await fs.rm(stateDir, { recursive: true, force: true })
    await fs.rm(workspace, { recursive: true, force: true })
  })

  const taskId = 'managed-process-unconfirmed-task'
  const execution = executor.execute({
    taskId,
    request: { prompt: 'remain running', cwd: workspace },
    signal: new AbortController().signal,
    broadcast: new EventBroadcast(taskId, path.join(stateDir, 'events')),
    callbacks: {
      onInstanceResolved: () => undefined,
      onThreadResolved: () => undefined,
      onExecutionCommitted: () => undefined,
      onTurnStarted: () => undefined,
      onUserInputRequest: async () => ({ answers: {} }),
      onUserInputResolved: () => undefined,
    },
  })
  await waitFor(() => runtime.active)

  const record: StoredTaskRecord = {
    schema_version: 1,
    task_id: taskId,
    request_hash: 'request-hash',
    status: 'running',
    thread_id: 'thread-managed',
    turn_id: 'turn-managed',
    created_at: new Date(0).toISOString(),
    updated_at: new Date(0).toISOString(),
  }
  const snapshot = executor.listManagedTaskProcesses()[0]
  assert.ok(snapshot)
  const result = await executor.manualPidKill(
    taskId,
    runtime.pid,
    record,
    manualOperation(taskId, `app-server-instance:${snapshot.instanceId}`),
  )

  assert.deepEqual(result, { observed_exit: false })
  assert.deepEqual(runtime.forceTerminateCalls, [runtime.pid])
  assert.equal(runtime.active, true)
  assert.equal(executor.listManagedTaskProcesses().length, 1)

  runtime.complete()
  await execution
})

function manualOperation(taskId: string, expectedProcessIdentity: string): TerminationOperationSummary {
  const issuedAt = new Date().toISOString()
  return {
    operation_id: 'manual-pid-operation',
    task_id: taskId,
    worker_id: 'test-navigator-worker',
    kind: 'MANUAL_PID_KILL',
    origin: 'ADMIN_MANUAL',
    actor_id: 'test-user',
    actor_type: 'USER',
    authorization_decision_id: 'decision-test',
    reason_code: 'USER_REQUESTED',
    correlation_id: 'correlation-test',
    issued_at: issuedAt,
    expires_at: new Date(Date.now() + 60_000).toISOString(),
    requested_at: issuedAt,
    status: 'CANCEL_REQUESTED',
    expected_pid: 4242,
    expected_process_identity: expectedProcessIdentity,
  }
}

class BlockingManagedRuntime implements PoolRuntimeInstance {
  readonly pid = 4242
  active = false
  healthy = true
  closeCalls = 0
  readonly forceTerminateCalls: number[] = []
  constructor(private readonly observeExit = true) {}
  private completion?: () => void

  isHealthy(): boolean { return this.healthy }
  isActive(): boolean { return this.active }

  async runTurn(options: PersistentTurnOptions): Promise<AppServerTurnResult> {
    this.active = true
    await options.onThreadResolved?.('thread-managed')
    await options.onExecutionCommitted?.('thread-managed')
    await options.onTurnStarted?.('thread-managed', 'turn-managed')
    await new Promise<void>(resolve => { this.completion = resolve })
    this.active = false
    return {
      threadId: 'thread-managed',
      turn: { id: 'turn-managed', status: 'completed' },
    }
  }

  async readThread(): Promise<Record<string, unknown>> { return { turns: [] } }

  async forceTerminateForAuthorizedOperation(pid: number): Promise<boolean> {
    this.forceTerminateCalls.push(pid)
    this.healthy = false
    if (this.observeExit) this.complete()
    return this.observeExit
  }

  close(): void {
    this.closeCalls++
    this.healthy = false
  }

  complete(): void {
    this.completion?.()
    this.completion = undefined
  }
}
