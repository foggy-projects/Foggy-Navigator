import client from './client'
import type { RX, SetupStatus, LlmModelConfig, AgentModelOverride } from './types'

const BASE = '/config/platform'

export async function getSetupStatus(): Promise<SetupStatus> {
  const rx = (await client.get(`${BASE}/setup-status`)) as unknown as RX<SetupStatus>
  return rx.data
}

export async function listModelConfigs(workerId?: string): Promise<LlmModelConfig[]> {
  const params = workerId ? { workerId } : {}
  const rx = (await client.get(`${BASE}/llm`, { params })) as unknown as RX<LlmModelConfig[]>
  return rx.data
}

export async function listAgentModelOverrides(): Promise<AgentModelOverride[]> {
  const rx = (await client.get(`${BASE}/agent-model`)) as unknown as RX<AgentModelOverride[]>
  return rx.data
}
