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
  requiresAppServerForNewUltra,
} from '../src/routes/query.ts'

const TEST_ALIASES = {
  'codex-latest': 'gpt-5.6-sol',
  'codex-max': 'gpt-5.6-sol:max',
  'codex-ultra': 'gpt-5.6-sol:ultra',
}

test('new Ultra queries fail closed for the independent app-server runtime', () => {
  assert.equal(CODEX_ULTRA_APP_SERVER_REQUIRED, 'CODEX_ULTRA_APP_SERVER_REQUIRED')
  assert.equal(requiresAppServerForNewUltra('codex-ultra', undefined, 'codex-latest', TEST_ALIASES), true)
  assert.equal(requiresAppServerForNewUltra('gpt-5.6-sol:ultra', undefined, 'codex-latest', TEST_ALIASES), true)
  assert.equal(requiresAppServerForNewUltra(undefined, undefined, 'codex-ultra', TEST_ALIASES), true)
  assert.equal(requiresAppServerForNewUltra('codex-max', undefined, 'codex-latest', TEST_ALIASES), false)
})

test('query route rejects a new Ultra session before creating Worker task state', async () => {
  const app = express()
  app.use(express.json())
  app.use(queryRouter)
  const server = app.listen(0, '127.0.0.1')
  await once(server, 'listening')
  const address = server.address() as AddressInfo
  const taskCountBefore = taskBroadcasts.size

  try {
    const response = await fetch(`http://127.0.0.1:${address.port}/api/v1/query`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ prompt: 'new Ultra task', model: 'codex-ultra' }),
    })
    assert.equal(response.status, 409)
    assert.deepEqual(await response.json(), {
      code: CODEX_ULTRA_APP_SERVER_REQUIRED,
      error: CODEX_ULTRA_APP_SERVER_REQUIRED,
    })
    assert.equal(taskBroadcasts.size, taskCountBefore)
  } finally {
    await new Promise<void>((resolve, reject) => {
      server.close(error => error ? reject(error) : resolve())
    })
  }
})

test('existing SDK Ultra threads remain resumable for affinity drain', () => {
  assert.equal(
    requiresAppServerForNewUltra('codex-ultra', 'sdk-thread-1', 'codex-latest', TEST_ALIASES),
    false,
  )
  assert.equal(
    requiresAppServerForNewUltra('gpt-5.6-sol:ultra', ' sdk-thread-2 ', 'codex-latest', TEST_ALIASES),
    false,
  )
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
