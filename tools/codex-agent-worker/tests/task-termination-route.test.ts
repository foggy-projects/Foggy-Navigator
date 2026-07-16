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

async function startTasksServer() {
  const app = express()
  app.use(express.json())
  app.use(createTasksRouter())
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
