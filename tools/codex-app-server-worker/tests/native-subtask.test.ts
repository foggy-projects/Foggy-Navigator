import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import test from 'node:test'
import { AppServerEventBridge } from '../src/app-server/event-bridge.js'
import { EventBroadcast } from '../src/persistence/event-store.js'
import { tempDirectory } from './helpers.js'

test('native child lifecycle is projected while child output and failures remain private', async t => {
  const eventsDir = await tempDirectory('codex-app-native-')
  t.after(() => fs.rm(eventsDir, { recursive: true, force: true }))
  const broadcast = new EventBroadcast('native-task', eventsDir)
  const bridge = new AppServerEventBridge({ taskId: 'native-task', broadcast, rootThreadId: 'root' })
  bridge.setRootTurnId('root-turn')
  bridge.handle({
    method: 'item/completed',
    params: {
      threadId: 'root',
      turnId: 'root-turn',
      completedAtMs: 1_000,
      item: { type: 'subAgentActivity', kind: 'started', agentThreadId: 'child', agentPath: '/root/reviewer' },
    },
  })
  bridge.handle({
    method: 'item/agentMessage/delta',
    params: { threadId: 'child', itemId: 'private', delta: 'secret child result' },
  })
  bridge.handle({
    method: 'turn/completed',
    params: { threadId: 'child', turn: { status: 'failed', error: { message: 'Bearer secret' } } },
  })
  bridge.handle({
    method: 'item/completed',
    params: {
      threadId: 'root',
      turnId: 'root-turn',
      completedAtMs: 1_500,
      item: {
        type: 'subAgentActivity', kind: 'started', agentThreadId: 'sensitive-child',
        agentPath: 'C:\\private\\sk-PRIVATE_LABEL_SENTINEL',
      },
    },
  })
  bridge.handle({
    method: 'thread/started',
    params: {
      thread: {
        id: 'sensitive-child', parentThreadId: 'root',
        agentNickname: 'https://PRIVATE_NICKNAME_SENTINEL.example',
        agentRole: 'Bearer PRIVATE_ROLE_SENTINEL',
        status: { type: 'active' },
      },
    },
  })
  await broadcast.flush()
  const serialized = JSON.stringify(broadcast.getEventsAfter(0))
  assert.match(serialized, /native_subtask_update/)
  assert.match(serialized, /NATIVE_SUBTASK_FAILED/)
  assert.match(serialized, /reviewer/)
  assert.doesNotMatch(serialized, /secret child result|Bearer secret|PRIVATE_LABEL_SENTINEL|PRIVATE_NICKNAME_SENTINEL|PRIVATE_ROLE_SENTINEL/)
})

test('turn-scoped output is rejected until the current root turn id is correlated', async t => {
  const eventsDir = await tempDirectory('codex-app-native-correlation-')
  t.after(() => fs.rm(eventsDir, { recursive: true, force: true }))
  const broadcast = new EventBroadcast('correlation-task', eventsDir)
  const bridge = new AppServerEventBridge({ taskId: 'correlation-task', broadcast, rootThreadId: 'root' })
  bridge.handle({
    method: 'item/agentMessage/delta',
    params: { threadId: 'root', turnId: 'previous-turn', itemId: 'stale', delta: 'STALE_OUTPUT' },
  })
  bridge.setRootTurnId('current-turn')
  bridge.handle({
    method: 'item/agentMessage/delta',
    params: { threadId: 'root', turnId: 'current-turn', itemId: 'current', delta: 'current output' },
  })
  await broadcast.flush()
  const serialized = JSON.stringify(broadcast.getEventsAfter(0))
  assert.match(serialized, /current output/)
  assert.doesNotMatch(serialized, /STALE_OUTPUT/)
})

test('root message deltas remain transient and item completion emits one durable full message', async t => {
  const eventsDir = await tempDirectory('codex-app-message-stream-')
  t.after(() => fs.rm(eventsDir, { recursive: true, force: true }))
  const broadcast = new EventBroadcast('message-stream-task', eventsDir)
  const bridge = new AppServerEventBridge({
    taskId: 'message-stream-task',
    broadcast,
    rootThreadId: 'root',
  })
  bridge.setRootTurnId('root-turn')

  bridge.handle({
    method: 'item/agentMessage/delta',
    params: { threadId: 'root', turnId: 'root-turn', itemId: 'message-1', delta: '_CHAIN_OK' },
  })
  const completed = {
    method: 'item/completed',
    params: {
      threadId: 'root',
      turnId: 'root-turn',
      item: { id: 'message-1', type: 'agentMessage', text: 'B_FULL_CHAIN_OK' },
    },
  } as const
  bridge.handle(completed)
  bridge.handle(completed)

  await broadcast.flush()
  const messages = broadcast.getEventsAfter(0).filter(event => event.type === 'assistant_text')
  assert.deepEqual(messages.map(event => ({ subtype: event.subtype, content: event.content, streamId: event.stream_id })), [
    { subtype: 'text_delta', content: '_CHAIN_OK', streamId: 'message-1' },
    { subtype: undefined, content: 'B_FULL_CHAIN_OK', streamId: 'message-1' },
  ])
  assert.equal(bridge.getResult().assistantText, 'B_FULL_CHAIN_OK')
})

test('turn result uses the latest canonical agent message instead of concatenating progress text', async t => {
  const eventsDir = await tempDirectory('codex-app-final-message-')
  t.after(() => fs.rm(eventsDir, { recursive: true, force: true }))
  const broadcast = new EventBroadcast('final-message-task', eventsDir)
  const bridge = new AppServerEventBridge({
    taskId: 'final-message-task',
    broadcast,
    rootThreadId: 'root',
  })
  bridge.setRootTurnId('root-turn')

  for (const [itemId, text] of [
    ['progress-message', 'Delegating the bounded check now.'],
    ['final-message', 'FINAL_STREAM_OK'],
  ] as const) {
    bridge.handle({
      method: 'item/agentMessage/delta',
      params: { threadId: 'root', turnId: 'root-turn', itemId, delta: text },
    })
    bridge.handle({
      method: 'item/completed',
      params: {
        threadId: 'root',
        turnId: 'root-turn',
        item: { id: itemId, type: 'agentMessage', text },
      },
    })
  }

  await broadcast.flush()
  assert.equal(bridge.getResult().assistantText, 'FINAL_STREAM_OK')
  assert.deepEqual(
    broadcast.getEventsAfter(0)
      .filter(event => event.type === 'assistant_text' && event.subtype !== 'text_delta')
      .map(event => event.content),
    ['Delegating the bounded check now.', 'FINAL_STREAM_OK'],
  )
})

test('reused instances ignore stale roots and never persist reasoning summaries', async t => {
  const eventsDir = await tempDirectory('codex-app-native-stale-')
  t.after(() => fs.rm(eventsDir, { recursive: true, force: true }))
  const broadcast = new EventBroadcast('native-stale-task', eventsDir)
  const bridge = new AppServerEventBridge({ taskId: 'native-stale-task', broadcast, rootThreadId: 'current-root' })
  bridge.setRootTurnId('current-turn')
  bridge.handle({
    method: 'item/reasoning/summaryTextDelta',
    params: { threadId: 'current-root', delta: 'PRIVATE_REASONING_SENTINEL' },
  })
  bridge.handle({
    method: 'item/completed',
    params: {
      threadId: 'previous-root',
      item: { type: 'subAgentActivity', kind: 'started', agentThreadId: 'stale-child' },
    },
  })
  bridge.handle({
    method: 'item/completed',
    params: {
      threadId: 'current-root',
      turnId: 'previous-turn',
      item: { type: 'subAgentActivity', kind: 'started', agentThreadId: 'same-thread-stale-child' },
    },
  })
  bridge.handle({
    method: 'thread/started',
    params: { thread: { id: 'stale-child-2', parentThreadId: 'previous-root' } },
  })
  bridge.handle({
    method: 'turn/completed',
    params: { threadId: 'stale-child', turn: { status: 'failed', error: { message: 'STALE_SECRET' } } },
  })

  assert.equal(broadcast.getEventCount(), 0)
  await broadcast.flush()
  const journals = await fs.readdir(eventsDir)
  for (const journal of journals) {
    const content = await fs.readFile(`${eventsDir}/${journal}`, 'utf8')
    assert.doesNotMatch(content, /PRIVATE_REASONING_SENTINEL|STALE_SECRET/)
  }
})
