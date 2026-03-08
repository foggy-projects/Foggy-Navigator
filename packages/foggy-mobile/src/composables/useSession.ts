import { ref, watch } from 'vue'
import { AipMessageType } from '@foggy/chat-core'
import type { ChatMessage } from '@foggy/chat-core'
import { useChatStore } from '@/stores/chat'
import { tutorAgentAdapter } from '@/adapters/TutorAgentAdapter'
import { useUnifiedSse } from '@/composables/useUnifiedSse'
import * as sessionApi from '@/api/session'
import type { AgentMessage } from '@/api/types'

let unsubscribeSse: (() => void) | null = null
let connectVersion = 0
const connectedSessionId = ref<string | null>(null)

export function useSession() {
  const chatStore = useChatStore()
  const { subscribeSession, connected } = useUnifiedSse()

  async function connectToSession(sessionId: string) {
    disconnectSession()
    const myVersion = ++connectVersion
    chatStore.clearMessages()
    chatStore.setConnectionStatus('connecting')
    connectedSessionId.value = sessionId

    // 加载历史消息
    try {
      const messages = await sessionApi.getMessages(sessionId)
      if (connectVersion !== myVersion) return
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
      if (connectVersion !== myVersion) return
      console.error('Failed to load history messages:', e)
    }

    if (connectVersion !== myVersion) return

    // Subscribe to session events via unified SSE
    unsubscribeSse = subscribeSession(sessionId, (raw: AgentMessage) => {
      const msgs = tutorAgentAdapter.convert(raw, sessionId)
      for (const msg of msgs) {
        chatStore.processAipMessage(msg)
      }

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
    })

    chatStore.setConnectionStatus(connected.value ? 'connected' : 'connecting')
    watch(connected, (val) => {
      chatStore.setConnectionStatus(val ? 'connected' : 'connecting')
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
    connectVersion++
    if (unsubscribeSse) {
      unsubscribeSse()
      unsubscribeSse = null
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
