import { createHash } from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import type { WorkerEvent } from '../models.js'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
export const DEFAULT_FILE_HINTS_DIR = path.resolve(__dirname, '..', '..', 'logs', 'file-hints')

export type SessionFileHintPathScope = 'inside_cwd' | 'outside_cwd' | 'unknown'
export type SessionFileHintChangeKind = 'add' | 'delete' | 'update' | 'unknown'
export type SessionFileHintSourceTool = 'file_change' | 'command_execution'
export type SessionFileHintConfidence = 'high' | 'low'

export interface SessionFileHintRecord {
  taskId: string
  sessionId: string
  codexThreadId?: string
  providerType: 'codex'
  filePath: string
  cwdRelativePath?: string
  pathScope: SessionFileHintPathScope
  openableInFileBrowser: boolean
  changeKind: SessionFileHintChangeKind
  sourceTool: SessionFileHintSourceTool
  confidence: SessionFileHintConfidence
  toolUseId?: string
  summary?: string
  firstSeenAt: string
  lastSeenAt: string
  seenCount: number
}

export interface SessionFileHintFile {
  filePath: string
  cwdRelativePath?: string
  pathScope: SessionFileHintPathScope
  openableInFileBrowser: boolean
  changeKinds: SessionFileHintChangeKind[]
  sourceTools: SessionFileHintSourceTool[]
  confidence: SessionFileHintConfidence
  toolUseIds: string[]
  taskIds: string[]
  firstSeenAt: string
  lastSeenAt: string
  seenCount: number
}

export interface SessionFileHintsResponse {
  session_id: string
  files: SessionFileHintFile[]
  total: number
  scanned_days: number
  truncated: boolean
}

type RecordOptions = {
  cwd?: string
  now?: Date
  rootDir?: string
}

type QueryOptions = {
  from?: string
  to?: string
  days?: number
  now?: Date
  rootDir?: string
}

const MAX_DEFAULT_SCAN_DAYS = 30
const MAX_EXPLICIT_SCAN_DAYS = 120

const MUTATING_COMMAND_RE =
  /\b(apply_patch|out-file|set-content|add-content|new-item|remove-item|move-item|copy-item|rm|del|erase|mv|move|cp|copy|touch|mkdir|rmdir|tee)\b|sed\s+-i|perl\s+-pi|>{1,2}/i

const REDIRECT_TARGET_RE = />{1,2}\s*(?:"([^"]+)"|'([^']+)'|([^\s;|&]+))/g
const POWERSHELL_PATH_RE = /-(?:literalpath|path|destination|target)\s+(?:"([^"]+)"|'([^']+)'|([^\s,;|]+))/gi
const PATH_TOKEN_RE =
  /(?:"([^"]*(?:[\\/][^"]+|\.[A-Za-z0-9_-]{1,12})[^"]*)"|'([^']*(?:[\\/][^']+|\.[A-Za-z0-9_-]{1,12})[^']*)'|((?:[A-Za-z]:[\\/]|\.{1,2}[\\/]|[A-Za-z0-9_.-]+[\\/])[^"'`\s,;|&<>]+|[A-Za-z0-9_.-]+\.[A-Za-z0-9_-]{1,12}))/g

function dateParts(date: Date): { yyyy: string; mm: string; dd: string } {
  const yyyy = String(date.getFullYear())
  const mm = String(date.getMonth() + 1).padStart(2, '0')
  const dd = String(date.getDate()).padStart(2, '0')
  return { yyyy, mm, dd }
}

export function sessionFileHintFileName(sessionId: string): string {
  if (/^[A-Za-z0-9._=-]+$/.test(sessionId)) {
    return `${sessionId}.jsonl`
  }
  const safePrefix = sessionId.replace(/[^A-Za-z0-9._=-]+/g, '_').replace(/^_+|_+$/g, '').slice(0, 80) || 'session'
  const digest = createHash('sha256').update(sessionId).digest('hex').slice(0, 12)
  return `${safePrefix}-${digest}.jsonl`
}

export function sessionFileHintPath(sessionId: string, date: Date, rootDir = DEFAULT_FILE_HINTS_DIR): string {
  const { yyyy, mm, dd } = dateParts(date)
  return path.join(rootDir, yyyy, mm, dd, sessionFileHintFileName(sessionId))
}

export function extractSessionFileHintRecords(
  event: WorkerEvent,
  options: RecordOptions = {}
): SessionFileHintRecord[] {
  if (event.type !== 'tool_use' || !event.session_id) {
    return []
  }

  const now = options.now ?? new Date()
  const seenAt = now.toISOString()
  const candidates = extractCandidates(event)

  return candidates
    .map(candidate => buildRecord(event, candidate, options.cwd, seenAt))
    .filter((record): record is SessionFileHintRecord => record != null)
}

export async function recordSessionFileHintsForEvent(
  event: WorkerEvent,
  options: RecordOptions = {}
): Promise<number> {
  const records = extractSessionFileHintRecords(event, options)
  if (records.length === 0 || !event.session_id) {
    return 0
  }

  const now = options.now ?? new Date()
  const filePath = sessionFileHintPath(event.session_id, now, options.rootDir)
  await fs.promises.mkdir(path.dirname(filePath), { recursive: true })
  await fs.promises.appendFile(filePath, records.map(record => JSON.stringify(record)).join('\n') + '\n')
  return records.length
}

export function recordSessionFileHintsForEventBestEffort(
  event: WorkerEvent,
  options: RecordOptions = {}
): void {
  void recordSessionFileHintsForEvent(event, options).catch(error => {
    console.warn(`Failed to persist session file hints for task ${event.task_id}:`, error)
  })
}

export async function listSessionFileHints(
  sessionId: string,
  options: QueryOptions = {}
): Promise<SessionFileHintsResponse> {
  const scan = enumerateScanDates(options)
  const records: SessionFileHintRecord[] = []

  for (const date of scan.dates) {
    const filePath = sessionFileHintPath(sessionId, date, options.rootDir)
    if (!fs.existsSync(filePath)) {
      continue
    }
    const content = await fs.promises.readFile(filePath, 'utf8')
    for (const line of content.split(/\r?\n/)) {
      if (!line.trim()) continue
      try {
        const parsed = JSON.parse(line) as SessionFileHintRecord
        if (parsed.sessionId === sessionId && parsed.filePath) {
          records.push(parsed)
        }
      } catch (error) {
        console.warn(`Failed to parse session file hint record: ${filePath}`, error)
      }
    }
  }

  const files = aggregateRecords(records)
  return {
    session_id: sessionId,
    files,
    total: files.length,
    scanned_days: scan.dates.length,
    truncated: scan.truncated,
  }
}

function extractCandidates(event: WorkerEvent): Array<{
  pathText: string
  changeKind: SessionFileHintChangeKind
  sourceTool: SessionFileHintSourceTool
  confidence: SessionFileHintConfidence
  summary?: string
}> {
  if (event.tool === 'file_change') {
    const status = event.input?.status
    if (status === 'failed') {
      return []
    }
    const changes = Array.isArray(event.input?.changes) ? event.input.changes : []
    return changes.flatMap(change => {
      if (!change || typeof change !== 'object') {
        return []
      }
      const item = change as Record<string, unknown>
      const pathText = stringField(item, 'path', 'file', 'filePath', 'filename', 'name')
      if (!pathText) return []
      return [{
        pathText,
        changeKind: normalizeChangeKind(item.kind),
        sourceTool: 'file_change' as const,
        confidence: 'high' as const,
        summary: typeof item.kind === 'string' ? item.kind : undefined,
      }]
    })
  }

  if (event.tool === 'command_execution') {
    const command = typeof event.input?.command === 'string' ? event.input.command : ''
    if (!command || !MUTATING_COMMAND_RE.test(command)) {
      return []
    }
    return extractCommandPaths(command).map(pathText => ({
      pathText,
      changeKind: inferCommandChangeKind(command),
      sourceTool: 'command_execution' as const,
      confidence: 'low' as const,
      summary: undefined,
    }))
  }

  return []
}

function buildRecord(
  event: WorkerEvent,
  candidate: {
    pathText: string
    changeKind: SessionFileHintChangeKind
    sourceTool: SessionFileHintSourceTool
    confidence: SessionFileHintConfidence
    summary?: string
  },
  cwd: string | undefined,
  seenAt: string
): SessionFileHintRecord | null {
  const normalized = normalizeFilePath(candidate.pathText, cwd)
  if (!normalized) {
    return null
  }
  return {
    taskId: event.task_id,
    sessionId: event.session_id!,
    codexThreadId: event.session_id,
    providerType: 'codex',
    filePath: normalized.filePath,
    cwdRelativePath: normalized.cwdRelativePath,
    pathScope: normalized.pathScope,
    openableInFileBrowser: normalized.openableInFileBrowser,
    changeKind: candidate.changeKind,
    sourceTool: candidate.sourceTool,
    confidence: candidate.confidence,
    toolUseId: event.tool_use_id,
    summary: candidate.summary,
    firstSeenAt: seenAt,
    lastSeenAt: seenAt,
    seenCount: 1,
  }
}

function normalizeFilePath(rawPath: string, cwd: string | undefined): {
  filePath: string
  cwdRelativePath?: string
  pathScope: SessionFileHintPathScope
  openableInFileBrowser: boolean
} | null {
  const cleaned = cleanPathToken(rawPath)
  if (!cleaned || cleaned.includes('\0') || cleaned.startsWith('http://') || cleaned.startsWith('https://')) {
    return null
  }

  const cwdNormalized = cwd ? normalizeFsPath(cwd) : undefined
  const isAbs = isAbsolutePath(cleaned)
  const absolutePath = isAbs
    ? normalizeFsPath(cleaned)
    : cwdNormalized
      ? resolveUnderCwd(cwdNormalized, cleaned)
      : normalizeFsPath(cleaned)

  if (!cwdNormalized) {
    return {
      filePath: absolutePath,
      pathScope: 'unknown',
      openableInFileBrowser: false,
    }
  }

  const relative = relativePathIfInside(cwdNormalized, absolutePath)
  if (relative != null) {
    return {
      filePath: absolutePath,
      cwdRelativePath: relative,
      pathScope: 'inside_cwd',
      openableInFileBrowser: true,
    }
  }

  return {
    filePath: absolutePath,
    pathScope: 'outside_cwd',
    openableInFileBrowser: false,
  }
}

function extractCommandPaths(command: string): string[] {
  const paths = new Set<string>()

  collectRegexMatches(command, REDIRECT_TARGET_RE, paths)
  collectRegexMatches(command, POWERSHELL_PATH_RE, paths)
  collectRegexMatches(command, PATH_TOKEN_RE, paths)

  return Array.from(paths)
    .map(cleanPathToken)
    .filter(Boolean)
    .filter(token => !isLikelyCommandName(token))
    .slice(0, 20)
}

function collectRegexMatches(command: string, regex: RegExp, target: Set<string>): void {
  regex.lastIndex = 0
  let match: RegExpExecArray | null
  while ((match = regex.exec(command)) != null) {
    const value = match[1] || match[2] || match[3]
    if (value) target.add(value)
  }
}

function cleanPathToken(value: string): string {
  return value
    .trim()
    .replace(/^['"`]+|['"`]+$/g, '')
    .replace(/[),.;]+$/g, '')
}

function isLikelyCommandName(value: string): boolean {
  const lower = value.toLowerCase()
  return ['rm', 'mv', 'cp', 'copy', 'del', 'move', 'touch', 'mkdir', 'tee'].includes(lower)
}

function stringField(obj: Record<string, unknown>, ...keys: string[]): string | undefined {
  for (const key of keys) {
    const value = obj[key]
    if (typeof value === 'string' && value.trim()) {
      return value
    }
  }
  return undefined
}

function normalizeChangeKind(value: unknown): SessionFileHintChangeKind {
  if (typeof value !== 'string') return 'unknown'
  const normalized = value.toLowerCase()
  if (['add', 'added', 'create', 'created', 'new'].includes(normalized)) return 'add'
  if (['delete', 'deleted', 'remove', 'removed'].includes(normalized)) return 'delete'
  if (['update', 'updated', 'modify', 'modified', 'change', 'changed', 'edit', 'edited'].includes(normalized)) return 'update'
  return 'unknown'
}

function inferCommandChangeKind(command: string): SessionFileHintChangeKind {
  if (/\b(rm|del|erase|remove-item|rmdir)\b/i.test(command)) return 'delete'
  if (/\b(touch|new-item|mkdir)\b/i.test(command)) return 'add'
  return 'update'
}

function isAbsolutePath(value: string): boolean {
  return path.isAbsolute(value) || path.win32.isAbsolute(value) || path.posix.isAbsolute(value)
}

function normalizeFsPath(value: string): string {
  return path.normalize(value).replace(/\\/g, '/')
}

function resolveUnderCwd(cwd: string, child: string): string {
  if (/^[A-Za-z]:\//.test(cwd) || /^[A-Za-z]:[\\/]/.test(child)) {
    return path.win32.resolve(cwd, child).replace(/\\/g, '/')
  }
  if (cwd.startsWith('/')) {
    return path.posix.resolve(cwd, child.replace(/\\/g, '/'))
  }
  return normalizeFsPath(path.resolve(cwd, child))
}

function comparablePath(value: string): string {
  const normalized = normalizeFsPath(value).replace(/\/+$/g, '')
  return /^[A-Za-z]:\//.test(normalized) ? normalized.toLowerCase() : normalized
}

function relativePathIfInside(cwd: string, filePath: string): string | null {
  const cwdComparable = comparablePath(cwd)
  const fileComparable = comparablePath(filePath)
  if (fileComparable === cwdComparable) {
    return ''
  }
  if (!fileComparable.startsWith(`${cwdComparable}/`)) {
    return null
  }
  return normalizeFsPath(filePath).slice(normalizeFsPath(cwd).replace(/\/+$/g, '').length + 1)
}

function enumerateScanDates(options: QueryOptions): { dates: Date[]; truncated: boolean } {
  if (options.from || options.to) {
    const start = parseYmd(options.from) ?? shiftDate(stripTime(options.now ?? new Date()), -(MAX_DEFAULT_SCAN_DAYS - 1))
    const end = parseYmd(options.to) ?? stripTime(options.now ?? new Date())
    const totalDays = countDaysInclusive(start, end)
    return {
      dates: enumerateDateRange(start, end, MAX_EXPLICIT_SCAN_DAYS),
      truncated: totalDays > MAX_EXPLICIT_SCAN_DAYS,
    }
  }

  const days = clampInt(options.days, 1, MAX_DEFAULT_SCAN_DAYS, MAX_DEFAULT_SCAN_DAYS)
  const today = stripTime(options.now ?? new Date())
  return {
    dates: Array.from({ length: days }, (_unused, index) => shiftDate(today, -index)),
    truncated: false,
  }
}

function parseYmd(value: string | undefined): Date | null {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return null
  }
  const [year, month, day] = value.split('-').map(Number)
  const date = new Date(year!, month! - 1, day!)
  if (date.getFullYear() !== year || date.getMonth() !== month! - 1 || date.getDate() !== day) {
    return null
  }
  return stripTime(date)
}

function enumerateDateRange(start: Date, end: Date, maxDays: number): Date[] {
  if (start.getTime() > end.getTime()) {
    return []
  }
  const dates: Date[] = []
  for (let cursor = stripTime(start); cursor.getTime() <= end.getTime() && dates.length < maxDays; cursor = shiftDate(cursor, 1)) {
    dates.push(cursor)
  }
  return dates
}

function countDaysInclusive(start: Date, end: Date): number {
  if (start.getTime() > end.getTime()) {
    return 0
  }
  const msPerDay = 24 * 60 * 60 * 1000
  return Math.floor((stripTime(end).getTime() - stripTime(start).getTime()) / msPerDay) + 1
}

function stripTime(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate())
}

function shiftDate(date: Date, days: number): Date {
  const shifted = new Date(date)
  shifted.setDate(shifted.getDate() + days)
  return shifted
}

function clampInt(value: number | undefined, min: number, max: number, fallback: number): number {
  if (!Number.isInteger(value)) return fallback
  return Math.max(min, Math.min(max, value!))
}

function aggregateRecords(records: SessionFileHintRecord[]): SessionFileHintFile[] {
  const byPath = new Map<string, SessionFileHintFile>()

  for (const record of records) {
    const existing = byPath.get(record.filePath)
    if (!existing) {
      byPath.set(record.filePath, {
        filePath: record.filePath,
        cwdRelativePath: record.cwdRelativePath,
        pathScope: record.pathScope,
        openableInFileBrowser: record.openableInFileBrowser,
        changeKinds: [record.changeKind],
        sourceTools: [record.sourceTool],
        confidence: record.confidence,
        toolUseIds: record.toolUseId ? [record.toolUseId] : [],
        taskIds: [record.taskId],
        firstSeenAt: record.firstSeenAt,
        lastSeenAt: record.lastSeenAt,
        seenCount: record.seenCount,
      })
      continue
    }

    if (!existing.changeKinds.includes(record.changeKind)) {
      existing.changeKinds.push(record.changeKind)
    }
    if (!existing.sourceTools.includes(record.sourceTool)) {
      existing.sourceTools.push(record.sourceTool)
    }
    if (record.toolUseId && !existing.toolUseIds.includes(record.toolUseId)) {
      existing.toolUseIds.push(record.toolUseId)
    }
    if (!existing.taskIds.includes(record.taskId)) {
      existing.taskIds.push(record.taskId)
    }
    if (record.firstSeenAt < existing.firstSeenAt) {
      existing.firstSeenAt = record.firstSeenAt
    }
    if (record.lastSeenAt > existing.lastSeenAt) {
      existing.lastSeenAt = record.lastSeenAt
    }
    existing.seenCount += record.seenCount
    if (record.confidence === 'high') {
      existing.confidence = 'high'
    }
    if (!existing.cwdRelativePath && record.cwdRelativePath) {
      existing.cwdRelativePath = record.cwdRelativePath
    }
    if (!existing.openableInFileBrowser && record.openableInFileBrowser) {
      existing.pathScope = record.pathScope
      existing.openableInFileBrowser = true
    }
  }

  return Array.from(byPath.values())
    .sort((a, b) => b.lastSeenAt.localeCompare(a.lastSeenAt) || a.filePath.localeCompare(b.filePath))
}
