<template>
  <view class="message-bubble" :class="[senderClass]">
    <!-- 用户消息 -->
    <view v-if="message.sender === 'user'" class="copyable-group">
      <view class="bubble user-bubble" @longpress="handleCopy">
        <rich-text :nodes="message.content" />
      </view>
      <text v-if="copyable" class="copy-action" @tap.stop="handleCopy">{{ copyLabel }}</text>
    </view>

    <!-- 助手消息 -->
    <view v-else-if="message.sender === 'assistant'" class="copyable-group">
      <view class="bubble assistant-bubble" @longpress="handleCopy">
        <rich-text :nodes="renderedContent" />
      </view>
      <text v-if="copyable" class="copy-action" @tap.stop="handleCopy">{{ copyLabel }}</text>
    </view>

    <!-- 工具调用 -->
    <view v-else-if="message.sender === 'tool'" class="tool-area">
      <ToolCallCard
        :tool-name="message.toolName || '工具'"
        :command="message.content"
        :thought="message.thought"
        :output="message.toolOutput"
        :error="message.error"
      />
    </view>

    <!-- 系统消息 -->
    <view v-else-if="message.sender === 'system'" class="system-area">
      <view v-if="isTaskCompleted" class="copyable-group system-copy-group">
        <view class="task-card" :class="taskStatusClass" @longpress="handleCopy">
          <text class="task-card-title">{{ taskStatus === 'FAILED' ? '任务失败' : '任务完成' }}</text>
          <text class="task-card-desc">{{ message.content }}</text>
        </view>
        <text v-if="copyable" class="copy-action" @tap.stop="handleCopy">{{ copyLabel }}</text>
      </view>
      <PlanReviewCard
        v-else-if="isConfirmation && isPlanReview"
        :message="message"
        @plan-respond="(pid, decision, denyMsg, planAction) => $emit('plan-respond', pid, decision, denyMsg, planAction)"
      />
      <UserQuestionCard
        v-else-if="isConfirmation && hasQuestions"
        :message="message"
        @question-respond="(pid, answers) => $emit('question-respond', pid, answers)"
      />
      <PermissionRequestCard
        v-else-if="isConfirmation"
        :message="message"
        @permission-respond="(pid, decision, scope) => $emit('permission-respond', pid, decision, scope)"
      />
      <view v-else-if="message.error" class="copyable-group system-copy-group">
        <view class="error-block" @longpress="handleCopy">
          <text class="error-text">{{ message.error }}</text>
        </view>
        <text v-if="copyable" class="copy-action" @tap.stop="handleCopy">{{ copyLabel }}</text>
      </view>
      <view v-else class="copyable-group system-copy-group">
        <text class="system-text" @longpress="handleCopy">{{ message.content }}</text>
        <text v-if="copyable" class="copy-action" @tap.stop="handleCopy">{{ copyLabel }}</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { AipMessageType } from '@foggy/chat-core'
import type { ChatMessage } from '@foggy/chat-core'
import ToolCallCard from './ToolCallCard.vue'
import PlanReviewCard from './PlanReviewCard.vue'
import PermissionRequestCard from './PermissionRequestCard.vue'
import UserQuestionCard from './UserQuestionCard.vue'
import { renderMarkdown } from '@/utils/markdown'
import { copyTextToClipboard } from '@/utils/clipboard'

const props = defineProps<{
  message: ChatMessage
}>()

defineEmits<{
  (e: 'plan-respond', permissionId: string, decision: string, denyMessage?: string, planAction?: string): void
  (e: 'question-respond', permissionId: string, answers: Record<string, string>): void
  (e: 'permission-respond', permissionId: string, decision: string, scope: string): void
}>()

const senderClass = computed(() => `sender-${props.message.sender}`)
const isCopied = ref(false)

const renderedContent = computed(() => {
  if (!props.message.content) return ''
  return renderMarkdown(props.message.content)
})

const isTaskCompleted = computed(() => props.message.type === AipMessageType.TASK_COMPLETED)
const isConfirmation = computed(() => props.message.type === AipMessageType.CONFIRMATION_REQUEST)
const isPlanReview = computed(() => !!props.message.planReview)
const hasQuestions = computed(() => (props.message.questions?.length ?? 0) > 0)

const taskStatus = computed(() => {
  const raw = props.message.raw as Record<string, string> | undefined
  return raw?.status || 'COMPLETED'
})

const taskStatusClass = computed(() =>
  taskStatus.value === 'FAILED' ? 'task-card-failed' : 'task-card-success',
)

const copyText = computed(() => {
  if (props.message.sender === 'tool') return ''
  if (isConfirmation.value) return ''
  return (props.message.error || props.message.content || '').trim()
})

const copyable = computed(() => copyText.value.length > 0)
const copyLabel = computed(() => (isCopied.value ? '已复制' : '复制'))

let copyTimer: ReturnType<typeof setTimeout> | null = null

async function handleCopy() {
  if (!copyable.value) return

  try {
    await copyTextToClipboard(copyText.value)
    isCopied.value = true
    uni.showToast({ title: '已复制', icon: 'none' })
    if (copyTimer) clearTimeout(copyTimer)
    copyTimer = setTimeout(() => {
      isCopied.value = false
      copyTimer = null
    }, 1500)
  } catch (error) {
    console.error('Failed to copy message:', error)
    uni.showToast({ title: '复制失败', icon: 'none' })
  }
}

onBeforeUnmount(() => {
  if (copyTimer) {
    clearTimeout(copyTimer)
    copyTimer = null
  }
})
</script>

<style scoped>
.message-bubble {
  padding: 8rpx 24rpx;
  box-sizing: border-box;
  max-width: 100%;
}
.sender-user {
  display: flex;
  flex-direction: row;
  justify-content: flex-end;
}
.sender-assistant, .sender-tool, .sender-system {
  display: flex;
  flex-direction: row;
  justify-content: flex-start;
}
.bubble {
  max-width: 100%;
  min-width: 0;
  box-sizing: border-box;
  padding: 20rpx 28rpx;
  border-radius: 20rpx;
  font-size: 28rpx;
  line-height: 1.6;
  overflow-wrap: anywhere;
  word-break: break-word;
}
.copyable-group {
  display: flex;
  flex-direction: column;
  gap: 10rpx;
  max-width: 80%;
  min-width: 0;
}
.sender-user .copyable-group {
  align-items: flex-end;
}
.sender-assistant .copyable-group {
  align-items: flex-start;
}
.user-bubble {
  background: #667eea;
  color: #ffffff;
  border-bottom-right-radius: 6rpx;
}
.assistant-bubble {
  background-color: #ffffff;
  color: #303133;
  border-bottom-left-radius: 6rpx;
  border: 1rpx solid #e8e8e8;
}
.tool-area {
  width: 85%;
  max-width: 85%;
  min-width: 0;
}
.system-area {
  width: 100%;
  display: flex;
  justify-content: center;
}
.system-copy-group {
  align-items: center;
  max-width: 90%;
}
.system-text {
  font-size: 24rpx;
  color: #909399;
  background: rgba(0, 0, 0, 0.04);
  padding: 8rpx 24rpx;
  border-radius: 20rpx;
  max-width: 100%;
  box-sizing: border-box;
  overflow-wrap: anywhere;
  word-break: break-word;
}
.copy-action {
  font-size: 22rpx;
  line-height: 1;
  color: #667eea;
  background: rgba(102, 126, 234, 0.12);
  border-radius: 999rpx;
  padding: 10rpx 18rpx;
  align-self: flex-end;
}
.system-copy-group .copy-action {
  align-self: center;
}
.error-block {
  background: #fef0f0;
  border: 2rpx solid #fbc4c4;
  border-radius: 12rpx;
  padding: 16rpx 24rpx;
  max-width: 100%;
  box-sizing: border-box;
}
.error-text {
  font-size: 26rpx;
  color: #f56c6c;
  overflow-wrap: anywhere;
  word-break: break-word;
}
.task-card {
  width: 90%;
  box-sizing: border-box;
  border-radius: 16rpx;
  padding: 24rpx;
}
.task-card-success {
  background: #f0f9eb;
  border: 2rpx solid #c2e7b0;
}
.task-card-failed {
  background: #fef0f0;
  border: 2rpx solid #fbc4c4;
}
.task-card-title {
  font-size: 28rpx;
  font-weight: 600;
  margin-bottom: 8rpx;
}
.task-card-success .task-card-title { color: #67c23a; }
.task-card-failed .task-card-title { color: #f56c6c; }
.task-card-desc {
  font-size: 26rpx;
  color: #606266;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.bubble :deep(uni-rich-text),
.bubble :deep(.uni-rich-text),
.bubble :deep(p),
.bubble :deep(ol),
.bubble :deep(ul),
.bubble :deep(li),
.bubble :deep(pre),
.bubble :deep(code),
.bubble :deep(a) {
  max-width: 100%;
  overflow-wrap: anywhere;
  word-break: break-word;
  white-space: pre-wrap;
}
</style>
