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
  AppServerPoolSingleInstanceLaneMismatchError,
  type AppServerLane,
  type PoolRuntimeInstance,
} from '../src/app-server/pool.js'
import { buildAppServerLane, buildProcessEnv, readAppServerLaneApiKey } from '../src/app-server/lane.js'
import { testConfig, waitFor } from './helpers.js'
import { createStubbornProcessTreeFixture, isProcessAlive } from './stubborn-app-server-fixture.js'

const lane = (key: string): AppServerLane => ({
  key,
  cliVersion: '0.144.3',
  authFingerprint: `auth-${key}`,
  codexHomeFingerprint: `home-${key}`,
  baseUrlFingerprint: `url-${key}`,
  processEnvFingerprint: `env-${key}`,
  env: {},
})

test('app-server lane preserves HOME and resolves a missing POSIX HOME from the effective user', () => {
  const preserved = buildProcessEnv(
    { HOME: '/custom/home' },
    { codexHome: '/codex/home', platform: 'linux', resolveUserHome: () => '/home/effective-user' },
  )
  const resolved = buildProcessEnv(
    { HOME: '' },
    { codexHome: '/codex/home', platform: 'linux', resolveUserHome: () => '/home/effective-user' },
  )

  assert.equal(preserved.HOME, '/custom/home')
  assert.equal(resolved.HOME, '/home/effective-user')
})

test('app-server lane leaves HOME unset when effective user lookup fails', () => {
  const env = buildProcessEnv(
    {},
    {
      codexHome: '/codex/home',
      platform: 'linux',
      resolveUserHome: () => {
        throw new Error('uid is not present in the user database')
      },
    },
  )

  assert.equal(env.HOME, undefined)
})

test('compatible leases share one resident child without queueing', async t => {
  const runtimes: FakeRuntime[] = []
  const pool = new AppServerPool(testConfig('C:\\state'), async () => {
    const runtime = new FakeRuntime()
    runtimes.push(runtime)
    return runtime
  })
  t.after(() => pool.drain(100))

  const first = await pool.acquire(lane('a'))
  const second = await pool.acquire(lane('a'))
  const third = await pool.acquire(lane('a'))
  assert.equal(second.instanceId, first.instanceId)
  assert.equal(third.instanceId, first.instanceId)
  assert.equal(pool.metrics().busy, 1)
  assert.equal(pool.metrics().queued, 0)
  assert.equal(pool.metrics().reused_total, 2)
  first.release()
  second.release()
  third.release()
  assert.equal(runtimes.length, 1)
})

test('an incompatible lane is rejected without replacing a healthy idle child', async t => {
  const runtimes: Array<{ laneKey: string, runtime: FakeRuntime }> = []
  const pool = new AppServerPool(testConfig('C:\\state'), async requestedLane => {
    const runtime = new FakeRuntime()
    runtimes.push({ laneKey: requestedLane.key, runtime })
    return runtime
  })
  t.after(() => pool.drain(100))

  const firstA = await pool.acquire(lane('a'))
  firstA.release()
  await assert.rejects(pool.acquire(lane('b')), AppServerPoolSingleInstanceLaneMismatchError)

  assert.equal(runtimes.length, 1)
  assert.equal(runtimes[0]?.runtime.closed, false)
  assert.equal(pool.metrics().instances, 1)
  assert.equal(pool.metrics().created_total, 1)
  assert.equal(pool.metrics().retired_total, 0)
})

test('an incompatible lane is rejected without disturbing active compatible leases', async t => {
  const runtimes: Array<{ laneKey: string, runtime: FakeRuntime }> = []
  const pool = new AppServerPool(testConfig('C:\\state'), async requestedLane => {
    const runtime = new FakeRuntime()
    runtimes.push({ laneKey: requestedLane.key, runtime })
    return runtime
  })
  t.after(() => pool.drain(100))

  const first = await pool.acquire(lane('a'))
  const second = await pool.acquire(lane('a'))
  await assert.rejects(pool.acquire(lane('b')), AppServerPoolSingleInstanceLaneMismatchError)

  assert.equal(first.instanceId, second.instanceId)
  assert.equal(runtimes.length, 1)
  assert.equal(runtimes[0]?.runtime.closed, false)
  assert.equal(pool.metrics().busy, 1)
  first.release()
  second.release()
})

test('concurrent compatible acquisition never creates more than one child', async t => {
  let openChildren = 0
  let maxOpenChildren = 0
  const pool = new AppServerPool(testConfig('C:\\state'), async () => {
    openChildren++
    maxOpenChildren = Math.max(maxOpenChildren, openChildren)
    return new SlowCloseRuntime(25, () => { openChildren-- })
  })
  t.after(() => pool.drain(500))

  const leases = await Promise.all([
    pool.acquire(lane('a')),
    pool.acquire(lane('a')),
    pool.acquire(lane('a')),
  ])
  assert.equal(maxOpenChildren, 1)
  assert.equal(new Set(leases.map(lease => lease.instanceId)).size, 1)
  assert.equal(pool.metrics().instances + pool.metrics().creating, 1)
  assert.equal(pool.metrics().created_total, 1)
  for (const lease of leases) lease.release()
})

test('a rejected idle close fails closed and makes drain report retirement failure', async () => {
  const pool = new AppServerPool(testConfig('C:\\state'), async () => new RejectingCloseRuntime())
  const lease = await pool.acquire(lane('a'))
  lease.release()

  await assert.rejects(pool.drain(500), AggregateError)
  assert.equal(pool.isDraining(), true)
  assert.equal(pool.metrics().instances, 0)
  assert.equal(pool.metrics().created_total, 1)
  assert.equal(pool.metrics().retired_total, 1)
  await assert.rejects(pool.acquire(lane('a')), AppServerPoolDrainingError)
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

test('single app-server child shares compatible leases concurrently without normal pool rotation', async t => {
  let now = 1_000
  const runtimes: FakeRuntime[] = []
  const pool = new AppServerPool(testConfig('C:\\state', {
    poolMaxInstances: 6,
    poolMaxInstancesPerLane: 4,
    poolIdleTtlMs: 100,
    poolMaxLifetimeMs: 200,
    poolMaxTasksPerInstance: 1,
  }), async () => {
    const runtime = new FakeRuntime()
    runtimes.push(runtime)
    return runtime
  }, () => now)
  t.after(() => pool.drain(100))

  const first = await pool.acquire(lane('shared'))
  const secondPromise = pool.acquire(lane('shared'))
  let resolvedBeforeRelease = false
  void secondPromise.then(() => { resolvedBeforeRelease = true })
  await new Promise(resolve => setTimeout(resolve, 20))
  if (!resolvedBeforeRelease) first.release()
  const second = await secondPromise
  assert.equal(second.instanceId, first.instanceId)
  assert.equal(resolvedBeforeRelease, true, 'compatible leases must share the resident child without queueing')
  assert.equal(pool.metrics().queued, 0)
  assert.equal(pool.metrics().busy, 1)
  first.release()
  second.release()

  now += 1_000
  pool.sweep()
  const third = await pool.acquire(lane('shared'))
  assert.equal(third.instanceId, first.instanceId)
  third.release()
  assert.equal(runtimes.length, 1)
  assert.equal(pool.metrics().retired_total, 0)
})

test('single-child pool rejects an incompatible startup lane instead of replacing its resident child', async t => {
  const runtimes: FakeRuntime[] = []
  const pool = new AppServerPool(testConfig('C:\\state', {
    poolAcquireTimeoutMs: 100,
  }), async () => {
    const runtime = new FakeRuntime()
    runtimes.push(runtime)
    return runtime
  })
  t.after(() => pool.drain(100))

  const resident = await pool.acquire(lane('first'))
  resident.release()
  await assert.rejects(pool.acquire(lane('different')), AppServerPoolSingleInstanceLaneMismatchError)
  assert.equal(runtimes.length, 1)
  assert.equal(runtimes[0]?.closed, false)
  assert.equal(pool.metrics().retired_total, 0)
})

test('a shared child crash waits for every lease before retirement and replacement', async t => {
  const runtimes: FakeRuntime[] = []
  const pool = new AppServerPool(testConfig('C:\\state'), async () => {
    const runtime = new FakeRuntime()
    runtimes.push(runtime)
    return runtime
  })
  t.after(() => pool.drain(100))

  const first = await pool.acquire(lane('shared-crash'))
  const second = await pool.acquire(lane('shared-crash'))
  const crashed = first.runtime as FakeRuntime
  crashed.crash()
  first.release(false)
  assert.equal(crashed.closed, false, 'another task still owns the crashed child lease')
  assert.equal(pool.metrics().instances, 1)

  second.release(false)
  assert.equal(crashed.closed, true)
  assert.equal(pool.metrics().crashes_total, 1)
  const replacement = await pool.acquire(lane('shared-crash'))
  assert.notEqual(replacement.instanceId, first.instanceId)
  assert.equal(runtimes.length, 2)
  replacement.release()
})

test('drain waits for all shared leases and closes the child only after the last release', async () => {
  const runtime = new FakeRuntime()
  const pool = new AppServerPool(testConfig('C:\\state'), async () => runtime)
  const first = await pool.acquire(lane('shared-drain'))
  const second = await pool.acquire(lane('shared-drain'))
  let drained = false
  const draining = pool.drain(1_000).then(() => { drained = true })

  await waitFor(() => pool.isDraining())
  first.release()
  await new Promise(resolve => setTimeout(resolve, 20))
  assert.equal(drained, false)
  assert.equal(runtime.closed, false)
  second.release()
  await draining
  assert.equal(runtime.closed, true)
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

test('drain cleans a tracked app-server descendant even after the legacy idle TTL elapses', async t => {
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

test('healthy TTL and task-count thresholds do not rotate the resident child, but a crash does', async t => {
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
  assert.equal(runtimes[0]?.closed, false)
  assert.equal(pool.metrics().instances, 1)

  const second = await pool.acquire(lane('a'))
  const secondRuntime = second.runtime as FakeRuntime
  assert.equal(second.instanceId, first.instanceId)
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
  assert.equal((fourth.runtime as FakeRuntime).closed, false, 'normal task count does not rotate a healthy child')
})

test('healthy idle processes remain resident across TTL sweeps', async t => {
  let now = 1_000
  const config = testConfig('C:\\state', {
    poolIdleTtlMs: 100,
    poolMaxLifetimeMs: 10_000,
    poolMaxTasksPerInstance: 10,
  })
  const pool = new AppServerPool(config, async () => new FakeRuntime(), () => now)
  t.after(() => pool.drain(100))

  const first = await pool.acquire(lane('resume-capable'))
  const firstId = first.instanceId
  first.release()
  now += 101
  pool.sweep()

  assert.equal(pool.metrics().instances, 1)
  const reused = await pool.acquire(lane('resume-capable'))
  assert.equal(reused.instanceId, firstId)
  reused.release()
})

test('continuations reuse the resident app-server child that has the thread loaded', async t => {
  const pool = new AppServerPool(testConfig('C:\\state'), async () => new FakeRuntime())
  t.after(() => pool.drain(100))

  const generic = await pool.acquire(lane('loaded-thread'))
  const loaded = await pool.acquire(lane('loaded-thread'))
  const loadedRuntime = loaded.runtime as FakeRuntime
  loadedRuntime.loadThread('thread-loaded')
  generic.release()
  loaded.release()

  const resumed = await pool.acquireForThread(lane('loaded-thread'), 'thread-loaded')
  assert.equal(resumed.instanceId, loaded.instanceId)
  resumed.release()
})

test('an active compatible lease never blocks a continuation from sharing the resident child', async t => {
  const pool = new AppServerPool(testConfig('C:\\state'), async () => new FakeRuntime())
  t.after(() => pool.drain(100))

  const busyLoaded = await pool.acquire(lane('soft-loaded-thread'))
  const idleFallback = await pool.acquire(lane('soft-loaded-thread'))
  const busyLoadedRuntime = busyLoaded.runtime as FakeRuntime
  busyLoadedRuntime.loadThread('thread-busy')
  idleFallback.release()

  const resumed = await pool.acquireForThread(lane('soft-loaded-thread'), 'thread-busy')
  assert.equal(resumed.instanceId, idleFallback.instanceId)
  resumed.release()
  busyLoaded.release()
})

test('loaded-thread lookup does not rotate a healthy resident child after its legacy TTL', async t => {
  let now = 1_000
  let expireAfterNextRead = false
  const config = testConfig('C:\\state', {
    poolMaxInstances: 1,
    poolMaxInstancesPerLane: 1,
    poolIdleTtlMs: 100,
  })
  const pool = new AppServerPool(config, async () => new FakeRuntime(), () => {
    const current = now
    if (expireAfterNextRead) {
      expireAfterNextRead = false
      now += 101
    }
    return current
  })
  t.after(() => pool.drain(100))

  const loaded = await pool.acquire(lane('expiring-loaded-thread'))
  const loadedRuntime = loaded.runtime as FakeRuntime
  const loadedInstanceId = loaded.instanceId
  loadedRuntime.loadThread('thread-expiring')
  loaded.release()

  expireAfterNextRead = true
  const resumed = await pool.acquireForThread(lane('expiring-loaded-thread'), 'thread-expiring')
  assert.equal(resumed.instanceId, loadedInstanceId)
  assert.equal(loadedRuntime.closed, false)
  resumed.release()
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
    cliVersion: '0.144.3',
    baseEnv,
    apiKey: 'sk-one',
    baseUrl: 'https://api.one.test',
    codexHome: 'C:\\codex\\one',
  })
  const same = await buildAppServerLane({
    cliVersion: '0.144.3',
    baseEnv: { ...baseEnv },
    apiKey: 'sk-one',
    baseUrl: 'https://api.one.test',
    codexHome: 'C:\\codex\\one',
  })
  const differentAuth = await buildAppServerLane({
    cliVersion: '0.144.3', baseEnv, apiKey: 'sk-two', baseUrl: 'https://api.one.test', codexHome: 'C:\\codex\\one',
  })
  const differentHome = await buildAppServerLane({
    cliVersion: '0.144.3', baseEnv, apiKey: 'sk-one', baseUrl: 'https://api.one.test', codexHome: 'C:\\codex\\two',
  })
  const differentUrl = await buildAppServerLane({
    cliVersion: '0.144.3', baseEnv, apiKey: 'sk-one', baseUrl: 'https://api.two.test', codexHome: 'C:\\codex\\one',
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
    cliVersion: '0.144.3',
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
  private readonly loadedThreadIds = new Set<string>()
  private readonly fatalHandlers = new Set<(error: Error) => void>()

  isHealthy(): boolean { return this.healthy && !this.closed }
  isActive(): boolean { return this.active }
  async runTurn(_options: PersistentTurnOptions): Promise<AppServerTurnResult> {
    this.active = true
    this.active = false
    return { threadId: 'thread', turn: { id: 'turn', status: 'completed' } }
  }
  async readThread(): Promise<Record<string, unknown>> { return { id: 'thread', turns: [] } }
  async listLoadedThreads(): Promise<string[]> { return [...this.loadedThreadIds] }
  close(): void { this.closed = true; this.healthy = false }
  onFatal(handler: (error: Error) => void): () => void {
    this.fatalHandlers.add(handler)
    return () => this.fatalHandlers.delete(handler)
  }
  crash(): void {
    this.healthy = false
    for (const handler of this.fatalHandlers) handler(new Error('child exited'))
  }
  loadThread(threadId: string): void { this.loadedThreadIds.add(threadId) }
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
