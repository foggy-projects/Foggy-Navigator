import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { taskRegistry } from './cli-wrapper.js'

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

export interface GeminiCliProcessInfo {
  pid: number
  parent_pid?: number
  command: string
  memory_mb: number
  started_at: string
}

export interface GeminiKillAttemptResult {
  command: string
  args: string[]
  exitCode: number | null
  stdout: string
  stderr: string
}

export class GeminiProcessKillError extends Error {
  pid: number
  attempts: GeminiKillAttemptResult[]

  constructor(pid: number, attempts: GeminiKillAttemptResult[]) {
    super(buildWindowsKillErrorMessage(pid, attempts))
    this.name = 'GeminiProcessKillError'
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
    '  $cmd = [string]$_.CommandLine',
    '  $cmd -and -not ($cmd -match "powershell\\s+-NoProfile\\s+-Command" -and $cmd -match "Get-CimInstance Win32_Process") -and (',
    '    $cmd -match "(^|\\\\|\\s)gemini(?:\\.cmd|\\.exe)?(\\s|$)" -or',
    '    $cmd -match "@google\\\\gemini-cli" -or',
    '    $cmd -match "bundle\\\\gemini\\.js" -or',
    '    $cmd -match "FOGGY_GEMINI_TASK_ID"',
    '  )',
    '} | ForEach-Object {',
    '  $startedAt = $null',
    '  if ($_.CreationDate) {',
    '    $startedAt = ([datetime]$_.CreationDate).ToString("o")',
    '  }',
  '  [pscustomobject]@{',
  '    pid = [int]$_.ProcessId',
  '    parent_pid = if ($_.ParentProcessId) { [int]$_.ParentProcessId } else { $null }',
  '    command = [string]$_.CommandLine',
  '    memory_mb = if ($_.WorkingSetSize) { [math]::Round(([double]$_.WorkingSetSize / 1MB), 1) } else { 0 }',
  '    started_at = $startedAt',
    '  }',
    '}',
    '$items | ConvertTo-Json -Compress',
  ].join('\n')
}

async function listProcessesWindows(): Promise<GeminiCliProcessInfo[]> {
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
    parent_pid: entry.parent_pid != null ? Number(entry.parent_pid) : undefined,
    command: String(entry.command || ''),
    memory_mb: Number(entry.memory_mb || 0),
    started_at: String(entry.started_at || ''),
  }))
}

function parsePsLine(line: string): GeminiCliProcessInfo | null {
  const match = line.match(/^\s*(\d+)\s+(\d+)\s+(\d+)\s+([A-Z][a-z]{2}\s+[A-Z][a-z]{2}\s+\d+\s+\d\d:\d\d:\d\d\s+\d{4})\s+(.+)$/)
  if (!match) {
    return null
  }
  const [, pid, parentPid, rssKb, startedAt, command] = match
  if (!command.includes('gemini') && !command.includes('@google/gemini-cli') && !command.includes('bundle/gemini.js')) {
    return null
  }
  return {
    pid: Number(pid),
    parent_pid: Number(parentPid),
    command,
    memory_mb: Math.round((Number(rssKb) / 1024) * 10) / 10,
    started_at: startedAt,
  }
}

async function listProcessesPosix(): Promise<GeminiCliProcessInfo[]> {
  const { stdout } = await execFileAsync('ps', ['-axo', 'pid=,ppid=,rss=,lstart=,command='], {
    maxBuffer: 1024 * 1024 * 4,
  })
  return stdout
    .split(/\r?\n/)
    .map(parsePsLine)
    .filter((entry): entry is GeminiCliProcessInfo => entry !== null)
}

function normalizeGeminiCommandTarget(command: string): string {
  const match = command.match(/((?:[A-Za-z]:)?[^"\s]*gemini\.js)/i)
  if (match?.[1]) {
    return match[1].replaceAll('\\', '/').toLowerCase()
  }
  if (command.includes('@google\\gemini-cli') || command.includes('@google/gemini-cli')) {
    return '@google/gemini-cli'
  }
  if (/(^|\s|\\|\/)gemini(?:\.cmd|\.exe)?(\s|$)/i.test(command)) {
    return 'gemini-cli'
  }
  return command.trim().replaceAll('\\', '/').toLowerCase()
}

function hasGeminiExecutionFlags(command: string): boolean {
  return /\s-(?:p|m|r)\b|\s--(?:prompt|model|resume|output-format|yolo|approval-mode|skip-trust)\b/i.test(command)
}

function isGeminiVersionProbeCommand(command: string): boolean {
  return /\s--version(?:\s|$)/i.test(command.trim())
}

function isLikelyGeminiWrapperProcess(
  parent: GeminiCliProcessInfo,
  child: GeminiCliProcessInfo,
): boolean {
  if (child.parent_pid !== parent.pid) {
    return false
  }
  if (normalizeGeminiCommandTarget(parent.command) !== normalizeGeminiCommandTarget(child.command)) {
    return false
  }
  if (hasGeminiExecutionFlags(parent.command)) {
    return false
  }
  return child.command.length >= parent.command.length
}

export function collapseGeminiProcessTree(processes: readonly GeminiCliProcessInfo[]): GeminiCliProcessInfo[] {
  const filteredProcesses = processes.filter(processInfo => !isGeminiVersionProbeCommand(processInfo.command))
  const childrenByParent = new Map<number, GeminiCliProcessInfo[]>()
  for (const processInfo of filteredProcesses) {
    if (processInfo.parent_pid == null) continue
    const siblings = childrenByParent.get(processInfo.parent_pid) ?? []
    siblings.push(processInfo)
    childrenByParent.set(processInfo.parent_pid, siblings)
  }

  return filteredProcesses.filter(processInfo => {
    const children = childrenByParent.get(processInfo.pid) ?? []
    return !children.some(child => isLikelyGeminiWrapperProcess(processInfo, child))
  })
}

export async function listGeminiCliProcesses(): Promise<GeminiCliProcessInfo[]> {
  const processes = process.platform === 'win32'
    ? await listProcessesWindows()
    : await listProcessesPosix()
  return collapseGeminiProcessTree(processes)
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

function normalizeKillAttemptFailure(args: readonly string[], error: any): GeminiKillAttemptResult {
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
  attempts: readonly GeminiKillAttemptResult[],
): string {
  const summary = attempts
    .map(attempt => {
      const detail = attempt.stderr || attempt.stdout || 'no output'
      return `${attempt.command} (exit=${attempt.exitCode ?? 'unknown'}): ${detail}`
    })
    .join(' | ')
  return `Failed to kill process ${pid}. ${summary || 'No taskkill output captured.'}`
}

export async function killGeminiCliProcessWindows(
  pid: number,
  force = false,
  executor: ExecFileLike = execFileAsync,
): Promise<void> {
  const attempts: GeminiKillAttemptResult[] = []

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

  throw new GeminiProcessKillError(pid, attempts)
}

export async function killGeminiCliProcess(pid: number, force = false): Promise<void> {
  if (process.platform === 'win32') {
    await killGeminiCliProcessWindows(pid, force)
    return
  }
  process.kill(pid, force ? 'SIGKILL' : 'SIGTERM')
}

function findTaskByPid(pid: number) {
  return Array.from(taskRegistry.values()).find(entry => entry.pid === pid)
}

export function extractGeminiSessionId(command: string): string | undefined {
  const match = command.match(/(?:-r|--resume)\s+([^\s"]+)/)
  return match?.[1]
}

export function toGeminiProcessPayload(processInfo: GeminiCliProcessInfo) {
  const matchedEntry = findTaskByPid(processInfo.pid)
  return {
    ...processInfo,
    process_type: 'gemini' as const,
    is_orphan: !matchedEntry,
    gemini_session_id: matchedEntry?.sessionId || extractGeminiSessionId(processInfo.command),
    model: matchedEntry?.model,
  }
}
