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
import { CodexProcessKillError } from '../src/codex/processes.ts'
import { createProcessesRouter } from '../src/routes/processes.ts'

const token = 'process-route-worker-token'
const navigatorWorkerId = 'navigator-worker-process-route'
const initialNavigatorWorkerId = config.navigatorWorkerId
const initialTerminationOperationLedgerDir = config.terminationOperationLedgerDir
let terminationOperationLedgerDir = ''
const processStartedAt = '2026-07-16T00:00:00.000Z'

function signedManualKill(
  taskId: string,
  pid: number,
  operationId = 'process-operation-1',
  expectedPid: number | null = pid,
  workerId = navigatorWorkerId,
  expectedProcessIdentity: string | null = expectedPid === null
    ? null
    : `codex-cli:${expectedPid}:${processStartedAt}`,
) {
  const claims = {
    schema_version: 1,
    operation_id: operationId,
    task_id: taskId,
    worker_id: workerId,
    kind: 'MANUAL_PID_KILL',
    origin: 'ADMIN_MANUAL',
    actor_id: 'admin-1',
    actor_type: 'ADMIN',
    authorization_decision_id: 'decision-1',
    reason_code: 'ADMIN_PROCESS_KILL',
    correlation_id: 'correlation-1',
    ...(expectedPid === null ? {} : { expected_pid: expectedPid }),
    ...(expectedProcessIdentity === null ? {} : { expected_process_identity: expectedProcessIdentity }),
    issued_at: new Date(Date.now() - 1_000).toISOString(),
    expires_at: new Date(Date.now() + 60_000).toISOString(),
  }
  const operation = Buffer.from(JSON.stringify(claims), 'utf8').toString('base64url')
  return {
    'X-Navigator-Termination-Operation': operation,
    'X-Navigator-Termination-Signature': createHmac('sha256', token).update(operation, 'utf8').digest('base64url'),
  }
}

async function startProcessServer(router: express.Router) {
  const app = express()
  app.use(express.json())
  app.use(router)
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
  terminationOperationLedgerDir = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-process-route-ledger-'))
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

test('manual PID kill requires ADMIN_MANUAL operation and only becomes ABORTED after post-kill exit verification', async () => {
  const previousToken = config.workerToken
  config.workerToken = token
  const abortController = new AbortController()
  taskRegistry.set('task-process-1', {
    taskId: 'task-process-1',
    status: 'running',
    pid: 321,
    abortController,
    startedAt: Date.now(),
  })
  let processes = [{ pid: 321, command: 'codex --experimental-json', memory_mb: 1, started_at: processStartedAt }]
  const router = createProcessesRouter({
    listProcesses: async () => processes,
    killProcess: async () => { processes = [] },
  })
  const server = await startProcessServer(router)

  try {
    const rejected = await fetch(`${server.baseUrl}/api/v1/processes/321/kill`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    })
    assert.equal(rejected.status, 400)
    assert.equal((await rejected.json()).code, 'TERMINATION_OPERATION_REQUIRED')
    assert.equal(taskRegistry.get('task-process-1')?.status, 'running')

    const accepted = await fetch(`${server.baseUrl}/api/v1/processes/321/kill`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...signedManualKill('task-process-1', 321) },
      body: JSON.stringify({ force: false }),
    })
    const body = await accepted.json()
    assert.equal(accepted.status, 200)
    assert.equal(body.status, 'observed_exit')
    assert.equal(body.observed_exit, true)
    assert.equal(body.termination_operation.status, 'OBSERVED_EXIT')
    assert.equal(body.termination_operation.task_id, 'task-process-1')
    assert.equal(body.termination_operation.worker_id, navigatorWorkerId)
    assert.equal(body.termination_operation.expected_process_identity, `codex-cli:321:${processStartedAt}`)
    assert.equal(taskRegistry.get('task-process-1')?.status, 'aborted')
    assert.equal(abortController.signal.aborted, false)
  } finally {
    config.workerToken = previousToken
    await server.close()
  }
})

test('manual PID kill reports unconfirmed and preserves CANCEL_REQUESTED when process exit cannot be verified', async () => {
  const previousToken = config.workerToken
  config.workerToken = token
  taskRegistry.set('task-process-2', {
    taskId: 'task-process-2',
    status: 'running',
    pid: 654,
    startedAt: Date.now(),
  })
  const processes = [{ pid: 654, command: 'codex --experimental-json', memory_mb: 1, started_at: processStartedAt }]
  const router = createProcessesRouter({
    listProcesses: async () => processes,
    killProcess: async () => undefined,
  })
  const server = await startProcessServer(router)

  try {
    const response = await fetch(`${server.baseUrl}/api/v1/processes/654/kill`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...signedManualKill('task-process-2', 654, 'process-operation-2') },
      body: '{}',
    })
    const body = await response.json()
    assert.equal(response.status, 202)
    assert.equal(body.status, 'unconfirmed')
    assert.equal(body.observed_exit, false)
    assert.equal(body.termination_operation.status, 'UNCONFIRMED')
    assert.equal(taskRegistry.get('task-process-2')?.status, 'cancel_requested')
    assert.equal(taskRegistry.get('task-process-2')?.completedAt, undefined)
    assert.equal(taskRegistry.get('task-process-2')?.attention?.at(-1)?.code, 'TERMINATION_UNCONFIRMED')
  } finally {
    config.workerToken = previousToken
    await server.close()
  }
})

test('a PID absent before manual dispatch remains unconfirmed and is never treated as observed exit', async () => {
  const previousToken = config.workerToken
  config.workerToken = token
  let killCalls = 0
  taskRegistry.set('task-process-absent-before-dispatch', {
    taskId: 'task-process-absent-before-dispatch',
    status: 'running',
    pid: 655,
    startedAt: Date.now(),
  })
  const router = createProcessesRouter({
    listProcesses: async () => [],
    killProcess: async () => { killCalls += 1 },
  })
  const server = await startProcessServer(router)

  try {
    const response = await fetch(`${server.baseUrl}/api/v1/processes/655/kill`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...signedManualKill('task-process-absent-before-dispatch', 655, 'process-operation-absent-before-dispatch'),
      },
      body: '{}',
    })
    const body = await response.json()
    assert.equal(response.status, 202)
    assert.equal(body.status, 'unconfirmed')
    assert.equal(body.observed_exit, false)
    assert.equal(body.code, 'PROCESS_ABSENT_BEFORE_MANUAL_KILL_UNCONFIRMED')
    assert.equal(body.termination_operation.status, 'UNCONFIRMED')
    assert.equal(taskRegistry.get('task-process-absent-before-dispatch')?.status, 'cancel_requested')
    assert.equal(taskRegistry.get('task-process-absent-before-dispatch')?.completedAt, undefined)
    assert.equal(killCalls, 0)
  } finally {
    config.workerToken = previousToken
    await server.close()
  }
})

test('manual PID kill rejects a missing or changed fresh process identity before signalling', async () => {
  const previousToken = config.workerToken
  config.workerToken = token
  let killCalls = 0
  let process = {
    pid: 658,
    command: 'codex --experimental-json',
    memory_mb: 1,
    started_at: '2026-07-16T00:00:01.000Z',
  }
  taskRegistry.set('task-process-identity', {
    taskId: 'task-process-identity',
    status: 'running',
    pid: 658,
    startedAt: Date.now(),
  })
  const router = createProcessesRouter({
    listProcesses: async () => [process],
    killProcess: async () => { killCalls += 1 },
  })
  const server = await startProcessServer(router)

  try {
    const changedIdentity = await fetch(`${server.baseUrl}/api/v1/processes/658/kill`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...signedManualKill('task-process-identity', 658, 'process-operation-identity-changed'),
      },
      body: '{}',
    })
    assert.equal(changedIdentity.status, 409)
    assert.equal(
      (await changedIdentity.json()).code,
      'TERMINATION_OPERATION_PROCESS_IDENTITY_MISMATCH',
    )
    assert.equal(taskRegistry.get('task-process-identity')?.status, 'running')
    assert.equal(killCalls, 0)

    process = { ...process, started_at: '' }
    const missingFreshIdentity = await fetch(`${server.baseUrl}/api/v1/processes/658/kill`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...signedManualKill('task-process-identity', 658, 'process-operation-identity-missing'),
      },
      body: '{}',
    })
    assert.equal(missingFreshIdentity.status, 409)
    assert.equal(
      (await missingFreshIdentity.json()).code,
      'TERMINATION_OPERATION_PROCESS_IDENTITY_MISMATCH',
    )
    assert.equal(taskRegistry.get('task-process-identity')?.status, 'running')
    assert.equal(taskRegistry.get('task-process-identity')?.completedAt, undefined)
    assert.equal(killCalls, 0)
  } finally {
    config.workerToken = previousToken
    await server.close()
  }
})

test('process list and kill failures expose only stable codes and whitelisted metadata', async () => {
  const listFailureRouter = createProcessesRouter({
    listProcesses: async () => {
      throw new Error('stderr includes /workspace/private and Bearer secret-token')
    },
  })
  const listFailureServer = await startProcessServer(listFailureRouter)

  try {
    const response = await fetch(`${listFailureServer.baseUrl}/api/v1/processes`)
    const body = await response.json()
    assert.equal(response.status, 503)
    assert.deepEqual(body, {
      error: 'CODEX_PROCESS_LIST_UNAVAILABLE',
      code: 'CODEX_PROCESS_LIST_UNAVAILABLE',
    })
    assert.doesNotMatch(JSON.stringify(body), /workspace|secret-token/i)
  } finally {
    await listFailureServer.close()
  }

  const previousToken = config.workerToken
  config.workerToken = token
  taskRegistry.set('task-process-kill-sanitized', {
    taskId: 'task-process-kill-sanitized',
    status: 'running',
    pid: 656,
    startedAt: Date.now(),
  })
  const process = {
    pid: 656,
    command: 'codex --experimental-json --token secret-token',
    memory_mb: 1,
    started_at: '2026-07-16T08:00:00+08:00',
  }
  const killFailureRouter = createProcessesRouter({
    listProcesses: async () => [process],
    killProcess: async () => {
      throw new CodexProcessKillError(656, [{
        command: 'taskkill /PID 656 /F',
        args: ['/PID', '656', '/F'],
        exitCode: 5,
        stdout: 'private stdout secret-token',
        stderr: 'private stderr /workspace/project',
      }])
    },
  })
  const killFailureServer = await startProcessServer(killFailureRouter)

  try {
    const listResponse = await fetch(`${killFailureServer.baseUrl}/api/v1/processes`)
    const listBody = await listResponse.json()
    assert.equal(listResponse.status, 200)
    assert.equal(listBody.processes[0].command, 'codex')
    assert.equal(listBody.processes[0].started_at, processStartedAt)
    assert.equal(listBody.processes[0].foggy_task_id, 'task-process-kill-sanitized')
    assert.equal(
      listBody.processes[0].process_identity,
      'codex-cli:656:2026-07-16T00:00:00.000Z',
    )
    assert.doesNotMatch(JSON.stringify(listBody), /secret-token/i)

    const response = await fetch(`${killFailureServer.baseUrl}/api/v1/processes/656/kill`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...signedManualKill('task-process-kill-sanitized', 656, 'process-operation-kill-sanitized'),
      },
      body: '{}',
    })
    const body = await response.json()
    assert.equal(response.status, 502)
    assert.equal(body.error, 'CODEX_PROCESS_KILL_UNCONFIRMED')
    assert.equal(body.code, 'CODEX_PROCESS_KILL_UNCONFIRMED')
    assert.equal(body.attempt_count, 1)
    assert.deepEqual(body.exit_codes, [5])
    assert.doesNotMatch(JSON.stringify(body), /stdout|stderr|workspace|secret-token|taskkill/i)
  } finally {
    config.workerToken = previousToken
    await killFailureServer.close()
  }
})

test('process list issues an identity only for a uniquely bound active task with a canonical start time', async () => {
  taskRegistry.set('task-process-active', {
    taskId: 'task-process-active',
    status: 'running',
    pid: 801,
    startedAt: Date.now(),
  })
  taskRegistry.set('task-process-completed', {
    taskId: 'task-process-completed',
    status: 'completed',
    pid: 802,
    startedAt: Date.now(),
    completedAt: Date.now(),
  })
  taskRegistry.set('task-process-no-start-time', {
    taskId: 'task-process-no-start-time',
    status: 'cancel_requested',
    pid: 803,
    startedAt: Date.now(),
  })
  const router = createProcessesRouter({
    listProcesses: async () => [
      { pid: 801, command: 'codex --experimental-json', memory_mb: 1, started_at: '2026-07-16T08:00:00+08:00' },
      { pid: 802, command: 'codex --experimental-json', memory_mb: 1, started_at: processStartedAt },
      { pid: 803, command: 'codex --experimental-json', memory_mb: 1, started_at: '' },
      { pid: 804, command: 'codex --experimental-json', memory_mb: 1, started_at: processStartedAt },
    ],
  })
  const server = await startProcessServer(router)

  try {
    const response = await fetch(`${server.baseUrl}/api/v1/processes`)
    const body = await response.json()
    assert.equal(response.status, 200)

    const [active, completed, noStartTime, orphan] = body.processes
    assert.equal(active.foggy_task_id, 'task-process-active')
    assert.equal(active.started_at, processStartedAt)
    assert.equal(active.process_identity, 'codex-cli:801:2026-07-16T00:00:00.000Z')

    assert.equal(completed.foggy_task_id, 'task-process-completed')
    assert.equal('process_identity' in completed, false)
    assert.equal('process_identity' in noStartTime, false)
    assert.equal('process_identity' in orphan, false)
  } finally {
    await server.close()
  }
})

test('manual PID kill rejects missing or mismatched signed task and PID bindings before signalling', async () => {
  const previousToken = config.workerToken
  config.workerToken = token
  taskRegistry.set('task-process-3', {
    taskId: 'task-process-3',
    status: 'running',
    pid: 777,
    startedAt: Date.now(),
  })
  let killCalls = 0
  const router = createProcessesRouter({
    listProcesses: async () => [{
      pid: 777,
      command: 'codex --experimental-json',
      memory_mb: 1,
      started_at: processStartedAt,
    }],
    killProcess: async () => { killCalls += 1 },
  })
  const server = await startProcessServer(router)

  try {
    const mismatchedWorker = await fetch(`${server.baseUrl}/api/v1/processes/777/kill`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...signedManualKill(
          'task-process-3',
          777,
          'process-operation-worker-mismatch',
          777,
          'another-navigator-worker',
        ),
      },
      body: '{}',
    })
    assert.equal(mismatchedWorker.status, 409)
    assert.equal((await mismatchedWorker.json()).code, 'TERMINATION_OPERATION_WORKER_MISMATCH')

    const missingPid = await fetch(`${server.baseUrl}/api/v1/processes/777/kill`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...signedManualKill('task-process-3', 777, 'process-operation-missing-pid', null),
      },
      body: '{}',
    })
    assert.equal(missingPid.status, 400)
    assert.equal((await missingPid.json()).code, 'TERMINATION_OPERATION_PID_REQUIRED')

    const missingProcessIdentity = await fetch(`${server.baseUrl}/api/v1/processes/777/kill`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...signedManualKill('task-process-3', 777, 'process-operation-missing-identity', 777, navigatorWorkerId, null),
      },
      body: '{}',
    })
    assert.equal(missingProcessIdentity.status, 400)
    assert.equal(
      (await missingProcessIdentity.json()).code,
      'TERMINATION_OPERATION_PROCESS_IDENTITY_REQUIRED',
    )

    const mismatchedTask = await fetch(`${server.baseUrl}/api/v1/processes/777/kill`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...signedManualKill('another-task', 777, 'process-operation-task-mismatch'),
      },
      body: '{}',
    })
    assert.equal(mismatchedTask.status, 409)
    assert.equal((await mismatchedTask.json()).code, 'TERMINATION_OPERATION_TASK_MISMATCH')

    const mismatchedPid = await fetch(`${server.baseUrl}/api/v1/processes/777/kill`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...signedManualKill('task-process-3', 777, 'process-operation-pid-mismatch', 778),
      },
      body: '{}',
    })
    assert.equal(mismatchedPid.status, 409)
    assert.equal((await mismatchedPid.json()).code, 'TERMINATION_OPERATION_PID_MISMATCH')
    assert.equal(killCalls, 0)
    assert.equal(taskRegistry.get('task-process-3')?.status, 'running')
  } finally {
    config.workerToken = previousToken
    await server.close()
  }
})
