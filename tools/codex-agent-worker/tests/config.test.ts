import test from 'node:test'
import assert from 'node:assert/strict'
import { createConfig } from '../src/config.ts'

test('createConfig normalizes placeholder api key and valid defaults', () => {
  const config = createConfig({
    CODEX_WORKER_PORT: '3051',
    CODEX_WORKER_HOST: '127.0.0.1',
    CODEX_WORKER_NAME: 'worker-a',
    OPENAI_API_KEY: 'sk-xxx',
    CODEX_WORKER_TOKEN: '',
    CODEX_ALLOWED_CWDS: 'D:\\repo,D:\\repo',
    CODEX_LOG_LEVEL: 'warn',
  })

  assert.equal(config.openaiApiKey, '')
  assert.equal(config.port, 3051)
  assert.deepEqual(config.allowedCwds, ['D:\\repo'])
  assert.equal(config.logLevel, 'warn')
})

test('createConfig rejects invalid port', () => {
  assert.throws(() => createConfig({
    CODEX_WORKER_PORT: 'abc',
  }), /CODEX_WORKER_PORT must be an integer/)
})

test('createConfig rejects relative allowed cwd entries', () => {
  assert.throws(() => createConfig({
    CODEX_ALLOWED_CWDS: 'relative\\repo',
  }), /CODEX_ALLOWED_CWDS entries must be absolute paths/)
})

test('createConfig rejects invalid log level', () => {
  assert.throws(() => createConfig({
    CODEX_LOG_LEVEL: 'trace',
  }), /CODEX_LOG_LEVEL must be one of/)
})
