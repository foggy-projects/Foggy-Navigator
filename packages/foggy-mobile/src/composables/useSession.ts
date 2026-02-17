import { ref } from 'vue'
import { AipMessageType } from '@foggy/chat-core'
import type { ChatMessage } from '@foggy/chat-core'
import { useChatStore } from '@/stores/chat'
import { tutorAgentAdapter } from '@/adapters/TutorAgentAdapter'
import { createUniSseClient } from '@/sse/UniSseClient'
import type { UniSseController } from '@/sse/types'
import * as sessionApi from '@/api/session'
import { getToken } from '@/utils/auth'
import { getSseBaseUrl } from '@/utils/config'
import type { AgentMessage } from '@/api/types'

let currentSseController: UniSseController | null = null
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
    const sseUrl = `${getSseBaseUrl()}/sessions/${sessionId}/stream`

    currentSseController = createUniSseClient<AgentMessage>({
      url: sseUrl,
      headers: token ? { Authorization: `Bearer ${token}` } : undefined,
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
      onReconnecting: () => {
        chatStore.setConnectionStatus('connecting')
      },
      onMaxRetriesExceeded: () => {
        uni.showToast({ title: '连接已断开，请返回重试', icon: 'none' })
      },
      onRawEvent: (raw: AgentMessage) => {
        // 处理 TASK_COMPLETED 事件 — toast 通知
        if (raw.type === AipMessageType.TASK_COMPLETED) {
          const p = raw.payload as Record<string, string>
          const status = p.status || 'COMPLETED'
          const agent = p.targetAgentId || p.taskType || ''
          uni.showToast({
            title: `${status === 'FAILED' ? '任务失败' : '任务完成'}: ${agent}`,
            icon: status === 'FAILED' ? 'error' : 'success',
            duration: 3000,
          })
        }
      },
    })
  }

  async function sendMessage(sessionId: string, content: string) {
    chatStore.addUserMessage(content, sessionId)
    chatStore.isThinking = true
    try {
      await sessionApi.sendMessage(sessionId, content)
    } catch (e) {
      console.error('Failed to send message:', e)
      chatStore.isThinking = false
    }
  }

  function disconnectSession() {
    if (currentSseController) {
      currentSseController.close()
      currentSseController = null
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
