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
  assert.equal(validateModelString('gpt-5.4:extra-high'), true)
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
