<template>
  <view :class="['permission-card', statusClass]">
    <view class="card-header">
      <text class="card-icon">{{ statusIcon }}</text>
      <text class="card-title">{{ toolLabel }}</text>
      <text :class="['status-badge', statusClass]">{{ statusLabel }}</text>
    </view>
    <view v-if="isPending" class="card-actions">
      <button class="action-btn allow-btn" @tap="handleRespond('allow', 'once')">允许</button>
      <button class="action-btn allow-session-btn" @tap="handleRespond('allow', 'session')">本次会话</button>
      <button class="action-btn allow-always-btn" @tap="handleRespond('allow', 'always')">始终允许</button>
      <button class="action-btn deny-btn" @tap="handleRespond('deny', 'once')">拒绝</button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ChatMessage } from '@foggy/chat-core'

const props = defineProps<{
  message: ChatMessage
}>()

const emit = defineEmits<{
  (e: 'permission-respond', permissionId: string, decision: string, scope: string): void
}>()

const isPending = computed(() => props.message.permissionStatus === 'pending')

const toolLabel = computed(() => {
  const name = props.message.toolName || 'Tool'
  return `权限: ${name}`
})

const statusClass = computed(() => {
  switch (props.message.permissionStatus) {
    case 'approved': return 'approved'
    case 'denied': return 'denied'
    default: return 'pending'
  }
})

const statusLabel = computed(() => {
  switch (props.message.permissionStatus) {
    case 'approved': return '已允许'
    case 'denied': return '已拒绝'
    default: return '待处理'
  }
})

const statusIcon = computed(() => {
  switch (props.message.permissionStatus) {
    case 'approved': return '\u2705'
    case 'denied': return '\u274C'
    default: return '\uD83D\uDD12'
  }
})

function handleRespond(decision: string, scope: string) {
  if (!props.message.permissionId) return
  emit('permission-respond', props.message.permissionId, decision, scope)
}
</script>

<style scoped>
.permission-card {
  width: 90%;
  max-width: 90%;
  box-sizing: border-box;
  margin: 8rpx auto;
  padding: 24rpx;
  border-radius: 16rpx;
  border-left: 8rpx solid #e6a23c;
  background: #fdf6ec;
}
.permission-card.approved { border-left-color: #67c23a; background: #f0f9eb; }
.permission-card.denied { border-left-color: #f56c6c; background: #fef0f0; }

.card-header { display: flex; flex-direction: row; align-items: center; }
.card-icon { font-size: 32rpx; margin-right: 12rpx; }
.card-title { font-size: 26rpx; font-weight: 600; color: #303133; flex: 1; min-width: 0; overflow-wrap: anywhere; word-break: break-word; }
.status-badge { font-size: 22rpx; padding: 4rpx 16rpx; border-radius: 20rpx; }
.status-badge.pending { background-color: #faecd8; color: #e6a23c; }
.status-badge.approved { background-color: #e1f3d8; color: #67c23a; }
.status-badge.denied { background-color: #fde2e2; color: #f56c6c; }

.card-actions {
  margin-top: 20rpx;
  display: flex;
  flex-direction: row;
  flex-wrap: wrap;
}
.action-btn {
  width: 48%;
  height: 68rpx;
  font-size: 24rpx;
  border-radius: 12rpx;
  border: none;
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: center;
  margin-right: 2%;
  margin-bottom: 12rpx;
}
.allow-btn { background-color: #67c23a; color: #ffffff; }
.allow-session-btn { background-color: #409eff; color: #ffffff; }
.allow-always-btn { background-color: #e6a23c; color: #ffffff; }
.deny-btn { background-color: #ffffff; color: #f56c6c; border: 2rpx solid #f56c6c; }
</style>
