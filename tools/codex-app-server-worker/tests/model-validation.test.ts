import assert from 'node:assert/strict'
import test from 'node:test'
import {
  parseModelString,
  resolveModelAlias,
  resolveSupportedModelAlias,
  UNSUPPORTED_CODEX_MODEL,
  UnsupportedCodexModelError,
} from '../src/model-resolution.js'
import { validateTaskRequest } from '../src/validation/task-request.js'
import { buildCodexConfig } from '../src/app-server/executor.js'

test('all declared reasoning efforts parse without SDK translation', () => {
  for (const effort of ['minimal', 'low', 'medium', 'high', 'xhigh', 'max', 'ultra'] as const) {
    assert.deepEqual(parseModelString(`gpt-5.6-sol:${effort}`), {
      model: 'gpt-5.6-sol',
      reasoningEffort: effort,
    })
  }
  assert.throws(() => parseModelString('gpt-5.6-sol:impossible'), /Unsupported reasoning effort/)
})

test('stable aliases resolve case-insensitively without allowing an Ultra downgrade', () => {
  const aliases = { 'codex-ultra': 'gpt-5.6-sol:ultra', 'codex-max': 'gpt-5.6-sol:max' }
  assert.equal(resolveModelAlias('CODEX-ULTRA', aliases), 'gpt-5.6-sol:ultra')
  assert.equal(resolveModelAlias('Codex-Max:high', aliases), 'gpt-5.6-sol:max')
})

test('retired Mini model is rejected after direct or alias resolution', () => {
  const aliases = { 'retired-mini': 'gpt-5.4-mini' }
  for (const model of ['gpt-5.4-mini', 'gpt-5.4-mini:high', 'retired-mini', 'retired-mini:xhigh']) {
    assert.throws(
      () => resolveSupportedModelAlias(model, aliases),
      (error: unknown) => error instanceof UnsupportedCodexModelError
        && error.code === UNSUPPORTED_CODEX_MODEL,
    )
  }
})

test('contract v1 fails closed for unsupported app-server approval and directory fields', () => {
  assert.deepEqual(validateTaskRequest({ prompt: 'x', approval_policy: 'on-failure' }), {
    ok: false,
    error: 'UNSUPPORTED_APPROVAL_POLICY',
  })
  assert.deepEqual(validateTaskRequest({ prompt: 'x', approval_policy: 'on-request' }), {
    ok: false,
    error: 'UNSUPPORTED_APPROVAL_POLICY',
  })
  assert.deepEqual(validateTaskRequest({ prompt: 'x', additional_directories: ['/tmp/other'] }), {
    ok: false,
    error: 'UNSUPPORTED_ADDITIONAL_DIRECTORIES',
  })
  assert.deepEqual(validateTaskRequest({ prompt: 'x', attachments: [{ url: 'https://example.test/a' }] }), {
    ok: false,
    error: 'UNSUPPORTED_ATTACHMENTS',
  })
  assert.deepEqual(validateTaskRequest({ prompt: 'x', business_runtime_context: {} }), {
    ok: false,
    error: 'UNSUPPORTED_BUSINESS_RUNTIME_CONTEXT',
  })
  assert.deepEqual(validateTaskRequest({ prompt: 'x', max_turns: 2 }), {
    ok: false,
    error: 'UNSUPPORTED_MAX_TURNS',
  })
  assert.deepEqual(validateTaskRequest({ prompt: 'x', env_vars: { PATH: '/private' } }), {
    ok: false,
    error: 'UNSUPPORTED_ENV_VARS',
  })
  for (const codexConfig of [
    { mcp_servers: { private: { command: 'steal-token' } } },
    { model_provider: 'private' },
    { model_providers: { private: { base_url: 'https://private.invalid' } } },
    { sandbox_workspace_write: { writable_roots: ['/'] } },
    { approval_policy: 'on-request' },
    { additional_directories: ['/private'] },
    { unknown_future_key: true },
    { 'features.default_mode_request_user_input': false },
    { features: { default_mode_request_user_input: false, another_experiment: true } },
  ]) {
    assert.deepEqual(validateTaskRequest({ prompt: 'x', codex_config: codexConfig }), {
      ok: false,
      error: 'UNSUPPORTED_CODEX_CONFIG_KEY',
    })
  }
  assert.deepEqual(validateTaskRequest({
    prompt: 'x',
    codex_config: { tool_output_token_limit: 4_096, model_context_window: 200_000 },
  }), {
    ok: true,
    value: {
      prompt: 'x',
      codex_config: { tool_output_token_limit: 4_096, model_context_window: 200_000 },
    },
  })
  assert.deepEqual(validateTaskRequest({ prompt: 'x', codex_config: { tool_output_token_limit: '4096' } }), {
    ok: false,
    error: 'INVALID_CODEX_CONFIG_VALUE',
  })
  assert.deepEqual(validateTaskRequest({ prompt: 'x', misspelled_option: true }), {
    ok: false,
    error: 'UNSUPPORTED_REQUEST_FIELD',
  })
})

test('server-owned request_user_input feature cannot be disabled or broadened by request config', () => {
  assert.deepEqual(buildCodexConfig({
    prompt: 'x',
    codex_config: { tool_output_token_limit: 4_096 },
  }, 'ultra'), {
    tool_output_token_limit: 4_096,
    model_auto_compact_token_limit: 140_000,
    approval_policy: 'never',
    'features.default_mode_request_user_input': true,
    'notice.hide_rate_limit_model_nudge': true,
    model_reasoning_effort: 'ultra',
  })
})
