<template>
  <view class="chat-input-wrap">
    <view class="chat-input-inner">
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
import { ref, computed, watch } from 'vue'

const props = withDefaults(defineProps<{
  placeholder?: string
  disabled?: boolean
  sendLabel?: string
  modelValue?: string
  historyItems?: string[]
}>(), {
  placeholder: '输入消息...',
  disabled: false,
  sendLabel: '发送',
  modelValue: undefined,
  historyItems: () => [],
})

const emit = defineEmits<{
  send: [content: string]
  sent: [content: string]
  'update:modelValue': [value: string]
}>()

// Internal state (used when modelValue not provided)
const internalText = ref('')

const isControlled = computed(() => props.modelValue !== undefined)
const currentText = computed(() => isControlled.value ? props.modelValue! : internalText.value)
const canSend = computed(() => currentText.value.trim().length > 0 && !props.disabled)

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
  const content = currentText.value.trim()
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
</script>

<style scoped>
.chat-input-wrap {
  background: #ffffff;
  border-top: 2rpx solid #e4e7ed;
  padding: 16rpx 24rpx;
  padding-bottom: calc(16rpx + env(safe-area-inset-bottom));
}
.chat-input-inner {
  display: flex;
  align-items: flex-end;
  gap: 16rpx;
}
.history-btn {
  width: 64rpx;
  height: 64rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
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
  background: #f5f5f5;
  border-radius: 32rpx;
  line-height: 1.5;
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
