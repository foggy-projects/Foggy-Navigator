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
  addUserMessage: (content: string, sessionId?: string) => void
  setConnectionStatus: (status: ConnectionStatus) => void
  clearMessages: () => void
  resolvePermission: (permissionId: string, status: 'approved' | 'denied') => void
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

  function processAipMessage(aip: AipMessage) {
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
            timestamp: aip.timestamp,
          })
        }
        break
      }
      case AipMessageType.TOOL_CALL_RESULT: {
        const p = aip.payload as ToolCallResultPayload
        // Map backend fields: data → output fallback
        const output = p.output ?? p.data ?? ''
        const existing = messages.value.find(
          (m) => m.toolCallId === p.toolCallId && m.type === AipMessageType.TOOL_CALL_START,
        )
        if (existing) {
          existing.toolOutput = output
        } else {
          messages.value.push({
            id: aip.messageId,
            type: aip.type,
            sender: 'tool',
            content: '',
            toolCallId: p.toolCallId,
            toolName: p.toolName,
            toolOutput: output,
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
        if (conversationStatus.value === p.status) break
        conversationStatus.value = p.status
        messages.value.push({
          id: aip.messageId,
          type: aip.type,
          sender: 'system',
          content: `状态变更: ${p.status}`,
          timestamp: aip.timestamp,
        })
        break
      }
      case AipMessageType.ERROR: {
        const p = aip.payload as ErrorPayload
        messages.value.push({
          id: aip.messageId,
          type: aip.type,
          sender: 'system',
          content: '',
          error: p.error,
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
          raw: p,
          timestamp: aip.timestamp,
        })
        break
      }
    }
  }

  function addUserMessage(content: string, _sessionId?: string) {
    messages.value.push({
      id: nextId(),
      type: AipMessageType.TEXT_COMPLETE,
      sender: 'user',
      content,
      timestamp: Date.now(),
    })
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
  }
}
