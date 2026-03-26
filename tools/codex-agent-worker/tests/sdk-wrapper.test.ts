import test from 'node:test'
import assert from 'node:assert/strict'
import type { ThreadItem } from '@openai/codex-sdk'
import {
  buildCodexProcessEnv,
  mapThreadItemToEvents,
  parseModelString,
  shouldAbortBeforeTurnStart,
} from '../src/codex/sdk-wrapper.ts'

function createSeq(): () => number {
  let seq = 0
  return () => ++seq
}

test('parseModelString maps extra-high to xhigh', () => {
  assert.deepEqual(parseModelString('gpt-5.4:extra-high'), {
    model: 'gpt-5.4',
    reasoningLevel: 'xhigh',
  })
})

test('shouldAbortBeforeTurnStart only aborts when completed turns reach the limit', () => {
  assert.equal(shouldAbortBeforeTurnStart(0, 1), false)
  assert.equal(shouldAbortBeforeTurnStart(1, 1), true)
  assert.equal(shouldAbortBeforeTurnStart(2, undefined), false)
})

test('command_execution completion emits monotonic seq values', () => {
  const item: ThreadItem = {
    id: 'cmd-1',
    type: 'command_execution',
    command: 'echo hello',
    aggregated_output: 'hello',
    status: 'completed',
  }

  const events = mapThreadItemToEvents('task-1', item, 'thread-1', createSeq())

  assert.equal(events.length, 2)
  assert.deepEqual(events.map(event => event.type), ['tool_use', 'tool_result'])
  assert.deepEqual(events.map(event => event.seq), [1, 2])
})

test('command_execution completion does not duplicate tool_use after item.started', () => {
  const item: ThreadItem = {
    id: 'cmd-2',
    type: 'command_execution',
    command: 'echo hello',
    aggregated_output: 'hello',
    status: 'completed',
  }

  const events = mapThreadItemToEvents('task-2', item, 'thread-2', createSeq(), new Set(['cmd-2']))

  assert.equal(events.length, 1)
  assert.equal(events[0]?.type, 'tool_result')
  assert.equal(events[0]?.seq, 1)
})

test('buildCodexProcessEnv hardens Windows child process environment', () => {
  const env = buildCodexProcessEnv(
    {
      PATH: 'C:\\Tools',
    },
    {
      platform: 'win32',
      tempDir: 'C:\\Temp',
      additionalPathEntries: ['D:\\codex\\vendor\\path'],
    }
  )

  assert.equal(env.CODEX_MANAGED_BY_NPM, '1')
  assert.equal(env.SystemRoot, 'C:\\WINDOWS')
  assert.equal(env.ComSpec, 'C:\\WINDOWS\\System32\\cmd.exe')
  assert.equal(env.TEMP, 'C:\\Temp')
  assert.equal(env.TMP, 'C:\\Temp')
  assert.equal(env.PATH, ['D:\\codex\\vendor\\path', 'C:\\Tools'].join(';'))
})

test('buildCodexProcessEnv preserves existing Windows variables and avoids duplicate PATH entries', () => {
  const env = buildCodexProcessEnv(
    {
      Path: ['D:\\codex\\vendor\\path', 'C:\\Tools'].join(';'),
      CODEX_MANAGED_BY_NPM: '0',
      SystemRoot: 'D:\\Windows',
      ComSpec: 'D:\\Windows\\System32\\cmd.exe',
      TEMP: 'D:\\Temp',
      TMP: 'D:\\Tmp',
    },
    {
      platform: 'win32',
      tempDir: 'C:\\Ignored',
      additionalPathEntries: ['D:\\codex\\vendor\\path'],
    }
  )

  assert.equal(env.CODEX_MANAGED_BY_NPM, '0')
  assert.equal(env.SystemRoot, 'D:\\Windows')
  assert.equal(env.ComSpec, 'D:\\Windows\\System32\\cmd.exe')
  assert.equal(env.TEMP, 'D:\\Temp')
  assert.equal(env.TMP, 'D:\\Tmp')
  assert.equal(env.Path, ['D:\\codex\\vendor\\path', 'C:\\Tools'].join(';'))
})
