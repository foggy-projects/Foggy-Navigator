import test from 'node:test'
import assert from 'node:assert/strict'
import { once } from 'node:events'
import type { AddressInfo } from 'node:net'
import express from 'express'
import { taskBroadcasts } from '../src/codex/sdk-wrapper.ts'
import {
  CODEX_ULTRA_APP_SERVER_REQUIRED,
  default as queryRouter,
  isPathWithinAllowedCwd,
  requiresAppServerForUltra,
} from '../src/routes/query.ts'

const TEST_ALIASES = {
  'codex-latest': 'gpt-5.6-sol',
  'codex-max': 'gpt-5.6-sol:max',
  'codex-ultra': 'gpt-5.6-sol:ultra',
}

test('all Ultra queries fail closed for the independent app-server runtime', () => {
  assert.equal(CODEX_ULTRA_APP_SERVER_REQUIRED, 'CODEX_ULTRA_APP_SERVER_REQUIRED')
  assert.equal(requiresAppServerForUltra('codex-ultra', 'codex-latest', TEST_ALIASES), true)
  assert.equal(requiresAppServerForUltra('gpt-5.6-sol:ultra', 'codex-latest', TEST_ALIASES), true)
  assert.equal(requiresAppServerForUltra(undefined, 'codex-ultra', TEST_ALIASES), true)
  assert.equal(requiresAppServerForUltra('codex-max', 'codex-latest', TEST_ALIASES), false)
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
