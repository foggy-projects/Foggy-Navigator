<template>
  <div class="tool-call-group">
    <div class="group-header" @click="toggle">
      <span class="group-toggle">{{ expanded ? '\u25BC' : '\u25B6' }}</span>
      <span class="group-icon">&#9881;</span>
      <span class="group-summary">
        {{ items.length }} tool call{{ items.length > 1 ? 's' : '' }}:
        <span class="group-names">{{ toolNamesSummary }}</span>
      </span>
      <span v-if="allCompleted" class="group-status">
        <span v-if="allSucceeded" class="status-dot success"></span>
        <span v-else class="status-dot failure"></span>
      </span>
      <span v-else class="group-status">
        <span class="status-dot running"></span>
      </span>
    </div>
    <div v-if="expanded" class="group-body">
      <ToolCallBlock
        v-for="item in items"
        :key="item.id"
        :message="item"
        :default-collapsed="!isLastGroup || hasOutput(item)"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import type { ChatMessage } from '../types/chat'
import ToolCallBlock from './ToolCallBlock.vue'

const props = defineProps<{
  items: ChatMessage[]
  isLastGroup?: boolean
}>()

// Last group defaults to expanded, others collapsed
const expanded = ref(!!props.isLastGroup)

const toolNamesSummary = computed(() => {
  const names = props.items
    .map(m => m.toolName || 'Tool')
    .filter((v, i, a) => a.indexOf(v) === i)
  if (names.length <= 3) return names.join(', ')
  return names.slice(0, 3).join(', ') + ` +${names.length - 3}`
})

const allCompleted = computed(() =>
  props.items.every(m => m.toolOutput !== undefined || m.error !== undefined)
)

const allSucceeded = computed(() =>
  props.items.every(m => m.toolSuccess !== false && !m.error)
)

function hasOutput(msg: ChatMessage): boolean {
  return msg.toolOutput !== undefined || msg.error !== undefined
}

function toggle() {
  expanded.value = !expanded.value
}
</script>

<style scoped>
.tool-call-group {
  margin-bottom: 8px;
  max-width: 90%;
  flex-shrink: 0;
}

.group-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  background-color: #f5f7fa;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  color: #606266;
  user-select: none;
  transition: background-color 0.15s;
}

.group-header:hover {
  background-color: #eef1f6;
}

.group-toggle {
  font-size: 10px;
  color: #909399;
  width: 12px;
  flex-shrink: 0;
}

.group-icon {
  font-size: 13px;
  flex-shrink: 0;
}

.group-summary {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.group-names {
  color: #303133;
  font-weight: 500;
}

.group-status {
  flex-shrink: 0;
  display: flex;
  align-items: center;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}

.status-dot.success { background-color: #67c23a; }
.status-dot.failure { background-color: #f56c6c; }
.status-dot.running {
  background-color: #409eff;
  animation: pulse 1.5s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

.group-body {
  margin-top: 4px;
  padding-left: 8px;
  border-left: 2px solid #e4e7ed;
}

.group-body :deep(.tool-call-block) {
  margin-bottom: 4px;
}
</style>
