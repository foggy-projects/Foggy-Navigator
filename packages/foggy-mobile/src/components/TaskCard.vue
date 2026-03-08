<template>
  <view class="task-card" @tap="$emit('tap')">
    <view class="task-header">
      <StatusBadge :status="task.status" :show-label="true" />
      <text v-if="task.model" class="task-model">{{ task.model }}</text>
    </view>
    <text class="task-prompt">{{ task.prompt }}</text>
    <view class="task-footer">
      <text class="task-time">{{ shortDateTime(task.createdAt) }}</text>
      <text v-if="task.costUsd != null" class="task-cost">${{ task.costUsd.toFixed(4) }}</text>
      <text v-if="task.durationMs != null" class="task-duration">{{ formatDuration(task.durationMs) }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import type { ClaudeTask } from '@/api/types'
import StatusBadge from './StatusBadge.vue'
import { shortDateTime, formatDuration } from '@/utils/time'

defineProps<{
  task: ClaudeTask
}>()

defineEmits<{
  tap: []
}>()

</script>

<style scoped>
.task-card {
  background-color: #ffffff;
  border-radius: 16rpx;
  padding: 24rpx 28rpx;
  margin-bottom: 16rpx;
  border: 1rpx solid #e8e8e8;
}
.task-header {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12rpx;
}
.task-model {
  font-size: 22rpx;
  color: #909399;
  background-color: #f0f0f0;
  padding: 4rpx 12rpx;
  border-radius: 8rpx;
}
.task-prompt {
  font-size: 28rpx;
  color: #303133;
  line-height: 1.5;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.task-footer {
  display: flex;
  flex-direction: row;
  align-items: center;
  margin-top: 16rpx;
}
.task-time, .task-cost, .task-duration {
  font-size: 24rpx;
  color: #c0c4cc;
  margin-right: 20rpx;
}
.task-cost { color: #e6a23c; }
</style>
