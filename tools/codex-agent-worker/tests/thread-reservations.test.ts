import assert from 'node:assert/strict'
import test from 'node:test'
import {
  acquireCodexThreadReservation,
  clearCodexThreadReservationsForTests,
  CodexThreadActiveError,
  getCodexThreadReservations,
} from '../src/codex/thread-reservations.ts'

test.beforeEach(() => clearCodexThreadReservationsForTests())

test('thread reservation rejects a concurrent resume atomically', async () => {
  const first = await acquireCodexThreadReservation('thread-1', 'task-1', {
    listProcesses: async () => [],
  })

  await assert.rejects(
    acquireCodexThreadReservation('thread-1', 'task-2', {
      listProcesses: async () => [],
    }),
    (error: unknown) => error instanceof CodexThreadActiveError
      && error.code === 'CODEX_THREAD_ACTIVE'
      && error.conflict.source === 'reservation'
      && error.conflict.taskId === 'task-1',
  )

  first.release()
  assert.equal(getCodexThreadReservations().size, 0)
})

test('thread reservation rejects an active task registry entry with diagnostics', async () => {
  await assert.rejects(
    acquireCodexThreadReservation('thread-1', 'task-new', {
      taskEntries: [{
        taskId: 'task-old',
        status: 'running',
        threadId: 'thread-1',
        pid: 123,
        startedAt: Date.now(),
      }],
      listProcesses: async () => [],
    }),
    (error: unknown) => error instanceof CodexThreadActiveError
      && error.conflict.source === 'task_registry'
      && error.conflict.taskId === 'task-old'
      && error.conflict.pid === 123,
  )
  assert.equal(getCodexThreadReservations().size, 0)
})

test('thread reservation rejects an orphan resumed process after Worker restart', async () => {
  await assert.rejects(
    acquireCodexThreadReservation('thread-orphan', 'task-new', {
      listProcesses: async () => [{
        pid: 456,
        command: 'codex exec --experimental-json resume thread-orphan',
        memory_mb: 20,
        started_at: '',
      }],
    }),
    (error: unknown) => error instanceof CodexThreadActiveError
      && error.conflict.source === 'process_scan'
      && error.conflict.pid === 456,
  )
  assert.equal(getCodexThreadReservations().size, 0)
})

test('thread reservation remains held until execution releases it', async () => {
  const reservation = await acquireCodexThreadReservation('thread-1', 'task-1', {
    listProcesses: async () => [],
  })
  assert.equal(getCodexThreadReservations().get('thread-1')?.taskId, 'task-1')
  reservation.release()
  reservation.release()
  assert.equal(getCodexThreadReservations().size, 0)
})
