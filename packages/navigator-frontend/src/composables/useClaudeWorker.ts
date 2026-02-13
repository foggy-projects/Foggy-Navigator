import { ref, computed } from 'vue'
import * as api from '@/api/claudeWorker'
import type { ClaudeWorker, ClaudeTask } from '@/types'

const workers = ref<ClaudeWorker[]>([])
const tasks = ref<ClaudeTask[]>([])
const loading = ref(false)

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
    tasks.value = await api.listTasks()
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

  async function createTask(form: { workerId: string; prompt: string; cwd?: string }) {
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
    if (idx >= 0) tasks.value[idx].status = 'ABORTED'
    return result
  }

  return {
    workers,
    tasks,
    loading,
    onlineWorkers,
    loadWorkers,
    loadTasks,
    registerWorker,
    updateWorker,
    deleteWorker,
    refreshWorkerStatus,
    createTask,
    resumeTask,
    abortTask,
  }
}
