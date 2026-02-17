<template>
  <view class="chat-input-wrap">
    <view class="chat-input-inner">
      <textarea
        v-model="inputText"
        class="chat-textarea"
        :placeholder="placeholder"
        :auto-height="true"
        :maxlength="-1"
        :cursor-spacing="12"
        :adjust-position="true"
        :confirm-type="'send'"
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

const props = withDefaults(defineProps<{
  placeholder?: string
  disabled?: boolean
  sendLabel?: string
}>(), {
  placeholder: '输入消息...',
  disabled: false,
  sendLabel: '发送',
})

const emit = defineEmits<{
  send: [content: string]
}>()

const inputText = ref('')
const canSend = computed(() => inputText.value.trim().length > 0 && !props.disabled)

function handleSend() {
  if (!canSend.value) return
  emit('send', inputText.value.trim())
  inputText.value = ''
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
