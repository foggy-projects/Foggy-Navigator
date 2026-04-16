<template>
  <view class="session-card" @tap="$emit('tap')" @longpress="$emit('longpress')">
    <view class="session-header">
      <view class="session-state-row">
        <view v-if="pinned" class="pin-icon">
          <text class="pin-text">&#x1F4CC;</text>
        </view>
        <InteractionBadge v-if="interactionState" :state="interactionState" />
        <text v-if="latestTask.model" class="session-model">{{ latestTask.model }}</text>
      </view>
    </view>
    <text class="session-title">{{ displayTitle }}</text>
    <view v-if="milestone" class="session-tags">
      <text class="session-milestone">{{ milestone.name }}</text>
      <text class="session-milestone-status">{{ milestoneStatusLabel(milestone.status) }}</text>
    </view>
    <view class="session-footer">
      <text class="session-time">{{ shortDateTime(group.updatedAt) }}</text>
      <text v-if="group.totalCost > 0" class="session-cost">${{ group.totalCost.toFixed(4) }}</text>
      <text v-if="modelConfigName" class="session-config">{{ modelConfigName }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ConversationGroup, DirectoryMilestone, LlmModelConfig } from '@/api/types'
import InteractionBadge from './InteractionBadge.vue'
import { shortDateTime } from '@/utils/time'
import { getGroupTitle, getGroupInteractionState } from '@/composables/useConversationGroup'

const props = defineProps<{
  group: ConversationGroup
  milestone?: DirectoryMilestone
  modelConfigs?: LlmModelConfig[]
}>()

defineEmits<{
  tap: []
  longpress: []
}>()

const latestTask = computed(() => props.group.latestTask)
const pinned = computed(() => props.group.config?.pinned ?? false)
const interactionState = computed(() => getGroupInteractionState(props.group))
const displayTitle = computed(() => getGroupTitle(props.group))
const modelConfigName = computed(() => {
  const configId = latestTask.value.modelConfigId
  if (!configId || !props.modelConfigs) return ''
  const cfg = props.modelConfigs.find(m => m.id === configId)
  return cfg?.name ?? ''
})

function milestoneStatusLabel(status?: string): string {
  switch (status) {
    case 'ACTIVE':
      return '进行中'
    case 'COMPLETED':
      return '已完成'
    case 'ARCHIVED':
      return '已归档'
    case 'PLANNED':
      return '规划中'
    default:
      return status || ''
  }
}
</script>

<style scoped>
.session-card {
  background-color: #ffffff;
  border-radius: 16rpx;
  padding: 24rpx 28rpx;
  margin-bottom: 16rpx;
  border: 1rpx solid #e8e8e8;
}
.session-header {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12rpx;
}
.session-state-row {
  display: flex;
  flex-direction: row;
  align-items: center;
  flex: 1;
  flex-wrap: wrap;
}
.pin-icon {
  margin-right: 8rpx;
}
.pin-text {
  font-size: 24rpx;
}
.session-model {
  font-size: 22rpx;
  color: #909399;
  background-color: #f0f0f0;
  padding: 4rpx 12rpx;
  border-radius: 8rpx;
  margin-left: auto;
}
.session-title {
  font-size: 28rpx;
  color: #303133;
  line-height: 1.5;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.session-tags {
  display: flex;
  flex-direction: row;
  align-items: center;
  flex-wrap: wrap;
  margin-top: 14rpx;
}
.session-milestone,
.session-milestone-status {
  font-size: 22rpx;
  padding: 4rpx 12rpx;
  border-radius: 999rpx;
  margin-right: 10rpx;
}
.session-milestone {
  color: #8a4b08;
  background-color: #fff1d6;
}
.session-milestone-status {
  color: #8c8c8c;
  background-color: #f5f5f5;
}
.session-footer {
  display: flex;
  flex-direction: row;
  align-items: center;
  margin-top: 16rpx;
}
.session-time, .session-cost, .session-config {
  font-size: 24rpx;
  color: #c0c4cc;
  margin-right: 20rpx;
}
.session-cost { color: #e6a23c; }
.session-config {
  color: #2e7d32;
  background-color: #e8f5e9;
  padding: 2rpx 12rpx;
  border-radius: 8rpx;
}
</style>
