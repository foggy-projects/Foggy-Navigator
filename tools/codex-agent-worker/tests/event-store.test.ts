import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'fs'
import path from 'path'
import { EventBroadcast } from '../src/persistence/event-store.ts'

const eventsDir = path.resolve(process.cwd(), 'logs', 'events')

test('EventBroadcast flush persists queued events to disk', async () => {
  const taskId = `test-flush-${Date.now()}`
  const broadcast = new EventBroadcast(taskId)
  const filePath = path.join(eventsDir, `${taskId}.jsonl`)

  broadcast.emit({
    type: 'assistant_text',
    task_id: taskId,
    content: 'hello',
    seq: 1,
  })

  await broadcast.flush()

  const content = fs.readFileSync(filePath, 'utf8')
  assert.match(content, /"content":"hello"/)

  fs.unlinkSync(filePath)
})

test('EventBroadcast cleanup removes persisted log after queued writes complete', async () => {
  const taskId = `test-cleanup-${Date.now()}`
  const broadcast = new EventBroadcast(taskId)
  const filePath = path.join(eventsDir, `${taskId}.jsonl`)

  broadcast.emit({
    type: 'assistant_text',
    task_id: taskId,
    content: 'cleanup',
    seq: 1,
  })
  broadcast.cleanup()
  await broadcast.flush()

  for (let i = 0; i < 20 && fs.existsSync(filePath); i++) {
    await new Promise(resolve => setTimeout(resolve, 25))
  }

  assert.equal(fs.existsSync(filePath), false)
})
