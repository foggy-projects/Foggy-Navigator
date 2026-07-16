import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import {
  TerminationOperationReceiptLedger,
  TerminationOperationValidationError,
  type ValidatedTerminationOperation,
} from '../src/termination-operation.js'
import { tempDirectory } from './helpers.js'

function operation(operationId: string): ValidatedTerminationOperation {
  return {
    schema_version: 1,
    operation_id: operationId,
    task_id: 'task-1',
    worker_id: 'navigator-worker-1',
    kind: 'REMOTE_CANCEL',
    origin: 'UPSTREAM_USER',
    actor_id: 'user-1',
    actor_type: 'USER',
    authorization_decision_id: 'decision-1',
    reason_code: 'USER_CANCEL',
    correlation_id: 'correlation-1',
    issued_at: '2026-07-16T00:00:00.000Z',
    expires_at: '2026-07-16T00:01:00.000Z',
  }
}

function assertValidationCode(callback: () => unknown, code: string): void {
  assert.throws(callback, (error: unknown) => error instanceof TerminationOperationValidationError
    && error.code === code)
}

test('termination receipt ledger fails closed at capacity and retains old operation ids', async t => {
  const stateDir = await tempDirectory('codex-app-termination-ledger-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const ledger = new TerminationOperationReceiptLedger(
    path.join(stateDir, 'termination-operations', 'receipts'),
    1,
  )
  const now = Date.parse('2026-07-16T00:00:10.000Z')
  const first = operation('operation-first')
  ledger.consume(first, now)

  assertValidationCode(
    () => ledger.consume(operation('operation-second'), now),
    'TERMINATION_OPERATION_REPLAY_LEDGER_FULL',
  )
  assertValidationCode(
    () => ledger.consume(first, now),
    'TERMINATION_OPERATION_REPLAYED',
  )

  ledger.consume({
    ...operation('operation-second'),
    expires_at: '2026-07-16T00:03:00.000Z',
  }, Date.parse('2026-07-16T00:02:01.000Z'))
})

test('termination receipt survives verifier recreation and corrupt receipts fail closed', async t => {
  const stateDir = await tempDirectory('codex-app-termination-ledger-restart-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const receiptDir = path.join(stateDir, 'termination-operations', 'receipts')
  const first = new TerminationOperationReceiptLedger(receiptDir)
  const now = Date.parse('2026-07-16T00:00:10.000Z')
  first.consume(operation('operation-restart'), now)

  const restarted = new TerminationOperationReceiptLedger(receiptDir)
  assertValidationCode(
    () => restarted.consume(operation('operation-restart'), now),
    'TERMINATION_OPERATION_REPLAYED',
  )

  await fs.writeFile(
    restarted.receiptPathFor('navigator-worker-1', 'operation-corrupt'),
    '{not-json',
    'utf8',
  )
  assertValidationCode(
    () => restarted.consume(operation('operation-corrupt'), now),
    'TERMINATION_OPERATION_REPLAY_LEDGER_UNAVAILABLE',
  )

  const expiredLedger = new TerminationOperationReceiptLedger(
    path.join(stateDir, 'termination-operations', 'expired-receipts'),
  )
  expiredLedger.consume({
    ...operation('operation-expired-receipt'),
    expires_at: '2026-07-16T00:00:00.000Z',
  }, now)
  assertValidationCode(
    () => expiredLedger.consume({
      ...operation('operation-expired-receipt'),
      expires_at: '2026-07-16T00:02:00.000Z',
    }, now),
    'TERMINATION_OPERATION_REPLAYED',
  )
})
