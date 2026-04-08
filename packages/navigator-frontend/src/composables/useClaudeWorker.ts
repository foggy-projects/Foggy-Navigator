import { ref, computed } from 'vue'
import * as api from '@/api/claudeWorker'
import {
  createTaskUnified,
  cancelTaskUnified,
  listTasksUnified,
  respondToTaskUnified,
  reconnectTaskUnified,
  resyncTaskUnified,
  resumeTaskUnified,
  deleteTaskUnified,
  listTasksPagedUnified,
} from '@/api/unifiedTask'
import type { ClaudeWorker, ClaudeTask, WorkingDirectory, ConversationConfig } from '@/types'

const workers = ref<ClaudeWorker[]>([])
const tasks = ref<ClaudeTask[]>([])
const directories = ref<WorkingDirectory[]>([])
const loading = ref(false)
const taskPage = ref(0)
const taskSize = ref(20)
const taskTotal = ref(0)
const conversationConfigs = ref<Map<string, ConversationConfig>>(new Map())
const activeTasks = ref<ClaudeTask[]>([])
const awaitingReplyTasks = ref<ClaudeTask[]>([])

export function useClaudeWorker() {
  const onlineWorkers = computed(() => workers.value.filter((w) => w.status === 'ONLINE'))

  async function loadWorkers() {
    loading.value = true
    try {
      workers.value = await api.listWorkers()
    } finally {
      loading.value = false
    }
  }

  async function loadTasks(state?: string) {
    try {
      // 使用统一任务 API（/api/v1/tasks/page）
      const result = await listTasksPagedUnified(taskPage.value, taskSize.value, state || undefined)
      tasks.value = result.content
      taskTotal.value = result.totalSessions
    } catch {
      // Fallback to non-paged API
      const unified = await listTasksUnified()
      tasks.value = unified as unknown as ClaudeTask[]
      taskTotal.value = tasks.value.length
    }
  }

  async function loadTasksPage(page: number, size?: number, state?: string) {
    taskPage.value = page
    if (size !== undefined) taskSize.value = size
    await loadTasks(state)
  }

  async function registerWorker(form: {
    name: string
    baseUrl: string
    authToken: string
    authMode?: string
    sshUsername?: string
    sshPort?: number
    sshPassword?: string
    codexConfig?: { baseUrl?: string; authToken?: string; model?: string }
  }) {
    const worker = await api.registerWorker(form)
    workers.value.push(worker)
    return worker
  }

  async function updateWorker(
    workerId: string,
    form: { name?: string; baseUrl?: string; authToken?: string; authMode?: string; sshUsername?: string; sshPort?: number; sshPassword?: string; codexConfig?: { baseUrl?: string; authToken?: string; model?: string } },
  ) {
    const updated = await api.updateWorker(workerId, form)
    const idx = workers.value.findIndex((w) => w.workerId === workerId)
    if (idx >= 0) workers.value[idx] = updated
    return updated
  }

  async function deleteWorker(workerId: string) {
    await api.deleteWorker(workerId)
    workers.value = workers.value.filter((w) => w.workerId !== workerId)
  }

  async function refreshWorkerStatus(workerId: string) {
    const updated = await api.triggerHealthCheck(workerId)
    const idx = workers.value.findIndex((w) => w.workerId === workerId)
    if (idx >= 0) workers.value[idx] = updated
    return updated
  }

  async function syncSkills(workerId: string) {
    return api.syncWorkerSkills(workerId)
  }

  async function createTask(form: {
    workerId: string
    prompt: string
    cwd?: string
    directoryId?: string
    model?: string
    maxTurns?: number
    agentTeamsJson?: string
    agentTeamsConfigId?: string
    images?: string
    permissionMode?: string
    modelConfigId?: string
    agentId?: string
  }) {
    // 使用统一任务 API（/api/v1/tasks），由显式执行上下文决定 logical agent/provider
    const task = await createTaskUnified(form) as unknown as ClaudeTask
    tasks.value.unshift(task)
    return task
  }

  async function resumeTask(form: {
    workerId: string
    prompt: string
    cwd?: string
    directoryId?: string
    sessionId?: string
    model?: string
    maxTurns?: number
    agentTeamsJson?: string
    agentTeamsConfigId?: string
    images?: string
    permissionMode?: string
    modelConfigId?: string
    agentId?: string
  }) {
    // 使用统一任务 API（/api/v1/tasks/resume）
    const task = await resumeTaskUnified(form) as unknown as ClaudeTask
    tasks.value.unshift(task)
    return task
  }

  async function respondToPermission(taskId: string, form: { permissionId: string; decision: string; denyMessage?: string; scope?: string; answers?: Record<string, string>; planAction?: string }) {
    // 使用统一任务 API（/api/v1/tasks/{taskId}/respond）
    return respondToTaskUnified(taskId, form)
  }

  async function abortTask(taskId: string) {
    // 使用统一任务 API（/api/v1/tasks/{taskId}/cancel）
    await cancelTaskUnified(taskId)
    const idx = tasks.value.findIndex((t) => t.taskId === taskId)
    if (idx >= 0) tasks.value[idx]!.status = 'ABORTED'
  }

  async function deleteTask(taskId: string) {
    // 使用统一任务 API（DELETE /api/v1/tasks/{taskId}）
    await deleteTaskUnified(taskId)
    tasks.value = tasks.value.filter((t) => t.taskId !== taskId)
  }

  // ===== Directory methods =====

  async function loadDirectories(workerId: string) {
    const newDirs = await api.listDirectoriesByWorker(workerId)
    // Merge: remove old entries for this worker, then add fresh data
    directories.value = directories.value.filter((d) => d.workerId !== workerId).concat(newDirs)
  }

  async function createDirectory(form: { workerId: string; projectName: string; path: string; directoryType?: string; parentProjectId?: string }) {
    const dir = await api.createDirectory(form)
    directories.value.push(dir)
    return dir
  }

  async function deleteDirectory(directoryId: string) {
    await api.deleteDirectory(directoryId)
    directories.value = directories.value.filter((d) => d.directoryId !== directoryId)
  }

  async function syncGitInfo(directoryId: string) {
    const updated = await api.syncDirectoryGitInfo(directoryId)
    const idx = directories.value.findIndex((d) => d.directoryId === directoryId)
    if (idx >= 0) directories.value[idx] = updated
    return updated
  }

  async function syncSessions(workerId: string) {
    const result = await api.syncWorkerSessions(workerId)
    return result
  }

  // ===== Active tasks =====

  async function loadActiveTasks() {
    try {
      // 使用统一 API（/api/v1/tasks）聚合所有 Agent 的活跃任务
      const unified = await listTasksUnified()
      activeTasks.value = unified as unknown as ClaudeTask[]
    } catch {
      activeTasks.value = []
    }
  }

  async function loadAwaitingReplyTasks() {
    try {
      awaitingReplyTasks.value = await api.listAwaitingReplyTasks()
    } catch {
      awaitingReplyTasks.value = []
    }
  }

  async function updateTags(sessionId: string, tags: string[]) {
    const config = await api.updateConversationTags(sessionId, tags)
    conversationConfigs.value.set(config.sessionId, config)
    return config
  }

  // ===== Conversation Config methods =====

  async function loadConversationConfigs(sessionIds: string[]) {
    if (sessionIds.length === 0) return
    try {
      const configs = await api.listConversationConfigs(sessionIds)
      for (const config of configs) {
        conversationConfigs.value.set(config.sessionId, config)
      }
    } catch {
      // best-effort
    }
  }

  async function togglePin(sessionId: string, pinned: boolean) {
    const config = await api.updateConversationPin(sessionId, pinned)
    conversationConfigs.value.set(config.sessionId, config)
    return config
  }

  async function setTitle(sessionId: string, title: string) {
    const config = await api.updateConversationTitle(sessionId, title)
    conversationConfigs.value.set(config.sessionId, config)
    return config
  }

  async function setMilestone(sessionId: string, milestoneId?: string) {
    const config = await api.updateConversationMilestone(sessionId, milestoneId)
    conversationConfigs.value.set(config.sessionId, config)
    return config
  }

  async function bindAuth(sessionId: string, form: { authMode: string; authToken: string; baseUrl?: string }) {
    const config = await api.bindConversationAuth(sessionId, form)
    conversationConfigs.value.set(config.sessionId, config)
    return config
  }

  async function updateAuth(sessionId: string, form: { authMode: string; authToken: string; baseUrl?: string }) {
    const config = await api.updateConversationAuth(sessionId, form)
    conversationConfigs.value.set(config.sessionId, config)
    return config
  }

  async function batchBindAuth(form: {
    sessionIds: string[]
    authMode: string
    authToken: string
    baseUrl?: string
    skipExisting?: boolean
    modelConfigId?: string
  }) {
    const result = await api.batchBindConversationAuth(form)
    // Reload configs for affected sessions
    if (form.sessionIds.length > 0) {
      await loadConversationConfigs(form.sessionIds)
    }
    return result
  }

  async function archiveConversation(sessionId: string) {
    const config = await api.archiveConversation(sessionId)
    conversationConfigs.value.set(config.sessionId, config)
    return config
  }

  async function unarchiveConversation(sessionId: string) {
    const config = await api.unarchiveConversation(sessionId)
    conversationConfigs.value.set(config.sessionId, config)
    return config
  }

  async function holdConversation(sessionId: string) {
    const config = await api.holdConversation(sessionId)
    conversationConfigs.value.set(config.sessionId, config)
    return config
  }

  async function unholdConversation(sessionId: string) {
    const config = await api.unholdConversation(sessionId)
    conversationConfigs.value.set(config.sessionId, config)
    return config
  }

  function updateInteractionStateFromSSE(sessionId: string, interactionState: string) {
    const config = conversationConfigs.value.get(sessionId)
    if (config) {
      config.interactionState = interactionState as ConversationConfig['interactionState']
    }
  }

  return {
    workers,
    tasks,
    directories,
    loading,
    taskPage,
    taskSize,
    taskTotal,
    onlineWorkers,
    loadWorkers,
    loadTasks,
    loadTasksPage,
    registerWorker,
    updateWorker,
    deleteWorker,
    refreshWorkerStatus,
    syncSkills,
    createTask,
    resumeTask,
    respondToPermission,
    abortTask,
    deleteTask,
    loadDirectories,
    createDirectory,
    deleteDirectory,
    syncGitInfo,
    syncSessions,
    activeTasks,
    loadActiveTasks,
    awaitingReplyTasks,
    loadAwaitingReplyTasks,
    updateTags,
    conversationConfigs,
    loadConversationConfigs,
    togglePin,
    setTitle,
    setMilestone,
    bindAuth,
    updateAuth,
    batchBindAuth,
    archiveConversation,
    unarchiveConversation,
    holdConversation,
    unholdConversation,
    updateInteractionStateFromSSE,
  }
}
