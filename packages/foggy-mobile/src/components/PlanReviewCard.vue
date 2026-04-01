<template>
  <view :class="['plan-review-card', statusClass]">
    <view class="card-header">
      <text class="card-icon">{{ statusIcon }}</text>
      <text class="card-title">方案审查</text>
      <text :class="['status-badge', statusClass]">{{ statusLabel }}</text>
    </view>
    <view class="card-body">
      <text class="plan-hint">
        {{ isPending ? 'Claude 已完成方案设计，请选择执行方式：' : resolvedHint }}
      </text>
      <!-- plan content (plain text with pre-wrap) -->
      <view v-if="planContent" class="plan-content-wrap">
        <text class="plan-content">{{ planContent }}</text>
      </view>
      <!-- allowedPrompts list -->
      <view v-if="isPending && allowedPrompts.length" class="prompts-list">
        <view v-for="(p, i) in allowedPrompts" :key="i" class="prompt-item">
          <text class="prompt-tool">{{ p.tool }}</text>
          <text class="prompt-text">{{ p.prompt }}</text>
        </view>
      </view>
      <!-- 4 action buttons -->
      <view v-if="isPending" class="plan-options">
        <button class="option-btn approve-btn" @tap="handleApprove('bypass')">
          跳过权限执行
        </button>
        <button class="option-btn bypass-btn" @tap="handleApprove('acceptEdits')">
          手动审批编辑
        </button>
      </view>
      <!-- Reject with feedback -->
      <view v-if="isPending" class="reject-wrap">
        <input
          v-model="rejectReason"
          class="reject-input"
          placeholder="修改方案（输入反馈）"
          confirm-type="send"
          @confirm="handleReject"
        />
        <button class="reject-btn" @tap="handleReject">修改方案</button>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ChatMessage, AllowedPrompt } from '@foggy/chat-core'

const props = defineProps<{
  message: ChatMessage
}>()

const emit = defineEmits<{
  (e: 'plan-respond', permissionId: string, decision: string, denyMessage?: string, planAction?: string): void
}>()

const rejectReason = ref('')
const isPending = computed(() => props.message.permissionStatus === 'pending')

const allowedPrompts = computed<AllowedPrompt[]>(() =>
  props.message.allowedPrompts || [],
)

const planContent = computed(() => props.message.plan || '')

const statusClass = computed(() => {
  switch (props.message.permissionStatus) {
    case 'approved': return 'approved'
    case 'denied': return 'denied'
    default: return 'pending'
  }
})

const statusLabel = computed(() => {
  switch (props.message.permissionStatus) {
    case 'approved': return '已批准'
    case 'denied': return '已拒绝'
    default: return '待审批'
  }
})

const statusIcon = computed(() => {
  switch (props.message.permissionStatus) {
    case 'approved': return '\u2705'
    case 'denied': return '\u274C'
    default: return '\uD83D\uDCCB'
  }
})

const resolvedHint = computed(() => {
  return props.message.permissionStatus === 'approved'
    ? '方案已批准，正在执行...'
    : '方案已拒绝，Claude 将重新规划。'
})

function handleApprove(planAction: string) {
  if (!props.message.permissionId) return
  emit('plan-respond', props.message.permissionId, 'allow', undefined, planAction)
}

function handleReject() {
  if (!props.message.permissionId) return
  emit('plan-respond', props.message.permissionId, 'deny', rejectReason.value.trim() || 'Plan rejected by user')
}
</script>

<style scoped>
.plan-review-card {
  width: 90%;
  margin: 8rpx auto;
  padding: 24rpx;
  border-radius: 16rpx;
  border-left: 8rpx solid #9b59b6;
  background: #f5eef8;
}
.plan-review-card.approved { border-left-color: #67c23a; background: #f0f9eb; }
.plan-review-card.denied { border-left-color: #f56c6c; background: #fef0f0; }

.card-header { display: flex; flex-direction: row; align-items: center; }
.card-icon { font-size: 32rpx; margin-right: 12rpx; }
.card-title { font-size: 28rpx; font-weight: 600; color: #303133; flex: 1; }
.status-badge { font-size: 22rpx; padding: 4rpx 16rpx; border-radius: 20rpx; }
.status-badge.pending { background-color: #e8daef; color: #9b59b6; }
.status-badge.approved { background-color: #e1f3d8; color: #67c23a; }
.status-badge.denied { background-color: #fde2e2; color: #f56c6c; }

.card-body { margin-top: 16rpx; }
.plan-hint { font-size: 26rpx; color: #606266; }

.plan-content-wrap {
  margin-top: 16rpx;
  max-height: 480rpx;
  overflow-y: auto;
  padding: 16rpx 20rpx;
  background-color: #ffffff;
  border: 2rpx solid #e8daef;
  border-radius: 12rpx;
}

.plan-content {
  font-size: 24rpx;
  line-height: 1.6;
  color: #303133;
  white-space: pre-wrap;
  word-break: break-word;
}

.prompts-list { margin-top: 16rpx; padding: 16rpx; background-color: rgba(155, 89, 182, 0.06); border-radius: 8rpx; }
.prompt-item { display: flex; flex-direction: row; align-items: baseline; margin-bottom: 8rpx; }
.prompt-tool { font-size: 22rpx; background-color: #e8daef; padding: 4rpx 10rpx; border-radius: 6rpx; color: #9b59b6; margin-right: 12rpx; }
.prompt-text { font-size: 24rpx; color: #606266; }

.plan-options { margin-top: 20rpx; display: flex; flex-direction: column; }
.option-btn {
  width: 100%;
  height: 72rpx;
  font-size: 26rpx;
  border-radius: 12rpx;
  border: none;
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: center;
  margin-bottom: 12rpx;
}
.bypass-btn { background-color: #f5eef8; color: #9b59b6; border: 2rpx solid #d2b4de; }
.approve-btn { background-color: #9b59b6; color: #ffffff; }

.reject-wrap { margin-top: 16rpx; display: flex; flex-direction: row; align-items: center; }
.reject-input {
  flex: 1;
  height: 64rpx;
  padding: 0 20rpx;
  font-size: 26rpx;
  background-color: #ffffff;
  border: 2rpx solid #dcdfe6;
  border-radius: 12rpx;
  margin-right: 12rpx;
}
.reject-btn {
  width: 160rpx;
  height: 64rpx;
  font-size: 24rpx;
  background-color: #ffffff;
  color: #f56c6c;
  border: 2rpx solid #f56c6c;
  border-radius: 12rpx;
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
</style>
