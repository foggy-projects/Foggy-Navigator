import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as workerApi from '@/api/claudeWorker'
import { listTasksByDirPagedUnified } from '@/api/unifiedTask'
import { listConversationConfigs } from '@/api/conversationConfig'
import type { ClaudeWorker, WorkingDirectory, DispatchTask, ConversationConfig, SessionPageResult } from '@/api/types'

export const useWorkerStore = defineStore('worker', () => {
  const workers = ref<ClaudeWorker[]>([])
  const directories = ref<Map<string, WorkingDirectory[]>>(new Map())
  const conversationConfigs = ref<Map<string, ConversationConfig>>(new Map())
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

  /** 某 PROJECT 下的子目录（排除 worktree，它们嵌套在源目录下） */
  function getChildDirectories(workerId: string, projectDirectoryId: string): WorkingDirectory[] {
    return getDirectories(workerId).filter((d) => d.parentProjectId === projectDirectoryId && !d.sourceDirectoryId)
  }

  /** 不属于任何 PROJECT 的普通目录（排除 worktree） */
  function getOrphanDirectories(workerId: string): WorkingDirectory[] {
    return getDirectories(workerId).filter(
      (d) => d.directoryType !== 'PROJECT' && !d.parentProjectId && !d.sourceDirectoryId,
    )
  }

  /** 某目录下关联的 worktree 列表 */
  function getWorktreesForDirectory(workerId: string, directoryId: string): WorkingDirectory[] {
    return getDirectories(workerId).filter((d) => d.sourceDirectoryId === directoryId)
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

  /** Load tasks by directory using unified task API (session-paginated) */
  async function loadTasksByDirectory(
    directoryId: string,
    page: number,
    size: number,
    state?: string,
  ): Promise<SessionPageResult<DispatchTask>> {
    return await listTasksByDirPagedUnified(directoryId, page, size, state)
  }

  /** Batch-load conversation configs for given session IDs */
  async function loadConversationConfigs(sessionIds: string[]): Promise<void> {
    const newIds = sessionIds.filter(id => !conversationConfigs.value.has(id))
    if (newIds.length === 0) return
    try {
      const configs = await listConversationConfigs(newIds)
      for (const cfg of configs) {
        conversationConfigs.value.set(cfg.sessionId, cfg)
      }
    } catch {
      // best-effort
    }
  }

  return {
    workers,
    directories,
    conversationConfigs,
    loading,
    loadWorkers,
    loadDirectories,
    getDirectories,
    getProjectDirectories,
    getChildDirectories,
    getOrphanDirectories,
    getWorktreesForDirectory,
    healthCheck,
    loadTasksByDirectory,
    loadConversationConfigs,
  }
})
