import test from 'node:test'
import assert from 'node:assert/strict'
import { once } from 'node:events'
import type { AddressInfo } from 'node:net'
import path from 'node:path'
import os from 'node:os'
import fs from 'node:fs'
import { fileURLToPath } from 'node:url'
import express from 'express'
import { config } from '../src/config.ts'
import {
  CODEX_NAVIGATOR_WORKER_CREDENTIAL_FORWARDING_UNREADY,
  taskBroadcasts,
  taskRegistry,
} from '../src/codex/sdk-wrapper.ts'
import {
  CODEX_ULTRA_APP_SERVER_REQUIRED,
  CODEX_WORKING_DIRECTORY_UNAVAILABLE,
  default as queryRouter,
  isPathWithinAllowedCwd,
  isUnsupportedCodexModelRequest,
  requiresAppServerForUltra,
  resolveNavigatorBusinessMcpPreflightError,
} from '../src/routes/query.ts'
import { UNSUPPORTED_CODEX_MODEL } from '../src/codex/sdk-wrapper.ts'
import {
  acquireCodexThreadReservation,
  clearCodexThreadReservationsForTests,
  getCodexThreadReservations,
} from '../src/codex/thread-reservations.ts'
import {
  lifecycleStore,
  resetLifecycleRuntimeForTest,
} from '../src/lifecycle/runtime.ts'

const TEST_ALIASES = {
  'codex-latest': 'gpt-5.6-sol',
  'codex-max': 'gpt-5.6-sol:max',
  'codex-ultra': 'gpt-5.6-sol:ultra',
  'retired-mini': 'gpt-5.4-mini',
}

test('Mini requests are rejected after direct or alias resolution', () => {
  assert.equal(UNSUPPORTED_CODEX_MODEL, 'UNSUPPORTED_CODEX_MODEL')
  assert.equal(isUnsupportedCodexModelRequest('gpt-5.4-mini', 'codex-latest', TEST_ALIASES), true)
  assert.equal(isUnsupportedCodexModelRequest('gpt-5.4-mini:high', 'codex-latest', TEST_ALIASES), true)
  assert.equal(isUnsupportedCodexModelRequest('retired-mini', 'codex-latest', TEST_ALIASES), true)
  assert.equal(isUnsupportedCodexModelRequest('retired-mini:xhigh', 'codex-latest', TEST_ALIASES), true)
  assert.equal(isUnsupportedCodexModelRequest('codex-latest', 'codex-latest', TEST_ALIASES), false)
})

test('all Ultra queries fail closed for the independent app-server runtime', () => {
  assert.equal(CODEX_ULTRA_APP_SERVER_REQUIRED, 'CODEX_ULTRA_APP_SERVER_REQUIRED')
  assert.equal(requiresAppServerForUltra('codex-ultra', 'codex-latest', TEST_ALIASES), true)
  assert.equal(requiresAppServerForUltra('CODEX-ULTRA:high', 'codex-latest', TEST_ALIASES), true)
  assert.equal(requiresAppServerForUltra('gpt-5.6-sol:ultra', 'codex-latest', TEST_ALIASES), true)
  assert.equal(requiresAppServerForUltra('codex-latest:ultra', 'codex-latest', TEST_ALIASES), true)
  assert.equal(requiresAppServerForUltra(undefined, 'codex-ultra', TEST_ALIASES), true)
  assert.equal(requiresAppServerForUltra(
    'codex-latest',
    'codex-latest',
    TEST_ALIASES,
    { model_reasoning_effort: ' ULTRA ' },
  ), true)
  assert.equal(requiresAppServerForUltra('codex-max', 'codex-latest', TEST_ALIASES), false)
})

test('business MCP credential preflight leaves internal token-only and non-business requests unchanged', () => {
  assert.equal(resolveNavigatorBusinessMcpPreflightError(undefined, 'worker-a', true), undefined)
  assert.equal(resolveNavigatorBusinessMcpPreflightError({
    task_scoped_token: 'btt_task',
    allowed_tools: ['filesystem.read'],
  }, 'worker-a', true), undefined)
  assert.equal(resolveNavigatorBusinessMcpPreflightError({
    task_scoped_token: 'btt_task',
    allowed_tools: ['business.functions.invoke'],
  }, '', false), undefined)
})

test('query route rejects configured credential business MCP before SSE, reservation, or task state', async () => {
  const previousWorkerId = config.navigatorWorkerId
  const previousWorkerCredential = config.navigatorWorkerCredential
  config.navigatorWorkerId = 'worker-a'
  config.navigatorWorkerCredential = 'bwc_route_secret'
  clearCodexThreadReservationsForTests()

  const app = express()
  app.use(express.json())
  app.use(queryRouter)
  const server = app.listen(0, '127.0.0.1')
  await once(server, 'listening')
  const address = server.address() as AddressInfo
  const broadcastCountBefore = taskBroadcasts.size
  const taskCountBefore = taskRegistry.size

  try {
    const response = await fetch(`http://127.0.0.1:${address.port}/api/v1/query`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        prompt: 'must fail before task allocation',
        session_id: 'thread-business-preflight',
        business_runtime_context: {
          task_scoped_token: 'btt_route_secret',
          allowed_tools: ['business.functions.invoke'],
          worker_id: 'worker-a',
          worker_lease_id: 'lease-a',
        },
      }),
    })
    const responseText = await response.text()

    assert.equal(response.status, 503)
    assert.match(response.headers.get('content-type') || '', /^application\/json/)
    assert.deepEqual(JSON.parse(responseText), {
      code: CODEX_NAVIGATOR_WORKER_CREDENTIAL_FORWARDING_UNREADY,
      error: CODEX_NAVIGATOR_WORKER_CREDENTIAL_FORWARDING_UNREADY,
    })
    assert.doesNotMatch(responseText, /bwc_route_secret|btt_route_secret|lease-a/)
    assert.equal(taskBroadcasts.size, broadcastCountBefore)
    assert.equal(taskRegistry.size, taskCountBefore)
    assert.equal(getCodexThreadReservations().has('thread-business-preflight'), false)
  } finally {
    config.navigatorWorkerId = previousWorkerId
    config.navigatorWorkerCredential = previousWorkerCredential
    clearCodexThreadReservationsForTests()
    await new Promise<void>((resolve, reject) => {
      server.close(error => error ? reject(error) : resolve())
    })
  }
})

test('query route rejects new and resumed Ultra sessions before creating Worker task state', async () => {
  const app = express()
  app.use(express.json())
  app.use(queryRouter)
  const server = app.listen(0, '127.0.0.1')
  await once(server, 'listening')
  const address = server.address() as AddressInfo
  const taskCountBefore = taskBroadcasts.size

  try {
    for (const body of [
      { prompt: 'new Ultra task', model: 'codex-ultra' },
      { prompt: 'resume Ultra task', model: 'codex-ultra', session_id: 'sdk-thread-1' },
      { prompt: 'alias override Ultra task', model: 'codex-latest:ultra' },
      { prompt: 'stable Ultra name cannot be weakened', model: 'CODEX-ULTRA:high' },
      {
        prompt: 'generic config cannot bypass the SDK boundary',
        model: 'codex-latest',
        codex_config: { model_reasoning_effort: 'ultra' },
      },
    ]) {
      const response = await fetch(`http://127.0.0.1:${address.port}/api/v1/query`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
      assert.equal(response.status, 409)
      assert.deepEqual(await response.json(), {
        code: CODEX_ULTRA_APP_SERVER_REQUIRED,
        error: CODEX_ULTRA_APP_SERVER_REQUIRED,
      })
    }
    assert.equal(taskBroadcasts.size, taskCountBefore)
  } finally {
    await new Promise<void>((resolve, reject) => {
      server.close(error => error ? reject(error) : resolve())
    })
  }
})

test('query route rejects direct Mini requests before creating Worker task state', async () => {
  const app = express()
  app.use(express.json())
  app.use(queryRouter)
  const server = app.listen(0, '127.0.0.1')
  await once(server, 'listening')
  const address = server.address() as AddressInfo
  const taskCountBefore = taskBroadcasts.size

  try {
    for (const model of ['gpt-5.4-mini', 'gpt-5.4-mini:high']) {
      const response = await fetch(`http://127.0.0.1:${address.port}/api/v1/query`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ prompt: 'retired Mini task', model }),
      })
      assert.equal(response.status, 400)
      assert.deepEqual(await response.json(), { error: UNSUPPORTED_CODEX_MODEL })
    }
    assert.equal(taskBroadcasts.size, taskCountBefore)
  } finally {
    await new Promise<void>((resolve, reject) => {
      server.close(error => error ? reject(error) : resolve())
    })
  }
})

test('query route rejects a missing working directory before SSE, reservation, or task state', async () => {
  clearCodexThreadReservationsForTests()
  const missingDirectory = path.join(
    os.tmpdir(),
    `codex-worker-missing-cwd-${Date.now()}-${Math.random().toString(16).slice(2)}`,
  )
  const app = express()
  app.use(express.json())
  app.use(queryRouter)
  const server = app.listen(0, '127.0.0.1')
  await once(server, 'listening')
  const address = server.address() as AddressInfo
  const broadcastCountBefore = taskBroadcasts.size
  const taskCountBefore = taskRegistry.size

  try {
    const response = await fetch(`http://127.0.0.1:${address.port}/api/v1/query`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        prompt: 'must fail before task allocation',
        cwd: missingDirectory,
        session_id: 'thread-missing-directory',
      }),
    })
    const responseText = await response.text()

    assert.equal(response.status, 409)
    assert.match(response.headers.get('content-type') || '', /^application\/json/)
    assert.deepEqual(JSON.parse(responseText), {
      code: CODEX_WORKING_DIRECTORY_UNAVAILABLE,
      error: CODEX_WORKING_DIRECTORY_UNAVAILABLE,
    })
    assert.doesNotMatch(responseText, new RegExp(missingDirectory.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))
    assert.equal(taskBroadcasts.size, broadcastCountBefore)
    assert.equal(taskRegistry.size, taskCountBefore)
    assert.equal(getCodexThreadReservations().has('thread-missing-directory'), false)
  } finally {
    clearCodexThreadReservationsForTests()
    await new Promise<void>((resolve, reject) => {
      server.close(error => error ? reject(error) : resolve())
    })
  }
})

test('query route returns stable conflict details for an already reserved thread', async () => {
  clearCodexThreadReservationsForTests()
  const reservation = await acquireCodexThreadReservation('thread-active', 'task-active', {
    listProcesses: async () => [],
  })
  const app = express()
  app.use(express.json())
  app.use(queryRouter)
  const server = app.listen(0, '127.0.0.1')
  await once(server, 'listening')
  const address = server.address() as AddressInfo

  try {
    const response = await fetch(`http://127.0.0.1:${address.port}/api/v1/query`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        prompt: 'must be rejected',
        session_id: 'thread-active',
      }),
    })
    assert.equal(response.status, 409)
    assert.deepEqual(await response.json(), {
      code: 'CODEX_THREAD_ACTIVE',
      error: 'CODEX_THREAD_ACTIVE',
      session_id: 'thread-active',
      active_task_id: 'task-active',
      conflict_source: 'reservation',
    })
  } finally {
    reservation.release()
    clearCodexThreadReservationsForTests()
    await new Promise<void>((resolve, reject) => {
      server.close(error => error ? reject(error) : resolve())
    })
  }
})

test('production query route durably rejects every frozen never-accepted reason before provider effect', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'arch001-r4-query-route-'))
  const previous = {
    workerToken: config.workerToken,
    navigatorWorkerId: config.navigatorWorkerId,
    lifecycleStoreDir: config.lifecycleStoreDir,
    codexHome: config.codexHome,
    maxConcurrentTasks: config.maxConcurrentTasks,
    nodeEnv: process.env.NODE_ENV,
    testCodexPath: process.env.CODEX_WORKER_TEST_CODEX_PATH_OVERRIDE,
  }
  config.workerToken = 'arch001-r4-route-token'
  config.navigatorWorkerId = 'arch001-r4-route-worker'
  config.lifecycleStoreDir = path.join(root, 'lifecycle')
  config.codexHome = path.join(root, 'codex-home')
  process.env.NODE_ENV = 'test'
  process.env.CODEX_WORKER_TEST_CODEX_PATH_OVERRIDE = fileURLToPath(
    new URL('./fixtures/codex', import.meta.url),
  )
  fs.mkdirSync(path.join(config.codexHome, 'sessions'), { recursive: true })
  resetLifecycleRuntimeForTest()
  clearCodexThreadReservationsForTests()

  const app = express()
  app.use(express.json())
  app.use(queryRouter)
  const server = app.listen(0, '127.0.0.1')
  await once(server, 'listening')
  const address = server.address() as AddressInfo
  const baseUrl = `http://127.0.0.1:${address.port}`
  const providerTaskBaseline = new Set(taskRegistry.keys())

  const request = async (
    reason: string,
    commandKind: 'TASK_CREATE' | 'TASK_RESUME',
    dispatchId: string,
    deliveryAttempt: number,
    sessionId?: string,
  ) => {
    const store = lifecycleStore()
    assert.ok(store)
    const response = await fetch(`${baseUrl}/api/v1/query`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${config.workerToken}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        prompt: `fixture-${reason}`,
        cwd: process.cwd(),
        model: 'gpt-5.6-sol',
        ...(sessionId ? { session_id: sessionId } : {}),
        lifecycle_context: {
          schema: 'NAVIGATOR_WORKER_LIFECYCLE_V1',
          ownership_mode: 'ENFORCED',
          command_kind: commandKind,
          navigator_task_id: `navigator-${reason}`,
          dispatch_id: dispatchId,
          delivery_attempt: deliveryAttempt,
          expected_physical_worker_id: config.navigatorWorkerId,
          expected_state_generation: store.identity.state_generation,
          termination_operation_id: null,
        },
      }),
    })
    if (response.status !== 409) await response.body?.cancel()
    assert.equal(response.status, 409)
    const disposition = await response.json() as Record<string, unknown>
    assert.equal(disposition.code, reason)
    assert.equal(disposition.acceptance_disposition, 'REJECTED')
    assert.equal(disposition.effect_phase, 'PRE_EFFECT')
    assert.equal(disposition.never_accepted_proof, true)
    assert.equal(disposition.provider_effect_started, false)
    assert.equal(disposition.provider_task_id, null)
    return disposition
  }

  try {
    const missingThread = '019f7f67-9829-7e11-b25b-000000000404'
    const activeThread = '019f7f67-9829-7e11-b25b-000000000409'
    const activeDirectory = path.join(config.codexHome, 'sessions', '2026', '07', '31')
    fs.mkdirSync(activeDirectory, { recursive: true })
    fs.writeFileSync(
      path.join(activeDirectory, `rollout-2026-07-31T00-00-00-${activeThread}.jsonl`),
      `${JSON.stringify({ type: 'session_meta', payload: { id: activeThread } })}\n`,
    )
    const reservation = await acquireCodexThreadReservation(
      activeThread,
      'already-running-provider-task',
      { listProcesses: async () => [] },
    )
    try {
      const cases = [
        {
          reason: 'WORKER_TASK_RESUME_TARGET_NOT_FOUND',
          kind: 'TASK_RESUME' as const,
          dispatch: 'dispatch-resume-not-found',
          sessionId: missingThread,
          maxConcurrentTasks: 4,
        },
        {
          reason: 'WORKER_TASK_ADMISSION_THREAD_CONFLICT',
          kind: 'TASK_RESUME' as const,
          dispatch: 'dispatch-thread-conflict',
          sessionId: activeThread,
          maxConcurrentTasks: 4,
        },
        {
          reason: 'WORKER_TASK_ADMISSION_CAPACITY_REJECTED',
          kind: 'TASK_CREATE' as const,
          dispatch: 'dispatch-capacity',
          maxConcurrentTasks: 0,
        },
      ]
      for (const candidate of cases) {
        config.maxConcurrentTasks = candidate.maxConcurrentTasks
        const first = await request(
          candidate.reason,
          candidate.kind,
          candidate.dispatch,
          1,
          candidate.sessionId,
        )
        resetLifecycleRuntimeForTest()
        const replay = await request(
          candidate.reason,
          candidate.kind,
          candidate.dispatch,
          2,
          candidate.sessionId,
        )
        assert.equal(replay.duplicate, true)
        assert.equal(replay.disposition_version, first.disposition_version)
      }
    } finally {
      reservation.release()
    }

    const inventory = lifecycleStore()?.inventory(0)
    assert.ok(inventory)
    assert.equal(inventory.dispatches.length, 3)
    assert.equal(inventory.facts.length, 3)
    assert.deepEqual(
      new Set(inventory.facts.map(fact => fact.safe_reason_code)),
      new Set([
        'WORKER_TASK_RESUME_TARGET_NOT_FOUND',
        'WORKER_TASK_ADMISSION_THREAD_CONFLICT',
        'WORKER_TASK_ADMISSION_CAPACITY_REJECTED',
      ]),
    )
    assert.deepEqual(new Set(taskRegistry.keys()), providerTaskBaseline)
  } finally {
    config.workerToken = previous.workerToken
    config.navigatorWorkerId = previous.navigatorWorkerId
    config.lifecycleStoreDir = previous.lifecycleStoreDir
    config.codexHome = previous.codexHome
    config.maxConcurrentTasks = previous.maxConcurrentTasks
    if (previous.nodeEnv === undefined) delete process.env.NODE_ENV
    else process.env.NODE_ENV = previous.nodeEnv
    if (previous.testCodexPath === undefined) {
      delete process.env.CODEX_WORKER_TEST_CODEX_PATH_OVERRIDE
    } else {
      process.env.CODEX_WORKER_TEST_CODEX_PATH_OVERRIDE = previous.testCodexPath
    }
    resetLifecycleRuntimeForTest()
    clearCodexThreadReservationsForTests()
    await new Promise<void>((resolve, reject) => {
      server.close(error => error ? reject(error) : resolve())
    })
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('malformed model values stay on the normal request-validation path', () => {
  assert.equal(requiresAppServerForUltra({ value: 'codex-ultra' }, 'codex-latest', TEST_ALIASES), false)
})

test('isPathWithinAllowedCwd accepts exact and nested Windows paths', () => {
  assert.equal(isPathWithinAllowedCwd('D:\\repo', 'D:\\repo'), true)
  assert.equal(isPathWithinAllowedCwd('D:\\repo\\scenario-1', 'D:\\repo'), true)
  assert.equal(isPathWithinAllowedCwd('d:\\repo\\scenario-1', 'D:\\repo'), true)
})

test('isPathWithinAllowedCwd rejects Windows sibling prefix paths', () => {
  assert.equal(isPathWithinAllowedCwd('D:\\repo2', 'D:\\repo'), false)
  assert.equal(isPathWithinAllowedCwd('D:/repo-other/scenario-1', 'D:/repo'), false)
})

test('isPathWithinAllowedCwd accepts nested POSIX paths and rejects sibling prefixes', () => {
  assert.equal(isPathWithinAllowedCwd('/srv/repo/scenario-1', '/srv/repo'), true)
  assert.equal(isPathWithinAllowedCwd('/srv/repo2', '/srv/repo'), false)
})
