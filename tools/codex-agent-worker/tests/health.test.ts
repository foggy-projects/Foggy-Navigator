import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {
  checkCodexSdkAvailable,
  resolveCodexAuthMode,
  resolveCodexBizReadiness,
  resolveCodexHomeAuthReadiness,
  resolveNavigatorWorkerCredentialReadiness,
  resolveLifecycleContractHealth,
  resolveTerminationReadiness,
  resolveWorkerHealthStatus,
} from '../src/routes/health.ts'
import { LifecycleStore } from '../src/lifecycle/store.ts'
import { CODEX_NAVIGATOR_WORKER_CREDENTIAL_FORWARDING_UNREADY } from '../src/codex/sdk-wrapper.ts'
import { resolveExternalModeState } from '../src/external-mode.ts'

test('resolveCodexAuthMode prefers api key over codex login', () => {
  assert.equal(resolveCodexAuthMode('sk-test', true), 'api_key')
  assert.equal(resolveCodexAuthMode('', true), 'codex_login')
  assert.equal(resolveCodexAuthMode(undefined, false), 'none')
})

test('checkCodexSdkAvailable returns false when Codex construction throws', () => {
  assert.equal(checkCodexSdkAvailable(() => {
    throw new Error('missing binary')
  }), false)
})

test('checkCodexSdkAvailable returns true when Codex construction succeeds', () => {
  assert.equal(checkCodexSdkAvailable(() => ({})), true)
})

test('resolveWorkerHealthStatus requires both SDK availability and compatibility', () => {
  assert.equal(resolveWorkerHealthStatus(true, true), 'ok')
  assert.equal(resolveWorkerHealthStatus(false, true), 'degraded')
  assert.equal(resolveWorkerHealthStatus(true, false), 'degraded')
})

test('resolveCodexBizReadiness exposes only non-sensitive scoped home state', () => {
  assert.deepEqual(resolveCodexBizReadiness('/tmp/foggy/codex-biz-homes'), {
    codex_biz_home_root_configured: true,
    codex_biz_scoped_home_ready: true,
  })
  assert.deepEqual(resolveCodexBizReadiness('  '), {
    codex_biz_home_root_configured: false,
    codex_biz_scoped_home_ready: false,
  })
})

test('Codex auth health uses the effective default home without exposing its path', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-health-home-'))
  const codexHome = path.join(root, 'configured-codex-home')
  fs.mkdirSync(codexHome, { recursive: true })
  fs.writeFileSync(path.join(codexHome, 'auth.json'), '{"token":"fake-test-value"}')

  try {
    const login = resolveCodexHomeAuthReadiness(codexHome, 'worker_config', '')
    assert.deepEqual(login, {
      codex_home_source: 'worker_config',
      codex_home_auth_configured: true,
      codex_auth_configured: true,
      codex_auth_mode: 'codex_login',
    })
    assert.doesNotMatch(JSON.stringify(login), new RegExp(root.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))

    const apiKey = resolveCodexHomeAuthReadiness(path.join(root, 'missing'), 'user_default', 'sk-test')
    assert.equal(apiKey.codex_home_auth_configured, false)
    assert.equal(apiKey.codex_auth_configured, true)
    assert.equal(apiKey.codex_auth_mode, 'api_key')
    assert.equal(apiKey.codex_home_source, 'user_default')
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('only a configured Gateway credential keeps Codex execution unready', () => {
  assert.deepEqual(resolveNavigatorWorkerCredentialReadiness('worker-a', false), [])
  const reasons = resolveNavigatorWorkerCredentialReadiness('worker-a', true)

  assert.deepEqual(reasons, [CODEX_NAVIGATOR_WORKER_CREDENTIAL_FORWARDING_UNREADY])
  assert.equal(reasons.some(reason => /bwc_|credential-value/.test(reason)), false)
  assert.deepEqual(resolveNavigatorWorkerCredentialReadiness('', false), [])
  assert.deepEqual(
    resolveNavigatorWorkerCredentialReadiness('', true),
    [CODEX_NAVIGATOR_WORKER_CREDENTIAL_FORWARDING_UNREADY],
  )
})

test('termination readiness is independent and reports only fixed non-secret reasons', () => {
  assert.deepEqual(resolveTerminationReadiness('worker-a', 'worker-token', true), {
    termination_ready: true,
    termination_reasons: [],
    termination_worker_id_configured: true,
    termination_auth_configured: true,
    termination_replay_ledger_ready: true,
  })

  const unavailable = resolveTerminationReadiness('', '', false)
  assert.deepEqual(unavailable, {
    termination_ready: false,
    termination_reasons: [
      'TERMINATION_WORKER_ID_REQUIRED',
      'TERMINATION_AUTH_TOKEN_REQUIRED',
      'TERMINATION_REPLAY_LEDGER_UNAVAILABLE',
    ],
    termination_worker_id_configured: false,
    termination_auth_configured: false,
    termination_replay_ledger_ready: false,
  })
  assert.doesNotMatch(JSON.stringify(unavailable), /worker-a|worker-token|\/var\//)
})

test('lifecycle health is content-free and fail-closed', () => {
  const unavailable = resolveLifecycleContractHealth(
    undefined, 'LIFECYCLE_AUTH_NOT_CONFIGURED',
  )
  assert.equal(unavailable.ready, false)
  assert.deepEqual(unavailable.reason_codes, ['LIFECYCLE_AUTH_NOT_CONFIGURED'])
  assert.deepEqual(unavailable.capabilities, [])

  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-lifecycle-health-'))
  try {
    const store = LifecycleStore.open({
      directory: root,
      physicalWorkerId: 'fixture-worker',
      workerToken: 'fixture-token',
      instanceEpoch: 'fixture-epoch',
    })
    const ready = resolveLifecycleContractHealth(store)
    assert.equal(ready.ready, true)
    assert.equal(ready.physical_worker_id, 'fixture-worker')
    assert.ok(ready.capabilities.includes('OWNERSHIP_MODE_BOUND_DISPATCH_V1'))
    assert.doesNotMatch(JSON.stringify(ready), new RegExp(root))
    assert.doesNotMatch(JSON.stringify(ready), /fixture-token/)
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('external mode never treats a configured bearer token as execution readiness', () => {
  assert.deepEqual(resolveExternalModeState({ externalEnabled: false, workerToken: '' }), {
    mode: 'internal-dev',
    external_enabled: false,
    external_ready: false,
    auth_configured: false,
    reasons: [],
  })
  assert.deepEqual(resolveExternalModeState({ externalEnabled: true, workerToken: '' }), {
    mode: 'external-enabled',
    external_enabled: true,
    external_ready: false,
    auth_configured: false,
    reasons: ['EXTERNAL_AUTH_TOKEN_REQUIRED', 'EXTERNAL_EXECUTION_POLICY_PENDING'],
  })
  assert.deepEqual(resolveExternalModeState({ externalEnabled: true, workerToken: 'secret' }), {
    mode: 'external-enabled',
    external_enabled: true,
    external_ready: false,
    auth_configured: true,
    reasons: ['EXTERNAL_EXECUTION_POLICY_PENDING'],
  })
})
