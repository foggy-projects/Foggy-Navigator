<template>
  <view class="chat-input-wrap">
    <view v-if="attachments.length > 0" class="attachment-strip">
      <view
        v-for="(att, index) in attachments"
        :key="`${att.name}-${index}`"
        class="attachment-chip"
      >
        <image
          v-if="att.isImage && att.previewUrl"
          class="attachment-thumb"
          :src="att.previewUrl"
          mode="aspectFill"
        />
        <text v-else class="attachment-file">{{ fileLabel(att.mimeType) }}</text>
        <text class="attachment-name">{{ att.name }}</text>
        <text class="attachment-remove" @tap="$emit('remove-attachment', index)">x</text>
      </view>
    </view>
    <view class="chat-input-inner">
      <view
        v-if="enableAttachments"
        class="attach-btn"
        @tap="showAttachmentActions"
      >
        <text class="attach-icon">+</text>
      </view>
      <view
        v-if="historyItems.length > 0"
        class="history-btn"
        @tap="showHistory"
      >
        <text class="history-icon">⏱</text>
      </view>
      <textarea
        :value="currentText"
        class="chat-textarea"
        :placeholder="placeholder"
        :auto-height="true"
        :maxlength="-1"
        :cursor-spacing="12"
        :adjust-position="true"
        :confirm-type="'send'"
        @input="onInput"
        @confirm="handleSend"
      />
      <view
        class="send-btn"
        :class="{ 'send-btn-active': canSend }"
        @tap="handleSend"
      >
        <text class="send-text">{{ sendLabel }}</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import type { Attachment } from '@/composables/useAttachments'
import { fileIcon } from '@/composables/useAttachments'

const props = withDefaults(defineProps<{
  placeholder?: string
  disabled?: boolean
  sendLabel?: string
  modelValue?: string
  historyItems?: string[]
  enableAttachments?: boolean
  attachments?: Attachment[]
}>(), {
  placeholder: '输入消息...',
  disabled: false,
  sendLabel: '发送',
  modelValue: undefined,
  historyItems: () => [],
  enableAttachments: false,
  attachments: () => [],
})

const emit = defineEmits<{
  send: [content: string]
  sent: [content: string]
  'update:modelValue': [value: string]
  'add-image': []
  'take-photo': []
  'add-file': []
  'remove-attachment': [index: number]
}>()

// Internal state (used when modelValue not provided)
const internalText = ref('')

const isControlled = computed(() => props.modelValue !== undefined)
const currentText = computed(() => isControlled.value ? props.modelValue! : internalText.value)
const canSend = computed(() => (currentText.value.trim().length > 0 || props.attachments.length > 0) && !props.disabled)

function onInput(e: any) {
  const val = e.detail.value ?? ''
  if (isControlled.value) {
    emit('update:modelValue', val)
  } else {
    internalText.value = val
  }
}

function setText(val: string) {
  if (isControlled.value) {
    emit('update:modelValue', val)
  } else {
    internalText.value = val
  }
}

function handleSend() {
  if (!canSend.value) return
  const content = currentText.value.trim() || '请查看附件'
  emit('send', content)
  emit('sent', content)
  setText('')
}

function showHistory() {
  const items = props.historyItems
  if (items.length === 0) return
  const truncated = items.map(s => s.length > 40 ? s.slice(0, 40) + '...' : s)
  uni.showActionSheet({
    itemList: truncated,
    success: (res) => {
      setText(items[res.tapIndex])
    },
  })
}

function showAttachmentActions() {
  uni.showActionSheet({
    itemList: ['相册选图', '拍照', '选择文件'],
    success: (res) => {
      if (res.tapIndex === 0) emit('add-image')
      if (res.tapIndex === 1) emit('take-photo')
      if (res.tapIndex === 2) emit('add-file')
    },
  })
}

function fileLabel(mimeType: string) {
  return fileIcon(mimeType)
}
</script>

<style scoped>
.chat-input-wrap {
  background: #ffffff;
  border-top: 2rpx solid #e4e7ed;
  padding: 16rpx 24rpx;
  padding-bottom: calc(16rpx + env(safe-area-inset-bottom));
}
.attachment-strip {
  display: flex;
  flex-direction: row;
  gap: 12rpx;
  margin-bottom: 14rpx;
  overflow-x: auto;
  white-space: nowrap;
}
.attachment-chip {
  display: inline-flex;
  flex-direction: row;
  align-items: center;
  max-width: 360rpx;
  min-width: 0;
  height: 64rpx;
  padding: 0 12rpx;
  border-radius: 12rpx;
  background: #f5f7fa;
  border: 2rpx solid #e4e7ed;
  box-sizing: border-box;
}
.attachment-thumb {
  width: 44rpx;
  height: 44rpx;
  border-radius: 8rpx;
  flex-shrink: 0;
  background: #e4e7ed;
}
.attachment-file {
  min-width: 48rpx;
  height: 36rpx;
  padding: 0 8rpx;
  border-radius: 6rpx;
  background: #dbeafe;
  color: #1d4ed8;
  font-size: 18rpx;
  line-height: 36rpx;
  text-align: center;
  flex-shrink: 0;
}
.attachment-name {
  max-width: 210rpx;
  margin-left: 10rpx;
  font-size: 24rpx;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.attachment-remove {
  margin-left: 10rpx;
  width: 32rpx;
  height: 32rpx;
  border-radius: 16rpx;
  background: #c0c4cc;
  color: #ffffff;
  font-size: 22rpx;
  line-height: 32rpx;
  text-align: center;
  flex-shrink: 0;
}
.chat-input-inner {
  display: flex;
  flex-direction: row;
  align-items: flex-end;
}
.attach-btn {
  width: 64rpx;
  height: 64rpx;
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-right: 12rpx;
  border-radius: 32rpx;
  background: #f0f2f5;
  color: #606266;
}
.attach-icon {
  font-size: 40rpx;
  line-height: 1;
}
.history-btn {
  width: 64rpx;
  height: 64rpx;
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-right: 12rpx;
}
.history-icon {
  font-size: 36rpx;
}
.chat-textarea {
  flex: 1;
  min-height: 64rpx;
  max-height: 200rpx;
  padding: 16rpx 24rpx;
  font-size: 28rpx;
  background-color: #f5f5f5;
  border-radius: 32rpx;
  line-height: 1.5;
  margin-right: 16rpx;
}
.send-btn {
  width: 100rpx;
  height: 64rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 32rpx;
  background: #c0c4cc;
  flex-shrink: 0;
}
.send-btn-active {
  background: #667eea;
}
.send-text {
  font-size: 28rpx;
  color: #ffffff;
}
</style>
