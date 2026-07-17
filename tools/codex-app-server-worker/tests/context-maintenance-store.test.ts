import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import { parseContextUsageNotification } from '../src/app-server/executor.js'
import {
  ContextCompactOperationConflictError,
  ContextMaintenanceStore,
} from '../src/persistence/context-maintenance-store.js'
import { tempDirectory } from './helpers.js'

test('context usage uses last.totalTokens and preserves an unknown model window', () => {
  const snapshot = parseContextUsageNotification({
    method: 'thread/tokenUsage/updated',
    params: {
      threadId: 'thread-usage',
      turnId: 'turn-usage',
      tokenUsage: {
        total: { totalTokens: 987_654 },
        last: { totalTokens: 81_234 },
        modelContextWindow: null,
      },
    },
  }, new Date('2026-07-17T01:02:03.000Z'))

  assert.deepEqual(snapshot, {
    schema_version: 1,
    thread_id: 'thread-usage',
    turn_id: 'turn-usage',
    observed_at: '2026-07-17T01:02:03.000Z',
    last_total_tokens: 81_234,
    model_context_window: undefined,
    remaining_tokens: undefined,
    status: 'window_unknown',
  })
})

test('context usage never treats cumulative totals as current context', () => {
  const snapshot = parseContextUsageNotification({
    method: 'thread/tokenUsage/updated',
    params: {
      threadId: 'thread-cumulative-only',
      tokenUsage: {
        total: { totalTokens: 200_000 },
        modelContextWindow: 270_000,
      },
    },
  })

  assert.equal(snapshot?.last_total_tokens, undefined)
  assert.equal(snapshot?.remaining_tokens, undefined)
  assert.equal(snapshot?.status, 'unknown')
})

test('context maintenance persists usage and recovers an interrupted compact as unknown', async t => {
  const stateDir = await tempDirectory('codex-app-context-maintenance-store-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const first = new ContextMaintenanceStore(stateDir, () => new Date('2026-07-17T02:00:00.000Z'))
  await first.initialize()
  await first.recordUsage({
    schema_version: 1,
    thread_id: 'thread-store',
    turn_id: 'turn-store',
    observed_at: '2026-07-17T01:59:00.000Z',
    last_total_tokens: 42_000,
    model_context_window: 270_000,
    remaining_tokens: 228_000,
    status: 'known',
  })
  await first.startOperation('task-store', 'thread-store', 'compact-store-1')

  const restarted = new ContextMaintenanceStore(stateDir, () => new Date('2026-07-17T02:01:00.000Z'))
  await restarted.initialize()
  assert.equal(restarted.getUsage('thread-store')?.last_total_tokens, 42_000)
  assert.deepEqual(restarted.getOperation('compact-store-1'), {
    schema_version: 1,
    operation_id: 'compact-store-1',
    task_id: 'task-store',
    thread_id: 'thread-store',
    status: 'unknown',
    error_code: 'APP_SERVER_COMPACT_RECOVERY_REQUIRED',
    created_at: '2026-07-17T02:00:00.000Z',
    updated_at: '2026-07-17T02:01:00.000Z',
  })
  assert.equal((await fs.stat(path.join(stateDir, 'context-maintenance'))).mode & 0o777, 0o700)
  assert.equal((await fs.stat(path.join(stateDir, 'context-maintenance', 'operations.jsonl'))).mode & 0o777, 0o600)
})

test('compact operation identity cannot be rebound to another task or thread', async t => {
  const stateDir = await tempDirectory('codex-app-context-operation-conflict-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const store = new ContextMaintenanceStore(stateDir)
  await store.initialize()
  await store.startOperation('task-one', 'thread-one', 'compact-conflict')

  await assert.rejects(
    store.startOperation('task-two', 'thread-two', 'compact-conflict'),
    ContextCompactOperationConflictError,
  )
})

test('failed durable append does not publish a ghost compact operation in memory', async t => {
  const stateDir = await tempDirectory('codex-app-context-operation-durability-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const store = new ContextMaintenanceStore(stateDir)
  await store.initialize()
  const maintenanceDir = path.join(stateDir, 'context-maintenance')
  await fs.rm(maintenanceDir, { recursive: true, force: true })
  await fs.writeFile(maintenanceDir, 'blocks-jsonl-directory', { mode: 0o600 })

  await assert.rejects(
    store.startOperation('task-durable', 'thread-durable', 'compact-durable'),
  )
  assert.equal(store.getOperation('compact-durable'), undefined)

  await fs.rm(maintenanceDir, { force: true })
  await fs.mkdir(maintenanceDir, { mode: 0o700 })
  const operation = await store.startOperation(
    'task-durable', 'thread-durable', 'compact-durable',
  )
  assert.equal(operation.status, 'running')

  const restarted = new ContextMaintenanceStore(stateDir)
  await restarted.initialize()
  assert.equal(restarted.getOperation('compact-durable')?.status, 'unknown')
})
