<template>
  <div :class="['message-bubble', props.message.sender]">
    <div class="bubble-header">
      <span class="sender-label">{{ senderLabel }}</span>
      <span class="timestamp">{{ formattedTime }}</span>
    </div>
    <div class="bubble-content">{{ props.message.content }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ChatMessage } from '../types/chat'

const props = defineProps<{
  message: ChatMessage
}>()

const senderLabel = computed(() => {
  switch (props.message.sender) {
    case 'user': return '用户'
    case 'assistant': return 'Agent'
    case 'system': return '系统'
    default: return props.message.sender
  }
})

const formattedTime = computed(() => {
  const d = new Date(props.message.timestamp)
  return d.toLocaleTimeString()
})
</script>

<style scoped>
.message-bubble {
  margin-bottom: 12px;
  padding: 10px 14px;
  border-radius: 8px;
  max-width: 80%;
}

.message-bubble.user {
  margin-left: auto;
  background-color: #e1f5fe;
  border-bottom-right-radius: 2px;
}

.message-bubble.assistant {
  margin-right: auto;
  background-color: #f5f5f5;
  border-bottom-left-radius: 2px;
}

.message-bubble.system {
  margin: 0 auto;
  max-width: 90%;
  background-color: #fff3e0;
  text-align: center;
  font-size: 13px;
}

.bubble-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}

.sender-label {
  font-weight: 600;
  font-size: 12px;
  color: #606266;
}

.timestamp {
  font-size: 11px;
  color: #909399;
}

.bubble-content {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.5;
}
</style>
