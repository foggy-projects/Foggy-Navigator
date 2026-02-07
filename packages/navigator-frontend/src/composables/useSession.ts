import { ref } from 'vue'
import { useChatStore, createSseClient, AipMessageType } from '@foggy/chat'
import type { ChatMessage, SseController } from '@foggy/chat'
import { tutorAgentAdapter } from '@/adapters/TutorAgentAdapter'
import * as sessionApi from '@/api/session'
import { getToken } from '@/utils/auth'
import type { AgentMessage, RoutePayload } from '@/types'

let currentSseController: SseController | null = null
const connectedSessionId = ref<string | null>(null)

/** ROUTE_REQUEST 回调类型 */
export type RouteRequestHandler = (payload: RoutePayload) => void

/** 全局路由请求处理器 */
let routeRequestHandler: RouteRequestHandler | null = null

/** 设置路由请求处理器 */
export function setRouteRequestHandler(handler: RouteRequestHandler | null) {
  routeRequestHandler = handler
}

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

    currentSseController = createSseClient<AgentMessage>({
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
      onReconnecting: () => {
        chatStore.setConnectionStatus('connecting')
      },
      onRawEvent: (raw: AgentMessage) => {
        // 处理 ROUTE_REQUEST 事件（不经过 adapter 转换）
        if (raw.type === 'ROUTE_REQUEST' && routeRequestHandler) {
          console.log('ROUTE_REQUEST received:', raw.payload)
          routeRequestHandler(raw.payload as RoutePayload)
        }
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
