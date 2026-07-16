import assert from 'node:assert/strict'
import test from 'node:test'
import { safeSdkError, sanitizeDiagnostic } from '../src/diagnostics.js'

test('SDK diagnostics classify errors and never expose the raw message as error', () => {
  const result = safeSdkError(new Error('request timed out at /home/sa/project with Bearer secret-token'))
  assert.equal(result.error, 'CODEX_TURN_TIMEOUT')
  assert.equal(result.error_category, 'TIMEOUT')
  assert.equal(result.runtime_phase, 'TURN_EXECUTION')
  assert.equal(result.diagnostic_text, 'CODEX_TURN_TIMEOUT')
  assert.doesNotMatch(result.diagnostic_text || '', /\/home\/sa|secret-token/)
})

test('diagnostic sanitizer returns only a stable code for arbitrary runtime text', () => {
  const result = sanitizeDiagnostic('api_key=abc123 https://u:p@example.com/a?q=x user@example.com 10.1.2.3')
  assert.equal(result, 'CODEX_AUTH_REQUIRED')
  assert.doesNotMatch(result || '', /abc123|example\.com|user@|10\.1\.2\.3/)
})
