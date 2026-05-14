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
  listDirectoriesByWorker: vi.fn(),
  createDirectory: vi.fn(),
  deleteDirectory: vi.fn(),
  syncDirectoryGitInfo: vi.fn(),
}))

vi.mock('@/api/langgraphWorker', () => ({
  listWorkers: vi.fn(),
  registerWorker: vi.fn(),
  updateWorker: vi.fn(),
  deleteWorker: vi.fn(),
  triggerHealthCheck: vi.fn(),
}))

vi.mock('@/api/unifiedTask', () => ({
  createTaskUnified: vi.fn(),
  cancelTaskUnified: vi.fn(),
  listTasksUnified: vi.fn(),
  respondToTaskUnified: vi.fn(),
  reconnectTaskUnified: vi.fn(),
  resyncTaskUnified: vi.fn(),
  resumeTaskUnified: vi.fn(),
  deleteTaskUnified: vi.fn(),
  listTasksPagedUnified: vi.fn(),
  forwardSessionUnified: vi.fn(),
}))

import * as api from '@/api/claudeWorker'
import * as langgraphApi from '@/api/langgraphWorker'
import * as unifiedTaskApi from '@/api/unifiedTask'
import { DEFAULT_TASK_PAGE_SIZE, useClaudeWorker } from '@/composables/useClaudeWorker'
import type { ClaudeWorker, ClaudeTask, WorkingDirectory } from '@/types'

const mockApi = vi.mocked(api)
const mockLanggraphApi = vi.mocked(langgraphApi)
const mockUnifiedTaskApi = vi.mocked(unifiedTaskApi)

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

function makeDirectory(overrides?: Partial<WorkingDirectory>): WorkingDirectory {
  return {
    directoryId: 'd-1',
    workerId: 'w-1',
    projectName: 'my-project',
    path: '/home/user/my-project',
    createdAt: '2026-02-10T10:00:00',
    updatedAt: '2026-02-10T10:00:00',
    ...overrides,
  }
}

describe('useClaudeWorker', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockLanggraphApi.listWorkers.mockResolvedValue([])
    // Reset the module-level state by directly mutating refs
    const { workers, tasks, directories, loading, taskPage, taskSize, taskTotal } =
      useClaudeWorker()
    workers.value = []
    tasks.value = []
    directories.value = []
    loading.value = false
    taskPage.value = 0
    taskSize.value = DEFAULT_TASK_PAGE_SIZE
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
      mockUnifiedTaskApi.listTasksPagedUnified.mockResolvedValue({
        content: [makeTask()],
        totalSessions: 50,
        page: 0, size: DEFAULT_TASK_PAGE_SIZE,
      })

      const { loadTasks, tasks, taskTotal } = useClaudeWorker()
      await loadTasks()

      expect(mockUnifiedTaskApi.listTasksPagedUnified).toHaveBeenCalledWith(0, DEFAULT_TASK_PAGE_SIZE, undefined)
      expect(tasks.value).toHaveLength(1)
      expect(taskTotal.value).toBe(50)
    })

    it('falls back to non-paged API on error', async () => {
      mockUnifiedTaskApi.listTasksPagedUnified.mockRejectedValue(new Error('404'))
      mockUnifiedTaskApi.listTasksUnified.mockResolvedValue([makeTask(), makeTask({ taskId: 't-2' })] as any)

      const { loadTasks, tasks, taskTotal } = useClaudeWorker()
      await loadTasks()

      expect(mockUnifiedTaskApi.listTasksUnified).toHaveBeenCalled()
      expect(tasks.value).toHaveLength(2)
      expect(taskTotal.value).toBe(2)
    })
  })

  // ========== loadTasksPage ==========

  describe('loadTasksPage', () => {
    it('updates page and size then loads', async () => {
      mockUnifiedTaskApi.listTasksPagedUnified.mockResolvedValue({
        content: [],
        totalSessions: 0,
        page: 0, size: 20,
      })

      const { loadTasksPage, taskPage, taskSize } = useClaudeWorker()
      await loadTasksPage(2, 10)

      expect(taskPage.value).toBe(2)
      expect(taskSize.value).toBe(10)
      expect(mockUnifiedTaskApi.listTasksPagedUnified).toHaveBeenCalledWith(2, 10, undefined)
    })

    it('keeps existing size if not provided', async () => {
      mockUnifiedTaskApi.listTasksPagedUnified.mockResolvedValue({
        content: [],
        totalSessions: 0,
        page: 0, size: 20,
      })

      const { loadTasksPage, taskSize } = useClaudeWorker()
      taskSize.value = 15
      await loadTasksPage(1)

      expect(taskSize.value).toBe(15)
      expect(mockUnifiedTaskApi.listTasksPagedUnified).toHaveBeenCalledWith(1, 15, undefined)
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
      mockUnifiedTaskApi.createTaskUnified.mockResolvedValue(task as any)

      const { createTask, tasks } = useClaudeWorker()
      tasks.value = [makeTask({ taskId: 't-existing' })]

      await createTask({ workerId: 'w-1', prompt: 'Do stuff' })

      expect(tasks.value).toHaveLength(2)
      expect(tasks.value[0]!.taskId).toBe('t-new')
    })

    it('passes directoryId when provided', async () => {
      const task = makeTask({ taskId: 't-dir', directoryId: 'd-1' })
      mockUnifiedTaskApi.createTaskUnified.mockResolvedValue(task as any)

      const { createTask } = useClaudeWorker()
      await createTask({ workerId: 'w-1', prompt: 'Do stuff', directoryId: 'd-1' })

      expect(mockUnifiedTaskApi.createTaskUnified).toHaveBeenCalledWith({
        workerId: 'w-1',
        prompt: 'Do stuff',
        directoryId: 'd-1',
      })
    })
  })

  // ========== abortTask ==========

  describe('abortTask', () => {
    it('sets task status to ABORTED', async () => {
      mockUnifiedTaskApi.cancelTaskUnified.mockResolvedValue(undefined)

      const { abortTask, tasks } = useClaudeWorker()
      tasks.value = [makeTask({ taskId: 't-1', status: 'RUNNING' })]

      await abortTask('t-1')

      expect(tasks.value[0]!.status).toBe('ABORTED')
    })
  })

  // ========== loadDirectories ==========

  describe('loadDirectories', () => {
    it('loads directories for a worker', async () => {
      const d1 = makeDirectory({ directoryId: 'd-1' })
      const d2 = makeDirectory({ directoryId: 'd-2', projectName: 'other-project' })
      mockApi.listDirectoriesByWorker.mockResolvedValue([d1, d2])

      const { loadDirectories, directories } = useClaudeWorker()
      await loadDirectories('w-1')

      expect(mockApi.listDirectoriesByWorker).toHaveBeenCalledWith('w-1')
      expect(directories.value).toHaveLength(2)
    })
  })

  // ========== createDirectory ==========

  describe('createDirectory', () => {
    it('adds to directories list', async () => {
      const newDir = makeDirectory({ directoryId: 'd-new' })
      mockApi.createDirectory.mockResolvedValue(newDir)

      const { createDirectory, directories } = useClaudeWorker()
      const result = await createDirectory({
        workerId: 'w-1',
        projectName: 'new-project',
        path: '/home/user/new-project',
      })

      expect(result).toEqual(newDir)
      expect(directories.value).toContainEqual(newDir)
      expect(mockApi.createDirectory).toHaveBeenCalledWith({
        workerId: 'w-1',
        projectName: 'new-project',
        path: '/home/user/new-project',
      })
    })
  })

  // ========== deleteDirectory ==========

  describe('deleteDirectory', () => {
    it('removes directory from list', async () => {
      mockApi.deleteDirectory.mockResolvedValue(undefined)

      const { directories, deleteDirectory } = useClaudeWorker()
      directories.value = [
        makeDirectory({ directoryId: 'd-1' }),
        makeDirectory({ directoryId: 'd-2' }),
      ]

      await deleteDirectory('d-1')

      expect(directories.value).toHaveLength(1)
      expect(directories.value[0]!.directoryId).toBe('d-2')
    })
  })

  // ========== syncGitInfo ==========

  describe('syncGitInfo', () => {
    it('updates directory in list', async () => {
      const updated = makeDirectory({
        directoryId: 'd-1',
        gitBranch: 'main',
        gitStatus: 'clean',
        gitProvider: 'GITHUB',
      })
      mockApi.syncDirectoryGitInfo.mockResolvedValue(updated)

      const { syncGitInfo, directories } = useClaudeWorker()
      directories.value = [makeDirectory({ directoryId: 'd-1' })]

      const result = await syncGitInfo('d-1')

      expect(result.gitBranch).toBe('main')
      expect(result.gitStatus).toBe('clean')
      expect(directories.value[0]!.gitBranch).toBe('main')
      expect(mockApi.syncDirectoryGitInfo).toHaveBeenCalledWith('d-1')
    })
  })
})
