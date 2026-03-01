import { ref, type Ref } from 'vue'
import { createChatState, createSseClient, AipMessageType } from '@foggy/chat'
import type { ChatState, SseController, ChatMessage } from '@foggy/chat'
import { tutorAgentAdapter } from '@/adapters/TutorAgentAdapter'
import * as sessionApi from '@/api/session'
import * as workerApi from '@/api/claudeWorker'
import { getToken } from '@/utils/auth'
import type { AgentMessage, ClaudeTask } from '@/types'

export interface TaskPaneState {
  paneId: string
  task: Ref<ClaudeTask | null>
  chatState: ChatState
  /** Pre-fill the input field (e.g. after rewind with original prompt) */
  pendingInput: Ref<string>
  connect(sessionId: string): Promise<void>
  /** Resume in-place: keep messages, update task, reconnect SSE */
  resumeInPlace(newTask: ClaudeTask): void
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
  let sseController: SseController | null = null

  // User-level SSE fallback: listen for task_status_change via useNotifications
  let taskUpdateHandler: ((event: Event) => void) | null = null

  function attachTaskUpdateListener() {
    detachTaskUpdateListener()
    taskUpdateHandler = (event: Event) => {
      const detail = (event as CustomEvent).detail
      if (!task.value || detail?.taskId !== task.value.taskId) return
      const newStatus = detail?.status as string
      if (newStatus && newStatus !== task.value.status) {
        task.value.status = newStatus as any
        if (detail.errorMessage) task.value.errorMessage = detail.errorMessage
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

  // --- Status polling fallback (activated when SSE retries exhausted) ---
  let statusPollTimer: ReturnType<typeof setInterval> | null = null

  function startStatusPolling() {
    stopStatusPolling()
    statusPollTimer = setInterval(() => syncTaskStatus(), 10_000)
  }

  function stopStatusPolling() {
    if (statusPollTimer) {
      clearInterval(statusPollTimer)
      statusPollTimer = null
    }
  }

  async function syncTaskStatus() {
    if (!task.value) return
    if (['COMPLETED', 'FAILED', 'ABORTED'].includes(task.value.status)) return
    try {
      const fresh = await workerApi.getTask(task.value.taskId)
      if (fresh.status !== task.value.status) {
        Object.assign(task.value, fresh)
        if (['COMPLETED', 'FAILED', 'ABORTED'].includes(fresh.status)) {
          options?.onTaskFinished?.(paneId)
          stopStatusPolling()
        }
      }
    } catch {
      /* task deleted or inaccessible — ignore */
    }
  }

  /** Create SSE connection for a given sessionId (shared by connect and resumeInPlace) */
  function createSseConnection(sessionId: string) {
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
        startStatusPolling()
      },
      onRawEvent: (raw: AgentMessage) => {
        if (!task.value) return
        const payload = raw.payload as Record<string, unknown> | undefined
        if (!payload?.taskId) return

        // taskId guard: ignore events from previous tasks in the same session
        if (payload.taskId !== task.value.taskId) return

        // Capture claudeSessionId as early as possible (from SESSION_START / system events)
        if (typeof payload.claudeSessionId === 'string' && payload.claudeSessionId) {
          task.value.claudeSessionId = payload.claudeSessionId
        }

        if (raw.type === 'CONFIRMATION_REQUEST') {
          task.value.status = 'AWAITING_PERMISSION' as ClaudeTask['status']
        } else if (raw.type === 'TEXT_COMPLETE') {
          task.value.status = 'COMPLETED'
          if (typeof payload.costUsd === 'number') task.value.costUsd = payload.costUsd
          if (typeof payload.durationMs === 'number') task.value.durationMs = payload.durationMs
          if (typeof payload.inputTokens === 'number') task.value.inputTokens = payload.inputTokens
          if (typeof payload.outputTokens === 'number') task.value.outputTokens = payload.outputTokens
          if (typeof payload.numTurns === 'number') task.value.numTurns = payload.numTurns
          if (typeof payload.model === 'string') task.value.model = payload.model
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

  async function connect(sessionId: string) {
    disconnect()
    chatState.clearMessages()
    chatState.setConnectionStatus('connecting')

    // Load history messages — reconstruct typed messages from metadata
    let dbMessageCount = 0
    try {
      const messages = await sessionApi.getMessages(sessionId)
      for (const msg of messages) {
        // Count USER/ASSISTANT messages (same口径 as JSONL count)
        if (msg.role === 'USER' || msg.role === 'ASSISTANT') {
          dbMessageCount++
        }
        const meta = msg.metadata
        const msgType = meta?.type as string | undefined
        const ts = new Date(msg.createdAt).getTime()

        if (msg.role === 'USER') {
          chatState.messages.value.push({
            id: msg.id,
            type: AipMessageType.TEXT_COMPLETE,
            sender: 'user',
            content: msg.content || '',
            timestamp: ts,
          })
        } else if (msgType && msgType in AipMessageType) {
          // Structured messages — reconstruct from metadata via processAipMessage
          chatState.processAipMessage({
            messageId: msg.id,
            sessionId: msg.sessionId,
            timestamp: ts,
            type: msgType as AipMessageType,
            payload: meta,
          })
        } else {
          // Fallback — render as plain text
          chatState.messages.value.push({
            id: msg.id,
            type: AipMessageType.TEXT_COMPLETE,
            sender: 'assistant',
            content: msg.content || '',
            timestamp: ts,
          })
        }
      }
    } catch (e) {
      console.error(`[TaskPane ${paneId}] Failed to load history:`, e)
    }

    createSseConnection(sessionId)
    attachTaskUpdateListener()

    // Non-blocking: detect and load JSONL delta messages
    if (task.value?.claudeSessionId && task.value?.workerId) {
      detectAndLoadDelta(task.value.workerId, task.value.claudeSessionId, dbMessageCount)
    }
  }

  /** Detect new messages in JSONL that are not in the DB and append them to chat */
  async function detectAndLoadDelta(workerId: string, claudeSessionId: string, dbMsgCount: number) {
    try {
      // 1. Get JSONL message count from Worker
      const countResult = await workerApi.getWorkerSessionMessageCount(workerId, claudeSessionId)
      const jsonlTotal = countResult.total

      // 2. If JSONL has more messages than DB and DB has at least some messages
      if (jsonlTotal > dbMsgCount && dbMsgCount > 0) {
        // 3. Fetch all JSONL messages
        const allMessages = await workerApi.getWorkerSessionMessages(workerId, claudeSessionId)

        // 4. Take the delta (messages after DB count)
        const delta = allMessages.slice(dbMsgCount)
        if (delta.length === 0) return

        // 5. Insert separator
        const separatorMsg: ChatMessage = {
          id: `delta-separator-${Date.now()}`,
          type: AipMessageType.TEXT_COMPLETE,
          sender: 'assistant',
          content: `--- 检测到 ${delta.length} 条外部对话 ---`,
          timestamp: Date.now(),
        }
        chatState.messages.value.push(separatorMsg)

        // 6. Append delta messages
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

        console.log(`[TaskPane ${paneId}] Loaded ${delta.length} delta messages from JSONL`)
      }
    } catch (e) {
      // Best-effort: fail silently
      console.debug(`[TaskPane ${paneId}] Delta detection failed (non-critical):`, e)
    }
  }

  /** Resume in the same pane without clearing messages */
  function resumeInPlace(newTask: ClaudeTask) {
    task.value = newTask
    chatState.addUserMessage(newTask.prompt)

    // Disconnect old SSE (without clearing messages)
    if (sseController) {
      sseController.close()
      sseController = null
    }
    chatState.setConnectionStatus('connecting')

    // Reconnect SSE to the same sessionId (no history reload)
    createSseConnection(newTask.sessionId)
    attachTaskUpdateListener()
  }

  function disconnect() {
    if (sseController) {
      sseController.close()
      sseController = null
    }
    stopStatusPolling()
    detachTaskUpdateListener()
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
    pendingInput,
    connect,
    resumeInPlace,
    syncTaskStatus,
    disconnect,
    dispose,
  }
}
