import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import type { AddressInfo } from 'node:net'
import test from 'node:test'
import { createApp } from '../src/app.js'
import type { PoolRateLimitsView } from '../src/app-server/rate-limits.js'
import { EXPECTED_INSTANCE_HEADER } from '../src/instance-affinity.js'
import { TaskStore } from '../src/persistence/task-store.js'
import { TaskManager } from '../src/task-manager.js'
import { FakeExecutor, tempDirectory, testConfig } from './helpers.js'

class RateLimitExecutor extends FakeExecutor {
  refreshes: boolean[] = []

  async readDefaultRateLimits(refresh = false): Promise<PoolRateLimitsView> {
    this.refreshes.push(refresh)
    return {
      state: 'AVAILABLE',
      observed_at_epoch_ms: 1_800_000_000_000,
      stale: false,
      limits: [{
        limit_id: 'codex',
        limit_name: null,
        primary: { used_percent: 25, window_duration_mins: 300, resets_at: 1_800_000_000 },
        secondary: null,
        rate_limit_reached_type: null,
      }],
      error_code: null,
    }
  }
}

test('runtime rate-limit endpoint is bearer and instance guarded with a no-store safe contract', async t => {
  const stateDir = await tempDirectory('codex-rate-limit-http-')
  const config = testConfig(stateDir)
  const executor = new RateLimitExecutor()
  const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const manager = new TaskManager(config, store, executor)
  await manager.initialize()
  const server = createApp(config, manager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()))
    await fs.rm(stateDir, { recursive: true, force: true })
  })

  assert.equal((await fetch(`${baseUrl}/api/v1/runtime/rate-limits`)).status, 401)
  const missingInstance = await fetch(`${baseUrl}/api/v1/runtime/rate-limits`, {
    headers: authHeaders(),
  })
  assert.equal(missingInstance.status, 400)
  assert.deepEqual(await missingInstance.json(), { error: 'RUNTIME_INSTANCE_REQUIRED' })
  const mismatched = await fetch(`${baseUrl}/api/v1/runtime/rate-limits`, {
    headers: { ...authHeaders(), [EXPECTED_INSTANCE_HEADER]: 'wrong-instance' },
  })
  assert.equal(mismatched.status, 409)
  assert.deepEqual(await mismatched.json(), { error: 'RUNTIME_INSTANCE_MISMATCH' })

  const invalid = await fetch(`${baseUrl}/api/v1/runtime/rate-limits?refresh=1`, {
    headers: { ...authHeaders(), [EXPECTED_INSTANCE_HEADER]: config.instanceId },
  })
  assert.equal(invalid.status, 400)
  assert.equal(invalid.headers.get('cache-control'), 'no-store')

  const response = await fetch(`${baseUrl}/api/v1/runtime/rate-limits?refresh=true`, {
    headers: { ...authHeaders(), [EXPECTED_INSTANCE_HEADER]: config.instanceId },
  })
  assert.equal(response.status, 200)
  assert.equal(response.headers.get('cache-control'), 'no-store')
  const body = await response.json() as Record<string, any>
  assert.deepEqual(body, {
    contract_version: 1,
    runtime_id: config.runtimeId,
    runtime_revision: config.runtimeRevision,
    instance_id: config.instanceId,
    scope: 'DEFAULT_CODEX_HOME',
    state: 'AVAILABLE',
    observed_at_epoch_ms: 1_800_000_000_000,
    stale: false,
    limits: [{
      limit_id: 'codex',
      limit_name: null,
      primary: { used_percent: 25, window_duration_mins: 300, resets_at: 1_800_000_000 },
      secondary: null,
      rate_limit_reached_type: null,
    }],
    error_code: null,
  })
  assert.deepEqual(executor.refreshes, [true])
  for (const forbidden of ['plan_type', 'credits', 'individual_limit', 'lane_key', 'email']) {
    assert.equal(JSON.stringify(body).toLowerCase().includes(forbidden), false)
  }
  assert.equal(JSON.stringify(body).includes(config.codexHome), false)

  const health = await (await fetch(`${baseUrl}/health`)).text()
  for (const forbidden of ['used_percent', 'plan_type', 'individual_limit', 'rate_limit_reached_type']) {
    assert.equal(health.includes(forbidden), false)
  }

  const capabilities = await (await fetch(`${baseUrl}/api/v1/capabilities`, {
    headers: authHeaders(),
  })).json() as Record<string, any>
  assert.equal(capabilities.features.account_rate_limits_read, true)
  assert.equal(capabilities.features.account_rate_limits_advisory_only, true)
  assert.equal(capabilities.features.account_rate_limits_model_routing, false)
})

function authHeaders(): Record<string, string> {
  return { Authorization: 'Bearer test-worker-token' }
}
