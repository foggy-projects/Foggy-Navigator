import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import { type AddressInfo } from 'node:net'
import path from 'node:path'
import test from 'node:test'
import { createApp } from '../src/app.js'
import { StrictAppServerExecutor, type ExecutionResult, type TaskExecutor } from '../src/app-server/executor.js'
import { buildAppServerLane } from '../src/app-server/lane.js'
import { AppServerPool, type PoolRuntimeInstance } from '../src/app-server/pool.js'
import {
  AppServerRuntimeError,
  VALIDATED_APP_SERVER_CLI_VERSION,
  type AppServerTurnResult,
  type PersistentTurnOptions,
} from '../src/app-server/runtime.js'
import type { AppConfig } from '../src/config.js'
import type { StoredTaskRecord, TaskRequest } from '../src/models.js'
import { EventBroadcast } from '../src/persistence/event-store.js'
import { TaskStore } from '../src/persistence/task-store.js'
import { TerminationOperationReceiptLedger } from '../src/termination-operation.js'
import { StaleTurnCleanupError } from '../src/stale-turn-cleanup.js'
import { TaskManager } from '../src/task-manager.js'
import { tempDirectory, testConfig } from './helpers.js'

const THREAD_ID = 'stale-native-thread'
const TURN_ID = 'stale-native-turn'
const TERMINAL_AT = '2026-07-18T00:00:00.000Z'

test('stale-turn cleanup returns already_terminal only after an exact native pre-read', async t => {
  const runtime = new StaleTurnRuntime({ reads: ['completed'] })
  const fixture = await createExecutorFixture(t, runtime)

  const result = await fixture.executor.cleanupStaleTurn(fixture.options)

  assert.deepEqual(result, { status: 'already_terminal' })
  assert.deepEqual(runtime.readThreads, [THREAD_ID])
  assert.deepEqual(runtime.interrupts, [])
  assert.deepEqual(runtime.observedTerminal, [[THREAD_ID, TURN_ID]])
  assert.equal(runtime.closed, false)
  assert.equal(fixture.pool.metrics().busy, 0)
  assert.equal(fixture.executor.metrics().retained_stale_turn_cleanup, 0)
})

test('stale-turn cleanup interrupts only the persisted exact native turn and releases after its terminal reread', async t => {
  const runtime = new StaleTurnRuntime({ reads: ['inProgress', 'interrupted'] })
  const fixture = await createExecutorFixture(t, runtime)

  const result = await fixture.executor.cleanupStaleTurn(fixture.options)

  assert.deepEqual(result, { status: 'cleaned' })
  assert.deepEqual(runtime.readThreads, [THREAD_ID, THREAD_ID])
  assert.deepEqual(runtime.interrupts, [[THREAD_ID, TURN_ID]])
  assert.deepEqual(runtime.observedTerminal, [[THREAD_ID, TURN_ID]])
  assert.equal(runtime.closed, false)
  assert.equal(fixture.pool.metrics().busy, 0)
  assert.equal(fixture.executor.metrics().retained_stale_turn_cleanup, 0)
})

test('stale-turn cleanup reuses the exact retained task lease instead of queueing behind an attention-bound child', async t => {
  const runtime = new StaleTurnRuntime({
    reads: ['inProgress', 'interrupted'],
    executeFailure: true,
    requiresAttention: true,
  })
  const fixture = await createExecutorFixture(t, runtime, {
    config: { poolAcquireTimeoutMs: 20 },
  })

  await assert.rejects(
    fixture.executor.execute({
      taskId: fixture.options.taskId,
      request: fixture.options.request,
      signal: new AbortController().signal,
      broadcast: new EventBroadcast(fixture.options.taskId, path.join(fixture.config.stateDir, 'events')),
      callbacks: {
        onInstanceResolved: () => undefined,
        onThreadResolved: () => undefined,
        onExecutionCommitted: () => undefined,
        onTurnStarted: () => undefined,
        onUserInputRequest: async () => ({ answers: {} }),
        onUserInputResolved: () => undefined,
      },
    }),
    AppServerRuntimeError,
  )
  assert.equal(fixture.pool.metrics().busy, 1)
  assert.equal(fixture.factoryCalls(), 1)
  assert.equal(fixture.executor.listManagedTaskProcesses().length, 1)

  const result = await fixture.executor.cleanupStaleTurn(fixture.options)

  assert.deepEqual(result, { status: 'cleaned' })
  assert.deepEqual(runtime.interrupts, [[THREAD_ID, TURN_ID]])
  assert.equal(fixture.factoryCalls(), 1, 'must not reacquire the attention-bound one-child pool')
  assert.deepEqual(fixture.executor.listManagedTaskProcesses(), [])
  assert.equal(fixture.pool.metrics().busy, 0)
})

test('stale-turn cleanup fails closed on a lane, thread, turn, or status affinity ambiguity', async t => {
  await t.test('persisted lane differs from the reconstructed original lane', async nested => {
    const runtime = new StaleTurnRuntime({ reads: ['completed'] })
    const fixture = await createExecutorFixture(nested, runtime, { laneKey: 'wrong-lane' })
    await assertStaleCleanupFailure(
      () => fixture.executor.cleanupStaleTurn(fixture.options),
      'STALE_TURN_CLEANUP_LANE_AFFINITY_MISMATCH',
    )
    assert.equal(fixture.pool.metrics().instances, 0)
    assert.deepEqual(runtime.interrupts, [])
    assert.deepEqual(runtime.observedTerminal, [])
  })

  const cases: Array<{
    name: string
    runtime: StaleTurnRuntime
    expected: StaleTurnCleanupError['code']
  }> = [
    {
      name: 'native read reports a different thread id',
      runtime: new StaleTurnRuntime({ reads: ['completed'], reportedThreadId: 'other-thread' }),
      expected: 'STALE_TURN_CLEANUP_THREAD_AFFINITY_MISMATCH',
    },
    {
      name: 'native read cannot find the persisted turn',
      runtime: new StaleTurnRuntime({ reads: ['missing'] }),
      expected: 'STALE_TURN_CLEANUP_TURN_NOT_FOUND',
    },
    {
      name: 'native read returns an unrecognized turn status',
      runtime: new StaleTurnRuntime({ reads: ['mystery'] }),
      expected: 'STALE_TURN_CLEANUP_TURN_STATUS_UNKNOWN',
    },
  ]
  for (const scenario of cases) {
    await t.test(scenario.name, async nested => {
      const fixture = await createExecutorFixture(nested, scenario.runtime)
      await assertStaleCleanupFailure(() => fixture.executor.cleanupStaleTurn(fixture.options), scenario.expected)
      assert.deepEqual(scenario.runtime.interrupts, [])
      assert.deepEqual(scenario.runtime.observedTerminal, [])
      assert.equal(scenario.runtime.closed, false)
      // The exact turn was not proven terminal, so a shared child must remain
      // leased instead of being eligible for pool retirement.
      assert.equal(fixture.pool.metrics().busy, 1)
      assert.equal(fixture.executor.metrics().retained_stale_turn_cleanup, 1)
    })
  }
})

test('stale-turn cleanup retains the exact lease whenever native state remains indeterminate', async t => {
  const cases: Array<{
    name: string
    runtime: StaleTurnRuntime
    expected: StaleTurnCleanupError['code']
    timing?: { rereadTimeoutMs: number; rereadIntervalMs: number }
    expectedInterrupts: number
  }> = [
    {
      name: 'the initial native read fails',
      runtime: new StaleTurnRuntime({ reads: [new Error('read failed')] }),
      expected: 'STALE_TURN_CLEANUP_READ_UNAVAILABLE',
      expectedInterrupts: 0,
    },
    {
      name: 'turn interrupt is unavailable',
      runtime: new StaleTurnRuntime({ reads: ['inProgress'], interrupt: 'unavailable' }),
      expected: 'STALE_TURN_CLEANUP_INTERRUPT_UNAVAILABLE',
      expectedInterrupts: 0,
    },
    {
      name: 'turn interrupt dispatch fails',
      runtime: new StaleTurnRuntime({ reads: ['inProgress'], interrupt: 'fail' }),
      expected: 'STALE_TURN_CLEANUP_INTERRUPT_UNAVAILABLE',
      expectedInterrupts: 1,
    },
    {
      name: 'the native reread fails after interrupt dispatch',
      runtime: new StaleTurnRuntime({ reads: ['inProgress', new Error('reread failed')] }),
      expected: 'STALE_TURN_CLEANUP_READ_UNAVAILABLE',
      expectedInterrupts: 1,
    },
    {
      name: 'the native reread remains active until its bounded deadline',
      runtime: new StaleTurnRuntime({ reads: ['inProgress'] }),
      expected: 'STALE_TURN_CLEANUP_REREAD_TIMEOUT',
      timing: { rereadTimeoutMs: 15, rereadIntervalMs: 1 },
      expectedInterrupts: 1,
    },
  ]

  for (const scenario of cases) {
    await t.test(scenario.name, async nested => {
      const fixture = await createExecutorFixture(nested, scenario.runtime, undefined, scenario.timing)
      await assertStaleCleanupFailure(() => fixture.executor.cleanupStaleTurn(fixture.options), scenario.expected)
      assert.equal(scenario.runtime.interrupts.length, scenario.expectedInterrupts)
      assert.deepEqual(scenario.runtime.observedTerminal, [])
      assert.equal(scenario.runtime.closed, false)
      assert.equal(fixture.pool.metrics().busy, 1)
      assert.equal(fixture.executor.metrics().retained_stale_turn_cleanup, 1)

      // A drain attempt must not turn the uncertainty into a close() of a
      // shared app-server child; the retained lease keeps it busy.
      await assert.rejects(fixture.pool.drain(10))
      assert.equal(scenario.runtime.closed, false)
    })
  }
})

test('explicit abort retry reuses its retained exact lease after an unconfirmed first retry', async t => {
  const runtime = new StaleTurnRuntime({ reads: ['inProgress'] })
  const fixture = await createExecutorFixture(
    t,
    runtime,
    { config: { poolAcquireTimeoutMs: 20 } },
    { rereadTimeoutMs: 10, rereadIntervalMs: 1 },
  )

  await assertStaleCleanupFailure(
    () => fixture.executor.retryExplicitAbort(fixture.options),
    'STALE_TURN_CLEANUP_REREAD_TIMEOUT',
  )
  assert.equal(fixture.factoryCalls(), 1)
  assert.equal(fixture.executor.metrics().retained_stale_turn_cleanup, 1)
  assert.equal(fixture.pool.metrics().busy, 1)

  runtime.setReads(['inProgress', 'interrupted'])
  const result = await fixture.executor.retryExplicitAbort(fixture.options)

  assert.deepEqual(result, { status: 'interrupted' })
  assert.equal(fixture.factoryCalls(), 1, 'retry must reuse the exact retained runtime lease')
  assert.equal(fixture.executor.metrics().retained_stale_turn_cleanup, 0)
  assert.equal(fixture.pool.metrics().busy, 0)
  assert.deepEqual(runtime.observedTerminal, [[THREAD_ID, TURN_ID]])
})

test('termination inspection refuses a replacement App Server instance before reading the turn', async t => {
  const runtime = new StaleTurnRuntime({ reads: ['inProgress'] })
  const fixture = await createExecutorFixture(t, runtime)
  fixture.options.record.app_server_instance_id = 'persisted-original-instance'

  const result = await fixture.executor.inspectTermination(fixture.options)

  assert.deepEqual(result, { state: 'binding_mismatch' })
  assert.deepEqual(runtime.readThreads, [])
  assert.equal(fixture.pool.metrics().busy, 0)
})

test('stale-turn cleanup HTTP contract is capability-bound, body-free, replay-safe, and identity-minimal', async t => {
  const fixture = await createHttpFixture(t)
  const bodyRejectedOperation = 'stale-cleanup-body-rejected'
  const bodyRejectedHeaders = staleTurnHeaders(fixture.config, fixture.taskId, {
    operationId: bodyRejectedOperation,
  })
  const nonEmptyBody = await fetch(`${fixture.baseUrl}/api/v1/tasks/${fixture.taskId}/stale-turn-cleanup`, {
    method: 'POST',
    headers: { ...bodyRejectedHeaders, 'Content-Type': 'application/json' },
    body: JSON.stringify({ thread_id: 'caller-must-not-select-a-thread' }),
  })
  assert.equal(nonEmptyBody.status, 400)
  assert.deepEqual(await nonEmptyBody.json(), { error: 'STALE_TURN_CLEANUP_BODY_UNSUPPORTED' })
  assert.equal(fixture.executor.cleanupCalls.length, 0)

  const result = await fetch(`${fixture.baseUrl}/api/v1/tasks/${fixture.taskId}/stale-turn-cleanup`, {
    method: 'POST', headers: bodyRejectedHeaders,
  })
  assert.equal(result.status, 200)
  assert.deepEqual(await result.json(), {
    task_id: fixture.taskId,
    operation_id: bodyRejectedOperation,
    status: 'cleaned',
  })
  assert.equal(fixture.executor.cleanupCalls.length, 1)
  assert.deepEqual(Object.keys(fixture.executor.cleanupCalls[0]!.record).includes('request_payload'), false)

  const replay = await fetch(`${fixture.baseUrl}/api/v1/tasks/${fixture.taskId}/stale-turn-cleanup`, {
    method: 'POST', headers: bodyRejectedHeaders,
  })
  assert.equal(replay.status, 409)
  assert.deepEqual(await replay.json(), { error: 'TERMINATION_OPERATION_REPLAYED' })
  assert.equal(fixture.executor.cleanupCalls.length, 1)

  const cases: Array<{
    name: string
    options: Parameters<typeof staleTurnHeaders>[2]
    status: number
    error: string
  }> = [
    {
      name: 'wrong capability kind',
      options: { operationId: 'stale-cleanup-wrong-kind', kind: 'REMOTE_CANCEL' },
      status: 409,
      error: 'TERMINATION_OPERATION_MISMATCH',
    },
    {
      name: 'wrong capability origin',
      options: { operationId: 'stale-cleanup-wrong-origin', origin: 'UPSTREAM_SYSTEM' },
      status: 400,
      error: 'TERMINATION_OPERATION_INVALID',
    },
    {
      name: 'wrong capability task',
      options: { operationId: 'stale-cleanup-wrong-task', taskId: 'another-task' },
      status: 409,
      error: 'TERMINATION_OPERATION_MISMATCH',
    },
    {
      name: 'wrong Navigator Worker binding',
      options: { operationId: 'stale-cleanup-wrong-worker', workerId: 'other-worker' },
      status: 409,
      error: 'TERMINATION_OPERATION_MISMATCH',
    },
    {
      name: 'process identity claims are forbidden for a native turn cleanup',
      options: {
        operationId: 'stale-cleanup-process-claim',
        expectedPid: 4242,
        expectedProcessIdentity: 'app-server-instance:must-not-be-accepted',
      },
      status: 400,
      error: 'TERMINATION_OPERATION_INVALID',
    },
  ]
  for (const scenario of cases) {
    await t.test(scenario.name, async () => {
      const response = await fetch(`${fixture.baseUrl}/api/v1/tasks/${fixture.taskId}/stale-turn-cleanup`, {
        method: 'POST', headers: staleTurnHeaders(fixture.config, fixture.taskId, scenario.options),
      })
      assert.equal(response.status, scenario.status)
      assert.deepEqual(await response.json(), { error: scenario.error })
      assert.equal(fixture.executor.cleanupCalls.length, 1)
    })
  }

  const corruptOperationId = 'stale-cleanup-corrupt-receipt'
  const receipt = new TerminationOperationReceiptLedger(
    path.join(fixture.config.stateDir, 'termination-operations', 'receipts'),
  ).receiptPathFor(fixture.config.navigatorWorkerId, corruptOperationId)
  await fs.mkdir(path.dirname(receipt), { recursive: true })
  await fs.writeFile(receipt, '{not-json', 'utf8')
  const corruptLedger = await fetch(`${fixture.baseUrl}/api/v1/tasks/${fixture.taskId}/stale-turn-cleanup`, {
    method: 'POST',
    headers: staleTurnHeaders(fixture.config, fixture.taskId, { operationId: corruptOperationId }),
  })
  assert.equal(corruptLedger.status, 503)
  assert.deepEqual(await corruptLedger.json(), { error: 'TERMINATION_OPERATION_REPLAY_LEDGER_UNAVAILABLE' })
  assert.equal(fixture.executor.cleanupCalls.length, 1)
})

test('TaskManager permits only non-tombstoned terminal stale-turn cleanup with its encrypted persisted request', async t => {
  const stateDir = await tempDirectory('codex-app-stale-turn-manager-')
  const config = testConfig(stateDir)
  const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  await store.initialize()
  const executor = new CleanupExecutor('already_terminal')
  const manager = new TaskManager(config, store, executor)
  await seedTerminalTask(store, 'terminal-cleanup-task')
  await manager.initialize()

  t.after(async () => {
    await fs.rm(stateDir, { recursive: true, force: true })
    await fs.rm(config.codexHome, { recursive: true, force: true })
  })

  // A Worker ingress drain does not revoke this narrow maintenance action.
  await manager.shutdown(50)
  assert.equal(manager.isAccepting(), false)
  assert.deepEqual(await manager.cleanupStaleTurn('terminal-cleanup-task'), { status: 'already_terminal' })
  assert.equal(executor.cleanupCalls.length, 1)
  assert.equal(executor.cleanupCalls[0]!.request.prompt, 'persisted original request')
  assert.equal(executor.cleanupCalls[0]!.record.request_payload, undefined)

  await store.accept('nonterminal-cleanup-task', { prompt: 'not terminal yet' })
  await assertStaleCleanupFailure(
    () => manager.cleanupStaleTurn('nonterminal-cleanup-task'),
    'STALE_TURN_CLEANUP_TASK_NOT_TERMINAL',
  )

  await seedTerminalTask(store, 'tombstoned-cleanup-task')
  await store.tombstoneTerminal('tombstoned-cleanup-task')
  await assertStaleCleanupFailure(
    () => manager.cleanupStaleTurn('tombstoned-cleanup-task'),
    'STALE_TURN_CLEANUP_TASK_NOT_TERMINAL',
  )

  await seedTerminalTask(store, 'missing-request-cleanup-task')
  await fs.rm(taskJournalPath(stateDir, 'missing-request-cleanup-task'))
  await assertStaleCleanupFailure(
    () => manager.cleanupStaleTurn('missing-request-cleanup-task'),
    'STALE_TURN_CLEANUP_BINDING_MISSING',
  )
  assert.equal(executor.cleanupCalls.length, 1)
})

async function createExecutorFixture(
  t: test.TestContext,
  runtime: StaleTurnRuntime,
  overrides: { laneKey?: string; config?: Partial<AppConfig> } = {},
  timing?: { rereadTimeoutMs: number; rereadIntervalMs: number },
) {
  const stateDir = await tempDirectory('codex-app-stale-turn-executor-')
  const config = testConfig(stateDir, overrides.config)
  let factoryCalls = 0
  const pool = new AppServerPool(config, async () => {
    factoryCalls++
    return runtime
  })
  const executor = new StrictAppServerExecutor(config, pool, undefined, timing)
  const request: TaskRequest = {
    prompt: 'repair a stale native turn',
    cwd: process.cwd(),
    session_id: THREAD_ID,
    sandbox_mode: 'read-only',
  }
  const expectedLaneKey = await laneKeyFor(config, request)
  const options = {
    taskId: 'stale-cleanup-task',
    request,
    record: staleRecord('stale-cleanup-task', overrides.laneKey || expectedLaneKey),
    signal: new AbortController().signal,
  }
  t.after(async () => {
    await pool.drain(50).catch(() => undefined)
    await fs.rm(stateDir, { recursive: true, force: true })
    await fs.rm(config.codexHome, { recursive: true, force: true })
  })
  return { config, executor, pool, options, factoryCalls: () => factoryCalls }
}

async function createHttpFixture(t: test.TestContext) {
  const stateDir = await tempDirectory('codex-app-stale-turn-http-')
  const config = testConfig(stateDir)
  const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  await store.initialize()
  const taskId = 'stale-cleanup-http-task'
  await seedTerminalTask(store, taskId)
  const executor = new CleanupExecutor('cleaned')
  const manager = new TaskManager(config, store, executor)
  await manager.initialize()
  const server = createApp(config, manager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()))
    await fs.rm(stateDir, { recursive: true, force: true })
    await fs.rm(config.codexHome, { recursive: true, force: true })
  })
  return { baseUrl, config, executor, manager, store, taskId }
}

async function seedTerminalTask(store: TaskStore, taskId: string): Promise<void> {
  await store.accept(taskId, {
    prompt: 'persisted original request',
    cwd: process.cwd(),
    session_id: THREAD_ID,
    sandbox_mode: 'read-only',
  })
  await store.transition(taskId, 'starting')
  await store.transition(taskId, 'committed', { thread_id: THREAD_ID })
  await store.transition(taskId, 'running', {
    thread_id: THREAD_ID,
    turn_id: TURN_ID,
    app_server_lane_key: 'persisted-original-lane',
  })
  await store.transition(taskId, 'terminal', { outcome: 'failed' })
}

function staleRecord(taskId: string, laneKey: string): StoredTaskRecord {
  return {
    schema_version: 1,
    task_id: taskId,
    request_hash: 'test-request-hash',
    status: 'terminal',
    outcome: 'failed',
    thread_id: THREAD_ID,
    turn_id: TURN_ID,
    app_server_lane_key: laneKey,
    created_at: TERMINAL_AT,
    updated_at: TERMINAL_AT,
    terminal_at: TERMINAL_AT,
  }
}

async function laneKeyFor(config: AppConfig, request: TaskRequest): Promise<string> {
  await fs.mkdir(config.codexHome, { recursive: true })
  const lane = await buildAppServerLane({
    cliVersion: VALIDATED_APP_SERVER_CLI_VERSION,
    apiKey: request.api_key || config.openaiApiKey || undefined,
    baseUrl: request.base_url || config.openaiBaseUrl || undefined,
    codexHome: await fs.realpath(config.codexHome),
  })
  return lane.key
}

async function assertStaleCleanupFailure(
  action: () => Promise<unknown>,
  code: StaleTurnCleanupError['code'],
): Promise<void> {
  await assert.rejects(action, (error: unknown) => (
    error instanceof StaleTurnCleanupError && error.code === code
  ))
}

function staleTurnHeaders(
  config: AppConfig,
  defaultTaskId: string,
  options: {
    operationId?: string
    taskId?: string
    workerId?: string
    kind?: 'STALE_TURN_INTERRUPT' | 'REMOTE_CANCEL'
    origin?: 'UPSTREAM_USER' | 'UPSTREAM_SYSTEM' | 'ADMIN_MANUAL'
    expectedPid?: number
    expectedProcessIdentity?: string
  } = {},
): Record<string, string> {
  const operation = {
    schema_version: 1,
    operation_id: options.operationId || `stale-turn-operation-${crypto.randomUUID()}`,
    task_id: options.taskId || defaultTaskId,
    worker_id: options.workerId === undefined ? config.navigatorWorkerId : options.workerId,
    kind: options.kind || 'STALE_TURN_INTERRUPT',
    origin: options.origin || 'UPSTREAM_USER',
    actor_id: 'test-user',
    actor_type: 'USER',
    authorization_decision_id: 'stale-turn-decision',
    reason_code: 'STALE_NATIVE_TURN',
    correlation_id: `stale-turn-correlation-${crypto.randomUUID()}`,
    issued_at: new Date().toISOString(),
    expires_at: new Date(Date.now() + 60_000).toISOString(),
    ...(options.expectedPid === undefined ? {} : { expected_pid: options.expectedPid }),
    ...(options.expectedProcessIdentity === undefined
      ? {}
      : { expected_process_identity: options.expectedProcessIdentity }),
  }
  const encoded = Buffer.from(JSON.stringify(operation)).toString('base64url')
  return {
    Authorization: `Bearer ${config.workerToken}`,
    'X-Navigator-Termination-Operation': encoded,
    'X-Navigator-Termination-Signature': crypto.createHmac('sha256', config.workerToken)
      .update(encoded)
      .digest('base64url'),
  }
}

function taskJournalPath(stateDir: string, taskId: string): string {
  return path.join(stateDir, 'tasks', `${crypto.createHash('sha256').update(taskId).digest('hex')}.jsonl`)
}

type RuntimeRead = 'inProgress' | 'completed' | 'failed' | 'interrupted' | 'missing' | 'mystery' | Error

class StaleTurnRuntime implements PoolRuntimeInstance {
  readonly pid = 9001
  readonly readThreads: string[] = []
  readonly interrupts: Array<[string, string]> = []
  readonly observedTerminal: Array<[string | undefined, string | undefined]> = []
  closed = false
  private readIndex = 0
  private attentionRequired: boolean
  readonly interruptTurn?: (threadId: string, turnId: string) => Promise<void>

  constructor(private readonly options: {
    reads: RuntimeRead[]
    reportedThreadId?: string
    interrupt?: 'available' | 'unavailable' | 'fail'
    executeFailure?: boolean
    requiresAttention?: boolean
  }) {
    this.attentionRequired = options.requiresAttention === true
    if (options.interrupt !== 'unavailable') {
      this.interruptTurn = async (threadId, turnId) => {
        this.interrupts.push([threadId, turnId])
        if (options.interrupt === 'fail') throw new Error('interrupt dispatch failed')
      }
    }
  }

  isHealthy(): boolean { return !this.closed }
  isActive(): boolean { return false }
  requiresAttention(): boolean { return this.attentionRequired }

  setReads(reads: RuntimeRead[]): void {
    this.options.reads = reads
    this.readIndex = 0
  }

  async runTurn(options: PersistentTurnOptions): Promise<AppServerTurnResult> {
    if (this.options.executeFailure) {
      await options.onThreadResolved?.(THREAD_ID)
      await options.onExecutionCommitted?.(THREAD_ID)
      await options.onTurnStarted?.(THREAD_ID, TURN_ID)
      throw new AppServerRuntimeError('native turn remains unverified', {
        executionCommitted: true,
        turnMayHaveStarted: true,
        threadId: THREAD_ID,
        turnId: TURN_ID,
        code: 'APP_SERVER_TURN_STALLED',
        reason: 'stalled',
      })
    }
    throw new Error('stale turn cleanup must not create a new app-server turn')
  }

  async readThread(threadId: string): Promise<Record<string, unknown>> {
    this.readThreads.push(threadId)
    const state = this.options.reads[Math.min(this.readIndex++, this.options.reads.length - 1)]
    if (state instanceof Error) throw state
    return {
      id: this.options.reportedThreadId || threadId,
      turns: state === 'missing' ? [] : [{ id: TURN_ID, status: state }],
    }
  }

  markObservedTerminal(threadId?: string, turnId?: string): void {
    this.observedTerminal.push([threadId, turnId])
    this.attentionRequired = false
  }

  close(): void { this.closed = true }
}

class CleanupExecutor implements TaskExecutor {
  readonly cleanupCalls: Array<Parameters<NonNullable<TaskExecutor['cleanupStaleTurn']>>[0]> = []

  constructor(private readonly cleanupStatus: 'cleaned' | 'already_terminal') {}

  async execute(): Promise<ExecutionResult> {
    throw new Error('seeded stale cleanup task must not execute')
  }

  async cleanupStaleTurn(
    options: Parameters<NonNullable<TaskExecutor['cleanupStaleTurn']>>[0],
  ): Promise<{ status: 'cleaned' | 'already_terminal' }> {
    this.cleanupCalls.push(options)
    return { status: this.cleanupStatus }
  }
}
