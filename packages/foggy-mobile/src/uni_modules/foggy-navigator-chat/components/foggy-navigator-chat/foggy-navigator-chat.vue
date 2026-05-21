<template>
  <view class="foggy-navigator-chat" :style="{ height }">
    <view v-if="showHeader" class="fnc-header">
      <button
        v-if="showHistory"
        class="fnc-icon-button"
        size="mini"
        plain
        @tap="openHistory"
      >
        历史
      </button>
      <view class="fnc-title-block">
        <text class="fnc-title">{{ title }}</text>
        <text v-if="subtitleText" class="fnc-subtitle">{{ subtitleText }}</text>
      </view>
      <button
        v-if="chat.isLoading.value"
        class="fnc-header-button fnc-header-button-danger"
        size="mini"
        plain
        @tap="chat.cancel"
      >
        取消
      </button>
      <text v-else-if="statusLabel" class="fnc-status" :class="statusClass">{{ statusLabel }}</text>
    </view>

    <scroll-view
      class="fnc-messages"
      scroll-y
      :scroll-into-view="scrollTarget"
      :scroll-with-animation="true"
      @scrolltoupper="$emit('loadMore')"
    >
      <view v-if="visibleMessages.length === 0 && !chat.isLoading.value" class="fnc-empty">
        <text class="fnc-empty-title">{{ emptyText }}</text>
        <text class="fnc-empty-subtitle">开始一轮新的 Agent 对话</text>
      </view>

      <view
        v-for="(message, index) in visibleMessages"
        :id="messageAnchorId(index)"
        :key="message.id"
        class="fnc-message-row"
        :class="`fnc-message-row-${message.role}`"
      >
        <view v-if="message.role === 'user'" class="fnc-bubble fnc-user-bubble">
          <text class="fnc-message-text">{{ message.content }}</text>
        </view>

        <view v-else-if="message.role === 'assistant'" class="fnc-bubble fnc-assistant-bubble">
          <rich-text v-if="message.content" class="fnc-rich-text" :nodes="renderMarkdown(message.content)" />
          <view v-if="message.actions?.length" class="fnc-action-list">
            <button
              v-for="action in message.actions"
              :key="action.id"
              class="fnc-action-button"
              size="mini"
              plain
              @tap="emitAction(action)"
            >
              {{ action.label }}
            </button>
          </view>
          <view
            v-if="hasReport(message)"
            class="fnc-report-card"
            @tap="openReport(message.executionReportRef, message.executionReportDigest, '执行报告')"
          >
            <text class="fnc-report-title">执行报告</text>
            <text v-if="reportSummary(message.executionReportDigest)" class="fnc-report-summary">
              {{ reportSummary(message.executionReportDigest) }}
            </text>
          </view>
        </view>

        <view v-else-if="message.toolExecution" class="fnc-tool-card">
          <view class="fnc-tool-header" @tap="toggleExpanded(toolKey(message.toolExecution))">
            <view class="fnc-tool-main">
              <text class="fnc-tool-dot" :class="`fnc-tool-dot-${message.toolExecution.status}`" />
              <text class="fnc-tool-name">{{ message.toolExecution.displayName }}</text>
            </view>
            <text class="fnc-tool-status">{{ toolStatusLabel(message.toolExecution.status) }}</text>
          </view>
          <text v-if="toolSummary(message.toolExecution)" class="fnc-tool-summary">
            {{ toolSummary(message.toolExecution) }}
          </text>
          <view
            v-if="hasReport(message.toolExecution)"
            class="fnc-report-card fnc-report-card-compact"
            @tap="openReport(message.toolExecution.executionReportRef, message.toolExecution.executionReportDigest, '工具执行报告')"
          >
            <text class="fnc-report-title">执行报告</text>
            <text v-if="reportSummary(message.toolExecution.executionReportDigest)" class="fnc-report-summary">
              {{ reportSummary(message.toolExecution.executionReportDigest) }}
            </text>
          </view>
          <view v-if="expandedItems[toolKey(message.toolExecution)]" class="fnc-tool-detail">
            <view v-if="message.toolExecution.functionId" class="fnc-detail-row">
              <text class="fnc-detail-label">业务能力</text>
              <text class="fnc-detail-value">{{ message.toolExecution.functionId }}</text>
            </view>
            <view v-if="message.toolExecution.durationMs != null" class="fnc-detail-row">
              <text class="fnc-detail-label">耗时</text>
              <text class="fnc-detail-value">{{ (message.toolExecution.durationMs / 1000).toFixed(1) }}s</text>
            </view>
            <scroll-view v-if="showDiagnosticDetails" scroll-x class="fnc-code-box">
              <text class="fnc-code-text">{{ formatJson({
                args: message.toolExecution.args,
                result: message.toolExecution.result,
                error: message.toolExecution.error,
              }) }}</text>
            </scroll-view>
          </view>
        </view>

        <view v-else-if="message.error" class="fnc-error-card">
          <text class="fnc-error-text">{{ message.content || message.error }}</text>
        </view>

        <view v-else class="fnc-state-line">
          <text>{{ message.content }}</text>
        </view>
      </view>

      <view v-if="chat.isLoading.value" :id="thinkingAnchorId" class="fnc-message-row fnc-message-row-assistant">
        <view class="fnc-bubble fnc-assistant-bubble fnc-thinking">
          <text class="fnc-thinking-dot">...</text>
          <text>{{ chat.progressText.value || thinkingText }}</text>
        </view>
      </view>
      <view id="fnc-scroll-bottom" class="fnc-scroll-bottom" />
    </scroll-view>

    <view v-if="showInput" class="fnc-input-shell">
      <view v-if="chat.error.value" class="fnc-input-error">
        <text>{{ chat.error.value }}</text>
      </view>
      <view class="fnc-input-row">
        <textarea
          :value="inputText"
          class="fnc-textarea"
          :placeholder="placeholder"
          :auto-height="true"
          :maxlength="-1"
          :cursor-spacing="18"
          :adjust-position="true"
          confirm-type="send"
          :disabled="chat.isLoading.value"
          @input="handleInput"
          @confirm="handleSend"
        />
        <button
          class="fnc-send-button"
          :class="{ 'fnc-send-button-active': canSend }"
          size="mini"
          :disabled="!canSend"
          @tap="handleSend"
        >
          {{ sendButtonText }}
        </button>
      </view>
    </view>

    <view v-if="historyVisible" class="fnc-overlay" @tap.self="historyVisible = false">
      <view class="fnc-sheet fnc-history-sheet">
        <view class="fnc-sheet-header">
          <view class="fnc-sheet-title-block">
            <text class="fnc-sheet-title">{{ historyTitle }}</text>
            <text class="fnc-sheet-subtitle">{{ historySubtitle }}</text>
          </view>
          <button class="fnc-icon-button" size="mini" plain @tap="historyVisible = false">关闭</button>
        </view>
        <view v-if="showHistorySearch" class="fnc-search-wrap">
          <input
            :value="historyKeyword"
            class="fnc-search"
            type="text"
            :placeholder="historySearchPlaceholder"
            @input="handleHistoryKeywordInput"
          />
        </view>
        <view class="fnc-sheet-actions">
          <button v-if="showHistoryNewSession" size="mini" plain @tap="handleNewSession">新会话</button>
          <button size="mini" plain :disabled="historyLoading" @tap="loadHistorySessions">
            {{ historyLoading ? '刷新中' : '刷新' }}
          </button>
        </view>
        <view v-if="historyError" class="fnc-history-error">
          <text>{{ historyError }}</text>
          <button size="mini" plain @tap="loadHistorySessions">重试</button>
        </view>
        <view v-else-if="historyLoading && historySessions.length === 0" class="fnc-history-empty">
          <text>加载中...</text>
        </view>
        <view v-else-if="filteredHistorySessions.length === 0" class="fnc-history-empty">
          <text>{{ historyKeyword ? historyNoResultText : historyEmptyText }}</text>
        </view>
        <scroll-view v-else scroll-y class="fnc-history-list">
          <view
            v-for="session in filteredHistorySessions"
            :key="session.contextId"
            class="fnc-history-item"
            :class="{ 'fnc-history-item-active': session.contextId === chat.contextId.value }"
          >
            <view class="fnc-history-content" @tap="handleHistorySessionClick(session.contextId)">
              <view class="fnc-history-main">
                <text class="fnc-history-name">{{ sessionDisplayTitle(session) }}</text>
                <text class="fnc-history-status">{{ sessionStatusLabel(session.status) }}</text>
              </view>
              <text v-if="sessionDisplayPreview(session)" class="fnc-history-preview">
                {{ sessionDisplayPreview(session) }}
              </text>
              <view class="fnc-history-meta">
                <text>{{ formatSessionTime(session.updatedAt || session.createdAt) }}</text>
                <text v-if="session.turnCount != null">{{ session.turnCount }}轮</text>
              </view>
            </view>
            <button
              v-if="showHistoryDelete"
              class="fnc-history-delete"
              size="mini"
              plain
              @tap.stop="handleHistorySessionDelete(session.contextId)"
            >
              删除
            </button>
          </view>
        </scroll-view>
      </view>
    </view>

    <view
      v-if="showSuspensionSheet && suspension && suspensionDialogVisible"
      class="fnc-overlay"
      @tap.self="updateSuspensionDialogVisible(false)"
    >
      <view class="fnc-sheet">
        <view class="fnc-sheet-grip" />
        <view class="fnc-sheet-header">
          <view class="fnc-sheet-title-block">
            <text class="fnc-sheet-kicker">{{ suspension.suspensionType }}</text>
            <text class="fnc-sheet-title">{{ suspensionTitle }}</text>
          </view>
          <button class="fnc-icon-button" size="mini" plain @tap="updateSuspensionDialogVisible(false)">关闭</button>
        </view>
        <scroll-view scroll-y class="fnc-sheet-body">
          <text v-if="suspension.summary" class="fnc-suspension-summary">{{ suspension.summary }}</text>
          <view class="fnc-detail-list">
            <view v-if="suspension.functionId" class="fnc-detail-row">
              <text class="fnc-detail-label">业务能力</text>
              <text class="fnc-detail-value">{{ suspension.functionDisplayName || suspension.functionId }}</text>
            </view>
            <view v-if="suspension.version" class="fnc-detail-row">
              <text class="fnc-detail-label">版本</text>
              <text class="fnc-detail-value">{{ suspension.version }}</text>
            </view>
            <view v-if="suspension.riskLevel" class="fnc-detail-row">
              <text class="fnc-detail-label">风险级别</text>
              <text class="fnc-detail-value">{{ suspension.riskLevel }}</text>
            </view>
            <view v-if="suspension.expiresAt" class="fnc-detail-row">
              <text class="fnc-detail-label">过期时间</text>
              <text class="fnc-detail-value">{{ suspension.expiresAt }}</text>
            </view>
            <view v-for="field in suspension.displayFields || []" :key="field.label" class="fnc-detail-row">
              <text class="fnc-detail-label">{{ field.label }}</text>
              <text class="fnc-detail-value">{{ fieldValue(field.value) }}</text>
            </view>
          </view>
          <textarea
            :value="suspensionComment"
            class="fnc-comment"
            :placeholder="suspension.commentPlaceholder || '补充说明（可选）'"
            :auto-height="true"
            :maxlength="-1"
            :disabled="suspensionSubmitting"
            @input="handleSuspensionCommentInput"
          />
        </scroll-view>
        <view class="fnc-sheet-actions fnc-sheet-actions-bottom">
          <button
            class="fnc-secondary-danger"
            :disabled="suspensionSubmitting"
            @tap="submitSuspensionDecision('rejected')"
          >
            {{ suspension.rejectLabel || '拒绝' }}
          </button>
          <button
            class="fnc-primary-button"
            :disabled="suspensionSubmitting"
            @tap="submitSuspensionDecision('approved')"
          >
            {{ suspensionSubmitting ? '提交中' : (suspension.approveLabel || '批准') }}
          </button>
        </view>
      </view>
    </view>

    <view v-if="activeReport" class="fnc-overlay" @tap.self="closeReport">
      <view class="fnc-sheet fnc-report-sheet">
        <view class="fnc-sheet-grip" />
        <view class="fnc-sheet-header">
          <view class="fnc-sheet-title-block">
            <text class="fnc-sheet-kicker">REPORT</text>
            <text class="fnc-sheet-title">{{ activeReport.title }}</text>
          </view>
          <button class="fnc-icon-button" size="mini" plain @tap="closeReport">关闭</button>
        </view>
        <scroll-view scroll-y class="fnc-sheet-body">
          <view class="fnc-detail-list">
            <view v-if="reportSummary(activeReport.digest)" class="fnc-detail-row">
              <text class="fnc-detail-label">摘要</text>
              <text class="fnc-detail-value">{{ reportSummary(activeReport.digest) }}</text>
            </view>
            <view v-if="activeReport.digest?.status" class="fnc-detail-row">
              <text class="fnc-detail-label">状态</text>
              <text class="fnc-detail-value">{{ activeReport.digest.status }}</text>
            </view>
            <view v-if="activeReport.digest?.error" class="fnc-detail-row">
              <text class="fnc-detail-label">错误</text>
              <text class="fnc-detail-value fnc-detail-error">{{ activeReport.digest.error }}</text>
            </view>
            <view v-if="activeReport.reportRef" class="fnc-detail-row">
              <text class="fnc-detail-label">report_ref</text>
              <text class="fnc-detail-value">{{ activeReport.reportRef }}</text>
            </view>
          </view>
          <view v-if="canLoadReportMarkdown" class="fnc-report-loader">
            <button size="mini" plain :disabled="reportMarkdownLoading" @tap="loadReportMarkdown">
              {{ reportMarkdownLoading ? '加载中' : (reportMarkdownText ? '刷新完整 Markdown' : '查看完整 Markdown') }}
            </button>
            <text v-if="reportMarkdownError" class="fnc-report-error">{{ reportMarkdownError }}</text>
            <rich-text v-if="reportMarkdownText" class="fnc-report-markdown" :nodes="renderMarkdown(reportMarkdownText)" />
          </view>
        </scroll-view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { renderBasicMarkdown } from '../../lib/normalizer'
import { useFoggyNavigatorChat } from '../../lib/useFoggyNavigatorChat'
import type {
  BusinessSuspensionDecision,
  BusinessSuspensionDecisionPayload,
  BusinessSuspensionDialogModel,
  ExecutionReportDigest,
  ExecutionReportMarkdownPayload,
  FoggyNavigatorAction,
  FoggyNavigatorChatClient,
  FoggyNavigatorChatConfig,
  FoggyNavigatorChatMessage,
  FoggyNavigatorSessionSummary,
  ToolExecutionBlock,
} from '../../lib/types'

const props = withDefaults(defineProps<{
  config: FoggyNavigatorChatConfig
  client?: FoggyNavigatorChatClient
  title?: string
  subtitle?: string
  height?: string
  placeholder?: string
  emptyText?: string
  thinkingText?: string
  sendButtonText?: string
  showHeader?: boolean
  showInput?: boolean
  showHistory?: boolean
  showHistorySearch?: boolean
  showHistoryNewSession?: boolean
  showHistoryDelete?: boolean
  showToolCalls?: boolean
  showToolResults?: boolean
  showRuntimeEvents?: boolean
  showSuspensionSheet?: boolean
  suspension?: BusinessSuspensionDialogModel | null
  suspensionDialogVisible?: boolean
  suspensionSubmitting?: boolean
}>(), {
  title: '业务助手',
  subtitle: '',
  height: '100vh',
  placeholder: '输入消息...',
  emptyText: '暂无消息',
  thinkingText: '正在处理...',
  sendButtonText: '发送',
  showHeader: true,
  showInput: true,
  showHistory: false,
  showHistorySearch: true,
  showHistoryNewSession: true,
  showHistoryDelete: false,
  showToolCalls: undefined,
  showToolResults: undefined,
  showRuntimeEvents: undefined,
  showSuspensionSheet: true,
  suspension: null,
  suspensionDialogVisible: false,
  suspensionSubmitting: false,
})

const emit = defineEmits<{
  (e: 'action', action: FoggyNavigatorAction): void
  (e: 'suspensionDecision', payload: BusinessSuspensionDecisionPayload): void
  (e: 'update:suspensionDialogVisible', value: boolean): void
  (e: 'statusChange', status: string | null): void
  (e: 'reply', content: string, taskId?: string): void
  (e: 'loadMore'): void
}>()

const runtimeConfig: FoggyNavigatorChatConfig = {
  ...props.config,
  showToolCalls: props.showToolCalls ?? props.config.showToolCalls,
  showToolResults: props.showToolResults ?? props.config.showToolResults,
  showRuntimeEvents: props.showRuntimeEvents ?? props.config.showRuntimeEvents,
}

const chat = useFoggyNavigatorChat(runtimeConfig, { client: props.client })
const inputText = ref('')
const scrollTarget = ref('fnc-scroll-bottom')
const historyVisible = ref(false)
const historySessions = ref<FoggyNavigatorSessionSummary[]>([])
const historyLoading = ref(false)
const historyError = ref('')
const historyKeyword = ref('')
const expandedItems = ref<Record<string, boolean>>({})
const suspensionComment = ref('')
const activeReport = ref<{
  reportRef?: string
  digest?: ExecutionReportDigest
  title: string
} | null>(null)
const reportMarkdownText = ref('')
const reportMarkdownLoading = ref(false)
const reportMarkdownError = ref('')

const thinkingAnchorId = 'fnc-thinking'
const historyTitle = '历史会话'
const historySubtitle = '选择一个上下文继续对话'
const historySearchPlaceholder = '搜索标题或摘要'
const historyEmptyText = '暂无历史会话'
const historyNoResultText = '没有匹配的会话'

const visibleMessages = computed(() => chat.messages.value)
const subtitleText = computed(() => props.subtitle || chat.progressText.value)
const canSend = computed(() => inputText.value.trim().length > 0 && !chat.isLoading.value)
const showDiagnosticDetails = computed(() => runtimeConfig.mode === 'debug' || runtimeConfig.mode === 'details')
const suspensionTitle = computed(() => props.suspension?.title || '需要确认')
const canLoadReportMarkdown = computed(() =>
  !!activeReport.value?.reportRef
  && !!(runtimeConfig.executionReportMarkdownLoader || props.client?.loadExecutionReportMarkdown),
)

const statusLabel = computed(() => {
  if (!chat.taskStatus.value) return ''
  switch (chat.taskStatus.value) {
    case 'SUBMITTED': return '已提交'
    case 'WORKING': return '处理中'
    case 'COMPLETED': return '已完成'
    case 'FAILED': return '失败'
    case 'CANCELED': return '已取消'
    case 'INPUT_REQUIRED': return '待确认'
    default: return String(chat.taskStatus.value)
  }
})

const statusClass = computed(() => {
  const status = chat.taskStatus.value
  if (status === 'COMPLETED') return 'fnc-status-success'
  if (status === 'FAILED') return 'fnc-status-failed'
  if (status === 'INPUT_REQUIRED') return 'fnc-status-input'
  if (status === 'WORKING' || status === 'SUBMITTED') return 'fnc-status-working'
  return ''
})

const filteredHistorySessions = computed(() => {
  const keyword = historyKeyword.value.trim().toLowerCase()
  if (!keyword) return historySessions.value
  return historySessions.value.filter((session) =>
    [
      session.title,
      session.preview,
      session.contextId,
      session.status,
    ].some((value) => String(value ?? '').toLowerCase().includes(keyword)),
  )
})

function handleInput(event: { detail?: { value?: string } }) {
  inputText.value = event.detail?.value ?? ''
}

async function handleSend() {
  if (!canSend.value) return
  const content = inputText.value.trim()
  inputText.value = ''
  await chat.send(content)
}

function emitAction(action: FoggyNavigatorAction) {
  emit('action', action)
}

function openHistory() {
  historyVisible.value = true
  if (historySessions.value.length === 0) {
    void loadHistorySessions()
  }
}

async function loadHistorySessions() {
  historyLoading.value = true
  historyError.value = ''
  try {
    const page = await chat.listSessions({ limit: 30 })
    historySessions.value = page.sessions ?? []
  } catch (cause) {
    const message = cause instanceof Error ? cause.message : String(cause)
    historyError.value = message || '历史会话加载失败'
  } finally {
    historyLoading.value = false
  }
}

async function handleHistorySessionClick(contextId: string) {
  historyError.value = ''
  try {
    await chat.loadSession(contextId, { limit: 50 })
    historyVisible.value = false
  } catch (cause) {
    const message = cause instanceof Error ? cause.message : String(cause)
    historyError.value = message || '会话加载失败'
  }
}

async function handleHistorySessionDelete(contextId: string) {
  historyError.value = ''
  try {
    await chat.deleteSession(contextId)
    historySessions.value = historySessions.value.filter((session) => session.contextId !== contextId)
  } catch (cause) {
    const message = cause instanceof Error ? cause.message : String(cause)
    historyError.value = message || '删除失败'
  }
}

function handleNewSession() {
  chat.clear()
  historyVisible.value = false
}

function handleHistoryKeywordInput(event: { detail?: { value?: string } }) {
  historyKeyword.value = event.detail?.value ?? ''
}

function updateSuspensionDialogVisible(value: boolean) {
  emit('update:suspensionDialogVisible', value)
  if (!value) suspensionComment.value = ''
}

function handleSuspensionCommentInput(event: { detail?: { value?: string } }) {
  suspensionComment.value = event.detail?.value ?? ''
}

function submitSuspensionDecision(decision: BusinessSuspensionDecision) {
  if (!props.suspension) return
  emit('suspensionDecision', {
    suspendId: props.suspension.suspendId,
    suspensionType: props.suspension.suspensionType,
    decision,
    comment: suspensionComment.value.trim() || undefined,
  })
}

function openReport(reportRef: string | undefined, digest: ExecutionReportDigest | undefined, title: string) {
  activeReport.value = { reportRef, digest, title }
  reportMarkdownText.value = ''
  reportMarkdownError.value = ''
}

function closeReport() {
  activeReport.value = null
  reportMarkdownText.value = ''
  reportMarkdownError.value = ''
}

async function loadReportMarkdown() {
  const reportRef = activeReport.value?.reportRef
  const loader = runtimeConfig.executionReportMarkdownLoader || props.client?.loadExecutionReportMarkdown
  if (!reportRef || !loader) return
  reportMarkdownLoading.value = true
  reportMarkdownError.value = ''
  try {
    const result = await loader(reportRef)
    if (typeof result === 'string') {
      reportMarkdownText.value = result
    } else {
      reportMarkdownText.value = result.markdown
      if (result.title && activeReport.value) activeReport.value.title = result.title
      if (result.digest && activeReport.value) activeReport.value.digest = result.digest
    }
  } catch (cause) {
    const message = cause instanceof Error ? cause.message : String(cause)
    reportMarkdownError.value = message || '执行报告读取失败'
  } finally {
    reportMarkdownLoading.value = false
  }
}

function toggleExpanded(key: string) {
  expandedItems.value = {
    ...expandedItems.value,
    [key]: !expandedItems.value[key],
  }
}

function hasReport(value: FoggyNavigatorChatMessage | ToolExecutionBlock): boolean {
  return !!(value.executionReportRef || value.executionReportDigest?.reportRef || value.executionReportDigest?.summary)
}

function reportSummary(digest?: ExecutionReportDigest): string {
  return String(digest?.summary || digest?.error || '')
}

function toolKey(block: ToolExecutionBlock): string {
  return block.toolCallId || block.displayName
}

function toolStatusLabel(status: ToolExecutionBlock['status']): string {
  switch (status) {
    case 'running': return '执行中'
    case 'success': return '成功'
    case 'failed': return '失败'
    case 'skipped': return '已跳过'
    case 'cancelled': return '已取消'
  }
}

function toolSummary(block: ToolExecutionBlock): string {
  return block.summary.join(' / ')
}

function sessionDisplayTitle(session: FoggyNavigatorSessionSummary): string {
  return session.title || session.preview || session.contextId
}

function sessionDisplayPreview(session: FoggyNavigatorSessionSummary): string {
  return String(session.preview || '')
}

function sessionStatusLabel(status?: string): string {
  if (!status) return '会话'
  if (status === 'COMPLETED') return '完成'
  if (status === 'FAILED') return '失败'
  if (status === 'INPUT_REQUIRED') return '待确认'
  if (status === 'WORKING' || status === 'SUBMITTED') return '处理中'
  return status
}

function formatSessionTime(value?: string): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  const hour = String(date.getHours()).padStart(2, '0')
  const minute = String(date.getMinutes()).padStart(2, '0')
  return `${month}-${day} ${hour}:${minute}`
}

function fieldValue(value: unknown): string {
  if (value == null || value === '') return '-'
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return formatJson(value)
}

function formatJson(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

function renderMarkdown(content: string): string {
  return renderBasicMarkdown(content)
}

function messageAnchorId(index: number): string {
  return `fnc-msg-${index}`
}

watch(
  () => visibleMessages.value.length,
  () => {
    nextTick(() => {
      scrollTarget.value = ''
      nextTick(() => {
        scrollTarget.value = chat.isLoading.value ? thinkingAnchorId : 'fnc-scroll-bottom'
      })
    })
  },
)

watch(
  () => chat.isLoading.value,
  (loading) => {
    if (loading) {
      nextTick(() => {
        scrollTarget.value = thinkingAnchorId
      })
    }
  },
)

watch(
  () => chat.taskStatus.value,
  (status) => {
    emit('statusChange', status)
    if (props.showHistory && ['COMPLETED', 'FAILED', 'CANCELED', 'INPUT_REQUIRED'].includes(String(status ?? ''))) {
      void loadHistorySessions()
    }
  },
)

watch(
  () => visibleMessages.value,
  (messages) => {
    const last = messages[messages.length - 1]
    if (last?.role === 'assistant' && last.taskId && (last.terminal || last.messageType === 'RESULT')) {
      emit('reply', last.content, last.taskId)
    }
  },
)

watch(
  () => props.suspensionDialogVisible,
  (visible) => {
    if (!visible) suspensionComment.value = ''
  },
)

onMounted(() => {
  if (props.showHistory) {
    void loadHistorySessions()
  }
})

defineExpose({
  send: chat.send,
  cancel: chat.cancel,
  clear: chat.clear,
  openHistory,
  loadHistorySessions,
})
</script>

<style scoped>
.foggy-navigator-chat {
  width: 100%;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  color: #172033;
  background: #f6f7f9;
  box-sizing: border-box;
}

.fnc-header {
  min-height: 96rpx;
  padding: calc(12rpx + env(safe-area-inset-top)) 20rpx 12rpx;
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 16rpx;
  border-bottom: 2rpx solid #d8dee8;
  background: #ffffff;
  box-sizing: border-box;
}

.fnc-title-block,
.fnc-sheet-title-block {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
}

.fnc-title {
  color: #172033;
  font-size: 32rpx;
  font-weight: 700;
  line-height: 1.25;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fnc-subtitle {
  margin-top: 4rpx;
  color: #657084;
  font-size: 24rpx;
  line-height: 1.25;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fnc-icon-button,
.fnc-header-button {
  flex-shrink: 0;
}

.fnc-header-button-danger {
  color: #c93637;
  border-color: #f1bfc0;
}

.fnc-status {
  flex-shrink: 0;
  padding: 8rpx 14rpx;
  border-radius: 8rpx;
  color: #657084;
  background: #eef2f6;
  font-size: 24rpx;
  line-height: 1;
}

.fnc-status-working {
  color: #1769e0;
  background: #e8f1ff;
}

.fnc-status-success {
  color: #168a55;
  background: #e8f6ef;
}

.fnc-status-failed {
  color: #c93637;
  background: #fff0f0;
}

.fnc-status-input {
  color: #9a650d;
  background: #fff7e6;
}

.fnc-messages {
  flex: 1;
  min-height: 0;
  padding: 20rpx 20rpx 24rpx;
  box-sizing: border-box;
}

.fnc-scroll-bottom {
  height: 4rpx;
}

.fnc-empty {
  min-height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8rpx;
  text-align: center;
}

.fnc-empty-title {
  color: #172033;
  font-size: 30rpx;
  font-weight: 700;
}

.fnc-empty-subtitle {
  color: #657084;
  font-size: 26rpx;
}

.fnc-message-row {
  display: flex;
  flex-direction: row;
  margin: 16rpx 0;
  max-width: 100%;
  box-sizing: border-box;
}

.fnc-message-row-user {
  justify-content: flex-end;
}

.fnc-message-row-assistant,
.fnc-message-row-system {
  justify-content: flex-start;
}

.fnc-bubble {
  max-width: 86%;
  min-width: 0;
  padding: 20rpx 24rpx;
  border-radius: 16rpx;
  box-sizing: border-box;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.fnc-user-bubble {
  color: #ffffff;
  background: #1769e0;
  border-bottom-right-radius: 6rpx;
}

.fnc-assistant-bubble {
  color: #172033;
  background: #ffffff;
  border: 2rpx solid #d8dee8;
  border-bottom-left-radius: 6rpx;
}

.fnc-message-text,
.fnc-rich-text {
  font-size: 28rpx;
  line-height: 1.62;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.fnc-action-list {
  margin-top: 16rpx;
  display: flex;
  flex-direction: row;
  flex-wrap: wrap;
  gap: 12rpx;
}

.fnc-action-button {
  color: #1769e0;
  border-color: #bcd5fa;
  background: #e8f1ff;
}

.fnc-report-card {
  width: 100%;
  margin-top: 16rpx;
  padding: 16rpx 18rpx;
  border: 2rpx solid #d8dee8;
  border-radius: 12rpx;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
  background: #f8fafc;
  box-sizing: border-box;
}

.fnc-report-card-compact {
  margin-top: 12rpx;
}

.fnc-report-title {
  color: #172033;
  font-size: 26rpx;
  font-weight: 700;
}

.fnc-report-summary {
  color: #657084;
  font-size: 24rpx;
  line-height: 1.35;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fnc-tool-card,
.fnc-error-card,
.fnc-state-line {
  width: 92%;
  min-width: 0;
  border: 2rpx solid #d8dee8;
  border-radius: 14rpx;
  background: #ffffff;
  box-sizing: border-box;
}

.fnc-tool-card {
  padding: 18rpx;
}

.fnc-tool-header {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  gap: 12rpx;
}

.fnc-tool-main {
  min-width: 0;
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 12rpx;
  flex: 1;
}

.fnc-tool-dot {
  width: 14rpx;
  height: 14rpx;
  border-radius: 50%;
  background: #657084;
  flex-shrink: 0;
}

.fnc-tool-dot-running {
  background: #1769e0;
}

.fnc-tool-dot-success {
  background: #168a55;
}

.fnc-tool-dot-failed {
  background: #c93637;
}

.fnc-tool-name {
  min-width: 0;
  color: #172033;
  font-size: 26rpx;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fnc-tool-status {
  color: #657084;
  font-size: 24rpx;
  flex-shrink: 0;
}

.fnc-tool-summary {
  margin-top: 12rpx;
  color: #657084;
  font-size: 24rpx;
  line-height: 1.45;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.fnc-tool-detail {
  margin-top: 14rpx;
}

.fnc-detail-list {
  padding: 20rpx;
}

.fnc-detail-row {
  display: flex;
  flex-direction: row;
  gap: 16rpx;
  padding: 8rpx 0;
}

.fnc-detail-label {
  width: 150rpx;
  flex-shrink: 0;
  color: #657084;
  font-size: 24rpx;
}

.fnc-detail-value {
  flex: 1;
  min-width: 0;
  color: #172033;
  font-size: 24rpx;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.fnc-detail-error {
  color: #c93637;
}

.fnc-code-box {
  margin-top: 12rpx;
  padding: 16rpx;
  border-radius: 10rpx;
  background: #111827;
  box-sizing: border-box;
}

.fnc-code-text {
  color: #e5e7eb;
  font-size: 22rpx;
  line-height: 1.55;
  white-space: pre-wrap;
}

.fnc-error-card {
  padding: 18rpx 22rpx;
  border-color: #f1bfc0;
  background: #fff4f4;
}

.fnc-error-text {
  color: #c93637;
  font-size: 26rpx;
  line-height: 1.5;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.fnc-state-line {
  padding: 12rpx 18rpx;
  color: #657084;
  background: rgba(255, 255, 255, 0.78);
  font-size: 24rpx;
  text-align: center;
}

.fnc-thinking {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 12rpx;
  color: #657084;
}

.fnc-thinking-dot {
  color: #1769e0;
  font-weight: 700;
}

.fnc-input-shell {
  flex-shrink: 0;
  padding: 16rpx 20rpx calc(16rpx + env(safe-area-inset-bottom));
  border-top: 2rpx solid #d8dee8;
  background: #ffffff;
  box-sizing: border-box;
}

.fnc-input-error {
  margin-bottom: 10rpx;
  color: #c93637;
  font-size: 24rpx;
}

.fnc-input-row {
  display: flex;
  flex-direction: row;
  align-items: flex-end;
  gap: 14rpx;
}

.fnc-textarea {
  flex: 1;
  min-height: 72rpx;
  max-height: 220rpx;
  padding: 18rpx 22rpx;
  border-radius: 16rpx;
  color: #172033;
  background: #f6f7f9;
  font-size: 28rpx;
  line-height: 1.45;
  box-sizing: border-box;
}

.fnc-send-button {
  min-width: 104rpx;
  height: 72rpx;
  color: #ffffff;
  background: #a9b3c2;
  border-radius: 16rpx;
  flex-shrink: 0;
}

.fnc-send-button-active {
  background: #1769e0;
}

.fnc-overlay {
  position: fixed;
  left: 0;
  right: 0;
  top: 0;
  bottom: 0;
  z-index: 3000;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  background: rgba(17, 24, 39, 0.48);
}

.fnc-sheet {
  width: 100%;
  max-height: 88vh;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-radius: 18rpx 18rpx 0 0;
  background: #ffffff;
  padding-bottom: env(safe-area-inset-bottom);
  box-sizing: border-box;
}

.fnc-history-sheet {
  height: 86vh;
}

.fnc-sheet-grip {
  width: 72rpx;
  height: 8rpx;
  margin: 16rpx auto 4rpx;
  border-radius: 4rpx;
  background: #c9d1dd;
  flex-shrink: 0;
}

.fnc-sheet-header {
  flex-shrink: 0;
  display: flex;
  flex-direction: row;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20rpx;
  padding: 22rpx 24rpx 18rpx;
  border-bottom: 2rpx solid #d8dee8;
  box-sizing: border-box;
}

.fnc-sheet-title {
  color: #172033;
  font-size: 32rpx;
  font-weight: 750;
  line-height: 1.25;
}

.fnc-sheet-subtitle,
.fnc-sheet-kicker {
  color: #657084;
  font-size: 24rpx;
  line-height: 1.35;
}

.fnc-search-wrap {
  padding: 18rpx 24rpx;
  border-bottom: 2rpx solid #d8dee8;
}

.fnc-search {
  height: 68rpx;
  padding: 0 18rpx;
  border-radius: 14rpx;
  background: #f6f7f9;
  color: #172033;
  font-size: 28rpx;
}

.fnc-sheet-actions {
  flex-shrink: 0;
  display: flex;
  flex-direction: row;
  gap: 14rpx;
  padding: 18rpx 24rpx;
  border-bottom: 2rpx solid #d8dee8;
}

.fnc-sheet-actions-bottom {
  border-top: 2rpx solid #d8dee8;
  border-bottom: 0;
}

.fnc-primary-button {
  flex: 1;
  color: #ffffff;
  background: #1769e0;
}

.fnc-secondary-danger {
  flex: 1;
  color: #c93637;
  border-color: #f1bfc0;
  background: #fff5f5;
}

.fnc-history-error {
  margin: 18rpx 24rpx;
  padding: 16rpx 18rpx;
  border-radius: 12rpx;
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  gap: 12rpx;
  color: #c93637;
  background: #fff4f4;
  font-size: 24rpx;
}

.fnc-history-empty {
  padding: 48rpx 24rpx;
  color: #657084;
  text-align: center;
  font-size: 26rpx;
}

.fnc-history-list,
.fnc-sheet-body {
  flex: 1;
  min-height: 0;
}

.fnc-history-list {
  padding: 12rpx;
  box-sizing: border-box;
}

.fnc-history-item {
  position: relative;
  min-height: 142rpx;
  border: 2rpx solid transparent;
  border-radius: 14rpx;
  box-sizing: border-box;
}

.fnc-history-item-active {
  border-color: #bcd5fa;
  background: #e8f1ff;
}

.fnc-history-content {
  min-height: 138rpx;
  padding: 18rpx 92rpx 18rpx 18rpx;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
  box-sizing: border-box;
}

.fnc-history-main {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 12rpx;
}

.fnc-history-name,
.fnc-history-preview {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fnc-history-name {
  flex: 1;
  color: #172033;
  font-size: 28rpx;
  font-weight: 700;
}

.fnc-history-status {
  flex-shrink: 0;
  padding: 4rpx 10rpx;
  border-radius: 8rpx;
  color: #657084;
  background: #eef2f6;
  font-size: 22rpx;
}

.fnc-history-preview {
  color: #657084;
  font-size: 24rpx;
}

.fnc-history-meta {
  display: flex;
  flex-direction: row;
  gap: 14rpx;
  color: #657084;
  font-size: 22rpx;
}

.fnc-history-delete {
  position: absolute;
  right: 12rpx;
  top: 18rpx;
}

.fnc-suspension-summary {
  display: block;
  padding: 20rpx 24rpx 0;
  color: #172033;
  font-size: 28rpx;
  line-height: 1.55;
}

.fnc-comment {
  width: calc(100% - 48rpx);
  min-height: 132rpx;
  margin: 0 24rpx 24rpx;
  padding: 18rpx;
  border-radius: 14rpx;
  color: #172033;
  background: #f6f7f9;
  font-size: 28rpx;
  line-height: 1.45;
  box-sizing: border-box;
}

.fnc-report-sheet {
  max-height: 90vh;
}

.fnc-report-loader {
  padding: 0 24rpx 24rpx;
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}

.fnc-report-error {
  color: #c93637;
  font-size: 24rpx;
}

.fnc-report-markdown {
  padding: 18rpx;
  border-radius: 12rpx;
  background: #f6f7f9;
  color: #172033;
  font-size: 26rpx;
  line-height: 1.6;
  overflow-wrap: anywhere;
  word-break: break-word;
}
</style>
