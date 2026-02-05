<template>
  <div class="chat-panel">
    <div v-if="showHeader" class="chat-header">
      <slot name="header">
        <div class="header-left">
          <slot name="header-left" />
        </div>
        <div class="header-right">
          <StatusBadge v-if="conversationStatus" :status="conversationStatus" />
          <span :class="['connection-dot', connectionStatus]" :title="connectionLabel" />
        </div>
      </slot>
    </div>
    <MessageList
      :messages="messages"
      :is-thinking="isThinking"
    >
      <template #empty>
        <slot name="empty">
          <div class="empty-hint">暂无消息</div>
        </slot>
      </template>
    </MessageList>
    <MessageInput
      :disabled="inputDisabled"
      :placeholder="placeholder"
      @send="(content) => emit('send', content)"
    />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ChatMessage, ConnectionStatus } from '../types/chat'
import MessageList from './MessageList.vue'
import MessageInput from './MessageInput.vue'
import StatusBadge from './StatusBadge.vue'

const props = withDefaults(defineProps<{
  messages: ChatMessage[]
  isThinking?: boolean
  connectionStatus?: ConnectionStatus
  conversationStatus?: string
  inputDisabled?: boolean
  placeholder?: string
  showHeader?: boolean
}>(), {
  isThinking: false,
  connectionStatus: 'disconnected',
  inputDisabled: false,
  placeholder: '输入消息...',
  showHeader: true,
})

const emit = defineEmits<{
  (e: 'send', content: string): void
}>()

const connectionLabel = computed(() => {
  const map: Record<ConnectionStatus, string> = {
    disconnected: '未连接',
    connecting: '连接中',
    connected: '已连接',
    error: '连接错误',
  }
  return map[props.connectionStatus] ?? ''
})
</script>

<style scoped>
.chat-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  overflow: hidden;
}

.chat-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 16px;
  border-bottom: 1px solid #e4e7ed;
  background: #fafafa;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.connection-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}

.connection-dot.connected {
  background-color: #67c23a;
}

.connection-dot.connecting {
  background-color: #e6a23c;
  animation: blink 1s infinite;
}

.connection-dot.disconnected {
  background-color: #c0c4cc;
}

.connection-dot.error {
  background-color: #f56c6c;
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}
</style>
