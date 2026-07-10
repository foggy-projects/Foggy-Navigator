import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import { createTestEncryptionKey } from '../src/config.js'
import {
  IdempotencyConflictError,
  TaskStateConflictError,
  TaskStore,
} from '../src/persistence/task-store.js'
import { tempDirectory } from './helpers.js'

test('TaskStore atomically accepts concurrent identical requests once', async t => {
  const stateDir = await tempDirectory('codex-app-task-store-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const store = new TaskStore({ stateDir, encryptionKey: createTestEncryptionKey() })
  await store.initialize()

  const results = await Promise.all(Array.from({ length: 25 }, () =>
    store.accept('navigator-task-1', { prompt: 'inspect', api_key: 'sk-private' })
  ))

  assert.equal(results.filter(result => result.created).length, 1)
  assert.equal(results.every(result => result.record.task_id === 'navigator-task-1'), true)
  assert.deepEqual(store.getRequest('navigator-task-1'), { prompt: 'inspect', api_key: 'sk-private' })
  assert.equal((store as unknown as { locks: Map<string, unknown> }).locks.size, 0)
  const files = await fs.readdir(path.join(stateDir, 'tasks'))
  assert.equal(files.length, 1)
  const journal = await fs.readFile(path.join(stateDir, 'tasks', files[0]!), 'utf8')
  assert.doesNotMatch(journal, /inspect|sk-private/)
})

test('TaskStore returns the same task for canonical retries and rejects changed payloads', async t => {
  const stateDir = await tempDirectory('codex-app-task-conflict-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const store = new TaskStore({ stateDir, encryptionKey: createTestEncryptionKey() })
  await store.initialize()
  await store.accept('navigator-task-2', {
    prompt: 'same',
    codex_config: { z: 1, nested: { b: true, a: false } },
  })
  const retry = await store.accept('navigator-task-2', {
    codex_config: { nested: { a: false, b: true }, z: 1 },
    prompt: 'same',
  })
  assert.equal(retry.created, false)
  await assert.rejects(
    store.accept('navigator-task-2', { prompt: 'different' }),
    IdempotencyConflictError,
  )
})

test('TaskStore recovers committed state without decrypting or replaying the request', async t => {
  const stateDir = await tempDirectory('codex-app-task-recovery-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const first = new TaskStore({ stateDir, encryptionKey: createTestEncryptionKey() })
  await first.initialize()
  await first.accept('navigator-task-3', { prompt: 'side effect' })
  await first.transition('navigator-task-3', 'starting')
  await first.transition('navigator-task-3', 'committed', { thread_id: 'thread-3' })

  const recovered = new TaskStore({ stateDir, encryptionKey: createTestEncryptionKey() })
  await recovered.initialize()
  assert.equal(recovered.get('navigator-task-3')?.status, 'committed')
  assert.equal(recovered.get('navigator-task-3')?.thread_id, 'thread-3')
})

test('TaskStore ignores a truncated tail append and recovers the last fsynced state', async t => {
  const stateDir = await tempDirectory('codex-app-task-truncated-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const first = new TaskStore({ stateDir, encryptionKey: createTestEncryptionKey() })
  await first.initialize()
  await first.accept('navigator-task-tail', { prompt: 'safe' })
  await first.transition('navigator-task-tail', 'starting')
  const files = await fs.readdir(path.join(stateDir, 'tasks'))
  await fs.appendFile(path.join(stateDir, 'tasks', files[0]!), '{"partial":')

  const recovered = new TaskStore({ stateDir, encryptionKey: createTestEncryptionKey() })
  await recovered.initialize()
  assert.equal(recovered.get('navigator-task-tail')?.status, 'starting')
  await recovered.transition('navigator-task-tail', 'committed', { thread_id: 'thread-after-repair' })

  const restarted = new TaskStore({ stateDir, encryptionKey: createTestEncryptionKey() })
  await restarted.initialize()
  assert.equal(restarted.get('navigator-task-tail')?.status, 'committed')
  assert.equal(restarted.get('navigator-task-tail')?.thread_id, 'thread-after-repair')
  const repaired = await fs.readFile(path.join(stateDir, 'tasks', files[0]!), 'utf8')
  assert.doesNotMatch(repaired, /\{"partial":/)
})

test('TaskStore keeps the first proven terminal outcome immutable', async t => {
  const stateDir = await tempDirectory('codex-app-task-terminal-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const store = new TaskStore({ stateDir, encryptionKey: createTestEncryptionKey() })
  await store.initialize()
  await store.accept('navigator-task-terminal', { prompt: 'stop safely' })
  await store.transition('navigator-task-terminal', 'starting')
  const aborted = await store.transition('navigator-task-terminal', 'terminal', {
    outcome: 'aborted',
    error_code: 'TASK_ABORTED',
  })

  const retry = await store.transition('navigator-task-terminal', 'terminal', {
    outcome: 'aborted',
    error_code: 'TASK_ABORTED',
  })
  assert.deepEqual(retry, aborted)
  await assert.rejects(
    store.transition('navigator-task-terminal', 'terminal', { outcome: 'completed' }),
    TaskStateConflictError,
  )
  assert.equal(store.get('navigator-task-terminal')?.outcome, 'aborted')
  assert.equal(store.get('navigator-task-terminal')?.error_code, 'TASK_ABORTED')
})
