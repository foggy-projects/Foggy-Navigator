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

test('missing Codex rollout is classified as a safe thread-not-found diagnostic', () => {
  const result = safeSdkError(new Error(
    'no rollout found for thread id 019f65d8-5896-7391-95d6-196d2f721f3c',
  ))

  assert.equal(result.error_code, 'CODEX_THREAD_NOT_FOUND')
  assert.equal(result.error_category, 'CONFIGURATION')
  assert.equal(result.error_message, 'Codex 会话在当前 Worker Home 中不存在')
  assert.equal(result.diagnostic_text, 'CODEX_THREAD_NOT_FOUND')
  assert.doesNotMatch(JSON.stringify(result), /019f65d8-5896-7391-95d6-196d2f721f3c/)
})

test('unsupported model diagnostics use a fixed code without exposing the model or provider text', () => {
  const result = safeSdkError(new Error(
    "The model 'gpt-5.6-sol' is not supported at /workspace/private with Bearer secret-token",
  ))

  assert.equal(result.error_code, 'CODEX_MODEL_UNSUPPORTED')
  assert.equal(result.error_category, 'CONFIGURATION')
  assert.equal(result.diagnostic_text, 'CODEX_MODEL_UNSUPPORTED')
  assert.doesNotMatch(JSON.stringify(result), /gpt-5\.6-sol|workspace|secret-token|Bearer/i)
})

test('incomplete provider streams are not misreported as worker network failures', () => {
  const result = safeSdkError(new Error(
    'stream disconnected before completion: stream closed before response.completed',
  ))

  assert.equal(result.error_code, 'CODEX_STREAM_UNCONFIRMED')
  assert.equal(result.error_category, 'RUNTIME')
  assert.equal(result.error_message, 'Codex 运行时报告了待核验错误，任务状态尚未终态')
})
