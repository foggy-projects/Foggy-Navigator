import { ref } from 'vue'
import { useChatStore, createSseClient, AipMessageType } from '@foggy/chat'
import type { ChatMessage } from '@foggy/chat'
import { tutorAgentAdapter } from '@/adapters/TutorAgentAdapter'
import * as sessionApi from '@/api/session'
import { getToken } from '@/utils/auth'
import type { AgentMessage } from '@/types'

let currentEventSource: EventSource | null = null
const connectedSessionId = ref<string | null>(null)

export function useSession() {
  const chatStore = useChatStore()

  async function connectToSession(sessionId: string) {
    disconnectSession()
    chatStore.clearMessages()
    chatStore.setConnectionStatus('connecting')
    connectedSessionId.value = sessionId

    // 加载历史消息
    try {
      const messages = await sessionApi.getMessages(sessionId)
      for (const msg of messages) {
        const chatMsg: ChatMessage = {
          id: msg.id,
          type: AipMessageType.TEXT_COMPLETE,
          sender: msg.role === 'USER' ? 'user' : 'assistant',
          content: msg.content,
          timestamp: new Date(msg.createdAt).getTime(),
        }
        chatStore.messages.push(chatMsg)
      }
    } catch (e) {
      console.error('Failed to load history messages:', e)
    }

    // 建立 SSE 连接
    const token = getToken()
    const sseUrl = `/api/v1/sessions/${sessionId}/stream${token ? `?token=${token}` : ''}`

    currentEventSource = createSseClient<AgentMessage>({
      url: sseUrl,
      adapter: tutorAgentAdapter,
      sessionId,
      onMessage: (msgs) => {
        for (const msg of msgs) {
          chatStore.processAipMessage(msg)
        }
      },
      onConnected: () => {
        chatStore.setConnectionStatus('connected')
      },
      onError: () => {
        chatStore.setConnectionStatus('error')
      },
    })
  }

  async function sendMessage(sessionId: string, content: string) {
    chatStore.addUserMessage(content, sessionId)
    chatStore.isThinking = true // Show typing indicator while waiting for response
    try {
      await sessionApi.sendMessage(sessionId, content)
    } catch (e) {
      console.error('Failed to send message:', e)
      chatStore.isThinking = false
    }
  }

  function disconnectSession() {
    if (currentEventSource) {
      currentEventSource.close()
      currentEventSource = null
    }
    connectedSessionId.value = null
    chatStore.setConnectionStatus('disconnected')
  }

  return {
    connectedSessionId,
    connectToSession,
    sendMessage,
    disconnectSession,
  }
}
