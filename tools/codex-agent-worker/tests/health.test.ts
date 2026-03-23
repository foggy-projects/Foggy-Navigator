import test from 'node:test'
import assert from 'node:assert/strict'
import { checkCodexSdkAvailable, resolveCodexAuthMode } from '../src/routes/health.ts'

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
