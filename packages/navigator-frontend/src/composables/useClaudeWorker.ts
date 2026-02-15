import { ref, computed } from 'vue'
import * as api from '@/api/claudeWorker'
import type { ClaudeWorker, ClaudeTask, WorkingDirectory } from '@/types'

const workers = ref<ClaudeWorker[]>([])
const tasks = ref<ClaudeTask[]>([])
const directories = ref<WorkingDirectory[]>([])
const loading = ref(false)
const taskPage = ref(0)
const taskSize = ref(20)
const taskTotal = ref(0)

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
  }) {
    const task = await api.resumeTask(form)
    tasks.value.unshift(task)
    return task
  }

  async function abortTask(taskId: string) {
    const result = await api.abortTask(taskId)
    const idx = tasks.value.findIndex((t) => t.taskId === taskId)
    if (idx >= 0) tasks.value[idx]!.status = 'ABORTED'
    return result
  }

  // ===== Directory methods =====

  async function loadDirectories(workerId: string) {
    directories.value = await api.listDirectoriesByWorker(workerId)
  }

  async function createDirectory(form: { workerId: string; projectName: string; path: string }) {
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
    abortTask,
    loadDirectories,
    createDirectory,
    deleteDirectory,
    syncGitInfo,
  }
}
