import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import test from 'node:test'
import { StrictAppServerExecutor } from '../src/app-server/executor.js'
import { buildAppServerLane } from '../src/app-server/lane.js'
import type { AppServerLane, AppServerPool } from '../src/app-server/pool.js'
import type { PoolRateLimitsView } from '../src/app-server/rate-limits.js'
import { VALIDATED_APP_SERVER_CLI_VERSION } from '../src/app-server/runtime.js'
import { tempDirectory, testConfig } from './helpers.js'

const EMPTY_RATE_LIMITS: PoolRateLimitsView = {
  state: 'AVAILABLE',
  observed_at_epoch_ms: 1,
  stale: false,
  limits: [],
  error_code: null,
}

test('default rate-limit reads use the same configured startup lane as task execution', async t => {
  const stateDir = await tempDirectory('codex-rate-limit-executor-')
  const config = testConfig(stateDir, {
    openaiApiKey: 'sk-service-api-key',
    openaiBaseUrl: 'https://service-api.example.test',
  })
  await fs.mkdir(config.codexHome, { recursive: true })
  await fs.writeFile(`${config.codexHome}/auth.json`, JSON.stringify({ tokens: { access_token: 'chatgpt-login' } }))
  t.after(async () => {
    await fs.rm(stateDir, { recursive: true, force: true })
    await fs.rm(config.codexHome, { recursive: true, force: true })
  })

  let capturedLane: AppServerLane | undefined
  let capturedRefresh: boolean | undefined
  const pool = {
    async readRateLimits(lane: AppServerLane, refresh: boolean): Promise<PoolRateLimitsView> {
      capturedLane = lane
      capturedRefresh = refresh
      return EMPTY_RATE_LIMITS
    },
  } as unknown as AppServerPool
  const executor = new StrictAppServerExecutor(config, pool)

  assert.equal(await executor.readDefaultRateLimits(true), EMPTY_RATE_LIMITS)
  assert.ok(capturedLane)
  assert.equal(capturedRefresh, true)
  const expectedTaskLane = await buildAppServerLane({
    cliVersion: VALIDATED_APP_SERVER_CLI_VERSION,
    baseEnv: process.env,
    apiKey: config.openaiApiKey,
    baseUrl: config.openaiBaseUrl,
    codexHome: capturedLane.env.CODEX_HOME!,
  })
  assert.equal(capturedLane.key, expectedTaskLane.key)
  assert.equal(capturedLane.authFingerprint, expectedTaskLane.authFingerprint)
  assert.equal(capturedLane.baseUrlFingerprint, expectedTaskLane.baseUrlFingerprint)
})
