import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import { TaskStore } from '../src/persistence/task-store.js'
import { TaskManager } from '../src/task-manager.js'
import { EventBroadcast } from '../src/persistence/event-store.js'
import { FakeExecutor, tempDirectory, testConfig, waitFor } from './helpers.js'

test('TaskManager persists committed before executor side effects and executes an accepted task once', async t => {
  const stateDir = await tempDirectory('codex-app-manager-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const config = testConfig(stateDir)
  const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const executor = new FakeExecutor(() => store.get('task-once')?.status)
  const manager = new TaskManager(config, store, executor)
  await manager.initialize()

  const first = await manager.accept('task-once', { prompt: 'change a file', model: 'codex-ultra' })
  const retry = await manager.accept('task-once', { prompt: 'change a file', model: 'codex-ultra' })
  assert.equal(first.created, true)
  assert.equal(retry.created, false)
  await waitFor(() => manager.get('task-once')?.status === 'terminal')
  assert.equal(executor.calls, 1)
  assert.equal(executor.sideEffects, 1)
  assert.equal(executor.stateAtSideEffect, 'committed')
  assert.equal(manager.get('task-once')?.outcome, 'completed')
  const events = manager.getBroadcast('task-once').getEventsAfter(0)
  const committedIndex = events.findIndex(event => event.subtype === 'execution_committed')
  const resultIndex = events.findIndex(event => event.type === 'result')
  assert.ok(committedIndex >= 0)
  assert.ok(resultIndex > committedIndex)
  const durable = new EventBroadcast('task-once', path.join(stateDir, 'events'))
  durable.loadFromDisk()
  assert.ok(durable.getEventsAfter(0).some(event => event.subtype === 'execution_committed'))
})

test('TaskManager resumes accepted work but never replays committed work after restart', async t => {
  const stateDir = await tempDirectory('codex-app-manager-recovery-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const config = testConfig(stateDir)
  const seed = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  await seed.initialize()
  await seed.accept('accepted-task', { prompt: 'safe to start' })
  await seed.accept('committed-task', { prompt: 'must not replay' })
  await seed.transition('committed-task', 'starting')
  await seed.transition('committed-task', 'committed', { thread_id: 'thread-existing' })

  const recoveredStore = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const executor = new FakeExecutor()
  const manager = new TaskManager(config, recoveredStore, executor)
  await manager.initialize()
  await waitFor(() => manager.get('accepted-task')?.status === 'terminal')

  assert.equal(executor.calls, 1)
  assert.equal(manager.get('committed-task')?.status, 'committed')
  assert.equal(manager.get('committed-task')?.recovery_required, true)
})

test('TaskManager fails startup closed when the durable encryption key is wrong', async t => {
  const stateDir = await tempDirectory('codex-app-manager-key-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const config = testConfig(stateDir)
  const seed = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  await seed.initialize()
  await seed.accept('encrypted-task', { prompt: 'private prompt', api_key: 'sk-private' })

  const wrongKey = Buffer.alloc(32, 7)
  const recovered = new TaskStore({ stateDir, encryptionKey: wrongKey })
  const executor = new FakeExecutor()
  const manager = new TaskManager(config, recovered, executor)
  await assert.rejects(manager.initialize(), /authenticate data|authentication|Unsupported state/i)
  assert.equal(executor.calls, 0)
})

test('TaskManager resumes recovery-required accepted work exactly once after readiness returns', async t => {
  const stateDir = await tempDirectory('codex-app-manager-readiness-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const config = testConfig(stateDir)
  const seed = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  await seed.initialize()
  await seed.accept('degraded-accepted', { prompt: 'resume after readiness' })

  const recoveredStore = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const executor = new FakeExecutor()
  const manager = new TaskManager(config, recoveredStore, executor)
  await manager.initialize({ resume: false })
  assert.equal(manager.get('degraded-accepted')?.recovery_required, true)

  await Promise.all([manager.resumeRecoverableTasks(), manager.resumeRecoverableTasks()])
  await waitFor(() => manager.get('degraded-accepted')?.status === 'terminal')
  await manager.resumeRecoverableTasks()
  assert.equal(executor.calls, 1)
  assert.equal(manager.get('degraded-accepted')?.outcome, 'completed')
})

test('TaskManager finalizes a durable pre-commit abort after restart without executing the task', async t => {
  const stateDir = await tempDirectory('codex-app-manager-abort-restart-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const config = testConfig(stateDir)
  const seed = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  await seed.initialize()
  await seed.accept('aborted-before-start', { prompt: 'must never execute' })
  await seed.requestAbort('aborted-before-start')

  const recoveredStore = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const executor = new FakeExecutor()
  const manager = new TaskManager(config, recoveredStore, executor)
  await manager.initialize()

  assert.equal(executor.calls, 0)
  assert.equal(manager.get('aborted-before-start')?.status, 'terminal')
  assert.equal(manager.get('aborted-before-start')?.outcome, 'aborted')
  assert.equal(manager.get('aborted-before-start')?.error_code, 'TASK_ABORTED')
})

test('TaskManager removes crash-left materialized image plaintext during startup recovery', async t => {
  const stateDir = await tempDirectory('codex-app-manager-input-cleanup-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const config = testConfig(stateDir)
  const seed = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  await seed.initialize()
  await seed.accept('image-residue', { prompt: 'inspect image' })
  const inputRoot = path.join(
    stateDir,
    'input',
    crypto.createHash('sha256').update('image-residue').digest('hex'),
  )
  await fs.mkdir(inputRoot, { recursive: true })
  await fs.writeFile(path.join(inputRoot, '0.png'), 'plaintext-image-bytes')

  const recoveredStore = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const manager = new TaskManager(config, recoveredStore, new FakeExecutor())
  await manager.initialize({ resume: false })

  await assert.rejects(fs.access(inputRoot), /ENOENT/)
})

test('TaskManager converges a crash-left tombstone by purging its durable event journal', async t => {
  const stateDir = await tempDirectory('codex-app-manager-tombstone-recovery-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const config = testConfig(stateDir)
  const seed = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  await seed.initialize()
  await seed.accept('tombstone-residue', { prompt: 'sensitive request' })
  await seed.transition('tombstone-residue', 'terminal', { outcome: 'completed' })
  await seed.tombstoneTerminal('tombstone-residue')
  const eventsDir = path.join(stateDir, 'events')
  const residue = new EventBroadcast('tombstone-residue', eventsDir)
  residue.emit({ type: 'result', task_id: 'tombstone-residue', result: 'sensitive result' })
  await residue.close()
  assert.equal((await fs.readdir(eventsDir)).length, 1)

  const recoveredStore = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const manager = new TaskManager(config, recoveredStore, new FakeExecutor())
  await manager.initialize()

  assert.deepEqual(await fs.readdir(eventsDir), [])
  assert.deepEqual(manager.getBroadcast('tombstone-residue').getEventsAfter(0), [])
})

test('terminal cleanup removes materialized input together with the event journal', async t => {
  const stateDir = await tempDirectory('codex-app-manager-delete-cleanup-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const config = testConfig(stateDir)
  const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  await store.initialize()
  await store.accept('delete-cleanup', { prompt: 'sensitive request' })
  await store.transition('delete-cleanup', 'terminal', { outcome: 'completed' })
  const events = new EventBroadcast('delete-cleanup', path.join(stateDir, 'events'))
  events.emit({ type: 'result', task_id: 'delete-cleanup', result: 'sensitive result' })
  await events.close()
  const inputRoot = path.join(
    stateDir,
    'input',
    crypto.createHash('sha256').update('delete-cleanup').digest('hex'),
  )
  await fs.mkdir(inputRoot, { recursive: true })
  await fs.writeFile(path.join(inputRoot, '0.png'), 'plaintext-image-bytes')
  const manager = new TaskManager(config, store, new FakeExecutor())

  const tombstone = await manager.cleanupTerminal('delete-cleanup')

  assert.ok(tombstone?.tombstoned_at)
  await assert.rejects(fs.access(inputRoot), /ENOENT/)
  assert.deepEqual(await fs.readdir(path.join(stateDir, 'events')), [])
})
