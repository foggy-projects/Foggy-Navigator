import { ref, computed } from 'vue'
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
} from '../types/aip'
import type { ChatMessage, ConnectionStatus } from '../types/chat'

let _msgSeq = 0
function nextId(): string {
  return `msg-${Date.now()}-${++_msgSeq}`
}

export interface ChatState {
  messages: ReturnType<typeof ref<ChatMessage[]>>
  sortedMessages: ReturnType<typeof computed<ChatMessage[]>>
  connectionStatus: ReturnType<typeof ref<ConnectionStatus>>
  conversationStatus: ReturnType<typeof ref<string>>
  isThinking: ReturnType<typeof ref<boolean>>
  processAipMessage: (aip: AipMessage) => void
  addUserMessage: (content: string, sessionId?: string) => void
  setConnectionStatus: (status: ConnectionStatus) => void
  clearMessages: () => void
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
        const existingResult = messages.value.find(
          (m) => m.toolCallId === p.toolCallId && m.type === AipMessageType.TOOL_CALL_RESULT,
        )
        if (existingResult) {
          existingResult.type = AipMessageType.TOOL_CALL_START
          existingResult.content = p.command ?? p.path ?? ''
          existingResult.thought = p.thought
          existingResult.toolName = p.toolName
          existingResult.timestamp = Math.min(existingResult.timestamp, aip.timestamp)
        } else {
          messages.value.push({
            id: aip.messageId,
            type: aip.type,
            sender: 'tool',
            content: p.command ?? p.path ?? '',
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
        const existing = messages.value.find(
          (m) => m.toolCallId === p.toolCallId && m.type === AipMessageType.TOOL_CALL_START,
        )
        if (existing) {
          existing.toolOutput = p.output
        } else {
          messages.value.push({
            id: aip.messageId,
            type: aip.type,
            sender: 'tool',
            content: '',
            toolCallId: p.toolCallId,
            toolName: p.toolName,
            toolOutput: p.output,
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
  }
}
