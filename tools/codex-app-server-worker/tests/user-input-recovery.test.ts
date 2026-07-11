import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import type { PendingUserInputInteraction } from '../src/models.js'
import { TaskStore } from '../src/persistence/task-store.js'
import { TaskManager, toPublicTask } from '../src/task-manager.js'
import type { ReconciliationResult } from '../src/app-server/executor.js'
import { FakeExecutor, tempDirectory, testConfig, waitFor } from './helpers.js'

test('restart clears an unanswerable durable interaction with a stable failure and releases its thread', async t => {
  const stateDir = await tempDirectory('codex-app-user-input-restart-')
  const config = testConfig(stateDir)
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const seed = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  await seed.initialize()
  await seed.accept('lost-input', { prompt: 'ask', session_id: 'thread-lost' })
  await seed.transition('lost-input', 'starting')
  await seed.transition('lost-input', 'committed', { thread_id: 'thread-lost' })
  await seed.transition('lost-input', 'running', {
    thread_id: 'thread-lost', turn_id: 'turn-lost', app_server_instance_id: 'runtime-lost',
  })
  await seed.patch('lost-input', { pending_interaction: pendingInteraction('lost-request', 'thread-lost', 'turn-lost', 'runtime-lost') })

  const recoveredStore = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const manager = new TaskManager(config, recoveredStore, new FakeExecutor())
  await manager.initialize()

  const lost = manager.get('lost-input')!
  assert.equal(lost.status, 'terminal')
  assert.equal(lost.error_code, 'USER_INPUT_CHANNEL_LOST')
  assert.equal(lost.pending_interaction, undefined)
  assert.equal(toPublicTask(lost).pending_interaction, undefined)
  const events = manager.getBroadcast('lost-input').getEventsAfter(0)
  assert.equal(events.some(event => event.type === 'user_input_resolved'
    && (event.data as Record<string, unknown>).reason === 'cleared'), true)
  assert.equal(events.some(event => event.type === 'error' && event.subtype === 'USER_INPUT_CHANNEL_LOST'), true)

  await manager.accept('after-lost-input', { prompt: 'continue', session_id: 'thread-lost' })
  await waitFor(() => manager.get('after-lost-input')?.status === 'terminal')
})

test('legacy duplicate nonterminal thread journals fail only the later owner instead of blocking startup', async t => {
  const stateDir = await tempDirectory('codex-app-legacy-thread-conflict-')
  const config = testConfig(stateDir)
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  let now = Date.parse('2026-01-01T00:00:00.000Z')
  const seed = new TaskStore({
    stateDir,
    encryptionKey: config.stateEncryptionKey!,
    now: () => new Date(now++),
  })
  await seed.initialize()
  await seed.accept('legacy-first', { prompt: 'first', session_id: 'legacy-thread' })
  await seed.accept('legacy-second', { prompt: 'second', session_id: 'legacy-thread' })
  await removeRequestedThreadIdsFromJournals(stateDir)

  const recoveredStore = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  await recoveredStore.initialize()
  assert.equal(recoveredStore.get('legacy-first')?.requested_thread_id, undefined)
  assert.equal(recoveredStore.get('legacy-first')?.thread_id, undefined)
  assert.equal(recoveredStore.get('legacy-second')?.requested_thread_id, undefined)
  assert.equal(recoveredStore.get('legacy-second')?.thread_id, undefined)
  assert.equal(recoveredStore.getRequest('legacy-first').session_id, 'legacy-thread')
  assert.equal(recoveredStore.getRequest('legacy-second').session_id, 'legacy-thread')
  const manager = new TaskManager(config, recoveredStore, new FakeExecutor())
  await manager.initialize({ resume: false })

  assert.equal(manager.get('legacy-first')?.status, 'accepted')
  assert.equal(manager.get('legacy-second')?.status, 'terminal')
  assert.equal(manager.get('legacy-second')?.error_code, 'APP_SERVER_THREAD_ACTIVE_RECOVERY')
  assert.equal(manager.getBroadcast('legacy-second').getEventsAfter(0)
    .some(event => event.subtype === 'APP_SERVER_THREAD_ACTIVE_RECOVERY'), true)

  const aborted = await manager.abort('legacy-first')
  assert.equal(aborted?.abort_status, 'aborted')
  await manager.accept('legacy-third', { prompt: 'third', session_id: 'legacy-thread' })
  await waitFor(() => manager.get('legacy-third')?.status === 'terminal')
})

test('reconciliation terminal outcome releases a rebuilt thread reservation', async t => {
  const stateDir = await tempDirectory('codex-app-reconcile-thread-release-')
  const config = testConfig(stateDir)
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const seed = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  await seed.initialize()
  await seed.accept('recover-owner', { prompt: 'old', session_id: 'recover-thread' })
  await seed.transition('recover-owner', 'starting')
  await seed.transition('recover-owner', 'committed', { thread_id: 'recover-thread' })
  await seed.transition('recover-owner', 'running', { thread_id: 'recover-thread', turn_id: 'recover-turn' })

  const recoveredStore = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const manager = new TaskManager(config, recoveredStore, new CompletedRecoveryExecutor())
  await manager.initialize()
  assert.equal(manager.get('recover-owner')?.outcome, 'completed')

  await manager.accept('recover-next', { prompt: 'next', session_id: 'recover-thread' })
  await waitFor(() => manager.get('recover-next')?.status === 'terminal')
})

class CompletedRecoveryExecutor extends FakeExecutor {
  async reconcile(): Promise<ReconciliationResult> {
    return {
      status: 'completed',
      threadId: 'recover-thread',
      turnId: 'recover-turn',
      assistantText: 'recovered',
      model: 'gpt-5.6-sol',
    }
  }
}

function pendingInteraction(
  requestId: string,
  threadId: string,
  turnId: string,
  runtimeInstanceId: string,
): PendingUserInputInteraction {
  return {
    contract_version: 1,
    request_id: requestId,
    method: 'item/tool/requestUserInput',
    thread_id: threadId,
    turn_id: turnId,
    item_id: 'item-lost',
    questions: [{
      id: 'secret', header: 'Secret', question: 'Private?',
      is_other: true, is_secret: true,
    }],
    runtime_instance_id: runtimeInstanceId,
    created_at: new Date(0).toISOString(),
  }
}

async function removeRequestedThreadIdsFromJournals(stateDir: string): Promise<void> {
  const taskDirectory = path.join(stateDir, 'tasks')
  for (const name of await fs.readdir(taskDirectory)) {
    if (!name.endsWith('.jsonl')) continue
    const journalPath = path.join(taskDirectory, name)
    const records = (await fs.readFile(journalPath, 'utf8'))
      .split('\n')
      .filter(Boolean)
      .map(line => JSON.parse(line) as Record<string, unknown>)
    for (const record of records) delete record.requested_thread_id
    await fs.writeFile(journalPath, `${records.map(record => JSON.stringify(record)).join('\n')}\n`, 'utf8')
  }
}
