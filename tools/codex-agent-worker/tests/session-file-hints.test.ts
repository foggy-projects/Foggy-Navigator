import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {
  extractSessionFileHintRecords,
  listSessionFileHints,
  recordSessionFileHintsForEvent,
  recordSessionFileHintsForEventBestEffort,
  sessionFileHintFileName,
} from '../src/persistence/session-file-hints.ts'
import type { WorkerEvent } from '../src/models.ts'

test('extractSessionFileHintRecords records completed file_change paths as high confidence', () => {
  const event: WorkerEvent = {
    type: 'tool_use',
    task_id: 'task-1',
    session_id: 'thread-1',
    tool: 'file_change',
    input: {
      status: 'completed',
      changes: [
        { path: 'src/app.ts', kind: 'update' },
        { path: 'README.md', kind: 'add' },
        { path: 'docs/notes.md', kind: 'modified' },
      ],
    },
    tool_use_id: 'patch-1',
    seq: 1,
  }

  const records = extractSessionFileHintRecords(event, {
    cwd: 'D:/repo',
    now: new Date('2026-06-28T01:02:03.000Z'),
  })

  assert.equal(records.length, 3)
  assert.equal(records[0]?.filePath, 'D:/repo/src/app.ts')
  assert.equal(records[0]?.cwdRelativePath, 'src/app.ts')
  assert.equal(records[0]?.pathScope, 'inside_cwd')
  assert.equal(records[0]?.openableInFileBrowser, true)
  assert.equal(records[0]?.confidence, 'high')
  assert.equal(records[0]?.changeKind, 'update')
  assert.equal(records[2]?.changeKind, 'update')
})

test('extractSessionFileHintRecords skips failed file_change events', () => {
  const event: WorkerEvent = {
    type: 'tool_use',
    task_id: 'task-1',
    session_id: 'thread-1',
    tool: 'file_change',
    input: {
      status: 'failed',
      changes: [{ path: 'src/app.ts', kind: 'update' }],
    },
    tool_use_id: 'patch-1',
    seq: 1,
  }

  assert.deepEqual(extractSessionFileHintRecords(event, { cwd: 'D:/repo' }), [])
})

test('extractSessionFileHintRecords records conservative command_execution path hints', () => {
  const event: WorkerEvent = {
    type: 'tool_use',
    task_id: 'task-2',
    session_id: 'thread-2',
    tool: 'command_execution',
    input: { command: 'Set-Content -Path src/generated.ts -Value hello' },
    tool_use_id: 'cmd-1',
    seq: 1,
  }

  const records = extractSessionFileHintRecords(event, { cwd: 'D:/repo' })

  assert.equal(records.length, 1)
  assert.equal(records[0]?.filePath, 'D:/repo/src/generated.ts')
  assert.equal(records[0]?.sourceTool, 'command_execution')
  assert.equal(records[0]?.confidence, 'low')
  assert.equal(records[0]?.changeKind, 'update')
})

test('extractSessionFileHintRecords keeps outside cwd paths non-openable', () => {
  const event: WorkerEvent = {
    type: 'tool_use',
    task_id: 'task-3',
    session_id: 'thread-3',
    tool: 'file_change',
    input: {
      status: 'completed',
      changes: [{ path: 'D:/other/outside.ts', kind: 'update' }],
    },
    tool_use_id: 'patch-outside',
    seq: 1,
  }

  const records = extractSessionFileHintRecords(event, { cwd: 'D:/repo' })

  assert.equal(records.length, 1)
  assert.equal(records[0]?.filePath, 'D:/other/outside.ts')
  assert.equal(records[0]?.cwdRelativePath, undefined)
  assert.equal(records[0]?.pathScope, 'outside_cwd')
  assert.equal(records[0]?.openableInFileBrowser, false)
})

test('recordSessionFileHintsForEvent persists JSONL and listSessionFileHints aggregates by file', async () => {
  const rootDir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-file-hints-'))
  const now = new Date('2026-06-28T08:00:00.000Z')
  try {
    const sessionId = 'thread-query-1'
    await recordSessionFileHintsForEvent({
      type: 'tool_use',
      task_id: 'task-a',
      session_id: sessionId,
      tool: 'file_change',
      input: { status: 'completed', changes: [{ path: 'src/app.ts', kind: 'update' }] },
      tool_use_id: 'patch-a',
      seq: 1,
    }, { cwd: '/repo', now, rootDir })

    await recordSessionFileHintsForEvent({
      type: 'tool_use',
      task_id: 'task-b',
      session_id: sessionId,
      tool: 'command_execution',
      input: { command: 'echo hi > src/app.ts' },
      tool_use_id: 'cmd-b',
      seq: 2,
    }, { cwd: '/repo', now, rootDir })

    const result = await listSessionFileHints(sessionId, { days: 1, now, rootDir })

    assert.equal(result.total, 1)
    assert.equal(result.files[0]?.filePath, '/repo/src/app.ts')
    assert.deepEqual(result.files[0]?.sourceTools.sort(), ['command_execution', 'file_change'])
    assert.equal(result.files[0]?.confidence, 'high')
    assert.equal(result.files[0]?.seenCount, 2)
  } finally {
    await fs.rm(rootDir, { recursive: true, force: true })
  }
})

test('recordSessionFileHintsForEventBestEffort isolates persistence failures', async () => {
  const rootFile = path.join(os.tmpdir(), `codex-file-hints-root-${Date.now()}.txt`)
  const originalWarn = console.warn
  const warnings: unknown[][] = []
  console.warn = (...args: unknown[]) => {
    warnings.push(args)
  }

  try {
    await fs.writeFile(rootFile, 'not a directory')

    recordSessionFileHintsForEventBestEffort({
      type: 'tool_use',
      task_id: 'task-best-effort',
      session_id: 'thread-best-effort',
      tool: 'file_change',
      input: { status: 'completed', changes: [{ path: 'src/app.ts', kind: 'update' }] },
      tool_use_id: 'patch-best-effort',
      seq: 1,
    }, { cwd: '/repo', now: new Date('2026-06-28T08:00:00.000Z'), rootDir: rootFile })

    await new Promise(resolve => setTimeout(resolve, 20))

    assert.equal(warnings.length, 1)
    assert.match(String(warnings[0]?.[0]), /Failed to persist session file hints/)
  } finally {
    console.warn = originalWarn
    await fs.rm(rootFile, { force: true })
  }
})

test('listSessionFileHints marks explicit date ranges as truncated when capped', async () => {
  const rootDir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-file-hints-empty-'))
  try {
    const result = await listSessionFileHints('thread-range-1', {
      from: '2026-01-01',
      to: '2026-06-28',
      rootDir,
    })

    assert.equal(result.total, 0)
    assert.equal(result.scanned_days, 120)
    assert.equal(result.truncated, true)
  } finally {
    await fs.rm(rootDir, { recursive: true, force: true })
  }
})

test('listSessionFileHints scans the most recent dates when explicit range is capped', async () => {
  const rootDir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-file-hints-recent-'))
  try {
    const sessionId = 'thread-range-recent'
    await recordSessionFileHintsForEvent({
      type: 'tool_use',
      task_id: 'task-old',
      session_id: sessionId,
      tool: 'file_change',
      input: { status: 'completed', changes: [{ path: 'old.ts', kind: 'update' }] },
      tool_use_id: 'patch-old',
      seq: 1,
    }, { cwd: '/repo', now: new Date('2026-01-02T08:00:00.000Z'), rootDir })

    await recordSessionFileHintsForEvent({
      type: 'tool_use',
      task_id: 'task-recent',
      session_id: sessionId,
      tool: 'file_change',
      input: { status: 'completed', changes: [{ path: 'recent.ts', kind: 'update' }] },
      tool_use_id: 'patch-recent',
      seq: 2,
    }, { cwd: '/repo', now: new Date('2026-06-28T08:00:00.000Z'), rootDir })

    const result = await listSessionFileHints(sessionId, {
      from: '2026-01-01',
      to: '2026-06-28',
      rootDir,
    })

    assert.equal(result.scanned_days, 120)
    assert.equal(result.truncated, true)
    assert.deepEqual(result.files.map(file => file.cwdRelativePath), ['recent.ts'])
  } finally {
    await fs.rm(rootDir, { recursive: true, force: true })
  }
})

test('sessionFileHintFileName uses session id directly when it is filename-safe', () => {
  assert.equal(sessionFileHintFileName('thread_abc-123.456'), 'thread_abc-123.456.jsonl')
  assert.match(sessionFileHintFileName('thread/unsafe'), /^thread_unsafe-[a-f0-9]{12}\.jsonl$/)
})
