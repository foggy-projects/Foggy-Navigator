import assert from 'node:assert/strict'
import test from 'node:test'
import type { PersistentTurnOptions, AppServerTurnResult } from '../src/app-server/runtime.js'
import {
  AppServerPool,
  AppServerPoolDrainingError,
  AppServerPoolOverloadedError,
  type AppServerLane,
  type PoolRuntimeInstance,
} from '../src/app-server/pool.js'
import { buildAppServerLane } from '../src/app-server/lane.js'
import { testConfig, waitFor } from './helpers.js'

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
