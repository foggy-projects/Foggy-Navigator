import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { syncParentDirectory } from './jsonl-durability.js'

const IDENTITY_FILE = '.codex-store-identity.json'
const LEASE_FILE = '.codex-store-writer-lease.json'
const RECOVERY_LOCK = '.codex-store-writer-lease-recovery'
const IDENTIFIER_PATTERN = /^[A-Za-z0-9._-]{1,128}$/

type StateIdentityRecord = {
  schema_version: 1
  instance_id: string
  created_at: string
}

type StateLeaseRecord = {
  schema_version: 1
  hostname: string
  pid: number
  nonce: string
  acquired_at: string
}

type LeaseOptions = {
  hostname?: string
  pid?: number
  now?: () => Date
  isProcessAlive?: (pid: number) => boolean
}

export class StateStoreSafetyError extends Error {
  constructor(readonly code: string) {
    super(code)
    this.name = 'StateStoreSafetyError'
  }
}

export class StateWriterLease {
  private released = false

  constructor(
    private readonly leaseFile: string,
    private readonly owner: StateLeaseRecord,
  ) {}

  async release(): Promise<void> {
    if (this.released) return
    this.released = true
    let current: StateLeaseRecord
    try {
      current = await readLease(this.leaseFile)
    } catch (error) {
      if (isNodeError(error, 'ENOENT')) return
      throw error
    }
    if (current.nonce !== this.owner.nonce) {
      throw new StateStoreSafetyError('CODEX_STATE_WRITER_LEASE_OWNERSHIP_LOST')
    }
    await fs.unlink(this.leaseFile)
    await syncParentDirectory(this.leaseFile)
  }
}

/**
 * Resolves the state-store generation identity. Existing pre-marker journals keep
 * their legacy identity once; a genuinely new store always gets a random identity.
 */
export async function initializeStateIdentity(
  stateDir: string,
  legacyInstanceId: string,
  now: () => Date = () => new Date(),
): Promise<string> {
  await fs.mkdir(stateDir, { recursive: true })
  const marker = path.join(stateDir, IDENTITY_FILE)
  try {
    return (await readIdentity(marker)).instance_id
  } catch (error) {
    if (!isNodeError(error, 'ENOENT')) throw error
  }

  const hasLegacyJournals = await containsJournal(stateDir)
  const instanceId = hasLegacyJournals
    ? validateIdentifier(legacyInstanceId, 'CODEX_STATE_LEGACY_INSTANCE_ID_INVALID')
    : `codex-store-${crypto.randomUUID()}`
  const record: StateIdentityRecord = {
    schema_version: 1,
    instance_id: instanceId,
    created_at: now().toISOString(),
  }
  const created = await createJsonFileExclusive(marker, record)
  return created ? instanceId : (await readIdentity(marker)).instance_id
}

/** Acquires the single local JSONL writer lease for a state directory. */
export async function acquireStateWriterLease(
  stateDir: string,
  options: LeaseOptions = {},
): Promise<StateWriterLease> {
  await fs.mkdir(stateDir, { recursive: true })
  const leaseFile = path.join(stateDir, LEASE_FILE)
  const hostname = options.hostname || os.hostname()
  const pid = options.pid || process.pid
  const isProcessAlive = options.isProcessAlive || defaultProcessAlive
  const now = options.now || (() => new Date())
  const owner: StateLeaseRecord = {
    schema_version: 1,
    hostname,
    pid,
    nonce: crypto.randomUUID(),
    acquired_at: now().toISOString(),
  }

  if (await createJsonFileExclusive(leaseFile, owner)) {
    return new StateWriterLease(leaseFile, owner)
  }

  const existing = await readLease(leaseFile)
  if (existing.hostname !== hostname) {
    throw new StateStoreSafetyError('CODEX_STATE_WRITER_LEASE_CROSS_HOST')
  }
  if (isProcessAlive(existing.pid)) {
    throw new StateStoreSafetyError('CODEX_STATE_WRITER_LEASE_ACTIVE')
  }

  const recoveryLock = path.join(stateDir, RECOVERY_LOCK)
  try {
    await fs.mkdir(recoveryLock)
  } catch (error) {
    if (isNodeError(error, 'EEXIST')) {
      throw new StateStoreSafetyError('CODEX_STATE_WRITER_LEASE_RECOVERY_IN_PROGRESS')
    }
    throw error
  }

  try {
    const rechecked = await readLease(leaseFile)
    if (rechecked.hostname !== hostname) {
      throw new StateStoreSafetyError('CODEX_STATE_WRITER_LEASE_CROSS_HOST')
    }
    if (isProcessAlive(rechecked.pid)) {
      throw new StateStoreSafetyError('CODEX_STATE_WRITER_LEASE_ACTIVE')
    }
    await fs.unlink(leaseFile)
    await syncParentDirectory(leaseFile)
    if (!await createJsonFileExclusive(leaseFile, owner)) {
      throw new StateStoreSafetyError('CODEX_STATE_WRITER_LEASE_RACE')
    }
    return new StateWriterLease(leaseFile, owner)
  } finally {
    try {
      await fs.rmdir(recoveryLock)
    } catch (error) {
      if (!isNodeError(error, 'ENOENT')) throw error
    }
    await syncParentDirectory(recoveryLock)
  }
}

async function containsJournal(stateDir: string): Promise<boolean> {
  for (const directory of ['tasks', 'events']) {
    try {
      const entries = await fs.readdir(path.join(stateDir, directory), { withFileTypes: true })
      if (entries.some(entry => entry.isFile() && entry.name.endsWith('.jsonl'))) return true
    } catch (error) {
      if (!isNodeError(error, 'ENOENT')) throw error
    }
  }
  return false
}

async function readIdentity(file: string): Promise<StateIdentityRecord> {
  const value = await readStrictJson(file, 'CODEX_STATE_IDENTITY_CORRUPT')
  if (!isObject(value)
      || value.schema_version !== 1
      || typeof value.instance_id !== 'string'
      || !IDENTIFIER_PATTERN.test(value.instance_id)
      || typeof value.created_at !== 'string'
      || !Number.isFinite(Date.parse(value.created_at))) {
    throw new StateStoreSafetyError('CODEX_STATE_IDENTITY_CORRUPT')
  }
  return value as StateIdentityRecord
}

async function readLease(file: string): Promise<StateLeaseRecord> {
  const value = await readStrictJson(file, 'CODEX_STATE_WRITER_LEASE_CORRUPT')
  if (!isObject(value)
      || value.schema_version !== 1
      || typeof value.hostname !== 'string'
      || !value.hostname
      || !Number.isInteger(value.pid)
      || (value.pid as number) <= 0
      || typeof value.nonce !== 'string'
      || !value.nonce
      || typeof value.acquired_at !== 'string'
      || !Number.isFinite(Date.parse(value.acquired_at))) {
    throw new StateStoreSafetyError('CODEX_STATE_WRITER_LEASE_CORRUPT')
  }
  return value as StateLeaseRecord
}

async function readStrictJson(file: string, code: string): Promise<unknown> {
  try {
    const stat = await fs.lstat(file)
    if (!stat.isFile() || stat.isSymbolicLink()) throw new StateStoreSafetyError(code)
    return JSON.parse(await fs.readFile(file, 'utf8')) as unknown
  } catch (error) {
    if (isNodeError(error, 'ENOENT')) throw error
    if (error instanceof StateStoreSafetyError) throw error
    throw new StateStoreSafetyError(code)
  }
}

async function createJsonFileExclusive(file: string, value: object): Promise<boolean> {
  const temporary = path.join(
    path.dirname(file),
    `.${path.basename(file)}.${process.pid}.${crypto.randomUUID()}.tmp`,
  )
  const handle = await fs.open(temporary, 'wx', 0o600)
  try {
    await handle.writeFile(`${JSON.stringify(value)}\n`, 'utf8')
    await handle.sync()
  } finally {
    await handle.close()
  }
  try {
    await fs.link(temporary, file)
    await syncParentDirectory(file)
    return true
  } catch (error) {
    if (isNodeError(error, 'EEXIST')) return false
    throw error
  } finally {
    await fs.rm(temporary, { force: true })
  }
}

function validateIdentifier(value: string, code: string): string {
  if (!IDENTIFIER_PATTERN.test(value)) throw new StateStoreSafetyError(code)
  return value
}

function defaultProcessAlive(pid: number): boolean {
  try {
    process.kill(pid, 0)
    return true
  } catch (error) {
    return !isNodeError(error, 'ESRCH')
  }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function isNodeError(error: unknown, code: string): boolean {
  return error instanceof Error && 'code' in error
    && String((error as NodeJS.ErrnoException).code) === code
}
