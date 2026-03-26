import { execFile } from 'node:child_process'
import { promisify } from 'node:util'

const execFileAsync = promisify(execFile)

export interface CodexCliProcessInfo {
  pid: number
  command: string
  memory_mb: number
  started_at: string
}

function extractJsonArray(raw: string): string {
  const trimmed = raw.trim()
  if (!trimmed) return '[]'
  const firstBracket = trimmed.indexOf('[')
  if (firstBracket >= 0) {
    return trimmed.slice(firstBracket)
  }
  return trimmed
}

async function listProcessesWindows(): Promise<CodexCliProcessInfo[]> {
  const script = [
    '$ErrorActionPreference = "Stop"',
    '$items = Get-CimInstance Win32_Process | Where-Object {',
    '  $_.CommandLine -and $_.CommandLine -match "--experimental-json" -and (',
    '    $_.Name -match "^codex(\\.exe)?$" -or',
    '    $_.ExecutablePath -match "codex(\\.exe)?$" -or',
    '    $_.CommandLine -match "\\\\codex(?:\\.exe)?(?:\\s|$)"',
    '  )',
    '} | ForEach-Object {',
    '  $startedAt = $null',
    '  if ($_.CreationDate) {',
    '    $startedAt = [Management.ManagementDateTimeConverter]::ToDateTime($_.CreationDate).ToString("o")',
    '  }',
    '  [pscustomobject]@{',
    '    pid = [int]$_.ProcessId',
    '    command = [string]$_.CommandLine',
    '    memory_mb = if ($_.WorkingSetSize) { [math]::Round(([double]$_.WorkingSetSize / 1MB), 1) } else { 0 }',
    '    started_at = $startedAt',
    '  }',
    '}',
    '$items | ConvertTo-Json -Compress',
  ].join('; ')

  const { stdout } = await execFileAsync('powershell', ['-NoProfile', '-Command', script], {
    windowsHide: true,
    maxBuffer: 1024 * 1024 * 4,
  })

  const normalized = extractJsonArray(stdout)
  if (normalized === 'null') {
    return []
  }
  const parsed = JSON.parse(normalized)
  const entries = Array.isArray(parsed) ? parsed : [parsed]
  return entries.map((entry: any) => ({
    pid: Number(entry.pid),
    command: String(entry.command || ''),
    memory_mb: Number(entry.memory_mb || 0),
    started_at: String(entry.started_at || ''),
  }))
}

function parsePsLine(line: string): CodexCliProcessInfo | null {
  const match = line.match(/^\s*(\d+)\s+(\d+)\s+([A-Z][a-z]{2}\s+[A-Z][a-z]{2}\s+\d+\s+\d\d:\d\d:\d\d\s+\d{4})\s+(.+)$/)
  if (!match) {
    return null
  }
  const [, pid, rssKb, startedAt, command] = match
  if (!command.includes('--experimental-json')) {
    return null
  }
  if (!/(^|\s|\/)codex(\s|$)/.test(command)) {
    return null
  }
  return {
    pid: Number(pid),
    command,
    memory_mb: Math.round((Number(rssKb) / 1024) * 10) / 10,
    started_at: startedAt,
  }
}

async function listProcessesPosix(): Promise<CodexCliProcessInfo[]> {
  const { stdout } = await execFileAsync('ps', ['-axo', 'pid=,rss=,lstart=,command='], {
    maxBuffer: 1024 * 1024 * 4,
  })
  return stdout
    .split(/\r?\n/)
    .map(parsePsLine)
    .filter((entry): entry is CodexCliProcessInfo => entry !== null)
}

export async function listCodexCliProcesses(): Promise<CodexCliProcessInfo[]> {
  if (process.platform === 'win32') {
    return await listProcessesWindows()
  }
  return await listProcessesPosix()
}

export async function snapshotCodexCliPids(): Promise<Set<number>> {
  const processes = await listCodexCliProcesses()
  return new Set(processes.map(processInfo => processInfo.pid))
}

export async function detectSpawnedCodexPid(existingPids: ReadonlySet<number>): Promise<number | undefined> {
  for (let attempt = 0; attempt < 10; attempt++) {
    const processes = await listCodexCliProcesses()
    const newest = processes
      .filter(processInfo => !existingPids.has(processInfo.pid))
      .sort((a, b) => b.pid - a.pid)[0]
    if (newest) {
      return newest.pid
    }
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  return undefined
}

export async function killCodexCliProcess(pid: number, force = false): Promise<void> {
  if (process.platform === 'win32') {
    const args = ['/PID', String(pid)]
    if (force) {
      args.push('/F')
    }
    await execFileAsync('taskkill', args, {
      windowsHide: true,
      maxBuffer: 1024 * 1024,
    })
    return
  }

  process.kill(pid, force ? 'SIGKILL' : 'SIGTERM')
}
