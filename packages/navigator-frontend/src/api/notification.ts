import client from './client'
import type { RX } from '@/types'

export interface TaskAssistantConfig {
  userId: string
  enabled: boolean
  foggySessionId: string | null
}

export interface A2aAgentCard {
  name: string
  description: string
  url: string | null
  version: string
  skills: Array<{
    id: string
    name: string
    description: string
    tags: string[]
    examples: string[]
  }>
  capabilities: {
    streaming: boolean
    pushNotifications: boolean
    stateTransitionHistory: boolean
  }
}

export async function getAssistantConfig(): Promise<TaskAssistantConfig | null> {
  const rx = (await client.get('/task-assistant/config')) as unknown as RX<TaskAssistantConfig | null>
  return rx.data
}

export async function updateAssistantConfig(form: {
  enabled?: boolean
}): Promise<TaskAssistantConfig> {
  const rx = (await client.put('/task-assistant/config', form)) as unknown as RX<TaskAssistantConfig>
  return rx.data
}

export async function testNotification(): Promise<any> {
  const rx = (await client.post('/task-assistant/test')) as unknown as RX<any>
  return rx.data
}

export async function getAgentCard(): Promise<A2aAgentCard> {
  const rx = (await client.get('/task-assistant/agent-card')) as unknown as RX<A2aAgentCard>
  return rx.data
}
