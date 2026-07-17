<template>
  <div :class="['task-pane', { 'pane-focused': isFocused }]" @click="emit('focus')">
    <div class="pane-header">
      <div class="pane-label">
        <span v-if="paneLabel" :class="['pane-letter', `pane-letter-${paneLabel.toLowerCase()}`]">
          {{ paneLabel }}
        </span>
        <span :class="['pane-status-dot', paneState.task.value?.status?.toLowerCase()]" />
        <TaskProviderBadge
          :provider-type="paneState.task.value?.providerType"
          compact
        />
        <span v-if="modelShort" class="pane-model">{{ modelShort }}</span>
        <span class="pane-prompt" :title="paneState.task.value?.prompt">
          {{ truncate(paneState.task.value?.prompt ?? '...', 40) }}
        </span>
      </div>
      <slot name="header-extra" :pane-state="paneState" />
      <div class="pane-actions">
        <el-button
          v-if="canShowFileHints"
          size="small"
          text
          :loading="fileHintsLoading && fileHintsVisible"
          :disabled="!paneState.task.value?.taskId"
          title="查看本会话推断的改动文件"
          @click.stop="handleShowFileHints"
        >
          改动文件
        </el-button>
        <el-button
          v-if="canShowContextUsage"
          class="context-usage-button"
          size="small"
          text
          :loading="contextUsageLoading && contextUsageVisible"
          title="查看 Codex App Server 原生上下文用量并手工压缩"
          @click.stop="handleShowContextUsage"
        >
          上下文
        </el-button>
        <el-button
          size="small"
          text
          :loading="copyingConversation"
          :disabled="!paneState.task.value?.sessionId"
          title="复制整个会话"
          @click.stop="handleCopyConversation"
        >
          复制会话
        </el-button>
        <span v-if="paneState.task.value?.costUsd" class="cost-label">
          ${{ paneState.task.value.costUsd.toFixed(4) }}
        </span>
        <span v-if="paneState.task.value?.durationMs" class="duration-label">
          {{ (paneState.task.value.durationMs / 1000).toFixed(1) }}s
        </span>
        <el-button
          v-if="['RUNNING', 'AWAITING_PERMISSION', 'AWAITING_INPUT'].includes(paneState.task.value?.status ?? '')"
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
    <NativeSubtaskBar
      v-if="paneState.nativeSubtasks.value.length > 0"
      :subtasks="paneState.nativeSubtasks.value"
      :loading="paneState.nativeSubtasksLoading.value"
      :last-event-seq="paneState.nativeSubtaskLastEventSeq.value"
    />
    <div class="pane-body">
      <ChatPanel
        :messages="paneState.chatState.sortedMessages.value"
        :is-thinking="paneState.chatState.isThinking.value"
        :connection-status="paneState.chatState.connectionStatus.value"
        :show-header="false"
        :show-input="canInput"
        :input-disabled="sendDisabled"
        :rewind-enabled="rewindEnabled"
        :has-more-history="paneState.hasMoreHistory.value"
        :loading-more="paneState.loadingMore.value"
        :total-messages="paneState.totalMessages.value"
        :placeholder="inputPlaceholder"
        @send="handleSend"
        @permission-respond="handlePermissionRespond"
        @question-respond="handleQuestionRespond"
        @plan-respond="(pid: string, decision: string, denyMsg?: string, planAction?: string) => handlePlanRespond(pid, decision, denyMsg, planAction)"
        @skill-approval-respond="handleSkillApprovalRespond"
        @rewind="handleRewind"
        @reconnect="handleReconnect"
        @load-more="paneState.loadMoreHistory()"
        @load-all="(limit?: number) => paneState.loadAllHistory(limit)"
        @forward="(message) => emit('forward', props.paneState.paneId, message)"
        @view-records="handleShowMessageRecords"
        @link-click="(payload) => emit('link-click', props.paneState.paneId, payload)"
        @artifact-open="(action) => emit('artifactOpen', props.paneState.paneId, action)"
      >
        <template #empty>
          <div class="waiting-hint">
            <template v-if="paneState.task.value?.status === 'ABORTED'">
              任务已中止
            </template>
            <template v-else-if="paneState.task.value?.status === 'FAILED'">
              <ErrorBlock
                v-if="paneState.task.value.error"
                :error="paneState.task.value.errorMessage || paneState.task.value.error.errorCode || 'TASK_FAILED'"
                :error-envelope="paneState.task.value.error"
                :task-id="paneState.task.value.taskId"
              />
              <template v-else>
                任务失败{{ paneState.task.value?.errorMessage ? ': ' + paneState.task.value.errorMessage : '' }}
              </template>
            </template>
            <template v-else-if="paneState.task.value?.status === 'COMPLETED'">
              任务已完成
            </template>
            <template v-else>
              等待 Worker 响应...
            </template>
          </div>
        </template>
        <template #input>
          <div class="pane-input-wrap">
            <div class="input-with-send">
              <SlashCommandInput
                v-model="paneInput"
                :rows="1"
                auto-grow
                :max-rows="4"
                :disabled="false"
                :placeholder="inputPlaceholder"
                :skills="skills || []"
                :agents="agents || []"
                :directory-id="paneState.task.value?.directoryId"
                @submit="handleSend()"
                @command="handleCommand"
                @history-prev="handlePaneHistoryPrev"
                @history-next="handlePaneHistoryNext"
              />
              <el-button
                class="send-btn-inside"
                type="primary"
                size="small"
                :disabled="sendDisabled || !paneInput.trim()"
                @click="handleSend()"
              >
                发送
              </el-button>
            </div>
          </div>
        </template>
      </ChatPanel>
    </div>

    <el-dialog
      v-model="fileHintsVisible"
      title="会话改动文件"
      width="760px"
      append-to-body
    >
      <el-alert
        class="file-hints-alert"
        type="warning"
        :closable="false"
        show-icon
        title="文件线索基于 Codex 工具消息推断，可能不完整或不精确；cwd 外路径只展示，不能通过文件浏览器打开。"
      />

      <div v-if="fileHintsResponse" class="file-hints-meta">
        <span v-if="fileHintsResponse.codexThreadId" :title="fileHintsResponse.codexThreadId">
          Codex: {{ fileHintsResponse.codexThreadId }}
        </span>
        <span v-if="fileHintsResponse.cwd" :title="fileHintsResponse.cwd">
          cwd: {{ fileHintsResponse.cwd }}
        </span>
      </div>

      <el-table
        v-loading="fileHintsLoading"
        :data="fileHintFiles"
        max-height="420"
        size="small"
        empty-text="暂无文件线索"
      >
        <el-table-column label="文件" min-width="300">
          <template #default="{ row }">
            <div class="file-path-cell">
              <div class="file-path-main" :title="row.filePath">
                {{ row.cwdRelativePath || row.filePath }}
              </div>
              <div v-if="row.cwdRelativePath && row.filePath !== row.cwdRelativePath" class="file-path-full">
                {{ row.filePath }}
              </div>
              <div class="file-hint-tags">
                <el-tag :type="fileScopeTagType(row.pathScope)" size="small" effect="plain">
                  {{ fileScopeLabel(row.pathScope) }}
                </el-tag>
                <el-tag
                  v-for="kind in row.changeKinds"
                  :key="kind"
                  size="small"
                  effect="plain"
                >
                  {{ fileChangeKindLabel(kind) }}
                </el-tag>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="112">
          <template #default="{ row }">
            <div class="file-hint-source-tags">
              <el-tag
                v-for="source in row.sourceTools"
                :key="source"
                size="small"
                effect="plain"
              >
                {{ fileSourceLabel(source) }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="可信度" width="80">
          <template #default="{ row }">
            <el-tag :type="fileConfidenceTagType(row.confidence)" size="small" effect="plain">
              {{ fileConfidenceLabel(row.confidence) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近" width="118">
          <template #default="{ row }">
            <span class="file-hint-time">{{ formatFileHintTime(row.lastSeenAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="seenCount" label="次数" width="56" />
        <el-table-column label="操作" width="108" fixed="right">
          <template #default="{ row }">
            <div class="file-hint-actions">
              <el-button
                size="small"
                text
                type="primary"
                :disabled="!canOpenFileHint(row)"
                :title="canOpenFileHint(row) ? '在文件浏览器打开' : '只有 cwd 内文件可打开'"
                @click="handleOpenFileHint(row)"
              >
                打开
              </el-button>
              <el-button size="small" text @click="handleCopyFileHintPath(row)">
                复制
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <template #footer>
        <el-button :loading="fileHintsLoading" @click="loadFileHints">刷新</el-button>
        <el-button type="primary" @click="fileHintsVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="contextUsageVisible"
      class="context-usage-dialog"
      title="Codex 上下文用量"
      width="620px"
      append-to-body
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="数值来自 app-server 的 thread/tokenUsage/updated；窗口未上报时保持未知，不会按模型名称猜测。"
      />

      <el-alert
        v-if="contextUsageError"
        class="context-usage-alert"
        type="warning"
        :closable="false"
        show-icon
        :title="contextUsageError"
      />

      <div v-loading="contextUsageLoading" class="context-usage-grid">
        <div class="context-usage-item">
          <span>当前上下文</span>
          <strong>{{ formatContextTokens(contextUsage?.current_tokens) }}</strong>
        </div>
        <div class="context-usage-item">
          <span>模型窗口</span>
          <strong>{{ contextWindowLabel }}</strong>
        </div>
        <div class="context-usage-item">
          <span>剩余窗口</span>
          <strong>{{ remainingContextLabel }}</strong>
        </div>
        <div class="context-usage-item">
          <span>观测时间</span>
          <strong>{{ formatContextObservedAt(contextUsage?.observed_at) }}</strong>
        </div>
      </div>

      <div v-if="contextUsage?.model_context_window == null" class="context-window-unknown">
        窗口未知：当前 Runtime 没有提供 modelContextWindow，因此只展示已观测到的当前 token 数。
      </div>

      <el-alert
        v-if="contextCompactResult"
        class="context-compact-result"
        :type="contextCompactResult.status === 'completed' ? 'success' : contextCompactResult.status === 'failed' ? 'error' : 'warning'"
        :closable="false"
        show-icon
        :title="contextCompactResultLabel"
      />

      <div v-if="canCompactContext" class="context-compact-section">
        <p>
          手工压缩会对当前 Codex Thread 调用原生 <code>thread/compact/start</code>。
          仅终态任务可执行；不会自动续发消息，也不会创建新 Thread。
        </p>
        <div v-if="contextPendingOperationId" class="context-operation-id">
          待确认操作：<code>{{ contextPendingOperationId }}</code>
        </div>
      </div>

      <template #footer>
        <el-button :loading="contextUsageLoading" @click="loadContextUsage">刷新用量</el-button>
        <el-button
          v-if="canCompactContext"
          class="context-compact-button"
          type="warning"
          :loading="contextCompactLoading"
          @click="handleCompactContext"
        >
          {{ contextPendingOperationId ? '重试同一压缩操作' : '压缩当前 Thread' }}
        </el-button>
        <el-button type="primary" @click="contextUsageVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="messageRecordsVisible"
      title="会话逐条记录"
      width="900px"
      append-to-body
      class="message-records-dialog"
    >
      <div class="message-record-identifiers">
        <div class="message-record-identifier">
          <span class="message-record-identifier-label">Session ID</span>
          <code :title="sessionId || undefined">{{ sessionId || '未关联' }}</code>
          <el-button
            size="small"
            text
            :disabled="!sessionId"
            @click="handleCopyIdentifier('Session ID', sessionId)"
          >复制</el-button>
        </div>
        <div class="message-record-identifier">
          <span class="message-record-identifier-label">Codex Thread ID</span>
          <code :title="codexThreadId || undefined">{{ codexThreadId || '未关联' }}</code>
          <el-button
            size="small"
            text
            :disabled="!codexThreadId"
            @click="handleCopyIdentifier('Codex Thread ID', codexThreadId)"
          >复制</el-button>
        </div>
      </div>

      <el-alert
        v-if="messageRecordsError"
        class="message-records-alert"
        type="warning"
        :closable="false"
        show-icon
        :title="messageRecordsError"
      />

      <div v-loading="messageRecordsLoading" class="message-records-list">
        <el-empty
          v-if="!messageRecordsLoading && messageRecords.length === 0"
          description="当前会话暂无可展示的消息记录"
          :image-size="72"
        />
        <article
          v-for="(message, index) in messageRecords"
          :key="`${message.id}-${index}`"
          class="message-record-item"
        >
          <div class="message-record-item-header">
            <el-tag size="small" effect="plain">{{ messageRoleLabel(message) }}</el-tag>
            <span class="message-record-time">{{ formatMessageTime(message.timestamp) || '时间未知' }}</span>
          </div>
          <pre class="message-record-content">{{ messageRecordText(message) }}</pre>
        </article>
      </div>

      <template #footer>
        <el-button type="primary" @click="messageRecordsVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ChatPanel, ErrorBlock } from '@foggy/chat'
import type { ChatMessage, NavigatorUiAction, UserQuestionAnswers } from '@foggy/chat'
import { ElMessage } from 'element-plus'
import {
  compactCodexTaskContext,
  getCodexTaskContextUsage,
  getCodexTaskFileHints,
} from '@/api/claudeWorker'
import type { TaskPaneState } from '@/composables/useTaskPane'
import { useInputMemory } from '@/composables/useInputMemory'
import { copyToClipboard } from '@/utils/clipboard'
import type { SkillInfo } from '@/types'
import type { SessionFileHintFile, SessionFileHintsResponse } from '@/types/sessionFileHints'
import type { CodexContextCompactOperation, CodexContextUsage } from '@/types/codexContext'
import { inferTaskWorkerBackend } from '@/utils/workerBackend'
import { loadTaskFileHints } from './taskPaneFileHints'
import NativeSubtaskBar from './NativeSubtaskBar.vue'
import SlashCommandInput from './SlashCommandInput.vue'
import TaskProviderBadge from './TaskProviderBadge.vue'
import type { AgentItem } from './SlashCommandInput.vue'
import {
  canEnableRewind,
  canShowContinuationInput,
  getPendingSingleSelectQuestion,
  parseQuestionShortcut,
} from './taskPaneResume'

const props = defineProps<{
  paneState: TaskPaneState
  skills?: SkillInfo[]
  agents?: AgentItem[]
  paneLabel?: string
  isFocused?: boolean
}>()

const emit = defineEmits<{
  (e: 'close', paneId: string): void
  (e: 'abort', paneId: string): void
  (e: 'send', paneId: string, content: string): void
  (e: 'command', payload: { command: string; value: string | number }): void
  (e: 'permissionRespond', paneId: string, permissionId: string, decision: string, scope: string): void
  (e: 'questionRespond', paneId: string, permissionId: string, answers: UserQuestionAnswers): void
  (e: 'planRespond', paneId: string, permissionId: string, decision: string, denyMessage?: string, planAction?: string): void
  (e: 'skillApprovalRespond', paneId: string, taskId: string, decision: string, comment: string): void
  (e: 'rewind', paneId: string, turnIndex: number): void
  (e: 'reconnect', paneId: string, taskId: string): void
  (e: 'forward', paneId: string, message: { id: string; content: string }): void
  (e: 'link-click', paneId: string, payload: { href: string; text: string }): void
  (e: 'artifactOpen', paneId: string, action: NavigatorUiAction): void
  (e: 'focus'): void
}>()

const paneInput = ref('')
const copyingConversation = ref(false)
const fileHintsVisible = ref(false)
const fileHintsLoading = ref(false)
const fileHintsResponse = ref<SessionFileHintsResponse | null>(null)
const fileHintFiles = computed(() => fileHintsResponse.value?.files ?? [])
const canShowFileHints = computed(() => inferTaskWorkerBackend(props.paneState.task.value) === 'OPENAI_CODEX')
const contextUsageVisible = ref(false)
const contextUsageLoading = ref(false)
const contextUsageError = ref('')
const contextUsage = ref<CodexContextUsage | null>(null)
const contextCompactLoading = ref(false)
const contextCompactResult = ref<CodexContextCompactOperation | null>(null)
const contextPendingOperationId = ref('')
const canShowContextUsage = computed(() => {
  const task = props.paneState.task.value
  return task?.providerType === 'codex-app-server-worker' && !!task.taskId && !!task.codexThreadId
})
const canCompactContext = computed(() => {
  const status = props.paneState.task.value?.status
  return canShowContextUsage.value
    && (status === 'COMPLETED' || status === 'FAILED' || status === 'ABORTED')
})
const contextWindowLabel = computed(() => {
  const window = contextUsage.value?.model_context_window
  return window == null ? '窗口未知' : formatContextTokens(window)
})
const remainingContextLabel = computed(() => {
  const remaining = contextUsage.value?.remaining_tokens
  return remaining == null ? '未知' : formatContextTokens(remaining)
})
const contextCompactResultLabel = computed(() => {
  const result = contextCompactResult.value
  if (!result) return ''
  if (result.status === 'completed') return '压缩已完成，已刷新原生上下文用量。'
  if (result.status === 'failed') return `压缩失败：${result.error_code || 'CONTEXT_COMPACT_FAILED'}`
  if (result.status === 'unknown') return '压缩结果尚不确定；再次操作会复用同一 operationId 查询/重试。'
  return '压缩正在执行；再次操作会复用同一 operationId。'
})
const messageRecordsVisible = ref(false)
const messageRecordsLoading = ref(false)
const messageRecordsError = ref('')
const messageRecords = ref<ChatMessage[]>([])
const sessionId = computed(() => props.paneState.task.value?.sessionId ?? '')
const codexThreadId = computed(() => props.paneState.task.value?.codexThreadId ?? '')
let messageRecordsRequest = 0
// --- Input memory: draft persistence + history for pane input ---
const paneInputScope = computed(() => {
  const sid = props.paneState.task.value?.sessionId
  return sid ? 'pane-' + sid : ''
})
const paneMemory = useInputMemory(paneInputScope)

// Load the session draft on mount or when the pane is reused for another session.
watch(paneInputScope, () => {
  paneInput.value = paneMemory.loadDraft()
}, { immediate: true })

// Persist every edit so switching sessions or refreshing cannot lose the text
// typed while the current task is still running.
watch(paneInput, (val) => {
  paneMemory.saveDraft(val)
})

watch(() => props.paneState.task.value?.taskId, () => {
  contextUsageVisible.value = false
  contextUsage.value = null
  contextUsageError.value = ''
  contextCompactResult.value = null
  contextPendingOperationId.value = ''
})

async function handleShowContextUsage() {
  contextUsageVisible.value = true
  await loadContextUsage()
}

async function loadContextUsage() {
  const taskId = props.paneState.task.value?.taskId
  if (!taskId || contextUsageLoading.value) return
  contextUsageLoading.value = true
  contextUsageError.value = ''
  try {
    contextUsage.value = await getCodexTaskContextUsage(taskId)
  } catch (error) {
    contextUsageError.value = error instanceof Error
      ? error.message
      : 'CODEX_CONTEXT_USAGE_UNAVAILABLE'
  } finally {
    contextUsageLoading.value = false
  }
}

async function handleCompactContext() {
  const taskId = props.paneState.task.value?.taskId
  if (!taskId || !canCompactContext.value || contextCompactLoading.value) return
  const operationId = contextPendingOperationId.value || createContextCompactOperationId()
  contextPendingOperationId.value = operationId
  contextCompactLoading.value = true
  try {
    const result = await compactCodexTaskContext(taskId, operationId)
    contextCompactResult.value = result
    if (result.status === 'completed') {
      contextPendingOperationId.value = ''
      ElMessage.success('Codex Thread 上下文压缩完成')
      await loadContextUsage()
    } else if (result.status === 'failed') {
      contextPendingOperationId.value = ''
      ElMessage.error(result.error_code || 'Codex Thread 上下文压缩失败')
    } else {
      ElMessage.warning('压缩结果尚未确认，已保留 operationId 供安全重试')
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'CODEX_CONTEXT_COMPACT_UNAVAILABLE')
  } finally {
    contextCompactLoading.value = false
  }
}

function createContextCompactOperationId(): string {
  const uuid = globalThis.crypto?.randomUUID?.()
    ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`
  return `navigator-compact-${uuid}`
}

function formatContextTokens(value?: number | null): string {
  return value == null || !Number.isFinite(value) ? '未知' : Math.max(0, value).toLocaleString('zh-CN')
}

function formatContextObservedAt(value?: string | null): string {
  if (!value) return '未知'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '未知' : date.toLocaleString('zh-CN', { hour12: false })
}

function handlePaneHistoryPrev() {
  const text = paneMemory.historyPrev(paneInput.value)
  if (text != null) paneInput.value = text
}
function handlePaneHistoryNext() {
  const text = paneMemory.historyNext()
  if (text != null) paneInput.value = text
}

// Sync pendingInput from parent (e.g. after rewind fills the original prompt)
watch(() => props.paneState.pendingInput.value, (val) => {
  if (val) {
    paneInput.value = val
    props.paneState.pendingInput.value = '' // consume it
  }
})

const modelShort = computed(() => {
  const m = props.paneState.task.value?.model
  if (!m) return ''
  const match = m.match(/(sonnet|opus|haiku)[\w-]*/i)
  return match ? match[0] : m.split('-').slice(1, 3).join('-')
})

const pendingQuestionShortcut = computed(() => (
  getPendingSingleSelectQuestion(
    props.paneState.chatState.sortedMessages.value,
    props.paneState.task.value?.taskId,
    props.paneState.task.value?.status === 'AWAITING_INPUT',
  )
))

const canInput = computed(() => {
  const task = props.paneState.task.value
  if (task?.status === 'RUNNING' || task?.status === 'AWAITING_PERMISSION') {
    return !!task.sessionId
  }
  if (task?.status === 'AWAITING_INPUT') return pendingQuestionShortcut.value != null
  return canShowContinuationInput(task)
})

const inputPlaceholder = computed(() => {
  const status = props.paneState.task.value?.status
  if (status === 'RUNNING' || status === 'AWAITING_PERMISSION') {
    return '可提前输入下一条消息，当前任务结束后才能发送'
  }
  if (status === 'AWAITING_INPUT') {
    return '输入选项序号或完整选项文本... (Ctrl+Enter 发送)'
  }
  return '输入后续指令... (Ctrl+Enter 发送, / 命令, @ 提及 Agent, ./ 搜索文件)'
})

const sendDisabled = computed(() => {
  const t = props.paneState.task.value
  return !!t && (
    t.status === 'RUNNING'
      || t.status === 'AWAITING_PERMISSION'
      || (t.status === 'AWAITING_INPUT' && pendingQuestionShortcut.value == null)
  )
})

function handleSend(content?: string) {
  if (sendDisabled.value) return
  const text = content || paneInput.value.trim()
  if (!text) return

  const taskStatus = props.paneState.task.value?.status
  if (taskStatus === 'AWAITING_INPUT') {
    const response = parseQuestionShortcut(
      props.paneState.chatState.sortedMessages.value,
      text,
      props.paneState.task.value?.taskId,
      true,
    )
    if (!response) {
      ElMessage.warning('请输入有效的选项序号或完整选项文本')
      return
    }
    emit('questionRespond', props.paneState.paneId, response.permissionId, response.answers)
    paneMemory.addToHistory(text)
    paneMemory.clearDraft()
    paneInput.value = ''
    return
  }
  if (!canShowContinuationInput(props.paneState.task.value)) return

  // Strip leading "/" to prevent CLI from interpreting as slash command
  const safeText = text.startsWith('/') ? text.slice(1) : text
  if (!safeText.trim()) return
  emit('send', props.paneState.paneId, safeText)
  paneMemory.addToHistory(text)
  paneMemory.clearDraft()
  paneInput.value = ''
}

function handlePermissionRespond(permissionId: string, decision: string, scope: string) {
  emit('permissionRespond', props.paneState.paneId, permissionId, decision, scope)
}

function handleSkillApprovalRespond(taskId: string, decision: string, comment: string) {
  emit('skillApprovalRespond', props.paneState.paneId, taskId, decision, comment)
}

function handleQuestionRespond(permissionId: string, answers: UserQuestionAnswers) {
  emit('questionRespond', props.paneState.paneId, permissionId, answers)
}

function handlePlanRespond(permissionId: string, decision: string, denyMessage?: string, planAction?: string) {
  emit('planRespond', props.paneState.paneId, permissionId, decision, denyMessage, planAction)
}

function handleCommand(payload: { command: string; value: string | number }) {
  emit('command', payload)
}

function handleRewind(turnIndex: number) {
  emit('rewind', props.paneState.paneId, turnIndex)
}

function handleReconnect(taskId: string) {
  emit('reconnect', props.paneState.paneId, taskId)
}

async function handleShowFileHints() {
  fileHintsVisible.value = true
  await loadFileHints()
}

async function handleShowMessageRecords() {
  const request = ++messageRecordsRequest
  messageRecordsVisible.value = true
  messageRecordsError.value = ''
  // Show what is already on screen immediately; replace it with the complete
  // persisted history when the fetch finishes.
  messageRecords.value = [...props.paneState.chatState.sortedMessages.value]
  messageRecordsLoading.value = true

  try {
    const history = await props.paneState.getAllHistoryMessages()
    if (request !== messageRecordsRequest) return
    if (history.length > 0 || messageRecords.value.length === 0) {
      messageRecords.value = history
    }
  } catch (error) {
    if (request !== messageRecordsRequest) return
    console.error('加载会话逐条记录失败:', error)
    messageRecordsError.value = '完整会话记录加载失败，当前仅展示已加载的消息。'
  } finally {
    if (request === messageRecordsRequest) messageRecordsLoading.value = false
  }
}

async function loadFileHints() {
  const taskId = props.paneState.task.value?.taskId
  if (!taskId) {
    fileHintsResponse.value = null
    return
  }

  fileHintsLoading.value = true
  try {
    const result = await loadTaskFileHints(taskId, getCodexTaskFileHints)
    fileHintsResponse.value = result.response
    if (result.error) {
      console.error('加载 Codex 文件线索失败:', result.error)
      ElMessage.error('加载文件线索失败')
    }
  } catch (error) {
    fileHintsResponse.value = null
    console.error('加载 Codex 文件线索失败:', error)
    ElMessage.error('加载文件线索失败')
  } finally {
    fileHintsLoading.value = false
  }
}

function canOpenFileHint(file: SessionFileHintFile): boolean {
  const task = props.paneState.task.value
  return !!task?.directoryId && !!task.workerId && file.openableInFileBrowser && !!file.cwdRelativePath
}

function handleOpenFileHint(file: SessionFileHintFile) {
  const task = props.paneState.task.value
  if (!task?.directoryId || !task.workerId || !file.cwdRelativePath || !file.openableInFileBrowser) {
    ElMessage.warning('只有 cwd 内文件可在文件浏览器打开')
    return
  }

  const params = new URLSearchParams({
    directoryId: task.directoryId,
    workerId: task.workerId,
    filePath: file.cwdRelativePath.replace(/\\/g, '/'),
  })
  window.open(`${window.location.origin}/#/files?${params.toString()}`, '_blank', 'width=1400,height=900')
}

async function handleCopyFileHintPath(file: SessionFileHintFile) {
  const ok = await copyToClipboard(file.filePath)
  if (ok) {
    ElMessage.success('已复制文件路径')
  } else {
    ElMessage.error('复制失败')
  }
}

async function handleCopyIdentifier(label: string, value: string) {
  if (!value) return
  const ok = await copyToClipboard(value)
  if (ok) {
    ElMessage.success(`已复制 ${label}`)
  } else {
    ElMessage.error('复制失败')
  }
}

async function handleCopyConversation() {
  if (copyingConversation.value) return

  copyingConversation.value = true
  try {
    const messages = await props.paneState.getAllHistoryMessages()
    const text = formatConversationForCopy(messages)
    if (!text.trim()) {
      ElMessage.warning('当前会话没有可复制的消息')
      return
    }
    const ok = await copyToClipboard(text)
    if (ok) {
      ElMessage.success(`已复制整个会话（${messages.length} 条）`)
    } else {
      ElMessage.error('复制失败')
    }
  } catch (error) {
    console.error('复制会话失败:', error)
    ElMessage.error('复制会话失败')
  } finally {
    copyingConversation.value = false
  }
}

function formatConversationForCopy(messages: ChatMessage[]): string {
  return messages
    .map(formatMessageForCopy)
    .filter((part) => part.trim().length > 0)
    .join('\n\n---\n\n')
}

function formatMessageForCopy(message: ChatMessage): string {
  const header = `[${formatMessageTime(message.timestamp)}] ${messageRoleLabel(message)}`
  const lines: string[] = [header]

  if (message.toolName) lines.push(`TOOL: ${message.toolName}`)
  if (message.thought) lines.push(`THOUGHT:\n${message.thought}`)
  if (message.content) {
    lines.push(message.toolName ? `COMMAND:\n${message.content}` : message.content)
  }
  if (message.toolOutput !== undefined) lines.push(`OUTPUT:\n${message.toolOutput}`)
  if (message.error) lines.push(`ERROR:\n${message.error}`)
  if (message.plan) lines.push(`PLAN:\n${message.plan}`)
  if (message.questions?.length) {
    lines.push(`QUESTIONS:\n${message.questions.map((q, i) => `${i + 1}. ${q.question}`).join('\n')}`)
  }
  if (!message.content && !message.toolOutput && !message.error && !message.plan && !message.questions?.length && message.raw) {
    lines.push(`RAW:\n${safeStringify(message.raw)}`)
  }

  return lines.join('\n')
}

function messageRecordText(message: ChatMessage): string {
  const sections: string[] = []
  if (message.toolName) sections.push(`TOOL: ${message.toolName}`)
  if (message.thought) sections.push(`THOUGHT:\n${message.thought}`)
  if (message.content) sections.push(message.toolName ? `COMMAND:\n${message.content}` : message.content)
  if (message.toolOutput !== undefined) sections.push(`OUTPUT:\n${message.toolOutput}`)
  if (message.error) sections.push(`ERROR:\n${message.error}`)
  if (message.plan) sections.push(`PLAN:\n${message.plan}`)
  if (message.questions?.length) {
    sections.push(`QUESTIONS:\n${message.questions.map((q, i) => `${i + 1}. ${q.question}`).join('\n')}`)
  }
  if (sections.length === 0 && message.raw) sections.push(`RAW:\n${safeStringify(message.raw)}`)
  return sections.join('\n\n') || '（无可展示正文）'
}

function messageRoleLabel(message: ChatMessage): string {
  if (message.toolName || message.sender === 'tool') return '工具'
  switch (message.sender) {
    case 'user': return '用户'
    case 'assistant': return 'Agent'
    case 'system': return '系统'
    default: return message.sender
  }
}

function formatMessageTime(timestamp: number): string {
  const d = new Date(timestamp)
  if (Number.isNaN(d.getTime())) return ''
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} `
    + `${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`
}

function pad2(value: number): string {
  return String(value).padStart(2, '0')
}

function safeStringify(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

// Message-level rewind is available for providers with platform conversation rewind.
const rewindEnabled = computed(() => {
  return canEnableRewind(props.paneState.task.value)
})

function fileScopeLabel(scope: SessionFileHintFile['pathScope']): string {
  if (scope === 'inside_cwd') return 'cwd 内'
  if (scope === 'outside_cwd') return 'cwd 外'
  return '未知'
}

function fileScopeTagType(scope: SessionFileHintFile['pathScope']) {
  if (scope === 'inside_cwd') return 'success'
  if (scope === 'outside_cwd') return 'warning'
  return 'info'
}

function fileChangeKindLabel(kind: SessionFileHintFile['changeKinds'][number]): string {
  if (kind === 'add') return '新增'
  if (kind === 'delete') return '删除'
  if (kind === 'update') return '修改'
  return '未知'
}

function fileSourceLabel(source: SessionFileHintFile['sourceTools'][number]): string {
  if (source === 'file_change') return '文件工具'
  if (source === 'command_execution') return '命令'
  return source
}

function fileConfidenceLabel(confidence: SessionFileHintFile['confidence']): string {
  return confidence === 'high' ? '较高' : '较低'
}

function fileConfidenceTagType(confidence: SessionFileHintFile['confidence']) {
  return confidence === 'high' ? 'success' : 'warning'
}

function formatFileHintTime(value?: string): string {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '-'
  return `${pad2(date.getMonth() + 1)}-${pad2(date.getDate())} ${pad2(date.getHours())}:${pad2(date.getMinutes())}`
}

function truncate(text: string, maxLen: number) {
  return text.length > maxLen ? text.substring(0, maxLen) + '...' : text
}
</script>

<style scoped>
.task-pane {
  container-type: inline-size;
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
.pane-status-dot.awaiting_input { background: #e6a23c; animation: pulse 1.5s infinite; }

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

.input-with-send {
  position: relative;
  flex: 1;
}

.input-with-send .slash-input-wrap {
  width: 100%;
}

/* Ensure textarea has room for the send button */
.input-with-send :deep(.el-textarea__inner) {
  padding-right: 76px;
  padding-bottom: 36px;
}

.send-btn-inside {
  position: absolute;
  right: 10px;
  bottom: 10px;
  z-index: 1;
  border-radius: 12px !important;
  padding: 0 14px !important;
  height: 28px !important;
  font-size: 13px !important;
}

.pane-letter {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 700;
  color: #fff;
  flex-shrink: 0;
}

.pane-letter-a { background: #409eff; }
.pane-letter-b { background: #67c23a; }
.pane-letter-c { background: #e6a23c; }
.pane-letter-d { background: #a855f6; }

.pane-focused {
  box-shadow: 0 0 0 2px #409eff;
}

.file-hints-alert {
  margin-bottom: 12px;
}

.context-usage-alert,
.context-compact-result {
  margin-top: 12px;
}

.context-usage-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-top: 16px;
}

.context-usage-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
}

.context-usage-item span,
.context-operation-id {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.context-usage-item strong {
  color: var(--el-text-color-primary);
  font-size: 18px;
}

.context-window-unknown,
.context-compact-section {
  margin-top: 14px;
  color: var(--el-text-color-regular);
  line-height: 1.6;
}

.context-compact-section {
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.context-compact-section p {
  margin: 0 0 8px;
}

.file-hints-meta {
  display: flex;
  gap: 12px;
  margin-bottom: 10px;
  color: #606266;
  font-size: 12px;
  min-width: 0;
}

.file-hints-meta span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-path-cell {
  min-width: 0;
}

.file-path-main,
.file-path-full {
  font-family: 'Cascadia Code', 'Fira Code', Consolas, monospace;
  word-break: break-all;
}

.file-path-main {
  color: #303133;
  font-size: 12px;
  line-height: 1.4;
}

.file-path-full {
  margin-top: 2px;
  color: #909399;
  font-size: 11px;
}

.file-hint-tags,
.file-hint-source-tags,
.file-hint-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.file-hint-tags,
.file-hint-source-tags {
  flex-wrap: wrap;
}

.file-hint-tags {
  margin-top: 6px;
}

.file-hint-time {
  color: #606266;
  font-family: 'Cascadia Code', 'Fira Code', Consolas, monospace;
  font-size: 12px;
}

.message-record-identifiers {
  display: grid;
  gap: 8px;
  margin-bottom: 14px;
  padding: 10px 12px;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  background: #fafafa;
}

.message-record-identifier {
  display: grid;
  grid-template-columns: 116px minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.message-record-identifier-label {
  color: #606266;
  font-size: 12px;
  font-weight: 600;
}

.message-record-identifier code {
  min-width: 0;
  overflow: hidden;
  color: #303133;
  font-family: 'Cascadia Code', 'Fira Code', Consolas, monospace;
  font-size: 12px;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.message-records-alert {
  margin-bottom: 12px;
}

.message-records-list {
  min-height: 120px;
  max-height: min(60vh, 620px);
  overflow-y: auto;
  padding-right: 4px;
}

.message-record-item {
  padding: 10px 12px;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  background: #fff;
}

.message-record-item + .message-record-item {
  margin-top: 8px;
}

.message-record-item-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.message-record-time {
  color: #909399;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.message-record-content {
  margin: 0;
  color: #303133;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.65;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}

@container (max-width: 520px) {
  .message-record-identifier {
    grid-template-columns: 1fr auto;
  }

  .message-record-identifier-label {
    grid-column: 1 / -1;
  }
}

</style>
