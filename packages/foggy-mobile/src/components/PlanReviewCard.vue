<template>
  <view :class="['plan-review-card', statusClass]">
    <view class="card-header">
      <text class="card-icon">{{ statusIcon }}</text>
      <text class="card-title">Plan Mode</text>
      <text :class="['status-badge', statusClass]">{{ statusLabel }}</text>
    </view>
    <view class="card-body">
      <text class="plan-hint">
        {{ isPending ? 'Claude 已完成方案设计，请选择执行方式：' : resolvedHint }}
      </text>
      <!-- allowedPrompts list -->
      <view v-if="isPending && allowedPrompts.length" class="prompts-list">
        <view v-for="(p, i) in allowedPrompts" :key="i" class="prompt-item">
          <text class="prompt-tool">{{ p.tool }}</text>
          <text class="prompt-text">{{ p.prompt }}</text>
        </view>
      </view>
      <!-- 4 action buttons -->
      <view v-if="isPending" class="plan-options">
        <button class="option-btn bypass-btn" @tap="handleApprove('clearAndBypass')">
          清空上下文，跳过权限执行
        </button>
        <button class="option-btn bypass-btn" @tap="handleApprove('bypass')">
          跳过权限执行
        </button>
        <button class="option-btn approve-btn" @tap="handleApprove('acceptEdits')">
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

const statusClass = computed(() => {
  switch (props.message.permissionStatus) {
    case 'approved': return 'approved'
    case 'denied': return 'denied'
    default: return 'pending'
  }
})

const statusLabel = computed(() => {
  switch (props.message.permissionStatus) {
    case 'approved': return 'Approved'
    case 'denied': return 'Rejected'
    default: return 'Awaiting review'
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

.card-header { display: flex; align-items: center; gap: 12rpx; }
.card-icon { font-size: 32rpx; }
.card-title { font-size: 28rpx; font-weight: 600; color: #303133; flex: 1; }
.status-badge { font-size: 22rpx; padding: 4rpx 16rpx; border-radius: 20rpx; }
.status-badge.pending { background: #e8daef; color: #9b59b6; }
.status-badge.approved { background: #e1f3d8; color: #67c23a; }
.status-badge.denied { background: #fde2e2; color: #f56c6c; }

.card-body { margin-top: 16rpx; }
.plan-hint { font-size: 26rpx; color: #606266; }

.prompts-list { margin-top: 16rpx; padding: 16rpx; background: rgba(155, 89, 182, 0.06); border-radius: 8rpx; }
.prompt-item { display: flex; align-items: baseline; gap: 12rpx; margin-bottom: 8rpx; }
.prompt-tool { font-size: 22rpx; background: #e8daef; padding: 4rpx 10rpx; border-radius: 6rpx; color: #9b59b6; }
.prompt-text { font-size: 24rpx; color: #606266; }

.plan-options { margin-top: 20rpx; display: flex; flex-direction: column; gap: 12rpx; }
.option-btn {
  width: 100%;
  height: 72rpx;
  font-size: 26rpx;
  border-radius: 12rpx;
  border: none;
  display: flex;
  align-items: center;
  justify-content: center;
}
.bypass-btn { background: #f5eef8; color: #9b59b6; border: 2rpx solid #d2b4de; }
.approve-btn { background: #9b59b6; color: #fff; }

.reject-wrap { margin-top: 16rpx; display: flex; gap: 12rpx; align-items: center; }
.reject-input {
  flex: 1;
  height: 64rpx;
  padding: 0 20rpx;
  font-size: 26rpx;
  background: #fff;
  border: 2rpx solid #dcdfe6;
  border-radius: 12rpx;
}
.reject-btn {
  width: 160rpx;
  height: 64rpx;
  font-size: 24rpx;
  background: #fff;
  color: #f56c6c;
  border: 2rpx solid #f56c6c;
  border-radius: 12rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
</style>
