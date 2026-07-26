import assert from 'node:assert/strict'
import { once } from 'node:events'
import fs from 'node:fs'
import type { AddressInfo } from 'node:net'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import express from 'express'
import { config } from '../src/config.ts'
import { taskRegistry } from '../src/codex/sdk-wrapper.ts'
import { CompletionReceiptStore } from '../src/persistence/completion-receipt.ts'
import { createTasksRouter } from '../src/routes/tasks.ts'

async function withServer(
  store: CompletionReceiptStore,
  listProcesses: () => Promise<Array<{
    pid: number
    command: string
    memory_mb: number
    started_at: string
  }>>,
  action: (baseUrl: string) => Promise<void>,
) {
  const app = express()
  app.use(createTasksRouter({ completionReceiptStore: store, listProcesses }))
  const server = app.listen(0, '127.0.0.1')
  await once(server, 'listening')
  const address = server.address() as AddressInfo
  try {
    await action(`http://127.0.0.1:${address.port}`)
  } finally {
    await new Promise<void>((resolve, reject) => {
      server.close(error => error ? reject(error) : resolve())
    })
  }
}

test.beforeEach(() => {
  taskRegistry.clear()
  config.navigatorWorkerId = 'worker-1'
})

test('completion readiness distinguishes a live exact-bound provider process', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-completion-route-'))
  const store = new CompletionReceiptStore(root)
  const startedAt = '2026-07-25T01:02:03.000Z'
  taskRegistry.set('worker-task-live', {
    taskId: 'worker-task-live',
    status: 'running',
    pid: 4321,
    processStartedAt: startedAt,
    startedAt: Date.parse(startedAt),
    lastProgressAt: Date.parse('2026-07-25T01:03:00.000Z'),
  })
  try {
    await withServer(store, async () => [{
      pid: 4321,
      command: 'codex --experimental-json',
      memory_mb: 10,
      started_at: startedAt,
    }], async baseUrl => {
      const response = await fetch(`${baseUrl}/api/v1/tasks/worker-task-live/completion-readiness`)
      assert.equal(response.status, 200)
      const body = await response.json() as Record<string, unknown>
      assert.equal(body.worker_task_known, true)
      assert.equal(body.provider_active_task_present, true)
      assert.equal(body.provider_process_present, true)
      assert.equal(body.provider_process_state, 'PRESENT')
      assert.equal(body.completion_signal_present, null)
    })
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('completion readiness exposes durable success after Worker memory is gone without returning content', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-completion-route-'))
  const store = new CompletionReceiptStore(root)
  try {
    await store.persist({
      taskId: 'worker-task-done',
      workerId: 'worker-1',
      resultText: 'sensitive final result',
      structuredOutputPresent: false,
    })
    await withServer(store, async () => [], async baseUrl => {
      const response = await fetch(`${baseUrl}/api/v1/tasks/worker-task-done/completion-readiness`)
      assert.equal(response.status, 200)
      const raw = await response.text()
      assert.doesNotMatch(raw, /sensitive final result/)
      const body = JSON.parse(raw) as Record<string, unknown>
      assert.equal(body.worker_task_known, false)
      assert.equal(body.provider_process_present, false)
      assert.equal(body.provider_task_terminal, true)
      assert.equal(body.provider_terminal_status, 'COMPLETED')
      assert.equal(body.final_output_durable, true)
      assert.equal(body.completion_signal_source, 'PROVIDER_TERMINAL_EVENT')
      assert.match(String(body.final_output_digest), /^sha256:[a-f0-9]{64}$/)
      assert.equal(body.result_recoverable, true)
    })
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('completion readiness leaves process state UNKNOWN when the task is unbound and other Codex processes exist', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-completion-route-'))
  const store = new CompletionReceiptStore(root)
  try {
    await withServer(store, async () => [{
      pid: 9999,
      command: 'codex --experimental-json',
      memory_mb: 10,
      started_at: '2026-07-25T01:02:03.000Z',
    }], async baseUrl => {
      const response = await fetch(`${baseUrl}/api/v1/tasks/worker-task-unknown/completion-readiness`)
      const body = await response.json() as Record<string, unknown>
      assert.equal(body.worker_task_known, false)
      assert.equal(body.provider_active_task_present, null)
      assert.equal(body.provider_process_present, null)
      assert.equal(body.provider_process_state, 'UNKNOWN')
      assert.equal(body.provider_task_terminal, null)
      assert.equal(body.final_output_present, null)
      assert.equal(body.completion_signal_present, null)
      assert.equal(body.result_recoverable, null)
    })
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})
