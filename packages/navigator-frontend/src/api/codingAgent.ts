import client from './client'
import type { RX, CodingAgent, DirectorySummary, A2aTask } from '@/types'

export async function listAgents(): Promise<CodingAgent[]> {
  const rx = (await client.get('/coding-agents')) as unknown as RX<CodingAgent[]>
  return rx.data
}

export async function getAgent(agentId: string): Promise<CodingAgent> {
  const rx = (await client.get(`/coding-agents/${agentId}`)) as unknown as RX<CodingAgent>
  return rx.data
}

export async function registerAgent(form: {
  name: string
  description?: string
  agentType?: string
  workerId: string
  defaultDirectoryId: string
  skills?: string
  defaultBranch?: string
}): Promise<CodingAgent> {
  const rx = (await client.post('/coding-agents', form)) as unknown as RX<CodingAgent>
  return rx.data
}

export async function updateAgent(
  agentId: string,
  form: { name?: string; description?: string; skills?: string; defaultBranch?: string; defaultDirectoryId?: string; projectSummary?: string },
): Promise<CodingAgent> {
  const rx = (await client.put(`/coding-agents/${agentId}`, form)) as unknown as RX<CodingAgent>
  return rx.data
}

export async function generateSummary(agentId: string, hint?: string): Promise<CodingAgent> {
  const rx = (await client.post(`/coding-agents/${agentId}/generate-summary`, hint ? { hint } : {})) as unknown as RX<CodingAgent>
  return rx.data
}

export async function deleteAgent(agentId: string): Promise<void> {
  await client.delete(`/coding-agents/${agentId}`)
}

export async function getAgentDirectories(agentId: string): Promise<DirectorySummary[]> {
  const rx = (await client.get(`/coding-agents/${agentId}/directories`)) as unknown as RX<DirectorySummary[]>
  return rx.data
}

export async function bindDirectory(agentId: string, directoryId: string): Promise<void> {
  await client.post(`/coding-agents/${agentId}/directories`, { directoryId })
}

export async function unbindDirectory(agentId: string, directoryId: string): Promise<void> {
  await client.delete(`/coding-agents/${agentId}/directories/${directoryId}`)
}

/** 向 Agent 提问（A2A ask），可选传入 sessionId 记录咨询 */
export async function askAgent(agentId: string, question: string, sessionId?: string): Promise<A2aTask> {
  const payload: Record<string, string> = { question }
  if (sessionId) payload.sessionId = sessionId
  const rx = (await client.post(`/agents/${agentId}/ask`, payload)) as unknown as RX<A2aTask>
  return rx.data
}
