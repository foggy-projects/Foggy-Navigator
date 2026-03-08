<template>
  <view class="session-item" @tap="$emit('tap')">
    <view class="session-content">
      <view class="session-header">
        <text class="session-title">{{ session.taskName || '新会话' }}</text>
        <StatusBadge :status="session.status" />
      </view>
      <text class="session-time">{{ relativeTime(session.updatedAt) }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import type { Session } from '@/api/types'
import StatusBadge from './StatusBadge.vue'
import { relativeTime } from '@/utils/time'

defineProps<{
  session: Session
}>()

defineEmits<{
  tap: []
}>()

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
}
.session-header {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12rpx;
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
