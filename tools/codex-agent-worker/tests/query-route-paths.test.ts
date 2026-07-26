import test from 'node:test'
import assert from 'node:assert/strict'
import { once } from 'node:events'
import type { AddressInfo } from 'node:net'
import path from 'node:path'
import os from 'node:os'
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
