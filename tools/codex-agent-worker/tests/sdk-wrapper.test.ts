import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import type { ThreadItem } from '@openai/codex-sdk'
import {
  buildCodexInput,
  buildCodexProcessEnv,
  getRunningTaskCount,
  mapThreadItemToEvents,
  parseModelString,
  resolveModelAlias,
  saveAttachments,
  taskRegistry,
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

const TEST_ALIASES: Record<string, string> = {
  'codex-latest': 'gpt-5.5',
  'codex-fast': 'gpt-5.5:low',
  'codex-deep': 'gpt-5.5:high',
  'codex-mini': 'gpt-5.4-mini',
}

test('resolveModelAlias returns the mapped real model when whole string hits an alias', () => {
  assert.deepEqual(resolveModelAlias('codex-latest', TEST_ALIASES), {
    resolved: 'gpt-5.5',
    wasAlias: true,
  })
  assert.deepEqual(resolveModelAlias('codex-mini', TEST_ALIASES), {
    resolved: 'gpt-5.4-mini',
    wasAlias: true,
  })
})

test('resolveModelAlias preserves alias-embedded reasoning when alias value already has colon', () => {
  // codex-fast → gpt-5.5:low (alias value already includes reasoning)
  assert.deepEqual(resolveModelAlias('codex-fast', TEST_ALIASES), {
    resolved: 'gpt-5.5:low',
    wasAlias: true,
  })
  // 请求 codex-fast:high — alias 自带 reasoning，请求的 high 被忽略（避免双重冒号）
  assert.deepEqual(resolveModelAlias('codex-fast:high', TEST_ALIASES), {
    resolved: 'gpt-5.5:low',
    wasAlias: true,
  })
})

test('resolveModelAlias appends reasoning suffix when alias value has no colon', () => {
  // codex-latest → gpt-5.5（无 reasoning）+ 请求 :high → gpt-5.5:high
  assert.deepEqual(resolveModelAlias('codex-latest:high', TEST_ALIASES), {
    resolved: 'gpt-5.5:high',
    wasAlias: true,
  })
  assert.deepEqual(resolveModelAlias('codex-latest:extra-high', TEST_ALIASES), {
    resolved: 'gpt-5.5:extra-high',
    wasAlias: true,
  })
})

test('resolveModelAlias passes through real model names unchanged (backward compat)', () => {
  assert.deepEqual(resolveModelAlias('gpt-5.5', TEST_ALIASES), {
    resolved: 'gpt-5.5',
    wasAlias: false,
  })
  assert.deepEqual(resolveModelAlias('gpt-5.5:high', TEST_ALIASES), {
    resolved: 'gpt-5.5:high',
    wasAlias: false,
  })
  assert.deepEqual(resolveModelAlias('gpt-5.4-mini', TEST_ALIASES), {
    resolved: 'gpt-5.4-mini',
    wasAlias: false,
  })
})

test('resolveModelAlias passes through unknown alias-like strings unchanged', () => {
  // 未在映射表中的 codex-* 串仍按真实模型名处理（不抛错，保持向后兼容）
  assert.deepEqual(resolveModelAlias('codex-future', TEST_ALIASES), {
    resolved: 'codex-future',
    wasAlias: false,
  })
})

test('shouldAbortBeforeTurnStart only aborts when completed turns reach the limit', () => {
  assert.equal(shouldAbortBeforeTurnStart(0, 1), false)
  assert.equal(shouldAbortBeforeTurnStart(1, 1), true)
  assert.equal(shouldAbortBeforeTurnStart(2, undefined), false)
})

test('getRunningTaskCount counts only running tasks', () => {
  const ids = ['running-a', 'done-b', 'running-c']
  try {
    taskRegistry.set(ids[0], { taskId: ids[0], status: 'running', startedAt: Date.now() })
    taskRegistry.set(ids[1], { taskId: ids[1], status: 'completed', startedAt: Date.now(), completedAt: Date.now() })
    taskRegistry.set(ids[2], { taskId: ids[2], status: 'running', startedAt: Date.now() })

    assert.equal(getRunningTaskCount(), 2)
  } finally {
    ids.forEach(id => taskRegistry.delete(id))
  }
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

test('saveAttachments writes image and non-image attachments separately', async () => {
  const cwd = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-images-'))
  const saved = await saveAttachments('task-images', cwd, [
    { name: 'screen.png', data: Buffer.from('pngdata').toString('base64'), mime_type: 'image/png' },
    { name: 'notes.txt', data: Buffer.from('hello').toString('base64'), mime_type: 'text/plain' },
  ])

  assert.equal(saved.imagePaths.length, 1)
  assert.equal(saved.filePaths.length, 1)
  const imageContent = await fs.readFile(saved.imagePaths[0]!, 'utf8')
  const fileContent = await fs.readFile(saved.filePaths[0]!, 'utf8')
  assert.equal(imageContent, 'pngdata')
  assert.equal(fileContent, 'hello')
})

test('buildCodexInput returns structured prompt when images exist', async () => {
  const cwd = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-input-'))
  const input = await buildCodexInput('task-input', 'describe image', cwd, [
    { name: 'screen.png', data: Buffer.from('img').toString('base64'), mime_type: 'image/png' },
  ])

  assert.ok(Array.isArray(input))
  assert.deepEqual(input[0], { type: 'text', text: 'describe image' })
  assert.equal(input[1]?.type, 'local_image')
})

test('buildCodexInput injects non-image attachment paths into prompt', async () => {
  const cwd = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-files-'))
  const input = await buildCodexInput('task-files', 'summarize attachment', cwd, [
    { name: 'notes.txt', data: Buffer.from('hello').toString('base64'), mime_type: 'text/plain' },
  ])

  assert.ok(Array.isArray(input))
  assert.equal(input.length, 1)
  assert.equal(input[0]?.type, 'text')
  assert.match(input[0]?.text || '', /The user attached files/)
  assert.match(input[0]?.text || '', /notes\.txt/)
  assert.match(input[0]?.text || '', /summarize attachment/)
})