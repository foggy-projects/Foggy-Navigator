import { execFile } from 'node:child_process'
import { promisify } from 'node:util'

const execFileAsync = promisify(execFile)

type ExecFileLike = (
  file: string,
  args: readonly string[],
  options: {
    windowsHide?: boolean
    maxBuffer?: number
  },
) => Promise<{
  stdout?: string
  stderr?: string
}>

export interface CodexCliProcessInfo {
  pid: number
  command: string
  memory_mb: number
  started_at: string
}

export interface CodexKillAttemptResult {
  command: string
  args: string[]
  exitCode: number | null
  stdout: string
  stderr: string
}

export class CodexProcessKillError extends Error {
  pid: number
  attempts: CodexKillAttemptResult[]

  constructor(pid: number, attempts: CodexKillAttemptResult[]) {
    super(buildWindowsKillErrorMessage(pid, attempts))
    this.name = 'CodexProcessKillError'
    this.pid = pid
    this.attempts = attempts
  }
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

export function buildListProcessesWindowsScript(): string {
  return [
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
    '    $startedAt = ([datetime]$_.CreationDate).ToString("o")',
    '  }',
    '  [pscustomobject]@{',
    '    pid = [int]$_.ProcessId',
    '    command = [string]$_.CommandLine',
    '    memory_mb = if ($_.WorkingSetSize) { [math]::Round(([double]$_.WorkingSetSize / 1MB), 1) } else { 0 }',
    '    started_at = $startedAt',
    '  }',
    '}',
    '$items | ConvertTo-Json -Compress',
  ].join('\n')
}

async function listProcessesWindows(): Promise<CodexCliProcessInfo[]> {
  const script = buildListProcessesWindowsScript()

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

export function buildWindowsKillAttemptArgs(pid: number, force = false): string[][] {
  const variants = force
    ? [
        ['/PID', String(pid), '/F'],
        ['/PID', String(pid), '/F', '/T'],
      ]
    : [
        ['/PID', String(pid)],
        ['/PID', String(pid), '/T'],
        ['/PID', String(pid), '/F'],
        ['/PID', String(pid), '/F', '/T'],
      ]

  const deduped = new Map<string, string[]>()
  for (const args of variants) {
    deduped.set(args.join('\0'), args)
  }
  return Array.from(deduped.values())
}

function formatWindowsKillCommand(args: readonly string[]): string {
  return ['taskkill', ...args].join(' ')
}

function normalizeKillAttemptFailure(args: readonly string[], error: any): CodexKillAttemptResult {
  const exitCode = typeof error?.code === 'number' ? error.code : null
  const stdout = typeof error?.stdout === 'string' ? error.stdout : ''
  const stderr = typeof error?.stderr === 'string'
    ? error.stderr
    : (typeof error?.message === 'string' ? error.message : '')

  return {
    command: formatWindowsKillCommand(args),
    args: [...args],
    exitCode,
    stdout: stdout.trim(),
    stderr: stderr.trim(),
  }
}

export function buildWindowsKillErrorMessage(
  pid: number,
  attempts: readonly CodexKillAttemptResult[],
): string {
  const summary = attempts
    .map(attempt => {
      const detail = attempt.stderr || attempt.stdout || 'no output'
      return `${attempt.command} (exit=${attempt.exitCode ?? 'unknown'}): ${detail}`
    })
    .join(' | ')
  return `Failed to kill process ${pid}. ${summary || 'No taskkill output captured.'}`
}

export async function killCodexCliProcessWindows(
  pid: number,
  force = false,
  executor: ExecFileLike = execFileAsync,
): Promise<void> {
  const attempts: CodexKillAttemptResult[] = []

  for (const args of buildWindowsKillAttemptArgs(pid, force)) {
    try {
      await executor('taskkill', args, {
        windowsHide: true,
        maxBuffer: 1024 * 1024,
      })
      return
    } catch (error: any) {
      attempts.push(normalizeKillAttemptFailure(args, error))
    }
  }

  throw new CodexProcessKillError(pid, attempts)
}

export async function killCodexCliProcess(pid: number, force = false): Promise<void> {
  if (process.platform === 'win32') {
    await killCodexCliProcessWindows(pid, force)
    return
  }

  process.kill(pid, force ? 'SIGKILL' : 'SIGTERM')
}
