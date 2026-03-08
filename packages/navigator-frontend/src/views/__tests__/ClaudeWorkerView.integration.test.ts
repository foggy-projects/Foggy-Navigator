import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { ElMessageBox, ElMessage } from 'element-plus'
import ClaudeWorkerView from '../ClaudeWorkerView.vue'
import * as claudeWorkerApi from '@/api/claudeWorker'
import type { ClaudeTask, ClaudeWorker, WorkingDirectory } from '@/types'

// Mock APIs
vi.mock('@/api/claudeWorker')
vi.mock('element-plus', async () => {
  const actual = await vi.importActual('element-plus')
  return {
    ...actual,
    ElMessageBox: {
      prompt: vi.fn(),
      confirm: vi.fn(),
    },
    ElMessage: {
      success: vi.fn(),
      warning: vi.fn(),
      error: vi.fn(),
      info: vi.fn(),
    },
  }
})

// Mock router
vi.mock('vue-router', async () => {
  const actual = await vi.importActual('vue-router')
  return {
    ...actual,
    useRouter: () => ({
      push: vi.fn(),
    }),
  }
})

describe('ClaudeWorkerView - Resume Task Integration', () => {
  const mockWorker: ClaudeWorker = {
    workerId: 'worker-1',
    name: 'Test Worker',
    baseUrl: 'http://localhost:3031',
    authToken: 'test-token',
    status: 'ONLINE',
    hostname: 'localhost',
    authMode: 'SUBSCRIPTION',
    createdAt: '2026-02-16T00:00:00Z',
    updatedAt: '2026-02-16T00:00:00Z',
  }

  const mockDirectory: WorkingDirectory = {
    directoryId: 'dir-1',
    workerId: 'worker-1',
    projectName: 'test-project',
    path: '/test/path',
    gitBranch: 'main',
    gitStatus: 'clean',
    gitRemoteUrl: 'https://github.com/test/repo.git',
    gitProvider: 'GITHUB',
    createdAt: '2026-02-16T00:00:00Z',
    updatedAt: '2026-02-16T00:00:00Z',
  }

  const mockCompletedTask: ClaudeTask = {
    taskId: 'task-1',
    sessionId: 'session-1',
    workerId: 'worker-1',
    directoryId: 'dir-1',
    prompt: 'echo hello',
    cwd: '/test/path',
    status: 'COMPLETED',
    claudeSessionId: 'claude-session-123',
    costUsd: 0.001,
    durationMs: 1000,
    createdAt: '2026-02-16T00:00:00Z',
    updatedAt: '2026-02-16T00:00:00Z',
  }

  const mockResumedTask: ClaudeTask = {
    taskId: 'task-2',
    sessionId: 'session-2',
    workerId: 'worker-1',
    directoryId: 'dir-1',
    prompt: 'echo world',
    cwd: '/test/path',
    status: 'RUNNING',
    claudeSessionId: 'claude-session-123',
    createdAt: '2026-02-16T00:01:00Z',
    updatedAt: '2026-02-16T00:01:00Z',
  }

  beforeEach(() => {
    vi.clearAllMocks()

    // Setup default API responses
    vi.mocked(claudeWorkerApi.listWorkers).mockResolvedValue([mockWorker])
    vi.mocked(claudeWorkerApi.listDirectoriesByWorker).mockResolvedValue([mockDirectory])
    vi.mocked(claudeWorkerApi.listTasksByDirectoryPaged).mockResolvedValue({
      content: [mockCompletedTask],
      totalSessions: 1,
      page: 0, size: 20,
    })
    vi.mocked(claudeWorkerApi.listTasksPaged).mockResolvedValue({
      content: [],
      totalSessions: 0,
      page: 0, size: 20,
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  describe('Issue 1: Resume should update history list', () => {
    it('should refresh task list after resuming task', async () => {
      // Mock resume API
      vi.mocked(claudeWorkerApi.resumeTask).mockResolvedValue(mockResumedTask)
      vi.mocked(ElMessageBox.prompt).mockResolvedValue({ value: 'echo world' } as any)

      // After resume, return both old and new task
      vi.mocked(claudeWorkerApi.listTasksByDirectoryPaged).mockResolvedValueOnce({
        content: [mockCompletedTask],
        totalSessions: 1,
        page: 0, size: 20,
      })

      const wrapper = mount(ClaudeWorkerView, {
        global: {
          stubs: {
            ElButton: false,
            ElTag: false,
            ElDropdown: true,
            ElForm: true,
            ElPagination: true,
            ChatPanel: true,
            TaskPaneGrid: true,
          },
        },
      })

      await flushPromises()

      // Simulate: expand worker, select directory
      // (In real test would click elements, here we directly call internal methods)
      const vm = wrapper.vm as any
      vm.selectedWorkerId = 'worker-1'
      vm.selectedDirectoryId = 'dir-1'
      await flushPromises()

      // Record initial API call count
      const initialCallCount = vi.mocked(claudeWorkerApi.listTasksByDirectoryPaged).mock.calls
        .length

      // Mock second call to return both tasks
      vi.mocked(claudeWorkerApi.listTasksByDirectoryPaged).mockResolvedValueOnce({
        content: [mockResumedTask, mockCompletedTask],
        totalSessions: 2,
        page: 0, size: 20,
      })

      // Simulate clicking "继续" button on completed task
      await vm.handleResumeFromHistory(mockCompletedTask)
      await flushPromises()

      // Verify: listTasksByDirectoryPaged should be called again to refresh
      const finalCallCount = vi.mocked(claudeWorkerApi.listTasksByDirectoryPaged).mock.calls.length
      expect(finalCallCount).toBeGreaterThan(initialCallCount)
    })
  })

  describe('Issue 2: Task pane should display prompt', () => {
    it('should show task prompt in pane header', async () => {
      vi.mocked(claudeWorkerApi.resumeTask).mockResolvedValue(mockResumedTask)
      vi.mocked(ElMessageBox.prompt).mockResolvedValue({ value: 'echo world' } as any)

      const wrapper = mount(ClaudeWorkerView, {
        global: {
          stubs: {
            ElButton: false,
            ElTag: false,
            ElDropdown: true,
            ElForm: true,
            ElPagination: true,
            ChatPanel: true,
            TaskPaneGrid: {
              template: '<div class="mock-pane-grid"><slot /></div>',
              props: ['panes'],
            },
          },
        },
      })

      await flushPromises()

      const vm = wrapper.vm as any
      vm.selectedWorkerId = 'worker-1'
      vm.selectedDirectoryId = 'dir-1'
      await flushPromises()

      // Resume task
      await vm.handleResumeFromHistory(mockCompletedTask)
      await flushPromises()

      // Verify: new pane should have task with prompt
      expect(vm.panes).toHaveLength(1)
      expect(vm.panes[0].task.value).toBeTruthy()
      expect(vm.panes[0].task.value.prompt).toBe('echo world')
    })
  })

  describe('Issue 3: Task list should persist after page refresh', () => {
    it('should load resumed task from API after mount', async () => {
      // Simulate: page was refreshed, now API returns resumed task
      vi.mocked(claudeWorkerApi.listTasksByDirectoryPaged).mockResolvedValue({
        content: [mockResumedTask, mockCompletedTask],
        totalSessions: 2,
        page: 0, size: 20,
      })

      const wrapper = mount(ClaudeWorkerView, {
        global: {
          stubs: {
            ElButton: false,
            ElTag: false,
            ElDropdown: true,
            ElForm: true,
            ElPagination: true,
            ChatPanel: true,
            TaskPaneGrid: true,
          },
        },
      })

      await flushPromises()

      const vm = wrapper.vm as any
      // Use selectDirectory to trigger loadDirectoryTasks
      vm.selectDirectory('worker-1', 'dir-1')
      await flushPromises()

      // Verify: should call API and load tasks
      expect(claudeWorkerApi.listTasksByDirectoryPaged).toHaveBeenCalled()

      // Verify: directoryTasks should include resumed task
      expect(vm.directoryTasks).toHaveLength(2)
      expect(vm.directoryTasks[0].taskId).toBe('task-2')
    })
  })
})
