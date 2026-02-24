import { ref, computed } from 'vue'
import * as api from '@/api/claudeWorker'
import type { ClaudeWorker, ClaudeTask, WorkingDirectory, ConversationConfig } from '@/types'

const workers = ref<ClaudeWorker[]>([])
const tasks = ref<ClaudeTask[]>([])
const directories = ref<WorkingDirectory[]>([])
const loading = ref(false)
const taskPage = ref(0)
const taskSize = ref(20)
const taskTotal = ref(0)
const conversationConfigs = ref<Map<string, ConversationConfig>>(new Map())

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

  async function loadTasks() {
    try {
      const result = await api.listTasksPaged(taskPage.value, taskSize.value)
      tasks.value = result.content
      taskTotal.value = result.totalElements
    } catch {
      // Fallback to non-paged API
      tasks.value = await api.listTasks()
      taskTotal.value = tasks.value.length
    }
  }

  async function loadTasksPage(page: number, size?: number) {
    taskPage.value = page
    if (size !== undefined) taskSize.value = size
    await loadTasks()
  }

  async function registerWorker(form: {
    name: string
    baseUrl: string
    authToken: string
    authMode?: string
  }) {
    const worker = await api.registerWorker(form)
    workers.value.push(worker)
    return worker
  }

  async function updateWorker(
    workerId: string,
    form: { name?: string; baseUrl?: string; authToken?: string; authMode?: string },
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

  async function createTask(form: {
    workerId: string
    prompt: string
    cwd?: string
    directoryId?: string
    modelConfigId?: string
    maxTurns?: number
    agentTeamsJson?: string
    permissionMode?: string
  }) {
    const task = await api.createTask(form)
    tasks.value.unshift(task)
    return task
  }

  async function resumeTask(form: {
    workerId: string
    claudeSessionId: string
    prompt: string
    cwd?: string
    directoryId?: string
    sessionId?: string
    model?: string
    maxTurns?: number
    agentTeamsJson?: string
    permissionMode?: string
  }) {
    const task = await api.resumeTask(form)
    tasks.value.unshift(task)
    return task
  }

  async function respondToPermission(taskId: string, form: { permissionId: string; decision: string; denyMessage?: string; scope?: string; answers?: Record<string, string>; planAction?: string }) {
    return api.respondToPermission(taskId, form)
  }

  async function abortTask(taskId: string) {
    const result = await api.abortTask(taskId)
    const idx = tasks.value.findIndex((t) => t.taskId === taskId)
    if (idx >= 0) tasks.value[idx]!.status = 'ABORTED'
    return result
  }

  async function deleteTask(taskId: string) {
    const result = await api.deleteTask(taskId)
    tasks.value = tasks.value.filter((t) => t.taskId !== taskId)
    return result
  }

  // ===== Directory methods =====

  async function loadDirectories(workerId: string) {
    directories.value = await api.listDirectoriesByWorker(workerId)
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

  async function bindAuth(sessionId: string, form: { authMode: string; authToken: string; baseUrl?: string }) {
    const config = await api.bindConversationAuth(sessionId, form)
    conversationConfigs.value.set(config.sessionId, config)
    return config
  }

  async function batchBindAuth(form: {
    sessionIds: string[]
    authMode: string
    authToken: string
    baseUrl?: string
    skipExisting?: boolean
  }) {
    const result = await api.batchBindConversationAuth(form)
    // Reload configs for affected sessions
    if (form.sessionIds.length > 0) {
      await loadConversationConfigs(form.sessionIds)
    }
    return result
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
    conversationConfigs,
    loadConversationConfigs,
    togglePin,
    setTitle,
    bindAuth,
    batchBindAuth,
  }
}
