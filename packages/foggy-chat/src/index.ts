// Types
export { AipMessageType } from './types/aip'
export type {
  AipMessage,
  TextPayload,
  ToolCallStartPayload,
  ToolCallResultPayload,
  ToolCallErrorPayload,
  ThinkingPayload,
  StateSyncPayload,
  ErrorPayload,
  TaskCompletedPayload,
} from './types/aip'
export type { EventAdapter } from './types/adapter'
export type { ChatMessage, ConnectionStatus } from './types/chat'

// SSE
export { createSseClient } from './sse/SseClient'
export type { SseClientOptions, SseController } from './sse/SseClient'

// Store
export { useChatStore } from './store/useChatStore'
export { createChatState } from './store/chatState'
export type { ChatState } from './store/chatState'

// Components
export { default as ChatPanel } from './components/ChatPanel.vue'
export { default as MessageList } from './components/MessageList.vue'
export { default as MessageBubble } from './components/MessageBubble.vue'
export { default as MessageInput } from './components/MessageInput.vue'
export { default as ToolCallBlock } from './components/ToolCallBlock.vue'
export { default as ThinkingIndicator } from './components/ThinkingIndicator.vue'
export { default as ErrorBlock } from './components/ErrorBlock.vue'
export { default as StatusBadge } from './components/StatusBadge.vue'
export { default as TaskCompletionCard } from './components/TaskCompletionCard.vue'
