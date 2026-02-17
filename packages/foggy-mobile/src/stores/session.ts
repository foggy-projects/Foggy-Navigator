import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as sessionApi from '@/api/session'
import type { Session } from '@/api/types'

export const useSessionStore = defineStore('session', () => {
  const sessions = ref<Session[]>([])
  const loading = ref(false)

  async function loadSessions() {
    loading.value = true
    try {
      sessions.value = await sessionApi.listSessions()
    } catch (e) {
      console.error('Failed to load sessions:', e)
    } finally {
      loading.value = false
    }
  }

  async function createSession(title: string): Promise<Session | null> {
    try {
      const session = await sessionApi.createSession(title)
      sessions.value.unshift(session)
      return session
    } catch (e) {
      console.error('Failed to create session:', e)
      return null
    }
  }

  async function removeSession(id: string) {
    try {
      await sessionApi.deleteSession(id)
      sessions.value = sessions.value.filter((s) => s.id !== id)
    } catch (e) {
      console.error('Failed to delete session:', e)
    }
  }

  return {
    sessions,
    loading,
    loadSessions,
    createSession,
    removeSession,
  }
})
