import { computed, ref, shallowRef, watch, type ComputedRef, type Ref } from 'vue'
import { isAxiosError } from 'axios'
import { createChatState, AipMessageType } from '@foggy/chat'
import type { ChatState, ChatMessage } from '@foggy/chat'
import { agentMessageAdapter } from '@/adapters/AgentMessageAdapter'
import * as sessionApi from '@/api/session'
import * as workerApi from '@/api/claudeWorker'
import { getNativeSubtasks } from '@/api/nativeSubtasks'
import { getTaskUnified } from '@/api/unifiedTask'
import { useUnifiedSse } from '@/composables/useUnifiedSse'
import {
  createNativeSubtaskState,
  parseNativeSubtaskUpdate,
  reduceNativeSubtasks,
  selectNativeSubtasks,
} from '@/composables/nativeSubtaskState'
import { inferTaskWorkerBackend, isClaudeCodeTask } from '@/utils/workerBackend'
import type { AgentMessage, ClaudeTask, Message } from '@/types'
import { NATIVE_SUBTASK_UPDATE_TYPE } from '@/types/nativeSubtasks'
import type { NativeSubtask } from '@/types/nativeSubtasks'

/** Number of messages to load per page */
const PAGE_SIZE = 800
const NATIVE_SUBTASK_SNAPSHOT_RETRY_BASE_DELAY = 1000
const NATIVE_SUBTASK_SNAPSHOT_RETRY_MAX_DELAY = 30000
const NATIVE_SUBTASK_UNSUPPORTED_STATUSES = new Set([404, 405, 501])

export interface TaskPaneState {
  paneId: string
  task: Ref<ClaudeTask | null>
  chatState: ChatState
  /** Pre-fill the input field (e.g. after rewind with original prompt) */
  pendingInput: Ref<string>
  /** Whether older messages are currently being loaded */
  loadingMore: Ref<boolean>
  /** Whether there are older messages available to load */
  hasMoreHistory: Ref<boolean>
  /** Total number of messages in the DB for the current session */
  totalMessages: Ref<number>
  /** Native Codex subtask state, kept outside @foggy/chat. */
  nativeSubtasks: ComputedRef<NativeSubtask[]>
  nativeSubtasksLoading: Ref<boolean>
  nativeSubtaskLastEventSeq: ComputedRef<number>
  connect(sessionId: string, pendingImages?: Array<{ name: string; url: string }>): Promise<void>
  /** Load older messages (prepend to chat). Called when user scrolls to top. */
  loadMoreHistory(): Promise<void>
  /** Load all (or up to `limit`) messages, replacing current messages. Scrolls to top. */
  loadAllHistory(limit?: number): Promise<void>
  /** Fetch all messages for export/copy without changing the visible pane history. */
  getAllHistoryMessages(): Promise<ChatMessage[]>
  /** Resume in-place: keep messages, update task, reconnect SSE */
  resumeInPlace(newTask: ClaudeTask, images?: Array<{ name: string; url: string }>): void
  /** Resume in-place without adding user message (caller already added it) */
  resumeInPlaceNoMessage(newTask: ClaudeTask): void
  /** Reconnect SSE only (no message clear/reload). Used by workspace suspend/resume. */
  reconnectSse(): void
  /** Pull latest task status from backend (idempotent, skips terminal states) */
  syncTaskStatus(): Promise<void>
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
  const pendingInput = ref('')
  let unsubscribeSse: (() => void) | null = null
  let connectVersion = 0

  // Pagination state
  let totalDbMessages = 0
  let dbLoadedOffset = 0    // how many messages from the tail we've already loaded
  let allDbLoaded = false
  const loadingMore = ref(false)
  const hasMoreHistory = ref(false)
  const totalMessages = ref(0)
  let currentSessionId = ''

  const nativeSubtaskState = shallowRef(createNativeSubtaskState())
  const nativeSubtasks = computed(() => selectNativeSubtasks(nativeSubtaskState.value))
  const nativeSubtaskLastEventSeq = computed(() => nativeSubtaskState.value.lastEventSeq)
  const nativeSubtasksLoading = ref(false)
  let nativeSnapshotRetryTimer: ReturnType<typeof setTimeout> | null = null
  let nativeSnapshotRetryAttempt = 0
  let nativeSnapshotRetryKey = ''
  let nativeSnapshotInFlightKey = ''
  let nativeSnapshotBlockedKey = ''
  let disposed = false

  const { subscribeSession, connected } = useUnifiedSse()

  const stopTaskWatcher = watch(() => task.value?.taskId, (taskId) => {
    if (!taskId) {
      resetNativeSnapshotRecovery()
      nativeSubtaskState.value = createNativeSubtaskState()
      nativeSubtasksLoading.value = false
      return
    }
    if (nativeSubtaskState.value.taskId === taskId) return
    resetNativeSnapshotRecovery()
    nativeSubtaskState.value = createNativeSubtaskState(taskId)
    nativeSubtasksLoading.value = false
    if (currentSessionId && unsubscribeSse) {
      void loadNativeSubtaskSnapshot(taskId, connectVersion)
    }
  })

  // User-level SSE fallback: listen for task_status_change, with task_completion as a terminal-state fallback.
  let taskUpdateHandler: ((event: Event) => void) | null = null

  function attachTaskUpdateListener() {
    detachTaskUpdateListener()
    taskUpdateHandler = (event: Event) => {
      const detail = (event as CustomEvent).detail
      if (!task.value || detail?.taskId !== task.value.taskId) return
      if (!['task_status_change', 'task_completion'].includes(detail?.type)) return
      const newStatus = detail?.status as string
      if (newStatus && newStatus !== task.value.status) {
        task.value.status = newStatus as any
        if (detail.errorMessage) {
          task.value.errorMessage = detail.errorMessage
        } else if (newStatus === 'FAILED' && detail.summary) {
          task.value.errorMessage = String(detail.summary)
        }
        if (['COMPLETED', 'FAILED', 'ABORTED'].includes(newStatus)) {
          options?.onTaskFinished?.(paneId)
        }
      }
    }
    window.addEventListener('task-update', taskUpdateHandler)
  }

  function detachTaskUpdateListener() {
    if (taskUpdateHandler) {
      window.removeEventListener('task-update', taskUpdateHandler)
      taskUpdateHandler = null
    }
  }

  async function syncTaskStatus() {
    if (!task.value) return
    if (['COMPLETED', 'FAILED', 'ABORTED'].includes(task.value.status)) return
    try {
      const fresh = (await getTaskUnified(task.value.taskId)) as unknown as ClaudeTask
      if (!fresh) return
      const prevStatus = task.value.status
      Object.assign(task.value, fresh)
      if (prevStatus !== fresh.status && ['COMPLETED', 'FAILED', 'ABORTED'].includes(fresh.status)) {
        options?.onTaskFinished?.(paneId)
      }
    } catch {
      /* task deleted or inaccessible — ignore */
    }
  }

  /**
   * Convert a DB Message to ChatMessage(s) and push into chatState.
   * Returns 1 if a USER/ASSISTANT message was counted, 0 otherwise.
   *
   * @param msg       The DB message
   * @param nextMsg   The next message in sequence (for waiting-hint suppression)
   */
  function convertAndPushDbMessage(msg: Message, nextMsg?: Message, targetState: ChatState = chatState): number {
    let counted = 0
    if (msg.role === 'USER' || msg.role === 'ASSISTANT') {
      counted = 1
    }
    const meta = msg.metadata
    const msgType = meta?.type as string | undefined
    const ts = new Date(msg.createdAt).getTime()

    if (msg.role === 'USER') {
      targetState.messages.value.push({
        id: msg.id,
        type: AipMessageType.TEXT_COMPLETE,
        sender: 'user',
        content: msg.content || '',
        timestamp: ts,
      })
    } else if (msgType === 'STATE_SYNC' && meta?.subtype === 'waiting') {
      // Skip transient "waiting" hints when the next message supersedes them
      const nextType = nextMsg?.metadata?.type as string | undefined
      if (nextMsg && (nextType === 'ERROR' || nextType === 'TEXT_COMPLETE'
        || nextType === 'TASK_COMPLETED' || nextMsg.role === 'USER')) {
        return counted
      }
      targetState.messages.value.push({
        id: msg.id,
        type: AipMessageType.STATE_SYNC,
        sender: 'system',
        content: msg.content || 'Waiting for response...',
        raw: { subtype: 'waiting' },
        timestamp: ts,
      })
    } else if (msgType && msgType in AipMessageType) {
      targetState.processAipMessage({
        messageId: msg.id,
        sessionId: msg.sessionId,
        timestamp: ts,
        type: msgType as AipMessageType,
        payload: meta,
      })
    } else {
      targetState.messages.value.push({
        id: msg.id,
        type: AipMessageType.TEXT_COMPLETE,
        sender: 'assistant',
        content: msg.content || '',
        timestamp: ts,
      })
    }
    return counted
  }

  /** SSE event handler — shared by connect and resumeInPlace */
  function handleSseEvent(raw: AgentMessage) {
    if (raw.type === NATIVE_SUBTASK_UPDATE_TYPE) {
      const update = parseNativeSubtaskUpdate(raw.payload)
      if (update && update.taskId === task.value?.taskId) {
        if (nativeSubtaskState.value.taskId !== update.taskId) {
          nativeSubtaskState.value = createNativeSubtaskState(update.taskId)
        }
        nativeSubtaskState.value = reduceNativeSubtasks(nativeSubtaskState.value, {
          type: 'UPDATE',
          update,
        })
      }
      return
    }

    // 1. Pass through adapter for chat messages
    const msgs = agentMessageAdapter.convert(raw, raw.sessionId)
    for (const msg of msgs) {
      chatState.processAipMessage(msg)
    }

    // 2. Handle raw events for task state tracking
    if (!task.value) return
    const payload = raw.payload as Record<string, unknown> | undefined
    if (!payload?.taskId) return

    // taskId guard: ignore events from previous tasks in the same session
    if (payload.taskId !== task.value.taskId) return

    // Capture provider session refs as early as possible.
    if (typeof payload.claudeSessionId === 'string' && payload.claudeSessionId) {
      task.value.claudeSessionId = payload.claudeSessionId
    }
    if (typeof payload.codexThreadId === 'string' && payload.codexThreadId) {
      task.value.codexThreadId = payload.codexThreadId
    }

    if (raw.type === 'CONFIRMATION_REQUEST') {
      task.value.status = 'AWAITING_PERMISSION' as ClaudeTask['status']
    } else if (raw.type === 'TEXT_COMPLETE' || raw.type === 'SESSION_END') {
      // Only treat as task completion when result-level metadata is present.
      // The "result" event from Python Worker always includes numTurns/costUsd/durationMs.
      // Intermediate "assistant_text" events only have content and taskId.
      if (payload.numTurns != null || payload.costUsd != null || payload.durationMs != null) {
        task.value.status = 'COMPLETED'
        if (typeof payload.costUsd === 'number') task.value.costUsd = payload.costUsd
        if (typeof payload.durationMs === 'number') task.value.durationMs = payload.durationMs
        if (typeof payload.inputTokens === 'number') task.value.inputTokens = payload.inputTokens
        if (typeof payload.outputTokens === 'number') task.value.outputTokens = payload.outputTokens
        if (typeof payload.numTurns === 'number') task.value.numTurns = payload.numTurns
        if (typeof payload.model === 'string') task.value.model = payload.model
        options?.onTaskFinished?.(paneId)
      }
    } else if (raw.type === 'ERROR') {
      task.value.status = 'FAILED'
      if (typeof payload.errorMessage === 'string') {
        task.value.errorMessage = payload.errorMessage
      } else if (typeof payload.content === 'string') {
        task.value.errorMessage = payload.content
      }
      options?.onTaskFinished?.(paneId)
    }
  }

  /** Subscribe to session events via unified SSE */
  function createSseSubscription(sessionId: string) {
    unsubscribeSse = subscribeSession(sessionId, handleSseEvent, {
      onSubscribed: () => {
        const currentTask = task.value
        if (currentTask?.sessionId === sessionId) {
          void loadNativeSubtaskSnapshot(currentTask.taskId, connectVersion)
        }
      },
    })
    chatState.setConnectionStatus(connected.value ? 'connected' : 'connecting')
  }

  function nativeSnapshotKey(taskId: string, version: number): string {
    return `${version}:${taskId}`
  }

  function clearNativeSnapshotRetry(): void {
    if (nativeSnapshotRetryTimer != null) clearTimeout(nativeSnapshotRetryTimer)
    nativeSnapshotRetryTimer = null
  }

  function resetNativeSnapshotRecovery(): void {
    clearNativeSnapshotRetry()
    nativeSnapshotRetryAttempt = 0
    nativeSnapshotRetryKey = ''
    nativeSnapshotInFlightKey = ''
    nativeSnapshotBlockedKey = ''
  }

  function isCurrentNativeSnapshotRequest(taskId: string, version: number): boolean {
    return !disposed
      && connectVersion === version
      && task.value?.taskId === taskId
      && inferTaskWorkerBackend(task.value) === 'OPENAI_CODEX'
  }

  function nativeSnapshotHttpStatus(error: unknown): number | null {
    if (!error || typeof error !== 'object' || !('response' in error)) return null
    const response = (error as { response?: unknown }).response
    if (!response || typeof response !== 'object' || !('status' in response)) return null
    const status = (response as { status?: unknown }).status
    return typeof status === 'number' ? status : null
  }

  function isRetryableNativeSnapshotError(error: unknown): boolean {
    const status = nativeSnapshotHttpStatus(error)
    if (status != null) {
      return status === 408 || status === 429 || (status >= 500 && status <= 599)
    }
    return isAxiosError(error) && error.response == null && error.request != null
  }

  function scheduleNativeSnapshotRetry(taskId: string, version: number): void {
    if (!isCurrentNativeSnapshotRequest(taskId, version)) return
    const key = nativeSnapshotKey(taskId, version)
    if (nativeSnapshotRetryKey !== key) {
      clearNativeSnapshotRetry()
      nativeSnapshotRetryKey = key
      nativeSnapshotRetryAttempt = 0
    }
    if (nativeSnapshotRetryTimer != null) return

    const delay = Math.min(
      NATIVE_SUBTASK_SNAPSHOT_RETRY_BASE_DELAY * Math.pow(2, nativeSnapshotRetryAttempt),
      NATIVE_SUBTASK_SNAPSHOT_RETRY_MAX_DELAY,
    )
    nativeSnapshotRetryAttempt++
    nativeSnapshotRetryTimer = setTimeout(() => {
      nativeSnapshotRetryTimer = null
      if (!isCurrentNativeSnapshotRequest(taskId, version)) return
      void loadNativeSubtaskSnapshot(taskId, version)
    }, delay)
  }

  async function loadNativeSubtaskSnapshot(taskId: string, version: number) {
    if (!isCurrentNativeSnapshotRequest(taskId, version)) return
    const key = nativeSnapshotKey(taskId, version)
    if (nativeSnapshotBlockedKey === key) return
    if (nativeSnapshotInFlightKey === key) return
    if (nativeSnapshotRetryKey !== key) {
      resetNativeSnapshotRecovery()
      nativeSnapshotRetryKey = key
    } else {
      clearNativeSnapshotRetry()
    }
    nativeSnapshotInFlightKey = key
    if (nativeSubtaskState.value.taskId !== taskId) {
      nativeSubtaskState.value = createNativeSubtaskState(taskId)
    }
    nativeSubtasksLoading.value = true
    try {
      const snapshot = await getNativeSubtasks(taskId)
      if (connectVersion !== version || task.value?.taskId !== taskId || !snapshot) return
      nativeSubtaskState.value = reduceNativeSubtasks(nativeSubtaskState.value, {
        type: 'SNAPSHOT',
        snapshot,
      })
      nativeSnapshotRetryAttempt = 0
    } catch (error) {
      if (!isCurrentNativeSnapshotRequest(taskId, version)) return
      const status = nativeSnapshotHttpStatus(error)
      if (status != null && NATIVE_SUBTASK_UNSUPPORTED_STATUSES.has(status)) {
        nativeSnapshotBlockedKey = key
        clearNativeSnapshotRetry()
        nativeSnapshotRetryAttempt = 0
      } else if (isRetryableNativeSnapshotError(error)) {
        scheduleNativeSnapshotRetry(taskId, version)
      } else {
        nativeSnapshotBlockedKey = key
        clearNativeSnapshotRetry()
        nativeSnapshotRetryAttempt = 0
      }
    } finally {
      if (nativeSnapshotInFlightKey === key) nativeSnapshotInFlightKey = ''
      if (connectVersion === version && task.value?.taskId === taskId) {
        nativeSubtasksLoading.value = false
      }
    }
  }

  async function connect(sessionId: string, pendingImages?: Array<{ name: string; url: string }>) {
    disconnect()
    const myVersion = ++connectVersion
    disposed = false
    resetNativeSnapshotRecovery()
    chatState.clearMessages()
    chatState.setConnectionStatus('connecting')
    nativeSubtaskState.value = createNativeSubtaskState(task.value?.taskId ?? null)
    nativeSubtasksLoading.value = false

    // Reset pagination state
    currentSessionId = sessionId
    totalDbMessages = 0
    dbLoadedOffset = 0
    allDbLoaded = false
    loadingMore.value = false
    hasMoreHistory.value = false
    totalMessages.value = 0

    // Load latest PAGE_SIZE messages from DB (paginated)
    try {
      const result = await sessionApi.getLatestMessages(sessionId, PAGE_SIZE, 0)
      if (connectVersion !== myVersion) return

      totalDbMessages = result.total
      totalMessages.value = result.total
      dbLoadedOffset = Math.min(PAGE_SIZE, result.total)
      allDbLoaded = !result.hasMore
      hasMoreHistory.value = result.hasMore

      const messages = result.messages
      for (let i = 0; i < messages.length; i++) {
        const msg = messages[i]
        if (!msg) continue
        convertAndPushDbMessage(msg, messages[i + 1])
      }
      // Attach pending images to the first user message (for newly created tasks)
      if (pendingImages && pendingImages.length > 0) {
        const firstUserMsg = chatState.messages.value.find(m => m.sender === 'user')
        if (firstUserMsg) {
          firstUserMsg.images = pendingImages
        }
      }
    } catch (e) {
      if (connectVersion !== myVersion) return
      console.error(`[TaskPane ${paneId}] Failed to load history:`, e)
      chatState.messages.value.push({
        id: `load-error-${Date.now()}`,
        type: AipMessageType.ERROR,
        sender: 'system',
        content: '',
        error: '消息加载失败，请尝试关闭后重新打开会话',
        timestamp: Date.now(),
      } as ChatMessage)
    }

    if (connectVersion !== myVersion) return
    createSseSubscription(sessionId)
    attachTaskUpdateListener()

    // Non-blocking: detect and load external Claude Code session delta messages.
    // Use totalDbMessages (total count) for comparison, not the loaded count.
    // Skip for ABORTED tasks — the delta is typically caused by the CLI
    // continuing to run after abort (undelivered SSE events), not by real
    // external conversations.
    if (connectVersion !== myVersion) return
    if (task.value?.claudeSessionId && task.value?.workerId && task.value?.status !== 'ABORTED'
      && isClaudeCodeTask(task.value)) {
      detectAndLoadDelta(task.value.workerId, task.value.claudeSessionId, totalDbMessages, myVersion)
    }
  }

  /** Detect external Claude Code session messages that are not in the DB and append them to chat */
  async function detectAndLoadDelta(workerId: string, claudeSessionId: string, dbMsgCount: number, myVersion: number) {
    try {
      // 1. Get Worker session message count
      const countResult = await workerApi.getWorkerSessionMessageCount(workerId, claudeSessionId)
      if (connectVersion !== myVersion) return
      const workerSessionTotal = countResult.total

      // 2. If Worker session has more messages than DB and DB has at least some messages
      if (workerSessionTotal > dbMsgCount && dbMsgCount > 0) {
        // 3. Fetch ONLY the delta using offset-based pagination (optimized)
        const deltaCount = workerSessionTotal - dbMsgCount
        const delta = await workerApi.getWorkerSessionMessagesPaged(
          workerId, claudeSessionId, dbMsgCount, deltaCount,
        )
        if (connectVersion !== myVersion) return
        if (delta.length === 0) return

        // 4. Insert separator
        const separatorMsg: ChatMessage = {
          id: `delta-separator-${Date.now()}`,
          type: AipMessageType.TEXT_COMPLETE,
          sender: 'assistant',
          content: `--- 检测到 ${delta.length} 条外部会话消息 ---`,
          timestamp: Date.now(),
        }
        chatState.messages.value.push(separatorMsg)

        // 5. Append delta messages
        for (const msg of delta) {
          const chatMsg: ChatMessage = {
            id: `delta-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
            type: AipMessageType.TEXT_COMPLETE,
            sender: msg.role === 'user' ? 'user' : 'assistant',
            content: msg.content,
            timestamp: msg.timestamp ? new Date(msg.timestamp).getTime() : Date.now(),
          }
          chatState.messages.value.push(chatMsg)
        }

        console.log(`[TaskPane ${paneId}] Loaded ${delta.length} external session messages`)
      }
    } catch (e) {
      if (connectVersion !== myVersion) return
      console.debug(`[TaskPane ${paneId}] Delta detection failed (non-critical):`, e)
    }
  }

  /** Load older messages and prepend to chat. Called when user scrolls to top. */
  async function loadMoreHistory(): Promise<void> {
    if (allDbLoaded || loadingMore.value || !currentSessionId) return

    loadingMore.value = true
    try {
      const result = await sessionApi.getLatestMessages(currentSessionId, PAGE_SIZE, dbLoadedOffset)

      if (!result.messages.length) {
        allDbLoaded = true
        hasMoreHistory.value = false
        return
      }

      // Convert DB messages to ChatMessages
      // We need to build the array first, then unshift, to preserve the conversion logic
      // that peeks at the next message (for waiting-hint suppression).
      const prevMessagesSnapshot = chatState.messages.value

      // Temporarily use a separate array to collect converted messages
      const tempMessages = chatState.messages.value
      const insertionPoint = tempMessages.length
      // Append at the end temporarily (the helper pushes to chatState.messages)
      for (let i = 0; i < result.messages.length; i++) {
        const msg = result.messages[i]
        if (!msg) continue
        // For boundary: the "next" of the last older message is the first already-loaded message
        const nextMsg = i < result.messages.length - 1
          ? result.messages[i + 1]
          : undefined // no peek across boundary for waiting-hint (conservative: render it)
        convertAndPushDbMessage(msg, nextMsg)
      }

      // Extract newly pushed messages and move them to the front
      const newlyAdded = tempMessages.splice(insertionPoint)
      if (newlyAdded.length > 0) {
        chatState.messages.value = [...newlyAdded, ...prevMessagesSnapshot]
      }

      dbLoadedOffset += result.messages.length
      allDbLoaded = !result.hasMore
      hasMoreHistory.value = result.hasMore
    } catch (e) {
      console.error(`[TaskPane ${paneId}] Failed to load more history:`, e)
    } finally {
      loadingMore.value = false
    }
  }

  /**
   * Load all (or up to `limit`) messages, replacing current chat messages.
   * When no limit is given, loads all messages from DB.
   * When a limit is given (e.g. 500, 1000), loads the latest N messages.
   */
  async function loadAllHistory(limit?: number): Promise<void> {
    if (loadingMore.value || !currentSessionId) return

    loadingMore.value = true
    try {
      chatState.clearMessages()

      if (limit == null) {
        // Load ALL messages (no pagination)
        const allMessages = await sessionApi.getMessages(currentSessionId)

        for (let i = 0; i < allMessages.length; i++) {
          const msg = allMessages[i]
          if (!msg) continue
          convertAndPushDbMessage(msg, allMessages[i + 1])
        }

        totalDbMessages = allMessages.length
        totalMessages.value = allMessages.length
        dbLoadedOffset = allMessages.length
        allDbLoaded = true
        hasMoreHistory.value = false
      } else {
        // Load latest `limit` messages
        const result = await sessionApi.getLatestMessages(currentSessionId, limit, 0)

        for (let i = 0; i < result.messages.length; i++) {
          const msg = result.messages[i]
          if (!msg) continue
          convertAndPushDbMessage(msg, result.messages[i + 1])
        }

        totalDbMessages = result.total
        totalMessages.value = result.total
        dbLoadedOffset = Math.min(limit, result.total)
        allDbLoaded = !result.hasMore
        hasMoreHistory.value = result.hasMore
      }
    } catch (e) {
      console.error(`[TaskPane ${paneId}] Failed to load all history:`, e)
    } finally {
      loadingMore.value = false
    }
  }

  async function getAllHistoryMessages(): Promise<ChatMessage[]> {
    const sessionId = currentSessionId || task.value?.sessionId
    if (!sessionId) return []

    const allMessages = await sessionApi.getMessages(sessionId)
    const exportState = createChatState()
    for (let i = 0; i < allMessages.length; i++) {
      const msg = allMessages[i]
      if (!msg) continue
      convertAndPushDbMessage(msg, allMessages[i + 1], exportState)
    }
    return [...exportState.sortedMessages.value]
  }

  /** Resume in the same pane without clearing messages */
  function resumeInPlace(newTask: ClaudeTask, images?: Array<{ name: string; url: string }>) {
    ++connectVersion
    resetNativeSnapshotRecovery()
    task.value = newTask
    nativeSubtaskState.value = createNativeSubtaskState(newTask.taskId)
    nativeSubtasksLoading.value = false
    chatState.addUserMessage(newTask.prompt, undefined, images)

    // Unsubscribe old SSE (without clearing messages)
    if (unsubscribeSse) {
      unsubscribeSse()
      unsubscribeSse = null
    }
    chatState.setConnectionStatus('connecting')

    // Subscribe to the same sessionId (no history reload)
    createSseSubscription(newTask.sessionId)
    attachTaskUpdateListener()
  }

  /** Resume in the same pane — user message already added by caller */
  function resumeInPlaceNoMessage(newTask: ClaudeTask) {
    ++connectVersion
    resetNativeSnapshotRecovery()
    task.value = newTask
    nativeSubtaskState.value = createNativeSubtaskState(newTask.taskId)
    nativeSubtasksLoading.value = false

    // Unsubscribe old SSE (without clearing messages)
    if (unsubscribeSse) {
      unsubscribeSse()
      unsubscribeSse = null
    }
    chatState.setConnectionStatus('connecting')

    // Subscribe to the same sessionId (no history reload)
    createSseSubscription(newTask.sessionId)
    attachTaskUpdateListener()
  }

  /** Reconnect SSE only — no message clear/reload. Used by workspace suspend/resume. */
  function reconnectSse() {
    if (unsubscribeSse) return // already subscribed
    const t = task.value
    if (!t) return
    if (['COMPLETED', 'FAILED', 'ABORTED'].includes(t.status)) return
    chatState.setConnectionStatus('connecting')
    createSseSubscription(t.sessionId)
    attachTaskUpdateListener()
  }

  function disconnect() {
    connectVersion++
    resetNativeSnapshotRecovery()
    nativeSubtasksLoading.value = false
    if (unsubscribeSse) {
      unsubscribeSse()
      unsubscribeSse = null
    }
    detachTaskUpdateListener()
    chatState.setConnectionStatus('disconnected')
  }

  function dispose() {
    disposed = true
    stopTaskWatcher()
    disconnect()
    chatState.clearMessages()
    nativeSubtaskState.value = createNativeSubtaskState()
    nativeSubtasksLoading.value = false
  }

  return {
    paneId,
    task,
    chatState,
    pendingInput,
    loadingMore,
    hasMoreHistory,
    totalMessages,
    nativeSubtasks,
    nativeSubtasksLoading,
    nativeSubtaskLastEventSeq,
    connect,
    loadMoreHistory,
    loadAllHistory,
    getAllHistoryMessages,
    resumeInPlace,
    resumeInPlaceNoMessage,
    reconnectSse,
    syncTaskStatus,
    disconnect,
    dispose,
  }
}
