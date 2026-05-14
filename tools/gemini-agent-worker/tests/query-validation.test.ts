import test from 'node:test'
import assert from 'node:assert/strict'
import { validateQueryRequest } from '../src/validation/query.ts'

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

test('validateQueryRequest rejects non-array attachments', () => {
  const result = validateQueryRequest({
    prompt: 'describe',
    attachments: { url: 'https://tms.example.com/files/pod-photo.png' },
  })

  assert.equal(result.ok, false)
  if (result.ok) return
  assert.equal(result.error, 'attachments must be an array')
})
