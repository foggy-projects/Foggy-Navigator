import assert from 'node:assert/strict'
import test from 'node:test'
import {
  AppServerProcessTreeSafetyError,
  AppServerRuntimeInstance,
  type AppServerTurnResult,
  type PersistentTurnOptions,
} from '../src/app-server/runtime.js'
import {
  AppServerPool,
  AppServerPoolDrainTimeoutError,
  AppServerPoolDrainingError,
  AppServerPoolOverloadedError,
  type AppServerLane,
  type PoolRuntimeInstance,
} from '../src/app-server/pool.js'
import { buildAppServerLane, readAppServerLaneApiKey } from '../src/app-server/lane.js'
import { testConfig, waitFor } from './helpers.js'
import { createStubbornProcessTreeFixture, isProcessAlive } from './stubborn-app-server-fixture.js'

const lane = (key: string): AppServerLane => ({
  key,
  cliVersion: '0.144.1',
  authFingerprint: `auth-${key}`,
  codexHomeFingerprint: `home-${key}`,
  baseUrlFingerprint: `url-${key}`,
  processEnvFingerprint: `env-${key}`,
  env: {},
})

test('exclusive leases reuse idle instances and scale parallel turns to separate instances', async t => {
  const runtimes: FakeRuntime[] = []
  const pool = new AppServerPool(testConfig('C:\\state'), async () => {
    const runtime = new FakeRuntime()
    runtimes.push(runtime)
    return runtime
  })
  t.after(() => pool.drain(100))

  const first = await pool.acquire(lane('a'))
  const second = await pool.acquire(lane('a'))
  assert.notEqual(first.instanceId, second.instanceId)
  assert.equal(pool.metrics().busy, 2)
  const thirdPromise = pool.acquire(lane('a'))
  await new Promise(resolve => setTimeout(resolve, 5))
  assert.equal(pool.metrics().queued, 1)
  const firstId = first.instanceId
  first.release()
  const third = await thirdPromise
  assert.equal(third.instanceId, firstId)
  assert.equal(pool.metrics().reused_total, 1)
  second.release()
  third.release()
  assert.equal(runtimes.length, 2)
})

test('full pool replaces the least-recently-used idle instance for a new lane', async t => {
  let now = 100
  const runtimes: Array<{ laneKey: string, runtime: FakeRuntime }> = []
  const config = testConfig('C:\\state', {
    poolMaxInstances: 2,
    poolMaxInstancesPerLane: 2,
    poolAcquireTimeoutMs: 500,
    poolIdleTtlMs: 10_000,
  })
  const pool = new AppServerPool(config, async requestedLane => {
    const runtime = new FakeRuntime()
    runtimes.push({ laneKey: requestedLane.key, runtime })
    return runtime
  }, () => now)
  t.after(() => pool.drain(100))

  const firstA = await pool.acquire(lane('a'))
  firstA.release()
  now = 200
  const firstB = await pool.acquire(lane('b'))
  firstB.release()
  now = 300
  const recentA = await pool.acquire(lane('a'))
  recentA.release()

  const leaseC = await pool.acquire(lane('c'))
  assert.equal(runtimes.find(item => item.laneKey === 'b')?.runtime.closed, true)
  assert.equal(runtimes.find(item => item.laneKey === 'a')?.runtime.closed, false)
  assert.equal(runtimes.find(item => item.laneKey === 'c')?.runtime, leaseC.runtime)
  assert.equal(pool.metrics().instances, 2)
  assert.equal(pool.metrics().created_total, 3)
  assert.equal(pool.metrics().reused_total, 1)
  assert.equal(pool.metrics().retired_total, 1)
  leaseC.release()
})

test('cross-lane replacement never retires a busy instance', async t => {
  const runtimes: Array<{ laneKey: string, runtime: FakeRuntime }> = []
  const config = testConfig('C:\\state', {
    poolMaxInstances: 2,
    poolMaxInstancesPerLane: 2,
    poolAcquireTimeoutMs: 500,
    poolIdleTtlMs: 10_000,
  })
  const pool = new AppServerPool(config, async requestedLane => {
    const runtime = new FakeRuntime()
    runtimes.push({ laneKey: requestedLane.key, runtime })
    return runtime
  })
  t.after(() => pool.drain(100))

  const busyA = await pool.acquire(lane('a'))
  const idleB = await pool.acquire(lane('b'))
  idleB.release()
  const leaseC = await pool.acquire(lane('c'))

  assert.equal(runtimes.find(item => item.laneKey === 'a')?.runtime.closed, false)
  assert.equal(runtimes.find(item => item.laneKey === 'b')?.runtime.closed, true)
  assert.equal(pool.metrics().busy, 2)
  busyA.release()
  leaseC.release()
})

test('concurrent cross-lane replacements wait for slow closes and never exceed global capacity', async t => {
  let openChildren = 0
  let maxOpenChildren = 0
  let maxReservedCapacity = 0
  let pool!: AppServerPool
  const config = testConfig('C:\\state', {
    poolMaxInstances: 2,
    poolMaxInstancesPerLane: 2,
    poolAcquireTimeoutMs: 1_000,
    poolIdleTtlMs: 10_000,
  })
  pool = new AppServerPool(config, async () => {
    openChildren++
    maxOpenChildren = Math.max(maxOpenChildren, openChildren)
    const metrics = pool.metrics()
    maxReservedCapacity = Math.max(maxReservedCapacity, metrics.instances + metrics.creating)
    return new SlowCloseRuntime(25, () => { openChildren-- })
  })
  t.after(() => pool.drain(500))

  const idleA = await pool.acquire(lane('a'))
  idleA.release()
  const idleB = await pool.acquire(lane('b'))
  idleB.release()

  const [leaseC, leaseD] = await Promise.all([
    pool.acquire(lane('c')),
    pool.acquire(lane('d')),
  ])
  assert.equal(maxOpenChildren, 2)
  assert.ok(maxReservedCapacity <= config.poolMaxInstances)
  assert.equal(pool.metrics().instances + pool.metrics().creating, 2)
  assert.equal(pool.metrics().created_total, 4)
  assert.equal(pool.metrics().retired_total, 2)
  leaseC.release()
  leaseD.release()
})

test('a rejected idle close fails closed and makes drain report retirement failure', async () => {
  let now = 100
  const runtimes: PoolRuntimeInstance[] = []
  const config = testConfig('C:\\state', {
    poolMaxInstances: 2,
    poolMaxInstancesPerLane: 2,
    poolAcquireTimeoutMs: 500,
    poolIdleTtlMs: 10_000,
  })
  const pool = new AppServerPool(config, async () => {
    const runtime = runtimes.length === 0 ? new RejectingCloseRuntime() : new FakeRuntime()
    runtimes.push(runtime)
    return runtime
  }, () => now)

  const idleA = await pool.acquire(lane('a'))
  idleA.release()
  now = 200
  const idleB = await pool.acquire(lane('b'))
  idleB.release()
  await assert.rejects(pool.acquire(lane('c')), AppServerPoolDrainingError)

  assert.equal(pool.isDraining(), true)
  assert.equal(pool.metrics().instances, 1)
  assert.equal(pool.metrics().created_total, 2)
  assert.equal(pool.metrics().retired_total, 1)
  await assert.rejects(pool.drain(500), AggregateError)
  assert.equal(pool.metrics().instances, 0)
})

test('process-tree safety failure rejects the current acquire and permanently fails the pool closed', async () => {
  let factoryCalls = 0
  const safetyFailure = new AppServerProcessTreeSafetyError()
  const pool = new AppServerPool(testConfig('C:\\state'), async () => {
    factoryCalls++
    throw safetyFailure
  })

  await assert.rejects(pool.acquire(lane('unsafe-tree')), error => error === safetyFailure)
  assert.equal(pool.isDraining(), true)
  await assert.rejects(pool.acquire(lane('no-retry')), AppServerPoolDrainingError)
  assert.equal(factoryCalls, 1)
  await assert.rejects(pool.drain(100), error => (
    error instanceof AggregateError && error.errors.includes(safetyFailure)
  ))
})

test('pool enforces global bounded queue and rejects new leases while draining', async t => {
  const config = testConfig('C:\\state', {
    poolMaxInstances: 1,
    poolMaxInstancesPerLane: 1,
    poolMaxQueue: 1,
  })
  const pool = new AppServerPool(config, async () => new FakeRuntime())
  t.after(() => pool.drain(100))
  const first = await pool.acquire(lane('a'))
  const waiting = pool.acquire(lane('a'))
  await assert.rejects(pool.acquire(lane('a')), AppServerPoolOverloadedError)
  assert.equal(pool.metrics().rejected_total, 1)
  first.release()
  const second = await waiting
  const drain = pool.drain(1_000)
  await waitFor(() => pool.isDraining())
  await assert.rejects(pool.acquire(lane('a')), AppServerPoolDrainingError)
  second.release()
  await drain
  assert.equal(pool.metrics().instances, 0)
})

test('pool drain waits for in-flight instance creation and retires the created child within its deadline', async () => {
  let finishCreation!: (runtime: FakeRuntime) => void
  const factoryGate = new Promise<FakeRuntime>(resolve => { finishCreation = resolve })
  const runtime = new FakeRuntime()
  const pool = new AppServerPool(testConfig('C:\\state'), async () => factoryGate)
  const acquiring = pool.acquire(lane('creating'))
  await waitFor(() => pool.metrics().creating === 1)
  const draining = pool.drain(500)
  finishCreation(runtime)
  await assert.rejects(acquiring, AppServerPoolDrainingError)
  await draining
  assert.equal(runtime.closed, true)
  assert.equal(pool.metrics().creating, 0)
  assert.equal(pool.metrics().instances, 0)
})

test('pool drain rejects instead of releasing shutdown ownership while close is still pending', async () => {
  const config = testConfig('C:\state', { poolMaxTasksPerInstance: 1 })
  const pool = new AppServerPool(config, async () => new SlowCloseRuntime(75, () => undefined))
  const lease = await pool.acquire(lane('slow-drain'))
  lease.release()

  await assert.rejects(pool.drain(5), AppServerPoolDrainTimeoutError)
  await new Promise(resolve => setTimeout(resolve, 100))
  await pool.drain(100)

  assert.equal(pool.metrics().instances, 0)
})

test('idle TTL retirement cleans the tracked app-server descendant before drain settles', async t => {
  let now = 1_000
  const fixture = await createStubbornProcessTreeFixture(t)
  const config = testConfig(fixture.stateDir, {
    poolIdleTtlMs: 100,
    poolMaxLifetimeMs: 10_000,
  })
  const pool = new AppServerPool(config, (requestedLane, signal) => AppServerRuntimeInstance.start({
    env: requestedLane.env,
    signal,
    spawnProcess: fixture.spawnProcess,
    processTreeStateDir: fixture.stateDir,
    processTreeEntry: fixture.entry,
    requestTimeoutMs: 1_000,
  }), () => now)
  const lease = await pool.acquire(lane('ttl-tree'))
  const descendantPid = await fixture.readDescendantPid()
  lease.release()

  now += 101
  pool.sweep()
  await pool.drain(10_000)

  assert.equal(isProcessAlive(descendantPid), false)
  assert.equal(pool.metrics().instances, 0)
})

test('pool drain does not resolve until a tracked app-server descendant is gone', async t => {
  const fixture = await createStubbornProcessTreeFixture(t)
  const config = testConfig(fixture.stateDir)
  const pool = new AppServerPool(config, (requestedLane, signal) => AppServerRuntimeInstance.start({
    env: requestedLane.env,
    signal,
    spawnProcess: fixture.spawnProcess,
    processTreeStateDir: fixture.stateDir,
    processTreeEntry: fixture.entry,
    requestTimeoutMs: 1_000,
  }))
  const lease = await pool.acquire(lane('drain-tree'))
  const descendantPid = await fixture.readDescendantPid()
  lease.release()

  await pool.drain(10_000)

  assert.equal(isProcessAlive(descendantPid), false, 'drain is the Worker state/cwd lock release boundary')
  assert.equal(pool.metrics().instances, 0)
})

test('idle TTL, task-count retirement, and child crashes evict only the affected instance', async t => {
  let now = 1_000
  const runtimes: FakeRuntime[] = []
  const config = testConfig('C:\\state', {
    poolIdleTtlMs: 100,
    poolMaxLifetimeMs: 10_000,
    poolMaxTasksPerInstance: 2,
  })
  const pool = new AppServerPool(config, async () => {
    const runtime = new FakeRuntime()
    runtimes.push(runtime)
    return runtime
  }, () => now)
  t.after(() => pool.drain(100))

  const first = await pool.acquire(lane('a'))
  first.release()
  now += 101
  pool.sweep()
  assert.equal(runtimes[0]?.closed, true)
  assert.equal(pool.metrics().instances, 0)

  const second = await pool.acquire(lane('a'))
  const secondRuntime = second.runtime as FakeRuntime
  secondRuntime.crash()
  second.release()
  assert.equal(secondRuntime.closed, true)
  assert.equal(pool.metrics().crashes_total, 1)

  const third = await pool.acquire(lane('a'))
  const thirdId = third.instanceId
  third.release()
  const fourth = await pool.acquire(lane('a'))
  assert.equal(fourth.instanceId, thirdId)
  fourth.release()
  assert.equal((fourth.runtime as FakeRuntime).closed, true, 'max task count retires the instance')
})

test('lane key changes for auth/home/base URL/environment and excludes Worker secrets', async () => {
  const baseEnv = {
    PATH: 'C:\\bin',
    CODEX_APP_SERVER_STATE_KEY: 'state-secret',
    CODEX_APP_SERVER_WORKER_TOKEN: 'worker-secret',
    FOGGY_CODEX_TASK_ID: 'task-transient',
    NAVIGATOR_WORKER_GATEWAY_TOKEN: 'navigator-secret',
    AWS_SECRET_ACCESS_KEY: 'aws-secret',
    DATABASE_PASSWORD: 'db-secret',
    SystemRoot: 'C:\\WINDOWS',
    TEMP: 'C:\\Temp',
  }
  const first = await buildAppServerLane({
    cliVersion: '0.144.1',
    baseEnv,
    apiKey: 'sk-one',
    baseUrl: 'https://api.one.test',
    codexHome: 'C:\\codex\\one',
  })
  const same = await buildAppServerLane({
    cliVersion: '0.144.1',
    baseEnv: { ...baseEnv },
    apiKey: 'sk-one',
    baseUrl: 'https://api.one.test',
    codexHome: 'C:\\codex\\one',
  })
  const differentAuth = await buildAppServerLane({
    cliVersion: '0.144.1', baseEnv, apiKey: 'sk-two', baseUrl: 'https://api.one.test', codexHome: 'C:\\codex\\one',
  })
  const differentHome = await buildAppServerLane({
    cliVersion: '0.144.1', baseEnv, apiKey: 'sk-one', baseUrl: 'https://api.one.test', codexHome: 'C:\\codex\\two',
  })
  const differentUrl = await buildAppServerLane({
    cliVersion: '0.144.1', baseEnv, apiKey: 'sk-one', baseUrl: 'https://api.two.test', codexHome: 'C:\\codex\\one',
  })
  assert.equal(first.key, same.key)
  assert.notEqual(first.key, differentAuth.key)
  assert.notEqual(first.key, differentHome.key)
  assert.notEqual(first.key, differentUrl.key)
  assert.equal(readAppServerLaneApiKey(first), 'sk-one')
  assert.equal(readAppServerLaneApiKey(differentAuth), 'sk-two')
  assert.equal(Object.prototype.hasOwnProperty.call(first, 'apiKey'), false)
  assert.doesNotMatch(JSON.stringify(first), /sk-one/)
  assert.equal(first.env.OPENAI_API_KEY, undefined)
  assert.equal(first.env.CODEX_API_KEY, undefined)
  assert.equal(first.env.OPENAI_BASE_URL, undefined)
  assert.equal(first.env.CODEX_APP_SERVER_STATE_KEY, undefined)
  assert.equal(first.env.CODEX_APP_SERVER_WORKER_TOKEN, undefined)
  assert.equal(first.env.FOGGY_CODEX_TASK_ID, undefined)
  assert.equal(first.env.NAVIGATOR_WORKER_GATEWAY_TOKEN, undefined)
  assert.equal(first.env.AWS_SECRET_ACCESS_KEY, undefined)
  assert.equal(first.env.DATABASE_PASSWORD, undefined)
  assert.equal(first.env.SystemRoot, 'C:\\WINDOWS')
  assert.equal(first.env.TEMP, 'C:\\Temp')
  assert.doesNotMatch(first.key, /sk-one|codex|api\.one/)
})

test('default pool factory forwards the opaque lane API key only to runtime startup', async t => {
  const requestedLane = await buildAppServerLane({
    cliVersion: '0.144.1',
    baseEnv: { PATH: 'C:\\bin' },
    apiKey: 'dummy-pool-key',
    baseUrl: 'https://api.example.test/v1',
    codexHome: 'C:\\codex\\pool',
  })
  const runtime = new FakeRuntime()
  let captured: Parameters<typeof AppServerRuntimeInstance.start>[0] | undefined
  t.mock.method(AppServerRuntimeInstance, 'start', async options => {
    captured = options
    return runtime as unknown as AppServerRuntimeInstance
  })
  const pool = new AppServerPool(testConfig('C:\\state'))
  t.after(() => pool.drain(100))

  const lease = await pool.acquire(requestedLane)
  assert.equal(captured?.apiKey, 'dummy-pool-key')
  assert.equal(captured?.env.OPENAI_API_KEY, undefined)
  assert.equal(captured?.env.CODEX_API_KEY, undefined)
  assert.equal(captured?.env.OPENAI_BASE_URL, undefined)
  lease.release()
})

class FakeRuntime implements PoolRuntimeInstance {
  readonly pid = 1
  closed = false
  private healthy = true
  private active = false
  private readonly fatalHandlers = new Set<(error: Error) => void>()

  isHealthy(): boolean { return this.healthy && !this.closed }
  isActive(): boolean { return this.active }
  async runTurn(_options: PersistentTurnOptions): Promise<AppServerTurnResult> {
    this.active = true
    this.active = false
    return { threadId: 'thread', turn: { id: 'turn', status: 'completed' } }
  }
  async readThread(): Promise<Record<string, unknown>> { return { id: 'thread', turns: [] } }
  close(): void { this.closed = true; this.healthy = false }
  onFatal(handler: (error: Error) => void): () => void {
    this.fatalHandlers.add(handler)
    return () => this.fatalHandlers.delete(handler)
  }
  crash(): void {
    this.healthy = false
    for (const handler of this.fatalHandlers) handler(new Error('child exited'))
  }
}

class SlowCloseRuntime extends FakeRuntime {
  constructor(
    private readonly closeDelayMs: number,
    private readonly onClosed: () => void,
  ) {
    super()
  }

  override async close(): Promise<void> {
    await new Promise(resolve => setTimeout(resolve, this.closeDelayMs))
    super.close()
    this.onClosed()
  }
}

class RejectingCloseRuntime extends FakeRuntime {
  override close(): Promise<void> {
    super.close()
    return Promise.reject(new Error('expected close failure'))
  }
}
