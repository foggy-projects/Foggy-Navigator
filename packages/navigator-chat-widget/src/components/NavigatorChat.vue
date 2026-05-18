<template>
  <div
    class="navigator-chat"
    :style="containerStyle"
    :data-attachments-enabled="attachmentsEnabled ? 'true' : 'false'"
    :data-upload-hook="resolvedConfig.uploadAttachment ? 'present' : 'missing'"
  >
    <!-- Header -->
    <div v-if="showHeader" class="nc-header">
      <slot name="header">
        <span class="nc-title">{{ title }}</span>
        <div class="nc-header-actions">
          <slot name="header-actions">
            <el-tag v-if="chat.taskStatus.value" size="small" :type="statusTagType">
              {{ statusLabel }}
            </el-tag>
          </slot>
        </div>
      </slot>
    </div>

    <!-- Messages -->
    <div ref="messagesRef" class="nc-messages" @scroll="handleScroll">
      <div v-if="chat.messages.value.length === 0" class="nc-empty">
        <slot name="empty">
          <el-empty :description="emptyText" :image-size="80" />
        </slot>
      </div>
      <div
        v-for="msg in chat.messages.value"
        :key="msg.id"
        :class="['nc-message', `nc-message--${msg.role}`]"
      >
        <div class="nc-bubble">
          <div v-if="msg.role === 'user'" class="nc-content nc-content--user">
            <div v-if="msg.content" class="nc-message-text">{{ msg.content }}</div>
            <div v-if="msg.attachments?.length" class="nc-message-attachments">
              <component
                :is="attachment.url ? 'a' : 'div'"
                v-for="attachment in msg.attachments"
                :key="attachmentKey(attachment)"
                class="nc-message-attachment"
                :href="attachment.url || undefined"
                target="_blank"
                rel="noopener noreferrer"
              >
                <img
                  v-if="isAttachmentImage(attachment) && attachmentPreviewUrl(attachment)"
                  :src="attachmentPreviewUrl(attachment)"
                  :alt="attachmentName(attachment)"
                  class="nc-message-attachment-thumb"
                />
                <el-icon v-else class="nc-message-attachment-icon"><Document /></el-icon>
                <span class="nc-message-attachment-name">{{ attachmentName(attachment) }}</span>
                <span v-if="attachment.size" class="nc-message-attachment-size">{{ formatFileSize(attachment.size) }}</span>
              </component>
            </div>
          </div>
          <div v-else-if="msg.role === 'assistant'" class="nc-content nc-content--assistant">
            <!-- eslint-disable-next-line vue/no-v-html -->
            <div v-if="msg.content" v-html="renderMarkdown(msg.content)" />
            <div v-if="msg.actions?.length" class="nc-actions">
              <el-button
                v-for="action in msg.actions"
                :key="action.id"
                size="small"
                type="primary"
                plain
                @click="handleAction(action)"
              >
                {{ action.label }}
              </el-button>
            </div>
            <ExecutionReportInline
              :report-ref="msg.executionReportRef"
              :digest="msg.executionReportDigest"
              :load-markdown="executionReportMarkdownLoader"
            />
            <div v-if="msg.durationMs || msg.costUsd" class="nc-meta">
              <span v-if="msg.durationMs">{{ (msg.durationMs / 1000).toFixed(1) }}s</span>
              <span v-if="msg.costUsd"> · ${{ msg.costUsd.toFixed(4) }}</span>
            </div>
          </div>
          <div v-else class="nc-content nc-content--system">
            <SkillFrameBlockView
              v-if="msg.skillFrame"
              :frame="msg.skillFrame"
              :load-markdown="executionReportMarkdownLoader"
            />
            <details v-else-if="msg.toolExecution" class="nc-process nc-tool-execution" open>
              <summary>
                <span class="nc-tool-title">
                  <el-icon v-if="msg.toolExecution.status === 'running'" class="is-loading"><Loading /></el-icon>
                  <span>工具调用 {{ msg.toolExecution.displayName }}</span>
                </span>
                <span :class="['nc-tool-status', `nc-tool-status--${msg.toolExecution.status}`]">
                  {{ toolStatusLabel(msg.toolExecution.status) }}
                </span>
                <span v-if="msg.toolExecution.durationMs != null" class="nc-tool-duration">
                  {{ (msg.toolExecution.durationMs / 1000).toFixed(1) }}s
                </span>
              </summary>
              <div class="nc-tool-body">
                <div class="nc-tool-summary">
                  摘要：{{ msg.toolExecution.summary.join(', ') }}
                </div>
                <ExecutionReportInline
                  :report-ref="msg.toolExecution.executionReportRef"
                  :digest="msg.toolExecution.executionReportDigest"
                  :load-markdown="executionReportMarkdownLoader"
                />
                <details class="nc-nested">
                  <summary>参数</summary>
                  <pre>{{ formatJson(msg.toolExecution.args) }}</pre>
                </details>
                <details class="nc-nested">
                  <summary>结果</summary>
                  <pre>{{ formatJson(msg.toolExecution.result ?? msg.toolExecution.error) }}</pre>
                </details>
                <details class="nc-nested">
                  <summary>排障字段</summary>
                  <pre>{{ formatJson(msg.toolExecution.trace) }}</pre>
                </details>
                <details class="nc-nested">
                  <summary>原始 JSON</summary>
                  <pre>{{ formatJson({ call: msg.toolExecution.rawCall, result: msg.toolExecution.rawResult }) }}</pre>
                </details>
              </div>
            </details>
            <details v-else-if="msg.process" class="nc-process">
              <summary>{{ processLabel(msg) }}</summary>
              <pre>{{ msg.content }}</pre>
            </details>
            <el-alert v-else :title="msg.content" :type="msg.error ? 'error' : 'info'" :closable="false" show-icon />
          </div>
        </div>
      </div>

      <!-- Loading indicator -->
      <div v-if="chat.isLoading.value" class="nc-message nc-message--assistant">
        <div class="nc-bubble">
          <div class="nc-thinking">
            <el-icon class="is-loading"><Loading /></el-icon>
            <span>{{ chat.progressText.value || thinkingText }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Input -->
    <div
      v-if="showInput"
      :class="['nc-input-area', { 'is-drag-over': isDragOver }]"
      @paste="handlePaste"
      @dragover.prevent="handleDragOver"
      @dragleave="handleDragLeave"
      @drop.prevent="handleDrop"
    >
      <div v-if="pendingAttachments.length" class="nc-pending-attachments">
        <div
          v-for="attachment in pendingAttachments"
          :key="attachment.id"
          :class="['nc-pending-attachment', `nc-pending-attachment--${attachment.status}`]"
        >
          <img
            v-if="attachment.isImage && attachment.previewUrl"
            :src="attachment.previewUrl"
            :alt="attachment.name"
            class="nc-pending-thumb"
          />
          <el-icon v-else class="nc-pending-file-icon"><Document /></el-icon>
          <div class="nc-pending-info">
            <span class="nc-pending-name" :title="attachment.name">{{ attachment.name }}</span>
            <span class="nc-pending-meta">
              {{ attachmentKindLabel(attachment.kind) }} · {{ formatFileSize(attachment.size) }}
            </span>
            <span v-if="attachment.error" class="nc-pending-error">{{ attachment.error }}</span>
          </div>
          <el-icon v-if="attachment.status === 'uploading'" class="nc-pending-status is-loading"><Loading /></el-icon>
          <button
            v-else
            type="button"
            class="nc-pending-remove"
            :disabled="isUploadingAttachments"
            aria-label="移除附件"
            @click="removePendingAttachment(attachment.id)"
          >
            <el-icon><Close /></el-icon>
          </button>
        </div>
      </div>
      <div class="nc-input-row">
        <input
          ref="fileInputRef"
          class="nc-file-input"
          type="file"
          :accept="attachmentAcceptAttr"
          multiple
          @change="handleFileInputChange"
        />
        <el-tooltip v-if="attachmentsEnabled" content="添加附件" placement="top">
          <el-button
            class="nc-attachment-button"
            size="small"
            :disabled="chat.isLoading.value || isUploadingAttachments"
            @click="openFileDialog"
          >
            <el-icon><Paperclip /></el-icon>
          </el-button>
        </el-tooltip>
        <el-input
          v-model="inputText"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 4 }"
          :placeholder="placeholder"
          :disabled="chat.isLoading.value || isUploadingAttachments"
          resize="none"
          @keydown.enter.exact.prevent="handleSend"
        />
        <div class="nc-input-actions">
          <el-button
            v-if="chat.isLoading.value"
            type="danger"
            size="small"
            plain
            @click="chat.cancel()"
          >
            取消
          </el-button>
          <el-button
            v-else
            type="primary"
            size="small"
            :loading="isUploadingAttachments"
            :disabled="!canSend"
            @click="handleSend"
          >
            发送
          </el-button>
        </div>
      </div>
    </div>

    <slot
      name="suspension-dialog"
      :suspension="suspension"
      :visible="suspensionDialogVisible"
      :submitting="suspensionSubmitting"
      :submit="handleSuspensionDecision"
      :updateVisible="updateSuspensionDialogVisible"
    >
      <BusinessSuspensionDialog
        v-if="showSuspensionDialog && suspension"
        :model-value="suspensionDialogVisible"
        :suspension="suspension"
        :submitting="suspensionSubmitting"
        @update:model-value="updateSuspensionDialogVisible"
        @submit="handleSuspensionDecision"
      />
    </slot>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, onBeforeUnmount, getCurrentInstance, type CSSProperties } from 'vue'
import { ElInput, ElButton, ElTag, ElEmpty, ElAlert, ElIcon, ElTooltip, ElMessage } from 'element-plus'
import { Close, Document, Loading, Paperclip } from '@element-plus/icons-vue'
import { BusinessSuspensionDialog, ExecutionReportInline } from '@foggy/chat'
import { useNavigatorChat } from '../composables/useNavigatorChat'
import SkillFrameBlockView from './SkillFrameBlockView.vue'
import type {
  BusinessSuspensionDecisionPayload,
  BusinessSuspensionDialogModel,
  ChatMessage,
  ExecutionReportMarkdownLoader,
  NavigatorAttachmentKind,
  NavigatorAttachmentResult,
  NavigatorAction,
  NavigatorChatConfig,
  NavigatorChatMode,
  PendingNavigatorAttachment,
  TaskStatus,
  ToolExecutionBlock,
  UploadAttachmentHook,
} from '../types'
import MarkdownIt from 'markdown-it'

const md = new MarkdownIt({ html: false, linkify: true, breaks: true })

const props = withDefaults(defineProps<{
  /** Navigator 配置 */
  config?: NavigatorChatConfig
  /** Navigator Open API 基地址；未传 config 时可直接使用 */
  baseUrl?: string
  /** API Key（如果通过上游后端代理，则不需要） */
  apiKey?: string
  /** Agent ID；未传 config 时可直接使用 */
  agentId?: string
  /** 展示模式，默认 business */
  mode?: NavigatorChatMode
  /** 等价调试开关 */
  debugMode?: boolean
  /** 是否展示运行时事件 */
  showRuntimeEvents?: boolean
  /** 是否展示工具调用 */
  showToolCalls?: boolean
  /** 是否展示工具结果 */
  showToolResults?: boolean
  /** 完整执行报告 Markdown 加载器 */
  executionReportMarkdownLoader?: ExecutionReportMarkdownLoader
  /** 轮询间隔，兼容 poll-interval-ms 写法 */
  pollIntervalMs?: number
  /** 轮询间隔，兼容原 config 字段 */
  pollInterval?: number
  /** 超时时间 */
  timeout?: number
  /** 最大交互轮数 */
  maxTurns?: number
  /** 发送前附件上传 hook，由宿主调用自己的上传接口 */
  uploadAttachment?: UploadAttachmentHook
  /** 是否启用默认附件入口；默认在提供 uploadAttachment 时启用 */
  enableAttachments?: boolean
  /** 单条消息最多附件数 */
  maxAttachments?: number
  /** 单个附件最大字节数 */
  maxAttachmentSize?: number
  /** 可接受附件类型，支持 MIME、image/* 和 .pdf */
  acceptedAttachmentTypes?: string[]
  /** 自定义请求函数 */
  fetch?: (url: string, init: RequestInit) => Promise<Response>
  /** 标题 */
  title?: string
  /** 高度 */
  height?: string
  /** 空状态文本 */
  emptyText?: string
  /** 输入框占位符 */
  placeholder?: string
  /** 是否显示头部 */
  showHeader?: boolean
  /** 是否显示输入框 */
  showInput?: boolean
  /** 是否启用默认 suspension 弹窗 */
  showSuspensionDialog?: boolean
  /** suspension 弹窗是否可见 */
  suspensionDialogVisible?: boolean
  /** 当前展示的业务 suspension，必须由宿主/BFF 清洗后传入 */
  suspension?: BusinessSuspensionDialogModel | null
  /** suspension 决策提交中 */
  suspensionSubmitting?: boolean
  /** 加载中提示文本 */
  thinkingText?: string
}>(), {
  title: 'AI 助手',
  height: '500px',
  emptyText: '开始对话吧',
  placeholder: '输入消息...',
  showHeader: true,
  showInput: true,
  showSuspensionDialog: true,
  suspensionDialogVisible: false,
  suspension: null,
  suspensionSubmitting: false,
  thinkingText: 'AI 正在思考...',
})

const emit = defineEmits<{
  /** 消息发送时触发 */
  send: [content: string, attachments?: NavigatorAttachmentResult[]]
  /** 任务状态变化时触发 */
  statusChange: [status: TaskStatus | null]
  /** 收到 AI 回复时触发 */
  reply: [content: string, taskId: string]
  /** suspension 弹窗显隐变化 */
  'update:suspensionDialogVisible': [visible: boolean]
  /** 用户提交 suspension 决策，由宿主/BFF 转发给 Navigator Control Plane */
  suspensionDecision: [payload: BusinessSuspensionDecisionPayload]
  /** 用户点击业务动作按钮或链接 */
  action: [action: NavigatorAction]
}>()

const instance = getCurrentInstance()

function hasExplicitProp(name: string): boolean {
  const rawProps = instance?.vnode.props ?? {}
  const kebabName = name.replace(/[A-Z]/g, (match) => `-${match.toLowerCase()}`)
  return Object.prototype.hasOwnProperty.call(rawProps, name)
    || Object.prototype.hasOwnProperty.call(rawProps, kebabName)
}

const resolvedConfig = computed<NavigatorChatConfig>(() => ({
  ...(props.config ?? {
    baseUrl: props.baseUrl ?? '',
    agentId: props.agentId ?? '',
  }),
  ...(props.baseUrl ? { baseUrl: props.baseUrl } : {}),
  ...(props.apiKey ? { apiKey: props.apiKey } : {}),
  ...(props.agentId ? { agentId: props.agentId } : {}),
  ...(props.pollIntervalMs != null ? { pollInterval: props.pollIntervalMs } : {}),
  ...(props.pollInterval != null ? { pollInterval: props.pollInterval } : {}),
  ...(props.timeout != null ? { timeout: props.timeout } : {}),
  ...(props.maxTurns != null ? { maxTurns: props.maxTurns } : {}),
  ...(props.uploadAttachment ? { uploadAttachment: props.uploadAttachment } : {}),
  ...(hasExplicitProp('enableAttachments') ? { enableAttachments: props.enableAttachments } : {}),
  ...(props.maxAttachments != null ? { maxAttachments: props.maxAttachments } : {}),
  ...(props.maxAttachmentSize != null ? { maxAttachmentSize: props.maxAttachmentSize } : {}),
  ...(props.acceptedAttachmentTypes ? { acceptedAttachmentTypes: props.acceptedAttachmentTypes } : {}),
  ...(props.fetch ? { fetch: props.fetch } : {}),
  ...(props.executionReportMarkdownLoader ? { executionReportMarkdownLoader: props.executionReportMarkdownLoader } : {}),
  mode: props.mode ?? props.config?.mode ?? 'business',
  debugMode: hasExplicitProp('debugMode') ? props.debugMode : props.config?.debugMode,
  showRuntimeEvents: hasExplicitProp('showRuntimeEvents') ? props.showRuntimeEvents : props.config?.showRuntimeEvents,
  showToolCalls: hasExplicitProp('showToolCalls') ? props.showToolCalls : props.config?.showToolCalls,
  showToolResults: hasExplicitProp('showToolResults') ? props.showToolResults : props.config?.showToolResults,
}))

const chat = useNavigatorChat(resolvedConfig.value)
const inputText = ref('')
const messagesRef = ref<HTMLElement>()
const fileInputRef = ref<HTMLInputElement>()
const pendingAttachments = ref<PendingNavigatorAttachment[]>([])
const isUploadingAttachments = ref(false)
const isDragOver = ref(false)
const executionReportMarkdownLoader = computed(() => resolvedConfig.value.executionReportMarkdownLoader)

// 暴露 composable 给父组件
defineExpose({
  /** 核心 chat composable */
  chat,
  /** 编程式发送 */
  send: chat.send,
  /** 获取历史会话列表 */
  listSessions: chat.listSessions,
  /** 获取历史会话消息 */
  getSessionMessages: chat.getSessionMessages,
  /** 加载历史会话并继续 contextId */
  loadSession: chat.loadSession,
  /** 取消当前任务 */
  cancel: chat.cancel,
  /** 清空对话 */
  clear: chat.clear,
  /** 当前 contextId */
  contextId: chat.contextId,
})

const containerStyle = computed<CSSProperties>(() => ({
  height: props.height,
}))

const statusTagType = computed<'success' | 'danger' | 'info' | 'warning' | 'primary'>(() => {
  switch (chat.taskStatus.value) {
    case 'COMPLETED': return 'success'
    case 'FAILED': return 'danger'
    case 'CANCELED': return 'info'
    case 'WORKING': return 'warning'
    default: return 'primary'
  }
})

const statusLabel = computed(() => {
  switch (chat.taskStatus.value) {
    case 'SUBMITTED': return '已提交'
    case 'WORKING': return '执行中'
    case 'COMPLETED': return '已完成'
    case 'FAILED': return '失败'
    case 'CANCELED': return '已取消'
    case 'INPUT_REQUIRED': return '等待输入'
    default: return ''
  }
})

const attachmentsEnabled = computed(() => Boolean(resolvedConfig.value.uploadAttachment) && (resolvedConfig.value.enableAttachments ?? true))
const attachmentLimit = computed(() => resolvedConfig.value.maxAttachments ?? 6)
const attachmentSizeLimit = computed(() => resolvedConfig.value.maxAttachmentSize ?? 20 * 1024 * 1024)
const attachmentAcceptAttr = computed(() => (resolvedConfig.value.acceptedAttachmentTypes ?? []).join(','))
const canSend = computed(() =>
  !chat.isLoading.value
  && !isUploadingAttachments.value
  && (inputText.value.trim().length > 0 || pendingAttachments.value.length > 0)
)

function renderMarkdown(text: string): string {
  return md.render(text)
}

function processLabel(message: ChatMessage): string {
  if (message.processKind === 'skill_frame') return '技能执行'
  switch (message.messageType) {
    case 'STATE': return '运行状态'
    case 'TOOL_CALL': return '工具调用'
    case 'TOOL_RESULT': return '工具结果'
    default: return '过程消息'
  }
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

function formatJson(value: unknown): string {
  if (value == null || value === '') return '无'
  if (typeof value === 'string') return value
  return JSON.stringify(value, null, 2)
}

async function handleSend() {
  const text = inputText.value.trim()
  const hasAttachments = pendingAttachments.value.length > 0
  if ((!text && !hasAttachments) || chat.isLoading.value || isUploadingAttachments.value) return

  let uploadedAttachments: NavigatorAttachmentResult[] | undefined
  try {
    uploadedAttachments = hasAttachments ? await uploadPendingAttachments() : undefined
  } catch (e: unknown) {
    const message = e instanceof Error ? e.message : '附件上传失败'
    ElMessage.error(message)
    return
  }

  const content = text || '请查看附件。'
  inputText.value = ''
  clearPendingAttachments()
  emit('send', content, uploadedAttachments)
  chat.send(content, uploadedAttachments?.length ? { attachments: uploadedAttachments } : undefined)
}

function handleScroll() {
  // Reserved for future load-more
}

function openFileDialog() {
  if (!attachmentsEnabled.value || chat.isLoading.value || isUploadingAttachments.value) return
  fileInputRef.value?.click()
}

function handleFileInputChange(event: Event) {
  const input = event.target as HTMLInputElement
  addFiles(input.files)
  input.value = ''
}

function handlePaste(event: ClipboardEvent) {
  if (!attachmentsEnabled.value) return
  const files = Array.from(event.clipboardData?.files ?? [])
  if (files.length === 0) return
  event.preventDefault()
  addFiles(files)
}

function handleDragOver(event: DragEvent) {
  if (!attachmentsEnabled.value || !hasDraggedFiles(event)) return
  isDragOver.value = true
}

function handleDragLeave(event: DragEvent) {
  const current = event.currentTarget as HTMLElement | null
  const related = event.relatedTarget as Node | null
  if (!current || !related || !current.contains(related)) {
    isDragOver.value = false
  }
}

function handleDrop(event: DragEvent) {
  if (!attachmentsEnabled.value) return
  isDragOver.value = false
  addFiles(event.dataTransfer?.files)
}

function hasDraggedFiles(event: DragEvent): boolean {
  return Array.from(event.dataTransfer?.types ?? []).includes('Files')
}

function addFiles(fileList: FileList | File[] | null | undefined) {
  if (!attachmentsEnabled.value || !fileList) return
  const files = Array.from(fileList)
  if (files.length === 0) return

  const remaining = attachmentLimit.value - pendingAttachments.value.length
  if (remaining <= 0) {
    ElMessage.warning(`最多添加 ${attachmentLimit.value} 个附件`)
    return
  }

  const acceptedFiles = files.slice(0, remaining)
  if (files.length > remaining) {
    ElMessage.warning(`最多添加 ${attachmentLimit.value} 个附件，已忽略多余文件`)
  }

  for (const file of acceptedFiles) {
    const validationError = validateAttachmentFile(file)
    if (validationError) {
      ElMessage.warning(validationError)
      continue
    }
    pendingAttachments.value.push(createPendingAttachment(file))
  }
}

function validateAttachmentFile(file: File): string | null {
  if (file.size > attachmentSizeLimit.value) {
    return `${file.name || '附件'} 超过 ${formatFileSize(attachmentSizeLimit.value)}`
  }
  const accept = resolvedConfig.value.acceptedAttachmentTypes ?? []
  if (accept.length > 0 && !matchesAcceptedType(file, accept)) {
    return `${file.name || '附件'} 类型不支持`
  }
  return null
}

function matchesAcceptedType(file: File, accept: string[]): boolean {
  const name = file.name.toLowerCase()
  const type = file.type.toLowerCase()
  return accept.some((item) => {
    const normalized = item.trim().toLowerCase()
    if (!normalized) return false
    if (normalized.startsWith('.')) return name.endsWith(normalized)
    if (normalized.endsWith('/*')) return type.startsWith(normalized.slice(0, -1))
    return type === normalized
  })
}

function createPendingAttachment(file: File): PendingNavigatorAttachment {
  const kind = inferAttachmentKind(file)
  const isImage = kind === 'image'
  return {
    id: `pending-${Date.now()}-${Math.random().toString(36).slice(2)}`,
    file,
    name: file.name || defaultAttachmentName(kind),
    mimeType: file.type || inferMimeTypeFromName(file.name),
    size: file.size,
    kind,
    isImage,
    previewUrl: isImage ? URL.createObjectURL(file) : undefined,
    status: 'pending',
  }
}

async function uploadPendingAttachments(): Promise<NavigatorAttachmentResult[]> {
  const upload = resolvedConfig.value.uploadAttachment
  if (!upload) throw new Error('未配置附件上传接口')

  isUploadingAttachments.value = true
  const uploaded: NavigatorAttachmentResult[] = []
  let failedMessage: string | null = null

  try {
    for (const attachment of pendingAttachments.value) {
      if (attachment.status === 'uploaded' && attachment.uploaded) {
        uploaded.push(attachment.uploaded)
        continue
      }
      attachment.status = 'uploading'
      attachment.error = undefined
      try {
        const result = normalizeUploadResult(await upload(attachment.file), attachment)
        attachment.uploaded = result
        attachment.status = 'uploaded'
        uploaded.push(result)
      } catch (e: unknown) {
        const message = e instanceof Error ? e.message : '上传失败'
        attachment.status = 'error'
        attachment.error = message
        failedMessage = `${attachment.name} ${message}`
      }
    }
  } finally {
    isUploadingAttachments.value = false
  }

  if (failedMessage) {
    throw new Error(failedMessage)
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

function updateSuspensionDialogVisible(visible: boolean) {
  emit('update:suspensionDialogVisible', visible)
}

function handleSuspensionDecision(payload: BusinessSuspensionDecisionPayload) {
  emit('suspensionDecision', payload)
}

function handleAction(action: NavigatorAction) {
  emit('action', action)
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

function defaultAttachmentName(kind: NavigatorAttachmentKind): string {
  switch (kind) {
    case 'image': return '粘贴图片.png'
    case 'pdf': return '附件.pdf'
    default: return '附件'
  }
}

function attachmentKindLabel(kind: NavigatorAttachmentKind): string {
  switch (kind) {
    case 'image': return '图片'
    case 'pdf': return 'PDF'
    case 'text': return '文本'
    case 'spreadsheet': return '表格'
    case 'document': return '文档'
    case 'archive': return '压缩包'
    default: return '文件'
  }
}

function formatFileSize(size: number): string {
  if (!Number.isFinite(size) || size < 0) return ''
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / (1024 * 1024)).toFixed(1)} MB`
}

onBeforeUnmount(() => {
  clearPendingAttachments()
})

// Auto-scroll to bottom when new messages arrive
watch(
  () => chat.messages.value.length,
  () => {
    nextTick(() => {
      if (messagesRef.value) {
        messagesRef.value.scrollTop = messagesRef.value.scrollHeight
      }
    })
  }
)

// Emit status changes
watch(chat.taskStatus, (status) => {
  emit('statusChange', status)
})

// Emit replies
watch(
  () => chat.messages.value,
  (msgs) => {
    const last = msgs[msgs.length - 1]
    if (last?.role === 'assistant' && last.taskId && (last.terminal || last.messageType === 'RESULT')) {
      emit('reply', last.content, last.taskId)
    }
  },
  { deep: true }
)
</script>

<style scoped>
.navigator-chat {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--el-border-color-light, #e4e7ed);
  border-radius: 8px;
  overflow: hidden;
  background: var(--el-bg-color, #fff);
}

.nc-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--el-border-color-lighter, #ebeef5);
  background: var(--el-bg-color-page, #f5f7fa);
}

.nc-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary, #303133);
}

.nc-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.nc-messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.nc-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

.nc-message {
  display: flex;
}

.nc-message--user {
  justify-content: flex-end;
}

.nc-message--assistant,
.nc-message--system {
  justify-content: flex-start;
}

.nc-bubble {
  max-width: 80%;
}

.nc-content--user {
  background: var(--el-color-primary, #409eff);
  color: #fff;
  padding: 10px 14px;
  border-radius: 12px 12px 2px 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 14px;
}

.nc-message-attachments {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.nc-message-text + .nc-message-attachments {
  margin-top: 8px;
}

.nc-message-attachment {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 220px;
  padding: 5px 7px;
  border: 1px solid rgba(255, 255, 255, 0.42);
  border-radius: 6px;
  color: inherit;
  text-decoration: none;
  background: rgba(255, 255, 255, 0.14);
}

.nc-message-attachment-thumb {
  width: 24px;
  height: 24px;
  border-radius: 4px;
  object-fit: cover;
  background: rgba(255, 255, 255, 0.24);
}

.nc-message-attachment-icon {
  flex-shrink: 0;
}

.nc-message-attachment-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nc-message-attachment-size {
  flex-shrink: 0;
  opacity: 0.8;
  font-size: 12px;
}

.nc-content--assistant {
  background: var(--el-fill-color-light, #f4f4f5);
  color: var(--el-text-color-primary, #303133);
  padding: 10px 14px;
  border-radius: 12px 12px 12px 2px;
  line-height: 1.6;
  font-size: 14px;
}

.nc-content--assistant :deep(p) {
  margin: 0 0 8px;
}

.nc-content--assistant :deep(p:last-child) {
  margin-bottom: 0;
}

.nc-content--assistant :deep(pre) {
  background: var(--el-fill-color-darker, #e6e8eb);
  padding: 8px 12px;
  border-radius: 6px;
  overflow-x: auto;
  font-size: 13px;
  margin: 8px 0;
}

.nc-content--assistant :deep(code) {
  font-family: 'Fira Code', Consolas, monospace;
  font-size: 13px;
}

.nc-content--assistant :deep(a) {
  color: var(--el-color-primary, #409eff);
}

.nc-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.nc-content--system {
  width: 100%;
}

.nc-process {
  width: 100%;
  padding: 8px 10px;
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 6px;
  background: var(--el-fill-color-blank, #fff);
  color: var(--el-text-color-secondary, #909399);
  font-size: 12px;
}

.nc-process summary {
  cursor: pointer;
  user-select: none;
}

.nc-tool-execution summary {
  display: flex;
  align-items: center;
  gap: 8px;
}

.nc-tool-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  color: var(--el-text-color-regular, #606266);
  font-weight: 600;
}

.nc-tool-status {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 11px;
  background: var(--el-fill-color-light, #f4f4f5);
  color: var(--el-text-color-secondary, #909399);
}

.nc-tool-status--success {
  background: var(--el-color-success-light-9, #f0f9eb);
  color: var(--el-color-success, #67c23a);
}

.nc-tool-status--failed {
  background: var(--el-color-danger-light-9, #fef0f0);
  color: var(--el-color-danger, #f56c6c);
}

.nc-tool-status--running {
  background: var(--el-color-primary-light-9, #ecf5ff);
  color: var(--el-color-primary, #409eff);
}

.nc-tool-duration {
  flex-shrink: 0;
  color: var(--el-text-color-placeholder, #a8abb2);
}

.nc-tool-body {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.nc-tool-summary {
  color: var(--el-text-color-regular, #606266);
}

.nc-nested {
  border-top: 1px solid var(--el-border-color-extra-light, #f2f6fc);
  padding-top: 6px;
}

.nc-nested summary {
  font-weight: 500;
  color: var(--el-text-color-secondary, #909399);
}

.nc-process pre {
  margin: 8px 0 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: 'Fira Code', Consolas, monospace;
  font-size: 12px;
}

.nc-meta {
  margin-top: 6px;
  font-size: 11px;
  color: var(--el-text-color-secondary, #909399);
}

.nc-thinking {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  background: var(--el-fill-color-light, #f4f4f5);
  border-radius: 12px 12px 12px 2px;
  color: var(--el-text-color-secondary, #909399);
  font-size: 13px;
}

.nc-input-area {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid var(--el-border-color-lighter, #ebeef5);
  background: var(--el-bg-color, #fff);
}

.nc-input-area.is-drag-over {
  background: var(--el-color-primary-light-9, #ecf5ff);
}

.nc-input-row {
  width: 100%;
  display: flex;
  align-items: flex-end;
  gap: 8px;
}

.nc-input-row :deep(.el-textarea) {
  flex: 1;
  min-width: 0;
}

.nc-input-area :deep(.el-textarea__inner) {
  box-shadow: none;
  padding: 8px 12px;
}

.nc-file-input {
  display: none;
}

.nc-attachment-button {
  flex-shrink: 0;
  width: 32px;
  padding: 8px;
  margin-bottom: 2px;
}

.nc-pending-attachments {
  width: 100%;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  max-height: 116px;
  overflow-y: auto;
}

.nc-pending-attachment {
  display: flex;
  align-items: center;
  gap: 8px;
  width: min(240px, 100%);
  min-width: 0;
  padding: 6px 8px;
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 6px;
  background: var(--el-fill-color-blank, #fff);
}

.nc-pending-attachment--error {
  border-color: var(--el-color-danger-light-5, #fab6b6);
  background: var(--el-color-danger-light-9, #fef0f0);
}

.nc-pending-thumb,
.nc-pending-file-icon {
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  border-radius: 4px;
  object-fit: cover;
  background: var(--el-fill-color-light, #f4f4f5);
  color: var(--el-text-color-secondary, #909399);
}

.nc-pending-file-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
}

.nc-pending-info {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.nc-pending-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--el-text-color-primary, #303133);
  font-size: 12px;
  line-height: 1.2;
}

.nc-pending-meta,
.nc-pending-error {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  line-height: 1.2;
}

.nc-pending-meta {
  color: var(--el-text-color-secondary, #909399);
}

.nc-pending-error {
  color: var(--el-color-danger, #f56c6c);
}

.nc-pending-status {
  flex-shrink: 0;
  color: var(--el-color-primary, #409eff);
}

.nc-pending-remove {
  flex-shrink: 0;
  border: 0;
  padding: 2px;
  background: transparent;
  color: var(--el-text-color-secondary, #909399);
  cursor: pointer;
  line-height: 1;
}

.nc-pending-remove:hover {
  color: var(--el-color-danger, #f56c6c);
}

.nc-pending-remove:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.nc-input-actions {
  flex-shrink: 0;
  padding-bottom: 2px;
}
</style>
