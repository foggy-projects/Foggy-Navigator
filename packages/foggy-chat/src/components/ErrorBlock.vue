<template>
  <div class="error-block">
    <span class="error-icon">&#9888;</span>
    <span class="error-text">
      {{ props.error }}
      <ExecutionReportInline :report-ref="props.reportRef" :digest="props.digest" />
    </span>
    <button
      v-if="props.reconnectable"
      class="reconnect-btn"
      :disabled="reconnecting"
      @click="handleReconnect"
    >
      {{ reconnecting ? '重连中...' : '重连' }}
    </button>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import type { ExecutionReportDigest } from '../types/chat'
import ExecutionReportInline from './ExecutionReportInline.vue'

const props = defineProps<{
  error: string
  reconnectable?: boolean
  taskId?: string
  reportRef?: string
  digest?: ExecutionReportDigest
}>()

const emit = defineEmits<{
  (e: 'reconnect', taskId: string): void
}>()

const reconnecting = ref(false)

function handleReconnect() {
  if (reconnecting.value || !props.taskId) return
  reconnecting.value = true
  emit('reconnect', props.taskId)
}
</script>

<style scoped>
.error-block {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 12px;
  padding: 10px 14px;
  background-color: #fef0f0;
  border: 1px solid #fde2e2;
  border-radius: 8px;
  max-width: 90%;
}

.error-icon {
  color: #f56c6c;
  font-size: 16px;
  flex-shrink: 0;
}

.error-text {
  color: #f56c6c;
  font-size: 13px;
  line-height: 1.5;
  word-break: break-word;
  flex: 1;
}

.reconnect-btn {
  flex-shrink: 0;
  padding: 2px 10px;
  font-size: 12px;
  color: #f56c6c;
  background: #fff;
  border: 1px solid #fde2e2;
  border-radius: 4px;
  cursor: pointer;
  white-space: nowrap;
  transition: background 0.2s, color 0.2s;
}

.reconnect-btn:hover:not(:disabled) {
  background: #fef0f0;
  color: #e04040;
  border-color: #f56c6c;
}

.reconnect-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
