<template>
  <div
    class="navigator-mobile-chat"
    :style="containerStyle"
    :data-attachments-enabled="attachmentsEnabled ? 'true' : 'false'"
    :data-upload-hook="resolvedConfig.uploadAttachment ? 'present' : 'missing'"
  >
    <header v-if="showHeader" class="nmc-header">
      <button
        v-if="showHistory"
        type="button"
        class="nmc-icon-button"
        aria-label="打开历史会话"
        @click="openHistory"
      >
        <span aria-hidden="true">≡</span>
      </button>
      <div class="nmc-title-block">
        <div class="nmc-title">{{ title }}</div>
        <div v-if="subtitleText" class="nmc-subtitle">{{ subtitleText }}</div>
      </div>
      <button
        v-if="chat.isLoading.value"
        type="button"
        class="nmc-header-action nmc-header-action--danger"
        @click="chat.cancel()"
      >
        取消
      </button>
      <span v-else-if="statusLabel" :class="['nmc-status-chip', `nmc-status-chip--${statusKind}`]">
        {{ statusLabel }}
      </span>
    </header>

    <main ref="messagesRef" class="nmc-messages" @scroll="handleMessagesScroll">
      <slot v-if="visibleMessages.length === 0 && !chat.isLoading.value" name="empty">
        <div class="nmc-empty">
          <div class="nmc-empty-title">{{ emptyText }}</div>
          <div class="nmc-empty-subtitle">开始一轮新的 Agent 对话</div>
        </div>
      </slot>

      <section
        v-for="message in visibleMessages"
        :key="message.id"
        :class="['nmc-message', `nmc-message--${message.role}`]"
      >
        <article v-if="message.role === 'user'" class="nmc-bubble nmc-bubble--user">
          <div v-if="message.content" class="nmc-user-text">{{ message.content }}</div>
          <div v-if="message.attachments?.length" class="nmc-attachment-list nmc-attachment-list--sent">
            <a
              v-for="attachment in message.attachments"
              :key="attachmentKey(attachment)"
              class="nmc-sent-attachment"
              :href="attachment.url || undefined"
              target="_blank"
              rel="noopener noreferrer"
              @click="handleAttachmentClick($event, attachment)"
            >
              <img
                v-if="isAttachmentImage(attachment) && attachmentPreviewUrl(attachment)"
                class="nmc-sent-attachment-thumb"
                :src="attachmentPreviewUrl(attachment)"
                :alt="attachmentName(attachment)"
              />
              <span v-else class="nmc-file-glyph" aria-hidden="true">□</span>
              <span class="nmc-attachment-name">{{ attachmentName(attachment) }}</span>
            </a>
          </div>
        </article>

        <article v-else-if="message.role === 'assistant'" class="nmc-bubble nmc-bubble--assistant">
          <!-- markdown-it is configured with html:false. -->
          <div v-if="message.content" class="nmc-markdown" v-html="renderMarkdown(message.content)" />
          <div v-if="message.actions?.length" class="nmc-action-list">
            <button
              v-for="action in message.actions"
              :key="action.id"
              type="button"
              class="nmc-action-button"
              @click="handleAction(action)"
            >
              {{ action.label }}
            </button>
          </div>
          <button
            v-if="hasReport(message)"
            type="button"
            class="nmc-report-card"
            @click="openReport(message.executionReportRef, message.executionReportDigest, '执行报告')"
          >
            <span class="nmc-report-title">执行报告</span>
            <span v-if="reportSummary(message.executionReportDigest)" class="nmc-report-summary">
              {{ reportSummary(message.executionReportDigest) }}
            </span>
          </button>
          <div v-if="message.durationMs || message.costUsd" class="nmc-meta">
            <span v-if="message.durationMs">{{ (message.durationMs / 1000).toFixed(1) }}s</span>
            <span v-if="message.costUsd">${{ message.costUsd.toFixed(4) }}</span>
          </div>
        </article>

        <article v-else class="nmc-system-block">
          <div v-if="message.toolExecution" class="nmc-tool-card">
            <button
              type="button"
              class="nmc-tool-header"
              @click="toggleExpanded(toolKey(message.toolExecution))"
            >
              <span class="nmc-tool-main">
                <span :class="['nmc-tool-dot', `nmc-tool-dot--${message.toolExecution.status}`]" />
                <span class="nmc-tool-name">{{ toolExecutionTitle(message.toolExecution) }}</span>
              </span>
              <span class="nmc-tool-status">{{ toolStatusLabel(message.toolExecution.status) }}</span>
            </button>
            <div v-if="toolSummary(message.toolExecution)" class="nmc-tool-summary">
              {{ toolSummary(message.toolExecution) }}
            </div>
            <button
              v-if="hasReport(message.toolExecution)"
              type="button"
              class="nmc-report-card nmc-report-card--compact"
              @click="openReport(message.toolExecution.executionReportRef, message.toolExecution.executionReportDigest, '工具执行报告')"
            >
              <span class="nmc-report-title">执行报告</span>
              <span v-if="reportSummary(message.toolExecution.executionReportDigest)" class="nmc-report-summary">
                {{ reportSummary(message.toolExecution.executionReportDigest) }}
              </span>
            </button>
            <div v-if="expandedItems[toolKey(message.toolExecution)]" class="nmc-tool-detail">
              <dl class="nmc-detail-grid">
                <template v-if="message.toolExecution.functionId">
                  <dt>业务能力</dt>
                  <dd>{{ message.toolExecution.functionId }}</dd>
                </template>
                <template v-if="message.toolExecution.durationMs != null">
                  <dt>耗时</dt>
                  <dd>{{ (message.toolExecution.durationMs / 1000).toFixed(1) }}s</dd>
                </template>
              </dl>
              <details v-if="showDiagnosticDetails" class="nmc-technical-details">
                <summary>技术详情</summary>
                <pre>{{ formatJson({
                  args: message.toolExecution.args,
                  result: message.toolExecution.result,
                  error: message.toolExecution.error,
                  trace: message.toolExecution.trace,
                }) }}</pre>
              </details>
            </div>
          </div>

          <div v-else-if="message.skillFrame" class="nmc-tool-card nmc-tool-card--frame">
            <div class="nmc-tool-header nmc-tool-header--static">
              <span class="nmc-tool-main">
                <span :class="['nmc-tool-dot', `nmc-tool-dot--${message.skillFrame.status}`]" />
                <span class="nmc-tool-name">{{ message.skillFrame.displayName }}</span>
              </span>
              <span class="nmc-tool-status">{{ frameStatusLabel(message.skillFrame.status) }}</span>
            </div>
            <button
              v-if="hasReport(message.skillFrame)"
              type="button"
              class="nmc-report-card nmc-report-card--compact"
              @click="openReport(message.skillFrame.executionReportRef, message.skillFrame.executionReportDigest, '步骤执行报告')"
            >
              <span class="nmc-report-title">执行报告</span>
              <span v-if="reportSummary(message.skillFrame.executionReportDigest)" class="nmc-report-summary">
                {{ reportSummary(message.skillFrame.executionReportDigest) }}
              </span>
            </button>
          </div>

          <details v-else-if="message.process" class="nmc-process-card">
            <summary>
              <span>{{ processLabel(message) }}</span>
              <span v-if="message.status" class="nmc-process-status">{{ taskStatusLabel(message.status) }}</span>
            </summary>
            <pre>{{ message.content }}</pre>
          </details>

          <div v-else-if="message.error" class="nmc-error-card">
            {{ message.content || message.error }}
          </div>

          <div v-else class="nmc-state-line">
            {{ message.content }}
          </div>
        </article>
      </section>

      <section v-if="chat.isLoading.value" class="nmc-message nmc-message--assistant">
        <article class="nmc-bubble nmc-bubble--assistant nmc-thinking">
          <span class="nmc-spinner" aria-hidden="true" />
          <span>{{ chat.progressText.value || thinkingText }}</span>
        </article>
      </section>
    </main>

    <footer v-if="showInput" class="nmc-input-shell">
      <div v-if="inputError" class="nmc-input-error">{{ inputError }}</div>
      <div v-if="pendingAttachments.length" class="nmc-pending-list">
        <div
          v-for="attachment in pendingAttachments"
          :key="attachment.id"
          :class="['nmc-pending-item', `nmc-pending-item--${attachment.status}`]"
        >
          <img
            v-if="attachment.isImage && attachment.previewUrl"
            class="nmc-pending-thumb"
            :src="attachment.previewUrl"
            :alt="attachment.name"
          />
          <span v-else class="nmc-file-glyph" aria-hidden="true">□</span>
          <span class="nmc-pending-name">{{ attachment.name }}</span>
          <button
            type="button"
            class="nmc-remove-button"
            :disabled="isUploadingAttachments"
            aria-label="移除附件"
            @click="removePendingAttachment(attachment.id)"
          >
            ×
          </button>
        </div>
      </div>
      <div class="nmc-input-row">
        <input
          ref="fileInputRef"
          class="nmc-file-input"
          type="file"
          :accept="attachmentAcceptAttr"
          multiple
          @change="handleFileInputChange"
        />
        <button
          v-if="attachmentsEnabled"
          type="button"
          class="nmc-icon-button nmc-icon-button--input"
          :disabled="chat.isLoading.value || isUploadingAttachments"
          aria-label="添加附件"
          @click="openFileDialog"
        >
          +
        </button>
        <textarea
          ref="textareaRef"
          v-model="inputText"
          class="nmc-textarea"
          :placeholder="placeholder"
          :disabled="chat.isLoading.value || isUploadingAttachments"
          rows="1"
          @input="autoResizeInput"
          @keydown.enter.exact.prevent="handleSend"
        />
        <button
          type="button"
          class="nmc-send-button"
          :disabled="!canSend"
          @click="handleSend"
        >
          {{ sendButtonText }}
        </button>
      </div>
    </footer>

    <Transition name="nmc-fade">
      <div v-if="historyVisible" class="nmc-overlay" @click.self="historyVisible = false">
        <aside class="nmc-history-sheet" aria-label="历史会话">
          <div class="nmc-sheet-header">
            <div>
              <div class="nmc-sheet-title">{{ historyTitle }}</div>
              <div class="nmc-sheet-subtitle">{{ historySubtitle }}</div>
            </div>
            <button type="button" class="nmc-icon-button" aria-label="关闭历史会话" @click="historyVisible = false">
              ×
            </button>
          </div>
          <div v-if="showHistorySearch" class="nmc-search-wrap">
            <input v-model.trim="historyKeyword" class="nmc-search" type="search" :placeholder="historySearchPlaceholder" />
          </div>
          <div class="nmc-history-actions">
            <button v-if="showHistoryNewSession" type="button" class="nmc-secondary-button" @click="handleNewSession">
              新会话
            </button>
            <button type="button" class="nmc-secondary-button" :disabled="historyLoading" @click="loadHistorySessions">
              {{ historyLoading ? '刷新中' : '刷新' }}
            </button>
          </div>
          <div v-if="historyError" class="nmc-history-error">
            <span>{{ historyLoadErrorText }}</span>
            <button type="button" @click="loadHistorySessions">重试</button>
          </div>
          <div v-else-if="historyLoading && historySessions.length === 0" class="nmc-history-empty">
            加载中...
          </div>
          <div v-else-if="filteredHistorySessions.length === 0" class="nmc-history-empty">
            {{ historyKeyword ? historyNoResultText : historyEmptyText }}
          </div>
          <div v-else class="nmc-history-list">
            <article
              v-for="session in filteredHistorySessions"
              :key="session.contextId"
              :class="['nmc-history-item', { 'is-active': session.contextId === chat.contextId.value }]"
            >
              <button
                type="button"
                class="nmc-history-content"
                :disabled="loadingHistoryContextId === session.contextId || deletingHistoryContextId === session.contextId"
                @click="handleHistorySessionClick(session)"
              >
                <span class="nmc-history-main">
                  <span class="nmc-history-name">{{ sessionDisplayTitle(session) }}</span>
                  <span :class="['nmc-history-status', `nmc-history-status--${sessionStatusKind(session)}`]">
                    {{ sessionStatusLabel(session) }}
                  </span>
                </span>
                <span v-if="sessionDisplayPreview(session)" class="nmc-history-preview">
                  {{ sessionDisplayPreview(session) }}
                </span>
                <span class="nmc-history-meta">
                  <span>{{ formatSessionTime(session.updatedAt || session.createdAt) }}</span>
                  <span v-if="sessionTurnCount(session) != null">{{ sessionTurnCount(session) }}轮</span>
                </span>
              </button>
              <button
                v-if="showHistoryDelete"
                type="button"
                class="nmc-history-delete"
                :disabled="deletingHistoryContextId === session.contextId"
                aria-label="删除会话"
                @click="handleHistorySessionDelete(session)"
              >
                ×
              </button>
            </article>
          </div>
        </aside>
      </div>
    </Transition>

    <Transition name="nmc-fade">
      <div v-if="showSuspensionSheet && suspension && suspensionDialogVisible" class="nmc-overlay" @click.self="updateSuspensionDialogVisible(false)">
        <section class="nmc-bottom-sheet" aria-label="业务确认">
          <div class="nmc-sheet-grip" />
          <div class="nmc-sheet-header">
            <div>
              <div class="nmc-sheet-kicker">{{ suspension.suspensionType }}</div>
              <div class="nmc-sheet-title">{{ suspensionTitle }}</div>
            </div>
            <button type="button" class="nmc-icon-button" aria-label="关闭确认面板" @click="updateSuspensionDialogVisible(false)">
              ×
            </button>
          </div>
          <p v-if="suspension.summary" class="nmc-suspension-summary">{{ suspension.summary }}</p>
          <dl class="nmc-detail-grid nmc-suspension-fields">
            <template v-if="suspension.functionId">
              <dt>业务能力</dt>
              <dd>{{ suspension.functionDisplayName || suspension.functionId }}</dd>
            </template>
            <template v-if="suspension.version">
              <dt>版本</dt>
              <dd>{{ suspension.version }}</dd>
            </template>
            <template v-if="suspension.riskLevel">
              <dt>风险级别</dt>
              <dd>{{ suspension.riskLevel }}</dd>
            </template>
            <template v-if="suspension.expiresAt">
              <dt>过期时间</dt>
              <dd>{{ suspension.expiresAt }}</dd>
            </template>
            <template v-for="field in suspension.displayFields || []" :key="field.label">
              <dt>{{ field.label }}</dt>
              <dd>{{ fieldValue(field.value) }}</dd>
            </template>
          </dl>
          <textarea
            v-model="suspensionComment"
            class="nmc-comment"
            :placeholder="suspension.commentPlaceholder || '补充说明（可选）'"
            :disabled="suspensionSubmitting"
          />
          <div class="nmc-sheet-actions">
            <button
              type="button"
              class="nmc-secondary-button nmc-secondary-button--danger"
              :disabled="suspensionSubmitting"
              @click="submitSuspensionDecision('rejected')"
            >
              {{ suspension.rejectLabel || '拒绝' }}
            </button>
            <button
              type="button"
              class="nmc-primary-button"
              :disabled="suspensionSubmitting"
              @click="submitSuspensionDecision('approved')"
            >
              {{ suspensionSubmitting ? '提交中' : (suspension.approveLabel || '批准') }}
            </button>
          </div>
        </section>
      </div>
    </Transition>

    <Transition name="nmc-fade">
      <div v-if="activeReport" class="nmc-overlay" @click.self="closeReport">
        <section class="nmc-bottom-sheet nmc-report-sheet" aria-label="执行报告">
          <div class="nmc-sheet-grip" />
          <div class="nmc-sheet-header">
            <div>
              <div class="nmc-sheet-kicker">REPORT</div>
              <div class="nmc-sheet-title">{{ activeReport.title }}</div>
            </div>
            <button type="button" class="nmc-icon-button" aria-label="关闭执行报告" @click="closeReport">
              ×
            </button>
          </div>
          <dl class="nmc-detail-grid nmc-report-fields">
            <template v-for="row in reportRows" :key="row.key">
              <dt>{{ row.label }}</dt>
              <dd :class="{ 'nmc-report-error': row.key === 'error' && row.value }">{{ row.value || row.emptyText }}</dd>
            </template>
            <template v-if="activeReport.reportRef">
              <dt>report_ref</dt>
              <dd class="nmc-report-ref">{{ activeReport.reportRef }}</dd>
            </template>
          </dl>
          <div v-if="canLoadReportMarkdown" class="nmc-report-markdown">
            <button
              type="button"
              class="nmc-secondary-button"
              :disabled="reportMarkdownLoading"
              @click="loadReportMarkdown"
            >
              {{ reportMarkdownLoading ? '加载中' : (reportMarkdownText ? '刷新完整 Markdown' : '查看完整 Markdown') }}
            </button>
            <div v-if="reportMarkdownError" class="nmc-history-error">{{ reportMarkdownError }}</div>
            <pre v-if="reportMarkdownText" class="nmc-markdown-viewer">{{ reportMarkdownText }}</pre>
          </div>
        </section>
      </div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch, type CSSProperties } from 'vue'
import MarkdownIt from 'markdown-it'
import { useNavigatorChat } from '../composables/useNavigatorChat'
import type {
  BusinessSuspensionDecision,
  BusinessSuspensionDecisionPayload,
  BusinessSuspensionDialogModel,
  ChatMessage,
  ExecutionReportDigest,
  ExecutionReportMarkdownLoader,
  ExecutionReportMarkdownPayload,
  NavigatorAction,
  NavigatorAttachmentKind,
  NavigatorAttachmentResult,
  NavigatorChatConfig,
  NavigatorChatMode,
  NavigatorSendOptions,
  PendingNavigatorAttachment,
  SessionSummary,
  SkillFrameBlock,
  TaskStatus,
  ToolExecutionBlock,
  UploadAttachmentHook,
} from '../types'

const md = new MarkdownIt({ html: false, linkify: true, breaks: true })

const props = withDefaults(defineProps<{
  config?: NavigatorChatConfig
  baseUrl?: string
  apiKey?: string
  agentId?: string
  mode?: NavigatorChatMode
  debugMode?: boolean
  showRuntimeEvents?: boolean
  showToolCalls?: boolean
  showToolResults?: boolean
  showDiagnosticDetails?: boolean
  executionReportMarkdownLoader?: ExecutionReportMarkdownLoader
  pollIntervalMs?: number
  pollInterval?: number
  timeout?: number
  maxTurns?: number
  uploadAttachment?: UploadAttachmentHook
  enableAttachments?: boolean
  maxAttachments?: number
  maxAttachmentSize?: number
  acceptedAttachmentTypes?: string[]
  fetch?: (url: string, init: RequestInit) => Promise<Response>
  title?: string
  subtitle?: string
  height?: string | number
  emptyText?: string
  thinkingText?: string
  placeholder?: string
  sendLabel?: string
  showHeader?: boolean
  showInput?: boolean
  showHistory?: boolean
  historyTitle?: string
  historySubtitle?: string
  historyLimit?: number
  showHistorySearch?: boolean
  showHistoryNewSession?: boolean
  showHistoryDelete?: boolean
  historySearchPlaceholder?: string
  historyEmptyText?: string
  historyNoResultText?: string
  historyLoadErrorText?: string
  showSuspensionSheet?: boolean
  suspensionDialogVisible?: boolean
  suspension?: BusinessSuspensionDialogModel | null
  suspensionSubmitting?: boolean
}>(), {
  mode: undefined,
  showDiagnosticDetails: false,
  title: '业务助手',
  subtitle: '',
  height: '100dvh',
  emptyText: '暂无消息',
  thinkingText: '正在处理...',
  placeholder: '输入消息...',
  sendLabel: '发送',
  showHeader: true,
  showInput: true,
  showHistory: false,
  historyTitle: '历史会话',
  historySubtitle: '选择会话继续上下文',
  historyLimit: 30,
  showHistorySearch: true,
  showHistoryNewSession: true,
  showHistoryDelete: false,
  historySearchPlaceholder: '搜索会话',
  historyEmptyText: '暂无历史会话',
  historyNoResultText: '没有匹配的会话',
  historyLoadErrorText: '历史会话加载失败',
  showSuspensionSheet: true,
  suspensionDialogVisible: false,
  suspension: null,
  suspensionSubmitting: false,
})

const emit = defineEmits<{
  send: [content: string, attachments?: NavigatorAttachmentResult[]]
  reply: [content: string, taskId: string]
  statusChange: [status: TaskStatus | null]
  action: [action: NavigatorAction]
  attachmentClick: [attachment: NavigatorAttachmentResult]
  historySessionChange: [session: SessionSummary]
  'update:suspensionDialogVisible': [visible: boolean]
  suspensionDecision: [payload: BusinessSuspensionDecisionPayload]
}>()

const resolvedMode = computed<NavigatorChatMode>(() =>
  props.mode ?? props.config?.mode ?? (props.debugMode || props.config?.debugMode ? 'debug' : 'business')
)

const resolvedConfig = computed<NavigatorChatConfig>(() => ({
  ...(props.config ?? {}),
  baseUrl: props.config?.baseUrl ?? props.baseUrl ?? '',
  apiKey: props.apiKey ?? props.config?.apiKey,
  agentId: props.config?.agentId ?? props.agentId ?? '',
  pollInterval: props.pollInterval ?? props.pollIntervalMs ?? props.config?.pollInterval,
  timeout: props.timeout ?? props.config?.timeout,
  maxTurns: props.maxTurns ?? props.config?.maxTurns,
  mode: resolvedMode.value,
  debugMode: props.debugMode ?? props.config?.debugMode,
  showRuntimeEvents: props.showRuntimeEvents ?? props.config?.showRuntimeEvents,
  showToolCalls: props.showToolCalls ?? props.config?.showToolCalls,
  showToolResults: props.showToolResults ?? props.config?.showToolResults,
  executionReportMarkdownLoader: props.executionReportMarkdownLoader ?? props.config?.executionReportMarkdownLoader,
  uploadAttachment: props.uploadAttachment ?? props.config?.uploadAttachment,
  enableAttachments: props.enableAttachments ?? props.config?.enableAttachments,
  maxAttachments: props.maxAttachments ?? props.config?.maxAttachments,
  maxAttachmentSize: props.maxAttachmentSize ?? props.config?.maxAttachmentSize,
  acceptedAttachmentTypes: props.acceptedAttachmentTypes ?? props.config?.acceptedAttachmentTypes,
  fetch: props.fetch ?? props.config?.fetch,
}))

const chat = useNavigatorChat(resolvedConfig.value)
const messagesRef = ref<HTMLElement | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)
const textareaRef = ref<HTMLTextAreaElement | null>(null)
const inputText = ref('')
const inputError = ref('')
const isUploadingAttachments = ref(false)
const pendingAttachments = ref<PendingNavigatorAttachment[]>([])
const expandedItems = ref<Record<string, boolean>>({})
const historyVisible = ref(false)
const historySessions = ref<SessionSummary[]>([])
const historyLoading = ref(false)
const historyError = ref('')
const historyKeyword = ref('')
const loadingHistoryContextId = ref<string | null>(null)
const deletingHistoryContextId = ref<string | null>(null)
const suspensionComment = ref('')
const activeReport = ref<{
  title: string
  reportRef?: string
  digest?: ExecutionReportDigest
} | null>(null)
const reportMarkdownLoading = ref(false)
const reportMarkdownError = ref('')
const reportMarkdownText = ref('')

const containerStyle = computed<CSSProperties>(() => {
  const height = typeof props.height === 'number' ? `${props.height}px` : props.height
  return {
    height,
    minHeight: height,
  }
})

const visibleMessages = computed(() => chat.messages.value.filter((message) => !message.transient))
const subtitleText = computed(() => props.subtitle || (chat.progressText.value && chat.isLoading.value ? chat.progressText.value : ''))
const statusLabel = computed(() => chat.taskStatus.value ? taskStatusLabel(chat.taskStatus.value) : '')
const statusKind = computed(() => taskStatusKind(chat.taskStatus.value))
const attachmentsEnabled = computed(() => {
  const explicit = props.enableAttachments ?? props.config?.enableAttachments
  if (explicit !== undefined) return explicit && !!resolvedConfig.value.uploadAttachment
  return !!resolvedConfig.value.uploadAttachment
})
const attachmentAcceptAttr = computed(() => (resolvedConfig.value.acceptedAttachmentTypes ?? []).join(','))
const canSend = computed(() =>
  !chat.isLoading.value
  && !isUploadingAttachments.value
  && (inputText.value.trim().length > 0 || pendingAttachments.value.length > 0)
)
const sendButtonText = computed(() => isUploadingAttachments.value ? '上传中' : props.sendLabel)
const showDiagnosticDetails = computed(() => props.showDiagnosticDetails || resolvedMode.value === 'debug')
const filteredHistorySessions = computed(() => {
  const keyword = historyKeyword.value.trim().toLowerCase()
  if (!keyword) return historySessions.value
  return historySessions.value.filter((session) => {
    const haystack = [
      session.title,
      session.contextId,
      session.lastMessagePreview,
      session.status,
    ].filter(Boolean).join(' ').toLowerCase()
    return haystack.includes(keyword)
  })
})
const suspensionTitle = computed(() =>
  props.suspension?.title
    || props.suspension?.functionDisplayName
    || props.suspension?.functionId
    || '需要确认'
)
const canLoadReportMarkdown = computed(() =>
  !!activeReport.value?.reportRef && !!resolvedConfig.value.executionReportMarkdownLoader
)
const reportRows = computed(() => {
  const digest = activeReport.value?.digest
  return [
    { key: 'status', label: 'status', value: digestString(digest, 'status', 'state'), emptyText: '未提供' },
    { key: 'summary', label: 'summary', value: digestString(digest, 'summary', 'resultSummary', 'result_summary', 'message', 'text'), emptyText: '未提供' },
    { key: 'error', label: 'error', value: digestString(digest, 'error', 'errorMessage', 'error_message'), emptyText: '无' },
    { key: 'skill_id', label: 'skill_id', value: digestString(digest, 'skillId', 'skill_id'), emptyText: '未提供' },
    { key: 'frame_kind', label: 'frame_kind', value: digestString(digest, 'frameKind', 'frame_kind'), emptyText: '未提供' },
    { key: 'generated_at', label: 'generated_at', value: digestString(digest, 'generatedAt', 'generated_at'), emptyText: '未提供' },
  ]
})

function renderMarkdown(content: string): string {
  return md.render(content)
}

async function handleSend() {
  if (!canSend.value) return
  inputError.value = ''
  const content = inputText.value.trim() || '请查看附件'
  let uploaded: NavigatorAttachmentResult[] = []
  try {
    uploaded = await uploadPendingAttachments()
    await chat.send(content, uploaded.length > 0 ? { attachments: uploaded } : {})
    emit('send', content, uploaded.length > 0 ? uploaded : undefined)
    inputText.value = ''
    clearPendingAttachments()
    await nextTick()
    resetTextareaHeight()
  } catch (error) {
    inputError.value = error instanceof Error ? error.message : String(error)
  }
}

function autoResizeInput(event: Event) {
  const target = event.target as HTMLTextAreaElement | null
  if (!target) return
  target.style.height = 'auto'
  target.style.height = `${Math.min(target.scrollHeight, 132)}px`
}

function resetTextareaHeight() {
  const textarea = textareaRef.value
  if (!textarea) return
  textarea.style.height = ''
}

function openFileDialog() {
  fileInputRef.value?.click()
}

function handleFileInputChange(event: Event) {
  const input = event.target as HTMLInputElement
  if (input.files) {
    addPendingFiles(Array.from(input.files))
  }
  input.value = ''
}

function addPendingFiles(files: File[]) {
  inputError.value = ''
  const maxCount = resolvedConfig.value.maxAttachments ?? 6
  const maxSize = resolvedConfig.value.maxAttachmentSize ?? 20 * 1024 * 1024
  const remaining = Math.max(0, maxCount - pendingAttachments.value.length)
  const accepted = files.slice(0, remaining)
  if (files.length > remaining) {
    inputError.value = `单条消息最多添加 ${maxCount} 个附件`
  }
  for (const file of accepted) {
    if (file.size > maxSize) {
      inputError.value = `${file.name} 超过 ${formatFileSize(maxSize)}`
      continue
    }
    const kind = inferAttachmentKind(file)
    pendingAttachments.value.push({
      id: `pending-${Date.now()}-${Math.random().toString(16).slice(2)}`,
      file,
      name: file.name,
      mimeType: file.type || inferMimeTypeFromName(file.name),
      size: file.size,
      kind,
      isImage: kind === 'image',
      previewUrl: kind === 'image' ? URL.createObjectURL(file) : undefined,
      status: 'pending',
    })
  }
}

async function uploadPendingAttachments(): Promise<NavigatorAttachmentResult[]> {
  if (!pendingAttachments.value.length) return []
  const upload = resolvedConfig.value.uploadAttachment
  if (!upload) throw new Error('宿主未注入附件上传接口')
  isUploadingAttachments.value = true
  const uploaded: NavigatorAttachmentResult[] = []
  try {
    for (const attachment of pendingAttachments.value) {
      attachment.status = 'uploading'
      try {
        const result = await upload(attachment.file)
        const normalized = normalizeUploadResult(result, attachment)
        attachment.status = 'uploaded'
        attachment.uploaded = normalized
        uploaded.push(normalized)
      } catch (error) {
        attachment.status = 'error'
        attachment.error = error instanceof Error ? error.message : String(error)
        throw new Error(`${attachment.name} 上传失败`)
      }
    }
  } finally {
    isUploadingAttachments.value = false
  }
  return uploaded
}

function normalizeUploadResult(
  result: NavigatorAttachmentResult,
  pending: PendingNavigatorAttachment
): NavigatorAttachmentResult {
  return {
    ...result,
    name: result.name || pending.name,
    mimeType: result.mimeType || pending.mimeType,
    size: result.size ?? pending.size,
    kind: result.kind || pending.kind,
  }
}

function removePendingAttachment(id: string) {
  const index = pendingAttachments.value.findIndex((item) => item.id === id)
  if (index < 0) return
  const [removed] = pendingAttachments.value.splice(index, 1)
  revokePendingAttachment(removed)
}

function clearPendingAttachments() {
  pendingAttachments.value.forEach(revokePendingAttachment)
  pendingAttachments.value = []
}

function revokePendingAttachment(attachment: PendingNavigatorAttachment) {
  if (attachment.previewUrl) URL.revokeObjectURL(attachment.previewUrl)
}

function openHistory() {
  historyVisible.value = true
  if (historySessions.value.length === 0) {
    void loadHistorySessions()
  }
}

async function loadHistorySessions() {
  if (historyLoading.value) return
  historyLoading.value = true
  historyError.value = ''
  try {
    const page = await chat.listSessions({ limit: props.historyLimit })
    historySessions.value = page.sessions ?? []
  } catch (error) {
    historyError.value = error instanceof Error ? error.message : String(error)
  } finally {
    historyLoading.value = false
  }
}

async function handleHistorySessionClick(session: SessionSummary) {
  loadingHistoryContextId.value = session.contextId
  try {
    await chat.loadSession(session.contextId)
    emit('historySessionChange', session)
    historyVisible.value = false
  } finally {
    loadingHistoryContextId.value = null
  }
}

async function handleHistorySessionDelete(session: SessionSummary) {
  deletingHistoryContextId.value = session.contextId
  try {
    await chat.deleteSession(session.contextId)
    historySessions.value = historySessions.value.filter((item) => item.contextId !== session.contextId)
  } finally {
    deletingHistoryContextId.value = null
  }
}

function handleNewSession() {
  chat.clear()
  historyVisible.value = false
}

function handleMessagesScroll() {
  // Reserved for future "new messages" affordance; scroll position is intentionally not persisted.
}

function scrollToBottom() {
  const el = messagesRef.value
  if (!el) return
  el.scrollTop = el.scrollHeight
}

function toggleExpanded(key: string) {
  expandedItems.value = {
    ...expandedItems.value,
    [key]: !expandedItems.value[key],
  }
}

function handleAction(action: NavigatorAction) {
  emit('action', action)
}

function handleAttachmentClick(event: MouseEvent, attachment: NavigatorAttachmentResult) {
  if (!attachment.url) {
    event.preventDefault()
  }
  emit('attachmentClick', attachment)
}

function updateSuspensionDialogVisible(visible: boolean) {
  emit('update:suspensionDialogVisible', visible)
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
  activeReport.value = { title, reportRef, digest }
  reportMarkdownError.value = ''
  reportMarkdownText.value = ''
}

function closeReport() {
  activeReport.value = null
  reportMarkdownError.value = ''
  reportMarkdownText.value = ''
}

async function loadReportMarkdown() {
  if (!activeReport.value?.reportRef || !resolvedConfig.value.executionReportMarkdownLoader) return
  reportMarkdownLoading.value = true
  reportMarkdownError.value = ''
  try {
    const payload = await resolvedConfig.value.executionReportMarkdownLoader(activeReport.value.reportRef)
    const markdown = markdownFromPayload(payload)
    if (!markdown) {
      reportMarkdownError.value = '未读取到 Markdown 内容'
    } else {
      reportMarkdownText.value = markdown
    }
  } catch (error) {
    reportMarkdownError.value = error instanceof Error ? error.message : String(error)
  } finally {
    reportMarkdownLoading.value = false
  }
}

function markdownFromPayload(payload: string | ExecutionReportMarkdownPayload): string {
  if (typeof payload === 'string') return payload
  if (!payload || typeof payload !== 'object') return ''
  if (payload.ok === false) {
    throw new Error(stringValue(payload.error, payload.message) || '执行报告读取失败')
  }
  return stringValue(
    payload.markdown,
    payload.content,
    payload.text,
    payload.markdownExcerpt,
    payload.markdown_excerpt,
  )
}

function hasReport(value: ChatMessage | ToolExecutionBlock | SkillFrameBlock): boolean {
  return !!value.executionReportRef || !!value.executionReportDigest
}

function reportSummary(digest?: ExecutionReportDigest): string {
  return digestString(digest, 'summary', 'resultSummary', 'result_summary', 'message', 'text')
}

function digestString(digest: ExecutionReportDigest | undefined, ...keys: string[]): string {
  if (!digest) return ''
  for (const key of keys) {
    const value = digest[key]
    if (typeof value === 'string' && value.trim()) return value.trim()
    if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  }
  return ''
}

function processLabel(message: ChatMessage): string {
  switch (message.messageType) {
    case 'STATE': return '运行状态'
    case 'TOOL_CALL': return '工具调用'
    case 'TOOL_RESULT': return '工具结果'
    case 'ERROR': return '错误'
    default: return '过程消息'
  }
}

function toolExecutionTitle(block: ToolExecutionBlock): string {
  return block.displayName || block.functionName || block.functionId || block.toolName || '执行步骤'
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

function frameStatusLabel(status: SkillFrameBlock['status']): string {
  switch (status) {
    case 'running': return '执行中'
    case 'success': return '完成'
    case 'failed': return '失败'
  }
}

function toolSummary(block: ToolExecutionBlock): string {
  return (block.summary ?? []).filter(Boolean).join(' / ')
}

function toolKey(block: ToolExecutionBlock): string {
  return block.toolCallId || `${block.toolName}-${block.startedAt ?? ''}`
}

function taskStatusLabel(status: TaskStatus | string): string {
  switch (status) {
    case 'SUBMITTED': return '已提交'
    case 'WORKING': return '进行中'
    case 'COMPLETED': return '已完成'
    case 'FAILED': return '失败'
    case 'CANCELED': return '已取消'
    case 'INPUT_REQUIRED': return '待确认'
    default: return status
  }
}

function taskStatusKind(status: TaskStatus | string | null): string {
  if (!status) return 'idle'
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED' || status === 'CANCELED') return 'failed'
  if (status === 'INPUT_REQUIRED') return 'input'
  return 'working'
}

function sessionDisplayTitle(session: SessionSummary): string {
  return session.title?.trim() || session.lastMessagePreview?.trim() || session.contextId
}

function sessionDisplayPreview(session: SessionSummary): string {
  return session.lastMessagePreview?.trim() || ''
}

function sessionTurnCount(session: SessionSummary): number | undefined {
  return typeof session.turnCount === 'number' ? session.turnCount : undefined
}

function sessionStatusKind(session: SessionSummary): string {
  return taskStatusKind(session.status ?? null)
}

function sessionStatusLabel(session: SessionSummary): string {
  return session.status ? taskStatusLabel(session.status) : '会话'
}

function formatSessionTime(value?: string): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  })
}

function attachmentKey(attachment: NavigatorAttachmentResult): string {
  return String(attachment.id || attachment.url || `${attachment.name}-${attachment.size ?? ''}`)
}

function attachmentName(attachment: NavigatorAttachmentResult): string {
  return String(attachment.name || '附件')
}

function attachmentPreviewUrl(attachment: NavigatorAttachmentResult): string {
  return String(attachment.thumbnailUrl || attachment.url || '')
}

function isAttachmentImage(attachment: NavigatorAttachmentResult): boolean {
  const kind = typeof attachment.kind === 'string' ? attachment.kind.toLowerCase() : ''
  const mimeType = typeof attachment.mimeType === 'string' ? attachment.mimeType.toLowerCase() : ''
  return kind === 'image' || mimeType.startsWith('image/')
}

function inferAttachmentKind(file: File): NavigatorAttachmentKind {
  const mimeType = (file.type || inferMimeTypeFromName(file.name)).toLowerCase()
  const name = file.name.toLowerCase()
  if (mimeType.startsWith('image/')) return 'image'
  if (mimeType === 'application/pdf' || name.endsWith('.pdf')) return 'pdf'
  if (mimeType.startsWith('text/') || /\.(txt|md|csv|json|xml|log)$/i.test(name)) return 'text'
  if (/(spreadsheet|excel|csv)/i.test(mimeType) || /\.(xls|xlsx|csv)$/i.test(name)) return 'spreadsheet'
  if (/(word|document|msword|officedocument)/i.test(mimeType) || /\.(doc|docx|rtf)$/i.test(name)) return 'document'
  if (/(zip|rar|7z|tar|gzip)/i.test(mimeType) || /\.(zip|rar|7z|tar|gz)$/i.test(name)) return 'archive'
  return 'file'
}

function inferMimeTypeFromName(name: string): string {
  const lower = name.toLowerCase()
  if (lower.endsWith('.png')) return 'image/png'
  if (lower.endsWith('.jpg') || lower.endsWith('.jpeg')) return 'image/jpeg'
  if (lower.endsWith('.webp')) return 'image/webp'
  if (lower.endsWith('.gif')) return 'image/gif'
  if (lower.endsWith('.pdf')) return 'application/pdf'
  if (lower.endsWith('.csv')) return 'text/csv'
  if (lower.endsWith('.txt')) return 'text/plain'
  return ''
}

function formatFileSize(size: number): string {
  if (!Number.isFinite(size) || size < 0) return ''
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / (1024 * 1024)).toFixed(1)} MB`
}

function formatJson(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

function fieldValue(value: unknown): string {
  if (value == null || value === '') return '-'
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return formatJson(value)
}

function stringValue(...values: unknown[]): string {
  for (const value of values) {
    if (typeof value === 'string' && value.trim()) return value.trim()
    if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  }
  return ''
}

onMounted(() => {
  if (props.showHistory) {
    void loadHistorySessions()
  }
})

onBeforeUnmount(() => {
  clearPendingAttachments()
})

watch(
  () => props.showHistory,
  (visible) => {
    if (visible && historySessions.value.length === 0) {
      void loadHistorySessions()
    }
  }
)

watch(
  () => [visibleMessages.value.length, chat.isLoading.value],
  () => {
    nextTick(scrollToBottom)
  }
)

watch(chat.taskStatus, (status) => {
  emit('statusChange', status)
  if (props.showHistory && ['COMPLETED', 'FAILED', 'CANCELED', 'INPUT_REQUIRED'].includes(status ?? '')) {
    void loadHistorySessions()
  }
})

watch(
  () => chat.messages.value,
  (messages) => {
    const last = messages[messages.length - 1]
    if (last?.role === 'assistant' && last.taskId && (last.terminal || last.messageType === 'RESULT')) {
      emit('reply', last.content, last.taskId)
    }
  },
  { deep: true }
)

watch(
  () => props.suspensionDialogVisible,
  (visible) => {
    if (!visible) suspensionComment.value = ''
  }
)

defineExpose({
  send: (content: string, options?: NavigatorSendOptions) => chat.send(content, options),
  cancel: () => chat.cancel(),
  clear: () => chat.clear(),
  openHistory,
  loadHistorySessions,
})
</script>

<style scoped>
.navigator-mobile-chat {
  --nmc-bg: #f6f7f9;
  --nmc-surface: #ffffff;
  --nmc-surface-muted: #eef2f6;
  --nmc-border: #d8dee8;
  --nmc-text: #172033;
  --nmc-muted: #657084;
  --nmc-primary: #1769e0;
  --nmc-primary-weak: #e8f1ff;
  --nmc-success: #168a55;
  --nmc-warning: #b7791f;
  --nmc-danger: #c93637;
  --nmc-shadow: 0 16px 40px rgb(23 32 51 / 18%);
  width: 100%;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  color: var(--nmc-text);
  background: var(--nmc-bg);
  font-family:
    "PingFang SC", "Microsoft YaHei", system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  box-sizing: border-box;
  -webkit-tap-highlight-color: transparent;
}

.navigator-mobile-chat *,
.navigator-mobile-chat *::before,
.navigator-mobile-chat *::after {
  box-sizing: border-box;
}

.nmc-header {
  flex: 0 0 auto;
  min-height: 56px;
  padding: calc(8px + env(safe-area-inset-top)) 12px 8px;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  border-bottom: 1px solid var(--nmc-border);
  background: rgb(255 255 255 / 94%);
  backdrop-filter: blur(14px);
}

.nmc-title-block {
  min-width: 0;
}

.nmc-title {
  overflow: hidden;
  color: var(--nmc-text);
  font-size: 16px;
  font-weight: 700;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nmc-subtitle {
  margin-top: 2px;
  overflow: hidden;
  color: var(--nmc-muted);
  font-size: 12px;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nmc-icon-button,
.nmc-header-action,
.nmc-secondary-button,
.nmc-primary-button,
.nmc-send-button,
.nmc-action-button {
  border: 1px solid var(--nmc-border);
  border-radius: 8px;
  font: inherit;
  cursor: pointer;
}

.nmc-icon-button {
  width: 36px;
  height: 36px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  color: var(--nmc-text);
  background: var(--nmc-surface);
  font-size: 20px;
  line-height: 1;
}

.nmc-icon-button:disabled,
.nmc-send-button:disabled,
.nmc-secondary-button:disabled,
.nmc-primary-button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.nmc-header-action {
  min-height: 32px;
  padding: 0 10px;
  color: var(--nmc-text);
  background: var(--nmc-surface);
  font-size: 13px;
}

.nmc-header-action--danger {
  border-color: rgb(201 54 55 / 28%);
  color: var(--nmc-danger);
  background: #fff5f5;
}

.nmc-status-chip {
  min-width: 0;
  padding: 5px 8px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 650;
  white-space: nowrap;
  background: var(--nmc-surface-muted);
  color: var(--nmc-muted);
}

.nmc-status-chip--working {
  color: var(--nmc-primary);
  background: var(--nmc-primary-weak);
}

.nmc-status-chip--success {
  color: var(--nmc-success);
  background: #e8f6ef;
}

.nmc-status-chip--failed {
  color: var(--nmc-danger);
  background: #fff0f0;
}

.nmc-status-chip--input {
  color: var(--nmc-warning);
  background: #fff7e6;
}

.nmc-messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 14px 12px;
  scroll-behavior: smooth;
}

.nmc-empty {
  min-height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  text-align: center;
}

.nmc-empty-title {
  color: var(--nmc-text);
  font-size: 15px;
  font-weight: 700;
}

.nmc-empty-subtitle {
  color: var(--nmc-muted);
  font-size: 13px;
}

.nmc-message {
  display: flex;
  margin: 10px 0;
}

.nmc-message--user {
  justify-content: flex-end;
}

.nmc-message--assistant,
.nmc-message--system {
  justify-content: flex-start;
}

.nmc-bubble {
  max-width: min(86%, 680px);
  min-width: 0;
  padding: 10px 12px;
  border-radius: 8px;
  overflow-wrap: anywhere;
  word-break: break-word;
  font-size: 14px;
  line-height: 1.62;
}

.nmc-bubble--user {
  color: #fff;
  background: var(--nmc-primary);
  border-bottom-right-radius: 3px;
}

.nmc-bubble--assistant {
  border: 1px solid var(--nmc-border);
  color: var(--nmc-text);
  background: var(--nmc-surface);
  border-bottom-left-radius: 3px;
}

.nmc-user-text {
  white-space: pre-wrap;
}

.nmc-markdown :deep(p) {
  margin: 0 0 8px;
}

.nmc-markdown :deep(p:last-child) {
  margin-bottom: 0;
}

.nmc-markdown :deep(pre) {
  margin: 8px 0;
  padding: 9px 10px;
  overflow-x: auto;
  border-radius: 6px;
  background: #111827;
  color: #e5e7eb;
  font-size: 12px;
}

.nmc-markdown :deep(code) {
  font-family: Consolas, "SFMono-Regular", monospace;
  font-size: 12px;
}

.nmc-markdown :deep(a) {
  color: var(--nmc-primary);
}

.nmc-action-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.nmc-action-button {
  min-height: 34px;
  padding: 0 10px;
  color: var(--nmc-primary);
  background: var(--nmc-primary-weak);
  border-color: rgb(23 105 224 / 22%);
  font-size: 13px;
  font-weight: 650;
}

.nmc-report-card {
  width: 100%;
  margin-top: 10px;
  padding: 9px 10px;
  border: 1px solid var(--nmc-border);
  border-radius: 8px;
  display: grid;
  gap: 3px;
  text-align: left;
  color: var(--nmc-text);
  background: #f8fafc;
  cursor: pointer;
}

.nmc-report-card--compact {
  margin-top: 8px;
}

.nmc-report-title {
  font-size: 13px;
  font-weight: 700;
}

.nmc-report-summary {
  overflow: hidden;
  color: var(--nmc-muted);
  font-size: 12px;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nmc-meta {
  display: flex;
  gap: 8px;
  margin-top: 8px;
  color: var(--nmc-muted);
  font-size: 11px;
}

.nmc-system-block {
  width: min(92%, 720px);
  min-width: 0;
}

.nmc-tool-card,
.nmc-process-card,
.nmc-error-card,
.nmc-state-line {
  width: 100%;
  border-radius: 8px;
  border: 1px solid var(--nmc-border);
  background: var(--nmc-surface);
}

.nmc-tool-card {
  padding: 10px;
}

.nmc-tool-card--frame {
  background: #fbfcfe;
}

.nmc-tool-header {
  width: 100%;
  min-width: 0;
  padding: 0;
  border: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  text-align: left;
  color: inherit;
  background: transparent;
  cursor: pointer;
}

.nmc-tool-header--static {
  cursor: default;
}

.nmc-tool-main {
  min-width: 0;
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.nmc-tool-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: 0 0 auto;
  background: var(--nmc-muted);
}

.nmc-tool-dot--running {
  background: var(--nmc-primary);
}

.nmc-tool-dot--success {
  background: var(--nmc-success);
}

.nmc-tool-dot--failed {
  background: var(--nmc-danger);
}

.nmc-tool-name {
  min-width: 0;
  overflow: hidden;
  color: var(--nmc-text);
  font-size: 13px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nmc-tool-status {
  flex: 0 0 auto;
  color: var(--nmc-muted);
  font-size: 12px;
}

.nmc-tool-summary {
  margin-top: 8px;
  color: var(--nmc-muted);
  font-size: 12px;
  line-height: 1.45;
}

.nmc-tool-detail {
  margin-top: 10px;
}

.nmc-detail-grid {
  margin: 0;
  display: grid;
  grid-template-columns: 82px minmax(0, 1fr);
  gap: 8px 10px;
  font-size: 13px;
}

.nmc-detail-grid dt {
  color: var(--nmc-muted);
}

.nmc-detail-grid dd {
  min-width: 0;
  margin: 0;
  color: var(--nmc-text);
  overflow-wrap: anywhere;
  word-break: break-word;
}

.nmc-technical-details {
  margin-top: 10px;
  color: var(--nmc-muted);
  font-size: 12px;
}

.nmc-technical-details pre,
.nmc-process-card pre,
.nmc-markdown-viewer {
  max-height: 260px;
  margin: 8px 0 0;
  padding: 10px;
  overflow: auto;
  border-radius: 6px;
  background: #111827;
  color: #e5e7eb;
  font-family: Consolas, "SFMono-Regular", monospace;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}

.nmc-process-card {
  padding: 9px 10px;
  color: var(--nmc-muted);
  font-size: 12px;
}

.nmc-process-card summary {
  cursor: pointer;
}

.nmc-process-status {
  margin-left: 8px;
  color: var(--nmc-text);
  font-weight: 650;
}

.nmc-error-card {
  padding: 10px 12px;
  color: var(--nmc-danger);
  background: #fff4f4;
  border-color: rgb(201 54 55 / 20%);
  font-size: 13px;
  line-height: 1.5;
}

.nmc-state-line {
  padding: 7px 10px;
  color: var(--nmc-muted);
  background: rgb(255 255 255 / 72%);
  font-size: 12px;
  text-align: center;
}

.nmc-thinking {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--nmc-muted);
}

.nmc-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgb(23 105 224 / 18%);
  border-top-color: var(--nmc-primary);
  border-radius: 50%;
  animation: nmc-spin 900ms linear infinite;
}

.nmc-input-shell {
  flex: 0 0 auto;
  padding: 10px 10px calc(10px + env(safe-area-inset-bottom));
  border-top: 1px solid var(--nmc-border);
  background: rgb(255 255 255 / 96%);
}

.nmc-input-error {
  margin-bottom: 8px;
  color: var(--nmc-danger);
  font-size: 12px;
}

.nmc-pending-list,
.nmc-attachment-list {
  display: flex;
  gap: 8px;
}

.nmc-pending-list {
  margin-bottom: 8px;
  overflow-x: auto;
}

.nmc-pending-item,
.nmc-sent-attachment {
  min-width: 0;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  border: 1px solid var(--nmc-border);
  border-radius: 8px;
  background: #f8fafc;
}

.nmc-pending-item {
  max-width: 220px;
  padding: 6px 7px;
  flex: 0 0 auto;
}

.nmc-pending-item--error {
  border-color: rgb(201 54 55 / 30%);
  background: #fff4f4;
}

.nmc-sent-attachment {
  max-width: 100%;
  padding: 5px 7px;
  color: inherit;
  text-decoration: none;
  background: rgb(255 255 255 / 16%);
  border-color: rgb(255 255 255 / 36%);
}

.nmc-attachment-list--sent {
  flex-wrap: wrap;
  margin-top: 8px;
}

.nmc-pending-thumb,
.nmc-sent-attachment-thumb {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  object-fit: cover;
  flex: 0 0 auto;
  background: var(--nmc-surface-muted);
}

.nmc-file-glyph {
  width: 26px;
  height: 26px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  color: var(--nmc-muted);
  background: var(--nmc-surface-muted);
  flex: 0 0 auto;
  font-size: 14px;
}

.nmc-pending-name,
.nmc-attachment-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
}

.nmc-remove-button {
  width: 24px;
  height: 24px;
  border: 0;
  border-radius: 6px;
  color: var(--nmc-muted);
  background: transparent;
  cursor: pointer;
  flex: 0 0 auto;
}

.nmc-input-row {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: end;
  gap: 8px;
}

.nmc-file-input {
  display: none;
}

.nmc-icon-button--input {
  width: 38px;
  height: 38px;
  font-size: 22px;
}

.nmc-textarea {
  width: 100%;
  min-height: 38px;
  max-height: 132px;
  padding: 9px 11px;
  resize: none;
  overflow-y: auto;
  border: 1px solid var(--nmc-border);
  border-radius: 8px;
  color: var(--nmc-text);
  background: #f8fafc;
  font: inherit;
  font-size: 14px;
  line-height: 1.45;
}

.nmc-textarea:focus {
  outline: 2px solid rgb(23 105 224 / 18%);
  border-color: var(--nmc-primary);
}

.nmc-send-button {
  min-width: 52px;
  height: 38px;
  padding: 0 12px;
  color: #fff;
  background: var(--nmc-primary);
  border-color: var(--nmc-primary);
  font-size: 14px;
  font-weight: 700;
}

.nmc-overlay {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  padding: 0;
  background: rgb(17 24 39 / 48%);
}

.nmc-history-sheet,
.nmc-bottom-sheet {
  width: min(100%, 720px);
  max-height: min(86dvh, 760px);
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-radius: 8px 8px 0 0;
  background: var(--nmc-surface);
  box-shadow: var(--nmc-shadow);
}

.nmc-history-sheet {
  height: min(86dvh, 760px);
}

.nmc-bottom-sheet {
  padding-bottom: env(safe-area-inset-bottom);
}

.nmc-sheet-grip {
  width: 36px;
  height: 4px;
  margin: 8px auto 2px;
  border-radius: 2px;
  background: #c9d1dd;
  flex: 0 0 auto;
}

.nmc-sheet-header {
  flex: 0 0 auto;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 14px 10px;
  border-bottom: 1px solid var(--nmc-border);
}

.nmc-sheet-title {
  color: var(--nmc-text);
  font-size: 16px;
  font-weight: 750;
  line-height: 1.25;
}

.nmc-sheet-subtitle,
.nmc-sheet-kicker {
  margin-top: 2px;
  color: var(--nmc-muted);
  font-size: 12px;
  line-height: 1.35;
}

.nmc-search-wrap {
  flex: 0 0 auto;
  padding: 10px 14px;
  border-bottom: 1px solid var(--nmc-border);
}

.nmc-search {
  width: 100%;
  height: 36px;
  padding: 0 10px;
  border: 1px solid var(--nmc-border);
  border-radius: 8px;
  background: #f8fafc;
  color: var(--nmc-text);
  font: inherit;
  font-size: 14px;
}

.nmc-history-actions,
.nmc-sheet-actions {
  display: flex;
  gap: 8px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--nmc-border);
}

.nmc-sheet-actions {
  border-top: 1px solid var(--nmc-border);
  border-bottom: 0;
}

.nmc-secondary-button,
.nmc-primary-button {
  min-height: 38px;
  padding: 0 12px;
  font-size: 14px;
  font-weight: 650;
}

.nmc-secondary-button {
  color: var(--nmc-text);
  background: var(--nmc-surface);
}

.nmc-secondary-button--danger {
  color: var(--nmc-danger);
  border-color: rgb(201 54 55 / 30%);
  background: #fff5f5;
}

.nmc-primary-button {
  flex: 1;
  color: #fff;
  background: var(--nmc-primary);
  border-color: var(--nmc-primary);
}

.nmc-history-error {
  margin: 10px 14px;
  padding: 9px 10px;
  border-radius: 8px;
  color: var(--nmc-danger);
  background: #fff4f4;
  font-size: 13px;
}

.nmc-history-error button {
  margin-left: 8px;
  border: 0;
  color: var(--nmc-primary);
  background: transparent;
  font: inherit;
  cursor: pointer;
}

.nmc-history-empty {
  padding: 24px 14px;
  color: var(--nmc-muted);
  text-align: center;
  font-size: 13px;
}

.nmc-history-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 8px;
}

.nmc-history-item {
  position: relative;
  min-height: 78px;
  border: 1px solid transparent;
  border-radius: 8px;
}

.nmc-history-item + .nmc-history-item {
  margin-top: 5px;
}

.nmc-history-item.is-active {
  border-color: rgb(23 105 224 / 28%);
  background: var(--nmc-primary-weak);
}

.nmc-history-content {
  width: 100%;
  min-height: 76px;
  padding: 9px 38px 9px 10px;
  border: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
  text-align: left;
  color: inherit;
  background: transparent;
  cursor: pointer;
}

.nmc-history-main {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

.nmc-history-name,
.nmc-history-preview {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nmc-history-name {
  flex: 1;
  color: var(--nmc-text);
  font-size: 14px;
  font-weight: 700;
}

.nmc-history-preview {
  color: var(--nmc-muted);
  font-size: 12px;
}

.nmc-history-status {
  flex: 0 0 auto;
  padding: 2px 6px;
  border-radius: 6px;
  color: var(--nmc-muted);
  background: var(--nmc-surface-muted);
  font-size: 11px;
}

.nmc-history-status--working {
  color: var(--nmc-primary);
  background: var(--nmc-primary-weak);
}

.nmc-history-status--success {
  color: var(--nmc-success);
  background: #e8f6ef;
}

.nmc-history-status--failed {
  color: var(--nmc-danger);
  background: #fff0f0;
}

.nmc-history-status--input {
  color: var(--nmc-warning);
  background: #fff7e6;
}

.nmc-history-meta {
  display: flex;
  gap: 8px;
  color: var(--nmc-muted);
  font-size: 11px;
}

.nmc-history-delete {
  position: absolute;
  top: 8px;
  right: 8px;
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: 6px;
  color: var(--nmc-muted);
  background: transparent;
  cursor: pointer;
}

.nmc-suspension-summary {
  margin: 0;
  padding: 12px 14px 0;
  color: var(--nmc-text);
  font-size: 14px;
  line-height: 1.55;
}

.nmc-suspension-fields,
.nmc-report-fields {
  padding: 14px;
}

.nmc-comment {
  width: calc(100% - 28px);
  min-height: 72px;
  margin: 0 14px 12px;
  padding: 9px 10px;
  resize: vertical;
  border: 1px solid var(--nmc-border);
  border-radius: 8px;
  color: var(--nmc-text);
  background: #f8fafc;
  font: inherit;
  font-size: 14px;
  line-height: 1.45;
}

.nmc-report-sheet {
  max-height: min(90dvh, 820px);
}

.nmc-report-error {
  color: var(--nmc-danger);
}

.nmc-report-ref {
  font-family: Consolas, "SFMono-Regular", monospace;
  font-size: 12px;
}

.nmc-report-markdown {
  padding: 0 14px 14px;
  overflow-y: auto;
}

.nmc-markdown-viewer {
  max-height: 46dvh;
}

.nmc-fade-enter-active,
.nmc-fade-leave-active {
  transition: opacity 160ms ease;
}

.nmc-fade-enter-from,
.nmc-fade-leave-to {
  opacity: 0;
}

@keyframes nmc-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (min-width: 720px) {
  .navigator-mobile-chat {
    border: 1px solid var(--nmc-border);
    border-radius: 8px;
  }

  .nmc-header {
    padding-top: 8px;
  }

  .nmc-overlay {
    align-items: center;
    padding: 24px;
  }

  .nmc-history-sheet,
  .nmc-bottom-sheet {
    border-radius: 8px;
  }
}
</style>
