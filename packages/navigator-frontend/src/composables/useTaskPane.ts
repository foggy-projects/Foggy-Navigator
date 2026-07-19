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

/** Keep the first paint bounded; fall back when a proxy truncates a response. */
const INITIAL_HISTORY_PAGE_SIZES = [100, 50, 20] as const
const HISTORY_PAGE_SIZE = 100
const NATIVE_SUBTASK_SNAPSHOT_RETRY_BASE_DELAY = 1000
const NATIVE_SUBTASK_SNAPSHOT_RETRY_MAX_DELAY = 30000
const NATIVE_SUBTASK_UNSUPPORTED_STATUSES = new Set([404, 405, 501])
const CODEX_APP_SERVER_PROVIDER = 'codex-app-server-worker'

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
  /** Non-empty when persisted history needs an explicit retry. */
  historyLoadError: Ref<string>
  historyRetrying: Ref<boolean>
  /** Native Codex subtask state, kept outside @foggy/chat. */
  nativeSubtasks: ComputedRef<NativeSubtask[]>
  nativeSubtasksLoading: Ref<boolean>
  nativeSubtaskLastEventSeq: ComputedRef<number>
  connect(sessionId: string, pendingImages?: Array<{ name: string; url: string }>): Promise<void>
  /** Load older messages (prepend to chat). Called when user scrolls to top. */
  loadMoreHistory(): Promise<void>
  /** Retry the latest persisted history page without clearing live messages. */
  retryHistory(): Promise<void>
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
  /** Pull latest task status from backend. Force is used after stream recovery. */
  syncTaskStatus(options?: { force?: boolean }): Promise<void>
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
  const historyLoadError = ref('')
  const historyRetrying = ref(false)
  let currentSessionId = ''
  /**
   * Session history and the live SSE stream overlap during reconnects. Keep
   * the durable AgentMessage identity so replay cannot render a second copy.
   */
  const knownMessageIds = new Set<string>()

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
  const stopSseConnectionWatcher = watch(() => connected.value, (isConnected) => {
    chatState.setConnectionStatus(isConnected ? 'connected' : 'connecting')
  }, { immediate: true })

  const stopTaskWatcher = watch(
    () => [task.value?.taskId, task.value?.providerType] as const,
    ([taskId, providerType]) => {
      if (!taskId || providerType !== CODEX_APP_SERVER_PROVIDER) {
        resetNativeSnapshotRecovery()
        nativeSubtaskState.value = createNativeSubtaskState(taskId ?? null)
        nativeSubtasksLoading.value = false
        return
      }
      if (nativeSubtaskState.value.taskId !== taskId) {
        resetNativeSnapshotRecovery()
        nativeSubtaskState.value = createNativeSubtaskState(taskId)
        nativeSubtasksLoading.value = false
      }
      if (currentSessionId && unsubscribeSse) {
        void loadNativeSubtaskSnapshot(taskId, connectVersion)
      }
    },
  )

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
        if (detail.error && typeof detail.error === 'object') {
          task.value.error = detail.error
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

  async function syncTaskStatus(syncOptions: { force?: boolean } = {}) {
    const currentTask = task.value
    if (!currentTask) return
    if (!syncOptions.force && ['COMPLETED', 'FAILED', 'ABORTED'].includes(currentTask.status)) return
    try {
      const fresh = (await getTaskUnified(currentTask.taskId)) as unknown as ClaudeTask
      if (!fresh) return
      if (task.value?.taskId !== currentTask.taskId) return
      const prevStatus = currentTask.status
      Object.assign(currentTask, fresh)
      appendMissingTaskResult()
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
  function convertAndPushDbMessage(
    msg: Message,
    nextMsg?: Message,
    targetState: ChatState = chatState,
    seenMessageIds?: Set<string>,
  ): number {
    const storedEventId = typeof msg.metadata?.messageId === 'string' && msg.metadata.messageId
      ? msg.metadata.messageId
      : undefined
    if (seenMessageIds?.has(msg.id) || (storedEventId && seenMessageIds?.has(storedEventId))) return 0
    seenMessageIds?.add(msg.id)
    if (storedEventId) seenMessageIds?.add(storedEventId)
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

  function convertDbMessagesSafely(
    messages: Message[],
    targetState: ChatState = chatState,
    seenMessageIds?: Set<string>,
  ): number {
    let failures = 0
    for (let i = 0; i < messages.length; i++) {
      const msg = messages[i]
      if (!msg) continue
      try {
        convertAndPushDbMessage(msg, messages[i + 1], targetState, seenMessageIds)
      } catch (error) {
        failures++
        console.error(`[TaskPane ${paneId}] Failed to convert persisted message ${msg.id}:`, error)
      }
    }
    return failures
  }

  function removeHistoryLoadErrorMessage() {
    chatState.messages.value = chatState.messages.value.filter((message) => (
      (message.raw as Record<string, unknown> | undefined)?.subtype !== 'history_load_failed'
    ))
  }

  function showHistoryLoadError(message: string) {
    historyLoadError.value = message
    removeHistoryLoadErrorMessage()
    chatState.messages.value.push({
      id: `load-error-${Date.now()}`,
      type: AipMessageType.ERROR,
      sender: 'system',
      content: '',
      error: message,
      raw: { subtype: 'history_load_failed' },
      timestamp: Date.now(),
    } as ChatMessage)
  }

  function applyInitialHistoryResult(result: sessionApi.PaginatedMessages) {
    totalDbMessages = result.total
    totalMessages.value = result.total
    dbLoadedOffset = result.messages.length
    allDbLoaded = !result.hasMore
    hasMoreHistory.value = result.hasMore
    const failures = convertDbMessagesSafely(result.messages, chatState, knownMessageIds)
    historyLoadError.value = failures > 0
      ? `${failures} 条历史消息解析失败，其他消息已保留`
      : ''
  }

  async function loadInitialHistory(sessionId: string, version: number): Promise<void> {
    let lastError: unknown
    for (const pageSize of INITIAL_HISTORY_PAGE_SIZES) {
      try {
        const result = await sessionApi.getLatestMessages(sessionId, pageSize, 0)
        if (connectVersion !== version) return
        applyInitialHistoryResult(result)
        return
      } catch (error) {
        lastError = error
        console.warn(`[TaskPane ${paneId}] Failed to load history with limit=${pageSize}:`, error)
      }
    }
    throw lastError ?? new Error('SESSION_HISTORY_UNAVAILABLE')
  }

  function appendMissingTaskResult() {
    const currentTask = task.value
    const resultText = currentTask?.resultText?.trim()
    if (currentTask?.status !== 'COMPLETED' || !resultText) return

    const hasPersistedResult = chatState.messages.value.some((message) => {
      if (message.sender !== 'assistant' || message.type !== AipMessageType.TEXT_COMPLETE) return false
      const messageTaskId = taskIdOfMessage(message)
      // A task-owned final message is authoritative even when the task
      // projection normalizes its text differently (for example whitespace or
      // rendered report formatting). Legacy unowned rows remain conservative:
      // only the same content can suppress a recovery for a later task.
      return messageTaskId === currentTask.taskId
        || (messageTaskId == null && message.content?.trim() === resultText)
    })
    if (hasPersistedResult) return

    chatState.messages.value.push({
      id: `task-result-${currentTask.taskId}`,
      type: AipMessageType.TEXT_COMPLETE,
      sender: 'assistant',
      content: resultText,
      raw: { taskId: currentTask.taskId, isResult: true, recoveredFromTask: true },
      timestamp: currentTask.updatedAt ? new Date(currentTask.updatedAt).getTime() : Date.now(),
    })
  }

  function taskIdOfMessage(message: ChatMessage): string | undefined {
    if (message.taskId) return message.taskId
    if (!message.raw || typeof message.raw !== 'object') return undefined
    const taskId = (message.raw as Record<string, unknown>).taskId
    return typeof taskId === 'string' && taskId ? taskId : undefined
  }

  /** SSE event handler — shared by connect and resumeInPlace */
  function handleSseEvent(raw: AgentMessage) {
    if (raw.type === NATIVE_SUBTASK_UPDATE_TYPE) {
      const update = parseNativeSubtaskUpdate(raw.payload)
      if (
        task.value?.providerType === CODEX_APP_SERVER_PROVIDER
        && update
        && update.taskId === task.value.taskId
      ) {
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

    const payload = raw.payload && typeof raw.payload === 'object'
      ? raw.payload as Record<string, unknown>
      : undefined

    // Ignore a replay for a previous task before it reaches chat-state. This
    // matters when a session contains several task turns and their SSE feeds
    // briefly overlap during a reconnect.
    if (task.value && typeof payload?.taskId === 'string' && payload.taskId !== task.value.taskId) return

    // 1. Pass through adapter for chat messages, de-duplicated against both
    // persisted history and already-received SSE events.
    const msgs = agentMessageAdapter.convert(raw, raw.sessionId)
    for (const msg of msgs) {
      if (msg.messageId && knownMessageIds.has(msg.messageId)) continue
      if (msg.messageId) knownMessageIds.add(msg.messageId)
      chatState.processAipMessage(msg)
    }

    // 2. Handle raw events for task state tracking
    if (!task.value) return
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
      const hasQuestions = Array.isArray(payload.questions) && payload.questions.length > 0
      const backend = inferTaskWorkerBackend(task.value)
      const isCodexTask = backend === 'OPENAI_CODEX' || backend === 'OPENAI_CODEX_APP_SERVER'
      task.value.status = isCodexTask && hasQuestions
        ? 'AWAITING_INPUT'
        : 'AWAITING_PERMISSION'
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
      if (payload.reconnectable === true) return
      task.value.status = 'FAILED'
      if (typeof payload.errorMessage === 'string') {
        task.value.errorMessage = payload.errorMessage
      } else if (typeof payload.content === 'string') {
        task.value.errorMessage = payload.content
      }
      task.value.error = {
        errorCode: typeof payload.errorCode === 'string' ? payload.errorCode : undefined,
        message: typeof payload.message === 'string' ? payload.message : undefined,
        category: typeof payload.category === 'string' ? payload.category : undefined,
        runtimePhase: typeof payload.runtimePhase === 'string' ? payload.runtimePhase : undefined,
        recoverable: typeof payload.recoverable === 'boolean' ? payload.recoverable : undefined,
        diagnosticRef: typeof payload.diagnosticRef === 'string' ? payload.diagnosticRef : undefined,
        occurredAt: typeof payload.occurredAt === 'string' ? payload.occurredAt : undefined,
        taskId: typeof payload.taskId === 'string' ? payload.taskId : task.value.taskId,
        providerType: typeof payload.providerType === 'string' ? payload.providerType : undefined,
        runtimeType: typeof payload.runtimeType === 'string' ? payload.runtimeType : undefined,
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
          void syncTaskStatus({ force: true })
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
      && task.value.providerType === CODEX_APP_SERVER_PROVIDER
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
    knownMessageIds.clear()
    totalDbMessages = 0
    dbLoadedOffset = 0
    allDbLoaded = false
    loadingMore.value = false
    hasMoreHistory.value = false
    totalMessages.value = 0
    historyLoadError.value = ''
    historyRetrying.value = false

    // Load a bounded latest page and progressively fall back if a proxy or
    // transport closes a large response early.
    try {
      await loadInitialHistory(sessionId, myVersion)
      if (connectVersion !== myVersion) return
      // Attach pending images to the first user message (for newly created tasks)
      if (pendingImages && pendingImages.length > 0) {
        const firstUserMsg = chatState.messages.value.find(m => m.sender === 'user')
        if (firstUserMsg) {
          firstUserMsg.images = pendingImages
        }
      }
      appendMissingTaskResult()
    } catch (e) {
      if (connectVersion !== myVersion) return
      console.error(`[TaskPane ${paneId}] Failed to load history:`, e)
      showHistoryLoadError('消息加载失败，可直接重试；实时消息仍会继续接收')
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
      const result = await sessionApi.getLatestMessages(currentSessionId, HISTORY_PAGE_SIZE, dbLoadedOffset)

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
      convertDbMessagesSafely(result.messages, chatState, knownMessageIds)

      // Extract newly pushed messages and move them to the front
      const newlyAdded = tempMessages.splice(insertionPoint)
      if (newlyAdded.length > 0) {
        chatState.messages.value = [...newlyAdded, ...prevMessagesSnapshot]
      }

      dbLoadedOffset += result.messages.length
      allDbLoaded = !result.hasMore
      hasMoreHistory.value = result.hasMore
      historyLoadError.value = ''
    } catch (e) {
      console.error(`[TaskPane ${paneId}] Failed to load more history:`, e)
      historyLoadError.value = '较早的历史消息加载失败，可重试'
    } finally {
      loadingMore.value = false
    }
  }

  async function retryHistory(): Promise<void> {
    if (!currentSessionId || historyRetrying.value) return
    historyRetrying.value = true
    removeHistoryLoadErrorMessage()
    try {
      await loadInitialHistory(currentSessionId, connectVersion)
      appendMissingTaskResult()
    } catch (error) {
      console.error(`[TaskPane ${paneId}] History retry failed:`, error)
      showHistoryLoadError('消息加载仍未成功，请稍后重试')
    } finally {
      historyRetrying.value = false
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
      const replacementState = createChatState()
      const replacementMessageIds = new Set<string>()
      let nextTotal = 0
      let nextOffset = 0
      let nextAllLoaded = false
      let nextHasMore = false

      if (limit == null) {
        // Load ALL messages (no pagination)
        const allMessages = await sessionApi.getMessages(currentSessionId)
        convertDbMessagesSafely(allMessages, replacementState, replacementMessageIds)
        nextTotal = allMessages.length
        nextOffset = allMessages.length
        nextAllLoaded = true
      } else {
        // Load latest `limit` messages
        const result = await sessionApi.getLatestMessages(currentSessionId, limit, 0)
        convertDbMessagesSafely(result.messages, replacementState, replacementMessageIds)
        nextTotal = result.total
        nextOffset = result.messages.length
        nextAllLoaded = !result.hasMore
        nextHasMore = result.hasMore
      }

      // Commit only after the replacement history is fully available. A
      // truncated response must not erase messages already visible in the pane.
      chatState.messages.value = replacementState.messages.value
      knownMessageIds.clear()
      replacementMessageIds.forEach(messageId => knownMessageIds.add(messageId))
      totalDbMessages = nextTotal
      totalMessages.value = nextTotal
      dbLoadedOffset = nextOffset
      allDbLoaded = nextAllLoaded
      hasMoreHistory.value = nextHasMore
      historyLoadError.value = ''
    } catch (e) {
      console.error(`[TaskPane ${paneId}] Failed to load all history:`, e)
      historyLoadError.value = '完整历史加载失败，当前消息已保留'
    } finally {
      loadingMore.value = false
    }
  }

  async function getAllHistoryMessages(): Promise<ChatMessage[]> {
    const sessionId = currentSessionId || task.value?.sessionId
    if (!sessionId) return []

    const allMessages = await sessionApi.getMessages(sessionId)
    const exportState = createChatState()
    // This is an independent viewer/export reducer. It must not reuse the
    // visible-pane dedup set, otherwise every already-rendered row would be
    // filtered out of the complete record list.
    const exportMessageIds = new Set<string>()
    for (let i = 0; i < allMessages.length; i++) {
      const msg = allMessages[i]
      if (!msg) continue
      convertAndPushDbMessage(msg, allMessages[i + 1], exportState, exportMessageIds)
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
    stopSseConnectionWatcher()
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
    historyLoadError,
    historyRetrying,
    nativeSubtasks,
    nativeSubtasksLoading,
    nativeSubtaskLastEventSeq,
    connect,
    loadMoreHistory,
    retryHistory,
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
