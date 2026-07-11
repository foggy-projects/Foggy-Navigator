import test from 'node:test'
import assert from 'node:assert/strict'
import { validateModelString, validateQueryRequest } from '../src/validation/query.ts'

test('validateQueryRequest trims and returns normalized payload', () => {
  const result = validateQueryRequest({
    prompt: '  hello  ',
    cwd: '  D:\\repo  ',
    model: ' gpt-5.4:high ',
    session_id: ' abc ',
    api_key: ' sk-test ',
    max_turns: 2,
  })

  assert.equal(result.ok, true)
  if (!result.ok) return
  assert.deepEqual(result.value, {
    prompt: 'hello',
    cwd: 'D:\\repo',
    model: 'gpt-5.4:high',
    session_id: 'abc',
    api_key: 'sk-test',
    max_turns: 2,
  })
})

test('validateQueryRequest rejects unsupported model reasoning level', () => {
  const result = validateQueryRequest({
    prompt: 'hello',
    model: 'gpt-5.4:turbo',
  })

  assert.equal(result.ok, false)
  if (result.ok) return
  assert.equal(result.error, 'unsupported model reasoning level')
})

test('validateQueryRequest rejects non-object bodies', () => {
  const result = validateQueryRequest('hello')
  assert.equal(result.ok, false)
  if (result.ok) return
  assert.equal(result.error, 'request body must be a JSON object')
})

test('validateModelString accepts bare model names and known reasoning levels', () => {
  assert.equal(validateModelString('gpt-5.4-mini'), true)
  assert.equal(validateModelString('gpt-5.5:xhigh'), true)
  assert.equal(validateModelString('gpt-5.4:extra-high'), true)
  assert.equal(validateModelString('gpt-5.6-sol:max'), true)
  assert.equal(validateModelString('gpt-5.6-sol:ultra'), 'unsupported model reasoning level')
  assert.equal(validateModelString('gpt-5.6-sol: ultra'), 'unsupported model reasoning level')
})

test('validateQueryRequest accepts image attachments', () => {
  const result = validateQueryRequest({
    prompt: 'describe',
    images: [
      { name: 'screen.png', data: 'YmFzZTY0', mime_type: 'image/png' },
    ],
  })

  assert.equal(result.ok, true)
  if (!result.ok) return
  assert.deepEqual(result.value.images, [
    { name: 'screen.png', data: 'YmFzZTY0', mime_type: 'image/png' },
  ])
})

test('validateQueryRequest accepts URL attachment metadata', () => {
  const attachments = [
    {
      name: 'pod-photo.png',
      url: 'https://tms.example.com/files/pod-photo.png',
      kind: 'image',
    },
  ]
  const result = validateQueryRequest({
    prompt: 'describe',
    attachments,
  })

  assert.equal(result.ok, true)
  if (!result.ok) return
  assert.deepEqual(result.value.attachments, attachments)
})

test('validateQueryRequest accepts CodexBiz execution options', () => {
  const outputSchema = {
    type: 'object',
    properties: {
      decision: { type: 'string' },
    },
    required: ['decision'],
  }
  const result = validateQueryRequest({
    prompt: 'decide',
    codex_home_key: 'tenant-a/world-sim/scenario-1/actor-1',
    developer_instructions: 'Return only valid JSON.',
    output_schema: outputSchema,
    codex_config: { tool_output_token_limit: 4096 },
    sandbox_mode: 'workspace-write',
    approval_policy: 'never',
    network_access_enabled: false,
    web_search_mode: 'disabled',
    business_runtime_context: { task_scoped_token: 'token-1' },
    additional_directories: ['D:\\shared'],
  })

  assert.equal(result.ok, true)
  if (!result.ok) return
  assert.equal(result.value.codex_home_key, 'tenant-a/world-sim/scenario-1/actor-1')
  assert.equal(result.value.developer_instructions, 'Return only valid JSON.')
  assert.deepEqual(result.value.output_schema, outputSchema)
  assert.equal(result.value.sandbox_mode, 'workspace-write')
  assert.equal(result.value.approval_policy, 'never')
  assert.equal(result.value.network_access_enabled, false)
  assert.deepEqual(result.value.business_runtime_context, { task_scoped_token: 'token-1' })
  assert.deepEqual(result.value.additional_directories, ['D:\\shared'])
})

test('validateQueryRequest rejects unsupported CodexBiz enum values', () => {
  const result = validateQueryRequest({
    prompt: 'decide',
    sandbox_mode: 'host-root',
  })

  assert.equal(result.ok, false)
  if (result.ok) return
  assert.equal(result.error, 'sandbox_mode is not supported')
})
