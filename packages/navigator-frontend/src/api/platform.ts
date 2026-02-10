import client from './client'
import type {
  RX,
  SetupStatus,
  GitProviderConfig,
  GitProviderConfigForm,
  LlmModelConfig,
  LlmModelConfigForm,
  AgentModelOverride,
} from '@/types'

const BASE = '/config/platform'

// ===== 初始化状态 =====

export async function getSetupStatus(): Promise<SetupStatus> {
  const rx = (await client.get(`${BASE}/setup-status`)) as unknown as RX<SetupStatus>
  return rx.data
}

// ===== Git 提供者 =====

export async function listGitProviders(): Promise<GitProviderConfig[]> {
  const rx = (await client.get(`${BASE}/git`)) as unknown as RX<GitProviderConfig[]>
  return rx.data
}

export async function getGitProvider(id: string): Promise<GitProviderConfig> {
  const rx = (await client.get(`${BASE}/git/${id}`)) as unknown as RX<GitProviderConfig>
  return rx.data
}

export async function saveGitProvider(form: GitProviderConfigForm): Promise<string> {
  const rx = (await client.post(`${BASE}/git`, form)) as unknown as RX<string>
  return rx.data
}

export async function updateGitProvider(id: string, form: Partial<GitProviderConfigForm>): Promise<void> {
  await client.put(`${BASE}/git/${id}`, form)
}

export async function deleteGitProvider(id: string): Promise<void> {
  await client.delete(`${BASE}/git/${id}`)
}

// ===== LLM 模型配置 =====

export async function listModelConfigs(): Promise<LlmModelConfig[]> {
  const rx = (await client.get(`${BASE}/llm`)) as unknown as RX<LlmModelConfig[]>
  return rx.data
}

export async function getModelConfig(id: string): Promise<LlmModelConfig> {
  const rx = (await client.get(`${BASE}/llm/${id}`)) as unknown as RX<LlmModelConfig>
  return rx.data
}

export async function testLlmConnection(form: Pick<LlmModelConfigForm, 'baseUrl' | 'apiKey' | 'modelName'>): Promise<string> {
  const rx = (await client.post(`${BASE}/llm/test-connection`, form)) as unknown as RX<string>
  return rx.data
}

export async function testSavedLlmConnection(id: string): Promise<string> {
  const rx = (await client.post(`${BASE}/llm/${id}/test-connection`)) as unknown as RX<string>
  return rx.data
}

export async function saveModelConfig(form: LlmModelConfigForm): Promise<string> {
  const rx = (await client.post(`${BASE}/llm`, form)) as unknown as RX<string>
  return rx.data
}

export async function updateModelConfig(id: string, form: Partial<LlmModelConfigForm>): Promise<void> {
  await client.put(`${BASE}/llm/${id}`, form)
}

export async function deleteModelConfig(id: string): Promise<void> {
  await client.delete(`${BASE}/llm/${id}`)
}

// ===== Agent 模型覆盖 =====

export async function listAgentModelOverrides(): Promise<AgentModelOverride[]> {
  const rx = (await client.get(`${BASE}/agent-model`)) as unknown as RX<AgentModelOverride[]>
  return rx.data
}

export async function setAgentModelOverride(form: AgentModelOverride): Promise<void> {
  await client.post(`${BASE}/agent-model`, form)
}

export async function removeAgentModelOverride(agentId: string): Promise<void> {
  await client.delete(`${BASE}/agent-model/${agentId}`)
}
