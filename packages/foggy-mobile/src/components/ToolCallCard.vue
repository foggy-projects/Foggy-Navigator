<template>
  <view class="tool-call-card" @tap="expanded = !expanded">
    <view class="tool-header">
      <text class="tool-icon">{{ hasError ? '❌' : '🔧' }}</text>
      <text class="tool-name">{{ toolName }}</text>
      <text class="tool-expand">{{ expanded ? '▲' : '▼' }}</text>
    </view>
    <view v-if="command && !expanded" class="tool-command-preview">
      <text class="tool-command-text">{{ command }}</text>
    </view>
    <view v-if="expanded" class="tool-detail">
      <view v-if="thought" class="tool-thought">
        <text class="detail-label">思考:</text>
        <text class="detail-text">{{ thought }}</text>
      </view>
      <view v-if="command" class="tool-command">
        <text class="detail-label">命令:</text>
        <scroll-view scroll-x class="code-scroll">
          <text class="code-text">{{ command }}</text>
        </scroll-view>
      </view>
      <view v-if="output" class="tool-output">
        <text class="detail-label">输出:</text>
        <scroll-view scroll-x class="code-scroll">
          <text class="code-text">{{ truncatedOutput }}</text>
        </scroll-view>
      </view>
      <view v-if="hasError" class="tool-error">
        <text class="error-text">{{ error }}</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'

const props = defineProps<{
  toolName: string
  command?: string
  thought?: string
  output?: string
  error?: string
}>()

const expanded = ref(false)
const hasError = computed(() => !!props.error)
const truncatedOutput = computed(() => {
  if (!props.output) return ''
  return props.output.length > 500 ? props.output.slice(0, 500) + '...' : props.output
})
</script>

<style scoped>
.tool-call-card {
  background: #f8f9fa;
  border-radius: 16rpx;
  padding: 20rpx 24rpx;
  margin: 8rpx 0;
  border-left: 6rpx solid #909399;
  box-sizing: border-box;
  max-width: 100%;
  min-width: 0;
  overflow: hidden;
}
.tool-header {
  display: flex;
  flex-direction: row;
  align-items: center;
}
.tool-icon { font-size: 28rpx; margin-right: 12rpx; }
.tool-name {
  font-size: 26rpx;
  color: #606266;
  font-weight: 500;
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
  word-break: break-word;
}
.tool-expand {
  font-size: 22rpx;
  color: #c0c4cc;
}
.tool-command-preview {
  margin-top: 8rpx;
  overflow: hidden;
}
.tool-command-text {
  font-size: 24rpx;
  color: #909399;
  display: block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tool-detail { margin-top: 16rpx; }
.detail-label {
  font-size: 24rpx;
  color: #909399;
  margin-bottom: 8rpx;
}
.detail-text {
  font-size: 26rpx;
  color: #606266;
  overflow-wrap: anywhere;
  word-break: break-word;
}
.tool-thought, .tool-command, .tool-output {
  margin-bottom: 12rpx;
}
.code-scroll {
  background: #f0f0f0;
  border-radius: 8rpx;
  padding: 12rpx;
  margin-top: 4rpx;
  width: 100%;
  box-sizing: border-box;
}
.code-text {
  font-size: 24rpx;
  font-family: monospace;
  color: #303133;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}
.tool-error { margin-top: 8rpx; }
.error-text {
  font-size: 24rpx;
  color: #f56c6c;
  overflow-wrap: anywhere;
  word-break: break-word;
}
</style>
