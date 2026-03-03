import { ref } from 'vue'
import { createChatState, AipMessageType } from '@foggy/chat-core'
import type { ChatState, ChatMessage } from '@foggy/chat-core'
import { tutorAgentAdapter } from '@/adapters/TutorAgentAdapter'
import { useUnifiedSse } from '@/composables/useUnifiedSse'
import * as sessionApi from '@/api/session'
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
  let unsubscribeSse: (() => void) | null = null

  const { subscribeSession, connected } = useUnifiedSse()

  function handleSseEvent(raw: AgentMessage) {
    // Pass through adapter for chat messages
    const msgs = tutorAgentAdapter.convert(raw, raw.sessionId)
    for (const msg of msgs) {
      chatState.processAipMessage(msg)
    }

    // Handle raw events for task state tracking
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
  }

  function createSseSubscription(sessionId: string) {
    unsubscribeSse = subscribeSession(sessionId, handleSseEvent)
    chatState.setConnectionStatus(connected.value ? 'connected' : 'connecting')
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

    createSseSubscription(sessionId)
  }

  function resumeInPlace(newTask: ClaudeTask) {
    task.value = newTask
    chatState.addUserMessage(newTask.prompt)

    if (unsubscribeSse) {
      unsubscribeSse()
      unsubscribeSse = null
    }
    chatState.setConnectionStatus('connecting')
    createSseSubscription(newTask.sessionId)
  }

  function disconnect() {
    if (unsubscribeSse) {
      unsubscribeSse()
      unsubscribeSse = null
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
