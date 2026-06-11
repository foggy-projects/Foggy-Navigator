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
      <div class="tool-header">
        <button
          class="tool-toggle-btn"
          type="button"
          :title="collapseViewState.isCollapsed ? '展开工具调用' : '收起工具调用'"
          @click="toggleCollapse"
        >
          {{ collapseViewState.isCollapsed ? '\u25B6' : '\u25BC' }}
        </button>
        <span class="tool-icon">&#9881;</span>
        <span class="tool-name">{{ props.message.toolName || 'Tool' }}</span>
        <span v-if="props.message.content" class="tool-actions" @click.stop>
          <button class="action-btn" title="复制" @click="copyContent">
            {{ copyLabel }}
          </button>
          <button class="action-btn" title="查看" @click="showViewer = true">
            查看
          </button>
        </span>
        <span v-if="collapseViewState.isCollapsed && commandSummary" class="tool-summary">{{ commandSummary }}</span>
        <span v-if="props.message.thought" class="tool-thought">{{ props.message.thought }}</span>
        <span class="tool-status-indicator">
          <span v-if="isRunning" class="status-dot running"></span>
          <span v-else-if="props.message.error" class="status-dot failure"></span>
          <span v-else-if="props.message.toolSuccess === false" class="status-dot failure"></span>
          <span v-else-if="hasResult" class="status-dot success"></span>
        </span>
      </div>

      <!-- JSON Viewer Dialog -->
      <ElDialog v-model="showViewer" :title="`${props.message.toolName || 'Tool'} - Command`"
                width="70vw" top="10vh" :close-on-press-escape="true" :append-to-body="true"
                class="json-viewer-dialog">
        <template #header="{ titleId, titleClass }">
          <div class="json-viewer-header">
            <span :id="titleId" :class="titleClass" class="json-viewer-title">{{ props.message.toolName || 'Tool' }} - Command</span>
            <button class="action-btn viewer-copy-btn" @click="copyFormatted">{{ copyFormattedLabel }}</button>
          </div>
        </template>
        <pre class="json-viewer-content"><code>{{ formattedContent }}</code></pre>
      </ElDialog>
      <div v-if="!collapseViewState.isCollapsed" class="tool-content">
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
      </div>
    </template>
    <ExecutionReportInline
      :report-ref="props.message.executionReportRef"
      :digest="props.message.executionReportDigest"
    />
  </div>
</template>

<script lang="ts">
import type { ChatMessage } from '../types/chat'

const MAX_COLLAPSE_STATE = 500
const collapseStateByMessage = new Map<string, boolean>()

function collapseKey(message: ChatMessage): string {
  return message.id || message.toolCallId || `${message.toolName || 'tool'}:${message.timestamp}`
}

function rememberCollapseState(key: string, value: boolean): void {
  if (collapseStateByMessage.has(key)) {
    collapseStateByMessage.delete(key)
  } else if (collapseStateByMessage.size >= MAX_COLLAPSE_STATE) {
    const oldestKey = collapseStateByMessage.keys().next().value
    if (oldestKey !== undefined) collapseStateByMessage.delete(oldestKey)
  }
  collapseStateByMessage.set(key, value)
}
</script>

<script setup lang="ts">
import { computed, getCurrentInstance, reactive, ref, watch } from 'vue'
import { ElDialog } from 'element-plus'
import ExecutionReportInline from './ExecutionReportInline.vue'

const props = withDefaults(defineProps<{
  message: ChatMessage
  defaultCollapsed?: boolean
  forceCollapsed?: boolean | null
}>(), {
  defaultCollapsed: false,
  forceCollapsed: null,
})
const instance = getCurrentInstance()

const isTodoWrite = computed(() => props.message.toolName === 'TodoWrite')
const isSubagent = computed(() => props.message.toolName === 'Task')

const specialClass = computed(() => {
  if (isTodoWrite.value) return 'todo-block'
  if (isSubagent.value) return 'subagent-block'
  return ''
})

const hasResult = computed(() =>
  props.message.toolOutput !== undefined || props.message.error !== undefined
)
const isRunning = computed(() => !hasResult.value)

function defaultCollapseState(): boolean {
  return collapseStateByMessage.get(collapseKey(props.message)) ?? props.defaultCollapsed
}

const collapseViewState = reactive({
  isCollapsed: defaultCollapseState(),
})

watch([() => collapseKey(props.message), () => props.defaultCollapsed], () => {
  collapseViewState.isCollapsed = defaultCollapseState()
})

watch(() => props.forceCollapsed, (val) => {
  if (val !== null && val !== undefined) {
    setCollapsed(val)
  }
})

function setCollapsed(value: boolean) {
  collapseViewState.isCollapsed = value
  rememberCollapseState(collapseKey(props.message), value)
  // Tool blocks can be reused by the chat scroller; force this local repaint after manual collapse changes.
  instance?.proxy?.$forceUpdate()
}

function toggleCollapse() {
  setCollapsed(!collapseViewState.isCollapsed)
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

// Copy & View functionality
const showViewer = ref(false)
const copyLabel = ref('复制')
const copyFormattedLabel = ref('复制')

function formatJson(text: string): string {
  try {
    const parsed = JSON.parse(text)
    return JSON.stringify(parsed, null, 2)
  } catch {
    return text
  }
}

const formattedContent = computed(() => {
  return formatJson(props.message.content || '')
})

async function copyContent() {
  const text = props.message.content || ''

  try {
    // 优先尝试使用 Clipboard API
    await navigator.clipboard.writeText(text)
    copyLabel.value = '已复制'
    setTimeout(() => { copyLabel.value = '复制' }, 1500)
  } catch (err) {
    // 如果 Clipboard API 失败，使用传统方法作为后备
    const textArea = document.createElement('textarea')
    textArea.value = text
    textArea.style.position = 'fixed'
    textArea.style.left = '-999999px'
    textArea.style.top = '-999999px'
    document.body.appendChild(textArea)
    textArea.focus()
    textArea.select()

    try {
      const successful = document.execCommand('copy')
      if (successful) {
        copyLabel.value = '已复制'
        setTimeout(() => { copyLabel.value = '复制' }, 1500)
      } else {
        console.error('复制失败：document.execCommand 返回 false')
      }
    } catch (err) {
      console.error('复制失败：', err)
    } finally {
      document.body.removeChild(textArea)
    }
  }
}

async function copyFormatted() {
  const text = formattedContent.value

  try {
    // 优先尝试使用 Clipboard API
    await navigator.clipboard.writeText(text)
    copyFormattedLabel.value = '已复制'
    setTimeout(() => { copyFormattedLabel.value = '复制' }, 1500)
  } catch (err) {
    // 如果 Clipboard API 失败，使用传统方法作为后备
    const textArea = document.createElement('textarea')
    textArea.value = text
    textArea.style.position = 'fixed'
    textArea.style.left = '-999999px'
    textArea.style.top = '-999999px'
    document.body.appendChild(textArea)
    textArea.focus()
    textArea.select()

    try {
      const successful = document.execCommand('copy')
      if (successful) {
        copyFormattedLabel.value = '已复制'
        setTimeout(() => { copyFormattedLabel.value = '复制' }, 1500)
      } else {
        console.error('复制失败：document.execCommand 返回 false')
      }
    } catch (err) {
      console.error('复制失败：', err)
    } finally {
      document.body.removeChild(textArea)
    }
  }
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

.tool-toggle-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: 1px solid transparent;
  border-radius: 4px;
  background: transparent;
  font-size: 10px;
  color: #909399;
  flex-shrink: 0;
  line-height: 1;
  cursor: pointer;
  transition: background-color 0.15s, border-color 0.15s, color 0.15s;
}

.tool-toggle-btn:hover {
  border-color: #c6e2ff;
  background: #ecf5ff;
  color: #409eff;
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

/* Action buttons */
.tool-actions {
  display: flex;
  gap: 4px;
  margin-left: 4px;
  flex-shrink: 0;
}

.action-btn {
  padding: 1px 8px;
  font-size: 11px;
  border: 1px solid #dcdfe6;
  border-radius: 3px;
  background: #fff;
  color: #606266;
  cursor: pointer;
  line-height: 1.6;
  transition: all 0.15s;
  white-space: nowrap;
}

.action-btn:hover {
  border-color: #409eff;
  color: #409eff;
  background: #ecf5ff;
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

/* JSON Viewer Dialog */
.json-viewer-header {
  display: flex;
  align-items: center;
  gap: 12px;
}

.json-viewer-title {
  flex: 1;
}

.viewer-copy-btn {
  flex-shrink: 0;
}

.json-viewer-content {
  margin: 0;
  padding: 16px;
  background-color: #1e1e1e;
  color: #d4d4d4;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 13px;
  line-height: 1.6;
  overflow: auto;
  max-height: 60vh;
  border-radius: 4px;
  tab-size: 2;
}
</style>
