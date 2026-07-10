import assert from 'node:assert/strict'
import fsSync from 'node:fs'
import fs from 'node:fs/promises'
import test from 'node:test'
import { EventBroadcast } from '../src/persistence/event-store.js'
import { tempDirectory } from './helpers.js'

test('EventBroadcast isolates subscriber failures and durably replays ESN events', async t => {
  const eventsDir = await tempDirectory('codex-app-events-')
  t.after(() => fs.rm(eventsDir, { recursive: true, force: true }))
  const first = new EventBroadcast('task-events', eventsDir)
  const observed: number[] = []
  first.subscribe(() => { throw new Error('disconnected SSE') })
  first.subscribe(event => observed.push(event.seq || 0))
  first.emit({ type: 'assistant_text', task_id: 'task-events', content: 'one' })
  first.emit({ type: 'result', task_id: 'task-events', result: 'done' })
  await first.flush()
  assert.deepEqual(observed, [1, 2])

  const recovered = new EventBroadcast('task-events', eventsDir)
  recovered.loadFromDisk()
  assert.deepEqual(recovered.getEventsAfter(1).map(event => event.type), ['result'])
  assert.equal(recovered.getLatestSeq(), 2)
})

test('EventBroadcast closes live SSE subscribers at terminal', async t => {
  const eventsDir = await tempDirectory('codex-app-events-close-')
  t.after(() => fs.rm(eventsDir, { recursive: true, force: true }))
  const broadcast = new EventBroadcast('task-close', eventsDir)
  let closed = 0
  broadcast.subscribe(() => undefined, () => { closed++ })
  await broadcast.close()
  assert.equal(closed, 1)
  await broadcast.close()
  assert.equal(closed, 1)
})

test('EventBroadcast publishes only after the event is fsync durable and close preserves ordering', async t => {
  const eventsDir = await tempDirectory('codex-app-events-order-')
  t.after(() => fs.rm(eventsDir, { recursive: true, force: true }))
  const broadcast = new EventBroadcast('task-order', eventsDir)
  const observed: string[] = []
  broadcast.subscribe(event => {
    const files = fsSync.readdirSync(eventsDir)
    const journal = fsSync.readFileSync(`${eventsDir}/${files[0]}`, 'utf8')
    assert.match(journal, new RegExp(`"seq":${event.seq}`))
    observed.push(event.type)
  }, () => observed.push('closed'))

  broadcast.emit({ type: 'result', task_id: 'task-order', result: 'done' })
  assert.deepEqual(observed, [])
  await broadcast.close()
  assert.deepEqual(observed, ['result', 'closed'])
  assert.deepEqual(broadcast.getEventsAfter(0).map(event => event.type), ['result'])
})

test('EventBroadcast never exposes an event whose durable append failed', async t => {
  const eventsDir = await tempDirectory('codex-app-events-failure-')
  const broadcast = new EventBroadcast('task-failure', eventsDir)
  await fs.rm(eventsDir, { recursive: true, force: true })
  await fs.writeFile(eventsDir, 'not-a-directory')
  t.after(() => fs.rm(eventsDir, { force: true }))
  const observed: number[] = []
  broadcast.subscribe(event => observed.push(event.seq || 0))
  broadcast.emit({ type: 'result', task_id: 'task-failure', result: 'must-not-publish' })

  await assert.rejects(broadcast.flush())
  assert.deepEqual(observed, [])
  assert.deepEqual(broadcast.getEventsAfter(0), [])
  await assert.rejects(broadcast.close())
})

test('subscribeAfter atomically bridges replay into live delivery without duplicates', async t => {
  const eventsDir = await tempDirectory('codex-app-events-subscribe-')
  t.after(() => fs.rm(eventsDir, { recursive: true, force: true }))
  const broadcast = new EventBroadcast('task-subscribe', eventsDir)
  broadcast.emit({ type: 'assistant_text', task_id: 'task-subscribe', content: 'replay' })
  await broadcast.flush()
  const observed: number[] = []
  const unsubscribe = broadcast.subscribeAfter(0, event => {
    observed.push(event.seq || 0)
    if (event.seq === 1) {
      broadcast.emit({ type: 'assistant_text', task_id: 'task-subscribe', content: 'live' })
    }
  })
  await broadcast.flush()
  unsubscribe()
  assert.deepEqual(observed, [1, 2])
})

test('EventBroadcast ignores only a truncated final JSONL append during recovery', async t => {
  const eventsDir = await tempDirectory('codex-app-events-tail-')
  t.after(() => fs.rm(eventsDir, { recursive: true, force: true }))
  const first = new EventBroadcast('task-tail', eventsDir)
  first.emit({ type: 'assistant_text', task_id: 'task-tail', content: 'durable' })
  await first.flush()
  const files = await fs.readdir(eventsDir)
  await fs.appendFile(`${eventsDir}/${files[0]}`, '{"partial":')
  const recovered = new EventBroadcast('task-tail', eventsDir)
  assert.equal(recovered.loadFromDisk().length, 1)
  assert.equal(recovered.getEventsAfter(0)[0]?.content, 'durable')
  recovered.emit({ type: 'result', task_id: 'task-tail', result: 'after repair' })
  await recovered.close()

  const restarted = new EventBroadcast('task-tail', eventsDir)
  restarted.loadFromDisk()
  assert.deepEqual(restarted.getEventsAfter(0).map(event => event.seq), [1, 2])
  assert.equal(restarted.getEventsAfter(0).at(-1)?.result, 'after repair')
  const repaired = await fs.readFile(`${eventsDir}/${files[0]}`, 'utf8')
  assert.doesNotMatch(repaired, /\{"partial":/)
})
