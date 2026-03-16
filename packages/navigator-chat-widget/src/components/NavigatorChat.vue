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
            <div v-html="renderMarkdown(msg.content)" />
            <div v-if="msg.durationMs || msg.costUsd" class="nc-meta">
              <span v-if="msg.durationMs">{{ (msg.durationMs / 1000).toFixed(1) }}s</span>
              <span v-if="msg.costUsd"> · ${{ msg.costUsd.toFixed(4) }}</span>
            </div>
          </div>
          <div v-else class="nc-content nc-content--system">
            <el-alert :title="msg.content" :type="msg.error ? 'error' : 'info'" :closable="false" show-icon />
          </div>
        </div>
      </div>

      <!-- Loading indicator -->
      <div v-if="chat.isLoading.value" class="nc-message nc-message--assistant">
        <div class="nc-bubble">
          <div class="nc-thinking">
            <el-icon class="is-loading"><Loading /></el-icon>
            <span>{{ thinkingText }}</span>
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
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, type CSSProperties } from 'vue'
import { ElInput, ElButton, ElTag, ElEmpty, ElAlert, ElIcon } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { useNavigatorChat } from '../composables/useNavigatorChat'
import type { NavigatorChatConfig, TaskStatus } from '../types'
import MarkdownIt from 'markdown-it'

const md = new MarkdownIt({ html: false, linkify: true, breaks: true })

const props = withDefaults(defineProps<{
  /** Navigator 配置 */
  config: NavigatorChatConfig
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
  /** 加载中提示文本 */
  thinkingText?: string
}>(), {
  title: 'AI 助手',
  height: '500px',
  emptyText: '开始对话吧',
  placeholder: '输入消息...',
  showHeader: true,
  showInput: true,
  thinkingText: 'AI 正在思考...',
})

const emit = defineEmits<{
  /** 消息发送时触发 */
  send: [content: string]
  /** 任务状态变化时触发 */
  statusChange: [status: TaskStatus | null]
  /** 收到 AI 回复时触发 */
  reply: [content: string, taskId: string]
}>()

const chat = useNavigatorChat(props.config)
const inputText = ref('')
const messagesRef = ref<HTMLElement>()

// 暴露 composable 给父组件
defineExpose({
  /** 核心 chat composable */
  chat,
  /** 编程式发送 */
  send: chat.send,
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
    if (last?.role === 'assistant' && last.taskId) {
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

.nc-content--system {
  width: 100%;
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
