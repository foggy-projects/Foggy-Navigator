import assert from 'node:assert/strict'
import test from 'node:test'
import {
  acquireCodexThreadReservation,
  clearCodexThreadReservationsForTests,
  CodexThreadActiveError,
  getCodexThreadReservations,
  releaseCodexThreadReservationsForTask,
} from '../src/codex/thread-reservations.ts'
import { abortTask, taskRegistry } from '../src/codex/sdk-wrapper.ts'

test.beforeEach(() => {
  clearCodexThreadReservationsForTests()
  taskRegistry.clear()
})

test.afterEach(() => {
  clearCodexThreadReservationsForTests()
  taskRegistry.clear()
})

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

test('thread reservations can be released through the owning task lifecycle', async () => {
  await acquireCodexThreadReservation('thread-1', 'task-1', {
    listProcesses: async () => [],
  })
  await acquireCodexThreadReservation('thread-2', 'task-1', {
    listProcesses: async () => [],
  })
  await acquireCodexThreadReservation('thread-3', 'task-2', {
    listProcesses: async () => [],
  })

  assert.deepEqual(
    releaseCodexThreadReservationsForTask('task-1').sort(),
    ['thread-1', 'thread-2'],
  )
  assert.deepEqual([...getCodexThreadReservations().keys()], ['thread-3'])
})

test('abortTask aborts the execution and releases its thread reservation through one lifecycle entry', async () => {
  const abortController = new AbortController()
  taskRegistry.set('task-1', {
    taskId: 'task-1',
    status: 'running',
    abortController,
    threadId: 'thread-1',
    pid: 321,
    startedAt: Date.now(),
  })
  await acquireCodexThreadReservation('thread-1', 'task-1', {
    listProcesses: async () => [],
  })

  assert.equal(abortTask('task-1', 'Codex CLI process disappeared'), true)
  assert.equal(taskRegistry.get('task-1')?.status, 'aborted')
  assert.equal(abortController.signal.reason, 'Codex CLI process disappeared')
  assert.equal(getCodexThreadReservations().size, 0)
})

test('a released reservation does not allow resume while the old Codex CLI process is still alive', async () => {
  taskRegistry.set('task-old', {
    taskId: 'task-old',
    status: 'running',
    abortController: new AbortController(),
    threadId: 'thread-1',
    pid: 321,
    startedAt: Date.now(),
  })
  await acquireCodexThreadReservation('thread-1', 'task-old', {
    listProcesses: async () => [],
  })

  assert.equal(abortTask('task-old', 'User requested process termination'), true)
  assert.equal(getCodexThreadReservations().size, 0)

  await assert.rejects(
    acquireCodexThreadReservation('thread-1', 'task-new', {
      taskEntries: taskRegistry.values(),
      listProcesses: async () => [{
        pid: 321,
        command: 'codex exec --experimental-json resume thread-1',
        memory_mb: 10,
        started_at: '',
      }],
    }),
    (error: unknown) => error instanceof CodexThreadActiveError
      && error.conflict.source === 'process_scan'
      && error.conflict.pid === 321,
  )
})
