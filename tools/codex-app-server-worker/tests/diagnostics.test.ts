import assert from 'node:assert/strict'
import test from 'node:test'
import { classifyErrorCode, safeAppServerMessage } from '../src/diagnostics.js'

test('App Server diagnostics expose only stable metadata', () => {
  assert.equal(classifyErrorCode('APP_SERVER_TURN_STALLED'), 'TIMEOUT')
  assert.equal(classifyErrorCode('CODEX_AUTH_REQUIRED'), 'AUTHENTICATION')
  assert.match(safeAppServerMessage('APP_SERVER_TURN_STALLED'), /超时/)
})
