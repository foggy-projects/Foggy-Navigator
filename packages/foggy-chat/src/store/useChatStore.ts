import { defineStore } from 'pinia'
import { createChatState } from './chatState'

export const useChatStore = defineStore('foggy-chat', () => {
  const state = createChatState()

  return {
    messages: state.messages,
    sortedMessages: state.sortedMessages,
    connectionStatus: state.connectionStatus,
    conversationStatus: state.conversationStatus,
    isThinking: state.isThinking,
    processAipMessage: state.processAipMessage,
    addUserMessage: state.addUserMessage,
    setConnectionStatus: state.setConnectionStatus,
    clearMessages: state.clearMessages,
  }
})
