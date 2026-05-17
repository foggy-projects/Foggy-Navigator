<template>
  <div class="navigator-chat" :style="containerStyle">
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
            {{ msg.content }}
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
            />
            <div v-if="msg.durationMs || msg.costUsd" class="nc-meta">
              <span v-if="msg.durationMs">{{ (msg.durationMs / 1000).toFixed(1) }}s</span>
              <span v-if="msg.costUsd"> · ${{ msg.costUsd.toFixed(4) }}</span>
            </div>
          </div>
          <div v-else class="nc-content nc-content--system">
            <SkillFrameBlockView v-if="msg.skillFrame" :frame="msg.skillFrame" />
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
    <div v-if="showInput" class="nc-input-area">
      <el-input
        v-model="inputText"
        type="textarea"
        :autosize="{ minRows: 1, maxRows: 4 }"
        :placeholder="placeholder"
        :disabled="chat.isLoading.value"
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
          :disabled="!inputText.trim()"
          @click="handleSend"
        >
          发送
        </el-button>
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
import { ref, computed, watch, nextTick, type CSSProperties } from 'vue'
import { ElInput, ElButton, ElTag, ElEmpty, ElAlert, ElIcon } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { BusinessSuspensionDialog, ExecutionReportInline } from '@foggy/chat'
import { useNavigatorChat } from '../composables/useNavigatorChat'
import SkillFrameBlockView from './SkillFrameBlockView.vue'
import type {
  BusinessSuspensionDecisionPayload,
  BusinessSuspensionDialogModel,
  ChatMessage,
  NavigatorAction,
  NavigatorChatConfig,
  NavigatorChatMode,
  TaskStatus,
  ToolExecutionBlock,
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
  /** 轮询间隔，兼容 poll-interval-ms 写法 */
  pollIntervalMs?: number
  /** 轮询间隔，兼容原 config 字段 */
  pollInterval?: number
  /** 超时时间 */
  timeout?: number
  /** 最大交互轮数 */
  maxTurns?: number
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
  send: [content: string]
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
  ...(props.fetch ? { fetch: props.fetch } : {}),
  mode: props.mode ?? props.config?.mode ?? 'business',
  debugMode: props.debugMode ?? props.config?.debugMode,
  showRuntimeEvents: props.showRuntimeEvents ?? props.config?.showRuntimeEvents,
  showToolCalls: props.showToolCalls ?? props.config?.showToolCalls,
  showToolResults: props.showToolResults ?? props.config?.showToolResults,
}))

const chat = useNavigatorChat(resolvedConfig.value)
const inputText = ref('')
const messagesRef = ref<HTMLElement>()

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

function handleSend() {
  const content = inputText.value.trim()
  if (!content || chat.isLoading.value) return
  inputText.value = ''
  emit('send', content)
  chat.send(content)
}

function handleScroll() {
  // Reserved for future load-more
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
  align-items: flex-end;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid var(--el-border-color-lighter, #ebeef5);
  background: var(--el-bg-color, #fff);
}

.nc-input-area :deep(.el-textarea__inner) {
  box-shadow: none;
  padding: 8px 12px;
}

.nc-input-actions {
  flex-shrink: 0;
  padding-bottom: 2px;
}
</style>
