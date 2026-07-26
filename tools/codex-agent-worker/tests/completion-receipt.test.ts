import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import {
  COMPLETION_RECEIPT_SCHEMA,
  CompletionReceiptStore,
} from '../src/persistence/completion-receipt.ts'

test('completion receipt persists result first and exposes only content-free evidence', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-completion-receipt-'))
  try {
    const store = new CompletionReceiptStore(root)
    const receipt = await store.persist({
      taskId: 'worker-task-1',
      workerId: 'worker-1',
      providerThreadId: 'thread-1',
      resultText: '{"ok":true,"private":"must-not-appear-in-receipt"}',
      structuredOutputPresent: true,
    })

    assert.equal(receipt.schema, COMPLETION_RECEIPT_SCHEMA)
    assert.match(receipt.final_output_digest, /^sha256:[a-f0-9]{64}$/)
    assert.equal(receipt.final_output_durable, true)
    assert.equal(receipt.result_recoverable, true)

    const receiptText = fs.readFileSync(
      path.join(root, 'completion-receipts', 'worker-task-1.json'),
      'utf8',
    )
    assert.doesNotMatch(receiptText, /must-not-appear-in-receipt/)
    assert.doesNotMatch(receiptText, /private/)

    const observed = await store.inspect('worker-task-1')
    assert.equal(observed?.result_recoverable, true)
    assert.equal(observed?.structured_output_digest, observed?.final_output_digest)
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('completion receipt never claims recoverability when the durable result object is missing', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-completion-receipt-'))
  try {
    const store = new CompletionReceiptStore(root)
    await store.persist({
      taskId: 'worker-task-2',
      workerId: 'worker-1',
      resultText: 'done',
      structuredOutputPresent: false,
    })
    fs.unlinkSync(path.join(root, 'completion-results', 'worker-task-2.result'))

    const observed = await store.inspect('worker-task-2')
    assert.equal(observed?.final_output_durable, true)
    assert.equal(observed?.result_recoverable, false)
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('completion receipt rejects empty output and corrupt content-free metadata', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-completion-receipt-'))
  try {
    const store = new CompletionReceiptStore(root)
    await assert.rejects(
      store.persist({
        taskId: 'worker-task-empty',
        workerId: 'worker-1',
        resultText: '',
        structuredOutputPresent: false,
      }),
      /FINAL_OUTPUT_EMPTY/,
    )

    await store.persist({
      taskId: 'worker-task-corrupt',
      workerId: 'worker-1',
      resultText: 'done',
      structuredOutputPresent: false,
    })
    const receiptPath = path.join(
      root,
      'completion-receipts',
      'worker-task-corrupt.json',
    )
    const receipt = JSON.parse(fs.readFileSync(receiptPath, 'utf8')) as Record<string, unknown>
    receipt.structured_output_present = true
    fs.writeFileSync(receiptPath, `${JSON.stringify(receipt)}\n`)

    await assert.rejects(
      store.inspect('worker-task-corrupt'),
      /COMPLETION_RECEIPT_INVALID/,
    )
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})
