import { ref, watch } from 'vue'
import { createChatState, AipMessageType } from '@foggy/chat-core'
import type { ChatState, ChatMessage } from '@foggy/chat-core'
import { tutorAgentAdapter } from '@/adapters/TutorAgentAdapter'
import { useUnifiedSse } from '@/composables/useUnifiedSse'
import * as sessionApi from '@/api/session'
import { getTaskUnified } from '@/api/unifiedTask'
import type { AgentMessage, DispatchTask } from '@/api/types'

export interface TaskStreamState {
  task: ReturnType<typeof ref<DispatchTask | null>>
  chatState: ChatState
  /** Total messages in DB for pagination */
  totalMessages: ReturnType<typeof ref<number>>
  /** Whether there are more history messages to load */
  hasMoreHistory: ReturnType<typeof ref<boolean>>
  /** Whether currently loading more history */
  loadingMore: ReturnType<typeof ref<boolean>>
  connect(sessionId: string): Promise<void>
  resumeInPlace(newTask: DispatchTask): void
  /** Load older messages (pagination) */
  loadMoreHistory(): Promise<void>
  /** Sync task status from backend */
  syncTaskStatus(): Promise<void>
  disconnect(): void
}

const HISTORY_PAGE_SIZE = 50

export function useTaskStream(onTaskFinished?: () => void): TaskStreamState {
  const task = ref<DispatchTask | null>(null)
  const chatState = createChatState()
  let unsubscribeSse: (() => void) | null = null
  let stopConnWatch: (() => void) | null = null
  let unsubscribeNotification: (() => void) | null = null
  let connectVersion = 0

  // Pagination state
  const totalMessages = ref(0)
  const loadedDbCount = ref(0)
  const hasMoreHistory = ref(false)
  const loadingMore = ref(false)

  const { subscribeSession, addNotificationListener, connected } = useUnifiedSse()

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

    // Sync claudeSessionId
    if (typeof payload.claudeSessionId === 'string' && payload.claudeSessionId) {
      task.value.claudeSessionId = payload.claudeSessionId
    }

    // Sync codexThreadId
    if (typeof payload.codexThreadId === 'string' && payload.codexThreadId) {
      task.value.codexThreadId = payload.codexThreadId
    }

    if (raw.type === 'TEXT_COMPLETE') {
      // Only mark completed when result-level metadata is present
      if (typeof payload.numTurns === 'number' || typeof payload.costUsd === 'number') {
        task.value.status = 'COMPLETED'
        if (typeof payload.costUsd === 'number') task.value.costUsd = payload.costUsd
        if (typeof payload.durationMs === 'number') task.value.durationMs = payload.durationMs
        if (typeof payload.inputTokens === 'number') task.value.inputTokens = payload.inputTokens
        if (typeof payload.outputTokens === 'number') task.value.outputTokens = payload.outputTokens
        if (typeof payload.numTurns === 'number') task.value.numTurns = payload.numTurns
        if (typeof payload.model === 'string') task.value.model = payload.model
        onTaskFinished?.()
      }
    } else if (raw.type === 'SESSION_END') {
      // SESSION_END with result metadata = completed
      if (typeof payload.numTurns === 'number') {
        task.value.status = 'COMPLETED'
        if (typeof payload.costUsd === 'number') task.value.costUsd = payload.costUsd
        if (typeof payload.durationMs === 'number') task.value.durationMs = payload.durationMs
        if (typeof payload.numTurns === 'number') task.value.numTurns = payload.numTurns
        onTaskFinished?.()
      }
    } else if (raw.type === 'ERROR') {
      task.value.status = 'FAILED'
      if (typeof payload.errorMessage === 'string') {
        task.value.errorMessage = payload.errorMessage
      } else if (typeof payload.content === 'string') {
        task.value.errorMessage = payload.content
      }
      onTaskFinished?.()
    } else if (raw.type === 'CONFIRMATION_REQUEST') {
      task.value.status = 'AWAITING_PERMISSION'
    }
  }

  function handleNotification(raw: AgentMessage) {
    // Sync interactionState from task_update / assistant_notification
    if (!task.value) return
    const payload = raw.payload as Record<string, unknown> | undefined
    if (!payload) return

    // Task status updates for our session
    if (raw.type === 'task_update' && payload.sessionId === task.value.sessionId) {
      if (typeof payload.status === 'string') {
        task.value.status = payload.status as DispatchTask['status']
      }
    }
  }

  function createSseSubscription(sessionId: string) {
    unsubscribeSse = subscribeSession(sessionId, handleSseEvent)
    chatState.setConnectionStatus(connected.value ? 'connected' : 'connecting')
    stopConnWatch?.()
    stopConnWatch = watch(connected, (val) => {
      chatState.setConnectionStatus(val ? 'connected' : 'connecting')
    })
    // Listen for global notifications
    unsubscribeNotification?.()
    unsubscribeNotification = addNotificationListener(handleNotification)
  }

  async function connect(sessionId: string) {
    disconnect()
    const thisVersion = ++connectVersion
    chatState.clearMessages()
    chatState.setConnectionStatus('connecting')

    try {
      // Use paginated latest messages API
      const result = await sessionApi.getLatestMessages(sessionId, HISTORY_PAGE_SIZE, 0)
      if (thisVersion !== connectVersion) return // stale

      totalMessages.value = result.total
      loadedDbCount.value = result.messages.length
      hasMoreHistory.value = result.hasMore

      for (const msg of result.messages) {
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

    if (thisVersion !== connectVersion) return
    createSseSubscription(sessionId)
  }

  async function loadMoreHistory(): Promise<void> {
    if (loadingMore.value || !hasMoreHistory.value || !task.value?.sessionId) return
    loadingMore.value = true
    try {
      const result = await sessionApi.getLatestMessages(
        task.value.sessionId,
        HISTORY_PAGE_SIZE,
        loadedDbCount.value,
      )
      // Prepend older messages
      const olderMessages: ChatMessage[] = result.messages.map(msg => ({
        id: msg.id,
        type: AipMessageType.TEXT_COMPLETE,
        sender: msg.role === 'USER' ? 'user' : 'assistant',
        content: msg.content,
        timestamp: new Date(msg.createdAt).getTime(),
      }))
      chatState.messages.value.unshift(...olderMessages)
      loadedDbCount.value += result.messages.length
      hasMoreHistory.value = result.hasMore
    } catch (e) {
      console.error('Failed to load more history:', e)
    } finally {
      loadingMore.value = false
    }
  }

  async function syncTaskStatus(): Promise<void> {
    if (!task.value) return
    const status = task.value.status
    // Skip if already terminal
    if (status === 'COMPLETED' || status === 'FAILED' || status === 'ABORTED') return
    try {
      const fresh = await getTaskUnified(task.value.taskId)
      if (fresh) {
        task.value = fresh
      }
    } catch (e) {
      console.error('Failed to sync task status:', e)
    }
  }

  function resumeInPlace(newTask: DispatchTask) {
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
    connectVersion++
    stopConnWatch?.()
    stopConnWatch = null
    unsubscribeNotification?.()
    unsubscribeNotification = null
    if (unsubscribeSse) {
      unsubscribeSse()
      unsubscribeSse = null
    }
    chatState.setConnectionStatus('disconnected')
  }

  return {
    task,
    chatState,
    totalMessages,
    hasMoreHistory,
    loadingMore,
    connect,
    resumeInPlace,
    loadMoreHistory,
    syncTaskStatus,
    disconnect,
  }
}
