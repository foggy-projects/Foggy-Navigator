import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import test from 'node:test'
import { promisify } from 'node:util'
import { buildListProcessesWindowsScript } from '../src/codex/processes.ts'

const execFileAsync = promisify(execFile)

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
