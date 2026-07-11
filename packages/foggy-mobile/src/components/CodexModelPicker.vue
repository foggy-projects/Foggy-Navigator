<template>
  <view v-if="visible" class="picker-mask" @tap="$emit('close')">
    <view class="picker-panel" @tap.stop>
      <view class="picker-header">
        <text class="picker-title">选择 Codex 模型</text>
        <text class="picker-close" @tap="$emit('close')">关闭</text>
      </view>
      <scroll-view class="picker-scroll" scroll-y>
        <view v-for="group in groups" :key="group.label" class="picker-group">
          <text class="picker-group-title">{{ group.label }}</text>
          <view class="picker-options">
            <view
              v-for="option in group.options"
              :key="option.value"
              :class="['picker-option', { selected: option.value === modelValue }]"
              @tap="$emit('select', option.value)"
            >
              <text>{{ option.optionLabel || option.label }}</text>
              <text v-if="option.value === modelValue" class="picker-check">✓</text>
            </view>
          </view>
        </view>
      </scroll-view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { groupMobileModelOptions, type MobileModelOption } from '@/utils/llmModelOptions'

const props = defineProps<{
  visible: boolean
  modelValue: string
  options: readonly MobileModelOption[]
}>()

defineEmits<{
  close: []
  select: [value: string]
}>()

const groups = computed(() => groupMobileModelOptions(props.options))
</script>

<style scoped>
.picker-mask {
  position: fixed;
  inset: 0;
  z-index: 9999;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  background: rgba(0, 0, 0, 0.42);
}
.picker-panel {
  max-height: 76vh;
  padding: 28rpx 28rpx calc(28rpx + env(safe-area-inset-bottom));
  border-radius: 28rpx 28rpx 0 0;
  background: #fff;
}
.picker-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20rpx;
}
.picker-title {
  color: #303133;
  font-size: 32rpx;
  font-weight: 600;
}
.picker-close {
  padding: 8rpx;
  color: #667eea;
  font-size: 26rpx;
}
.picker-scroll {
  max-height: 64vh;
}
.picker-group + .picker-group {
  margin-top: 24rpx;
}
.picker-group-title {
  display: block;
  margin-bottom: 12rpx;
  color: #606266;
  font-size: 25rpx;
  font-weight: 600;
}
.picker-options {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12rpx;
}
.picker-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 72rpx;
  padding: 0 22rpx;
  border: 2rpx solid #ebeef5;
  border-radius: 14rpx;
  color: #606266;
  font-size: 26rpx;
  background: #fafafa;
}
.picker-option.selected {
  border-color: #667eea;
  color: #5268d8;
  background: #f0f2ff;
}
.picker-check {
  color: #667eea;
  font-weight: 700;
}
</style>
