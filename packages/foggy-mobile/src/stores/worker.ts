import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as workerApi from '@/api/claudeWorker'
import type { ClaudeWorker, WorkingDirectory, ClaudeTask, PageResult } from '@/api/types'

export const useWorkerStore = defineStore('worker', () => {
  const workers = ref<ClaudeWorker[]>([])
  const directories = ref<Map<string, WorkingDirectory[]>>(new Map())
  const loading = ref(false)

  async function loadWorkers() {
    loading.value = true
    try {
      workers.value = await workerApi.listWorkers()
    } catch (e) {
      console.error('Failed to load workers:', e)
    } finally {
      loading.value = false
    }
  }

  async function loadDirectories(workerId: string) {
    try {
      const dirs = await workerApi.listDirectoriesByWorker(workerId)
      directories.value.set(workerId, dirs)
    } catch (e) {
      console.error('Failed to load directories:', e)
    }
  }

  function getDirectories(workerId: string): WorkingDirectory[] {
    return directories.value.get(workerId) ?? []
  }

  /** PROJECT 类型目录 */
  function getProjectDirectories(workerId: string): WorkingDirectory[] {
    return getDirectories(workerId).filter((d) => d.directoryType === 'PROJECT')
  }

  /** 某 PROJECT 下的子目录 */
  function getChildDirectories(workerId: string, projectDirectoryId: string): WorkingDirectory[] {
    return getDirectories(workerId).filter((d) => d.parentProjectId === projectDirectoryId)
  }

  /** 不属于任何 PROJECT 的普通目录 */
  function getOrphanDirectories(workerId: string): WorkingDirectory[] {
    return getDirectories(workerId).filter(
      (d) => d.directoryType !== 'PROJECT' && !d.parentProjectId,
    )
  }

  async function healthCheck(workerId: string) {
    try {
      const updated = await workerApi.triggerHealthCheck(workerId)
      const idx = workers.value.findIndex((w) => w.workerId === workerId)
      if (idx >= 0) workers.value[idx] = updated
      return updated
    } catch (e) {
      console.error('Health check failed:', e)
      return null
    }
  }

  async function loadTasksByDirectory(
    directoryId: string,
    page: number,
    size: number,
  ): Promise<PageResult<ClaudeTask>> {
    return await workerApi.listTasksByDirectoryPaged(directoryId, page, size)
  }

  return {
    workers,
    directories,
    loading,
    loadWorkers,
    loadDirectories,
    getDirectories,
    getProjectDirectories,
    getChildDirectories,
    getOrphanDirectories,
    healthCheck,
    loadTasksByDirectory,
  }
})
