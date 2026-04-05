<template>
  <view class="interaction-badge" :class="stateClass">
    <text class="badge-label">{{ label }}</text>
  </view>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  state: string
}>()

const stateClass = computed(() => {
  switch (props.state) {
    case 'PROCESSING': return 'badge-processing'
    case 'AWAITING_REPLY': return 'badge-awaiting'
    case 'ON_HOLD': return 'badge-hold'
    case 'ARCHIVED': return 'badge-archived'
    default: return 'badge-default'
  }
})

const label = computed(() => {
  const map: Record<string, string> = {
    PROCESSING: '处理中',
    AWAITING_REPLY: '待回复',
    ON_HOLD: '已搁置',
    ARCHIVED: '已归档',
  }
  return map[props.state] || props.state
})
</script>

<style scoped>
.interaction-badge {
  padding: 4rpx 16rpx;
  border-radius: 8rpx;
  margin-right: 12rpx;
}
.badge-label {
  font-size: 22rpx;
  font-weight: 500;
}
.badge-processing {
  background-color: #ecf5ff;
}
.badge-processing .badge-label {
  color: #409eff;
}
.badge-awaiting {
  background-color: #fdf6ec;
}
.badge-awaiting .badge-label {
  color: #e6a23c;
}
.badge-hold {
  background-color: #f4f4f5;
}
.badge-hold .badge-label {
  color: #909399;
}
.badge-archived {
  background-color: #f5f5f5;
}
.badge-archived .badge-label {
  color: #c0c4cc;
}
.badge-default {
  background-color: #f0f0f0;
}
.badge-default .badge-label {
  color: #606266;
}
</style>
