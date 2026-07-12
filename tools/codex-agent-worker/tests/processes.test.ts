import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import test from 'node:test'
import { promisify } from 'node:util'
import {
  buildListProcessesWindowsScript,
  buildWindowsKillAttemptArgs,
  CodexProcessKillError,
  extractResumedThreadId,
  findCodexCliProcessForThread,
  killCodexCliProcessWindows,
} from '../src/codex/processes.ts'

const execFileAsync = promisify(execFile)

test('extractResumedThreadId reads resumed thread ids from Codex commands', () => {
  assert.equal(
    extractResumedThreadId('/usr/bin/codex exec --experimental-json resume thread-123'),
    'thread-123',
  )
  assert.equal(
    extractResumedThreadId('codex --experimental-json resume "thread quoted"'),
    'thread',
  )
  assert.equal(extractResumedThreadId('codex exec --experimental-json'), undefined)
})

test('findCodexCliProcessForThread uses registry pid before command fallback', () => {
  const processes = [
    { pid: 101, command: 'codex exec --experimental-json', memory_mb: 10, started_at: '' },
    { pid: 202, command: 'codex exec --experimental-json resume thread-orphan', memory_mb: 11, started_at: '' },
  ]
  assert.equal(findCodexCliProcessForThread('thread-known', processes, [
    { threadId: 'thread-known', pid: 101 },
  ])?.pid, 101)
  assert.equal(findCodexCliProcessForThread('thread-orphan', processes)?.pid, 202)
})

test('buildListProcessesWindowsScript keeps PowerShell expressions intact', () => {
  const script = buildListProcessesWindowsScript()

  assert.doesNotMatch(script, /-and\s*\(\s*;/)
  assert.doesNotMatch(script, /;\s*\)/)
  assert.match(script, /\$items = Get-CimInstance Win32_Process \| Where-Object \{/)
})

test('buildListProcessesWindowsScript executes successfully on Windows', {
  skip: process.platform !== 'win32',
}, async () => {
  const { stdout } = await execFileAsync('powershell', ['-NoProfile', '-Command', buildListProcessesWindowsScript()], {
    windowsHide: true,
    maxBuffer: 1024 * 1024 * 4,
  })

  const trimmed = stdout.trim()
  assert.ok(trimmed === '' || trimmed === 'null' || trimmed.startsWith('[') || trimmed.startsWith('{'))
})

test('buildWindowsKillAttemptArgs retries with stronger taskkill variants', () => {
  assert.deepEqual(buildWindowsKillAttemptArgs(12345, false), [
    ['/PID', '12345'],
    ['/PID', '12345', '/T'],
    ['/PID', '12345', '/F'],
    ['/PID', '12345', '/F', '/T'],
  ])
  assert.deepEqual(buildWindowsKillAttemptArgs(12345, true), [
    ['/PID', '12345', '/F'],
    ['/PID', '12345', '/F', '/T'],
  ])
})

test('killCodexCliProcessWindows escalates from graceful to forceful kill', async () => {
  const calls: string[][] = []
  let attempt = 0

  await killCodexCliProcessWindows(24680, false, async (_file, args) => {
    calls.push([...args])
    attempt += 1
    if (attempt < 4) {
      const error = new Error(`attempt ${attempt} failed`) as Error & {
        code?: number
        stderr?: string
      }
      error.code = 128
      error.stderr = `attempt ${attempt} failed`
      throw error
    }
    return { stdout: '', stderr: '' }
  })

  assert.deepEqual(calls, [
    ['/PID', '24680'],
    ['/PID', '24680', '/T'],
    ['/PID', '24680', '/F'],
    ['/PID', '24680', '/F', '/T'],
  ])
})

test('killCodexCliProcessWindows exposes detailed taskkill failures', async () => {
  await assert.rejects(
    killCodexCliProcessWindows(13579, true, async (_file, args) => {
      const error = new Error(`failed ${args.join(' ')}`) as Error & {
        code?: number
        stdout?: string
        stderr?: string
      }
      error.code = 5
      error.stdout = ''
      error.stderr = `ERROR ${args.join(' ')}`
      throw error
    }),
    (error: unknown) => {
      assert.ok(error instanceof CodexProcessKillError)
      assert.equal(error.pid, 13579)
      assert.equal(error.attempts.length, 2)
      assert.match(error.message, /Failed to kill process 13579/)
      assert.match(error.message, /taskkill \/PID 13579 \/F/)
      assert.match(error.message, /ERROR \/PID 13579 \/F \/T/)
      return true
    },
  )
})
