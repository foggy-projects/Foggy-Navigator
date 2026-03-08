<template>
  <view class="worker-card" @tap="$emit('tap')">
    <view class="worker-header">
      <text class="worker-name">{{ worker.name }}</text>
      <StatusBadge :status="worker.status" :show-label="true" />
    </view>
    <view class="worker-info">
      <text v-if="worker.hostname" class="worker-hostname">{{ worker.hostname }}</text>
      <text v-if="worker.workerVersion" class="worker-version">v{{ worker.workerVersion }}</text>
    </view>
    <view v-if="worker.lastHeartbeat" class="worker-heartbeat">
      <text class="heartbeat-text">上次心跳: {{ formatTime(worker.lastHeartbeat) }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import type { ClaudeWorker } from '@/api/types'
import StatusBadge from './StatusBadge.vue'

defineProps<{
  worker: ClaudeWorker
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
  return `${date.getMonth() + 1}/${date.getDate()} ${date.getHours()}:${String(date.getMinutes()).padStart(2, '0')}`
}
</script>

<style scoped>
.worker-card {
  background: #ffffff;
  border-radius: 16rpx;
  padding: 28rpx 32rpx;
  margin-bottom: 20rpx;
  box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, 0.06);
}
.worker-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16rpx;
}
.worker-name {
  font-size: 32rpx;
  color: #303133;
  font-weight: 600;
}
.worker-info {
  display: flex;
  gap: 16rpx;
  margin-bottom: 8rpx;
}
.worker-hostname {
  font-size: 26rpx;
  color: #606266;
}
.worker-version {
  font-size: 24rpx;
  color: #909399;
  background: #f0f0f0;
  padding: 2rpx 12rpx;
  border-radius: 8rpx;
}
.worker-heartbeat {
  margin-top: 8rpx;
}
.heartbeat-text {
  font-size: 24rpx;
  color: #c0c4cc;
}
</style>
