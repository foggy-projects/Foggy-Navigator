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

// This is deliberately stricter than a generally valid ISO-8601 instant.
// A signed manual-kill operation needs one byte-for-byte representation across
// the Worker, Java control plane, and a later fresh process scan.
const CANONICAL_PROCESS_STARTED_AT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/

/**
 * Normalizes an OS-reported process start time to the canonical UTC instant
 * exposed by `/api/v1/processes`: `YYYY-MM-DDTHH:mm:ss.SSSZ`.
 */
export function canonicalizeCodexCliProcessStartedAt(value: string): string | undefined {
  if (!value.trim()) return undefined
  const timestamp = Date.parse(value)
  if (!Number.isFinite(timestamp)) return undefined
  const canonical = new Date(timestamp).toISOString()
  return CANONICAL_PROCESS_STARTED_AT.test(canonical) ? canonical : undefined
}

/**
 * Returns the language-neutral process identity signed for an authorized
 * manual kill.  Do not normalize here: a fresh scan must already expose the
 * exact canonical timestamp that the control plane observed.
 */
export function codexCliProcessIdentity(
  pid: number,
  startedAt: string,
): string | undefined {
  if (!Number.isSafeInteger(pid) || pid <= 0 || !CANONICAL_PROCESS_STARTED_AT.test(startedAt)) {
    return undefined
  }
  const timestamp = Date.parse(startedAt)
  if (!Number.isFinite(timestamp) || new Date(timestamp).toISOString() !== startedAt) {
    return undefined
  }
  return `codex-cli:${pid}:${startedAt}`
}

export function isCanonicalCodexCliProcessIdentity(
  value: string,
  expectedPid?: number,
): boolean {
  const match = value.match(/^codex-cli:([1-9]\d*):(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z)$/)
  if (!match) return false
  const pid = Number(match[1])
  return Number.isSafeInteger(pid)
    && (expectedPid === undefined || pid === expectedPid)
    && codexCliProcessIdentity(pid, match[2]) === value
}

export function extractResumedThreadId(command: string): string | undefined {
  const match = command.match(/\bresume\s+(?:--[^\s]+\s+)*["']?([^\s"']+)/)
  return match?.[1]
}

export function findCodexCliProcessForThread(
  threadId: string,
  processes: readonly CodexCliProcessInfo[],
  taskEntries: readonly { threadId?: string; pid?: number }[] = [],
): CodexCliProcessInfo | undefined {
  const normalized = threadId.trim()
  if (!normalized) return undefined

  const knownPids = new Set(
    taskEntries
      .filter(entry => entry.threadId === normalized && entry.pid !== undefined)
      .map(entry => entry.pid as number),
  )
  return processes.find(processInfo => (
    knownPids.has(processInfo.pid)
    || extractResumedThreadId(processInfo.command) === normalized
  ))
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
    '    $startedAt = ([datetime]$_.CreationDate).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss.fffZ")',
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
    started_at: canonicalizeCodexCliProcessStartedAt(String(entry.started_at || '')) ?? '',
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
    started_at: canonicalizeCodexCliProcessStartedAt(startedAt) ?? '',
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
