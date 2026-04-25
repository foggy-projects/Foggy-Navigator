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

test('createConfig parses max concurrent tasks and rejects invalid values', () => {
  const config = createConfig({
    CODEX_MAX_CONCURRENT_TASKS: '3',
  })

  assert.equal(config.maxConcurrentTasks, 3)

  assert.throws(() => createConfig({
    CODEX_MAX_CONCURRENT_TASKS: '0',
  }), /CODEX_MAX_CONCURRENT_TASKS must be between 1 and 32/)
})

test('createConfig falls back to alias codex-latest when CODEX_DEFAULT_MODEL is unset', () => {
  const config = createConfig({})
  // 1.0.4 起：默认 alias，由 resolveModelAlias 解析为真实模型；前端零变动响应模型升级
  assert.equal(config.defaultModel, 'codex-latest')
})

test('createConfig honors CODEX_DEFAULT_MODEL override including reasoning suffix', () => {
  const config = createConfig({
    CODEX_DEFAULT_MODEL: 'gpt-5.5:high',
  })
  assert.equal(config.defaultModel, 'gpt-5.5:high')
})

test('createConfig rejects CODEX_DEFAULT_MODEL containing whitespace', () => {
  assert.throws(() => createConfig({
    CODEX_DEFAULT_MODEL: 'gpt 5.5',
  }), /CODEX_DEFAULT_MODEL must not contain whitespace/)
})

test('createConfig provides default Codex modelAliases when CODEX_MODEL_ALIASES is unset', () => {
  const config = createConfig({})
  assert.equal(config.modelAliases['codex-latest'], 'gpt-5.5')
  assert.equal(config.modelAliases['codex-fast'], 'gpt-5.5:low')
  assert.equal(config.modelAliases['codex-deep'], 'gpt-5.5:high')
  assert.equal(config.modelAliases['codex-mini'], 'gpt-5.4-mini')
})

test('createConfig CODEX_MODEL_ALIASES JSON override merges into defaults', () => {
  const config = createConfig({
    CODEX_MODEL_ALIASES: JSON.stringify({
      'codex-latest': 'gpt-5.6',
      'codex-experimental': 'gpt-5.6:high',
    }),
  })
  // 覆盖 codex-latest
  assert.equal(config.modelAliases['codex-latest'], 'gpt-5.6')
  // 新增自定义 alias
  assert.equal(config.modelAliases['codex-experimental'], 'gpt-5.6:high')
  // 未覆盖的 alias 保持默认
  assert.equal(config.modelAliases['codex-fast'], 'gpt-5.5:low')
})

test('createConfig CODEX_MODEL_ALIASES rejects non-JSON values', () => {
  assert.throws(() => createConfig({
    CODEX_MODEL_ALIASES: 'codex-latest=gpt-5.6',
  }), /CODEX_MODEL_ALIASES must be valid JSON object/)
})

test('createConfig CODEX_MODEL_ALIASES rejects array', () => {
  assert.throws(() => createConfig({
    CODEX_MODEL_ALIASES: '["codex-latest","gpt-5.6"]',
  }), /CODEX_MODEL_ALIASES must be a JSON object/)
})

test('createConfig CODEX_MODEL_ALIASES rejects alias key containing colon', () => {
  assert.throws(() => createConfig({
    CODEX_MODEL_ALIASES: JSON.stringify({ 'codex:latest': 'gpt-5.6' }),
  }), /alias key must not contain ":"/)
})

test('createConfig CODEX_MODEL_ALIASES rejects empty alias value', () => {
  assert.throws(() => createConfig({
    CODEX_MODEL_ALIASES: JSON.stringify({ 'codex-latest': '' }),
  }), /alias value must not be empty/)
})

test('createConfig CODEX_MODEL_ALIASES rejects whitespace in alias value', () => {
  assert.throws(() => createConfig({
    CODEX_MODEL_ALIASES: JSON.stringify({ 'codex-latest': 'gpt 5.6' }),
  }), /alias value must not contain whitespace/)
})
