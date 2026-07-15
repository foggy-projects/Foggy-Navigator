<template>
  <div class="error-block" role="alert">
    <span class="error-icon" aria-hidden="true">&#9888;</span>
    <div class="error-content">
      <strong class="error-title">{{ presentation.title }}</strong>
      <p class="error-description">{{ presentation.description }}</p>
      <p v-if="presentation.detail" class="error-detail">
        <span class="error-label">远端信息：</span>{{ presentation.detail }}
      </p>
      <p v-if="presentation.action" class="error-action">
        <span class="error-label">建议处理：</span>{{ presentation.action }}
      </p>
      <div v-if="presentation.code || props.taskId" class="error-meta">
        <span v-if="presentation.code" class="error-meta-item">
          <span class="error-meta-label">错误码</span>
          <code>{{ presentation.code }}</code>
        </span>
        <span v-if="props.taskId" class="error-meta-item">
          <span class="error-meta-label">任务 ID</span>
          <code>{{ props.taskId }}</code>
        </span>
      </div>
      <ExecutionReportInline :report-ref="props.reportRef" :digest="props.digest" />
    </div>
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
import { computed, ref } from 'vue'
import type { ExecutionReportDigest } from '../types/chat'
import { presentError } from '../utils/errorPresentation'
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
const presentation = computed(() => presentError(props.error))

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
  gap: 10px;
  margin-bottom: 12px;
  padding: 12px 14px;
  background-color: #fff7f7;
  border: 1px solid #f5c2c7;
  border-left: 3px solid #e5484d;
  border-radius: 8px;
  max-width: 90%;
}

.error-icon {
  color: #d92d20;
  font-size: 17px;
  line-height: 1.4;
  flex-shrink: 0;
}

.error-content {
  min-width: 0;
  flex: 1;
}

.error-title {
  display: block;
  color: #9f1c1c;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.5;
}

.error-description,
.error-detail,
.error-action {
  margin: 4px 0 0;
  color: #5f3131;
  font-size: 13px;
  line-height: 1.6;
  word-break: break-word;
}

.error-action {
  color: #754343;
}

.error-label {
  color: #9f1c1c;
  font-weight: 600;
}

.error-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 12px;
  margin-top: 9px;
}

.error-meta-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-width: 0;
  color: #7a4b4b;
  font-size: 12px;
}

.error-meta-label {
  flex-shrink: 0;
}

.error-meta code {
  overflow: hidden;
  max-width: min(360px, 55vw);
  padding: 1px 5px;
  color: #7f1d1d;
  background: rgba(127, 29, 29, 0.07);
  border-radius: 4px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.reconnect-btn {
  flex-shrink: 0;
  min-height: 28px;
  padding: 3px 10px;
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

.reconnect-btn:focus-visible {
  outline: 2px solid #d92d20;
  outline-offset: 2px;
}

.reconnect-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
