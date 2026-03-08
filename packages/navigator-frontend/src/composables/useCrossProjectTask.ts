import { ref, computed } from 'vue'
import type { CrossProjectTask } from '@/types'
import * as api from '@/api/crossProjectTask'

const tasks = ref<CrossProjectTask[]>([])
const selectedTask = ref<CrossProjectTask | null>(null)
const loading = ref(false)
const totalElements = ref(0)
const currentPage = ref(0)

export function useCrossProjectTask() {
  async function loadTasks(page = 0, size = 20) {
    loading.value = true
    try {
      const result = await api.listCrossProjectTasks(page, size)
      tasks.value = result.content
      totalElements.value = result.totalElements
      currentPage.value = page
    } finally {
      loading.value = false
    }
  }

  async function refreshSelectedTask() {
    if (!selectedTask.value) return
    try {
      selectedTask.value = await api.getCrossProjectTask(selectedTask.value.contextId)
      // Also update in list
      const idx = tasks.value.findIndex(t => t.contextId === selectedTask.value!.contextId)
      if (idx >= 0) tasks.value[idx] = selectedTask.value
    } catch (e) {
      console.error('Failed to refresh task:', e)
    }
  }

  async function selectTask(contextId: string) {
    loading.value = true
    try {
      selectedTask.value = await api.getCrossProjectTask(contextId)
    } finally {
      loading.value = false
    }
  }

  async function createTask(form: Parameters<typeof api.createCrossProjectTask>[0]) {
    const task = await api.createCrossProjectTask(form)
    tasks.value.unshift(task)
    selectedTask.value = task
    return task
  }

  async function startTask(contextId: string) {
    const task = await api.startCrossProjectTask(contextId)
    updateTaskInList(task)
    selectedTask.value = task
    return task
  }

  async function triggerReview(contextId: string) {
    return await api.triggerReview(contextId)
  }

  async function updateHandoff(contextId: string, phaseId: string, handoffArtifact: string) {
    const phase = await api.updateHandoff(contextId, phaseId, handoffArtifact)
    // Refresh selected task to pick up the change
    await refreshSelectedTask()
    return phase
  }

  async function advancePhase(contextId: string, handoffArtifact?: string) {
    const task = await api.advancePhase(contextId, handoffArtifact)
    updateTaskInList(task)
    selectedTask.value = task
    return task
  }

  async function cancelTask(contextId: string) {
    const task = await api.cancelCrossProjectTask(contextId)
    updateTaskInList(task)
    selectedTask.value = task
    return task
  }

  function updateTaskInList(task: CrossProjectTask) {
    const idx = tasks.value.findIndex(t => t.contextId === task.contextId)
    if (idx >= 0) {
      tasks.value[idx] = task
    }
  }

  const activePhase = computed(() => {
    if (!selectedTask.value) return null
    const idx = selectedTask.value.currentPhaseIndex
    if (idx == null) return null
    return selectedTask.value.phases.find(p => p.phaseIndex === idx) ?? null
  })

  return {
    tasks,
    selectedTask,
    loading,
    totalElements,
    currentPage,
    activePhase,
    loadTasks,
    selectTask,
    refreshSelectedTask,
    createTask,
    startTask,
    triggerReview,
    updateHandoff,
    advancePhase,
    cancelTask,
  }
}
