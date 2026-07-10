import assert from 'node:assert/strict'
import path from 'node:path'
import test from 'node:test'
import {
  evaluateRuntimeReadiness,
  resetRuntimeProbeCacheForTests,
  resolveCachedCliAvailability,
} from '../src/runtime-capabilities.js'
import { testConfig } from './helpers.js'

test('CLI readiness probe is cached for a short TTL', () => {
  resetRuntimeProbeCacheForTests()
  let calls = 0
  const probe = (): boolean => {
    calls++
    return true
  }
  assert.equal(resolveCachedCliAvailability(probe, 1_000, 100), true)
  assert.equal(resolveCachedCliAvailability(probe, 1_050, 100), true)
  assert.equal(calls, 1)
  assert.equal(resolveCachedCliAvailability(probe, 1_101, 100), true)
  assert.equal(calls, 2)
  resetRuntimeProbeCacheForTests()
})

test('readiness fails closed on missing encryption key, isolated CODEX_HOME, or CLI mismatch', () => {
  const config = testConfig('C:\\state')
  assert.deepEqual(evaluateRuntimeReadiness(config, '0.144.0', true).reasons, [
    'APP_SERVER_CLI_VERSION_MISMATCH',
  ])
  assert.deepEqual(evaluateRuntimeReadiness({ ...config, stateEncryptionKey: undefined }, '0.144.1', true).reasons, [
    'STATE_ENCRYPTION_KEY_MISSING',
  ])
  assert.deepEqual(evaluateRuntimeReadiness(config, '0.144.1', false).reasons, [
    'APP_SERVER_CLI_UNAVAILABLE',
  ])
  assert.deepEqual(evaluateRuntimeReadiness({ ...config, codexHome: '' }, '0.144.1', true).reasons, [
    'CODEX_HOME_MISSING',
  ])
  assert.deepEqual(evaluateRuntimeReadiness({
    ...config,
    codexHome: path.join(config.stateDir, 'codex-home'),
  }, '0.144.1', true).reasons, [
    'CODEX_HOME_NOT_ISOLATED',
  ])
})
