import { ref } from 'vue'
import { useChatStore, AipMessageType } from '@foggy/chat'
import { ElNotification } from 'element-plus'
import { tutorAgentAdapter } from '@/adapters/TutorAgentAdapter'
import * as sessionApi from '@/api/session'
import { useUnifiedSse } from '@/composables/useUnifiedSse'
import type { AgentMessage, RoutePayload } from '@/types'
import router from '@/router'

let unsubscribeSse: (() => void) | null = null
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
  const { subscribeSession, connected } = useUnifiedSse()

  async function connectToSession(sessionId: string) {
    disconnectSession()
    chatStore.clearMessages()
    chatStore.setConnectionStatus('connecting')
    connectedSessionId.value = sessionId

    // 加载历史消息 — 从 metadata 还原原始类型，走 processAipMessage 统一路径
    try {
      const messages = await sessionApi.getMessages(sessionId)
      for (const msg of messages) {
        const meta = msg.metadata
        const msgType = meta?.type as string | undefined
        const ts = new Date(msg.createdAt).getTime()

        if (msg.role === 'USER') {
          // User messages — push directly
          chatStore.messages.push({
            id: msg.id,
            type: AipMessageType.TEXT_COMPLETE,
            sender: 'user',
            content: msg.content || '',
            timestamp: ts,
          })
        } else if (msgType && msgType in AipMessageType) {
          // Structured messages — reconstruct AipMessage from metadata and process
          chatStore.processAipMessage({
            messageId: msg.id,
            sessionId: msg.sessionId,
            timestamp: ts,
            type: msgType as AipMessageType,
            payload: meta,
          })
        } else {
          // Fallback — render as plain text
          chatStore.messages.push({
            id: msg.id,
            type: AipMessageType.TEXT_COMPLETE,
            sender: 'assistant',
            content: msg.content || '',
            timestamp: ts,
          })
        }
      }
    } catch (e) {
      console.error('Failed to load history messages:', e)
    }

    // Subscribe to session events via unified SSE
    unsubscribeSse = subscribeSession(sessionId, (raw: AgentMessage) => {
      // Pass through adapter for chat messages
      const msgs = tutorAgentAdapter.convert(raw, sessionId)
      for (const msg of msgs) {
        chatStore.processAipMessage(msg)
      }

      // 处理 ROUTE_REQUEST 事件（不经过 adapter 转换）
      if (raw.type === AipMessageType.ROUTE_REQUEST && routeRequestHandler) {
        console.log('ROUTE_REQUEST received:', raw.payload)
        routeRequestHandler(raw.payload as RoutePayload)
      }
      // 处理 TASK_COMPLETED 事件 — toast 通知
      if (raw.type === AipMessageType.TASK_COMPLETED) {
        const p = raw.payload as Record<string, string>
        const status = p.status || 'COMPLETED'
        const agent = p.targetAgentId || p.taskType || ''
        ElNotification({
          title: status === 'FAILED' ? '任务失败' : '任务完成',
          message: `${agent} ${p.resultSummary ? '— ' + p.resultSummary.substring(0, 80) : ''}`,
          type: status === 'FAILED' ? 'error' : 'success',
          duration: 6000,
          onClick: () => router.push('/tasks'),
        })
      }
    })

    chatStore.setConnectionStatus(connected.value ? 'connected' : 'connecting')
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
