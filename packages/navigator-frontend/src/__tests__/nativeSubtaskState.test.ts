import { describe, expect, it } from 'vitest'
import {
  buildNativeSubtaskRows,
  createNativeSubtaskState,
  normalizeNativeSubtaskMessage,
  normalizeNativeSubtaskStatus,
  parseNativeSubtaskUpdate,
  reduceNativeSubtasks,
  selectNativeSubtasks,
} from '@/composables/nativeSubtaskState'
import { NATIVE_SUBTASK_FAILURE_CODE, type NativeSubtask } from '@/types/nativeSubtasks'

describe('nativeSubtaskState', () => {
  it('merges snapshot and SSE updates per subtask lastEventSeq', () => {
    let state = createNativeSubtaskState('task-1')
    state = reduceNativeSubtasks(state, {
      type: 'SNAPSHOT',
      snapshot: {
        taskId: 'task-1',
        subtasks: [
          { subtaskId: 'a', role: 'explorer', status: 'running', lastEventSeq: 4 },
        ],
      },
    })
    state = reduceNativeSubtasks(state, {
      type: 'UPDATE',
      update: {
        taskId: 'task-1',
        lastEventSeq: 6,
        subtask: { subtaskId: 'a', status: 'completed', message: 'done' },
      },
    })
    state = reduceNativeSubtasks(state, {
      type: 'SNAPSHOT',
      snapshot: {
        taskId: 'task-1',
        subtasks: [
          { subtaskId: 'a', status: 'pending', lastEventSeq: 5 },
          { subtaskId: 'b', status: 'running', lastEventSeq: 2 },
        ],
      },
    })

    expect(state.byId.a).toMatchObject({ status: 'COMPLETED', lastEventSeq: 6 })
    expect(state.byId.a?.message).toBeUndefined()
    expect(state.byId.b).toMatchObject({ status: 'RUNNING', lastEventSeq: 2 })
    expect(state.lastEventSeq).toBe(6)
  })

  it('ignores another task and clears all Pane-local data on reset', () => {
    let state = createNativeSubtaskState('task-1')
    state = reduceNativeSubtasks(state, {
      type: 'UPDATE',
      update: {
        taskId: 'task-old',
        lastEventSeq: 1,
        subtask: { subtaskId: 'old', status: 'running' },
      },
    })
    expect(selectNativeSubtasks(state)).toEqual([])

    state = reduceNativeSubtasks(state, {
      type: 'UPDATE',
      update: {
        taskId: 'task-1',
        lastEventSeq: 1,
        subtask: { subtaskId: 'current', status: 'running' },
      },
    })
    state = reduceNativeSubtasks(state, { type: 'RESET', taskId: 'task-2' })

    expect(state).toEqual({ taskId: 'task-2', byId: {}, lastEventSeq: 0 })
  })

  it('parses payload.data and normalizes lowercase or interrupted statuses', () => {
    const update = parseNativeSubtaskUpdate({
      data: {
        taskId: 'task-1',
        lastEventSeq: '8',
        subtask: {
          subtaskId: 'child-1',
          parentSubtaskId: 'root',
          depth: 1,
          role: 'reviewer',
          status: 'interrupted',
          updatedAt: '2026-07-10T01:00:00Z',
        },
      },
    })

    expect(update).toMatchObject({ taskId: 'task-1', lastEventSeq: 8 })
    expect(update?.subtask).toMatchObject({ subtaskId: 'child-1', role: 'reviewer' })
    expect(normalizeNativeSubtaskStatus(update?.subtask.status)).toBe('INTERRUPTED')
  })

  it('replaces arbitrary provider failure text with a stable UI-safe code', () => {
    const update = parseNativeSubtaskUpdate({
      data: {
        taskId: 'task-1',
        lastEventSeq: 9,
        subtask: {
          subtaskId: 'child-secret',
          status: 'failed',
          message: 'Bearer sk-provider-secret\nraw child output',
        },
      },
    })

    expect(update?.subtask.message).toBe(NATIVE_SUBTASK_FAILURE_CODE)
    expect(JSON.stringify(update)).not.toContain('sk-provider-secret')
    expect(normalizeNativeSubtaskMessage('any provider text', 'FAILED'))
      .toBe(NATIVE_SUBTASK_FAILURE_CODE)
  })

  it('orders parent-child rows and caps untrusted hierarchy depth', () => {
    const subtasks: NativeSubtask[] = [
      { subtaskId: 'child', parentSubtaskId: 'root', depth: 8, status: 'RUNNING', lastEventSeq: 2 },
      { subtaskId: 'root', depth: 0, status: 'RUNNING', lastEventSeq: 1 },
      { subtaskId: 'leaf', parentSubtaskId: 'child', depth: 9, status: 'PENDING', lastEventSeq: 3 },
    ]

    const rows = buildNativeSubtaskRows(subtasks, 2)

    expect(rows.map((row) => row.subtask.subtaskId)).toEqual(['root', 'child', 'leaf'])
    expect(rows.map((row) => row.displayDepth)).toEqual([0, 2, 2])
  })
})
