import assert from 'node:assert/strict'
import test from 'node:test'
import type { SafeAccountRateLimits } from '../src/app-server/rate-limits.js'
import {
  AppServerPool,
  type AppServerLane,
  type PoolRuntimeInstance,
} from '../src/app-server/pool.js'
import { tempDirectory, testConfig, waitFor } from './helpers.js'

class RateLimitRuntime implements PoolRuntimeInstance {
  readonly pid = 200
  healthy = true
  active = false
  reads = 0
  usedPercent = 25
  nextReadGate?: Promise<void>
  readError?: Error
  private readonly updatedHandlers = new Set<() => void>()

  isHealthy(): boolean { return this.healthy }
  isActive(): boolean { return this.active }
  runTurn(): never { throw new Error('not used') }
  async readThread(): Promise<Record<string, unknown>> { return {} }
  close(): void { this.healthy = false }
  onRateLimitsUpdated(handler: () => void): () => void {
    this.updatedHandlers.add(handler)
    return () => this.updatedHandlers.delete(handler)
  }
  async readAccountRateLimits(): Promise<SafeAccountRateLimits> {
    this.reads++
    const usedPercent = this.usedPercent
    const gate = this.nextReadGate
    this.nextReadGate = undefined
    if (gate) await gate
    if (this.readError) throw this.readError
    return safeLimits(usedPercent)
  }
  emitUpdated(): void {
    for (const handler of this.updatedHandlers) handler()
  }
}

test('pool keeps one TTL and singleflight cache per lane', async () => {
  const stateDir = await tempDirectory('codex-rate-limit-pool-')
  let now = 1_000
  const runtimes: RateLimitRuntime[] = []
  const pool = new AppServerPool(
    testConfig(stateDir),
    async () => {
      const runtime = new RateLimitRuntime()
      runtimes.push(runtime)
      return runtime
    },
    () => now,
    60_000,
  )
  const lane = testLane('lane-a')

  const [first, concurrent] = await Promise.all([
    pool.readRateLimits(lane, true),
    pool.readRateLimits(lane, true),
  ])
  assert.deepEqual(first, concurrent)
  assert.equal(runtimes.length, 1)
  assert.equal(runtimes[0]?.reads, 1)
  assert.equal((await pool.readRateLimits(lane)).limits[0]?.primary?.used_percent, 25)
  assert.equal(runtimes[0]?.reads, 1)

  now += 60_001
  runtimes[0]!.usedPercent = 40
  assert.equal((await pool.readRateLimits(lane)).limits[0]?.primary?.used_percent, 40)
  assert.equal(runtimes[0]?.reads, 2)

  runtimes[0]!.usedPercent = 50
  runtimes[0]!.emitUpdated()
  await waitFor(() => runtimes[0]!.reads === 3)
  assert.equal((await pool.readRateLimits(lane)).limits[0]?.primary?.used_percent, 50)
  await pool.drain(1_000)
})

test('quota reads reuse the resident lane and cannot replace it with an incompatible lane', async () => {
  const stateDir = await tempDirectory('codex-rate-limit-lanes-')
  const runtimes = new Map<string, RateLimitRuntime>()
  const pool = new AppServerPool(testConfig(stateDir), async lane => {
    const runtime = new RateLimitRuntime()
    runtime.usedPercent = lane.key === 'lane-limited' ? 100 : 10
    runtimes.set(lane.key, runtime)
    return runtime
  })
  const limitedLane = testLane('lane-limited')
  const availableLane = testLane('lane-available')

  assert.equal((await pool.readRateLimits(limitedLane, true)).state, 'LIMIT_REACHED')
  const incompatible = await pool.readRateLimits(availableLane, true)
  assert.equal(incompatible.state, 'UNKNOWN')
  assert.equal(incompatible.error_code, 'RATE_LIMITS_SOURCE_UNAVAILABLE')
  assert.equal(runtimes.size, 1)

  const lease = await pool.acquire(limitedLane)
  assert.equal(lease.runtime, runtimes.get('lane-limited'))
  lease.release()
  assert.equal(pool.metrics().rejected_total, 0)
  assert.equal(pool.metrics().acquire_timeouts_total, 0)
  await pool.drain(1_000)
})

test('pool retries when an update invalidates an in-flight quota read', async () => {
  const stateDir = await tempDirectory('codex-rate-limit-inflight-update-')
  let runtime: RateLimitRuntime | undefined
  const pool = new AppServerPool(testConfig(stateDir), async () => {
    runtime = new RateLimitRuntime()
    return runtime
  })
  const lane = testLane('lane-inflight-update')

  await pool.readRateLimits(lane, true)
  let releaseRead!: () => void
  runtime!.nextReadGate = new Promise<void>(resolve => { releaseRead = resolve })
  const refreshing = pool.readRateLimits(lane, true)
  await waitFor(() => runtime!.reads === 2)
  runtime!.usedPercent = 75
  runtime!.emitUpdated()
  releaseRead()

  const refreshed = await refreshing
  assert.equal(runtime!.reads, 3)
  assert.equal(refreshed.stale, false)
  assert.equal(refreshed.limits[0]?.primary?.used_percent, 75)
  assert.equal((await pool.readRateLimits(lane)).limits[0]?.primary?.used_percent, 75)
  await pool.drain(1_000)
})

test('pool releases invalidation versions after a first quota read fails without a cache', async () => {
  const stateDir = await tempDirectory('codex-rate-limit-failed-version-')
  let runtime: RateLimitRuntime | undefined
  const pool = new AppServerPool(testConfig(stateDir), async () => {
    runtime = new RateLimitRuntime()
    runtime.readError = new Error('quota source failed')
    return runtime
  })
  const lane = testLane('lane-failed-version')
  const lease = await pool.acquire(lane)
  lease.release()

  runtime!.emitUpdated()
  await waitFor(() => runtime!.reads === 1)
  const internal = pool as unknown as {
    rateLimitsInvalidationVersions: Map<string, number>
    rateLimitsRefreshes: Map<string, Promise<unknown>>
  }
  await waitFor(() => !internal.rateLimitsRefreshes.has(lane.key))
  assert.equal(internal.rateLimitsInvalidationVersions.has(lane.key), false)
  await pool.drain(1_000)
})

function testLane(key: string): AppServerLane {
  return {
    key,
    cliVersion: '0.144.3',
    authFingerprint: `${key}-auth`,
    codexHomeFingerprint: `${key}-home`,
    baseUrlFingerprint: `${key}-base`,
    processEnvFingerprint: `${key}-env`,
    env: {},
  }
}

function safeLimits(usedPercent: number): SafeAccountRateLimits {
  return {
    limits: [{
      limit_id: 'codex',
      limit_name: null,
      primary: { used_percent: usedPercent, window_duration_mins: 300, resets_at: 1_800_000_000 },
      secondary: null,
      rate_limit_reached_type: usedPercent >= 100 ? 'rate_limit_reached' : null,
    }],
  }
}
