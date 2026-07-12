import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox, ElMessage } from 'element-plus'
import ClaudeWorkerView from '../ClaudeWorkerView.vue'
import claudeWorkerViewSource from '../ClaudeWorkerView.vue?raw'
import * as claudeWorkerApi from '@/api/claudeWorker'
import * as langgraphWorkerApi from '@/api/langgraphWorker'
import * as unifiedTaskApi from '@/api/unifiedTask'
import * as sessionApi from '@/api/session'
import * as platformApi from '@/api/platform'
import * as codingAgentApi from '@/api/codingAgent'
import * as codexRuntimeApi from '@/api/codexRuntime'
import { DEFAULT_TASK_PAGE_SIZE } from '@/composables/useClaudeWorker'
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
vi.mock('@/api/codexRuntime', () => ({
  getCodexRuntimeAvailability: vi.fn().mockResolvedValue({
    appServerManaged: false,
    ultraAvailable: false,
    blockReason: 'CODEX_ULTRA_RUNTIME_UNAVAILABLE',
  }),
  listCodexRuntimes: vi.fn().mockResolvedValue([]),
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
  getIncomingForwardRelation: vi.fn().mockResolvedValue(null),
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
    codexBaseUrl: 'http://localhost:3051',
    geminiBaseUrl: 'http://localhost:3071',
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

  const mockCodexModelConfig: LlmModelConfig = {
    id: 'config-codex',
    tenantId: 'tenant-1',
    name: 'Codex API Key',
    category: 'CODING',
    baseUrl: 'https://codex.example.test/v1',
    modelName: 'codex-latest',
    isDefault: false,
    hasApiKey: true,
    scope: 'GLOBAL',
    availableModels: ['codex-latest', 'codex-fast'],
    workerBackend: 'OPENAI_CODEX',
    sortOrder: 3,
    createdAt: '2026-02-16T00:00:00Z',
    updatedAt: '2026-02-16T00:00:00Z',
  }

  const mockCodexAppServerModelConfig: LlmModelConfig = {
    ...mockCodexModelConfig,
    id: 'config-codex-app-server',
    name: 'Codex App Server',
    baseUrl: '',
    modelName: 'gpt-5.6-sol:high',
    hasApiKey: false,
    availableModels: ['gpt-5.6-sol:high', 'gpt-5.6-sol:ultra'],
    workerBackend: 'OPENAI_CODEX_APP_SERVER',
    sortOrder: 4,
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
    vi.mocked(claudeWorkerApi.deleteConversation).mockResolvedValue(undefined)

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
    vi.mocked(codexRuntimeApi.listCodexRuntimes).mockResolvedValue([])
    vi.mocked(codexRuntimeApi.getCodexRuntimeAvailability).mockResolvedValue({
      appServerManaged: false,
      ultraAvailable: false,
      blockReason: 'CODEX_ULTRA_RUNTIME_UNAVAILABLE',
    })
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

    it('creates a new session instead of resuming when the selected Codex provider changes', async () => {
      const sourceTask: ClaudeTask = {
        ...mockCompletedTask,
        providerType: 'codex-worker',
        model: 'gpt-5.6-sol:max',
      }
      const newTask: ClaudeTask = {
        ...mockResumedTask,
        taskId: 'task-app-server-1',
        sessionId: 'session-app-server-1',
        providerType: 'codex-app-server-worker',
        model: 'gpt-5.6-sol:high',
      }
      vi.mocked(platformApi.listModelConfigs).mockResolvedValue([
        mockCodexModelConfig,
        mockCodexAppServerModelConfig,
      ])
      vi.mocked(codexRuntimeApi.getCodexRuntimeAvailability).mockResolvedValue({
        appServerManaged: true,
        modelAvailable: true,
        ultraAvailable: false,
        blockReason: null,
      })
      vi.mocked(unifiedTaskApi.createTaskUnified).mockResolvedValue(newTask as any)
      vi.mocked(ElMessageBox.prompt).mockResolvedValue({ value: 'continue on app server' } as any)
      vi.mocked(ElMessageBox.confirm).mockResolvedValue('confirm' as any)

      const wrapper = mount(ClaudeWorkerView, { global: commonGlobal })
      await flushPromises()
      const vm = wrapper.vm as any
      vm.selectDirectory('worker-1', 'dir-1')
      await flushPromises()
      vm.platformModelConfigId = 'config-codex-app-server'
      vm.taskForm.model = 'gpt-5.6-sol:high'
      await flushPromises()

      await vm.handleResumeFromHistory(sourceTask)
      await flushPromises()

      expect(ElMessageBox.confirm).toHaveBeenCalledWith(
        expect.stringContaining('无法续接原生会话'),
        '创建新会话',
        expect.objectContaining({ confirmButtonText: '创建新会话' }),
      )
      expect(unifiedTaskApi.createTaskUnified).toHaveBeenCalledWith(expect.objectContaining({
        workerId: 'worker-1',
        directoryId: 'dir-1',
        prompt: 'continue on app server',
        modelConfigId: 'config-codex-app-server',
        providerType: 'codex-app-server-worker',
      }))
      expect(unifiedTaskApi.createTaskUnified).toHaveBeenCalledWith(expect.not.objectContaining({
        sessionId: 'session-1',
      }))
      expect(unifiedTaskApi.resumeTaskUnified).not.toHaveBeenCalled()
      wrapper.unmount()
    })

    it.each(['RUNNING', 'AWAITING_INPUT'] as const)(
      'does not open or execute resume while a task is %s',
      async (status) => {
        const wrapper = mount(ClaudeWorkerView, { global: commonGlobal })
        await flushPromises()

        const vm = wrapper.vm as any
        await vm.handleResumeFromHistory({
          ...mockCompletedTask,
          status,
        })
        await flushPromises()

        expect(ElMessageBox.prompt).not.toHaveBeenCalled()
        expect(unifiedTaskApi.resumeTaskUnified).not.toHaveBeenCalled()
        expect(ElMessage.warning).toHaveBeenCalled()
        wrapper.unmount()
      },
    )

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

    it('should prefer conversation auth model config over stale task modelConfigId when opening history', async () => {
      vi.mocked(platformApi.listModelConfigs).mockResolvedValue([
        mockClaudeModelConfig,
        mockCodexModelConfig,
      ])

      const wrapper = mount(ClaudeWorkerView, { global: commonGlobal })
      await flushPromises()

      const vm = wrapper.vm as any
      vm.selectedWorkerId = 'worker-1'
      await vm.loadPlatformModelConfig()
      await flushPromises()

      expect(vm.platformModelConfigId).toBe('config-claude')

      vm.workerState.conversationConfigs.value.set('session-1', {
        sessionId: 'session-1',
        pinned: false,
        authBound: true,
        authMode: 'CUSTOM_ENDPOINT',
        authModelConfigId: 'config-codex',
      })

      vm.restoreSessionModelSelection({
        ...mockCompletedTask,
        modelConfigId: 'config-claude',
        model: 'opus[1m]',
      })
      await flushPromises()

      expect(vm.platformModelConfigId).toBe('config-codex')
      expect(vm.taskForm.model).toBe('codex-latest:low')
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
      expect(unifiedTaskApi.listTasksByDirectoryPagedUnified).toHaveBeenCalledWith(
        'dir-1',
        0,
        DEFAULT_TASK_PAGE_SIZE,
        'AWAITING_REPLY,PROCESSING',
      )

      // Verify: directoryTasks should include resumed task
      expect(vm.directoryTasks).toHaveLength(2)
      expect(vm.directoryTasks[0].taskId).toBe('task-2')
    })

    it('exposes stable session and task identities for history navigation', async () => {
      const historyTask = {
        ...mockCompletedTask,
        source: 'PLATFORM',
      } as ClaudeTask
      vi.mocked(unifiedTaskApi.listTasksPagedUnified).mockResolvedValue({
        content: [historyTask],
        totalSessions: 1,
        page: 0,
        size: DEFAULT_TASK_PAGE_SIZE,
      } as any)
      vi.mocked(claudeWorkerApi.listConversationConfigs).mockResolvedValue([{
        sessionId: mockCompletedTask.sessionId,
        customTitle: 'Generated conversation title',
        pinned: false,
        authBound: false,
        interactionState: 'AWAITING_REPLY',
      }] as any)

      const wrapper = mount(ClaudeWorkerView, { global: commonGlobal })
      await flushPromises()

      const vm = wrapper.vm as any
      vm.selectWorker(mockWorker.workerId)
      await flushPromises()

      const historyItem = wrapper.find(
        `.conv-item[data-session-id="${historyTask.sessionId}"][data-task-id="${historyTask.taskId}"]`,
      )
      expect(historyItem.exists()).toBe(true)
      expect(historyItem.find('.conv-prompt').attributes('title')).toBe('Generated conversation title')
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

  describe('App Server Worker CLI process boundary', () => {
    const appServerAvailability = {
      appServerManaged: true,
      ultraAvailable: true,
      blockReason: null,
    }

    const emptyProcesses = {
      processes: [],
      active_task_count: 0,
    }

    it('keeps SDK CLI probes independent from an App Server Runtime', async () => {
      vi.mocked(codexRuntimeApi.getCodexRuntimeAvailability)
        .mockResolvedValue(appServerAvailability)
      vi.mocked(claudeWorkerApi.listCliProcesses).mockResolvedValue(emptyProcesses)
      vi.mocked(claudeWorkerApi.listCodexCliProcesses).mockResolvedValue(emptyProcesses)
      vi.mocked(claudeWorkerApi.listGeminiCliProcesses).mockResolvedValue(emptyProcesses)
      const wrapper = mount(ClaudeWorkerView, { global: commonGlobal })
      await flushPromises()

      const vm = wrapper.vm as any
      vm.selectWorker('worker-1')
      await flushPromises()

      expect(codexRuntimeApi.getCodexRuntimeAvailability).not.toHaveBeenCalled()
      expect(claudeWorkerApi.listCliProcesses).toHaveBeenCalledWith('worker-1')
      expect(claudeWorkerApi.listCodexCliProcesses).toHaveBeenCalledWith('worker-1', {
        suppressErrorMessage: true,
      })
      expect(claudeWorkerApi.listGeminiCliProcesses).toHaveBeenCalledWith('worker-1', {
        suppressErrorMessage: true,
      })
      expect(vm.cliProcessEmptyText).toBe('未检测到 CLI 进程')
      vm.handleWorkerTabChange('processes')
      await flushPromises()
      expect(codexRuntimeApi.listCodexRuntimes).not.toHaveBeenCalled()
      expect(ElMessage.error).not.toHaveBeenCalled()
      wrapper.unmount()
    })

    it('keeps the legacy probes when the runtime registry is empty', async () => {
      vi.mocked(codexRuntimeApi.getCodexRuntimeAvailability).mockResolvedValue({
        appServerManaged: false,
        ultraAvailable: false,
        blockReason: 'CODEX_ULTRA_RUNTIME_UNAVAILABLE',
      })
      vi.mocked(claudeWorkerApi.listCliProcesses).mockResolvedValue(emptyProcesses)
      vi.mocked(claudeWorkerApi.listCodexCliProcesses).mockResolvedValue(emptyProcesses)
      vi.mocked(claudeWorkerApi.listGeminiCliProcesses).mockResolvedValue(emptyProcesses)
      const wrapper = mount(ClaudeWorkerView, { global: commonGlobal })
      await flushPromises()

      const vm = wrapper.vm as any
      vm.selectWorker('worker-1')
      await flushPromises()

      expect(claudeWorkerApi.listCliProcesses).toHaveBeenCalledWith('worker-1')
      expect(claudeWorkerApi.listCodexCliProcesses).toHaveBeenCalledWith('worker-1', {
        suppressErrorMessage: true,
      })
      expect(claudeWorkerApi.listGeminiCliProcesses).toHaveBeenCalledWith('worker-1', {
        suppressErrorMessage: true,
      })
      expect(vm.cliProcessEmptyText).toBe('未检测到 CLI 进程')
      wrapper.unmount()
    })

    it('falls back to the legacy probes when runtime discovery fails', async () => {
      vi.mocked(codexRuntimeApi.getCodexRuntimeAvailability)
        .mockRejectedValue(new Error('registry unavailable'))
      vi.mocked(claudeWorkerApi.listCliProcesses).mockResolvedValue(emptyProcesses)
      vi.mocked(claudeWorkerApi.listCodexCliProcesses).mockResolvedValue(emptyProcesses)
      vi.mocked(claudeWorkerApi.listGeminiCliProcesses).mockResolvedValue(emptyProcesses)
      const wrapper = mount(ClaudeWorkerView, { global: commonGlobal })
      await flushPromises()

      const vm = wrapper.vm as any
      vm.selectWorker('worker-1')
      await flushPromises()

      expect(claudeWorkerApi.listCliProcesses).toHaveBeenCalledWith('worker-1')
      expect(claudeWorkerApi.listCodexCliProcesses).toHaveBeenCalledWith('worker-1', {
        suppressErrorMessage: true,
      })
      expect(claudeWorkerApi.listGeminiCliProcesses).toHaveBeenCalledWith('worker-1', {
        suppressErrorMessage: true,
      })
      expect(vm.cliProcessEmptyText).toBe('未检测到 CLI 进程')
      wrapper.unmount()
    })

    it('ignores late SDK CLI process responses from the previously selected worker', async () => {
      const worker2 = {
        ...mockWorker,
        workerId: 'worker-2',
        name: 'Second Worker',
      }
      vi.mocked(claudeWorkerApi.listWorkers).mockResolvedValue([mockWorker, worker2])
      const processResult = (workerId: string) => ({
        processes: [{
          pid: workerId === 'worker-2' ? 22 : 11,
          command: 'codex',
          memory_mb: 1,
          started_at: '2026-07-10T00:00:00Z',
          process_type: 'codex',
        }],
        active_task_count: 0,
      }) as any
      let resolveClaudeWorker1!: (value: ReturnType<typeof processResult>) => void
      let resolveCodexWorker1!: (value: ReturnType<typeof processResult>) => void
      let resolveGeminiWorker1!: (value: ReturnType<typeof processResult>) => void
      vi.mocked(claudeWorkerApi.listCliProcesses).mockImplementation((workerId) => (
        workerId === 'worker-1'
          ? new Promise(resolve => { resolveClaudeWorker1 = resolve })
          : Promise.resolve(processResult(workerId))
      ))
      vi.mocked(claudeWorkerApi.listCodexCliProcesses).mockImplementation((workerId) => (
        workerId === 'worker-1'
          ? new Promise(resolve => { resolveCodexWorker1 = resolve })
          : Promise.resolve(processResult(workerId))
      ))
      vi.mocked(claudeWorkerApi.listGeminiCliProcesses).mockImplementation((workerId) => (
        workerId === 'worker-1'
          ? new Promise(resolve => { resolveGeminiWorker1 = resolve })
          : Promise.resolve(processResult(workerId))
      ))
      const wrapper = mount(ClaudeWorkerView, { global: commonGlobal })
      await flushPromises()

      const vm = wrapper.vm as any
      vm.selectWorker('worker-1')
      vm.selectWorker('worker-2')
      await flushPromises()

      expect(vm.selectedWorkerId).toBe('worker-2')
      expect(vm.cliProcesses).toHaveLength(3)
      expect(vm.cliProcesses.every((process: { pid: number }) => process.pid === 22)).toBe(true)

      resolveClaudeWorker1(processResult('worker-1'))
      resolveCodexWorker1(processResult('worker-1'))
      resolveGeminiWorker1(processResult('worker-1'))
      await flushPromises()

      expect(vm.selectedWorkerId).toBe('worker-2')
      expect(vm.cliProcessEmptyText).toBe('未检测到 CLI 进程')
      expect(vm.cliProcesses).toHaveLength(3)
      expect(vm.cliProcesses.every((process: { pid: number }) => process.pid === 22)).toBe(true)
      expect(claudeWorkerApi.listCliProcesses).toHaveBeenCalledTimes(2)
      expect(claudeWorkerApi.listCliProcesses).toHaveBeenCalledWith('worker-1')
      expect(claudeWorkerApi.listCliProcesses).toHaveBeenCalledWith('worker-2')
      wrapper.unmount()
    })
  })

  describe('Codex Ultra runtime readiness for new tasks', () => {
    const readyUltraAvailability = {
      appServerManaged: true,
      modelAvailable: true,
      ultraAvailable: true,
      blockReason: null,
    } as const

    it('blocks SDK Ultra without probing the App Server Runtime', async () => {
      vi.mocked(platformApi.listModelConfigs).mockResolvedValue([mockCodexModelConfig])
      const wrapper = mount(ClaudeWorkerView, { global: commonGlobal })
      await flushPromises()

      const vm = wrapper.vm as any
      vm.selectedWorkerId = 'worker-1'
      vm.taskForm.model = 'gpt-5.6-sol:ultra'
      vm.taskForm.prompt = 'invalid sdk ultra task'
      await flushPromises()

      expect(vm.ultraRuntimeCreateBlockReason).toBe('Ultra 仅支持 Codex App Server 后端')
      expect(vm.createTaskDisabled).toBe(true)
      expect(codexRuntimeApi.getCodexRuntimeAvailability).not.toHaveBeenCalled()
      wrapper.unmount()
    })

    it('blocks a non-Ultra App Server model when no routed Runtime supports it', async () => {
      vi.mocked(platformApi.listModelConfigs).mockResolvedValue([mockCodexAppServerModelConfig])
      vi.mocked(codexRuntimeApi.getCodexRuntimeAvailability).mockResolvedValue({
        appServerManaged: true,
        modelAvailable: false,
        ultraAvailable: false,
        blockReason: 'CODEX_RUNTIME_UNAVAILABLE',
      })
      const wrapper = mount(ClaudeWorkerView, { global: commonGlobal })
      await flushPromises()

      const vm = wrapper.vm as any
      vm.selectedWorkerId = 'worker-1'
      vm.taskForm.model = 'gpt-5.6-sol:high'
      vm.taskForm.prompt = 'app server high task'
      await flushPromises()

      expect(codexRuntimeApi.getCodexRuntimeAvailability).toHaveBeenCalledWith('worker-1', {
        model: 'gpt-5.6-sol:high',
        suppressErrorMessage: true,
      })
      expect(vm.taskForm.model).toBe('')
      expect(vm.claudeModelOptions).toEqual([])
      expect(vm.ultraRuntimeCreateBlockReason).toBe('当前 Worker 没有可执行的 App Server 模型')
      expect(vm.createTaskDisabled).toBe(true)
      wrapper.unmount()
    })

    it('blocks a new Ultra task while the selected worker has no ready runtime', async () => {
      vi.mocked(platformApi.listModelConfigs).mockResolvedValue([{
        ...mockCodexAppServerModelConfig,
        availableModels: ['codex-ultra'],
      }])
      vi.mocked(codexRuntimeApi.getCodexRuntimeAvailability).mockResolvedValue({
        appServerManaged: true,
        modelAvailable: false,
        ultraAvailable: false,
        blockReason: 'CODEX_ULTRA_RUNTIME_UNAVAILABLE',
      })
      const wrapper = mount(ClaudeWorkerView, { global: commonGlobal })
      await flushPromises()

      const vm = wrapper.vm as any
      vm.selectedWorkerId = 'worker-1'
      vm.taskForm.model = 'codex-ultra'
      await flushPromises()
      vm.taskForm.prompt = 'new ultra task'

      expect(codexRuntimeApi.getCodexRuntimeAvailability).toHaveBeenCalledWith('worker-1', {
        model: 'codex-ultra',
        suppressErrorMessage: true,
      })
      expect(codexRuntimeApi.listCodexRuntimes).not.toHaveBeenCalled()
      expect(vm.taskForm.model).toBe('')
      expect(vm.ultraRuntimeCreateBlockReason).toBe('当前 Worker 没有可执行的 App Server 模型')
      expect(vm.createTaskDisabled).toBe(true)
      await vm.handleCreateTask()
      expect(unifiedTaskApi.createTaskUnified).not.toHaveBeenCalled()
      expect(ElMessage.warning).toHaveBeenCalledWith('当前 Worker 没有可执行的 App Server 模型')
      wrapper.unmount()
    })

    it('enables only new Ultra creation after the selected worker reports a ready runtime', async () => {
      vi.mocked(platformApi.listModelConfigs).mockResolvedValue([{
        ...mockCodexAppServerModelConfig,
        availableModels: ['codex-ultra'],
      }])
      vi.mocked(codexRuntimeApi.getCodexRuntimeAvailability)
        .mockResolvedValue(readyUltraAvailability)
      const wrapper = mount(ClaudeWorkerView, { global: commonGlobal })
      await flushPromises()

      const vm = wrapper.vm as any
      vm.selectedWorkerId = 'worker-1'
      vm.taskForm.model = 'codex-ultra'
      await flushPromises()
      vm.taskForm.prompt = 'ready ultra task'
      await wrapper.vm.$nextTick()

      expect(vm.taskForm.model).toBe('codex-latest:ultra')
      expect(vm.ultraRuntimeReadiness).toBe('READY')
      expect(vm.selectedWorkerEntity?.status).toBe('ONLINE')
      expect(vm.createTaskDisabled).toBe(false)
      expect(vm.ultraRuntimeCreateBlockReason).toBe('')
      wrapper.unmount()
    })

    it('intersects App Server model grants with the selected Worker runtime capability', async () => {
      vi.mocked(platformApi.listModelConfigs).mockResolvedValue([{
        ...mockCodexAppServerModelConfig,
        availableModels: [
          'codex-latest:high',
          'codex-latest:ultra',
          'codex-terra:ultra',
        ],
      }])
      vi.mocked(codexRuntimeApi.getCodexRuntimeAvailability).mockImplementation(async (_workerId, options) => ({
        appServerManaged: true,
        modelAvailable: options?.model === 'codex-latest:ultra',
        ultraAvailable: options?.model === 'codex-latest:ultra',
        blockReason: options?.model === 'codex-latest:ultra' ? null : 'CODEX_RUNTIME_UNAVAILABLE',
      }))
      const wrapper = mount(ClaudeWorkerView, { global: commonGlobal })
      await flushPromises()

      const vm = wrapper.vm as any
      vm.selectedWorkerId = 'worker-1'
      await flushPromises()

      expect(vm.claudeModelOptions.map((option: { value: string }) => option.value))
        .toEqual(['codex-latest:ultra'])
      expect(vm.taskForm.model).toBe('codex-latest:ultra')
      expect(codexRuntimeApi.getCodexRuntimeAvailability).toHaveBeenCalledWith('worker-1', {
        model: 'codex-terra:ultra',
        suppressErrorMessage: true,
      })
      wrapper.unmount()
    })

    it('filters Agent default models through the selected Worker App Runtime capability', async () => {
      vi.mocked(platformApi.listModelConfigs).mockResolvedValue([{
        ...mockCodexAppServerModelConfig,
        availableModels: ['codex-latest:high', 'codex-latest:ultra'],
      }])
      vi.mocked(codexRuntimeApi.getCodexRuntimeAvailability).mockImplementation(
        async (_workerId, options) => ({
          appServerManaged: true,
          modelAvailable: options?.model === 'codex-latest:high',
          ultraAvailable: false,
          blockReason: options?.model === 'codex-latest:high'
            ? null
            : 'CODEX_RUNTIME_UNAVAILABLE',
        }),
      )
      const wrapper = mount(ClaudeWorkerView, { global: commonGlobal })
      await flushPromises()

      const vm = wrapper.vm as any
      vm.selectedWorkerId = 'worker-1'
      vm.agentForm.defaultModelConfigId = 'config-codex-app-server'
      vm.agentForm.defaultModel = 'codex-latest:ultra'
      vm.showAgentRegisterDialog = true
      await flushPromises()

      expect(vm.agentModelOptions.map((option: { value: string }) => option.value))
        .toEqual(['codex-latest:high'])
      expect(vm.agentForm.defaultModel).toBe('codex-latest:high')
      expect(codexRuntimeApi.getCodexRuntimeAvailability).toHaveBeenCalledWith('worker-1', {
        model: 'codex-latest:ultra',
        suppressErrorMessage: true,
      })
      wrapper.unmount()
    })

    it('blocks Agent registration while App Runtime model capability is still loading', async () => {
      vi.mocked(platformApi.listModelConfigs).mockResolvedValue([{
        ...mockCodexAppServerModelConfig,
        availableModels: ['codex-latest:high', 'codex-latest:ultra'],
      }])
      vi.mocked(codexRuntimeApi.getCodexRuntimeAvailability).mockImplementation(
        () => new Promise(() => {}),
      )
      const wrapper = mount(ClaudeWorkerView, { global: commonGlobal })
      await flushPromises()

      const vm = wrapper.vm as any
      vm.selectedWorkerId = 'worker-1'
      vm.agentForm = {
        name: 'app-agent',
        description: '',
        defaultDirectoryId: 'dir-1',
        defaultBranch: '',
        projectSummary: '',
        defaultModelConfigId: 'config-codex-app-server',
        defaultModel: 'codex-latest:ultra',
      }
      vm.showAgentRegisterDialog = true
      await wrapper.vm.$nextTick()

      expect(vm.agentModelOptionsLoading).toBe(true)
      expect(vm.agentModelSelectionBlocked).toBe(true)
      await vm.handleRegisterAgent()

      expect(codingAgentApi.registerAgent).not.toHaveBeenCalled()
      expect(ElMessage.warning).toHaveBeenCalledWith('正在检查 Runtime 模型能力，请稍后重试')
      wrapper.unmount()
    })

    it('ignores stale App model results after switching Workers', async () => {
      const workerOneResolvers: Array<(value: typeof readyUltraAvailability) => void> = []
      vi.mocked(platformApi.listModelConfigs).mockResolvedValue([{
        ...mockCodexAppServerModelConfig,
        availableModels: ['codex-latest:high', 'codex-latest:ultra'],
      }])
      vi.mocked(claudeWorkerApi.listWorkers).mockResolvedValue([
        mockWorker,
        { ...mockWorker, workerId: 'worker-2', name: 'Second Worker' },
      ])
      vi.mocked(codexRuntimeApi.getCodexRuntimeAvailability).mockImplementation((workerId, options) => {
        if (workerId === 'worker-1') {
          return new Promise((resolve) => workerOneResolvers.push(resolve))
        }
        const available = options?.model === 'codex-latest:high'
        return Promise.resolve({
          appServerManaged: true,
          modelAvailable: available,
          ultraAvailable: false,
          blockReason: available ? null : 'CODEX_RUNTIME_UNAVAILABLE',
        })
      })
      const wrapper = mount(ClaudeWorkerView, { global: commonGlobal })
      await flushPromises()

      const vm = wrapper.vm as any
      vm.selectedWorkerId = 'worker-1'
      await wrapper.vm.$nextTick()
      vm.selectedWorkerId = 'worker-2'
      await flushPromises()

      expect(vm.claudeModelOptions.map((option: { value: string }) => option.value))
        .toEqual(['codex-latest:high'])
      for (const resolve of workerOneResolvers) resolve(readyUltraAvailability)
      await flushPromises()
      expect(vm.selectedWorkerId).toBe('worker-2')
      expect(vm.claudeModelOptions.map((option: { value: string }) => option.value))
        .toEqual(['codex-latest:high'])
      wrapper.unmount()
    })

    it('accepts the aggregate availability produced for ALL_CANARY at zero rollout', async () => {
      vi.mocked(platformApi.listModelConfigs).mockResolvedValue([{
        ...mockCodexAppServerModelConfig,
        availableModels: ['codex-ultra'],
      }])
      vi.mocked(codexRuntimeApi.getCodexRuntimeAvailability)
        .mockResolvedValue(readyUltraAvailability)
      const wrapper = mount(ClaudeWorkerView, { global: commonGlobal })
      await flushPromises()

      const vm = wrapper.vm as any
      vm.selectedWorkerId = 'worker-1'
      vm.taskForm.model = 'codex-ultra'
      await flushPromises()
      vm.taskForm.prompt = 'all canary ultra task'
      await wrapper.vm.$nextTick()

      expect(vm.ultraRuntimeReadiness).toBe('READY')
      expect(vm.ultraRuntimeCreateBlockReason).toBe('')
      expect(vm.createTaskDisabled).toBe(false)
      wrapper.unmount()
    })

    it('ignores a late ready response from the previously selected worker', async () => {
      let resolveWorkerOne!: (value: typeof readyUltraAvailability) => void
      vi.mocked(platformApi.listModelConfigs).mockResolvedValue([{
        ...mockCodexAppServerModelConfig,
        availableModels: ['codex-ultra'],
      }])
      vi.mocked(claudeWorkerApi.listWorkers).mockResolvedValue([
        mockWorker,
        { ...mockWorker, workerId: 'worker-2', name: 'Second Worker' },
      ])
      vi.mocked(codexRuntimeApi.getCodexRuntimeAvailability).mockImplementation((workerId: string) => {
        if (workerId === 'worker-1') {
          return new Promise((resolve) => { resolveWorkerOne = resolve }) as any
        }
        return Promise.resolve({
          appServerManaged: true,
          modelAvailable: false,
          ultraAvailable: false,
          blockReason: 'CODEX_ULTRA_RUNTIME_UNAVAILABLE',
        })
      })
      const wrapper = mount(ClaudeWorkerView, { global: commonGlobal })
      await flushPromises()

      const vm = wrapper.vm as any
      vm.taskForm.model = 'codex-ultra'
      vm.taskForm.prompt = 'worker switch'
      vm.selectedWorkerId = 'worker-1'
      await flushPromises()
      vm.selectedWorkerId = 'worker-2'
      await flushPromises()
      resolveWorkerOne(readyUltraAvailability)
      await flushPromises()

      expect(vm.ultraRuntimeCheckedWorkerId).toBe('worker-2')
      expect(vm.ultraRuntimeReadiness).toBe('UNAVAILABLE')
      expect(vm.createTaskDisabled).toBe(true)
      wrapper.unmount()
    })
  })

  describe('Issue 4: Conversation delete must use session API', () => {
    it('deletes a conversation by session id instead of deleting its tasks', async () => {
      vi.mocked(ElMessageBox.confirm).mockResolvedValue('confirm' as any)

      const wrapper = mount(ClaudeWorkerView, { global: commonGlobal })
      await flushPromises()

      const vm = wrapper.vm as any
      await vm.handleDeleteConversation({
        sessionId: 'session-1',
        latestTask: mockCompletedTask,
        tasks: [
          mockCompletedTask,
          { ...mockCompletedTask, taskId: 'task-2' },
        ],
        taskCount: 2,
        totalCost: 0,
        firstPrompt: mockCompletedTask.prompt,
      })
      await flushPromises()

      expect(claudeWorkerApi.deleteConversation).toHaveBeenCalledWith('session-1')
      expect(unifiedTaskApi.deleteTaskUnified).not.toHaveBeenCalled()
    })

    it('deletes a pane-only session even when it is absent from the conversation list', async () => {
      vi.mocked(ElMessageBox.confirm).mockResolvedValue('confirm' as any)

      const wrapper = mount(ClaudeWorkerView, { global: commonGlobal })
      await flushPromises()

      const vm = wrapper.vm as any
      await vm.handlePaneDelete('session-pane-only')
      await flushPromises()

      expect(claudeWorkerApi.deleteConversation).toHaveBeenCalledWith('session-pane-only')
      expect(unifiedTaskApi.deleteTaskUnified).not.toHaveBeenCalled()
    })
  })

  describe('BUG-003: narrow Worker view navigation', () => {
    const originalMatchMedia = window.matchMedia

    function mockNarrowViewport(matches: boolean) {
      Object.defineProperty(window, 'matchMedia', {
        configurable: true,
        writable: true,
        value: vi.fn().mockImplementation((query: string) => ({
          matches: matches && query === '(max-width: 720px)',
          media: query,
          onchange: null,
          addListener: vi.fn(),
          removeListener: vi.fn(),
          addEventListener: vi.fn(),
          removeEventListener: vi.fn(),
          dispatchEvent: vi.fn(),
        })),
      })
    }

    afterEach(() => {
      Object.defineProperty(window, 'matchMedia', {
        configurable: true,
        writable: true,
        value: originalMatchMedia,
      })
    })

    it('uses dismissible, mutually exclusive panels and reveals the task after selection', async () => {
      mockNarrowViewport(true)
      const wrapper = mount(ClaudeWorkerView, { global: commonGlobal })
      await flushPromises()

      const vm = wrapper.vm as any
      vm.prefs.leftPanelCollapsed = false
      vm.prefs.rightPanelCollapsed = false

      vm.selectWorker('worker-1')
      await flushPromises()
      expect(vm.mobileLeftPanelOpen).toBe(false)
      expect(vm.mobileRightPanelOpen).toBe(false)
      expect(vm.prefs.leftPanelCollapsed).toBe(false)
      expect(vm.prefs.rightPanelCollapsed).toBe(false)

      vm.openMobilePanel('left')
      await flushPromises()
      expect(vm.mobileLeftPanelOpen).toBe(true)
      expect(vm.mobileRightPanelOpen).toBe(false)
      expect(wrapper.find('.mobile-panel-backdrop').exists()).toBe(true)

      vm.openMobilePanel('right')
      await flushPromises()
      expect(vm.mobileLeftPanelOpen).toBe(false)
      expect(vm.mobileRightPanelOpen).toBe(true)

      await vm.viewTask(mockCompletedTask)
      await flushPromises()
      expect(vm.mobileRightPanelOpen).toBe(false)
      expect(wrapper.find('.worker-main').classes()).toContain('has-panes')

      vm.openMobilePanel('left')
      await flushPromises()
      await wrapper.find('.mobile-panel-backdrop').trigger('click')
      expect(vm.mobileLeftPanelOpen).toBe(false)
      expect(vm.mobileRightPanelOpen).toBe(false)
      expect(vm.prefs.leftPanelCollapsed).toBe(false)
      expect(vm.prefs.rightPanelCollapsed).toBe(false)
      wrapper.unmount()
    })

    it('reserves the narrow viewport for an open task pane instead of Worker overview chrome', () => {
      expect(claudeWorkerViewSource).toMatch(
        /@media \(max-width: 720px\) \{[\s\S]*?\.worker-main\.has-panes > \.worker-header,[\s\S]*?\.worker-main\.has-panes > \.worker-tabs,[\s\S]*?\.worker-main\.has-panes > \.dir-compact-header,[\s\S]*?\.worker-main\.has-panes > \.fav-scripts-bar,[\s\S]*?\.worker-main\.has-panes > \.new-task-mini \{\s*display: none;/,
      )
      expect(claudeWorkerViewSource).toMatch(
        /\.worker-main\.has-panes :deep\(\.task-pane-grid\) \{\s*flex: 1 1 0;\s*height: 100%;\s*grid-auto-rows: minmax\(0, 1fr\);/,
      )
      expect(claudeWorkerViewSource).toMatch(
        /\.worker-main\.has-panes \.panel-expand-btn \{\s*top: 10px;\s*transform: none;/,
      )
      expect(claudeWorkerViewSource).toMatch(
        /\.worker-main\.has-panes :deep\(\.pane-header\) \{\s*padding-left: 54px;\s*padding-right: 54px;/,
      )
    })

    it('preserves the desktop panel state when selecting a Worker', async () => {
      mockNarrowViewport(false)
      const wrapper = mount(ClaudeWorkerView, { global: commonGlobal })
      await flushPromises()

      const vm = wrapper.vm as any
      vm.prefs.leftPanelCollapsed = false
      vm.prefs.rightPanelCollapsed = false
      vm.selectWorker('worker-1')
      await flushPromises()

      expect(vm.prefs.leftPanelCollapsed).toBe(false)
      expect(vm.prefs.rightPanelCollapsed).toBe(false)
      wrapper.unmount()
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
