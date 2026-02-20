<template>
  <div class="task-pane">
    <div class="pane-header">
      <div class="pane-label">
        <span :class="['pane-status-dot', paneState.task.value?.status?.toLowerCase()]" />
        <span v-if="modelShort" class="pane-model">{{ modelShort }}</span>
        <span class="pane-prompt" :title="paneState.task.value?.prompt">
          {{ truncate(paneState.task.value?.prompt ?? '...', 40) }}
        </span>
      </div>
      <div class="pane-actions">
        <span v-if="paneState.task.value?.costUsd" class="cost-label">
          ${{ paneState.task.value.costUsd.toFixed(4) }}
        </span>
        <span v-if="paneState.task.value?.durationMs" class="duration-label">
          {{ (paneState.task.value.durationMs / 1000).toFixed(1) }}s
        </span>
        <el-button
          v-if="paneState.task.value?.status === 'RUNNING'"
          size="small"
          type="danger"
          text
          @click="emit('abort', paneState.paneId)"
        >
          中止
        </el-button>
        <el-button
          size="small"
          text
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
        :show-input="canInput"
        :input-disabled="inputDisabled"
        placeholder="输入后续指令... (Ctrl+Enter 发送)"
        @send="handleSend"
        @permission-respond="handlePermissionRespond"
        @question-respond="handleQuestionRespond"
      >
        <template #empty>
          <div class="waiting-hint">等待 Worker 响应...</div>
        </template>
        <template #input>
          <div class="pane-input-wrap">
            <SlashCommandInput
              v-model="paneInput"
              :rows="1"
              :disabled="inputDisabled"
              placeholder="输入后续指令... (输入 / 触发命令)"
              :skills="skills || []"
              @submit="handleSend()"
              @command="handleCommand"
            />
            <el-button
              type="primary"
              :disabled="inputDisabled || !paneInput.trim()"
              @click="handleSend()"
            >
              发送
            </el-button>
          </div>
        </template>
      </ChatPanel>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ChatPanel } from '@foggy/chat'
import type { TaskPaneState } from '@/composables/useTaskPane'
import type { SkillInfo } from '@/types'
import SlashCommandInput from './SlashCommandInput.vue'

const props = defineProps<{
  paneState: TaskPaneState
  skills?: SkillInfo[]
}>()

const emit = defineEmits<{
  (e: 'close', paneId: string): void
  (e: 'abort', paneId: string): void
  (e: 'send', paneId: string, content: string): void
  (e: 'command', payload: { command: string; value: string | number }): void
  (e: 'permissionRespond', paneId: string, permissionId: string, decision: string, scope: string): void
  (e: 'questionRespond', paneId: string, permissionId: string, answers: Record<string, string>): void
}>()

const paneInput = ref('')

const modelShort = computed(() => {
  const m = props.paneState.task.value?.model
  if (!m) return ''
  const match = m.match(/(sonnet|opus|haiku)[\w-]*/i)
  return match ? match[0] : m.split('-').slice(1, 3).join('-')
})

const canInput = computed(() => {
  const t = props.paneState.task.value
  return !!t && t.status !== 'RUNNING' && t.status !== 'PENDING' && !!t.claudeSessionId
})

const inputDisabled = computed(() => {
  const t = props.paneState.task.value
  return !!t && t.status === 'RUNNING'
})

function handleSend(content?: string) {
  const text = content || paneInput.value.trim()
  if (!text) return
  // Strip leading "/" to prevent CLI from interpreting as slash command
  const safeText = text.startsWith('/') ? text.slice(1) : text
  if (!safeText.trim()) return
  emit('send', props.paneState.paneId, safeText)
  paneInput.value = ''
}

function handlePermissionRespond(permissionId: string, decision: string, scope: string) {
  emit('permissionRespond', props.paneState.paneId, permissionId, decision, scope)
}

function handleQuestionRespond(permissionId: string, answers: Record<string, string>) {
  emit('questionRespond', props.paneState.paneId, permissionId, answers)
}

function handleCommand(payload: { command: string; value: string | number }) {
  emit('command', payload)
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
  padding: 6px 12px;
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

.pane-status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.pane-status-dot.completed { background: #67c23a; }
.pane-status-dot.running { background: #409eff; animation: pulse 1.5s infinite; }
.pane-status-dot.failed { background: #f56c6c; }
.pane-status-dot.aborted { background: #e6a23c; }
.pane-status-dot.awaiting_permission { background: #e6a23c; animation: pulse 1.5s infinite; }

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

.pane-model {
  font-size: 11px;
  color: #909399;
  background: #f0f2f5;
  padding: 1px 6px;
  border-radius: 3px;
  flex-shrink: 0;
  font-family: 'Cascadia Code', 'Fira Code', monospace;
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
  gap: 4px;
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

/* Multi-round separator: dashed line before each new round's user message */
.pane-body :deep(.message-list > .message-bubble.user ~ .message-bubble.user) {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px dashed #dcdfe6;
  position: relative;
}

.waiting-hint {
  color: #909399;
  font-size: 14px;
}

.pane-input-wrap {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid #e4e7ed;
  background: #fff;
}

.pane-input-wrap .slash-input-wrap {
  flex: 1;
}
</style>
