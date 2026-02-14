<template>
  <div :class="['task-completion-card', statusClass]">
    <div class="card-header">
      <span class="card-icon">{{ statusIcon }}</span>
      <span class="card-title">{{ title }}</span>
      <span :class="['status-badge', statusClass]">{{ message.raw?.status || 'COMPLETED' }}</span>
    </div>
    <div v-if="message.content" class="card-body">
      {{ message.content }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ChatMessage } from '../types/chat'

const props = defineProps<{
  message: ChatMessage
}>()

const status = computed(() => (props.message.raw as Record<string, unknown>)?.status as string || 'COMPLETED')
const taskType = computed(() => (props.message.raw as Record<string, unknown>)?.taskType as string || '')
const targetAgent = computed(() => (props.message.raw as Record<string, unknown>)?.targetAgentId as string || '')

const title = computed(() => {
  const agent = targetAgent.value || taskType.value
  return `${agent} 任务已完成`
})

const statusClass = computed(() => {
  switch (status.value) {
    case 'COMPLETED': return 'success'
    case 'FAILED': return 'error'
    default: return 'info'
  }
})

const statusIcon = computed(() => {
  switch (status.value) {
    case 'COMPLETED': return '\u2705'
    case 'FAILED': return '\u274C'
    default: return '\u2139\uFE0F'
  }
})
</script>

<style scoped>
.task-completion-card {
  margin: 8px 0;
  padding: 12px 16px;
  border-radius: 8px;
  border-left: 4px solid #909399;
  background: #f4f4f5;
  max-width: 480px;
}

.task-completion-card.success {
  border-left-color: #67c23a;
  background: #f0f9eb;
}

.task-completion-card.error {
  border-left-color: #f56c6c;
  background: #fef0f0;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.card-icon {
  font-size: 16px;
}

.card-title {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
  flex: 1;
}

.status-badge {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
  font-weight: 500;
}

.status-badge.success {
  background: #e1f3d8;
  color: #67c23a;
}

.status-badge.error {
  background: #fde2e2;
  color: #f56c6c;
}

.status-badge.info {
  background: #e9e9eb;
  color: #909399;
}

.card-body {
  margin-top: 8px;
  font-size: 13px;
  color: #606266;
  line-height: 1.5;
  word-break: break-all;
}
</style>
