import assert from 'node:assert/strict'
import test from 'node:test'
import { parseModelString, resolveModelAlias } from '../src/model-resolution.js'
import { validateTaskRequest } from '../src/validation/task-request.js'

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
