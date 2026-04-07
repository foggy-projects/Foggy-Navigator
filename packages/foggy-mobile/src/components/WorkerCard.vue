<template>
  <view class="worker-card" @tap="$emit('tap')">
    <view class="worker-header">
      <text class="worker-name">{{ worker.name }}</text>
      <view class="header-actions">
        <view class="health-btn" @tap.stop="$emit('health-check')">
          <text class="health-btn-text">检测</text>
        </view>
        <StatusBadge :status="worker.status" :show-label="true" />
      </view>
    </view>
    <view class="worker-info">
      <text v-if="worker.hostname" class="worker-hostname">{{ worker.hostname }}</text>
      <text v-if="worker.workerVersion" class="worker-version">v{{ worker.workerVersion }}</text>
    </view>
    <view v-if="worker.lastHeartbeat" class="worker-heartbeat">
      <text class="heartbeat-text">上次心跳: {{ relativeTimeWithAbsolute(worker.lastHeartbeat) }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import type { ClaudeWorker } from '@/api/types'
import StatusBadge from './StatusBadge.vue'
import { relativeTimeWithAbsolute } from '@/utils/time'

defineProps<{
  worker: ClaudeWorker
}>()

defineEmits<{
  tap: []
  'health-check': []
}>()

</script>

<style scoped>
.worker-card {
  background-color: #ffffff;
  border-radius: 16rpx;
  padding: 28rpx 32rpx;
  margin-bottom: 20rpx;
  border: 1rpx solid #e8e8e8;
}
.worker-header {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16rpx;
}
.worker-name {
  font-size: 32rpx;
  color: #303133;
  font-weight: 600;
  flex: 1;
}
.header-actions {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 16rpx;
}
.health-btn {
  padding: 6rpx 20rpx;
  background: #ecf5ff;
  border-radius: 8rpx;
  border: 1rpx solid #d9ecff;
}
.health-btn-text {
  font-size: 22rpx;
  color: #409eff;
}
.worker-info {
  display: flex;
  flex-direction: row;
  align-items: center;
  margin-bottom: 8rpx;
}
.worker-hostname {
  font-size: 26rpx;
  color: #606266;
  margin-right: 16rpx;
}
.worker-version {
  font-size: 24rpx;
  color: #909399;
  background-color: #f0f0f0;
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
