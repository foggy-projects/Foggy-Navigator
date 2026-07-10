import assert from 'node:assert/strict'
import test from 'node:test'
import { KeyedExecutionLocks } from '../src/app-server/execution-locks.js'

test('keyed execution lock serializes a thread and releases queued work in order', async () => {
  const locks = new KeyedExecutionLocks()
  const first = await locks.acquire('thread:one')
  let secondAcquired = false
  const secondPromise = locks.acquire('thread:one').then(release => {
    secondAcquired = true
    return release
  })
  await new Promise(resolve => setTimeout(resolve, 5))
  assert.equal(secondAcquired, false)
  assert.deepEqual(locks.metrics(), { active_keys: 1, waiting: 1 })
  first()
  const second = await secondPromise
  assert.equal(secondAcquired, true)
  second()
  assert.deepEqual(locks.metrics(), { active_keys: 0, waiting: 0 })
})

test('aborted lock waiter is removed without affecting the current holder', async () => {
  const locks = new KeyedExecutionLocks()
  const first = await locks.acquire('cwd:one')
  const controller = new AbortController()
  const waiting = locks.acquire('cwd:one', controller.signal)
  controller.abort()
  await assert.rejects(waiting, { name: 'AbortError' })
  assert.deepEqual(locks.metrics(), { active_keys: 1, waiting: 0 })
  first()
  assert.deepEqual(locks.metrics(), { active_keys: 0, waiting: 0 })
})
