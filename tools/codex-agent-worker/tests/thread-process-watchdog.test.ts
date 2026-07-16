import assert from 'node:assert/strict'
import test from 'node:test'
import type { TaskAttention, TaskEntry } from '../src/models.ts'
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

test('watchdog marks a confirmed missing process PROCESS_UNVERIFIED without aborting or releasing its reservation', async () => {
  const entry = createRunningTask()
  const tasks = new Map([[entry.taskId, entry]])
  let now = 10_000
  const attention: TaskAttention[] = []
  await acquireCodexThreadReservation(entry.threadId!, entry.taskId, {
    listProcesses: async () => [],
  })
  const watchdog = new CodexThreadProcessWatchdog({
    getTaskEntries: () => tasks.values(),
    getReservations: getCodexThreadReservations,
    listProcesses: async () => [],
    now: () => now,
    markTaskAttention: (_taskId, value) => attention.push(value),
    releaseReservationsForTask: releaseCodexThreadReservationsForTask,
  }, {
    intervalMs: 1_000,
    missingGraceMs: 5_000,
    startupGraceMs: 0,
  })

  const suspected = await watchdog.runOnce()
  assert.deepEqual(suspected.unverifiedTasks, [])
  assert.equal(suspected.suspectedMissingTasks, 1)
  assert.equal(getCodexThreadReservations().size, 1)

  now += 5_000
  const reconciled = await watchdog.runOnce()
  assert.deepEqual(reconciled.abortedTasks, [])
  assert.deepEqual(reconciled.unverifiedTasks, ['task-1'])
  assert.equal(entry.status, 'running')
  assert.equal(entry.abortController?.signal.aborted, false)
  assert.equal(getCodexThreadReservations().size, 1)
  assert.deepEqual(attention.map(item => item.code), ['PROCESS_UNVERIFIED'])
})

test('watchdog never changes a live task even when it runs longer than the grace window', async () => {
  const entry = createRunningTask()
  let now = 1_000_000
  let attentionCount = 0
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
    markTaskAttention: () => { attentionCount++ },
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
  assert.equal(attentionCount, 0)
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

test('watchdog marks a late task with neither PID nor thread identity as unverified after startup grace', async () => {
  const entry = createRunningTask({ pid: undefined, threadId: undefined, startedAt: 0 })
  const attention: TaskAttention[] = []
  const watchdog = new CodexThreadProcessWatchdog({
    getTaskEntries: () => [entry],
    getReservations: getCodexThreadReservations,
    listProcesses: async () => [],
    now: () => 10_000,
    markTaskAttention: (_taskId, value) => attention.push(value),
    releaseReservationsForTask: releaseCodexThreadReservationsForTask,
  }, {
    intervalMs: 1_000,
    missingGraceMs: 2_000,
    startupGraceMs: 1_000,
  })

  const report = await watchdog.runOnce()
  assert.deepEqual(report.unverifiedTasks, ['task-1'])
  assert.equal(entry.status, 'running')
  assert.equal(entry.abortController?.signal.aborted, false)
  assert.equal(attention[0]?.code, 'PROCESS_UNVERIFIED')
})

test('watchdog turns a process scan error into recoverable attention and retains task and reservation state', async () => {
  const entry = createRunningTask()
  await acquireCodexThreadReservation(entry.threadId!, entry.taskId, {
    listProcesses: async () => [],
  })
  const attention: TaskAttention[] = []
  const watchdog = new CodexThreadProcessWatchdog({
    getTaskEntries: () => [entry],
    getReservations: getCodexThreadReservations,
    listProcesses: async () => {
      throw new Error('ps unavailable')
    },
    markTaskAttention: (_taskId, value) => attention.push(value),
    releaseReservationsForTask: releaseCodexThreadReservationsForTask,
  }, {
    intervalMs: 1_000,
    missingGraceMs: 2_000,
    startupGraceMs: 0,
  })

  const report = await watchdog.runOnce()
  assert.equal(entry.status, 'running')
  assert.equal(entry.abortController?.signal.aborted, false)
  assert.deepEqual(report.unverifiedTasks, ['task-1'])
  assert.equal(attention[0]?.code, 'PROCESS_UNVERIFIED')
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
