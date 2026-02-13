<template>
  <div class="task-pane">
    <div class="pane-header">
      <div class="pane-label">
        <span class="pane-prompt" :title="paneState.task.value?.prompt">
          {{ truncate(paneState.task.value?.prompt ?? '...', 40) }}
        </span>
        <el-tag
          v-if="paneState.task.value"
          :type="taskStatusType(paneState.task.value.status)"
          size="small"
        >
          {{ paneState.task.value.status }}
        </el-tag>
      </div>
      <div class="pane-actions">
        <span v-if="paneState.task.value?.costUsd" class="cost-label">
          ${{ paneState.task.value.costUsd.toFixed(4) }}
        </span>
        <span v-if="paneState.task.value?.durationMs" class="duration-label">
          {{ (paneState.task.value.durationMs / 1000).toFixed(1) }}s
        </span>
        <el-button
          v-if="canResume"
          size="small"
          type="primary"
          plain
          @click="emit('resume', paneState.paneId)"
        >
          继续
        </el-button>
        <el-button
          v-if="paneState.task.value?.status === 'RUNNING'"
          size="small"
          type="danger"
          @click="emit('abort', paneState.paneId)"
        >
          中止
        </el-button>
        <el-button
          size="small"
          @click="emit('close', paneState.paneId)"
        >
          关闭
        </el-button>
      </div>
    </div>
    <div class="pane-body">
      <ChatPanel
        :messages="paneState.chatState.sortedMessages.value"
        :is-thinking="paneState.chatState.isThinking.value"
        :connection-status="paneState.chatState.connectionStatus.value"
        :show-header="false"
        :show-input="false"
      >
        <template #empty>
          <div class="waiting-hint">等待 Worker 响应...</div>
        </template>
      </ChatPanel>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ChatPanel } from '@foggy/chat'
import type { TaskPaneState } from '@/composables/useTaskPane'

const props = defineProps<{
  paneState: TaskPaneState
}>()

const emit = defineEmits<{
  (e: 'close', paneId: string): void
  (e: 'abort', paneId: string): void
  (e: 'resume', paneId: string): void
}>()

const canResume = computed(() => {
  const t = props.paneState.task.value
  return t && t.status === 'COMPLETED' && t.claudeSessionId
})

function taskStatusType(status: string) {
  if (status === 'COMPLETED') return 'success'
  if (status === 'RUNNING') return 'primary'
  if (status === 'FAILED') return 'danger'
  if (status === 'ABORTED') return 'warning'
  return 'info'
}

function truncate(text: string, maxLen: number) {
  return text.length > maxLen ? text.substring(0, maxLen) + '...' : text
}
</script>

<style scoped>
.task-pane {
  display: flex;
  flex-direction: column;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  overflow: hidden;
  min-height: 0;
}

.pane-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  background: #fafafa;
  border-bottom: 1px solid #e4e7ed;
  flex-shrink: 0;
  gap: 8px;
}

.pane-label {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.pane-prompt {
  font-size: 13px;
  color: #303133;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.pane-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.cost-label {
  font-size: 12px;
  color: #e6a23c;
  font-weight: 500;
}

.duration-label {
  font-size: 12px;
  color: #909399;
}

.pane-body {
  flex: 1;
  min-height: 0;
}

.pane-body > :deep(.chat-panel) {
  height: 100%;
  border: none;
  border-radius: 0;
}

.waiting-hint {
  color: #909399;
  font-size: 14px;
}
</style>
