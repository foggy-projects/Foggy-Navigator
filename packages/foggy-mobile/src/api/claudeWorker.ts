/**
 * Claude Worker API — Worker and Directory management only.
 *
 * Task-related functions have been migrated to unifiedTask.ts (/api/v1/tasks).
 * Session config functions are in conversationConfig.ts (/api/v1/sessions/{id}/config).
 */
import client from './client'
import type { RX, ClaudeWorker, DirectoryMilestone, WorkingDirectory } from './types'

// ===== Worker API =====

export async function listWorkers(): Promise<ClaudeWorker[]> {
  const rx = (await client.get('/claude-workers')) as unknown as RX<ClaudeWorker[]>
  return rx.data
}

export async function triggerHealthCheck(workerId: string): Promise<ClaudeWorker> {
  const rx = (await client.post(`/claude-workers/${workerId}/health-check`)) as unknown as RX<ClaudeWorker>
  return rx.data
}

// ===== Working Directory API =====

export async function listDirectoriesByWorker(workerId: string): Promise<WorkingDirectory[]> {
  const rx = (await client.get(`/working-directories/worker/${workerId}`)) as unknown as RX<WorkingDirectory[]>
  return rx.data
}

export async function syncDirectoryGitInfo(directoryId: string): Promise<WorkingDirectory> {
  const rx = (await client.post(`/working-directories/${directoryId}/sync`)) as unknown as RX<WorkingDirectory>
  return rx.data
}

export async function listMilestones(directoryId: string): Promise<DirectoryMilestone[]> {
  const rx = (await client.get(
    `/working-directories/${directoryId}/milestones`,
  )) as unknown as RX<DirectoryMilestone[]>
  return rx.data
}
