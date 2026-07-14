import test from 'node:test'
import assert from 'node:assert/strict'
import {
  checkCodexSdkAvailable,
  resolveCodexAuthMode,
  resolveCodexBizReadiness,
  resolveNavigatorWorkerCredentialReadiness,
  resolveWorkerHealthStatus,
} from '../src/routes/health.ts'
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

test('configured Navigator Worker credential keeps Codex Worker unready without exposing it', () => {
  const reasons = resolveNavigatorWorkerCredentialReadiness('worker-a', true)

  assert.deepEqual(reasons, [CODEX_NAVIGATOR_WORKER_CREDENTIAL_FORWARDING_UNREADY])
  assert.equal(reasons.some(reason => /bwc_|credential-value/.test(reason)), false)
  assert.deepEqual(resolveNavigatorWorkerCredentialReadiness('', false), [])
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
