import assert from 'node:assert/strict'
import { createHmac } from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import {
  TerminationOperationReceiptLedger,
  TerminationOperationValidationError,
  toTerminationOperationSummary,
  validateTerminationOperation,
} from '../src/termination-operation.ts'

const NOW = Date.parse('2026-07-16T00:00:00.000Z')

function signedOperation(
  overrides: Record<string, unknown> = {},
  token = 'worker-test-token',
): { encoded: string; signature: string } {
  const claims = {
    schema_version: 1,
    operation_id: 'operation-1',
    task_id: 'task-1',
    worker_id: 'navigator-worker-1',
    kind: 'REMOTE_CANCEL',
    origin: 'UPSTREAM_USER',
    actor_id: 'user-1',
    actor_type: 'USER',
    authorization_decision_id: 'decision-1',
    reason_code: 'USER_CANCELLED',
    correlation_id: 'correlation-1',
    issued_at: '2026-07-15T23:59:00.000Z',
    expires_at: '2026-07-16T00:01:00.000Z',
    ...overrides,
  }
  const encoded = Buffer.from(JSON.stringify(claims), 'utf8').toString('base64url')
  const signature = createHmac('sha256', token).update(encoded, 'utf8').digest('base64url')
  return { encoded, signature }
}

function assertValidationCode(callback: () => unknown, code: string): void {
  assert.throws(callback, (error: unknown) => error instanceof TerminationOperationValidationError
    && error.code === code)
}

function temporaryLedger(
  t: { after: (callback: () => void) => void },
  maxEntries = 2_048,
): TerminationOperationReceiptLedger {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-termination-ledger-'))
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }))
  return new TerminationOperationReceiptLedger(directory, maxEntries)
}

test('valid signed remote cancel is accepted once with matching task and short-lived claims', t => {
  const replayLedger = temporaryLedger(t)
  const { encoded, signature } = signedOperation()
  const claims = validateTerminationOperation(encoded, signature, {
    workerToken: 'worker-test-token',
    expectedWorkerId: 'navigator-worker-1',
    expectedKind: 'REMOTE_CANCEL',
    expectedTaskId: 'task-1',
    now: () => NOW,
    replayLedger,
  })

  assert.equal(claims.operation_id, 'operation-1')
  assert.equal(claims.kind, 'REMOTE_CANCEL')
  assert.equal(toTerminationOperationSummary(claims, 'CANCEL_REQUESTED').task_id, 'task-1')
})

test('durable receipt lookup proves only an exact previously consumed operation', t => {
  const replayLedger = temporaryLedger(t)
  replayLedger.consume('navigator-worker-1', 'operation-received', NOW + 60_000, NOW)

  assert.equal(replayLedger.hasConsumed('navigator-worker-1', 'operation-received'), true)
  assert.equal(replayLedger.hasConsumed('navigator-worker-1', 'operation-missing'), false)
  assert.equal(replayLedger.hasConsumed('navigator-worker-2', 'operation-received'), false)
})

test('termination operation rejects missing authentication, tampering, expiry, replay, and mismatched task or PID', t => {
  const operation = signedOperation({ expected_pid: 321 })
  const options = {
    workerToken: 'worker-test-token',
    expectedWorkerId: 'navigator-worker-1',
    expectedKind: 'REMOTE_CANCEL' as const,
    expectedTaskId: 'task-1',
    now: () => NOW,
    replayLedger: temporaryLedger(t),
  }
  assertValidationCode(
    () => validateTerminationOperation(undefined, undefined, options),
    'TERMINATION_OPERATION_REQUIRED',
  )
  assertValidationCode(
    () => validateTerminationOperation(operation.encoded, `${operation.signature}x`, options),
    'TERMINATION_OPERATION_SIGNATURE_INVALID',
  )
  assertValidationCode(
    () => validateTerminationOperation(operation.encoded, operation.signature, {
      ...options,
      workerToken: '',
      replayLedger: temporaryLedger(t),
    }),
    'TERMINATION_OPERATION_AUTH_UNCONFIGURED',
  )
  assertValidationCode(
    () => validateTerminationOperation(operation.encoded, operation.signature, {
      ...options,
      expectedTaskId: 'other-task',
      replayLedger: temporaryLedger(t),
    }),
    'TERMINATION_OPERATION_TASK_MISMATCH',
  )
  assertValidationCode(
    () => validateTerminationOperation(operation.encoded, operation.signature, {
      ...options,
      expectedPid: 123,
      replayLedger: temporaryLedger(t),
    }),
    'TERMINATION_OPERATION_PID_MISMATCH',
  )
  const expired = signedOperation({
    operation_id: 'operation-expired',
    issued_at: '2026-07-15T23:50:00.000Z',
    expires_at: '2026-07-15T23:55:00.000Z',
  })
  assertValidationCode(
    () => validateTerminationOperation(expired.encoded, expired.signature, {
      ...options,
      replayLedger: temporaryLedger(t),
    }),
    'TERMINATION_OPERATION_EXPIRED',
  )
  const epochTimestamp = signedOperation({
    operation_id: 'operation-epoch-timestamp',
    issued_at: String(NOW - 60_000),
    expires_at: String(NOW + 60_000),
  })
  assertValidationCode(
    () => validateTerminationOperation(epochTimestamp.encoded, epochTimestamp.signature, {
      ...options,
      replayLedger: temporaryLedger(t),
    }),
    'TERMINATION_OPERATION_INVALID_CLAIMS',
  )

  const replayLedger = temporaryLedger(t)
  validateTerminationOperation(operation.encoded, operation.signature, { ...options, replayLedger })
  assertValidationCode(
    () => validateTerminationOperation(operation.encoded, operation.signature, { ...options, replayLedger }),
    'TERMINATION_OPERATION_REPLAYED',
  )
})

test('a full durable receipt ledger fails closed without making an unexpired operation replayable', t => {
  const replayLedger = temporaryLedger(t, 1)
  replayLedger.consume('navigator-worker-1', 'operation-first', NOW + 60_000, NOW)

  assertValidationCode(
    () => replayLedger.consume('navigator-worker-1', 'operation-second', NOW + 60_000, NOW),
    'TERMINATION_OPERATION_REPLAY_LEDGER_FULL',
  )
  assertValidationCode(
    () => replayLedger.consume('navigator-worker-1', 'operation-first', NOW + 60_000, NOW),
    'TERMINATION_OPERATION_REPLAYED',
  )

  replayLedger.consume('navigator-worker-1', 'operation-second', NOW + 180_000, NOW + 120_001)
})

test('operation kind and origin are constrained to explicit remote cancel or admin manual PID kill', t => {
  const replayLedger = temporaryLedger(t)
  const manual = signedOperation({
    operation_id: 'operation-manual',
    kind: 'MANUAL_PID_KILL',
    origin: 'ADMIN_MANUAL',
    expected_pid: 321,
    expected_process_identity: 'codex-cli:321:2026-07-16T00:00:00.000Z',
  })
  const claims = validateTerminationOperation(manual.encoded, manual.signature, {
    workerToken: 'worker-test-token',
    expectedWorkerId: 'navigator-worker-1',
    expectedKind: 'MANUAL_PID_KILL',
    expectedTaskId: 'task-1',
    expectedPid: 321,
    now: () => NOW,
    replayLedger,
  })
  assert.equal(claims.origin, 'ADMIN_MANUAL')

  const badOrigin = signedOperation({
    operation_id: 'operation-bad-origin',
    kind: 'MANUAL_PID_KILL',
    origin: 'UPSTREAM_USER',
  })
  assertValidationCode(
    () => validateTerminationOperation(badOrigin.encoded, badOrigin.signature, {
      workerToken: 'worker-test-token',
      expectedWorkerId: 'navigator-worker-1',
      expectedKind: 'MANUAL_PID_KILL',
      now: () => NOW,
      replayLedger,
    }),
    'TERMINATION_OPERATION_ORIGIN_MISMATCH',
  )

  const missingPid = signedOperation({
    operation_id: 'operation-manual-missing-pid',
    kind: 'MANUAL_PID_KILL',
    origin: 'ADMIN_MANUAL',
  })
  assertValidationCode(
    () => validateTerminationOperation(missingPid.encoded, missingPid.signature, {
      workerToken: 'worker-test-token',
      expectedWorkerId: 'navigator-worker-1',
      expectedKind: 'MANUAL_PID_KILL',
      expectedTaskId: 'task-1',
      expectedPid: 321,
      now: () => NOW,
      replayLedger,
    }),
    'TERMINATION_OPERATION_PID_REQUIRED',
  )

  const missingIdentity = signedOperation({
    operation_id: 'operation-manual-missing-identity',
    kind: 'MANUAL_PID_KILL',
    origin: 'ADMIN_MANUAL',
    expected_pid: 321,
  })
  assertValidationCode(
    () => validateTerminationOperation(missingIdentity.encoded, missingIdentity.signature, {
      workerToken: 'worker-test-token',
      expectedWorkerId: 'navigator-worker-1',
      expectedKind: 'MANUAL_PID_KILL',
      expectedTaskId: 'task-1',
      expectedPid: 321,
      now: () => NOW,
      replayLedger,
    }),
    'TERMINATION_OPERATION_PROCESS_IDENTITY_REQUIRED',
  )

  const nonCanonicalIdentity = signedOperation({
    operation_id: 'operation-manual-noncanonical-identity',
    kind: 'MANUAL_PID_KILL',
    origin: 'ADMIN_MANUAL',
    expected_pid: 321,
    expected_process_identity: 'codex-cli:321:2026-07-16T00:00:00+00:00',
  })
  assertValidationCode(
    () => validateTerminationOperation(nonCanonicalIdentity.encoded, nonCanonicalIdentity.signature, {
      workerToken: 'worker-test-token',
      expectedWorkerId: 'navigator-worker-1',
      expectedKind: 'MANUAL_PID_KILL',
      expectedTaskId: 'task-1',
      expectedPid: 321,
      now: () => NOW,
      replayLedger,
    }),
    'TERMINATION_OPERATION_PROCESS_IDENTITY_INVALID',
  )
})

test('reconcile cancel accepts only an upstream user origin and remains one-use', t => {
  const replayLedger = temporaryLedger(t)
  const operation = signedOperation({
    operation_id: 'operation-reconcile',
    kind: 'RECONCILE_CANCEL',
    origin: 'UPSTREAM_USER',
  })
  const claims = validateTerminationOperation(operation.encoded, operation.signature, {
    workerToken: 'worker-test-token',
    expectedWorkerId: 'navigator-worker-1',
    expectedKind: 'RECONCILE_CANCEL',
    expectedTaskId: 'task-1',
    now: () => NOW,
    replayLedger,
  })
  assert.equal(claims.kind, 'RECONCILE_CANCEL')

  const badOrigin = signedOperation({
    operation_id: 'operation-reconcile-system',
    kind: 'RECONCILE_CANCEL',
    origin: 'UPSTREAM_SYSTEM',
  })
  assertValidationCode(
    () => validateTerminationOperation(badOrigin.encoded, badOrigin.signature, {
      workerToken: 'worker-test-token',
      expectedWorkerId: 'navigator-worker-1',
      expectedKind: 'RECONCILE_CANCEL',
      expectedTaskId: 'task-1',
      now: () => NOW,
      replayLedger: temporaryLedger(t),
    }),
    'TERMINATION_OPERATION_ORIGIN_MISMATCH',
  )
})

test('termination operation requires an exact configured Navigator Worker binding without consuming rejected operations', t => {
  const operation = signedOperation({ operation_id: 'worker-binding-operation' })
  const replayLedger = temporaryLedger(t)
  const options = {
    workerToken: 'worker-test-token',
    expectedKind: 'REMOTE_CANCEL' as const,
    expectedTaskId: 'task-1',
    now: () => NOW,
    replayLedger,
  }

  assertValidationCode(
    () => validateTerminationOperation(operation.encoded, operation.signature, {
      ...options,
      expectedWorkerId: '',
    }),
    'TERMINATION_OPERATION_WORKER_UNCONFIGURED',
  )
  assertValidationCode(
    () => validateTerminationOperation(operation.encoded, operation.signature, {
      ...options,
      expectedWorkerId: 'different-navigator-worker',
    }),
    'TERMINATION_OPERATION_WORKER_MISMATCH',
  )
  assertValidationCode(
    () => {
      const missingWorker = signedOperation({ operation_id: 'missing-worker-binding', worker_id: '' })
      return validateTerminationOperation(missingWorker.encoded, missingWorker.signature, {
        ...options,
        expectedWorkerId: 'navigator-worker-1',
        replayLedger: temporaryLedger(t),
      })
    },
    'TERMINATION_OPERATION_WORKER_ID_REQUIRED',
  )

  const accepted = validateTerminationOperation(operation.encoded, operation.signature, {
    ...options,
    expectedWorkerId: 'navigator-worker-1',
  })
  assert.equal(accepted.worker_id, 'navigator-worker-1')
})

test('durable receipt ledger rejects replay after verifier recreation and fails closed for corruption', t => {
  const first = temporaryLedger(t)
  first.consume('navigator-worker-1', 'durable-operation', NOW + 60_000, NOW)

  const restarted = new TerminationOperationReceiptLedger(
    path.dirname(first.receiptPathFor('navigator-worker-1', 'durable-operation')),
  )
  assertValidationCode(
    () => restarted.consume('navigator-worker-1', 'durable-operation', NOW + 60_000, NOW),
    'TERMINATION_OPERATION_REPLAYED',
  )

  const corruptPath = restarted.receiptPathFor('navigator-worker-1', 'corrupt-operation')
  fs.writeFileSync(corruptPath, '{not-json', 'utf8')
  assertValidationCode(
    () => restarted.consume('navigator-worker-1', 'corrupt-operation', NOW + 60_000, NOW),
    'TERMINATION_OPERATION_REPLAY_LEDGER_UNAVAILABLE',
  )

  const expiredLedger = temporaryLedger(t)
  expiredLedger.consume('navigator-worker-1', 'expired-operation', NOW - 1, NOW)
  assertValidationCode(
    () => expiredLedger.consume('navigator-worker-1', 'expired-operation', NOW + 60_000, NOW),
    'TERMINATION_OPERATION_REPLAYED',
  )
})

test('durable receipt ledger exposes non-destructive readiness without leaking its path', t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-termination-readiness-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))

  const ready = new TerminationOperationReceiptLedger(path.join(root, 'ledger'))
  assert.equal(ready.isReady(), true)

  const unsafePath = path.join(root, 'not-a-directory')
  fs.writeFileSync(unsafePath, 'unsafe')
  assert.equal(new TerminationOperationReceiptLedger(unsafePath).isReady(), false)
})
