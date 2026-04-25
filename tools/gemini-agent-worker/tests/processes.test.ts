import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import test from 'node:test'
import { promisify } from 'node:util'
import {
  buildListProcessesWindowsScript,
  buildWindowsKillAttemptArgs,
  collapseGeminiProcessTree,
  extractGeminiSessionId,
  GeminiProcessKillError,
  killGeminiCliProcessWindows,
} from '../src/gemini/processes.ts'

const execFileAsync = promisify(execFile)

test('buildListProcessesWindowsScript keeps PowerShell expressions intact', () => {
  const script = buildListProcessesWindowsScript()

  assert.doesNotMatch(script, /-and\s*\(\s*;/)
  assert.doesNotMatch(script, /;\s*\)/)
  assert.match(script, /\$items = Get-CimInstance Win32_Process \| Where-Object \{/)
})

test('buildListProcessesWindowsScript does not classify generic stream-json commands as Gemini', () => {
  const script = buildListProcessesWindowsScript()

  assert.doesNotMatch(script, /--output-format\\s\+stream-json/)
  assert.match(script, /bundle\\\\gemini\\\.js/)
  assert.match(script, /@google\\\\gemini-cli/)
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

test('extractGeminiSessionId parses resume flags', () => {
  assert.equal(
    extractGeminiSessionId('cmd.exe /c gemini -p "hi" --output-format stream-json -r session-1 --yolo'),
    'session-1',
  )
  assert.equal(
    extractGeminiSessionId('gemini --resume session-2 -p "hi"'),
    'session-2',
  )
  assert.equal(extractGeminiSessionId('gemini -p "hi"'), undefined)
})

test('collapseGeminiProcessTree hides wrapper parent processes', () => {
  const collapsed = collapseGeminiProcessTree([
    {
      pid: 39196,
      parent_pid: 49852,
      command: '"node" "C:\\Users\\oldse\\AppData\\Roaming\\npm\\node_modules\\@google\\gemini-cli\\bundle\\gemini.js"',
      memory_mb: 44.4,
      started_at: '2026-04-25T00:06:29.3507790+08:00',
    },
    {
      pid: 58144,
      parent_pid: 39196,
      command: '"C:\\Program Files\\nodejs\\node.exe" --max-old-space-size=32349 C:\\Users\\oldse\\AppData\\Roaming\\npm\\node_modules\\@google\\gemini-cli\\bundle\\gemini.js',
      memory_mb: 52.8,
      started_at: '2026-04-25T00:06:44.9936470+08:00',
    },
  ])

  assert.deepEqual(collapsed.map(item => item.pid), [58144])
})

test('collapseGeminiProcessTree keeps explicit gemini task commands', () => {
  const collapsed = collapseGeminiProcessTree([
    {
      pid: 101,
      parent_pid: 1,
      command: 'cmd.exe /c gemini -p "hi" --output-format stream-json',
      memory_mb: 20,
      started_at: '2026-04-25T00:00:00+08:00',
    },
    {
      pid: 202,
      parent_pid: 101,
      command: '"C:\\Program Files\\nodejs\\node.exe" --max-old-space-size=32349 C:\\Users\\oldse\\AppData\\Roaming\\npm\\node_modules\\@google\\gemini-cli\\bundle\\gemini.js',
      memory_mb: 30,
      started_at: '2026-04-25T00:00:01+08:00',
    },
  ])

  assert.deepEqual(collapsed.map(item => item.pid), [101, 202])
})

test('collapseGeminiProcessTree filters out version probes', () => {
  const collapsed = collapseGeminiProcessTree([
    {
      pid: 58100,
      parent_pid: 58164,
      command: 'cmd.exe /c gemini --version',
      memory_mb: 6.7,
      started_at: '2026-04-25T11:32:18.1229660+08:00',
    },
    {
      pid: 60060,
      parent_pid: 58100,
      command: '"node" "C:\\Users\\oldse\\AppData\\Roaming\\npm\\node_modules\\@google\\gemini-cli\\bundle\\gemini.js" --version',
      memory_mb: 56.6,
      started_at: '2026-04-25T11:32:18.1359720+08:00',
    },
  ])

  assert.deepEqual(collapsed, [])
})

test('killGeminiCliProcessWindows escalates from graceful to forceful kill', async () => {
  const calls: string[][] = []
  let attempt = 0

  await killGeminiCliProcessWindows(24680, false, async (_file, args) => {
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

test('killGeminiCliProcessWindows exposes detailed taskkill failures', async () => {
  await assert.rejects(
    killGeminiCliProcessWindows(13579, true, async (_file, args) => {
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
      assert.ok(error instanceof GeminiProcessKillError)
      assert.equal(error.pid, 13579)
      assert.equal(error.attempts.length, 2)
      assert.match(error.message, /Failed to kill process 13579/)
      assert.match(error.message, /taskkill \/PID 13579 \/F/)
      assert.match(error.message, /ERROR \/PID 13579 \/F \/T/)
      return true
    },
  )
})
