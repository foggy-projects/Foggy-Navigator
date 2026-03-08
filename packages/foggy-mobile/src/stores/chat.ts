import { defineStore } from 'pinia'
import { createChatState } from '@foggy/chat-core'

export const useChatStore = defineStore('chat', () => {
  const state = createChatState()
  return { ...state }
})
