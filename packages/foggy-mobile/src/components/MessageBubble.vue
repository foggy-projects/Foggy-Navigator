<template>
  <view class="message-bubble" :class="[senderClass]">
    <!-- 用户消息 -->
    <view v-if="message.sender === 'user'" class="bubble user-bubble">
      <rich-text :nodes="message.content" />
    </view>

    <!-- 助手消息 -->
    <view v-else-if="message.sender === 'assistant'" class="bubble assistant-bubble">
      <rich-text :nodes="renderedContent" />
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
      <view v-if="isTaskCompleted" class="task-card" :class="taskStatusClass">
        <text class="task-card-title">{{ taskStatus === 'FAILED' ? '任务失败' : '任务完成' }}</text>
        <text class="task-card-desc">{{ message.content }}</text>
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
      <view v-else-if="message.error" class="error-block">
        <text class="error-text">{{ message.error }}</text>
      </view>
      <text v-else class="system-text">{{ message.content }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { AipMessageType } from '@foggy/chat-core'
import type { ChatMessage } from '@foggy/chat-core'
import ToolCallCard from './ToolCallCard.vue'
import PlanReviewCard from './PlanReviewCard.vue'
import PermissionRequestCard from './PermissionRequestCard.vue'
import UserQuestionCard from './UserQuestionCard.vue'
import { renderMarkdown } from '@/utils/markdown'

const props = defineProps<{
  message: ChatMessage
}>()

defineEmits<{
  (e: 'plan-respond', permissionId: string, decision: string, denyMessage?: string, planAction?: string): void
  (e: 'question-respond', permissionId: string, answers: Record<string, string>): void
  (e: 'permission-respond', permissionId: string, decision: string, scope: string): void
}>()

const senderClass = computed(() => `sender-${props.message.sender}`)

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
</script>

<style scoped>
.message-bubble {
  padding: 8rpx 24rpx;
}
.sender-user {
  display: flex;
  justify-content: flex-end;
}
.sender-assistant, .sender-tool, .sender-system {
  display: flex;
  justify-content: flex-start;
}
.bubble {
  max-width: 80%;
  padding: 20rpx 28rpx;
  border-radius: 20rpx;
  font-size: 28rpx;
  line-height: 1.6;
  word-break: break-all;
}
.user-bubble {
  background: #667eea;
  color: #ffffff;
  border-bottom-right-radius: 6rpx;
}
.assistant-bubble {
  background: #ffffff;
  color: #303133;
  border-bottom-left-radius: 6rpx;
  box-shadow: 0 2rpx 8rpx rgba(0, 0, 0, 0.06);
}
.tool-area {
  width: 85%;
}
.system-area {
  width: 100%;
  display: flex;
  justify-content: center;
}
.system-text {
  font-size: 24rpx;
  color: #909399;
  background: rgba(0, 0, 0, 0.04);
  padding: 8rpx 24rpx;
  border-radius: 20rpx;
}
.error-block {
  background: #fef0f0;
  border: 2rpx solid #fbc4c4;
  border-radius: 12rpx;
  padding: 16rpx 24rpx;
}
.error-text {
  font-size: 26rpx;
  color: #f56c6c;
}
.task-card {
  width: 90%;
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
}
</style>
