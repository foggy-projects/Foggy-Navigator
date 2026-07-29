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

test('buildGeminiProcessEnv preserves HOME and resolves a missing POSIX HOME from the effective user', () => {
  const preserved = buildGeminiProcessEnv(
    'task-preserved',
    undefined,
    undefined,
    undefined,
    { HOME: '/custom/home' },
    'linux',
    () => '/home/effective-user',
  )
  const resolved = buildGeminiProcessEnv(
    'task-resolved',
    undefined,
    undefined,
    undefined,
    { HOME: '' },
    'linux',
    () => '/home/effective-user',
  )

  assert.equal(preserved.HOME, '/custom/home')
  assert.equal(resolved.HOME, '/home/effective-user')
})

test('buildGeminiProcessEnv leaves HOME unset when effective user lookup fails', () => {
  const env = buildGeminiProcessEnv(
    'task-failed-lookup',
    undefined,
    undefined,
    undefined,
    {},
    'linux',
    () => {
      throw new Error('uid is not present in the user database')
    },
  )

  assert.equal(env.HOME, undefined)
})
