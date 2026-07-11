import assert from 'node:assert/strict'
import path from 'node:path'
import test from 'node:test'
import { createConfig, resolveInstanceId } from '../src/config.js'

test('default instance id is stable across process restarts and unique per state directory', () => {
  const first = resolveInstanceId(undefined, 'C:\\state\\one', 'worker-host')
  const restarted = resolveInstanceId(undefined, 'C:\\state\\one', 'worker-host')
  const otherState = resolveInstanceId(undefined, 'C:\\state\\two', 'worker-host')
  assert.equal(first, restarted)
  assert.notEqual(first, otherState)
  assert.match(first, /^worker-host-[0-9a-f]{20}$/)
  assert.equal(resolveInstanceId('explicit-instance', 'C:\\state\\one', 'worker-host'), 'explicit-instance')
})

test('runtime and instance identifiers respect the Java registry persistence limits', () => {
  assert.equal(createConfig({
    ...process.env,
    CODEX_APP_SERVER_RUNTIME_ID: 'r'.repeat(64),
    CODEX_APP_SERVER_INSTANCE_ID: 'i'.repeat(128),
  }).runtimeId.length, 64)
  assert.throws(() => createConfig({
    ...process.env,
    CODEX_APP_SERVER_RUNTIME_ID: 'r'.repeat(65),
  }), /CODEX_APP_SERVER_RUNTIME_ID must be 1-64 characters/)
  assert.throws(() => createConfig({
    ...process.env,
    CODEX_APP_SERVER_INSTANCE_ID: 'i'.repeat(129),
  }), /CODEX_APP_SERVER_INSTANCE_ID must be 1-128 characters/)
})

test('built-in Ultra and Max aliases cannot be silently downgraded', () => {
  assert.throws(() => createConfig({
    ...process.env,
    CODEX_MODEL_ALIASES: JSON.stringify({ 'codex-ultra': 'gpt-5.6-sol:high' }),
  }), /cannot override built-in alias: codex-ultra/)
  assert.throws(() => createConfig({
    ...process.env,
    CODEX_MODEL_ALIASES: JSON.stringify({ 'codex-max': 'gpt-5.6-sol:high' }),
  }), /cannot override built-in alias: codex-max/)
  assert.throws(() => createConfig({
    ...process.env,
    CODEX_MODEL_ALIASES: JSON.stringify({ 'CODEX-ULTRA': 'gpt-5.6-sol:high' }),
  }), /cannot override built-in alias: codex-ultra/)
  const accepted = createConfig({
    ...process.env,
    CODEX_MODEL_ALIASES: JSON.stringify({
      'codex-ultra': 'gpt-5.6-sol:ultra',
      'custom-review': 'gpt-5.6-sol:medium',
    }),
  })
  assert.equal(accepted.modelAliases['codex-ultra'], 'gpt-5.6-sol:ultra')
  assert.equal(accepted.modelAliases['codex-terra'], 'gpt-5.6-terra')
  assert.equal(accepted.modelAliases['codex-luna'], 'gpt-5.6-luna')
  assert.equal(accepted.modelAliases['codex-mini'], undefined)
  assert.equal(accepted.modelAliases['custom-review'], 'gpt-5.6-sol:medium')
})

test('custom aliases cannot target the retired Mini model', () => {
  for (const target of ['gpt-5.4-mini', 'GPT-5.4-MINI:high']) {
    assert.throws(() => createConfig({
      ...process.env,
      CODEX_MODEL_ALIASES: JSON.stringify({ 'retired-mini': target }),
    }), /cannot target retired model gpt-5\.4-mini: retired-mini/)
  }
})

test('default model cannot target the retired Mini model', () => {
  for (const target of ['gpt-5.4-mini', 'GPT-5.4-MINI:high']) {
    assert.throws(() => createConfig({
      ...process.env,
      CODEX_DEFAULT_MODEL: target,
    }), /CODEX_DEFAULT_MODEL cannot target retired model gpt-5\.4-mini/)
  }
})

test('concurrency cannot exceed the worst-case single-lane pool capacity', () => {
  assert.throws(() => createConfig({
    ...process.env,
    CODEX_APP_SERVER_MAX_CONCURRENT_TASKS: '10',
    CODEX_APP_SERVER_POOL_MAX_INSTANCES: '8',
    CODEX_APP_SERVER_POOL_MAX_INSTANCES_PER_LANE: '2',
    CODEX_APP_SERVER_POOL_MAX_QUEUE: '4',
  }), /per-lane instances plus pool queue/)
})

test('configuration rejects a CODEX_HOME nested in state or an allowed workspace', () => {
  const stateDir = path.resolve('.codex-app-server-config-test-state')
  assert.throws(() => createConfig({
    ...process.env,
    CODEX_APP_SERVER_STATE_DIR: stateDir,
    CODEX_APP_SERVER_ALLOWED_CWDS: process.cwd(),
    CODEX_HOME: path.join(stateDir, 'codex-home'),
    CODEX_BIZ_HOME_ROOT: '',
  }), error => error instanceof Error
    && (error as Error & { code?: string }).code === 'CODEX_HOME_NOT_ISOLATED')
})
