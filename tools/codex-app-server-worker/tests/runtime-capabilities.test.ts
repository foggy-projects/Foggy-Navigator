import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
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
  assert.deepEqual(evaluateRuntimeReadiness({ ...config, stateEncryptionKey: undefined }, '0.144.3', true).reasons, [
    'STATE_ENCRYPTION_KEY_MISSING',
  ])
  assert.deepEqual(evaluateRuntimeReadiness(config, '0.144.3', false).reasons, [
    'APP_SERVER_CLI_UNAVAILABLE',
  ])
  assert.deepEqual(evaluateRuntimeReadiness({ ...config, codexHome: '' }, '0.144.3', true).reasons, [
    'CODEX_HOME_MISSING',
  ])
  assert.deepEqual(evaluateRuntimeReadiness({
    ...config,
    codexHome: path.join(config.stateDir, 'codex-home'),
  }, '0.144.3', true).reasons, [
    'CODEX_HOME_NOT_ISOLATED',
  ])
})

test('readiness accepts a filesystem-root allowlist with isolated private paths', t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-app-root-readiness-'))
  const stateDir = path.join(root, 'state')
  const codexHome = path.join(root, 'codex-home')
  fs.mkdirSync(stateDir)
  fs.mkdirSync(codexHome)
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))

  const config = testConfig(stateDir, {
    codexHome,
    allowedCwds: [path.parse(root).root],
    workerToken: '',
  })
  assert.deepEqual(evaluateRuntimeReadiness(config, '0.144.3', true).reasons, [])
})

test('readiness rejects unavailable or wholly private workspace roots', t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-app-root-unavailable-'))
  const stateDir = path.join(root, 'state')
  const codexHome = path.join(root, 'codex-home')
  fs.mkdirSync(stateDir)
  fs.mkdirSync(codexHome)
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))

  const config = testConfig(stateDir, { codexHome })
  assert.deepEqual(evaluateRuntimeReadiness({
    ...config,
    allowedCwds: [path.join(root, 'missing-drive')],
  }, '0.144.3', true).reasons, ['ALLOWED_CWDS_UNAVAILABLE'])
  assert.deepEqual(evaluateRuntimeReadiness({
    ...config,
    allowedCwds: [stateDir],
  }, '0.144.3', true).reasons, ['ALLOWED_CWDS_UNAVAILABLE'])
})

test('external readiness requires auth and remains closed while execution policy is pending', () => {
  const config = testConfig('C:\\state', { externalEnabled: true, workerToken: '' })
  assert.deepEqual(evaluateRuntimeReadiness(config, '0.144.3', true).reasons, [
    'EXTERNAL_AUTH_TOKEN_REQUIRED',
    'EXTERNAL_EXECUTION_POLICY_PENDING',
  ])
  assert.deepEqual(evaluateRuntimeReadiness({
    ...config,
    workerToken: 'configured-secret',
  }, '0.144.3', true).reasons, [
    'EXTERNAL_EXECUTION_POLICY_PENDING',
  ])
})
