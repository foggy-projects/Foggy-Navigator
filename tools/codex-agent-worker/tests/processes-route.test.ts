import assert from 'node:assert/strict'
import test from 'node:test'
import { abortTaskBoundToProcess } from '../src/routes/processes.ts'

test('process termination aborts the running Worker task bound to the PID', () => {
  const calls: Array<{ taskId: string; reason: string }> = []
  const taskId = abortTaskBoundToProcess(321, [
    { taskId: 'task-other', pid: 123, status: 'running' },
    { taskId: 'task-target', pid: 321, status: 'running' },
  ], (candidateTaskId, reason) => {
    calls.push({ taskId: candidateTaskId, reason })
    return true
  })

  assert.equal(taskId, 'task-target')
  assert.deepEqual(calls, [{
    taskId: 'task-target',
    reason: 'Codex CLI process 321 was terminated',
  }])
})

test('process termination does not mutate completed or unrelated tasks', () => {
  let abortCount = 0
  const taskId = abortTaskBoundToProcess(321, [
    { taskId: 'task-complete', pid: 321, status: 'completed' },
    { taskId: 'task-other', pid: 123, status: 'running' },
  ], () => {
    abortCount += 1
    return true
  })

  assert.equal(taskId, undefined)
  assert.equal(abortCount, 0)
})
