import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import type {
  EncryptedPayload,
  StoredTaskRecord,
  TaskOutcome,
  TaskPhase,
  TaskRequest,
} from '../models.js'
import { readJsonlAndRepair, syncParentDirectory } from './jsonl-durability.js'

const TRANSITIONS: Readonly<Record<TaskPhase, ReadonlySet<TaskPhase>>> = {
  accepted: new Set(['starting', 'terminal']),
  starting: new Set(['committed', 'terminal']),
  committed: new Set(['running', 'terminal']),
  running: new Set(['terminal']),
  terminal: new Set(),
}

export class IdempotencyConflictError extends Error {
  constructor(readonly taskId: string) {
    super(`Idempotency key already exists with a different request: ${taskId}`)
    this.name = 'IdempotencyConflictError'
  }
}

export class TaskStateConflictError extends Error {
  constructor(taskId: string, from: TaskPhase, to: TaskPhase) {
    super(`Invalid task state transition for ${taskId}: ${from} -> ${to}`)
    this.name = 'TaskStateConflictError'
  }
}

type TaskStoreOptions = {
  stateDir: string
  encryptionKey: Buffer
  now?: () => Date
}

type TaskJournalRecord = StoredTaskRecord & {
  request_payload_persisted?: true
}

export class TaskStore {
  private readonly stateDir: string
  private readonly encryptionKey: Buffer
  private readonly now: () => Date
  private readonly records = new Map<string, StoredTaskRecord>()
  private readonly locks = new Map<string, Promise<void>>()

  constructor(options: TaskStoreOptions) {
    if (options.encryptionKey.length !== 32) throw new Error('TaskStore encryptionKey must be 32 bytes')
    this.stateDir = path.join(options.stateDir, 'tasks')
    this.encryptionKey = options.encryptionKey
    this.now = options.now || (() => new Date())
  }

  async initialize(): Promise<void> {
    await fs.mkdir(this.stateDir, { recursive: true })
    const entries = await fs.readdir(this.stateDir, { withFileTypes: true })
    for (const entry of entries) {
      if (!entry.isFile() || !entry.name.endsWith('.jsonl')) continue
      const record = await this.readLastRecord(path.join(this.stateDir, entry.name))
      if (record) this.remember(record)
    }
  }

  async accept(taskId: string, request: TaskRequest): Promise<{ record: StoredTaskRecord; created: boolean }> {
    return this.withLock(taskId, async () => {
      const requestHash = hashCanonical(request)
      const existing = this.records.get(taskId) || await this.loadByTaskId(taskId)
      if (existing) {
        if (existing.request_hash !== requestHash) throw new IdempotencyConflictError(taskId)
        return { record: clone(existing), created: false }
      }

      const timestamp = this.now().toISOString()
      const record: StoredTaskRecord = {
        schema_version: 1,
        task_id: taskId,
        request_hash: requestHash,
        request_payload: encryptRequest(request, this.encryptionKey),
        status: 'accepted',
        model: request.model,
        created_at: timestamp,
        updated_at: timestamp,
      }
      const file = this.filePath(taskId)
      try {
        const handle = await fs.open(file, 'wx', 0o600)
        try {
          await handle.writeFile(`${JSON.stringify(record)}\n`, 'utf8')
          await handle.sync()
        } finally {
          await handle.close()
        }
        await syncParentDirectory(file)
        await syncParentDirectory(path.dirname(file))
      } catch (error) {
        if (!isNodeError(error, 'EEXIST')) throw error
        const raced = await this.readLastRecord(file)
        if (!raced) throw new Error(`Task acceptance journal is empty: ${taskId}`)
        this.remember(raced)
        if (raced.request_hash !== requestHash) throw new IdempotencyConflictError(taskId)
        return { record: clone(this.required(taskId)), created: false }
      }
      this.remember(record)
      return { record: clone(record), created: true }
    })
  }

  get(taskId: string): StoredTaskRecord | undefined {
    const record = this.records.get(taskId)
    return record ? clone(record) : undefined
  }

  list(): StoredTaskRecord[] {
    return [...this.records.values()].map(clone)
  }

  getRequest(taskId: string): TaskRequest {
    const record = this.records.get(taskId)
    if (!record) throw new Error(`Task not found: ${taskId}`)
    if (!record.request_payload) throw new Error(`Task request payload has been tombstoned: ${taskId}`)
    return decryptRequest(record.request_payload, this.encryptionKey)
  }

  verifyEncryptionKey(): void {
    for (const record of this.records.values()) {
      if (record.request_payload) decryptRequest(record.request_payload, this.encryptionKey)
    }
  }

  async tombstoneTerminal(taskId: string): Promise<StoredTaskRecord | undefined> {
    return this.withLock(taskId, async () => {
      const current = this.records.get(taskId)
      if (!current || current.status !== 'terminal') return undefined
      if (current.tombstoned_at) return clone(current)
      const timestamp = this.now().toISOString()
      const tombstone: StoredTaskRecord = compact({
        schema_version: 1,
        task_id: current.task_id,
        request_hash: current.request_hash,
        status: 'terminal',
        outcome: current.outcome,
        error_code: current.error_code,
        created_at: current.created_at,
        updated_at: timestamp,
        terminal_at: current.terminal_at || timestamp,
        abort_requested_at: current.abort_requested_at,
        tombstoned_at: timestamp,
      }) as StoredTaskRecord
      await this.replaceJournal(tombstone)
      this.remember(tombstone)
      return clone(tombstone)
    })
  }

  async patch(taskId: string, patch: Partial<Pick<StoredTaskRecord,
    'thread_id' | 'turn_id' | 'app_server_instance_id' | 'app_server_lane_key' | 'model' | 'reasoning_effort' | 'recovery_required' | 'abort_requested_at'>>): Promise<StoredTaskRecord> {
    return this.withLock(taskId, async () => {
      const current = this.required(taskId)
      const next = compact({ ...current, ...patch, updated_at: this.now().toISOString() }) as StoredTaskRecord
      await this.append(next)
      return clone(this.required(taskId))
    })
  }

  async requestAbort(taskId: string): Promise<StoredTaskRecord> {
    return this.withLock(taskId, async () => {
      const current = this.required(taskId)
      if (current.status === 'terminal' || current.abort_requested_at) return clone(current)
      const timestamp = this.now().toISOString()
      const next = compact({
        ...current,
        abort_requested_at: timestamp,
        updated_at: timestamp,
      }) as StoredTaskRecord
      await this.append(next)
      return clone(next)
    })
  }

  async transition(
    taskId: string,
    status: TaskPhase,
    patch: Partial<Pick<StoredTaskRecord,
      'thread_id' | 'turn_id' | 'app_server_instance_id' | 'app_server_lane_key' | 'model' | 'reasoning_effort' | 'error_code' | 'recovery_required'>> & {
        outcome?: TaskOutcome
      } = {}
  ): Promise<StoredTaskRecord> {
    return this.withLock(taskId, async () => {
      const current = this.required(taskId)
      if (current.status === 'terminal') {
        if (status !== 'terminal' || terminalPatchConflicts(current, patch)) {
          throw new TaskStateConflictError(taskId, current.status, status)
        }
        return clone(current)
      }
      if (current.status !== status && !TRANSITIONS[current.status].has(status)) {
        throw new TaskStateConflictError(taskId, current.status, status)
      }
      const timestamp = this.now().toISOString()
      const next = compact({
        ...current,
        ...patch,
        status,
        updated_at: timestamp,
        terminal_at: status === 'terminal' ? timestamp : current.terminal_at,
      }) as StoredTaskRecord
      if (status !== 'terminal' && patch.outcome !== undefined) {
        throw new Error('Task outcome is only valid for terminal tasks')
      }
      await this.append(next)
      return clone(this.required(taskId))
    })
  }

  private required(taskId: string): StoredTaskRecord {
    const record = this.records.get(taskId)
    if (!record) throw new Error(`Task not found: ${taskId}`)
    return record
  }

  private async append(record: StoredTaskRecord): Promise<void> {
    const persisted = toJournalSnapshot(record)
    const handle = await fs.open(this.filePath(record.task_id), 'a', 0o600)
    try {
      await handle.writeFile(`${JSON.stringify(persisted)}\n`, 'utf8')
      await handle.sync()
    } finally {
      await handle.close()
    }
    this.remember(record)
  }

  private async loadByTaskId(taskId: string): Promise<StoredTaskRecord | undefined> {
    const record = await this.readLastRecord(this.filePath(taskId))
    if (!record) return undefined
    this.remember(record)
    return this.required(taskId)
  }

  private async readLastRecord(file: string): Promise<StoredTaskRecord | undefined> {
    try {
      const records = readJsonlAndRepair(file, isTaskJournalRecord)
      const latest = records.at(-1)
      if (!latest) return undefined
      const normalized = withoutJournalMarker(latest)
      if (normalized.request_payload || normalized.tombstoned_at) return normalized
      const requestPayload = records.find(record => record.request_payload)?.request_payload
      if (!requestPayload) throw new Error(`Task request payload is missing from journal: ${normalized.task_id}`)
      return { ...normalized, request_payload: requestPayload }
    } catch (error) {
      if (isNodeError(error, 'ENOENT')) return undefined
      throw error
    }
  }

  private filePath(taskId: string): string {
    return path.join(this.stateDir, `${crypto.createHash('sha256').update(taskId).digest('hex')}.jsonl`)
  }

  private async replaceJournal(record: StoredTaskRecord): Promise<void> {
    const file = this.filePath(record.task_id)
    const temporary = `${file}.${process.pid}.${crypto.randomUUID()}.tmp`
    const handle = await fs.open(temporary, 'wx', 0o600)
    try {
      await handle.writeFile(`${JSON.stringify(record)}\n`, 'utf8')
      await handle.sync()
    } finally {
      await handle.close()
    }
    try {
      await fs.rename(temporary, file)
      await syncParentDirectory(file)
    } catch (error) {
      await fs.rm(temporary, { force: true })
      throw error
    }
  }

  private remember(record: StoredTaskRecord): void {
    this.records.set(record.task_id, compactResidentRecord(record))
  }

  private async withLock<T>(taskId: string, action: () => Promise<T>): Promise<T> {
    const previous = this.locks.get(taskId) || Promise.resolve()
    let release!: () => void
    const current = new Promise<void>(resolve => { release = resolve })
    const queued = previous.catch(() => undefined).then(() => current)
    this.locks.set(taskId, queued)
    await previous.catch(() => undefined)
    try {
      return await action()
    } finally {
      release()
      if (this.locks.get(taskId) === queued) this.locks.delete(taskId)
    }
  }
}

export function hashCanonical(value: unknown): string {
  return crypto.createHash('sha256').update(canonicalJson(value)).digest('hex')
}

function canonicalJson(value: unknown): string {
  if (value === undefined) return '"$undefined"'
  if (value === null || typeof value !== 'object') return JSON.stringify(value)
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`
  const record = value as Record<string, unknown>
  return `{${Object.keys(record).sort().map(key => `${JSON.stringify(key)}:${canonicalJson(record[key])}`).join(',')}}`
}

function encryptRequest(request: TaskRequest, key: Buffer): EncryptedPayload {
  const iv = crypto.randomBytes(12)
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv)
  const ciphertext = Buffer.concat([cipher.update(JSON.stringify(request), 'utf8'), cipher.final()])
  return {
    algorithm: 'aes-256-gcm',
    iv: iv.toString('base64'),
    auth_tag: cipher.getAuthTag().toString('base64'),
    ciphertext: ciphertext.toString('base64'),
  }
}

function decryptRequest(payload: EncryptedPayload, key: Buffer): TaskRequest {
  if (payload.algorithm !== 'aes-256-gcm') throw new Error('Unsupported task request encryption')
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, Buffer.from(payload.iv, 'base64'))
  decipher.setAuthTag(Buffer.from(payload.auth_tag, 'base64'))
  const plaintext = Buffer.concat([
    decipher.update(Buffer.from(payload.ciphertext, 'base64')),
    decipher.final(),
  ]).toString('utf8')
  return JSON.parse(plaintext) as TaskRequest
}

function clone<T>(value: T): T {
  return structuredClone(value)
}

function compactResidentRecord(record: StoredTaskRecord): StoredTaskRecord {
  if (record.status !== 'terminal' || !record.request_payload) return record
  const { request_payload: _requestPayload, ...summary } = record
  return summary
}

function toJournalSnapshot(record: StoredTaskRecord): TaskJournalRecord {
  if (record.tombstoned_at) return record
  const { request_payload: _requestPayload, ...snapshot } = record
  return { ...snapshot, request_payload_persisted: true }
}

function withoutJournalMarker(record: TaskJournalRecord): StoredTaskRecord {
  const { request_payload_persisted: _persisted, ...snapshot } = record
  return snapshot
}

function compact<T extends Record<string, unknown>>(value: T): T {
  return Object.fromEntries(Object.entries(value).filter(([, item]) => item !== undefined)) as T
}

function terminalPatchConflicts(
  current: StoredTaskRecord,
  patch: Partial<StoredTaskRecord>,
): boolean {
  const immutableFields: Array<keyof StoredTaskRecord> = [
    'outcome',
    'error_code',
    'thread_id',
    'turn_id',
    'app_server_instance_id',
    'app_server_lane_key',
    'model',
    'reasoning_effort',
    'recovery_required',
    'abort_requested_at',
  ]
  return immutableFields.some(field => (
    Object.prototype.hasOwnProperty.call(patch, field)
    && patch[field] !== current[field]
  ))
}

function isNodeError(error: unknown, code: string): error is NodeJS.ErrnoException {
  return error instanceof Error && (error as NodeJS.ErrnoException).code === code
}

function isTaskJournalRecord(value: unknown): value is TaskJournalRecord {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const record = value as Partial<TaskJournalRecord>
  return record.schema_version === 1
    && typeof record.task_id === 'string'
    && Boolean(record.task_id)
    && typeof record.request_hash === 'string'
    && Boolean(record.request_hash)
    && Boolean(record.request_payload || record.tombstoned_at || record.request_payload_persisted === true)
}
