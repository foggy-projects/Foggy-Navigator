import assert from 'node:assert/strict'
import test from 'node:test'
import { buildGeminiProcessEnv } from '../src/gemini/cli-wrapper.ts'

test('buildGeminiProcessEnv trusts workspace for headless worker tasks', () => {
  const env = buildGeminiProcessEnv(
    'task-1',
    'api-key-1',
    'https://gemini.example.test',
    { GEMINI_CLI_TRUST_WORKSPACE: 'false', CUSTOM_ENV: 'custom' },
  )

  assert.equal(env.GEMINI_CLI_TRUST_WORKSPACE, 'true')
  assert.equal(env.GEMINI_API_KEY, 'api-key-1')
  assert.equal(env.GEMINI_BASE_URL, 'https://gemini.example.test')
  assert.equal(env.FOGGY_GEMINI_TASK_ID, 'task-1')
  assert.equal(env.CUSTOM_ENV, 'custom')
})
