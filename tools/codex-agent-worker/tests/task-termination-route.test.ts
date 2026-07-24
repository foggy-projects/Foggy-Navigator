import assert from 'node:assert/strict'
import { createHmac } from 'node:crypto'
import { once } from 'node:events'
import fs from 'node:fs'
import type { AddressInfo } from 'node:net'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import express from 'express'
import { config } from '../src/config.ts'
import { taskBroadcasts, taskRegistry } from '../src/codex/sdk-wrapper.ts'
import { EventBroadcast } from '../src/persistence/event-store.ts'
import { createTasksRouter } from '../src/routes/tasks.ts'
import { TerminationOperationReceiptLedger } from '../src/termination-operation.ts'

const token = 'route-worker-token'
const navigatorWorkerId = 'navigator-worker-route'
const initialNavigatorWorkerId = config.navigatorWorkerId
const initialTerminationOperationLedgerDir = config.terminationOperationLedgerDir
let terminationOperationLedgerDir = ''

function signedCancel(
  taskId: string,
  operationId = 'route-operation-1',
  workerId = navigatorWorkerId,
) {
  const claims = {
    schema_version: 1,
    operation_id: operationId,
    task_id: taskId,
    worker_id: workerId,
    kind: 'REMOTE_CANCEL',
    origin: 'UPSTREAM_USER',
    actor_id: 'user-1',
    actor_type: 'USER',
    authorization_decision_id: 'decision-1',
    reason_code: 'USER_CANCELLED',
    correlation_id: 'correlation-1',
    issued_at: new Date(Date.now() - 1_000).toISOString(),
    expires_at: new Date(Date.now() + 60_000).toISOString(),
  }
  const operation = Buffer.from(JSON.stringify(claims), 'utf8').toString('base64url')
  return {
    'X-Navigator-Termination-Operation': operation,
    'X-Navigator-Termination-Signature': createHmac('sha256', token).update(operation, 'utf8').digest('base64url'),
  }
}

function signedReconcile(taskId: string, operationId = 'route-reconcile-operation-1') {
  const claims = {
    schema_version: 1,
    operation_id: operationId,
    task_id: taskId,
    worker_id: navigatorWorkerId,
    kind: 'RECONCILE_CANCEL',
    origin: 'UPSTREAM_USER',
    actor_id: 'user-1',
    actor_type: 'USER',
    authorization_decision_id: 'decision-reconcile-1',
    reason_code: 'OPERATOR_RECONCILE',
    correlation_id: 'correlation-reconcile-1',
    issued_at: new Date(Date.now() - 1_000).toISOString(),
    expires_at: new Date(Date.now() + 60_000).toISOString(),
  }
  const operation = Buffer.from(JSON.stringify(claims), 'utf8').toString('base64url')
  return {
    'X-Navigator-Termination-Operation': operation,
    'X-Navigator-Termination-Signature': createHmac('sha256', token).update(operation, 'utf8').digest('base64url'),
  }
}

async function startTasksServer(dependencies: Parameters<typeof createTasksRouter>[0] = {}) {
  const app = express()
  app.use(express.json())
  app.use(createTasksRouter(dependencies))
  const server = app.listen(0, '127.0.0.1')
  await once(server, 'listening')
  const address = server.address() as AddressInfo
  return {
    baseUrl: `http://127.0.0.1:${address.port}`,
    close: async () => await new Promise<void>((resolve, reject) => {
      server.close(error => error ? reject(error) : resolve())
    }),
  }
}

test.beforeEach(() => {
  taskRegistry.clear()
  taskBroadcasts.clear()
  terminationOperationLedgerDir = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-task-route-ledger-'))
  config.terminationOperationLedgerDir = terminationOperationLedgerDir
  config.navigatorWorkerId = navigatorWorkerId
})

test('task reconciliation requires durable original receipt, matching unconfirmed event, and fresh zero-process proof', async () => {
  const previousToken = config.workerToken
  config.workerToken = token
  const taskId = `task-reconcile-${Date.now()}`
  const originalOperationId = 'route-original-operation-1'
  const ledger = new TerminationOperationReceiptLedger(terminationOperationLedgerDir)
  ledger.consume(navigatorWorkerId, originalOperationId, Date.now() + 60_000, Date.now())
  const broadcast = new EventBroadcast(taskId)
  broadcast.loadFromDisk()
  broadcast.emit({
    type: 'warning',
    task_id: taskId,
    subtype: 'termination_unconfirmed',
    lifecycle_state: 'CANCEL_REQUESTED',
    termination_operation: {
      operation_id: originalOperationId,
      task_id: taskId,
      worker_id: navigatorWorkerId,
      kind: 'REMOTE_CANCEL',
      origin: 'UPSTREAM_USER',
      actor_id: 'user-1',
      actor_type: 'USER',
      authorization_decision_id: 'decision-original-1',
      reason_code: 'USER_CANCELLED',
      correlation_id: 'correlation-original-1',
      requested_at: new Date().toISOString(),
      status: 'UNCONFIRMED',
      result: 'SDK_CANCEL_PROCESS_BINDING_UNVERIFIED',
    },
    seq: broadcast.nextSeq(),
  })
  await broadcast.flush()
  const server = await startTasksServer({
    terminationReplayLedger: ledger,
    listProcesses: async () => [],
  })

  try {
    const readiness = await fetch(
      `${server.baseUrl}/api/v1/tasks/${taskId}/termination-reconciliation-readiness`
      + `?original_operation_id=${originalOperationId}`,
    )
    const readinessBody = await readiness.json()
    assert.equal(readiness.status, 200)
    assert.equal(readinessBody.dry_run, true)
    assert.equal(readinessBody.reconciliation_allowed, true)
    assert.equal(readinessBody.process_snapshot.total, 0)

    const response = await fetch(`${server.baseUrl}/api/v1/tasks/${taskId}/termination-reconcile`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...signedReconcile(taskId),
      },
      body: JSON.stringify({ original_operation_id: originalOperationId }),
    })
    const body = await response.json()
    assert.equal(response.status, 200)
    assert.equal(body.status, 'aborted')
    assert.equal(body.terminal_observed, true)
    assert.equal(body.terminal_source, 'WORKER_WIDE_ZERO_PROCESS_RECONCILIATION')
    assert.equal(body.termination_operation.operation_id, originalOperationId)
    assert.equal(body.termination_operation.status, 'OBSERVED_EXIT')
    assert.equal(body.reconciliation_operation.kind, 'RECONCILE_CANCEL')

    const events = new EventBroadcast(taskId).loadFromDisk()
    assert.equal(events.filter(event => (
      event.subtype === 'lifecycle_terminal'
      && event.terminal_source === 'WORKER_WIDE_ZERO_PROCESS_RECONCILIATION'
    )).length, 1)

    const replay = await fetch(`${server.baseUrl}/api/v1/tasks/${taskId}/termination-reconcile`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...signedReconcile(taskId),
      },
      body: JSON.stringify({ original_operation_id: originalOperationId }),
    })
    assert.equal(replay.status, 409)
    assert.equal((await replay.json()).code, 'TERMINATION_OPERATION_REPLAYED')
  } finally {
    new EventBroadcast(taskId).cleanup()
    config.workerToken = previousToken
    await server.close()
  }
})

test('task reconciliation fails closed when any durable proof is missing or a Codex process exists', async () => {
  const previousToken = config.workerToken
  config.workerToken = token
  const taskId = `task-reconcile-blocked-${Date.now()}`
  const originalOperationId = 'route-original-operation-blocked'
  const ledger = new TerminationOperationReceiptLedger(terminationOperationLedgerDir)
  const noReceiptServer = await startTasksServer({
    terminationReplayLedger: ledger,
    listProcesses: async () => [],
  })
  try {
    const noReceipt = await fetch(
      `${noReceiptServer.baseUrl}/api/v1/tasks/${taskId}/termination-reconciliation-readiness`
      + `?original_operation_id=${originalOperationId}`,
    )
    assert.equal(noReceipt.status, 409)
    assert.equal(
      (await noReceipt.json()).code,
      'TERMINATION_RECONCILIATION_ORIGINAL_RECEIPT_MISSING',
    )
  } finally {
    await noReceiptServer.close()
  }

  ledger.consume(navigatorWorkerId, originalOperationId, Date.now() + 60_000, Date.now())
  const broadcast = new EventBroadcast(taskId)
  broadcast.emit({
    type: 'warning',
    task_id: taskId,
    subtype: 'termination_unconfirmed',
    lifecycle_state: 'CANCEL_REQUESTED',
    termination_operation: {
      operation_id: originalOperationId,
      task_id: taskId,
      worker_id: navigatorWorkerId,
      kind: 'REMOTE_CANCEL',
      origin: 'UPSTREAM_USER',
      actor_id: 'user-1',
      actor_type: 'USER',
      authorization_decision_id: 'decision-original-blocked',
      reason_code: 'USER_CANCELLED',
      correlation_id: 'correlation-original-blocked',
      requested_at: new Date().toISOString(),
      status: 'UNCONFIRMED',
    },
    seq: broadcast.nextSeq(),
  })
  await broadcast.flush()
  const processServer = await startTasksServer({
    terminationReplayLedger: ledger,
    listProcesses: async () => [{
      pid: 777,
      command: 'codex',
      memory_mb: 1,
      started_at: new Date().toISOString(),
    }],
  })
  try {
    const processPresent = await fetch(
      `${processServer.baseUrl}/api/v1/tasks/${taskId}/termination-reconciliation-readiness`
      + `?original_operation_id=${originalOperationId}`,
    )
    assert.equal(processPresent.status, 409)
    assert.equal(
      (await processPresent.json()).code,
      'TERMINATION_RECONCILIATION_WORKER_PROCESS_PRESENT',
    )
  } finally {
    new EventBroadcast(taskId).cleanup()
    config.workerToken = previousToken
    await processServer.close()
  }
})

test.afterEach(() => {
  taskRegistry.clear()
  taskBroadcasts.clear()
  fs.rmSync(terminationOperationLedgerDir, { recursive: true, force: true })
  config.terminationOperationLedgerDir = initialTerminationOperationLedgerDir
  config.navigatorWorkerId = initialNavigatorWorkerId
})

test('task abort route requires an authenticated operation and ACKs only CANCEL_REQUESTED with lifecycle observability', async () => {
  const previousToken = config.workerToken
  config.workerToken = token
  const abortController = new AbortController()
  taskRegistry.set('task-route-1', {
    taskId: 'task-route-1',
    status: 'running',
    abortController,
    threadId: 'thread-route-1',
    pid: 123,
    startedAt: Date.now(),
  })
  const server = await startTasksServer()

  try {
    const rejected = await fetch(`${server.baseUrl}/api/v1/tasks/task-route-1/abort`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    })
    assert.equal(rejected.status, 400)
    assert.equal((await rejected.json()).code, 'TERMINATION_OPERATION_REQUIRED')
    assert.equal(taskRegistry.get('task-route-1')?.status, 'running')

    const headers = signedCancel('task-route-1')
    const accepted = await fetch(`${server.baseUrl}/api/v1/tasks/task-route-1/abort`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...headers },
      body: '{}',
    })
    const body = await accepted.json()
    assert.equal(accepted.status, 202)
    assert.equal(body.status, 'cancel_requested')
    assert.equal(body.lifecycle_state, 'CANCEL_REQUESTED')
    assert.equal(body.termination_operation.operation_id, 'route-operation-1')
    assert.equal(body.termination_operation.task_id, 'task-route-1')
    assert.equal(body.termination_operation.worker_id, navigatorWorkerId)
    assert.equal(body.termination_operation.status, 'CANCEL_REQUESTED')
    assert.deepEqual(body.available_actions, ['CONTINUE_WAIT', 'QUERY_DIAGNOSTICS', 'CANCEL'])
    assert.equal(taskRegistry.get('task-route-1')?.completedAt, undefined)
    assert.equal(abortController.signal.aborted, true)

    const status = await fetch(`${server.baseUrl}/api/v1/tasks/task-route-1/status`)
    const statusBody = await status.json()
    assert.equal(statusBody.status, 'cancel_requested')
    assert.equal(statusBody.pid, 123)
    assert.equal(statusBody.attention_status, 'CANCELLATION_PENDING_CONFIRMATION')

    const replay = await fetch(`${server.baseUrl}/api/v1/tasks/task-route-1/abort`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...headers },
      body: '{}',
    })
    assert.equal(replay.status, 409)
    assert.equal((await replay.json()).code, 'TERMINATION_OPERATION_REPLAYED')

    const retry = await fetch(`${server.baseUrl}/api/v1/tasks/task-route-1/abort`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...signedCancel('task-route-1', 'route-operation-retry'),
      },
      body: '{}',
    })
    const retryBody = await retry.json()
    assert.equal(retry.status, 202)
    assert.equal(retryBody.status, 'cancel_requested')
    assert.equal(retryBody.termination_operation.operation_id, 'route-operation-retry')
  } finally {
    config.workerToken = previousToken
    await server.close()
  }
})

test('task abort route rejects a signed operation for another Worker or task without aborting the active task', async () => {
  const previousToken = config.workerToken
  config.workerToken = token
  const abortController = new AbortController()
  taskRegistry.set('task-route-2', {
    taskId: 'task-route-2',
    status: 'running',
    abortController,
    startedAt: Date.now(),
  })
  const server = await startTasksServer()

  try {
    const wrongWorker = await fetch(`${server.baseUrl}/api/v1/tasks/task-route-2/abort`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...signedCancel('task-route-2', 'route-operation-wrong-worker', 'another-navigator-worker'),
      },
      body: '{}',
    })
    assert.equal(wrongWorker.status, 409)
    assert.equal((await wrongWorker.json()).code, 'TERMINATION_OPERATION_WORKER_MISMATCH')
    assert.equal(taskRegistry.get('task-route-2')?.status, 'running')
    assert.equal(abortController.signal.aborted, false)

    const response = await fetch(`${server.baseUrl}/api/v1/tasks/task-route-2/abort`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...signedCancel('other-task', 'route-operation-2') },
      body: '{}',
    })
    assert.equal(response.status, 409)
    assert.equal((await response.json()).code, 'TERMINATION_OPERATION_TASK_MISMATCH')
    assert.equal(taskRegistry.get('task-route-2')?.status, 'running')
    assert.equal(abortController.signal.aborted, false)
  } finally {
    config.workerToken = previousToken
    await server.close()
  }
})

test('task abort route fails closed when its durable receipt ledger is corrupt', async () => {
  const previousToken = config.workerToken
  config.workerToken = token
  const abortController = new AbortController()
  taskRegistry.set('task-route-corrupt-ledger', {
    taskId: 'task-route-corrupt-ledger',
    status: 'running',
    abortController,
    startedAt: Date.now(),
  })
  const operationId = 'route-corrupt-ledger-operation'
  const receiptPath = new TerminationOperationReceiptLedger(terminationOperationLedgerDir)
    .receiptPathFor(navigatorWorkerId, operationId)
  fs.writeFileSync(receiptPath, '{not-json', 'utf8')
  const server = await startTasksServer()

  try {
    const response = await fetch(`${server.baseUrl}/api/v1/tasks/task-route-corrupt-ledger/abort`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...signedCancel('task-route-corrupt-ledger', operationId),
      },
      body: '{}',
    })
    assert.equal(response.status, 503)
    assert.equal((await response.json()).code, 'TERMINATION_OPERATION_REPLAY_LEDGER_UNAVAILABLE')
    assert.equal(abortController.signal.aborted, false)
    assert.equal(taskRegistry.get('task-route-corrupt-ledger')?.status, 'running')
  } finally {
    config.workerToken = previousToken
    await server.close()
  }
})
