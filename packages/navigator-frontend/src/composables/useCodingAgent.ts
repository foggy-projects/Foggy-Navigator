import { ref } from 'vue'
import type { CodingAgent } from '@/types'
import * as agentApi from '@/api/codingAgent'

const agents = ref<CodingAgent[]>([])
const loading = ref(false)

export function useCodingAgent() {
  async function loadAgents() {
    loading.value = true
    try {
      agents.value = await agentApi.listAgents()
    } finally {
      loading.value = false
    }
  }

  async function registerAgent(form: {
    name: string
    description?: string
    agentType?: string
    workerId: string
    defaultDirectoryId: string
    skills?: string
    defaultBranch?: string
  }) {
    const agent = await agentApi.registerAgent(form)
    agents.value.unshift(agent)
    return agent
  }

  async function updateAgent(agentId: string, form: {
    name?: string
    description?: string
    skills?: string
    defaultBranch?: string
    defaultDirectoryId?: string
  }) {
    const updated = await agentApi.updateAgent(agentId, form)
    const idx = agents.value.findIndex(a => a.agentId === agentId)
    if (idx >= 0) agents.value[idx] = updated
    return updated
  }

  async function deleteAgent(agentId: string) {
    await agentApi.deleteAgent(agentId)
    agents.value = agents.value.filter(a => a.agentId !== agentId)
  }

  async function bindDirectory(agentId: string, directoryId: string) {
    await agentApi.bindDirectory(agentId, directoryId)
  }

  async function unbindDirectory(agentId: string, directoryId: string) {
    await agentApi.unbindDirectory(agentId, directoryId)
  }

  return {
    agents,
    loading,
    loadAgents,
    registerAgent,
    updateAgent,
    deleteAgent,
    bindDirectory,
    unbindDirectory,
  }
}
