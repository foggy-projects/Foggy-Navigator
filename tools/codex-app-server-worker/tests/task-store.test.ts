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

test('TaskStore keeps large terminal payloads durable but only retains bounded summaries across restart', async t => {
  const stateDir = await tempDirectory('codex-app-task-bounded-terminal-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const encryptionKey = createTestEncryptionKey()
  const store = new TaskStore({ stateDir, encryptionKey })
  await store.initialize()
  const taskCount = 128
  const largePromptBody = 'x'.repeat(32 * 1024)

  for (let index = 0; index < taskCount; index++) {
    const taskId = `bounded-terminal-${index}`
    await store.accept(taskId, { prompt: `${index}:${largePromptBody}`, api_key: `private-${index}` })
    await store.transition(taskId, 'terminal', { outcome: 'completed' })
  }

  const resident = store.list()
  assert.equal(resident.length, taskCount)
  assert.equal(resident.every(record => record.status === 'terminal' && record.request_payload === undefined), true)
  assert.ok(JSON.stringify(resident).length < 256 * 1024)
  const journals = await fs.readdir(path.join(stateDir, 'tasks'))
  const firstJournal = await fs.readFile(path.join(stateDir, 'tasks', journals[0]!), 'utf8')
  assert.match(firstJournal, /"request_payload"/)
  assert.match(firstJournal, /"ciphertext"/)

  const retry = await store.accept('bounded-terminal-0', {
    prompt: `0:${largePromptBody}`,
    api_key: 'private-0',
  })
  assert.equal(retry.created, false)
  assert.equal(retry.record.status, 'terminal')
  assert.equal(retry.record.request_payload, undefined)
  await assert.rejects(
    store.accept('bounded-terminal-0', { prompt: 'different' }),
    IdempotencyConflictError,
  )

  const restarted = new TaskStore({ stateDir, encryptionKey })
  await restarted.initialize()
  const restartedResident = restarted.list()
  assert.equal(restartedResident.length, taskCount)
  assert.equal(restartedResident.every(record => record.request_payload === undefined), true)
  assert.ok(JSON.stringify(restartedResident).length < 256 * 1024)
  const restartedRetry = await restarted.accept('bounded-terminal-0', {
    prompt: `0:${largePromptBody}`,
    api_key: 'private-0',
  })
  assert.equal(restartedRetry.created, false)
  assert.equal(restartedRetry.record.request_payload, undefined)
})

test('TaskStore persists request ciphertext once instead of amplifying it on every state append', async t => {
  const stateDir = await tempDirectory('codex-app-task-journal-amplification-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const encryptionKey = createTestEncryptionKey()
  const store = new TaskStore({ stateDir, encryptionKey })
  await store.initialize()
  const taskId = 'bounded-journal-task'
  const request = {
    prompt: 'inspect a large image without duplicating it in the state journal',
    images: [{ name: 'large.png', data: 'A'.repeat(1024 * 1024), mime_type: 'image/png' }],
  }
  await store.accept(taskId, request)
  const [journalName] = await fs.readdir(path.join(stateDir, 'tasks'))
  const journalPath = path.join(stateDir, 'tasks', journalName!)
  const acceptedSize = (await fs.stat(journalPath)).size

  for (let index = 0; index < 12; index++) {
    await store.patch(taskId, { thread_id: `thread-${index}` })
  }
  await store.transition(taskId, 'starting')
  const activeSize = (await fs.stat(journalPath)).size
  assert.ok(activeSize <= acceptedSize + 128 * 1024)
  const activeJournal = await fs.readFile(journalPath, 'utf8')
  assert.equal([...activeJournal.matchAll(/"ciphertext"/g)].length, 1)

  const restarted = new TaskStore({ stateDir, encryptionKey })
  await restarted.initialize()
  assert.deepEqual(restarted.getRequest(taskId), request)
  assert.equal(restarted.get(taskId)?.thread_id, 'thread-11')
  await restarted.transition(taskId, 'terminal', { outcome: 'completed' })
  assert.equal(restarted.get(taskId)?.request_payload, undefined)
  const terminalJournal = await fs.readFile(journalPath, 'utf8')
  assert.equal([...terminalJournal.matchAll(/"ciphertext"/g)].length, 1)

  const finalRestart = new TaskStore({ stateDir, encryptionKey })
  await finalRestart.initialize()
  assert.equal(finalRestart.get(taskId)?.status, 'terminal')
  assert.equal(finalRestart.get(taskId)?.request_payload, undefined)
  const retry = await finalRestart.accept(taskId, request)
  assert.equal(retry.created, false)
  await finalRestart.tombstoneTerminal(taskId)
  assert.doesNotMatch(await fs.readFile(journalPath, 'utf8'), /ciphertext/)
  assert.equal((await finalRestart.accept(taskId, request)).created, false)
})

test('TaskStore loads legacy full snapshots and writes subsequent state without another ciphertext copy', async t => {
  const stateDir = await tempDirectory('codex-app-task-legacy-journal-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const encryptionKey = createTestEncryptionKey()
  const seed = new TaskStore({ stateDir, encryptionKey })
  await seed.initialize()
  const taskId = 'legacy-full-snapshot'
  const request = { prompt: 'legacy encrypted request', api_key: 'legacy-private' }
  await seed.accept(taskId, request)
  const [journalName] = await fs.readdir(path.join(stateDir, 'tasks'))
  const journalPath = path.join(stateDir, 'tasks', journalName!)
  const initial = JSON.parse((await fs.readFile(journalPath, 'utf8')).trim()) as Record<string, unknown>
  await fs.appendFile(journalPath, `${JSON.stringify({
    ...initial,
    status: 'starting',
    updated_at: new Date().toISOString(),
  })}\n`)

  const upgraded = new TaskStore({ stateDir, encryptionKey })
  await upgraded.initialize()
  assert.equal(upgraded.get(taskId)?.status, 'starting')
  assert.deepEqual(upgraded.getRequest(taskId), request)
  await upgraded.patch(taskId, { thread_id: 'thread-after-upgrade' })
  const upgradedJournal = await fs.readFile(journalPath, 'utf8')
  assert.equal([...upgradedJournal.matchAll(/"ciphertext"/g)].length, 2)
  assert.match(upgradedJournal.split('\n').at(-2) || '', /"request_payload_persisted":true/)

  const restarted = new TaskStore({ stateDir, encryptionKey })
  await restarted.initialize()
  assert.equal(restarted.get(taskId)?.thread_id, 'thread-after-upgrade')
  assert.deepEqual(restarted.getRequest(taskId), request)
})
