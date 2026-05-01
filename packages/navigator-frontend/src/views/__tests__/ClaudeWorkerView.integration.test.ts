import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox, ElMessage } from 'element-plus'
import ClaudeWorkerView from '../ClaudeWorkerView.vue'
import * as claudeWorkerApi from '@/api/claudeWorker'
import * as langgraphWorkerApi from '@/api/langgraphWorker'
import * as unifiedTaskApi from '@/api/unifiedTask'
import * as sessionApi from '@/api/session'
import * as platformApi from '@/api/platform'
import * as codingAgentApi from '@/api/codingAgent'
import type { ClaudeTask, ClaudeWorker, LlmModelConfig, WorkingDirectory } from '@/types'

// Mock APIs
vi.mock('@/api/claudeWorker')
vi.mock('@/api/langgraphWorker', () => ({
  listWorkers: vi.fn().mockResolvedValue([]),
  registerWorker: vi.fn(),
  updateWorker: vi.fn(),
  deleteWorker: vi.fn(),
  triggerHealthCheck: vi.fn(),
  approveTask: vi.fn(),
}))
vi.mock('@/api/session', () => ({
  getLatestMessages: vi.fn().mockResolvedValue({
    messages: [],
    total: 0,
    limit: 800,
    offset: 0,
    hasMore: false,
  }),
}))
vi.mock('@/api/codingAgent', () => ({
  listAgents: vi.fn().mockResolvedValue([]),
  getAgent: vi.fn(),
  registerAgent: vi.fn(),
  updateAgent: vi.fn(),
  generateSummary: vi.fn(),
  deleteAgent: vi.fn(),
  getAgentDirectories: vi.fn(),
  bindDirectory: vi.fn(),
  unbindDirectory: vi.fn(),
  askAgent: vi.fn(),
}))
vi.mock('@/api/platform', () => ({
  listModelConfigs: vi.fn().mockResolvedValue([]),
  listAgentModelOverrides: vi.fn().mockResolvedValue([]),
}))
vi.mock('@/api/unifiedTask', () => ({
  createTaskUnified: vi.fn(),
  cancelTaskUnified: vi.fn(),
  listTasksUnified: vi.fn().mockResolvedValue([]),
  respondToTaskUnified: vi.fn(),
  reconnectTaskUnified: vi.fn(),
  resyncTaskUnified: vi.fn(),
  resumeTaskUnified: vi.fn(),
  deleteTaskUnified: vi.fn(),
  rewindTaskUnified: vi.fn(),
  scanCheckpointsUnified: vi.fn(),
  searchSessionsUnified: vi.fn().mockResolvedValue([]),
  listTasksPagedUnified: vi.fn().mockResolvedValue({ content: [], totalSessions: 0, page: 0, size: 20 }),
  listTasksByDirectoryUnified: vi.fn().mockResolvedValue([]),
  listTasksByDirectoryPagedUnified: vi.fn().mockResolvedValue({ content: [], totalSessions: 0, page: 0, size: 20 }),
}))
vi.mock('@/api/ssh', () => ({
  connectSsh: vi.fn(),
  disconnectSsh: vi.fn(),
  listSshSessions: vi.fn().mockResolvedValue([]),
}))
vi.mock('@/api/fileBrowser', () => ({
  searchFiles: vi.fn().mockResolvedValue([]),
}))
vi.mock('@/composables/useUnifiedSse', () => ({
  useUnifiedSse: () => ({
    connected: { value: true },
    subscribeSession: vi.fn(() => vi.fn()),
    addNotificationListener: vi.fn(() => vi.fn()),
    connect: vi.fn(),
    disconnect: vi.fn(),
  }),
}))
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

// Common mount options: register Element Plus globally so el-* components resolve correctly
const commonGlobal = {
  plugins: [ElementPlus],
  stubs: {
    ElDropdown: true,
    ElForm: true,
    ElPagination: true,
    ChatPanel: true,
    TaskPaneGrid: true,
    SshTerminalPanel: true,
    SshTerminal: true,
    SlashCommandInput: true,
    SessionSearchDialog: true,
    PencilCanvas: true,
    ScreenshotAnnotator: true,
  },
}

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

  const mockGeminiCompletedTask: ClaudeTask = {
    taskId: 'task-gemini-1',
    sessionId: 'session-gemini-1',
    workerId: 'worker-1',
    directoryId: 'dir-1',
    prompt: 'hello gemini',
    cwd: '/test/path',
    status: 'COMPLETED',
    providerType: 'gemini-worker',
    model: 'gemini-3.1-pro-preview',
    createdAt: '2026-02-16T00:00:00Z',
    updatedAt: '2026-02-16T00:00:00Z',
  }

  const mockClaudeModelConfig: LlmModelConfig = {
    id: 'config-claude',
    tenantId: 'tenant-1',
    name: 'Claude Subscription',
    category: 'CODING',
    baseUrl: '',
    modelName: 'opus[1m]',
    isDefault: false,
    hasApiKey: false,
    scope: 'GLOBAL',
    availableModels: ['opus[1m]', 'opus'],
    workerBackend: 'CLAUDE_CODE',
    sortOrder: 1,
    createdAt: '2026-02-16T00:00:00Z',
    updatedAt: '2026-02-16T00:00:00Z',
  }

  const mockGeminiModelConfig: LlmModelConfig = {
    id: 'config-gemini',
    tenantId: 'tenant-1',
    name: 'Gemini Subscription',
    category: 'CODING',
    baseUrl: '',
    modelName: 'gemini-pro',
    isDefault: false,
    hasApiKey: false,
    scope: 'GLOBAL',
    availableModels: ['gemini-pro', 'gemini-flash', 'gemini-flash-lite'],
    workerBackend: 'GEMINI_CLI',
    sortOrder: 2,
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

  const mockRunningTask: ClaudeTask = {
    taskId: 'task-running-1',
    sessionId: 'session-running-1',
    workerId: 'worker-1',
    directoryId: 'dir-1',
    prompt: 'build feature',
    cwd: '/test/path',
    status: 'RUNNING',
    claudeSessionId: 'claude-session-running',
    source: 'PLATFORM',
    createdAt: '2026-02-16T00:02:00Z',
    updatedAt: '2026-02-16T00:02:00Z',
  }

  const mockClaudeFailedTask: ClaudeTask = {
    taskId: 'task-claude-failed-1',
    sessionId: 'session-claude-failed-1',
    workerId: 'worker-1',
    directoryId: 'dir-1',
    prompt: 'claude failed task',
    cwd: '/test/path',
    status: 'FAILED',
    providerType: 'claude-worker',
    model: 'sonnet',
    claudeSessionId: 'claude-session-failed',
    createdAt: '2026-02-16T00:04:00Z',
    updatedAt: '2026-02-16T00:04:00Z',
  }

  const mockLangGraphFailedTask: ClaudeTask = {
    taskId: 'task-langgraph-failed-1',
    sessionId: 'session-langgraph-failed-1',
    workerId: 'worker-1',
    directoryId: 'dir-1',
    prompt: 'langgraph failed task',
    cwd: '/test/path',
    status: 'FAILED',
    providerType: 'langgraph-biz-worker',
    model: 'biz-default',
    claudeSessionId: 'worker-session-langgraph-1',
    createdAt: '2026-02-16T00:05:00Z',
    updatedAt: '2026-02-16T00:05:00Z',
  }

  beforeEach(() => {
    vi.clearAllMocks()

    // Setup default API responses (claudeWorker auto-mock)
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
    vi.mocked(claudeWorkerApi.getWorkerSessionMessageCount).mockResolvedValue({
      user_count: 0,
      assistant_count: 0,
      total: 0,
    })
    vi.mocked(claudeWorkerApi.getWorkerSessionMessagesPaged).mockResolvedValue([])
    vi.mocked(claudeWorkerApi.listAwaitingReplyTasks).mockResolvedValue([])
    vi.mocked(claudeWorkerApi.listConversationConfigs).mockResolvedValue([])

    // Re-setup unified / platform / codingAgent mocks (clearAllMocks resets factory defaults)
    vi.mocked(langgraphWorkerApi.listWorkers).mockResolvedValue([])
    vi.mocked(sessionApi.getLatestMessages).mockResolvedValue({
      messages: [],
      total: 0,
      limit: 800,
      offset: 0,
      hasMore: false,
    })
    vi.mocked(unifiedTaskApi.listTasksUnified).mockResolvedValue([])
    vi.mocked(unifiedTaskApi.listTasksPagedUnified).mockResolvedValue({
      content: [], totalSessions: 0, page: 0, size: 20,
    } as any)
    vi.mocked(unifiedTaskApi.listTasksByDirectoryPagedUnified).mockResolvedValue({
      content: [mockCompletedTask], totalSessions: 1, page: 0, size: 20,
    } as any)
    vi.mocked(unifiedTaskApi.listTasksByDirectoryUnified).mockResolvedValue([] as any)
    vi.mocked(platformApi.listModelConfigs).mockResolvedValue([])
    vi.mocked(platformApi.listAgentModelOverrides).mockResolvedValue([])
    vi.mocked(codingAgentApi.listAgents).mockResolvedValue([])
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  describe('Issue 1: Resume should update history list', () => {
    it('should refresh task list after resuming task', async () => {
      // Mock resume API (component uses resumeTaskUnified via workerState.resumeTask)
      vi.mocked(unifiedTaskApi.resumeTaskUnified).mockResolvedValue(mockResumedTask as any)
      vi.mocked(ElMessageBox.prompt).mockResolvedValue({ value: 'echo world' } as any)

      const wrapper = mount(ClaudeWorkerView, { global: commonGlobal })

      await flushPromises()

      // Simulate: expand worker, select directory
      const vm = wrapper.vm as any
      vm.selectedWorkerId = 'worker-1'
      vm.selectedDirectoryId = 'dir-1'
      await flushPromises()

      // Record initial API call count (component uses listTasksByDirectoryPagedUnified)
      const initialCallCount = vi.mocked(unifiedTaskApi.listTasksByDirectoryPagedUnified).mock.calls
        .length

      // Mock next call to return both tasks
      vi.mocked(unifiedTaskApi.listTasksByDirectoryPagedUnified).mockResolvedValueOnce({
        content: [mockResumedTask, mockCompletedTask],
        totalSessions: 2,
        page: 0, size: 20,
      } as any)

      // Simulate clicking "继续" button on completed task
      await vm.handleResumeFromHistory(mockCompletedTask)
      await flushPromises()

      // Verify: listTasksByDirectoryPagedUnified should be called again to refresh
      const finalCallCount = vi.mocked(unifiedTaskApi.listTasksByDirectoryPagedUnified).mock.calls.length
      expect(finalCallCount).toBeGreaterThan(initialCallCount)
    })

    it('should resume Gemini task by platform sessionId without provider-native id', async () => {
      vi.mocked(unifiedTaskApi.resumeTaskUnified).mockResolvedValue(mockResumedTask as any)
      vi.mocked(ElMessageBox.prompt).mockResolvedValue({ value: 'continue gemini' } as any)

      const wrapper = mount(ClaudeWorkerView, { global: commonGlobal })
      await flushPromises()

      const vm = wrapper.vm as any
      vm.selectedWorkerId = 'worker-1'
      vm.selectedDirectoryId = 'dir-1'
      await flushPromises()

      await vm.handleResumeFromHistory(mockGeminiCompletedTask)
      await flushPromises()

      expect(unifiedTaskApi.resumeTaskUnified).toHaveBeenCalledWith(expect.objectContaining({
        workerId: 'worker-1',
        prompt: 'continue gemini',
        sessionId: 'session-gemini-1',
      }))
    })

    it('should restore Gemini model config when opening a Gemini history task without modelConfigId', async () => {
      vi.mocked(platformApi.listModelConfigs).mockResolvedValue([
        mockClaudeModelConfig,
        mockGeminiModelConfig,
      ])

      const wrapper = mount(ClaudeWorkerView, { global: commonGlobal })
      await flushPromises()

      const vm = wrapper.vm as any
      vm.selectedWorkerId = 'worker-1'
      await vm.loadPlatformModelConfig()
      await flushPromises()

      expect(vm.platformModelConfigId).toBe('config-claude')

      vm.restoreSessionModelSelection({
        ...mockGeminiCompletedTask,
        modelConfigId: undefined,
      })
      await flushPromises()

      expect(vm.platformModelConfigId).toBe('config-gemini')
      expect(vm.taskForm.model).toBe('gemini-pro')
    })
  })

  describe('Issue 2: Task pane should display prompt', () => {
    it('should show task prompt in pane header', async () => {
      vi.mocked(unifiedTaskApi.resumeTaskUnified).mockResolvedValue(mockResumedTask as any)
      vi.mocked(ElMessageBox.prompt).mockResolvedValue({ value: 'echo world' } as any)

      const wrapper = mount(ClaudeWorkerView, {
        global: {
          ...commonGlobal,
          stubs: {
            ...commonGlobal.stubs,
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
      // Simulate: page was refreshed, now unified API returns resumed task
      vi.mocked(unifiedTaskApi.listTasksByDirectoryPagedUnified).mockResolvedValue({
        content: [mockResumedTask, mockCompletedTask],
        totalSessions: 2,
        page: 0, size: 20,
      } as any)

      const wrapper = mount(ClaudeWorkerView, { global: commonGlobal })

      await flushPromises()

      const vm = wrapper.vm as any
      // Use selectDirectory to trigger loadDirectoryTasks
      vm.selectDirectory('worker-1', 'dir-1')
      await flushPromises()

      // Verify: should call unified API to load tasks
      expect(unifiedTaskApi.listTasksByDirectoryPagedUnified).toHaveBeenCalled()

      // Verify: directoryTasks should include resumed task
      expect(vm.directoryTasks).toHaveLength(2)
      expect(vm.directoryTasks[0].taskId).toBe('task-2')
    })
  })

  describe('History sync regression', () => {
    it('should insert a newly created directory session into history immediately', async () => {
      const mockNewTask: ClaudeTask = {
        taskId: 'task-new-1',
        sessionId: 'session-new-1',
        workerId: 'worker-1',
        directoryId: 'dir-1',
        prompt: 'new task',
        cwd: '/test/path',
        status: 'RUNNING',
        claudeSessionId: 'claude-session-new',
        createdAt: '2026-02-16T00:03:00Z',
        updatedAt: '2026-02-16T00:03:00Z',
      }
      vi.mocked(unifiedTaskApi.createTaskUnified).mockResolvedValue(mockNewTask as any)

      const wrapper = mount(ClaudeWorkerView, { global: commonGlobal })
      await flushPromises()

      const vm = wrapper.vm as any
      vm.selectDirectory('worker-1', 'dir-1')
      await flushPromises()

      vm.taskForm.prompt = 'new task'
      await vm.handleCreateTask()
      await flushPromises()

      expect(vm.directoryTasks[0].taskId).toBe('task-new-1')
      expect(vm.directoryTasks[0].status).toBe('RUNNING')
    })

    it('should keep history badge/state aligned with live task status for directory sessions', async () => {
      vi.mocked(unifiedTaskApi.listTasksByDirectoryPagedUnified).mockResolvedValue({
        content: [mockRunningTask],
        totalSessions: 1,
        page: 0,
        size: 20,
      } as any)
      vi.mocked(claudeWorkerApi.listConversationConfigs).mockResolvedValue([
        {
          sessionId: 'session-running-1',
          pinned: false,
          authBound: false,
          interactionState: 'AWAITING_REPLY',
        },
      ] as any)

      const wrapper = mount(ClaudeWorkerView, { global: commonGlobal })
      await flushPromises()

      const vm = wrapper.vm as any
      vm.selectDirectory('worker-1', 'dir-1')
      await flushPromises()

      expect(vm.activeConversations).toHaveLength(1)
      expect(vm.conversationInteractionState(vm.activeConversations[0])).toBe('PROCESSING')

      window.dispatchEvent(new CustomEvent('task-update', {
        detail: {
          type: 'task_completion',
          taskId: 'task-running-1',
          sessionId: 'session-running-1',
          status: 'COMPLETED',
        },
      }))
      await flushPromises()

      expect(vm.directoryTasks[0].status).toBe('COMPLETED')
      expect(vm.conversationInteractionState(vm.activeConversations[0])).toBe('AWAITING_REPLY')
    })
  })

  describe('LangGraph Biz Worker UI boundaries', () => {
    const paneGridStubs = {
      ...commonGlobal.stubs,
      TaskPaneGrid: {
        props: ['panes'],
        template: `
          <div class="mock-pane-grid">
            <div v-for="pane in panes" :key="pane.paneId" class="mock-pane">
              <slot name="header-extra" :pane-state="pane" />
            </div>
          </div>
        `,
      },
    }

    it('does not show resync for a failed LangGraph task', async () => {
      const wrapper = mount(ClaudeWorkerView, {
        global: {
          ...commonGlobal,
          stubs: paneGridStubs,
        },
      })
      await flushPromises()

      const vm = wrapper.vm as any
      vm.selectDirectory('worker-1', 'dir-1')
      await flushPromises()

      await vm.viewTask(mockLangGraphFailedTask)
      await flushPromises()

      expect(vm.canResyncTask(mockLangGraphFailedTask)).toBe(false)
    })

    it('keeps resync visible for a failed Claude Code task', async () => {
      const wrapper = mount(ClaudeWorkerView, {
        global: {
          ...commonGlobal,
          stubs: paneGridStubs,
        },
      })
      await flushPromises()

      const vm = wrapper.vm as any
      vm.selectDirectory('worker-1', 'dir-1')
      await flushPromises()

      await vm.viewTask(mockClaudeFailedTask)
      await flushPromises()

      expect(vm.canResyncTask(mockClaudeFailedTask)).toBe(true)
    })

    it('shows the LangGraph worker session reference in conversation detail', async () => {
      const wrapper = mount(ClaudeWorkerView, {
        global: {
          ...commonGlobal,
          stubs: {
            ...commonGlobal.stubs,
            ElDialog: {
              props: ['modelValue'],
              template: '<section v-if="modelValue" class="mock-dialog"><slot /><slot name="footer" /></section>',
            },
            'el-dialog': {
              props: ['modelValue'],
              template: '<section v-if="modelValue" class="mock-dialog"><slot /><slot name="footer" /></section>',
            },
            ElDescriptions: {
              template: '<dl><slot /></dl>',
            },
            'el-descriptions': {
              template: '<dl><slot /></dl>',
            },
            ElDescriptionsItem: {
              props: ['label'],
              template: '<div class="mock-desc-item"><dt>{{ label }}</dt><dd><slot /></dd></div>',
            },
            'el-descriptions-item': {
              props: ['label'],
              template: '<div class="mock-desc-item"><dt>{{ label }}</dt><dd><slot /></dd></div>',
            },
          },
        },
      })
      await flushPromises()

      const vm = wrapper.vm as any
      vm.handleShowDetail({
        sessionId: mockLangGraphFailedTask.sessionId,
        claudeSessionId: mockLangGraphFailedTask.claudeSessionId,
        codexThreadId: '',
        latestTask: mockLangGraphFailedTask,
        tasks: [mockLangGraphFailedTask],
        totalCost: 0,
        firstPrompt: mockLangGraphFailedTask.prompt,
      })
      await flushPromises()

      const dialog = wrapper.find('.mock-dialog')
      if (dialog.exists()) {
        const detailText = dialog.text()
        expect(detailText).toContain('Worker Session ID')
        expect(detailText).toContain('worker-session-langgraph-1')
      }
      expect(vm.taskSessionRefLabel(mockLangGraphFailedTask)).toBe('Worker Session ID')
      expect(vm.taskSessionRefValue(mockLangGraphFailedTask)).toBe('worker-session-langgraph-1')
    })
  })
})
