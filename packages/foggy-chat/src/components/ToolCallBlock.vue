<template>
  <div :class="['tool-call-block', specialClass]">
    <!-- TodoWrite: visual task list -->
    <template v-if="isTodoWrite && todoItems.length">
      <div class="tool-header">
        <span class="tool-icon">&#9745;</span>
        <span class="tool-name">Task List</span>
        <span class="todo-stats">
          {{ todoStats.completed }}/{{ todoStats.total }}
        </span>
      </div>
      <div class="todo-list">
        <div v-for="(item, i) in todoItems" :key="i" :class="['todo-item', item.status]">
          <span :class="['todo-check', item.status]">
            {{ item.status === 'completed' ? '\u2705' : item.status === 'in_progress' ? '\u23F3' : '\u2B1C' }}
          </span>
          <span class="todo-text">{{ item.content }}</span>
          <span v-if="item.status === 'in_progress' && item.activeForm" class="todo-active">
            {{ item.activeForm }}
          </span>
        </div>
      </div>
    </template>

    <!-- Task (Subagent): nested context -->
    <template v-else-if="isSubagent">
      <div class="tool-header subagent-header">
        <span class="tool-icon">&#129302;</span>
        <span class="tool-name">Subagent</span>
        <span v-if="subagentDesc" class="subagent-desc">{{ subagentDesc }}</span>
        <span v-if="subagentType" class="subagent-type">{{ subagentType }}</span>
      </div>
      <div v-if="props.message.content" class="tool-command">
        <div class="code-label">prompt</div>
        <pre class="code-block subagent-prompt"><code>{{ truncate(props.message.content, 500) }}</code></pre>
      </div>
      <div v-if="props.message.toolOutput" class="tool-output">
        <div class="code-label">result</div>
        <pre class="code-block output"><code>{{ props.message.toolOutput }}</code></pre>
      </div>
    </template>

    <!-- Default: standard tool call with collapse/expand -->
    <template v-else>
      <div class="tool-header tool-header-clickable" @click="toggleCollapse">
        <span class="tool-toggle">{{ collapsed ? '\u25B6' : '\u25BC' }}</span>
        <span class="tool-icon">&#9881;</span>
        <span class="tool-name">{{ props.message.toolName || 'Tool' }}</span>
        <span v-if="collapsed && commandSummary" class="tool-summary">{{ commandSummary }}</span>
        <span v-if="props.message.thought" class="tool-thought">{{ props.message.thought }}</span>
        <span class="tool-status-indicator">
          <span v-if="isRunning" class="status-dot running"></span>
          <span v-else-if="props.message.error" class="status-dot failure"></span>
          <span v-else-if="props.message.toolSuccess === false" class="status-dot failure"></span>
          <span v-else-if="hasResult" class="status-dot success"></span>
        </span>
      </div>
      <template v-if="!collapsed">
        <div v-if="props.message.content" class="tool-command">
          <div class="code-label">command</div>
          <pre class="code-block"><code>{{ props.message.content }}</code></pre>
        </div>
        <div v-if="props.message.toolOutput" class="tool-output">
          <div class="code-label">output</div>
          <pre class="code-block output"><code>{{ props.message.toolOutput }}</code></pre>
        </div>
        <div v-if="props.message.error" class="tool-error">
          <div class="code-label">error</div>
          <pre class="code-block error"><code>{{ props.message.error }}</code></pre>
        </div>
      </template>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ChatMessage } from '../types/chat'

const props = defineProps<{
  message: ChatMessage
  defaultCollapsed?: boolean
}>()

const isTodoWrite = computed(() => props.message.toolName === 'TodoWrite')
const isSubagent = computed(() => props.message.toolName === 'Task')

const specialClass = computed(() => {
  if (isTodoWrite.value) return 'todo-block'
  if (isSubagent.value) return 'subagent-block'
  return ''
})

// Collapse state: completed tools default collapsed, running default expanded
const hasResult = computed(() =>
  props.message.toolOutput !== undefined || props.message.error !== undefined
)
const isRunning = computed(() => !hasResult.value)

const collapsed = ref(
  props.defaultCollapsed !== undefined ? props.defaultCollapsed : hasResult.value
)

function toggleCollapse() {
  collapsed.value = !collapsed.value
}

// Short summary for collapsed state
const commandSummary = computed(() => {
  const content = props.message.content
  if (!content) return ''
  // Truncate to ~60 chars for collapsed header
  return content.length > 60 ? content.substring(0, 60) + '...' : content
})

// Parse TodoWrite items from tool input (content field is JSON-stringified arguments)
const todoItems = computed(() => {
  if (!isTodoWrite.value) return []
  try {
    const raw = props.message.raw as Record<string, unknown> | undefined
    // Try from raw payload arguments
    const args = raw?.arguments || raw?.input
    if (args && typeof args === 'object' && 'todos' in (args as Record<string, unknown>)) {
      return (args as Record<string, unknown>).todos as { content: string; status: string; activeForm?: string }[]
    }
    // Fallback: parse from content (JSON string)
    if (props.message.content) {
      const parsed = JSON.parse(props.message.content)
      if (parsed.todos) return parsed.todos
    }
    // Try toolOutput for result stats
    return []
  } catch {
    return []
  }
})

const todoStats = computed(() => {
  const items = todoItems.value
  return {
    total: items.length,
    completed: items.filter((i: { status: string }) => i.status === 'completed').length,
    inProgress: items.filter((i: { status: string }) => i.status === 'in_progress').length,
  }
})

// Parse Task subagent metadata
const subagentDesc = computed(() => {
  if (!isSubagent.value) return ''
  try {
    const raw = props.message.raw as Record<string, unknown> | undefined
    const args = raw?.arguments || raw?.input
    if (args && typeof args === 'object') {
      return (args as Record<string, unknown>).description as string || ''
    }
    if (props.message.content) {
      const parsed = JSON.parse(props.message.content)
      return parsed.description || ''
    }
  } catch { /* ignore */ }
  return ''
})

const subagentType = computed(() => {
  try {
    const raw = props.message.raw as Record<string, unknown> | undefined
    const args = raw?.arguments || raw?.input
    if (args && typeof args === 'object') {
      return (args as Record<string, unknown>).subagent_type as string || ''
    }
  } catch { /* ignore */ }
  return ''
})

function truncate(text: string, max: number) {
  return text.length > max ? text.substring(0, max) + '...' : text
}
</script>

<style scoped>
.tool-call-block {
  margin-bottom: 12px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  overflow: hidden;
  max-width: 90%;
  flex-shrink: 0;
}

.tool-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background-color: #fafafa;
  border-bottom: 1px solid #e4e7ed;
  font-size: 13px;
}

.tool-header-clickable {
  cursor: pointer;
  user-select: none;
  transition: background-color 0.15s;
}

.tool-header-clickable:hover {
  background-color: #f0f2f5;
}

.tool-toggle {
  font-size: 10px;
  color: #909399;
  width: 12px;
  flex-shrink: 0;
}

.tool-icon { font-size: 14px; }

.tool-name {
  font-weight: 600;
  color: #303133;
  flex-shrink: 0;
}

.tool-summary {
  color: #909399;
  font-size: 12px;
  font-family: 'Consolas', 'Monaco', monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
}

.tool-thought {
  color: #909399;
  font-style: italic;
  font-size: 12px;
  margin-left: auto;
}

.tool-status-indicator {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  margin-left: auto;
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

.code-label {
  padding: 4px 12px;
  font-size: 11px;
  color: #909399;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.code-block {
  margin: 0;
  padding: 8px 12px;
  background-color: #1e1e1e;
  color: #d4d4d4;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 13px;
  line-height: 1.5;
  overflow-x: auto;
  max-height: 300px;
  overflow-y: auto;
}

.code-block.output {
  background-color: #1a2332;
  color: #a9d4a0;
}

.code-block.error {
  background-color: #2d1a1a;
  color: #f48771;
}

.tool-command,
.tool-output,
.tool-error {
  border-top: 1px solid #e4e7ed;
}

/* TodoWrite styles */
.todo-block { border-color: #b3d8ff; }
.todo-block .tool-header { background: #ecf5ff; }

.todo-stats {
  margin-left: auto;
  font-size: 12px;
  color: #67c23a;
  font-weight: 600;
}

.todo-list { padding: 8px 12px; }

.todo-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  font-size: 13px;
}

.todo-check { font-size: 14px; flex-shrink: 0; }

.todo-text { color: #303133; }

.todo-item.completed .todo-text {
  color: #909399;
  text-decoration: line-through;
}

.todo-item.in_progress .todo-text { color: #409eff; font-weight: 500; }

.todo-active {
  margin-left: auto;
  font-size: 11px;
  color: #909399;
  font-style: italic;
}

/* Subagent styles */
.subagent-block { border-color: #d9b8ff; }
.subagent-block .tool-header { background: #f5eef8; }

.subagent-header { gap: 6px; }

.subagent-desc {
  font-size: 12px;
  color: #606266;
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.subagent-type {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 3px;
  background: #e8daef;
  color: #9b59b6;
  font-family: 'Cascadia Code', 'Fira Code', monospace;
  flex-shrink: 0;
}

.code-block.subagent-prompt {
  background-color: #1a1a2e;
  max-height: 150px;
}
</style>
