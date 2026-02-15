import { describe, it, expect, vi, beforeEach } from 'vitest'

// Mock the API module before importing the composable
vi.mock('@/api/claudeWorker', () => ({
  listWorkers: vi.fn(),
  listTasks: vi.fn(),
  listTasksPaged: vi.fn(),
  registerWorker: vi.fn(),
  updateWorker: vi.fn(),
  deleteWorker: vi.fn(),
  triggerHealthCheck: vi.fn(),
  createTask: vi.fn(),
  resumeTask: vi.fn(),
  abortTask: vi.fn(),
}))

import * as api from '@/api/claudeWorker'
import { useClaudeWorker } from '@/composables/useClaudeWorker'
import type { ClaudeWorker, ClaudeTask } from '@/types'

const mockApi = vi.mocked(api)

function makeWorker(overrides?: Partial<ClaudeWorker>): ClaudeWorker {
  return {
    workerId: 'w-1',
    name: 'Worker 1',
    baseUrl: 'http://localhost:3031',
    status: 'ONLINE',
    authMode: 'SUBSCRIPTION',
    createdAt: '2026-02-10T10:00:00',
    ...overrides,
  }
}

function makeTask(overrides?: Partial<ClaudeTask>): ClaudeTask {
  return {
    taskId: 't-1',
    sessionId: 'session-1',
    workerId: 'w-1',
    prompt: 'Write tests',
    status: 'COMPLETED',
    createdAt: '2026-02-10T10:00:00',
    updatedAt: '2026-02-10T10:00:00',
    ...overrides,
  }
}

describe('useClaudeWorker', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // Reset the module-level state by directly mutating refs
    const { workers, tasks, loading, taskPage, taskSize, taskTotal } = useClaudeWorker()
    workers.value = []
    tasks.value = []
    loading.value = false
    taskPage.value = 0
    taskSize.value = 20
    taskTotal.value = 0
  })

  // ========== loadWorkers ==========

  describe('loadWorkers', () => {
    it('loads workers from API', async () => {
      const w1 = makeWorker({ workerId: 'w-1' })
      const w2 = makeWorker({ workerId: 'w-2', name: 'Worker 2', status: 'OFFLINE' })
      mockApi.listWorkers.mockResolvedValue([w1, w2])

      const { loadWorkers, workers, loading } = useClaudeWorker()
      await loadWorkers()

      expect(workers.value).toHaveLength(2)
      expect(loading.value).toBe(false)
    })

    it('sets loading to false even on error', async () => {
      mockApi.listWorkers.mockRejectedValue(new Error('Network error'))

      const { loadWorkers, loading } = useClaudeWorker()
      await expect(loadWorkers()).rejects.toThrow('Network error')
      expect(loading.value).toBe(false)
    })
  })

  // ========== onlineWorkers ==========

  describe('onlineWorkers', () => {
    it('filters to ONLINE workers only', async () => {
      mockApi.listWorkers.mockResolvedValue([
        makeWorker({ workerId: 'w-1', status: 'ONLINE' }),
        makeWorker({ workerId: 'w-2', status: 'OFFLINE' }),
        makeWorker({ workerId: 'w-3', status: 'ONLINE' }),
      ])

      const { loadWorkers, onlineWorkers } = useClaudeWorker()
      await loadWorkers()

      expect(onlineWorkers.value).toHaveLength(2)
      expect(onlineWorkers.value.every((w) => w.status === 'ONLINE')).toBe(true)
    })
  })

  // ========== loadTasks (paged) ==========

  describe('loadTasks', () => {
    it('uses paged API', async () => {
      mockApi.listTasksPaged.mockResolvedValue({
        content: [makeTask()],
        totalElements: 50,
        totalPages: 3,
      })

      const { loadTasks, tasks, taskTotal } = useClaudeWorker()
      await loadTasks()

      expect(mockApi.listTasksPaged).toHaveBeenCalledWith(0, 20)
      expect(tasks.value).toHaveLength(1)
      expect(taskTotal.value).toBe(50)
    })

    it('falls back to non-paged API on error', async () => {
      mockApi.listTasksPaged.mockRejectedValue(new Error('404'))
      mockApi.listTasks.mockResolvedValue([makeTask(), makeTask({ taskId: 't-2' })])

      const { loadTasks, tasks, taskTotal } = useClaudeWorker()
      await loadTasks()

      expect(mockApi.listTasks).toHaveBeenCalled()
      expect(tasks.value).toHaveLength(2)
      expect(taskTotal.value).toBe(2)
    })
  })

  // ========== loadTasksPage ==========

  describe('loadTasksPage', () => {
    it('updates page and size then loads', async () => {
      mockApi.listTasksPaged.mockResolvedValue({
        content: [],
        totalElements: 0,
        totalPages: 0,
      })

      const { loadTasksPage, taskPage, taskSize } = useClaudeWorker()
      await loadTasksPage(2, 10)

      expect(taskPage.value).toBe(2)
      expect(taskSize.value).toBe(10)
      expect(mockApi.listTasksPaged).toHaveBeenCalledWith(2, 10)
    })

    it('keeps existing size if not provided', async () => {
      mockApi.listTasksPaged.mockResolvedValue({
        content: [],
        totalElements: 0,
        totalPages: 0,
      })

      const { loadTasksPage, taskSize } = useClaudeWorker()
      taskSize.value = 15
      await loadTasksPage(1)

      expect(taskSize.value).toBe(15)
      expect(mockApi.listTasksPaged).toHaveBeenCalledWith(1, 15)
    })
  })

  // ========== registerWorker ==========

  describe('registerWorker', () => {
    it('adds to workers list', async () => {
      const newWorker = makeWorker({ workerId: 'w-new' })
      mockApi.registerWorker.mockResolvedValue(newWorker)

      const { registerWorker, workers } = useClaudeWorker()
      const result = await registerWorker({
        name: 'New',
        baseUrl: 'http://new:3031',
        authToken: 'token',
      })

      expect(result).toEqual(newWorker)
      expect(workers.value).toContainEqual(newWorker)
    })
  })

  // ========== updateWorker ==========

  describe('updateWorker', () => {
    it('replaces worker in list', async () => {
      const original = makeWorker({ workerId: 'w-1', name: 'Old' })
      const updated = makeWorker({ workerId: 'w-1', name: 'Updated' })

      const { workers, updateWorker } = useClaudeWorker()
      workers.value = [original]
      mockApi.updateWorker.mockResolvedValue(updated)

      await updateWorker('w-1', { name: 'Updated' })

      expect(workers.value[0]!.name).toBe('Updated')
    })
  })

  // ========== deleteWorker ==========

  describe('deleteWorker', () => {
    it('removes worker from list', async () => {
      mockApi.deleteWorker.mockResolvedValue(undefined)

      const { workers, deleteWorker } = useClaudeWorker()
      workers.value = [makeWorker({ workerId: 'w-1' }), makeWorker({ workerId: 'w-2' })]

      await deleteWorker('w-1')

      expect(workers.value).toHaveLength(1)
      expect(workers.value[0]!.workerId).toBe('w-2')
    })
  })

  // ========== createTask ==========

  describe('createTask', () => {
    it('prepends task to list', async () => {
      const task = makeTask({ taskId: 't-new', status: 'PENDING' })
      mockApi.createTask.mockResolvedValue(task)

      const { createTask, tasks } = useClaudeWorker()
      tasks.value = [makeTask({ taskId: 't-existing' })]

      await createTask({ workerId: 'w-1', prompt: 'Do stuff' })

      expect(tasks.value).toHaveLength(2)
      expect(tasks.value[0]!.taskId).toBe('t-new')
    })
  })

  // ========== abortTask ==========

  describe('abortTask', () => {
    it('sets task status to ABORTED', async () => {
      mockApi.abortTask.mockResolvedValue({ taskId: 't-1', status: 'ABORTED' })

      const { abortTask, tasks } = useClaudeWorker()
      tasks.value = [makeTask({ taskId: 't-1', status: 'RUNNING' })]

      await abortTask('t-1')

      expect(tasks.value[0]!.status).toBe('ABORTED')
    })
  })
})
