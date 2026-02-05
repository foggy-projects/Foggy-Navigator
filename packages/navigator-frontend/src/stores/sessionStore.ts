import { ref } from 'vue'
import { defineStore } from 'pinia'
import * as sessionApi from '@/api/session'
import type { Session } from '@/types'

export const useSessionStore = defineStore('navigator-session', () => {
  const sessions = ref<Session[]>([])
  const activeSessionId = ref<string | null>(null)

  async function loadSessions() {
    sessions.value = await sessionApi.listSessions()
  }

  async function createSession(title: string): Promise<Session> {
    const session = await sessionApi.createSession(title)
    sessions.value.unshift(session)
    return session
  }

  async function deleteSession(id: string) {
    await sessionApi.deleteSession(id)
    sessions.value = sessions.value.filter((s) => s.id !== id)
    if (activeSessionId.value === id) {
      activeSessionId.value = null
    }
  }

  function setActiveSession(id: string | null) {
    activeSessionId.value = id
  }

  return {
    sessions,
    activeSessionId,
    loadSessions,
    createSession,
    deleteSession,
    setActiveSession,
  }
})
