import assert from 'node:assert/strict'
import test from 'node:test'
import type { TaskEntry } from '../src/models.ts'
import { CodexThreadProcessWatchdog } from '../src/codex/thread-process-watchdog.ts'
import {
  acquireCodexThreadReservation,
  clearCodexThreadReservationsForTests,
  getCodexThreadReservations,
  releaseCodexThreadReservationsForTask,
} from '../src/codex/thread-reservations.ts'

test.beforeEach(() => clearCodexThreadReservationsForTests())

function createRunningTask(overrides: Partial<TaskEntry> = {}): TaskEntry {
  return {
    taskId: 'task-1',
    status: 'running',
    abortController: new AbortController(),
    threadId: 'thread-1',
    pid: 321,
    startedAt: 0,
    ...overrides,
  }
}

test('watchdog aborts a task and releases its reservation after a confirmed missing-process window', async () => {
  const entry = createRunningTask()
  const tasks = new Map([[entry.taskId, entry]])
  let now = 10_000
  let abortReason = ''
  await acquireCodexThreadReservation(entry.threadId!, entry.taskId, {
    listProcesses: async () => [],
  })
  const watchdog = new CodexThreadProcessWatchdog({
    getTaskEntries: () => tasks.values(),
    getReservations: getCodexThreadReservations,
    listProcesses: async () => [],
    now: () => now,
    abortTask: (taskId, reason) => {
      const task = tasks.get(taskId)
      if (!task || task.status !== 'running') return false
      abortReason = reason
      task.abortController?.abort(reason)
      task.status = 'aborted'
      task.completedAt = now
      releaseCodexThreadReservationsForTask(taskId)
      return true
    },
    releaseReservationsForTask: releaseCodexThreadReservationsForTask,
  }, {
    intervalMs: 1_000,
    missingGraceMs: 5_000,
    startupGraceMs: 0,
  })

  const suspected = await watchdog.runOnce()
  assert.deepEqual(suspected.abortedTasks, [])
  assert.equal(suspected.suspectedMissingTasks, 1)
  assert.equal(getCodexThreadReservations().size, 1)

  now += 4_999
  await watchdog.runOnce()
  assert.equal(entry.status, 'running')

  now += 1
  const reconciled = await watchdog.runOnce()
  assert.deepEqual(reconciled.abortedTasks, ['task-1'])
  assert.equal(entry.status, 'aborted')
  assert.equal(entry.abortController?.signal.aborted, true)
  assert.match(abortReason, /pid=321/)
  assert.equal(getCodexThreadReservations().size, 0)
})

test('watchdog never releases a live task even when it runs longer than the grace window', async () => {
  const entry = createRunningTask()
  let now = 1_000_000
  let abortCount = 0
  await acquireCodexThreadReservation(entry.threadId!, entry.taskId, {
    listProcesses: async () => [],
  })
  const watchdog = new CodexThreadProcessWatchdog({
    getTaskEntries: () => [entry],
    getReservations: getCodexThreadReservations,
    listProcesses: async () => [{
      pid: 321,
      command: 'codex exec --experimental-json resume thread-1',
      memory_mb: 10,
      started_at: '',
    }],
    now: () => now,
    abortTask: () => {
      abortCount += 1
      return true
    },
    releaseReservationsForTask: releaseCodexThreadReservationsForTask,
  }, {
    intervalMs: 1_000,
    missingGraceMs: 2_000,
    startupGraceMs: 0,
  })

  await watchdog.runOnce()
  now += 60 * 60 * 1000
  const report = await watchdog.runOnce()

  assert.equal(report.liveTasks, 1)
  assert.equal(abortCount, 0)
  assert.equal(getCodexThreadReservations().size, 1)
})

test('watchdog discovers a resumed process during startup instead of treating the task as missing', async () => {
  const entry = createRunningTask({ pid: undefined, startedAt: 9_500 })
  const watchdog = new CodexThreadProcessWatchdog({
    getTaskEntries: () => [entry],
    getReservations: getCodexThreadReservations,
    listProcesses: async () => [{
      pid: 654,
      command: 'codex exec --experimental-json resume thread-1',
      memory_mb: 12,
      started_at: '',
    }],
    now: () => 10_000,
    abortTask: () => false,
    releaseReservationsForTask: releaseCodexThreadReservationsForTask,
  }, {
    intervalMs: 1_000,
    missingGraceMs: 2_000,
    startupGraceMs: 10_000,
  })

  const report = await watchdog.runOnce()
  assert.equal(report.liveTasks, 1)
  assert.equal(entry.pid, 654)
})

test('watchdog leaves task and reservation state unchanged when process scanning fails', async () => {
  const entry = createRunningTask()
  await acquireCodexThreadReservation(entry.threadId!, entry.taskId, {
    listProcesses: async () => [],
  })
  let abortCount = 0
  const watchdog = new CodexThreadProcessWatchdog({
    getTaskEntries: () => [entry],
    getReservations: getCodexThreadReservations,
    listProcesses: async () => {
      throw new Error('ps unavailable')
    },
    abortTask: () => {
      abortCount += 1
      return true
    },
    releaseReservationsForTask: releaseCodexThreadReservationsForTask,
  }, {
    intervalMs: 1_000,
    missingGraceMs: 2_000,
    startupGraceMs: 0,
  })

  await assert.rejects(watchdog.runOnce(), /ps unavailable/)
  assert.equal(entry.status, 'running')
  assert.equal(abortCount, 0)
  assert.equal(getCodexThreadReservations().size, 1)
})

test('watchdog removes an orphan reservation only after a successful process scan', async () => {
  await acquireCodexThreadReservation('thread-orphan', 'task-gone', {
    listProcesses: async () => [],
  })
  const watchdog = new CodexThreadProcessWatchdog({
    getTaskEntries: () => [],
    getReservations: getCodexThreadReservations,
    listProcesses: async () => [],
    abortTask: () => false,
    releaseReservationsForTask: releaseCodexThreadReservationsForTask,
  }, {
    intervalMs: 1_000,
    missingGraceMs: 2_000,
  })

  const report = await watchdog.runOnce()
  assert.deepEqual(report.releasedThreadIds, ['thread-orphan'])
  assert.equal(getCodexThreadReservations().size, 0)
})

test('watchdog avoids process enumeration while there are no active tasks or reservations', async () => {
  let scanCount = 0
  const watchdog = new CodexThreadProcessWatchdog({
    getTaskEntries: () => [],
    getReservations: getCodexThreadReservations,
    listProcesses: async () => {
      scanCount += 1
      return []
    },
    abortTask: () => false,
    releaseReservationsForTask: releaseCodexThreadReservationsForTask,
  }, {
    intervalMs: 1_000,
    missingGraceMs: 2_000,
  })

  const report = await watchdog.runOnce()
  assert.equal(scanCount, 0)
  assert.equal(report.scannedTasks, 0)
})

test('watchdog start and stop are idempotent lifecycle operations', () => {
  const watchdog = new CodexThreadProcessWatchdog({
    getTaskEntries: () => [],
    getReservations: getCodexThreadReservations,
    listProcesses: async () => [],
    abortTask: () => false,
    releaseReservationsForTask: releaseCodexThreadReservationsForTask,
  }, {
    intervalMs: 1_000,
    missingGraceMs: 2_000,
  })

  assert.equal(watchdog.isRunning(), false)
  watchdog.start()
  watchdog.start()
  assert.equal(watchdog.isRunning(), true)
  watchdog.stop()
  watchdog.stop()
  assert.equal(watchdog.isRunning(), false)
})
