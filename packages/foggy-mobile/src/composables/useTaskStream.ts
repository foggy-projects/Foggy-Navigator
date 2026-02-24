import { ref } from 'vue'
import { createChatState, AipMessageType } from '@foggy/chat-core'
import type { ChatState, ChatMessage } from '@foggy/chat-core'
import { createUniSseClient } from '@/sse/UniSseClient'
import type { UniSseController } from '@/sse/types'
import { tutorAgentAdapter } from '@/adapters/TutorAgentAdapter'
import * as sessionApi from '@/api/session'
import { getToken } from '@/utils/auth'
import { getSseBaseUrl } from '@/utils/config'
import type { AgentMessage, ClaudeTask } from '@/api/types'

export interface TaskStreamState {
  task: ReturnType<typeof ref<ClaudeTask | null>>
  chatState: ChatState
  connect(sessionId: string): Promise<void>
  resumeInPlace(newTask: ClaudeTask): void
  disconnect(): void
}

export function useTaskStream(onTaskFinished?: () => void): TaskStreamState {
  const task = ref<ClaudeTask | null>(null)
  const chatState = createChatState()
  let sseController: UniSseController | null = null

  function createSseConnection(sessionId: string) {
    const token = getToken()
    const sseUrl = `${getSseBaseUrl()}/sessions/${sessionId}/stream`

    sseController = createUniSseClient<AgentMessage>({
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
        if (payload.taskId !== task.value.taskId) return

        if (typeof payload.claudeSessionId === 'string' && payload.claudeSessionId) {
          task.value.claudeSessionId = payload.claudeSessionId
        }

        if (raw.type === 'TEXT_COMPLETE') {
          task.value.status = 'COMPLETED'
          if (typeof payload.costUsd === 'number') task.value.costUsd = payload.costUsd
          if (typeof payload.durationMs === 'number') task.value.durationMs = payload.durationMs
          if (typeof payload.inputTokens === 'number') task.value.inputTokens = payload.inputTokens
          if (typeof payload.outputTokens === 'number') task.value.outputTokens = payload.outputTokens
          if (typeof payload.numTurns === 'number') task.value.numTurns = payload.numTurns
          if (typeof payload.model === 'string') task.value.model = payload.model
          onTaskFinished?.()
        } else if (raw.type === 'ERROR') {
          task.value.status = 'FAILED'
          if (typeof payload.errorMessage === 'string')
            task.value.errorMessage = payload.errorMessage
          onTaskFinished?.()
        } else if (raw.type === 'CONFIRMATION_REQUEST') {
          task.value.status = 'AWAITING_PERMISSION'
        }
      },
    })
  }

  async function connect(sessionId: string) {
    disconnect()
    chatState.clearMessages()
    chatState.setConnectionStatus('connecting')

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
      console.error('Failed to load task history:', e)
    }

    createSseConnection(sessionId)
  }

  function resumeInPlace(newTask: ClaudeTask) {
    task.value = newTask
    chatState.addUserMessage(newTask.prompt)

    if (sseController) {
      sseController.close()
      sseController = null
    }
    chatState.setConnectionStatus('connecting')
    createSseConnection(newTask.sessionId)
  }

  function disconnect() {
    if (sseController) {
      sseController.close()
      sseController = null
    }
    chatState.setConnectionStatus('disconnected')
  }

  return {
    task,
    chatState,
    connect,
    resumeInPlace,
    disconnect,
  }
}
