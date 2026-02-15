import { ref, type Ref } from 'vue'
import { createChatState, createSseClient, AipMessageType } from '@foggy/chat'
import type { ChatState, SseController, ChatMessage } from '@foggy/chat'
import { tutorAgentAdapter } from '@/adapters/TutorAgentAdapter'
import * as sessionApi from '@/api/session'
import { getToken } from '@/utils/auth'
import type { AgentMessage, ClaudeTask } from '@/types'

export interface TaskPaneState {
  paneId: string
  task: Ref<ClaudeTask | null>
  chatState: ChatState
  connect(sessionId: string): Promise<void>
  disconnect(): void
  dispose(): void
}

export interface UseTaskPaneOptions {
  /** Called when task reaches a terminal state (COMPLETED / FAILED) */
  onTaskFinished?: (paneId: string) => void
}

export function useTaskPane(paneId: string, options?: UseTaskPaneOptions): TaskPaneState {
  const task = ref<ClaudeTask | null>(null)
  const chatState = createChatState()
  let sseController: SseController | null = null

  async function connect(sessionId: string) {
    disconnect()
    chatState.clearMessages()
    chatState.setConnectionStatus('connecting')

    // Load history messages
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
        chatState.messages.value.push(chatMsg)
      }
    } catch (e) {
      console.error(`[TaskPane ${paneId}] Failed to load history:`, e)
    }

    // Create independent SSE connection
    const token = getToken()
    const sseUrl = `/api/v1/sessions/${sessionId}/stream`

    sseController = createSseClient<AgentMessage>({
      url: sseUrl,
      headers: token ? { Authorization: `Bearer ${token}` } : undefined,
      adapter: tutorAgentAdapter,
      sessionId,
      onMessage: (msgs) => {
        for (const msg of msgs) {
          chatState.processAipMessage(msg)
        }
      },
      onConnected: () => {
        chatState.setConnectionStatus('connected')
      },
      onError: () => {
        chatState.setConnectionStatus('error')
      },
      onReconnecting: () => {
        chatState.setConnectionStatus('connecting')
      },
      onMaxRetriesExceeded: () => {
        chatState.setConnectionStatus('error')
      },
      onRawEvent: (raw: AgentMessage) => {
        if (!task.value) return
        const payload = raw.payload as Record<string, unknown> | undefined
        if (!payload?.taskId) return

        if (raw.type === 'TEXT_COMPLETE') {
          task.value.status = 'COMPLETED'
          if (typeof payload.costUsd === 'number') task.value.costUsd = payload.costUsd
          if (typeof payload.durationMs === 'number') task.value.durationMs = payload.durationMs
          if (typeof payload.claudeSessionId === 'string')
            task.value.claudeSessionId = payload.claudeSessionId
          options?.onTaskFinished?.(paneId)
        } else if (raw.type === 'ERROR') {
          task.value.status = 'FAILED'
          if (typeof payload.errorMessage === 'string')
            task.value.errorMessage = payload.errorMessage
          options?.onTaskFinished?.(paneId)
        }
      },
    })
  }

  function disconnect() {
    if (sseController) {
      sseController.close()
      sseController = null
    }
    chatState.setConnectionStatus('disconnected')
  }

  function dispose() {
    disconnect()
    chatState.clearMessages()
  }

  return {
    paneId,
    task,
    chatState,
    connect,
    disconnect,
    dispose,
  }
}
