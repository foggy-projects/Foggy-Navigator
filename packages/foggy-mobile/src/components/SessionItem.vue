<template>
  <view class="session-item" @tap="$emit('tap')">
    <view class="session-content">
      <view class="session-header">
        <text class="session-title">{{ session.taskName || '新会话' }}</text>
        <StatusBadge :status="session.status" />
      </view>
      <text class="session-time">{{ formatTime(session.updatedAt) }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import type { Session } from '@/api/types'
import StatusBadge from './StatusBadge.vue'

defineProps<{
  session: Session
}>()

defineEmits<{
  tap: []
}>()

function formatTime(dateStr: string): string {
  const date = new Date(dateStr)
  const now = new Date()
  const diff = now.getTime() - date.getTime()
  const minutes = Math.floor(diff / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes}分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}小时前`
  const days = Math.floor(hours / 24)
  if (days < 7) return `${days}天前`
  return `${date.getMonth() + 1}/${date.getDate()}`
}
</script>

<style scoped>
.session-item {
  background: #ffffff;
  padding: 28rpx 32rpx;
  border-bottom: 2rpx solid #f0f0f0;
}
.session-content {
  display: flex;
  flex-direction: column;
  gap: 12rpx;
}
.session-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.session-title {
  font-size: 30rpx;
  color: #303133;
  font-weight: 500;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-time {
  font-size: 24rpx;
  color: #c0c4cc;
}
</style>
