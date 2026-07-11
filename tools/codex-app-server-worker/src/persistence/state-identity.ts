import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { syncParentDirectory } from './jsonl-durability.js'

const IDENTITY_FILE = '.codex-store-identity.json'
const GENERATION_SENTINEL_FILE = '.codex-store-generation.json'
const LEASE_FILE = '.codex-store-writer-lease.json'
const RECOVERY_LOCK_FILE = '.codex-store-writer-lease-recovery.json'
const STATE_SUBDIRECTORIES = ['tasks', 'events'] as const
const IDENTIFIER_PATTERN = /^[A-Za-z0-9._-]{1,128}$/
const GENERATION_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const MAX_STATE_METADATA_BYTES = 16 * 1024
const MAX_LEGACY_INSTANCE_ID_LENGTH = 128

type StateIdentityRecordV1 = {
  schema_version: 1
  instance_id: string
  created_at: string
}

type StateIdentityRecordV2 = {
  schema_version: 2
  instance_id: string
  generation_id: string
  created_at: string
  legacy_instance_id?: string
}

type StateIdentityRecord = StateIdentityRecordV1 | StateIdentityRecordV2

type GenerationSentinel = {
  schema_version: 1
  generation_id: string
  created_at: string
}

type StateOwnerRecord = {
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
    private readonly owner: StateOwnerRecord,
    private readonly corruptCode = 'CODEX_STATE_WRITER_LEASE_CORRUPT',
    private readonly ownershipLostCode = 'CODEX_STATE_WRITER_LEASE_OWNERSHIP_LOST',
  ) {}

  async assertOwned(): Promise<void> {
    const current = await readOwner(this.leaseFile, this.corruptCode)
    if (current.nonce !== this.owner.nonce) {
      throw new StateStoreSafetyError(this.ownershipLostCode)
    }
  }

  async release(): Promise<void> {
    if (this.released) return
    let current: StateOwnerRecord
    try {
      current = await readOwner(this.leaseFile, this.corruptCode)
    } catch (error) {
      if (!isNodeError(error, 'ENOENT')) throw error
      await syncParentDirectory(this.leaseFile)
      this.released = true
      return
    }
    if (current.nonce !== this.owner.nonce) {
      throw new StateStoreSafetyError(this.ownershipLostCode)
    }
    await fs.unlink(this.leaseFile)
    await syncParentDirectory(this.leaseFile)
    this.released = true
  }
}

/** Resolves aliases, symlinks and Windows junctions before any state lock or journal is opened. */
export async function canonicalizeStateDirectory(stateDir: string): Promise<string> {
  const requested = path.resolve(stateDir)
  await fs.mkdir(requested, { recursive: true, mode: 0o700 })
  const canonical = path.normalize(await fs.realpath(requested))
  const stat = await fs.lstat(canonical)
  if (!stat.isDirectory() || stat.isSymbolicLink()) {
    throw new StateStoreSafetyError('CODEX_STATE_DIRECTORY_INVALID')
  }
  return canonical
}

/**
 * Resolves the state-store generation identity. Schema v1 markers receive one
 * compatibility migration; schema v2 requires matching sentinels in both
 * journal directories and rotates the public identity when either is cleared.
 */
export async function initializeStateIdentity(
  stateDir: string,
  legacyInstanceId: string,
  now: () => Date = () => new Date(),
): Promise<string> {
  const canonicalStateDir = await canonicalizeStateDirectory(stateDir)
  const marker = path.join(canonicalStateDir, IDENTITY_FILE)
  let existing: StateIdentityRecord | undefined
  try {
    existing = await readIdentity(marker)
  } catch (error) {
    if (!isNodeError(error, 'ENOENT')) throw error
  }

  if (!existing) {
    const legacy = await containsJournal(canonicalStateDir)
      ? resolveLegacyInstanceIdentity(legacyInstanceId)
      : undefined
    const createdAt = now().toISOString()
    const record = createIdentityRecord(
      legacy?.instanceId || randomInstanceId(),
      createdAt,
      legacy?.storedLegacyInstanceId,
    )
    await writeGenerationSentinels(canonicalStateDir, record)
    if (!await createJsonFileExclusive(marker, record)) {
      return initializeStateIdentity(canonicalStateDir, legacyInstanceId, now)
    }
    return record.instance_id
  }

  if (existing.schema_version === 1) {
    const migrated = createIdentityRecord(existing.instance_id, existing.created_at)
    await writeGenerationSentinels(canonicalStateDir, migrated)
    await replaceJsonFile(marker, migrated)
    return migrated.instance_id
  }

  if (await generationSentinelsMatch(canonicalStateDir, existing)) {
    return existing.instance_id
  }

  const rotated = createIdentityRecord(randomInstanceId(), now().toISOString())
  await writeGenerationSentinels(canonicalStateDir, rotated)
  await replaceJsonFile(marker, rotated)
  return rotated.instance_id
}

/** Acquires the single local JSONL writer lease for a canonical state directory. */
export async function acquireStateWriterLease(
  stateDir: string,
  options: LeaseOptions = {},
): Promise<StateWriterLease> {
  const canonicalStateDir = await canonicalizeStateDirectory(stateDir)
  const hostname = options.hostname ?? os.hostname()
  const pid = options.pid ?? process.pid
  const isProcessAlive = options.isProcessAlive ?? defaultProcessAlive
  const now = options.now ?? (() => new Date())
  const owner = createOwner(hostname, pid, now)
  const recoveryOwner = createOwner(hostname, pid, now)
  const recoveryFile = path.join(canonicalStateDir, RECOVERY_LOCK_FILE)
  const recovery = await acquireRecoveryLock(recoveryFile, recoveryOwner, isProcessAlive)
  let acquired: StateWriterLease | undefined

  try {
    const leaseFile = path.join(canonicalStateDir, LEASE_FILE)
    if (await createJsonFileExclusive(leaseFile, owner)) {
      acquired = new StateWriterLease(leaseFile, owner)
    } else {
      const existing = await readOwner(leaseFile, 'CODEX_STATE_WRITER_LEASE_CORRUPT')
      assertReclaimableOwner(existing, hostname, isProcessAlive, {
        crossHost: 'CODEX_STATE_WRITER_LEASE_CROSS_HOST',
        active: 'CODEX_STATE_WRITER_LEASE_ACTIVE',
      })
      await recovery.assertOwned()
      const rechecked = await readOwner(leaseFile, 'CODEX_STATE_WRITER_LEASE_CORRUPT')
      assertReclaimableOwner(rechecked, hostname, isProcessAlive, {
        crossHost: 'CODEX_STATE_WRITER_LEASE_CROSS_HOST',
        active: 'CODEX_STATE_WRITER_LEASE_ACTIVE',
      })
      await fs.unlink(leaseFile)
      await syncParentDirectory(leaseFile)
      if (!await createJsonFileExclusive(leaseFile, owner)) {
        throw new StateStoreSafetyError('CODEX_STATE_WRITER_LEASE_RACE')
      }
      acquired = new StateWriterLease(leaseFile, owner)
    }
  } finally {
    try {
      await recovery.release()
    } catch (error) {
      await acquired?.release().catch(() => undefined)
      throw error
    }
  }

  return acquired
}

async function acquireRecoveryLock(
  recoveryFile: string,
  owner: StateOwnerRecord,
  isProcessAlive: (pid: number) => boolean,
): Promise<StateWriterLease> {
  if (await createJsonFileExclusive(recoveryFile, owner)) {
    return recoveryLease(recoveryFile, owner)
  }

  const existing = await readOwner(recoveryFile, 'CODEX_STATE_WRITER_LEASE_RECOVERY_CORRUPT')
  assertReclaimableOwner(existing, owner.hostname, isProcessAlive, {
    crossHost: 'CODEX_STATE_WRITER_LEASE_RECOVERY_CROSS_HOST',
    active: 'CODEX_STATE_WRITER_LEASE_RECOVERY_IN_PROGRESS',
  })
  const rechecked = await readOwner(recoveryFile, 'CODEX_STATE_WRITER_LEASE_RECOVERY_CORRUPT')
  if (rechecked.nonce !== existing.nonce) {
    throw new StateStoreSafetyError('CODEX_STATE_WRITER_LEASE_RECOVERY_IN_PROGRESS')
  }
  assertReclaimableOwner(rechecked, owner.hostname, isProcessAlive, {
    crossHost: 'CODEX_STATE_WRITER_LEASE_RECOVERY_CROSS_HOST',
    active: 'CODEX_STATE_WRITER_LEASE_RECOVERY_IN_PROGRESS',
  })
  await fs.unlink(recoveryFile)
  await syncParentDirectory(recoveryFile)
  if (!await createJsonFileExclusive(recoveryFile, owner)) {
    throw new StateStoreSafetyError('CODEX_STATE_WRITER_LEASE_RECOVERY_IN_PROGRESS')
  }
  return recoveryLease(recoveryFile, owner)
}

function recoveryLease(file: string, owner: StateOwnerRecord): StateWriterLease {
  return new StateWriterLease(
    file,
    owner,
    'CODEX_STATE_WRITER_LEASE_RECOVERY_CORRUPT',
    'CODEX_STATE_WRITER_LEASE_RECOVERY_OWNERSHIP_LOST',
  )
}

function assertReclaimableOwner(
  existing: StateOwnerRecord,
  hostname: string,
  isProcessAlive: (pid: number) => boolean,
  codes: { crossHost: string; active: string },
): void {
  if (existing.hostname !== hostname) throw new StateStoreSafetyError(codes.crossHost)
  if (isProcessAlive(existing.pid)) throw new StateStoreSafetyError(codes.active)
}

function createOwner(hostname: string, pid: number, now: () => Date): StateOwnerRecord {
  if (!hostname || hostname.length > 255 || /[\r\n\0]/.test(hostname)) {
    throw new StateStoreSafetyError('CODEX_STATE_WRITER_HOSTNAME_INVALID')
  }
  if (!Number.isInteger(pid) || pid <= 0) {
    throw new StateStoreSafetyError('CODEX_STATE_WRITER_PID_INVALID')
  }
  return {
    schema_version: 1,
    hostname,
    pid,
    nonce: crypto.randomUUID(),
    acquired_at: now().toISOString(),
  }
}

function createIdentityRecord(
  instanceId: string,
  createdAt: string,
  legacyInstanceId?: string,
): StateIdentityRecordV2 {
  return {
    schema_version: 2,
    instance_id: instanceId,
    generation_id: crypto.randomUUID(),
    created_at: createdAt,
    ...(legacyInstanceId === undefined ? {} : { legacy_instance_id: legacyInstanceId }),
  }
}

function randomInstanceId(): string {
  return `codex-store-${crypto.randomUUID()}`
}

function resolveLegacyInstanceIdentity(value: string): {
  instanceId: string
  storedLegacyInstanceId?: string
} {
  const effective = value.trim()
  if (!effective || effective.length > MAX_LEGACY_INSTANCE_ID_LENGTH) {
    throw new StateStoreSafetyError('CODEX_STATE_LEGACY_INSTANCE_ID_INVALID')
  }
  if (IDENTIFIER_PATTERN.test(effective)) return { instanceId: effective }
  const digest = crypto.createHash('sha256').update(JSON.stringify(effective), 'utf8').digest('hex')
  return {
    instanceId: `codex-legacy-${digest}`,
    storedLegacyInstanceId: effective,
  }
}

async function containsJournal(stateDir: string): Promise<boolean> {
  for (const directory of STATE_SUBDIRECTORIES) {
    try {
      const entries = await fs.readdir(path.join(stateDir, directory), { withFileTypes: true })
      if (entries.some(entry => entry.isFile() && entry.name.endsWith('.jsonl'))) return true
    } catch (error) {
      if (!isNodeError(error, 'ENOENT')) throw error
    }
  }
  return false
}

async function writeGenerationSentinels(stateDir: string, identity: StateIdentityRecordV2): Promise<void> {
  const sentinel: GenerationSentinel = {
    schema_version: 1,
    generation_id: identity.generation_id,
    created_at: identity.created_at,
  }
  for (const directory of STATE_SUBDIRECTORIES) {
    const journalDirectory = path.join(stateDir, directory)
    await ensurePlainDirectory(journalDirectory)
    await replaceJsonFile(path.join(journalDirectory, GENERATION_SENTINEL_FILE), sentinel)
  }
}

async function generationSentinelsMatch(
  stateDir: string,
  identity: StateIdentityRecordV2,
): Promise<boolean> {
  for (const directory of STATE_SUBDIRECTORIES) {
    try {
      const journalDirectory = path.join(stateDir, directory)
      const stat = await fs.lstat(journalDirectory)
      if (!stat.isDirectory() || stat.isSymbolicLink()) return false
      const sentinel = await readGenerationSentinel(path.join(journalDirectory, GENERATION_SENTINEL_FILE))
      if (sentinel.generation_id !== identity.generation_id) return false
    } catch (error) {
      if (isNodeError(error, 'ENOENT') || error instanceof StateStoreSafetyError) return false
      throw error
    }
  }
  return true
}

async function ensurePlainDirectory(directory: string): Promise<void> {
  await fs.mkdir(directory, { recursive: true, mode: 0o700 })
  const stat = await fs.lstat(directory)
  if (!stat.isDirectory() || stat.isSymbolicLink()) {
    throw new StateStoreSafetyError('CODEX_STATE_JOURNAL_DIRECTORY_INVALID')
  }
}

async function readIdentity(file: string): Promise<StateIdentityRecord> {
  const value = await readStrictJson(file, 'CODEX_STATE_IDENTITY_CORRUPT')
  if (!isObject(value)
      || (value.schema_version !== 1 && value.schema_version !== 2)
      || typeof value.instance_id !== 'string'
      || !IDENTIFIER_PATTERN.test(value.instance_id)
      || typeof value.created_at !== 'string'
      || !Number.isFinite(Date.parse(value.created_at))) {
    throw new StateStoreSafetyError('CODEX_STATE_IDENTITY_CORRUPT')
  }
  if (value.schema_version === 1) return value as StateIdentityRecordV1
  if (typeof value.generation_id !== 'string' || !GENERATION_PATTERN.test(value.generation_id)) {
    throw new StateStoreSafetyError('CODEX_STATE_IDENTITY_CORRUPT')
  }
  if (value.legacy_instance_id !== undefined
      && (typeof value.legacy_instance_id !== 'string'
        || !value.legacy_instance_id
        || value.legacy_instance_id.length > MAX_LEGACY_INSTANCE_ID_LENGTH)) {
    throw new StateStoreSafetyError('CODEX_STATE_IDENTITY_CORRUPT')
  }
  return value as StateIdentityRecordV2
}

async function readGenerationSentinel(file: string): Promise<GenerationSentinel> {
  const value = await readStrictJson(file, 'CODEX_STATE_GENERATION_SENTINEL_CORRUPT')
  if (!isObject(value)
      || value.schema_version !== 1
      || typeof value.generation_id !== 'string'
      || !GENERATION_PATTERN.test(value.generation_id)
      || typeof value.created_at !== 'string'
      || !Number.isFinite(Date.parse(value.created_at))) {
    throw new StateStoreSafetyError('CODEX_STATE_GENERATION_SENTINEL_CORRUPT')
  }
  return value as GenerationSentinel
}

async function readOwner(file: string, code: string): Promise<StateOwnerRecord> {
  const value = await readStrictJson(file, code)
  if (!isObject(value)
      || value.schema_version !== 1
      || typeof value.hostname !== 'string'
      || !value.hostname
      || value.hostname.length > 255
      || /[\r\n\0]/.test(value.hostname)
      || !Number.isInteger(value.pid)
      || (value.pid as number) <= 0
      || typeof value.nonce !== 'string'
      || !GENERATION_PATTERN.test(value.nonce)
      || typeof value.acquired_at !== 'string'
      || !Number.isFinite(Date.parse(value.acquired_at))) {
    throw new StateStoreSafetyError(code)
  }
  return value as StateOwnerRecord
}

async function readStrictJson(file: string, code: string): Promise<unknown> {
  let handle: fs.FileHandle | undefined
  try {
    const before = await fs.lstat(file)
    if (!before.isFile() || before.isSymbolicLink() || before.nlink !== 1
        || before.size <= 0 || before.size > MAX_STATE_METADATA_BYTES) {
      throw new StateStoreSafetyError(code)
    }
    handle = await fs.open(file, 'r')
    const opened = await handle.stat()
    if (!opened.isFile() || opened.nlink !== 1 || opened.dev !== before.dev || opened.ino !== before.ino) {
      throw new StateStoreSafetyError(code)
    }
    return JSON.parse(await handle.readFile('utf8')) as unknown
  } catch (error) {
    if (isNodeError(error, 'ENOENT')) throw error
    if (error instanceof StateStoreSafetyError) throw error
    throw new StateStoreSafetyError(code)
  } finally {
    await handle?.close()
  }
}

async function createJsonFileExclusive(file: string, value: object): Promise<boolean> {
  let handle: fs.FileHandle
  try {
    handle = await fs.open(file, 'wx', 0o600)
  } catch (error) {
    if (isNodeError(error, 'EEXIST')) return false
    throw error
  }
  try {
    await handle.writeFile(`${JSON.stringify(value)}\n`, 'utf8')
    await handle.sync()
  } finally {
    await handle.close()
  }
  await syncParentDirectory(file)
  return true
}

async function replaceJsonFile(file: string, value: object): Promise<void> {
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
    await fs.rename(temporary, file)
    await syncParentDirectory(file)
  } catch (error) {
    await fs.rm(temporary, { force: true })
    throw error
  }
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

function isNodeError(error: unknown, code: string): error is NodeJS.ErrnoException {
  return error instanceof Error && 'code' in error
    && String((error as NodeJS.ErrnoException).code) === code
}
