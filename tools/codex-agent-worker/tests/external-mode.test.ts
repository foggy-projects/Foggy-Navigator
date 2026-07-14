import assert from 'node:assert/strict'
import { once } from 'node:events'
import test from 'node:test'
import express from 'express'
import { createExternalModeMiddleware } from '../src/external-mode.ts'

test('external mode keeps health public and fails closed for business ingress', async t => {
  const app = express()
  const runtimeConfig = { externalEnabled: true, workerToken: '' }
  app.use(createExternalModeMiddleware(runtimeConfig))
  app.get('/health', (_req, res) => res.json({ status: 'degraded' }))
  app.post('/api/v1/query', (_req, res) => res.status(204).end())
  const server = app.listen(0, '127.0.0.1')
  await once(server, 'listening')
  t.after(() => server.close())
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  const baseUrl = `http://127.0.0.1:${address.port}`

  assert.equal((await fetch(`${baseUrl}/health`)).status, 200)
  const response = await fetch(`${baseUrl}/api/v1/query`, { method: 'POST' })
  assert.equal(response.status, 503)
  assert.deepEqual(await response.json(), {
    error: 'EXTERNAL_WORKER_UNREADY',
    reasons: ['EXTERNAL_AUTH_TOKEN_REQUIRED', 'EXTERNAL_EXECUTION_POLICY_PENDING'],
  })

  runtimeConfig.workerToken = 'configured-secret'
  const stillClosed = await fetch(`${baseUrl}/api/v1/query`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${runtimeConfig.workerToken}` },
  })
  assert.equal(stillClosed.status, 503)
  assert.deepEqual(await stillClosed.json(), {
    error: 'EXTERNAL_WORKER_UNREADY',
    reasons: ['EXTERNAL_EXECUTION_POLICY_PENDING'],
  })
})

test('internal dev mode preserves the existing empty-token behavior', async t => {
  const app = express()
  app.use(createExternalModeMiddleware({ externalEnabled: false, workerToken: '' }))
  app.post('/api/v1/query', (_req, res) => res.status(204).end())
  const server = app.listen(0, '127.0.0.1')
  await once(server, 'listening')
  t.after(() => server.close())
  const address = server.address()
  assert.ok(address && typeof address !== 'string')

  const response = await fetch(`http://127.0.0.1:${address.port}/api/v1/query`, { method: 'POST' })
  assert.equal(response.status, 204)
})
