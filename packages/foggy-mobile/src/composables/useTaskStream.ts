import { ref, watch } from 'vue'
import { createChatState, AipMessageType } from '@foggy/chat-core'
import type { ChatState, ChatMessage } from '@foggy/chat-core'
import { agentMessageAdapter } from '@/adapters/AgentMessageAdapter'
import { useUnifiedSse } from '@/composables/useUnifiedSse'
import * as sessionApi from '@/api/session'
import { getTaskUnified } from '@/api/unifiedTask'
import type { AgentMessage, DispatchTask, Message } from '@/api/types'

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
  resumeInPlace(newTask: DispatchTask, images?: Array<{ name: string; url: string }>): void
  /** Load older messages (pagination) */
  loadMoreHistory(): Promise<void>
  /** Fetch the full persisted session for the independent record viewer. */
  getAllHistoryMessages(): Promise<ChatMessage[]>
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

  // Track message IDs to prevent duplicates (DB messages + SSE messages)
  const knownMessageIds = new Set<string>()

  /**
   * Rehydrate persisted messages through the same AIP reducer as live SSE.
   * The server stores the original event type in metadata, so treating every
   * row as TEXT_COMPLETE loses tool/error/confirmation semantics and can also
   * merge a historical stream into the wrong assistant bubble.
   */
  function restoreDbMessage(
    msg: Message,
    nextMsg?: Message,
    targetState: ChatState = chatState,
    seenMessageIds: Set<string> = knownMessageIds,
  ): boolean {
    const storedEventId = typeof msg.metadata?.messageId === 'string' && msg.metadata.messageId
      ? msg.metadata.messageId
      : undefined
    if ((msg.id && seenMessageIds.has(msg.id)) || (storedEventId && seenMessageIds.has(storedEventId))) {
      return false
    }
    if (msg.id) seenMessageIds.add(msg.id)
    if (storedEventId) seenMessageIds.add(storedEventId)

    const timestamp = new Date(msg.createdAt).getTime()
    const metadata = msg.metadata
    const messageType = metadata?.type

    if (msg.role === 'USER') {
      targetState.messages.value.push({
        id: msg.id,
        type: AipMessageType.TEXT_COMPLETE,
        sender: 'user',
        content: msg.content || '',
        timestamp,
      })
      return true
    }

    if (messageType === AipMessageType.STATE_SYNC && metadata?.subtype === 'waiting') {
      // A persisted waiting hint is only useful when it is still the latest
      // state in its page. Avoid resurrecting stale temporary copy.
      const nextType = nextMsg?.metadata?.type
      if (nextMsg && (
        nextType === AipMessageType.ERROR
        || nextType === AipMessageType.TEXT_COMPLETE
        || nextType === AipMessageType.TASK_COMPLETED
        || nextMsg.role === 'USER'
      )) {
        return false
      }
    }

    if (messageType && Object.values(AipMessageType).includes(messageType as AipMessageType)) {
      // Some historic rows store the visible text only in Message.content.
      // Keep the original metadata but supply that fallback to the reducer.
      const payload = {
        ...metadata,
        content: typeof metadata?.content === 'string' ? metadata.content : (msg.content || ''),
      }
      targetState.processAipMessage({
        messageId: msg.id,
        sessionId: msg.sessionId,
        timestamp,
        type: messageType as AipMessageType,
        payload,
      })
      return true
    }

    targetState.messages.value.push({
      id: msg.id,
      type: AipMessageType.TEXT_COMPLETE,
      sender: msg.role === 'TOOL' ? 'tool' : msg.role === 'SYSTEM' ? 'system' : 'assistant',
      content: msg.content || '',
      timestamp,
    })
    return true
  }

  /**
   * Restore one API page in an isolated reducer, then insert the finished
   * page as a unit. This preserves the page order when older rows are
   * prepended and lets the durable message-id set remove pagination overlap.
   */
  function restoreDbPage(messages: Message[], prepend = false) {
    const pageState = createChatState()
    for (let index = 0; index < messages.length; index++) {
      restoreDbMessage(messages[index], messages[index + 1], pageState)
    }
    if (pageState.messages.value.length === 0) return
    if (prepend) {
      chatState.messages.value.unshift(...pageState.messages.value)
    } else {
      chatState.messages.value.push(...pageState.messages.value)
    }
  }

  function handleSseEvent(raw: AgentMessage) {
    const payload = raw.payload as Record<string, unknown> | undefined
    // A session may contain previous task turns. Ignore their replay before
    // it reaches chat-state; the persisted history already covers them.
    if (task.value && typeof payload?.taskId === 'string' && payload.taskId !== task.value.taskId) return

    // Pass through adapter for chat messages
    const msgs = agentMessageAdapter.convert(raw, raw.sessionId)
    for (const msg of msgs) {
      // Deduplicate across DB-loaded history and live SSE events using the shared messageId.
      const messageId = msg.messageId
      if (messageId && knownMessageIds.has(messageId)) continue
      if (messageId) knownMessageIds.add(messageId)
      chatState.processAipMessage(msg)
    }

    // Handle raw events for task state tracking
    if (!task.value) return
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

    if (typeof payload.providerType === 'string' && payload.providerType) {
      task.value.providerType = payload.providerType
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

  function handleNotification(eventType: string, data: unknown) {
    if (!task.value) return
    const payload = data as Record<string, unknown> | undefined
    if (!payload) return

    // Task status updates for our session
    if (eventType === 'task_update' && payload.sessionId === task.value.sessionId) {
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
    knownMessageIds.clear()
    chatState.setConnectionStatus('connecting')

    try {
      // Use paginated latest messages API
      const result = await sessionApi.getLatestMessages(sessionId, HISTORY_PAGE_SIZE, 0)
      if (thisVersion !== connectVersion) return // stale

      totalMessages.value = result.total
      loadedDbCount.value = result.messages.length
      hasMoreHistory.value = result.hasMore

      restoreDbPage(result.messages)
    } catch (e) {
      console.error('Failed to load task history:', e)
    }

    if (thisVersion !== connectVersion) return

    // Only subscribe to SSE if the task is still active (RUNNING/PENDING/AWAITING_PERMISSION).
    // For terminal states (COMPLETED/FAILED/ABORTED), SSE would replay cached events
    // that are already loaded from DB, causing message duplication.
    const status = task.value?.status
    const isTerminal = status === 'COMPLETED' || status === 'FAILED' || status === 'ABORTED'
    if (!isTerminal) {
      createSseSubscription(sessionId)
    } else {
      chatState.setConnectionStatus('connected')
    }
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
      // The API can overlap the tail of the previous page during writes.
      // restoreDbPage shares knownMessageIds with SSE and skips that overlap.
      restoreDbPage(result.messages, true)
      loadedDbCount.value += result.messages.length
      hasMoreHistory.value = result.hasMore
    } catch (e) {
      console.error('Failed to load more history:', e)
    } finally {
      loadingMore.value = false
    }
  }

  async function getAllHistoryMessages() {
    const sessionId = task.value?.sessionId
    if (!sessionId) return []

    const allMessages = await sessionApi.getMessages(sessionId)
    const viewerState = createChatState()
    const viewerMessageIds = new Set<string>()
    for (let index = 0; index < allMessages.length; index++) {
      const message = allMessages[index]
      if (!message) continue
      restoreDbMessage(message, allMessages[index + 1], viewerState, viewerMessageIds)
    }
    return [...viewerState.sortedMessages.value]
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

  function resumeInPlace(newTask: DispatchTask, images?: Array<{ name: string; url: string }>) {
    task.value = newTask
    chatState.addUserMessage(newTask.prompt, undefined, images && images.length > 0 ? images : undefined)

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
    getAllHistoryMessages,
    syncTaskStatus,
    disconnect,
  }
}
