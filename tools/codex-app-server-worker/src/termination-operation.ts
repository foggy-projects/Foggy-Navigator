import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import type { Request } from 'express'
import type { AppConfig } from './config.js'
import type {
  TerminationOperationKind,
  TerminationOperationOrigin,
  TerminationOperationSummary,
} from './models.js'

export const TERMINATION_OPERATION_HEADER = 'X-Navigator-Termination-Operation'
export const TERMINATION_SIGNATURE_HEADER = 'X-Navigator-Termination-Signature'

const MAX_OPERATION_LIFETIME_MS = 5 * 60_000
const MAX_CLOCK_SKEW_MS = 30_000
const MAX_REPLAY_ENTRIES = 10_000
const MAX_OPERATION_HEADER_LENGTH = 8 * 1024
const ISO_8601_INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/
const RECEIPT_SCHEMA_VERSION = 1
const RECEIPT_FILE_PATTERN = /^[a-f0-9]{64}\.json$/
const MAX_RECEIPT_BYTES = 4 * 1024

export interface ValidatedTerminationOperation extends TerminationOperationSummary {
  schema_version: 1
}

export class TerminationOperationValidationError extends Error {
  constructor(readonly code:
    | 'TERMINATION_AUTH_UNCONFIGURED'
    | 'TERMINATION_OPERATION_MISSING'
    | 'TERMINATION_OPERATION_INVALID'
    | 'TERMINATION_OPERATION_SIGNATURE_INVALID'
    | 'TERMINATION_OPERATION_EXPIRED'
    | 'TERMINATION_OPERATION_REPLAYED'
    | 'TERMINATION_OPERATION_REPLAY_LEDGER_FULL'
    | 'TERMINATION_OPERATION_REPLAY_LEDGER_UNAVAILABLE'
    | 'TERMINATION_OPERATION_MISMATCH',
  ) {
    super(code)
    this.name = 'TerminationOperationValidationError'
  }
}

type TerminationOperationReceipt = {
  schema_version: 1
  worker_id: string
  operation_id: string
  expires_at: number
}

/**
 * A receipt is written with O_EXCL before an authorized cancel or PID signal
 * can be dispatched.  It survives a local Worker restart and stores no signed
 * capability material, only the one-use operation fence and expiry.
 */
export class TerminationOperationReceiptLedger {
  constructor(
    private readonly directory: string,
    private readonly maxEntries = MAX_REPLAY_ENTRIES,
  ) {}

  consume(operation: ValidatedTerminationOperation, now = Date.now()): void {
    try {
      this.consumeReceipt(operation, now)
    } catch (error) {
      if (error instanceof TerminationOperationValidationError) throw error
      throw new TerminationOperationValidationError('TERMINATION_OPERATION_REPLAY_LEDGER_UNAVAILABLE')
    }
  }

  /** Exposed for isolated temporary-state regression tests. */
  receiptPathFor(workerId: string, operationId: string): string {
    return path.join(this.directory, `${receiptKey(workerId, operationId)}.json`)
  }

  private consumeReceipt(operation: ValidatedTerminationOperation, now: number): void {
    const directory = this.ensureDirectory()
    const expiresAt = Date.parse(operation.expires_at)
    const receiptPath = this.receiptPathFor(operation.worker_id, operation.operation_id)
    const existing = this.readReceiptIfPresent(receiptPath, operation.worker_id, operation.operation_id)
    if (existing) {
      // This key has already fenced one dispatch.  An expired receipt is
      // still a replay: envelope validation prevents that old operation from
      // being used, while unlink/reuse here could race a second dispatcher.
      throw new TerminationOperationValidationError('TERMINATION_OPERATION_REPLAYED')
    }

    if (this.pruneExpiredReceipts(directory, now, path.basename(receiptPath)) >= this.maxEntries) {
      throw new TerminationOperationValidationError('TERMINATION_OPERATION_REPLAY_LEDGER_FULL')
    }

    let descriptor: number | undefined
    try {
      descriptor = fs.openSync(receiptPath, fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_EXCL, 0o600)
    } catch (error) {
      if (!isNodeError(error, 'EEXIST')) throw error
      // Keep malformed concurrent state fail-closed, but an existing valid
      // receipt is always a replay; never unlink and retry this operation key.
      this.readReceipt(receiptPath, operation.worker_id, operation.operation_id)
      throw new TerminationOperationValidationError('TERMINATION_OPERATION_REPLAYED')
    }

    try {
      const receipt: TerminationOperationReceipt = {
        schema_version: RECEIPT_SCHEMA_VERSION,
        worker_id: operation.worker_id,
        operation_id: operation.operation_id,
        expires_at: expiresAt,
      }
      fs.writeFileSync(descriptor, `${JSON.stringify(receipt)}\n`, 'utf8')
      fs.fsyncSync(descriptor)
    } finally {
      if (descriptor !== undefined) fs.closeSync(descriptor)
    }
    // POSIX requires the parent directory to be fsynced after a newly-created
    // receipt file; otherwise a crash can lose its name despite file fsync.
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
      // If another verifier created our key after the first lookup, let its
      // O_EXCL conflict report replay instead of removing that receipt here.
      if (entry.name === protectedReceiptName) continue
      const receiptPath = path.join(directory, entry.name)
      const receipt = this.readReceipt(receiptPath)
      // Do not reclaim an entry until it is beyond clock-skew grace, so no
      // normally-skewed local verifier could still accept that capability.
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

export function validateTerminationOperation(
  req: Request,
  config: Pick<AppConfig, 'workerToken' | 'navigatorWorkerId' | 'workerName' | 'runtimeId' | 'instanceId'>,
  taskId: string | undefined,
  kind: TerminationOperationKind,
  replayLedger: TerminationOperationReceiptLedger,
  now = Date.now(),
): ValidatedTerminationOperation {
  if (!config.workerToken.trim()) throw new TerminationOperationValidationError('TERMINATION_AUTH_UNCONFIGURED')
  const encoded = req.header(TERMINATION_OPERATION_HEADER)
  const signature = req.header(TERMINATION_SIGNATURE_HEADER)
  if (!encoded || !signature || encoded.length > MAX_OPERATION_HEADER_LENGTH || signature.length > MAX_OPERATION_HEADER_LENGTH) {
    throw new TerminationOperationValidationError('TERMINATION_OPERATION_MISSING')
  }
  verifySignature(encoded, signature, config.workerToken)
  const operation = parseOperation(encoded)
  validateOperationShape(operation, now)
  if ((taskId !== undefined && operation.task_id !== taskId) || operation.kind !== kind) {
    throw new TerminationOperationValidationError('TERMINATION_OPERATION_MISMATCH')
  }
  // Java binds the operation to its durable PhysicalWorker identity.  Runtime
  // ids, instance ids, and names are deliberately not substitutes here: they
  // are mutable/process-local observability fields, not authorization scope.
  if (!config.navigatorWorkerId || operation.worker_id !== config.navigatorWorkerId) {
    throw new TerminationOperationValidationError('TERMINATION_OPERATION_MISMATCH')
  }
  // target_worker_id predates the exact worker_id contract.  Keep it only as
  // an optional additional binding for mixed-version callers; it can never
  // replace worker_id above.
  if (operation.target_worker_id && !matchesTargetWorker(operation.target_worker_id, config)) {
    throw new TerminationOperationValidationError('TERMINATION_OPERATION_MISMATCH')
  }
  replayLedger.consume(operation, now)
  return operation
}

function verifySignature(encoded: string, provided: string, workerToken: string): void {
  if (!isBase64Url(encoded) || !isBase64Url(provided)) {
    throw new TerminationOperationValidationError('TERMINATION_OPERATION_SIGNATURE_INVALID')
  }
  let actual: Buffer
  try {
    actual = Buffer.from(provided, 'base64url')
  } catch {
    throw new TerminationOperationValidationError('TERMINATION_OPERATION_SIGNATURE_INVALID')
  }
  const expected = crypto.createHmac('sha256', workerToken).update(encoded).digest()
  if (actual.length !== expected.length || !crypto.timingSafeEqual(actual, expected)) {
    throw new TerminationOperationValidationError('TERMINATION_OPERATION_SIGNATURE_INVALID')
  }
}

function parseOperation(encoded: string): ValidatedTerminationOperation {
  try {
    const decoded = Buffer.from(encoded, 'base64url').toString('utf8')
    const value = JSON.parse(decoded) as unknown
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
      throw new Error('not an object')
    }
    return value as ValidatedTerminationOperation
  } catch {
    throw new TerminationOperationValidationError('TERMINATION_OPERATION_INVALID')
  }
}

function validateOperationShape(operation: ValidatedTerminationOperation, now: number): void {
  if (operation.schema_version !== 1
      || !isIdentifier(operation.operation_id)
      || !isIdentifier(operation.task_id)
      || !isText(operation.worker_id)
      || !isKind(operation.kind)
      || !isOrigin(operation.origin)
      || !isText(operation.actor_id)
      || !isText(operation.actor_type)
      || !isIdentifier(operation.authorization_decision_id)
      || !isText(operation.reason_code)
      || !isText(operation.correlation_id)
      || !isIsoInstant(operation.issued_at)
      || !isIsoInstant(operation.expires_at)
      || (operation.expected_pid !== undefined && (!Number.isSafeInteger(operation.expected_pid) || operation.expected_pid <= 0))
      || (operation.expected_process_identity !== undefined && !isText(operation.expected_process_identity))
      || (operation.kind === 'MANUAL_PID_KILL'
        && (operation.expected_pid === undefined || !operation.expected_process_identity))
      || (operation.target_worker_id !== undefined && !isText(operation.target_worker_id))
      || (operation.kind === 'MANUAL_PID_KILL' && operation.origin !== 'ADMIN_MANUAL')
      || (operation.kind === 'REMOTE_CANCEL' && operation.origin === 'ADMIN_MANUAL')) {
    throw new TerminationOperationValidationError('TERMINATION_OPERATION_INVALID')
  }
  const issuedAt = Date.parse(operation.issued_at)
  const expiresAt = Date.parse(operation.expires_at)
  if (expiresAt <= issuedAt || expiresAt - issuedAt > MAX_OPERATION_LIFETIME_MS
      || issuedAt > now + MAX_CLOCK_SKEW_MS || expiresAt <= now) {
    throw new TerminationOperationValidationError('TERMINATION_OPERATION_EXPIRED')
  }
}

function matchesTargetWorker(
  target: string,
  config: Pick<AppConfig, 'navigatorWorkerId' | 'workerName' | 'runtimeId' | 'instanceId'>,
): boolean {
  return target === config.navigatorWorkerId
    || target === config.workerName
    || target === config.runtimeId
    || target === config.instanceId
}

function isIdentifier(value: unknown): value is string {
  return typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$/.test(value)
}

function isText(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0 && value.length <= 512
}

function isIsoInstant(value: unknown): value is string {
  return typeof value === 'string'
    && ISO_8601_INSTANT.test(value)
    && Number.isFinite(Date.parse(value))
}

function isKind(value: unknown): value is TerminationOperationKind {
  return value === 'REMOTE_CANCEL' || value === 'MANUAL_PID_KILL'
}

function isBase64Url(value: string): boolean {
  return /^[A-Za-z0-9_-]+$/.test(value)
}

function isOrigin(value: unknown): value is TerminationOperationOrigin {
  return value === 'UPSTREAM_USER' || value === 'UPSTREAM_SYSTEM' || value === 'ADMIN_MANUAL'
}

function receiptKey(workerId: string, operationId: string): string {
  return crypto.createHash('sha256').update(workerId, 'utf8').update('\0', 'utf8').update(operationId, 'utf8').digest('hex')
}

function isNodeError(error: unknown, code: string): error is NodeJS.ErrnoException {
  return error instanceof Error && 'code' in error
    && String((error as NodeJS.ErrnoException).code) === code
}

function syncDirectory(directory: string): void {
  if (process.platform === 'win32') return
  let descriptor: number | undefined
  try {
    descriptor = fs.openSync(directory, fs.constants.O_RDONLY)
    fs.fsyncSync(descriptor)
  } finally {
    if (descriptor !== undefined) fs.closeSync(descriptor)
  }
}
