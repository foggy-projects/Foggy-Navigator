import test from 'node:test'
import assert from 'node:assert/strict'
import {
  checkCodexSdkAvailable,
  resolveCodexAuthMode,
  resolveCodexBizReadiness,
  resolveWorkerHealthStatus,
} from '../src/routes/health.ts'

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
