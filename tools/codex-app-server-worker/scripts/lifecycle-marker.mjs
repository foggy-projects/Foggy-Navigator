import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

const SCHEMA_VERSION = 1
const SAFE_NONCE = /^[a-f0-9]{32}$/
const SAFE_REASON = /^[A-Za-z][A-Za-z0-9_]{2,127}$/
const SAFE_MANAGED_NAME = /^(?:[A-Za-z0-9._-]+)$/
const LOCK_OPERATIONS = new Set(['start', 'stop', 'update'])
const PHASES = new Set([
  'created',
  'validated',
  'draining',
  'drained',
  'backing_up',
  'installing',
  'candidate_start',
  'committed',
  'rollback',
  'rollback_failed',
  'candidate_failed',
  'failed_pre_swap',
])

class MarkerError extends Error {}

function main(argv) {
  const options = parseArguments(argv)
  switch (options.action) {
    case 'create':
      createTransaction(options)
      break
    case 'update':
      updateTransaction(options)
      break
    case 'verify-owner':
      verifyOwner(options)
      break
    case 'remove':
      removeTransaction(options)
      break
    case 'write-once':
      writeOnceFailure(options)
      break
    case 'lock-acquire':
      acquireLifecycleLock(options)
      break
    case 'lock-verify-owner':
      verifyLifecycleLockOwner(options)
      break
    case 'lock-release':
      releaseLifecycleLock(options)
      break
    default:
      throw new MarkerError('unsupported action')
  }
}

function parseArguments(argv) {
  if (argv.length === 0) throw new MarkerError('missing action')
  const action = argv[0]
  if (![
    'create', 'update', 'verify-owner', 'remove', 'write-once',
    'lock-acquire', 'lock-verify-owner', 'lock-release',
  ].includes(action)) {
    throw new MarkerError('unsupported action')
  }
  const values = new Map()
  for (let index = 1; index < argv.length; index += 2) {
    const key = argv[index]
    const value = argv[index + 1]
    if (!key?.startsWith('--') || value === undefined || values.has(key)) throw new MarkerError('invalid arguments')
    values.set(key, value)
  }
  const allowed = new Set([
    '--path', '--nonce', '--stage-root', '--phase', '--append-backed-up', '--append-installed', '--reason', '--operation',
  ])
  for (const key of values.keys()) if (!allowed.has(key)) throw new MarkerError('unsupported argument')
  const markerPath = requiredPath(values, '--path')
  const nonce = values.get('--nonce')
  if (nonce !== undefined && !SAFE_NONCE.test(nonce)) throw new MarkerError('invalid nonce')
  return {
    action,
    markerPath,
    nonce,
    stageRoot: values.has('--stage-root') ? path.resolve(values.get('--stage-root')) : undefined,
    phase: values.get('--phase'),
    appendBackedUp: values.get('--append-backed-up'),
    appendInstalled: values.get('--append-installed'),
    reason: values.get('--reason'),
    operation: values.get('--operation'),
  }
}

function requiredPath(values, key) {
  const value = values.get(key)
  if (!value) throw new MarkerError(`missing ${key}`)
  return path.resolve(value)
}

function createTransaction(options) {
  if (!options.nonce || !options.stageRoot) throw new MarkerError('create requires nonce and stage root')
  rejectUnexpected(options, ['action', 'markerPath', 'nonce', 'stageRoot'])
  ensureSafeParent(options.markerPath)
  const installParent = path.dirname(path.dirname(options.markerPath))
  if (path.dirname(options.stageRoot) !== installParent || !/^\.caw-[a-f0-9]{12}$/.test(path.basename(options.stageRoot))) {
    throw new MarkerError('stage root is outside the bound install parent')
  }
  const now = new Date().toISOString()
  const marker = {
    schema_version: SCHEMA_VERSION,
    nonce: options.nonce,
    created_at: now,
    updated_at: now,
    phase: 'created',
    stage_root: options.stageRoot,
    stage_digest: sha256(normalizePath(options.stageRoot)),
    backed_up: [],
    installed: [],
  }
  writeExclusive(options.markerPath, serialize(marker))
}

function updateTransaction(options) {
  if (!options.nonce || !options.phase || !PHASES.has(options.phase)) {
    throw new MarkerError('update requires nonce and valid phase')
  }
  rejectUnexpected(options, ['action', 'markerPath', 'nonce', 'phase', 'appendBackedUp', 'appendInstalled'])
  const marker = readTransaction(options.markerPath)
  assertOwner(marker, options.nonce)
  const backedUp = appendManaged(marker.backed_up, options.appendBackedUp)
  const installed = appendManaged(marker.installed, options.appendInstalled)
  const next = {
    ...marker,
    updated_at: new Date().toISOString(),
    phase: options.phase,
    backed_up: backedUp,
    installed,
  }
  writeReplacement(options.markerPath, serialize(next))
}

function verifyOwner(options) {
  if (!options.nonce) throw new MarkerError('verify-owner requires nonce')
  rejectUnexpected(options, ['action', 'markerPath', 'nonce'])
  assertOwner(readTransaction(options.markerPath), options.nonce)
}

function removeTransaction(options) {
  if (!options.nonce) throw new MarkerError('remove requires nonce')
  rejectUnexpected(options, ['action', 'markerPath', 'nonce'])
  assertOwner(readTransaction(options.markerPath), options.nonce)
  fs.unlinkSync(options.markerPath)
  fsyncDirectory(path.dirname(options.markerPath))
}

function writeOnceFailure(options) {
  if (!options.reason || !SAFE_REASON.test(options.reason)) throw new MarkerError('write-once requires a safe reason')
  rejectUnexpected(options, ['action', 'markerPath', 'reason'])
  ensureSafeParent(options.markerPath)
  if (fs.existsSync(options.markerPath)) {
    assertRegularFile(options.markerPath)
    return
  }
  writeExclusive(options.markerPath, `${options.reason}\n`)
}

function acquireLifecycleLock(options) {
  if (!options.nonce || !options.operation || !LOCK_OPERATIONS.has(options.operation)) {
    throw new MarkerError('lock-acquire requires nonce and valid operation')
  }
  rejectUnexpected(options, ['action', 'markerPath', 'nonce', 'operation'])
  ensureSafeParent(options.markerPath)
  const lock = {
    schema_version: SCHEMA_VERSION,
    kind: 'lifecycle_lock',
    owner_digest: sha256(options.nonce),
    operation: options.operation,
    created_at: new Date().toISOString(),
  }
  writeExclusive(options.markerPath, serializeLifecycleLock(lock))
}

function verifyLifecycleLockOwner(options) {
  if (!options.nonce || !options.operation || !LOCK_OPERATIONS.has(options.operation)) {
    throw new MarkerError('lock-verify-owner requires nonce and valid operation')
  }
  rejectUnexpected(options, ['action', 'markerPath', 'nonce', 'operation'])
  const lock = readLifecycleLock(options.markerPath)
  if (lock.operation !== options.operation) throw new MarkerError('lifecycle lock operation mismatch')
  assertLifecycleLockOwner(lock, options.nonce)
}

function releaseLifecycleLock(options) {
  if (!options.nonce) throw new MarkerError('lock-release requires nonce')
  rejectUnexpected(options, ['action', 'markerPath', 'nonce'])
  const identity = regularFileIdentity(options.markerPath)
  assertLifecycleLockOwner(readLifecycleLock(options.markerPath), options.nonce)
  assertSameRegularFile(options.markerPath, identity)
  fs.unlinkSync(options.markerPath)
  fsyncDirectory(path.dirname(options.markerPath))
}

function rejectUnexpected(options, allowed) {
  const allowedKeys = new Set(allowed)
  for (const [key, value] of Object.entries(options)) {
    if (value !== undefined && !allowedKeys.has(key)) throw new MarkerError('unexpected arguments')
  }
}

function appendManaged(existing, value) {
  if (value === undefined) return existing
  if (!SAFE_MANAGED_NAME.test(value)) throw new MarkerError('invalid managed path name')
  if (existing.includes(value)) throw new MarkerError('managed path progress is duplicated')
  return [...existing, value]
}

function readTransaction(markerPath) {
  assertRegularFile(markerPath)
  let marker
  try {
    marker = JSON.parse(fs.readFileSync(markerPath, 'utf8'))
  } catch {
    throw new MarkerError('transaction marker is invalid')
  }
  validateTransaction(marker)
  return marker
}

function readLifecycleLock(markerPath) {
  assertRegularFile(markerPath)
  let lock
  try {
    lock = JSON.parse(fs.readFileSync(markerPath, 'utf8'))
  } catch {
    throw new MarkerError('lifecycle lock is invalid')
  }
  validateLifecycleLock(lock)
  return lock
}

function validateTransaction(marker) {
  if (!isPlainObject(marker)) throw new MarkerError('transaction marker is invalid')
  assertExactKeys(marker, [
    'schema_version', 'nonce', 'created_at', 'updated_at', 'phase',
    'stage_root', 'stage_digest', 'backed_up', 'installed',
  ])
  if (marker.schema_version !== SCHEMA_VERSION || !SAFE_NONCE.test(marker.nonce)) throw new MarkerError('transaction identity is invalid')
  if (!PHASES.has(marker.phase)) throw new MarkerError('transaction phase is invalid')
  if (!isTimestamp(marker.created_at) || !isTimestamp(marker.updated_at)) throw new MarkerError('transaction timestamp is invalid')
  if (typeof marker.stage_root !== 'string' || path.resolve(marker.stage_root) !== marker.stage_root) {
    throw new MarkerError('transaction stage root is invalid')
  }
  if (marker.stage_digest !== sha256(normalizePath(marker.stage_root))) throw new MarkerError('transaction stage digest is invalid')
  validateManagedList(marker.backed_up)
  validateManagedList(marker.installed)
}

function validateLifecycleLock(lock) {
  if (!isPlainObject(lock)) throw new MarkerError('lifecycle lock is invalid')
  assertExactKeys(lock, ['schema_version', 'kind', 'owner_digest', 'operation', 'created_at'])
  if (lock.schema_version !== SCHEMA_VERSION || lock.kind !== 'lifecycle_lock') {
    throw new MarkerError('lifecycle lock identity is invalid')
  }
  if (!/^[a-f0-9]{64}$/.test(lock.owner_digest) || !LOCK_OPERATIONS.has(lock.operation)) {
    throw new MarkerError('lifecycle lock owner is invalid')
  }
  if (!isTimestamp(lock.created_at)) throw new MarkerError('lifecycle lock timestamp is invalid')
}

function validateManagedList(value) {
  if (!Array.isArray(value) || new Set(value).size !== value.length || value.some(item => typeof item !== 'string' || !SAFE_MANAGED_NAME.test(item))) {
    throw new MarkerError('transaction progress is invalid')
  }
}

function assertOwner(marker, nonce) {
  const left = Buffer.from(marker.nonce, 'utf8')
  const right = Buffer.from(nonce, 'utf8')
  if (left.length !== right.length || !crypto.timingSafeEqual(left, right)) throw new MarkerError('transaction owner mismatch')
}

function assertLifecycleLockOwner(lock, nonce) {
  const left = Buffer.from(lock.owner_digest, 'utf8')
  const right = Buffer.from(sha256(nonce), 'utf8')
  if (left.length !== right.length || !crypto.timingSafeEqual(left, right)) {
    throw new MarkerError('lifecycle lock owner mismatch')
  }
}

function writeExclusive(target, content) {
  const temporary = temporaryPath(target)
  try {
    writeDurableFile(temporary, content)
    fs.linkSync(temporary, target)
    fsyncDirectory(path.dirname(target))
  } catch (error) {
    if (error?.code === 'EEXIST') throw new MarkerError('marker already exists')
    throw error
  } finally {
    fs.rmSync(temporary, { force: true })
  }
}

function writeReplacement(target, content) {
  assertRegularFile(target)
  const temporary = temporaryPath(target)
  try {
    writeDurableFile(temporary, content)
    fs.renameSync(temporary, target)
    fsyncDirectory(path.dirname(target))
  } finally {
    fs.rmSync(temporary, { force: true })
  }
}

function writeDurableFile(file, content) {
  const descriptor = fs.openSync(file, 'wx', 0o600)
  try {
    fs.writeFileSync(descriptor, content, 'utf8')
    fs.fsyncSync(descriptor)
  } finally {
    fs.closeSync(descriptor)
  }
}

function ensureSafeParent(target) {
  const parent = path.dirname(target)
  fs.mkdirSync(parent, { recursive: true })
  const stat = fs.lstatSync(parent)
  if (!stat.isDirectory() || stat.isSymbolicLink()) throw new MarkerError('marker parent is unsafe')
  if (fs.existsSync(target)) assertRegularFile(target)
}

function assertRegularFile(file) {
  let stat
  try {
    stat = fs.lstatSync(file)
  } catch {
    throw new MarkerError('marker is unavailable')
  }
  if (!stat.isFile() || stat.isSymbolicLink() || stat.nlink !== 1) throw new MarkerError('marker is unsafe')
}

function regularFileIdentity(file) {
  assertRegularFile(file)
  const stat = fs.lstatSync(file)
  return { dev: stat.dev, ino: stat.ino }
}

function assertSameRegularFile(file, expected) {
  assertRegularFile(file)
  const stat = fs.lstatSync(file)
  if (stat.dev !== expected.dev || stat.ino !== expected.ino) throw new MarkerError('marker identity changed')
}

function fsyncDirectory(directory) {
  let descriptor
  try {
    descriptor = fs.openSync(directory, 'r')
    fs.fsyncSync(descriptor)
  } catch (error) {
    if (process.platform !== 'win32') throw error
  } finally {
    if (descriptor !== undefined) fs.closeSync(descriptor)
  }
}

function temporaryPath(target) {
  return path.join(path.dirname(target), `.${path.basename(target)}.${process.pid}.${crypto.randomBytes(12).toString('hex')}.tmp`)
}

function serialize(marker) {
  validateTransaction(marker)
  return `${JSON.stringify(marker, null, 2)}\n`
}

function serializeLifecycleLock(lock) {
  validateLifecycleLock(lock)
  return `${JSON.stringify(lock, null, 2)}\n`
}

function assertExactKeys(value, expected) {
  const actual = Object.keys(value).sort()
  const wanted = [...expected].sort()
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) {
    throw new MarkerError('transaction marker contains unsupported data')
  }
}

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function isTimestamp(value) {
  return typeof value === 'string' && Number.isFinite(Date.parse(value))
}

function normalizePath(value) {
  const normalized = path.resolve(value).replaceAll('\\', '/')
  return process.platform === 'win32' ? normalized.toLowerCase() : normalized
}

function sha256(value) {
  return crypto.createHash('sha256').update(value).digest('hex')
}

const isDirectExecution = process.argv[1]
  && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url

if (isDirectExecution) {
  try {
    main(process.argv.slice(2))
  } catch (error) {
    const message = error instanceof MarkerError ? error.message : 'marker operation failed'
    process.stderr.write(`lifecycle-marker: ${message}\n`)
    process.exitCode = 1
  }
}
