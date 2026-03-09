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
      :rewind-enabled="rewindEnabled"
      :has-more-history="hasMoreHistory"
      :loading-more="loadingMore"
      :total-messages="totalMessages"
      @permission-respond="(pid, decision, scope) => emit('permissionRespond', pid, decision, scope)"
      @question-respond="(pid, answers) => emit('questionRespond', pid, answers)"
      @plan-respond="(pid, decision, denyMsg, planAction) => emit('planRespond', pid, decision, denyMsg, planAction)"
      @rewind="(turnIndex) => emit('rewind', turnIndex)"
      @reconnect="(taskId: string) => emit('reconnect', taskId)"
      @load-more="emit('loadMore')"
      @load-all="(limit?: number) => emit('loadAll', limit)"
    >
      <template #empty>
        <slot name="empty">
          <div class="empty-hint">暂无消息</div>
        </slot>
      </template>
    </MessageList>
    <slot v-if="showInput" name="input">
      <MessageInput
        :disabled="inputDisabled"
        :placeholder="placeholder"
        @send="(content) => emit('send', content)"
      />
    </slot>
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
  showInput?: boolean
  rewindEnabled?: boolean
  hasMoreHistory?: boolean
  loadingMore?: boolean
  totalMessages?: number
}>(), {
  isThinking: false,
  connectionStatus: 'disconnected',
  inputDisabled: false,
  placeholder: '输入消息...',
  showHeader: true,
  showInput: true,
})

const emit = defineEmits<{
  (e: 'send', content: string): void
  (e: 'permissionRespond', permissionId: string, decision: string, scope: string): void
  (e: 'questionRespond', permissionId: string, answers: Record<string, string>): void
  (e: 'planRespond', permissionId: string, decision: string, denyMessage?: string, planAction?: string): void
  (e: 'rewind', turnIndex: number): void
  (e: 'reconnect', taskId: string): void
  (e: 'loadMore'): void
  (e: 'loadAll', limit?: number): void
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
