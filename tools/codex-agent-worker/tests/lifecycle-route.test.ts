import assert from 'node:assert/strict'
import { once } from 'node:events'
import fs from 'node:fs'
import type { AddressInfo } from 'node:net'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import express from 'express'
import { createLifecycleRouter } from '../src/routes/lifecycle.js'
import {
  computeSafeBindingDigest,
  LifecycleStore,
  type LifecycleContext,
} from '../src/lifecycle/store.js'

const token = 'fixture-route-token'
const workerId = 'fixture-route-worker'

async function fixture() {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'lifecycle-route-'))
  const store = LifecycleStore.open({
    directory,
    physicalWorkerId: workerId,
    workerToken: token,
    instanceEpoch: 'fixture-epoch',
  })
  const app = express()
  app.use(express.json())
  app.use(createLifecycleRouter({ store, workerToken: token }))
  const server = app.listen(0, '127.0.0.1')
  await once(server, 'listening')
  const address = server.address() as AddressInfo
  return {
    store,
    directory,
    baseUrl: `http://127.0.0.1:${address.port}`,
    close: async () => {
      await new Promise<void>((resolve, reject) => {
        server.close(error => error ? reject(error) : resolve())
      })
      fs.rmSync(directory, { recursive: true, force: true })
    },
  }
}

function identityHeaders(store: LifecycleStore) {
  return {
    Authorization: `Bearer ${token}`,
    'X-Navigator-Expected-Physical-Worker-Id': workerId,
    'X-Navigator-Expected-State-Generation': store.identity.state_generation,
  }
}

test('lifecycle auth and identity fail before facts are returned', async () => {
  const server = await fixture()
  try {
    const missingAuth = await fetch(
      `${server.baseUrl}/api/v1/lifecycle/inventory?after_sequence=0`,
    )
    assert.equal(missingAuth.status, 401)
    assert.deepEqual(await missingAuth.json(), {
      schema: 'NAVIGATOR_WORKER_LIFECYCLE_V1',
      code: 'WORKER_LIFECYCLE_AUTH_REQUIRED',
    })

    const missingFence = await fetch(
      `${server.baseUrl}/api/v1/lifecycle/inventory?after_sequence=0`,
      { headers: { Authorization: `Bearer ${token}` } },
    )
    assert.equal(missingFence.status, 400)
    const body = await missingFence.text()
    assert.match(body, /LIFECYCLE_EXPECTED_IDENTITY_REQUIRED/)
    assert.doesNotMatch(body, /facts|provider_task_id/)
  } finally {
    await server.close()
  }
})

test('dispatch status is mode-first and never discloses durable binding on mismatch', async () => {
  const server = await fixture()
  try {
    const context: LifecycleContext = {
      schema: 'NAVIGATOR_WORKER_LIFECYCLE_V1',
      ownership_mode: 'SHADOW',
      command_kind: 'TASK_CREATE',
      navigator_task_id: 'fixture-task',
      dispatch_id: 'fixture-dispatch',
      delivery_attempt: 1,
      expected_physical_worker_id: workerId,
      expected_state_generation: server.store.identity.state_generation,
      termination_operation_id: null,
    }
    const binding = computeSafeBindingDigest({
      context,
      httpMethod: 'POST',
      routeTemplate: '/api/v1/query',
      bodyWithoutLifecycleContext: { prompt: 'fixture' },
      providerTaskId: null,
      capabilityPayload: null,
    })
    server.store.prepareAcceptedDispatch(context, binding, () => 'provider-task')

    const crossMode = await fetch(
      `${server.baseUrl}/api/v1/lifecycle/dispatches/fixture-dispatch`,
      {
        headers: {
          ...identityHeaders(server.store),
          'X-Navigator-Expected-Ownership-Mode': 'ENFORCED',
          'X-Navigator-Expected-Safe-Binding-Digest-Version': binding.version,
          'X-Navigator-Expected-Safe-Binding-Digest': binding.digest,
        },
      },
    )
    assert.equal(crossMode.status, 409)
    const mismatchBody = await crossMode.text()
    assert.match(mismatchBody, /LIFECYCLE_OWNERSHIP_MODE_MISMATCH/)
    assert.doesNotMatch(mismatchBody, /SHADOW|provider-task|safe_binding_digest/)

    const exact = await fetch(
      `${server.baseUrl}/api/v1/lifecycle/dispatches/fixture-dispatch`,
      {
        headers: {
          ...identityHeaders(server.store),
          'X-Navigator-Expected-Ownership-Mode': 'SHADOW',
          'X-Navigator-Expected-Safe-Binding-Digest-Version': binding.version,
          'X-Navigator-Expected-Safe-Binding-Digest': binding.digest,
        },
      },
    )
    assert.equal(exact.status, 200)
    const exactBody = await exact.json() as Record<string, unknown>
    assert.equal(exactBody.provider_task_id, 'provider-task')
    assert.equal(exactBody.ownership_mode, 'SHADOW')
  } finally {
    await server.close()
  }
})

test('ack is monotonic and identity fenced', async () => {
  const server = await fixture()
  try {
    server.store.appendFact({
      fact_type: 'WORKER_HEARTBEAT_OBSERVED',
      aggregate_type: 'WORKER',
      aggregate_id: workerId,
      safe_reason_code: 'FIXTURE',
    })
    const response = await fetch(`${server.baseUrl}/api/v1/lifecycle/ack`, {
      method: 'PUT',
      headers: {
        ...identityHeaders(server.store),
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        schema: 'NAVIGATOR_WORKER_LIFECYCLE_V1',
        physical_worker_id: workerId,
        state_generation: server.store.identity.state_generation,
        through_sequence: 1,
      }),
    })
    assert.equal(response.status, 200)
    assert.equal((await response.json()).acked_through_sequence, 1)

    const replay = await fetch(`${server.baseUrl}/api/v1/lifecycle/ack`, {
      method: 'PUT',
      headers: {
        ...identityHeaders(server.store),
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        schema: 'NAVIGATOR_WORKER_LIFECYCLE_V1',
        physical_worker_id: workerId,
        state_generation: server.store.identity.state_generation,
        through_sequence: 0,
      }),
    })
    assert.equal((await replay.json()).acked_through_sequence, 1)
  } finally {
    await server.close()
  }
})
