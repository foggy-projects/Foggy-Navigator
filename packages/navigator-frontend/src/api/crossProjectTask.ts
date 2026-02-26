import client from './client'
import type { RX, CrossProjectTask, CrossProjectPhase, ClaudeTask } from '@/types'

export async function createCrossProjectTask(form: {
  title: string
  description?: string
  initialSessionId?: string
  initialDirectoryId?: string
  phases: {
    phaseName: string
    prompt: string
    agentId?: string
    directoryId?: string
    worktreeBranch?: string
  }[]
}): Promise<CrossProjectTask> {
  const rx = (await client.post('/cross-project-tasks', form)) as unknown as RX<CrossProjectTask>
  return rx.data
}

export async function startCrossProjectTask(contextId: string): Promise<CrossProjectTask> {
  const rx = (await client.post(`/cross-project-tasks/${contextId}/start`)) as unknown as RX<CrossProjectTask>
  return rx.data
}

export async function getCrossProjectTask(contextId: string): Promise<CrossProjectTask> {
  const rx = (await client.get(`/cross-project-tasks/${contextId}`)) as unknown as RX<CrossProjectTask>
  return rx.data
}

export async function listCrossProjectTasks(
  page: number,
  size: number,
): Promise<{ content: CrossProjectTask[]; totalElements: number; totalPages: number }> {
  const rx = (await client.get('/cross-project-tasks/page', {
    params: { page, size },
  })) as unknown as RX<{ content: CrossProjectTask[]; totalElements: number; totalPages: number }>
  return rx.data
}

export async function triggerReview(contextId: string): Promise<ClaudeTask> {
  const rx = (await client.post(`/cross-project-tasks/${contextId}/review`)) as unknown as RX<ClaudeTask>
  return rx.data
}

export async function updateHandoff(
  contextId: string,
  phaseId: string,
  handoffArtifact: string,
): Promise<CrossProjectPhase> {
  const rx = (await client.put(
    `/cross-project-tasks/${contextId}/phases/${phaseId}/handoff`,
    { handoffArtifact },
  )) as unknown as RX<CrossProjectPhase>
  return rx.data
}

export async function advancePhase(
  contextId: string,
  handoffArtifact?: string,
): Promise<CrossProjectTask> {
  const rx = (await client.post(`/cross-project-tasks/${contextId}/advance`, {
    handoffArtifact: handoffArtifact || undefined,
  })) as unknown as RX<CrossProjectTask>
  return rx.data
}

export async function cancelCrossProjectTask(contextId: string): Promise<CrossProjectTask> {
  const rx = (await client.post(`/cross-project-tasks/${contextId}/cancel`)) as unknown as RX<CrossProjectTask>
  return rx.data
}
