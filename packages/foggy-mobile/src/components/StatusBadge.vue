<template>
  <view class="status-badge" :class="statusClass">
    <view class="status-dot" />
    <text v-if="showLabel" class="status-label">{{ label }}</text>
  </view>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  status: string
  showLabel?: boolean
}>(), {
  showLabel: false,
})

const statusClass = computed(() => {
  const s = props.status?.toUpperCase()
  switch (s) {
    case 'ONLINE': case 'CONNECTED': case 'COMPLETED': case 'ACTIVE': return 'status-success'
    case 'RUNNING': case 'CONNECTING': case 'PENDING': return 'status-warning'
    case 'OFFLINE': case 'FAILED': case 'ERROR': case 'ABORTED': return 'status-danger'
    default: return 'status-info'
  }
})

const label = computed(() => {
  const s = props.status?.toUpperCase()
  const map: Record<string, string> = {
    ONLINE: '在线', OFFLINE: '离线', UNKNOWN: '未知',
    ACTIVE: '活跃', CLOSED: '已关闭',
    PENDING: '等待中', RUNNING: '运行中', COMPLETED: '已完成', FAILED: '失败', ABORTED: '已中止',
    CONNECTED: '已连接', CONNECTING: '连接中', DISCONNECTED: '已断开', ERROR: '错误',
  }
  return map[s] || props.status
})
</script>

<style scoped>
.status-badge {
  display: flex;
  flex-direction: row;
  align-items: center;
}
.status-dot {
  width: 16rpx;
  height: 16rpx;
  border-radius: 50%;
  margin-right: 8rpx;
}
.status-label {
  font-size: 24rpx;
}
.status-success .status-dot { background-color: #67c23a; }
.status-success .status-label { color: #67c23a; }
.status-warning .status-dot { background-color: #e6a23c; }
.status-warning .status-label { color: #e6a23c; }
.status-danger .status-dot { background-color: #f56c6c; }
.status-danger .status-label { color: #f56c6c; }
.status-info .status-dot { background-color: #909399; }
.status-info .status-label { color: #909399; }
</style>
