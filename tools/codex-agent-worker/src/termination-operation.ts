import { createHash, createHmac, timingSafeEqual } from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import type {
  TerminationOperationKind,
  TerminationOperationOrigin,
  TerminationOperationSummary,
} from './models.js'
import { isCanonicalCodexCliProcessIdentity } from './codex/processes.js'

const MAX_OPERATION_HEADER_LENGTH = 8 * 1024
const MAX_OPERATION_LIFETIME_MS = 5 * 60 * 1000
const MAX_CLOCK_SKEW_MS = 60 * 1000
const ISO_8601_TIMESTAMP = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/

type OperationHeaderValue = string | string[] | undefined

export type TerminationOperationClaims = {
  schema_version: 1
  operation_id: string
  task_id: string
  /** Durable Navigator PhysicalWorker identity, never a process/runtime alias. */
  worker_id: string
  kind: TerminationOperationKind
  origin: TerminationOperationOrigin
  actor_id: string
  actor_type: string
  authorization_decision_id: string
  reason_code: string
  correlation_id: string
  expected_pid?: number
  expected_process_identity?: string
  issued_at: string
  expires_at: string
}

export type TerminationOperationValidationOptions = {
  workerToken: string
  /**
   * Stable locally configured Navigator PhysicalWorker id.  It is required
   * for every termination route so a valid operation cannot be replayed at a
   * different Worker that happens to share the inbound Worker token.
   */
  expectedWorkerId: string
  expectedKind: TerminationOperationKind
  expectedTaskId?: string
  expectedPid?: number
  now?: () => number
  /**
   * Durable, local receipt ledger.  The receipt is created before any caller
   * can request a provider interrupt or signal a process, so restarting this
   * Worker cannot make an unexpired signed command replayable.
   */
  replayLedger: TerminationOperationReceiptLedger
}

export class TerminationOperationValidationError extends Error {
  constructor(
    readonly code: string,
    readonly statusCode: number,
    message = code,
  ) {
    super(message)
    this.name = 'TerminationOperationValidationError'
  }
}

const RECEIPT_SCHEMA_VERSION = 1
const RECEIPT_FILE_PATTERN = /^[a-f0-9]{64}\.json$/
const MAX_RECEIPT_BYTES = 4 * 1024

type TerminationOperationReceipt = {
  schema_version: 1
  worker_id: string
  operation_id: string
  expires_at: number
}

/**
 * A short-lived operation receipt is a local, durable one-use fence.  It does
 * not store the signed capability or signature.  Its name is derived from the
 * exact Navigator Worker identity and operation id, preventing a receipt for
 * one Worker from colliding with another Worker's authorization scope.
 */
export class TerminationOperationReceiptLedger {
  constructor(
    private readonly directory: string,
    private readonly maxEntries = 2_048,
  ) {}

  consume(workerId: string, operationId: string, expiresAt: number, now: number): void {
    try {
      this.consumeReceipt(workerId, operationId, expiresAt, now)
    } catch (error) {
      if (error instanceof TerminationOperationValidationError) throw error
      // The receipt is the safety boundary.  If its directory, contents, or
      // atomic write cannot be trusted, refuse the termination operation
      // rather than allow a replay after a partial failure.
      throw new TerminationOperationValidationError(
        'TERMINATION_OPERATION_REPLAY_LEDGER_UNAVAILABLE',
        503,
      )
    }
  }

  /** Exposed only to allow isolated temporary-directory regression tests. */
  receiptPathFor(workerId: string, operationId: string): string {
    return path.join(this.directory, `${receiptKey(workerId, operationId)}.json`)
  }

  private consumeReceipt(workerId: string, operationId: string, expiresAt: number, now: number): void {
    const directory = this.ensureDirectory()
    const receiptPath = this.receiptPathFor(workerId, operationId)
    const existing = this.readReceiptIfPresent(receiptPath, workerId, operationId)
    if (existing) {
      // A receipt remains a one-use fence even after expiry.  An expired
      // operation cannot pass envelope validation, and unlinking/reusing this
      // exact key would race another verifier into a second dispatch.
      throw new TerminationOperationValidationError('TERMINATION_OPERATION_REPLAYED', 409)
    }

    const activeEntries = this.pruneExpiredReceipts(directory, now, path.basename(receiptPath))
    if (activeEntries >= this.maxEntries) {
      throw new TerminationOperationValidationError(
        'TERMINATION_OPERATION_REPLAY_LEDGER_FULL',
        503,
      )
    }

    // O_EXCL is the cross-process fence: only the first verifier can write
    // this receipt.  The write and fsync complete before this function returns
    // to the route that performs the termination side effect.
    let descriptor: number | undefined
    try {
      descriptor = fs.openSync(receiptPath, fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_EXCL, 0o600)
    } catch (error) {
      if (!isNodeError(error, 'EEXIST')) throw error
      // Validate the concurrently-created receipt so corrupt state remains a
      // 503, but never unlink/retry the same operation key.
      this.readReceipt(receiptPath, workerId, operationId)
      throw new TerminationOperationValidationError('TERMINATION_OPERATION_REPLAYED', 409)
    }

    try {
      const receipt: TerminationOperationReceipt = {
        schema_version: RECEIPT_SCHEMA_VERSION,
        worker_id: workerId,
        operation_id: operationId,
        expires_at: expiresAt,
      }
      fs.writeFileSync(descriptor, `${JSON.stringify(receipt)}\n`, 'utf8')
      fs.fsyncSync(descriptor)
    } finally {
      if (descriptor !== undefined) fs.closeSync(descriptor)
    }
    // File fsync alone does not make the new directory entry crash-durable on
    // POSIX filesystems.  A directory-sync failure is a failed replay fence.
    syncDirectory(directory)
  }

  private ensureDirectory(): string {
    fs.mkdirSync(this.directory, { recursive: true, mode: 0o700 })
    const stat = fs.lstatSync(this.directory)
    if (!stat.isDirectory() || stat.isSymbolicLink()) {
      throw new Error('termination receipt ledger directory is unsafe')
    }
    return this.directory
  }

  private pruneExpiredReceipts(directory: string, now: number, protectedReceiptName: string): number {
    let activeEntries = 0
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      if (!entry.isFile() || !RECEIPT_FILE_PATTERN.test(entry.name)) {
        throw new Error('termination receipt ledger contains an invalid entry')
      }
      // A concurrent writer can create this key after the initial lookup.
      // Leave it for O_EXCL to report as a replay rather than pruning it.
      if (entry.name === protectedReceiptName) continue
      const receiptPath = path.join(directory, entry.name)
      const receipt = this.readReceipt(receiptPath)
      // Keep a skew grace before reclaiming another key: a receipt must be
      // old enough that no normally-skewed verifier could still accept it.
      if (receipt.expires_at <= now - MAX_CLOCK_SKEW_MS) {
        fs.unlinkSync(receiptPath)
      } else {
        activeEntries += 1
      }
    }
    return activeEntries
  }

  private readReceiptIfPresent(
    receiptPath: string,
    workerId: string,
    operationId: string,
  ): TerminationOperationReceipt | undefined {
    try {
      return this.readReceipt(receiptPath, workerId, operationId)
    } catch (error) {
      if (isNodeError(error, 'ENOENT')) return undefined
      throw error
    }
  }

  private readReceipt(
    receiptPath: string,
    expectedWorkerId?: string,
    expectedOperationId?: string,
  ): TerminationOperationReceipt {
    const before = fs.lstatSync(receiptPath)
    if (!before.isFile() || before.isSymbolicLink() || before.nlink !== 1
        || before.size <= 0 || before.size > MAX_RECEIPT_BYTES) {
      throw new Error('termination receipt is unsafe')
    }
    let descriptor: number | undefined
    try {
      descriptor = fs.openSync(receiptPath, fs.constants.O_RDONLY)
      const opened = fs.fstatSync(descriptor)
      if (!opened.isFile() || opened.nlink !== 1 || opened.dev !== before.dev || opened.ino !== before.ino) {
        throw new Error('termination receipt changed while reading')
      }
      const parsed = JSON.parse(fs.readFileSync(descriptor, 'utf8')) as unknown
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
        throw new Error('termination receipt is malformed')
      }
      const receipt = parsed as Partial<TerminationOperationReceipt>
      if (receipt.schema_version !== RECEIPT_SCHEMA_VERSION
          || typeof receipt.worker_id !== 'string'
          || typeof receipt.operation_id !== 'string'
          || !Number.isFinite(receipt.expires_at)
          || (expectedWorkerId !== undefined && receipt.worker_id !== expectedWorkerId)
          || (expectedOperationId !== undefined && receipt.operation_id !== expectedOperationId)) {
        throw new Error('termination receipt does not match its key')
      }
      return receipt as TerminationOperationReceipt
    } finally {
      if (descriptor !== undefined) fs.closeSync(descriptor)
    }
  }
}

function receiptKey(workerId: string, operationId: string): string {
  return createHash('sha256').update(workerId, 'utf8').update('\0', 'utf8').update(operationId, 'utf8').digest('hex')
}

function isNodeError(error: unknown, code: string): error is NodeJS.ErrnoException {
  return error instanceof Error && 'code' in error
    && String((error as NodeJS.ErrnoException).code) === code
}

function syncDirectory(directory: string): void {
  // Windows does not provide the POSIX directory-fsync durability contract.
  // On POSIX, failure to sync the create entry must fail the operation closed.
  if (process.platform === 'win32') return
  let descriptor: number | undefined
  try {
    descriptor = fs.openSync(directory, fs.constants.O_RDONLY)
    fs.fsyncSync(descriptor)
  } finally {
    if (descriptor !== undefined) fs.closeSync(descriptor)
  }
}

function singleHeaderValue(value: OperationHeaderValue): string | undefined {
  if (Array.isArray(value)) return value.length === 1 ? value[0] : undefined
  return value
}

function requireString(
  source: Record<string, unknown>,
  field: keyof TerminationOperationClaims,
  maxLength = 512,
): string {
  const value = source[field]
  if (typeof value !== 'string' || !value.trim() || value.length > maxLength) {
    throw new TerminationOperationValidationError(
      'TERMINATION_OPERATION_INVALID_CLAIMS',
      400,
      `Invalid termination operation field: ${field}`,
    )
  }
  return value.trim()
}

function parseTimestamp(value: string, field: string): number {
  if (!ISO_8601_TIMESTAMP.test(value)) {
    throw new TerminationOperationValidationError(
      'TERMINATION_OPERATION_INVALID_CLAIMS',
      400,
      `Invalid termination operation timestamp: ${field}`,
    )
  }
  const parsed = Date.parse(value)
  if (!Number.isFinite(parsed)) {
    throw new TerminationOperationValidationError(
      'TERMINATION_OPERATION_INVALID_CLAIMS',
      400,
      `Invalid termination operation timestamp: ${field}`,
    )
  }
  return parsed
}

function parseClaims(encodedOperation: string): TerminationOperationClaims {
  let parsed: unknown
  try {
    const decoded = Buffer.from(encodedOperation, 'base64url').toString('utf8')
    parsed = JSON.parse(decoded)
  } catch {
    throw new TerminationOperationValidationError('TERMINATION_OPERATION_INVALID', 400)
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new TerminationOperationValidationError('TERMINATION_OPERATION_INVALID_CLAIMS', 400)
  }
  const source = parsed as Record<string, unknown>
  if (source.schema_version !== 1) {
    throw new TerminationOperationValidationError('TERMINATION_OPERATION_UNSUPPORTED_VERSION', 400)
  }
  const kind = requireString(source, 'kind', 64)
  if (kind !== 'REMOTE_CANCEL' && kind !== 'MANUAL_PID_KILL') {
    throw new TerminationOperationValidationError('TERMINATION_OPERATION_INVALID_CLAIMS', 400)
  }
  const origin = requireString(source, 'origin', 64)
  if (origin !== 'UPSTREAM_USER' && origin !== 'UPSTREAM_SYSTEM' && origin !== 'ADMIN_MANUAL') {
    throw new TerminationOperationValidationError('TERMINATION_OPERATION_INVALID_CLAIMS', 400)
  }
  const expectedPidRaw = source.expected_pid
  if (expectedPidRaw !== undefined && (!Number.isSafeInteger(expectedPidRaw) || (expectedPidRaw as number) <= 0)) {
    throw new TerminationOperationValidationError('TERMINATION_OPERATION_INVALID_CLAIMS', 400)
  }
  const expectedProcessIdentityRaw = source.expected_process_identity
  if (expectedProcessIdentityRaw !== undefined
      && (typeof expectedProcessIdentityRaw !== 'string' || expectedProcessIdentityRaw.length > 160)) {
    throw new TerminationOperationValidationError('TERMINATION_OPERATION_INVALID_CLAIMS', 400)
  }

  return {
    schema_version: 1,
    operation_id: requireString(source, 'operation_id'),
    task_id: requireString(source, 'task_id'),
    worker_id: requireWorkerId(source),
    kind,
    origin,
    actor_id: requireString(source, 'actor_id'),
    actor_type: requireString(source, 'actor_type'),
    authorization_decision_id: requireString(source, 'authorization_decision_id'),
    reason_code: requireString(source, 'reason_code'),
    correlation_id: requireString(source, 'correlation_id'),
    expected_pid: expectedPidRaw as number | undefined,
    expected_process_identity: expectedProcessIdentityRaw as string | undefined,
    issued_at: requireString(source, 'issued_at', 128),
    expires_at: requireString(source, 'expires_at', 128),
  }
}

function requireWorkerId(source: Record<string, unknown>): string {
  const value = source.worker_id
  if (typeof value !== 'string' || !value.trim() || value.length > 128) {
    throw new TerminationOperationValidationError(
      'TERMINATION_OPERATION_WORKER_ID_REQUIRED',
      400,
      'Termination operation must bind to a Navigator Worker identity',
    )
  }
  return value.trim()
}

function assertSignature(
  encodedOperation: string,
  providedSignature: string,
  workerToken: string,
): void {
  if (!workerToken.trim()) {
    throw new TerminationOperationValidationError(
      'TERMINATION_OPERATION_AUTH_UNCONFIGURED',
      503,
    )
  }
  const expectedSignature = createHmac('sha256', workerToken)
    .update(encodedOperation, 'utf8')
    .digest('base64url')
  const providedBuffer = Buffer.from(providedSignature, 'utf8')
  const expectedBuffer = Buffer.from(expectedSignature, 'utf8')
  if (providedBuffer.length !== expectedBuffer.length
      || !timingSafeEqual(providedBuffer, expectedBuffer)) {
    throw new TerminationOperationValidationError('TERMINATION_OPERATION_SIGNATURE_INVALID', 403)
  }
}

/**
 * Validates the signed one-use operation envelope shared by the Java control
 * plane and every Worker.  The signature is calculated over the exact
 * base64url payload, avoiding JSON key-order ambiguity between languages.
 */
export function validateTerminationOperation(
  operationHeader: OperationHeaderValue,
  signatureHeader: OperationHeaderValue,
  options: TerminationOperationValidationOptions,
): TerminationOperationClaims {
  const encodedOperation = singleHeaderValue(operationHeader)
  const providedSignature = singleHeaderValue(signatureHeader)
  if (!encodedOperation || !providedSignature
      || encodedOperation.length > MAX_OPERATION_HEADER_LENGTH
      || providedSignature.length > MAX_OPERATION_HEADER_LENGTH
      || !/^[A-Za-z0-9_-]+$/.test(encodedOperation)
      || !/^[A-Za-z0-9_-]+$/.test(providedSignature)) {
    throw new TerminationOperationValidationError('TERMINATION_OPERATION_REQUIRED', 400)
  }

  assertSignature(encodedOperation, providedSignature, options.workerToken)
  const claims = parseClaims(encodedOperation)
  if (claims.kind !== options.expectedKind) {
    throw new TerminationOperationValidationError('TERMINATION_OPERATION_KIND_MISMATCH', 409)
  }
  if (claims.kind === 'REMOTE_CANCEL'
      && claims.origin !== 'UPSTREAM_USER'
      && claims.origin !== 'UPSTREAM_SYSTEM') {
    throw new TerminationOperationValidationError('TERMINATION_OPERATION_ORIGIN_MISMATCH', 403)
  }
  if (claims.kind === 'MANUAL_PID_KILL' && claims.origin !== 'ADMIN_MANUAL') {
    throw new TerminationOperationValidationError('TERMINATION_OPERATION_ORIGIN_MISMATCH', 403)
  }
  // A manual signal must be pinned to the exact PID that was authorized.  Do
  // this at the envelope layer so a future route cannot accidentally accept a
  // broad ADMIN_MANUAL operation without a process binding.
  if (claims.kind === 'MANUAL_PID_KILL' && claims.expected_pid === undefined) {
    throw new TerminationOperationValidationError('TERMINATION_OPERATION_PID_REQUIRED', 400)
  }
  if (claims.kind === 'MANUAL_PID_KILL' && !claims.expected_process_identity) {
    throw new TerminationOperationValidationError(
      'TERMINATION_OPERATION_PROCESS_IDENTITY_REQUIRED',
      400,
    )
  }
  if (claims.kind === 'MANUAL_PID_KILL'
      && !isCanonicalCodexCliProcessIdentity(
        claims.expected_process_identity as string,
        claims.expected_pid,
      )) {
    throw new TerminationOperationValidationError(
      'TERMINATION_OPERATION_PROCESS_IDENTITY_INVALID',
      400,
    )
  }
  if (options.expectedTaskId && claims.task_id !== options.expectedTaskId) {
    throw new TerminationOperationValidationError('TERMINATION_OPERATION_TASK_MISMATCH', 409)
  }
  const expectedWorkerId = options.expectedWorkerId.trim()
  if (!expectedWorkerId) {
    throw new TerminationOperationValidationError(
      'TERMINATION_OPERATION_WORKER_UNCONFIGURED',
      503,
    )
  }
  if (claims.worker_id !== expectedWorkerId) {
    throw new TerminationOperationValidationError('TERMINATION_OPERATION_WORKER_MISMATCH', 409)
  }
  if (options.expectedPid !== undefined
      && claims.expected_pid !== options.expectedPid) {
    throw new TerminationOperationValidationError('TERMINATION_OPERATION_PID_MISMATCH', 409)
  }

  const now = (options.now ?? Date.now)()
  const issuedAt = parseTimestamp(claims.issued_at, 'issued_at')
  const expiresAt = parseTimestamp(claims.expires_at, 'expires_at')
  if (issuedAt > now + MAX_CLOCK_SKEW_MS
      || expiresAt <= now
      || expiresAt <= issuedAt
      || expiresAt - issuedAt > MAX_OPERATION_LIFETIME_MS) {
    throw new TerminationOperationValidationError('TERMINATION_OPERATION_EXPIRED', 409)
  }
  options.replayLedger.consume(claims.worker_id, claims.operation_id, expiresAt, now)
  return claims
}

export function toTerminationOperationSummary(
  claims: TerminationOperationClaims,
  status: TerminationOperationSummary['status'],
  result?: string,
): TerminationOperationSummary {
  return {
    operation_id: claims.operation_id,
    task_id: claims.task_id,
    worker_id: claims.worker_id,
    kind: claims.kind,
    origin: claims.origin,
    actor_id: claims.actor_id,
    actor_type: claims.actor_type,
    authorization_decision_id: claims.authorization_decision_id,
    reason_code: claims.reason_code,
    correlation_id: claims.correlation_id,
    expected_pid: claims.expected_pid,
    expected_process_identity: claims.expected_process_identity,
    requested_at: claims.issued_at,
    status,
    result,
  }
}
