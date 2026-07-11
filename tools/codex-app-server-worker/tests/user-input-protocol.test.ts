import assert from 'node:assert/strict'
import test from 'node:test'
import type { PendingUserInputInteraction } from '../src/models.js'
import {
  normalizeUserInputAnswers,
  parseUserInputServerRequest,
  UserInputProtocolError,
  UserInputResponseValidationError,
} from '../src/app-server/user-input.js'

test('pinned request_user_input fields are allowlisted and normalized without arbitrary payloads', () => {
  const parsed = parseUserInputServerRequest({
    id: 'request-1',
    method: 'item/tool/requestUserInput',
    params: {
      threadId: 'thread-1',
      turnId: 'turn-1',
      itemId: 'item-1',
      autoResolutionMs: 60_000,
      ignoredSecretField: 'must-not-project',
      questions: [{
        id: 'choice',
        header: 'Mode',
        question: 'Choose a mode',
        options: [
          { label: 'Safe', description: 'Use safe mode', ignored: 'drop' },
          { label: 'Fast', description: 'Use fast mode' },
        ],
        isOther: false,
        isSecret: true,
        ignored: 'drop',
      }],
    },
  })

  assert.deepEqual(parsed, {
    requestId: 'request-1',
    method: 'item/tool/requestUserInput',
    threadId: 'thread-1',
    turnId: 'turn-1',
    itemId: 'item-1',
    autoResolutionMs: 60_000,
    questions: [{
      id: 'choice',
      header: 'Mode',
      question: 'Choose a mode',
      options: [
        { label: 'Safe', description: 'Use safe mode' },
        { label: 'Fast', description: 'Use fast mode' },
      ],
      is_other: false,
      is_secret: true,
    }],
  })
  assert.doesNotMatch(JSON.stringify(parsed), /ignoredSecretField|must-not-project|"ignored"/)
})

test('request_user_input rejects malformed identities, duplicate questions and unsafe auto resolution', () => {
  const valid = {
    id: 7,
    method: 'item/tool/requestUserInput',
    params: {
      threadId: 'thread-1',
      turnId: 'turn-1',
      itemId: 'item-1',
      questions: [{ id: 'q1', header: 'One', question: 'First?' }],
    },
  }
  const invalid = [
    { ...valid, id: Number.MAX_SAFE_INTEGER + 1 },
    { ...valid, params: { ...valid.params, threadId: '' } },
    { ...valid, params: { ...valid.params, questions: [] } },
    { ...valid, params: { ...valid.params, questions: Array.from({ length: 4 }, (_, i) => ({ id: `q${i}`, header: 'H', question: 'Q' })) } },
    { ...valid, params: { ...valid.params, questions: [
      { id: 'same', header: 'H', question: 'Q1' },
      { id: 'same', header: 'H', question: 'Q2' },
    ] } },
    { ...valid, params: { ...valid.params, autoResolutionMs: 59_999 } },
    { ...valid, params: { ...valid.params, autoResolutionMs: 240_001 } },
  ]
  for (const request of invalid) {
    assert.throws(() => parseUserInputServerRequest(request), UserInputProtocolError)
  }
  assert.equal(parseUserInputServerRequest({
    ...valid,
    params: { ...valid.params, autoResolutionMs: 240_000 },
  }).autoResolutionMs, 240_000)
})

test('HTTP answers are exactly one value per question with exact-label precedence and ordinal shortcuts', () => {
  const interaction = pendingInteraction([
    {
      id: 'numeric-label',
      header: 'Numeric',
      question: 'Choose numeric',
      options: [
        { label: 'Alpha', description: '' },
        { label: '1', description: '' },
      ],
      is_other: false,
      is_secret: false,
    },
    {
      id: 'ordinal',
      header: 'Ordinal',
      question: 'Choose ordinal',
      options: [
        { label: 'First', description: '' },
        { label: 'Second', description: '' },
      ],
      is_other: false,
      is_secret: false,
    },
    {
      id: 'freeform',
      header: 'Other',
      question: 'Say something',
      options: [{ label: 'Known', description: '' }],
      is_other: true,
      is_secret: true,
    },
  ])

  assert.deepEqual(normalizeUserInputAnswers({
    'numeric-label': '1',
    ordinal: ['2'],
    freeform: 'private freeform answer',
  }, interaction), {
    answers: {
      'numeric-label': { answers: ['1'] },
      ordinal: { answers: ['Second'] },
      freeform: { answers: ['private freeform answer'] },
    },
  })

  for (const invalid of [
    { 'numeric-label': 'Alpha', ordinal: 'First' },
    { 'numeric-label': 'Alpha', ordinal: 'Unknown', freeform: 'x' },
    { 'numeric-label': ['Alpha', '1'], ordinal: 'First', freeform: 'x' },
    { 'numeric-label': '', ordinal: 'First', freeform: 'x' },
    { 'numeric-label': 'Alpha', ordinal: 'First', freeform: 'x', extra: 'no' },
  ]) {
    assert.throws(() => normalizeUserInputAnswers(invalid, interaction), UserInputResponseValidationError)
  }
})

test('answer normalization preserves question ids that are special object property names', () => {
  const interaction = pendingInteraction([
    {
      id: '__proto__', header: 'Prototype', question: 'Prototype answer?',
      is_other: true, is_secret: false,
    },
    {
      id: 'constructor', header: 'Constructor', question: 'Constructor answer?',
      is_other: true, is_secret: false,
    },
  ])
  const input = JSON.parse('{"__proto__":"prototype-value","constructor":"constructor-value"}')
  const result = normalizeUserInputAnswers(input, interaction)

  assert.equal(Object.hasOwn(result.answers, '__proto__'), true)
  assert.equal(Object.hasOwn(result.answers, 'constructor'), true)
  assert.deepEqual(result.answers.__proto__, { answers: ['prototype-value'] })
  assert.deepEqual(result.answers.constructor, { answers: ['constructor-value'] })
})

function pendingInteraction(questions: PendingUserInputInteraction['questions']): PendingUserInputInteraction {
  return {
    contract_version: 1,
    request_id: 'request-1',
    method: 'item/tool/requestUserInput',
    thread_id: 'thread-1',
    turn_id: 'turn-1',
    item_id: 'item-1',
    questions,
    runtime_instance_id: 'runtime-1',
    created_at: new Date(0).toISOString(),
  }
}
