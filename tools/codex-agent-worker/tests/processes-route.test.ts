import assert from 'node:assert/strict'
import test from 'node:test'
import { findActiveTaskBoundToProcess } from '../src/routes/processes.ts'

test('manual process route finds the active Worker task bound to the PID without mutating it', () => {
  const task = findActiveTaskBoundToProcess(321, [
    { taskId: 'task-other', pid: 123, status: 'running' },
    { taskId: 'task-target', pid: 321, status: 'running' },
  ])

  assert.deepEqual(task, { taskId: 'task-target', pid: 321, status: 'running' })
})

test('manual process route accepts a cancellation-pending task but ignores terminal or unrelated tasks', () => {
  const pending = findActiveTaskBoundToProcess(321, [
    { taskId: 'task-complete', pid: 321, status: 'completed' },
    { taskId: 'task-pending', pid: 321, status: 'cancel_requested' },
  ])
  const unrelated = findActiveTaskBoundToProcess(321, [
    { taskId: 'task-complete', pid: 321, status: 'completed' },
    { taskId: 'task-other', pid: 123, status: 'running' },
  ])

  assert.equal(pending?.taskId, 'task-pending')
  assert.equal(unrelated, undefined)
})

test('manual process route rejects ambiguous active PID bindings', () => {
  const ambiguous = findActiveTaskBoundToProcess(321, [
    { taskId: 'task-one', pid: 321, status: 'running' },
    { taskId: 'task-two', pid: 321, status: 'cancel_requested' },
  ])

  assert.equal(ambiguous, undefined)
})
