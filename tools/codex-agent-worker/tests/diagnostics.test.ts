import assert from 'node:assert/strict'
import test from 'node:test'
import { safeSdkError, sanitizeDiagnostic } from '../src/diagnostics.js'

test('SDK diagnostics classify errors and never expose the raw message as error', () => {
  const result = safeSdkError(new Error('request timed out at /home/sa/project with Bearer secret-token'))
  assert.equal(result.error, 'CODEX_TURN_TIMEOUT')
  assert.equal(result.error_category, 'TIMEOUT')
  assert.equal(result.runtime_phase, 'TURN_EXECUTION')
  assert.doesNotMatch(result.diagnostic_text || '', /\/home\/sa|secret-token/)
})

test('diagnostic sanitizer removes credentials, urls and identity hints', () => {
  const result = sanitizeDiagnostic('api_key=abc123 https://u:p@example.com/a?q=x user@example.com 10.1.2.3')
  assert.equal(result, '[credential] [url] [email] [ip]')
})
