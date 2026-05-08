import { ref, computed, type ComputedRef, type Ref } from 'vue'
import { AipMessageType } from '../types/aip'
import type {
  AipMessage,
  TextPayload,
  ToolCallStartPayload,
  ToolCallResultPayload,
  ToolCallErrorPayload,
  ThinkingPayload,
  StateSyncPayload,
  ErrorPayload,
  TaskCompletedPayload,
  ConfirmationRequestPayload,
  ConfirmationResponsePayload,
} from '../types/aip'
import type { ChatMessage, ConnectionStatus } from '../types/chat'

let _msgSeq = 0
function nextId(): string {
  return `msg-${Date.now()}-${++_msgSeq}`
}

export interface ChatState {
  messages: Ref<ChatMessage[]>
  sortedMessages: ComputedRef<ChatMessage[]>
  connectionStatus: Ref<ConnectionStatus>
  conversationStatus: Ref<string>
  isThinking: Ref<boolean>
  processAipMessage: (aip: AipMessage) => void
  addUserMessage: (content: string, sessionId?: string, images?: Array<{ name: string; url: string }>) => void
  setConnectionStatus: (status: ConnectionStatus) => void
  clearMessages: () => void
  resolvePermission: (permissionId: string, status: 'approved' | 'denied') => void
  resolveSkillApproval: (taskId: string, decision: 'approved' | 'rejected') => void
}

function formatArguments(args: unknown): string {
  if (!args) return ''
  if (typeof args === 'string') return args
  try {
    const str = JSON.stringify(args)
    // Treat empty object as no arguments
    return str === '{}' ? '' : str
  } catch {
    return String(args)
  }
}

export function createChatState(): ChatState {
  const messages = ref<ChatMessage[]>([])
  const connectionStatus = ref<ConnectionStatus>('disconnected')
  const conversationStatus = ref<string>('')
  const isThinking = ref(false)

  const sortedMessages = computed(() =>
    [...messages.value].sort((a, b) => a.timestamp - b.timestamp),
  )

  function removeWaitingHint() {
    const idx = messages.value.findIndex(
      (m) => m.type === AipMessageType.STATE_SYNC && (m.raw as Record<string, unknown>)?.subtype === 'waiting',
    )
    if (idx >= 0) messages.value.splice(idx, 1)
  }

  function processAipMessage(aip: AipMessage) {
    // Clear waiting hint when a real event arrives (except STATE_SYNC/waiting itself)
    const raw = aip.payload as Record<string, unknown> | undefined
    if (!(aip.type === AipMessageType.STATE_SYNC && raw?.subtype === 'waiting')) {
      removeWaitingHint()
    }

    switch (aip.type) {
      case AipMessageType.TEXT_CHUNK: {
        const p = aip.payload as TextPayload
        isThinking.value = false
        const lastChunk = [...messages.value].reverse().find(
          (m) => m.type === AipMessageType.TEXT_CHUNK && m.sender === 'assistant',
        )
        if (lastChunk) {
          lastChunk.content += p.content
        } else {
          messages.value.push({
            id: aip.messageId,
            type: aip.type,
            sender: 'assistant',
            content: p.content,
            timestamp: aip.timestamp,
          })
        }
        break
      }
      case AipMessageType.TEXT_COMPLETE: {
        const p = aip.payload as TextPayload
        isThinking.value = false
        // Skip result events — their text content was already emitted by assistant_text.
        // The result event still carries metadata (cost, tokens) handled by useTaskPane.
        const raw = aip.payload as Record<string, unknown>
        if (raw?.isResult === true) break
        const lastChunk = [...messages.value].reverse().find(
          (m) => m.type === AipMessageType.TEXT_CHUNK && m.sender === 'assistant',
        )
        if (lastChunk) {
          lastChunk.content = p.content
          lastChunk.type = AipMessageType.TEXT_COMPLETE
        } else {
          messages.value.push({
            id: aip.messageId,
            type: aip.type,
            sender: 'assistant',
            content: p.content,
            timestamp: aip.timestamp,
          })
        }
        break
      }
      case AipMessageType.THINKING: {
        const p = aip.payload as ThinkingPayload
        isThinking.value = true
        messages.value.push({
          id: aip.messageId,
          type: aip.type,
          sender: 'assistant',
          content: '',
          thought: p.thought,
          timestamp: aip.timestamp,
        })
        break
      }
      case AipMessageType.TOOL_CALL_START: {
        const p = aip.payload as ToolCallStartPayload
        isThinking.value = false
        // Map backend fields: arguments → command fallback
        const command = p.command ?? p.path ?? formatArguments(p.arguments)
        const existingResult = messages.value.find(
          (m) => m.toolCallId === p.toolCallId && m.type === AipMessageType.TOOL_CALL_RESULT,
        )
        if (existingResult) {
          existingResult.type = AipMessageType.TOOL_CALL_START
          existingResult.content = command
          existingResult.thought = p.thought
          existingResult.toolName = p.toolName
          existingResult.timestamp = Math.min(existingResult.timestamp, aip.timestamp)
        } else {
          messages.value.push({
            id: aip.messageId,
            type: aip.type,
            sender: 'tool',
            content: command,
            toolCallId: p.toolCallId,
            toolName: p.toolName,
            thought: p.thought,
            raw: aip.payload,
            timestamp: aip.timestamp,
          })
        }
        break
      }
      case AipMessageType.TOOL_CALL_RESULT: {
        const p = aip.payload as ToolCallResultPayload
        // Map backend fields: data → output fallback
        const output = p.output ?? p.data ?? ''
        const success = p.success !== false
        const existing = messages.value.find(
          (m) => m.toolCallId === p.toolCallId && m.type === AipMessageType.TOOL_CALL_START,
        )
        if (existing) {
          existing.toolOutput = output
          existing.toolSuccess = success
        } else {
          messages.value.push({
            id: aip.messageId,
            type: aip.type,
            sender: 'tool',
            content: '',
            toolCallId: p.toolCallId,
            toolName: p.toolName,
            toolOutput: output,
            toolSuccess: success,
            timestamp: aip.timestamp,
          })
        }
        break
      }
      case AipMessageType.TOOL_CALL_ERROR: {
        const p = aip.payload as ToolCallErrorPayload
        const existing = messages.value.find(
          (m) => m.toolCallId === p.toolCallId && m.type === AipMessageType.TOOL_CALL_START,
        )
        if (existing) {
          existing.error = p.errorMessage
        } else {
          messages.value.push({
            id: aip.messageId,
            type: aip.type,
            sender: 'tool',
            content: '',
            toolCallId: p.toolCallId,
            toolName: p.toolName,
            error: p.errorMessage,
            timestamp: aip.timestamp,
          })
        }
        break
      }
      case AipMessageType.STATE_SYNC: {
        const p = aip.payload as StateSyncPayload
        const raw = aip.payload as unknown as Record<string, unknown>
        const subtype = raw.subtype as string | undefined
        if (subtype === 'auto_compact' || subtype === 'context_compression') {
          // Context compression hint — render as lightweight divider
          messages.value.push({
            id: aip.messageId,
            type: aip.type,
            sender: 'system',
            content: (raw.content as string) || 'Context compressed',
            raw: { subtype },
            timestamp: aip.timestamp,
          })
          break
        }
        if (subtype === 'reconnected') {
          const taskId = raw.taskId as string | undefined
          const alreadyRendered = messages.value.some((m) => {
            if (m.type !== AipMessageType.STATE_SYNC) return false
            const msgRaw = m.raw as Record<string, unknown> | undefined
            if (msgRaw?.subtype !== 'reconnected') return false
            if (!taskId) return true
            return msgRaw.taskId === taskId
          })
          if (alreadyRendered) break
          // Stream reconnected hint — lightweight divider (same as compression hint)
          messages.value.push({
            id: aip.messageId,
            type: aip.type,
            sender: 'system',
            content: (raw.content as string) || 'Task stream reconnected',
            raw: taskId ? { subtype, taskId } : { subtype },
            timestamp: aip.timestamp,
          })
          break
        }
        if (subtype === 'sync_checkpoint' || subtype === 'reconnected') {
          // Internal sync events — consume silently, do not render
          break
        }
        if (subtype === 'approval_required' || subtype === 'skill_approval_request') {
          // Skill/FSScript approval request from langgraph-biz-worker.
          messages.value.push({
            id: aip.messageId,
            type: aip.type,
            sender: 'system',
            content: (raw.content as string) || 'Skill requires approval',
            approvalStatus: 'pending',
            raw,
            timestamp: aip.timestamp,
          })
          break
        }
        if (subtype === 'waiting') {
          // "Waiting for response" hint — replace previous waiting msg to avoid stacking
          const existingIdx = messages.value.findIndex(
            (m) => m.type === AipMessageType.STATE_SYNC && (m.raw as Record<string, unknown>)?.subtype === 'waiting',
          )
          const elapsed = (raw.elapsedSeconds as number) || 0
          const timeout = (raw.timeoutSeconds as number) || 600
          const mins = Math.floor(elapsed / 60)
          const content = mins > 0
            ? `Waiting for response... (${mins}min elapsed)`
            : `Waiting for response...`
          const msg: ChatMessage = {
            id: aip.messageId,
            type: aip.type,
            sender: 'system',
            content,
            raw: { subtype, elapsedSeconds: elapsed, timeoutSeconds: timeout },
            timestamp: aip.timestamp,
          }
          if (existingIdx >= 0) {
            messages.value[existingIdx] = msg
          } else {
            messages.value.push(msg)
          }
          break
        }
        // Default: use status if available, otherwise fall back to content field
        const statusText = p.status || (raw.content as string)
        if (!statusText) break // skip empty STATE_SYNC
        if (conversationStatus.value === statusText) break
        conversationStatus.value = statusText
        messages.value.push({
          id: aip.messageId,
          type: aip.type,
          sender: 'system',
          content: p.status ? `状态变更: ${p.status}` : statusText,
          timestamp: aip.timestamp,
        })
        break
      }
      case AipMessageType.ERROR: {
        const p = aip.payload as ErrorPayload
        // Backend may store error text as 'content' (WorkerStreamRelay) or 'error' (ErrorPayload)
        const raw = aip.payload as Record<string, unknown>
        const errorText = p.error || (raw?.content as string) || ''
        messages.value.push({
          id: aip.messageId,
          type: aip.type,
          sender: 'system',
          content: '',
          error: errorText,
          reconnectable: raw?.reconnectable === true,
          raw: { taskId: raw?.taskId, reconnectable: raw?.reconnectable },
          timestamp: aip.timestamp,
        })
        break
      }
      case AipMessageType.TASK_COMPLETED: {
        const p = aip.payload as TaskCompletedPayload
        messages.value.push({
          id: aip.messageId,
          type: aip.type,
          sender: 'system',
          content: p.resultSummary || '',
          raw: p,
          timestamp: aip.timestamp,
        })
        break
      }
      case AipMessageType.SESSION_START: {
        const p = aip.payload as TextPayload
        const content = p?.content
        if (content) {
          messages.value.push({
            id: aip.messageId,
            type: AipMessageType.SESSION_START,
            sender: 'assistant',
            content,
            timestamp: aip.timestamp,
          })
        }
        break
      }
      case AipMessageType.CHECKPOINT: {
        // Checkpoint events are stored silently — not rendered as chat messages
        break
      }
      case AipMessageType.CONFIRMATION_REQUEST: {
        const p = aip.payload as ConfirmationRequestPayload
        messages.value.push({
          id: aip.messageId,
          type: aip.type,
          sender: 'system',
          content: '',
          toolName: p.toolName,
          permissionId: p.permissionId,
          permissionStatus: 'pending',
          questions: p.questions,
          planReview: p.planReview,
          allowedPrompts: p.allowedPrompts,
          plan: p.plan,
          raw: p,
          timestamp: aip.timestamp,
        })
        break
      }
      case AipMessageType.CONFIRMATION_RESPONSE: {
        const p = aip.payload as ConfirmationResponsePayload
        // Find the matching CONFIRMATION_REQUEST and resolve it
        const target = messages.value.find(
          (m) => m.permissionId === p.permissionId && m.type === AipMessageType.CONFIRMATION_REQUEST,
        )
        if (target) {
          target.permissionStatus = p.decision === 'allow' ? 'approved' : 'denied'
          // Restore answered values from persisted answers
          if (p.answers && target.questions) {
            const vals: Record<number, string> = {}
            target.questions.forEach((q, qi) => {
              if (p.answers![q.question]) {
                vals[qi] = p.answers![q.question]
              }
            })
            target.answeredValues = vals
          }
        }
        break
      }
    }
  }

  function addUserMessage(content: string, _sessionId?: string, images?: Array<{ name: string; url: string }>) {
    const msg: ChatMessage = {
      id: nextId(),
      type: AipMessageType.TEXT_COMPLETE,
      sender: 'user',
      content,
      timestamp: Date.now(),
    }
    if (images && images.length > 0) {
      msg.images = images
    }
    messages.value.push(msg)
  }

  function setConnectionStatus(status: ConnectionStatus) {
    connectionStatus.value = status
  }

  function resolvePermission(permissionId: string, status: 'approved' | 'denied') {
    const msg = messages.value.find(
      (m) => m.permissionId === permissionId && m.type === AipMessageType.CONFIRMATION_REQUEST,
    )
    if (msg) {
      msg.permissionStatus = status
    }
  }

  function resolveSkillApproval(taskId: string, decision: 'approved' | 'rejected') {
    const msg = messages.value.find(
      (m) =>
        m.approvalStatus === 'pending' &&
        (m.raw as Record<string, unknown>)?.taskId === taskId,
    )
    if (msg) {
      msg.approvalStatus = decision
    }
  }

  function clearMessages() {
    messages.value = []
    isThinking.value = false
    conversationStatus.value = ''
  }

  return {
    messages,
    sortedMessages,
    connectionStatus,
    conversationStatus,
    isThinking,
    processAipMessage,
    addUserMessage,
    setConnectionStatus,
    clearMessages,
    resolvePermission,
    resolveSkillApproval,
  }
}
