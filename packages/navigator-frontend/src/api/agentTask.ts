import client from './client'
import type { RX, AgentTask } from '@/types'

/**
 * 列出当前用户的所有任务
 */
export async function listAgentTasks(): Promise<AgentTask[]> {
  const rx = (await client.get('/agent-tasks')) as unknown as RX<AgentTask[]>
  return rx.data
}

/**
 * 列出某会话下的委派任务
 */
export async function listAgentTasksBySession(sessionId: string): Promise<AgentTask[]> {
  const rx = (await client.get(`/agent-tasks/session/${sessionId}`)) as unknown as RX<AgentTask[]>
  return rx.data
}
