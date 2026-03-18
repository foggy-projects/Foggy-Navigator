<template>
  <div class="message-input">
    <div class="message-input-wrapper">
      <div class="textarea-container">
        <el-input
          ref="textareaRef"
          v-model="inputText"
          :placeholder="placeholder"
          :disabled="disabled"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 6 }"
          resize="none"
          class="custom-textarea"
          @input="handleInput"
          @keydown.ctrl.enter.prevent="handleSend"
        />
      </div>
      <el-button
        type="primary"
        :disabled="disabled || !inputText.trim()"
        @click="handleSend"
        class="send-button"
      >
        发送
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import type { ElInput } from 'element-plus'

const props = withDefaults(defineProps<{
  disabled?: boolean
  placeholder?: string
}>(), {
  disabled: false,
  placeholder: '输入消息...',
})

const emit = defineEmits<{
  (e: 'send', content: string): void
}>()

const textareaRef = ref<InstanceType<typeof ElInput> | null>(null)
const inputText = ref('')
const isTouchDevice = ref(false)

// 检测是否为触摸设备
function detectTouchDevice() {
  isTouchDevice.value = 'ontouchstart' in window || navigator.maxTouchPoints > 0
}

function handleInput() {
  // 触发 autosize 重新计算
  if (textareaRef.value) {
    const textarea = textareaRef.value.$el?.querySelector?.('textarea')
    if (textarea) {
      // 确保高度正确计算
      textarea.style.height = 'auto'
      const newHeight = Math.min(textarea.scrollHeight, 180) // 限制最大高度
      textarea.style.height = newHeight + 'px'

      // iPad 上的额外处理：确保滚动条可见
      if (textarea.scrollHeight > 180) {
        textarea.style.overflowY = 'scroll'
      }
    }
  }
}

function handleSend() {
  const text = inputText.value.trim()
  if (!text || props.disabled) return
  emit('send', text)
  inputText.value = ''
  // 重置高度
  setTimeout(() => handleInput(), 0)
}

onMounted(() => {
  detectTouchDevice()
  // 延迟初始化以确保 DOM 已渲染
  setTimeout(() => handleInput(), 100)
})
</script>

<style scoped>
.message-input {
  display: flex;
  flex-direction: column;
  padding: 12px 16px;
  border-top: 1px solid #e4e7ed;
  background: #fff;
}

.message-input-wrapper {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  width: 100%;
  position: relative;
}

.textarea-container {
  flex: 1;
  min-width: 0;
  max-width: calc(100% - 80px); /* 为发送按钮预留空间 */
}

.message-input :deep(.custom-textarea) {
  width: 100%;
}

.message-input :deep(.el-textarea) {
  width: 100%;
}

.message-input :deep(.el-textarea__inner) {
  box-shadow: none;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  padding: 10px 14px;
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
  overflow-wrap: break-word;
  overflow-y: auto;
  /* 最大高度由 JS 控制 */
}

.send-button {
  flex-shrink: 0;
  width: 70px; /* 固定宽度，防止被压缩 */
  min-width: 70px;
  height: auto;
  min-height: 40px;
  padding: 8px 16px;
  white-space: nowrap;
}

/* iPad / 移动端适配 */
@media (max-width: 768px) {
  .message-input {
    padding: 10px 12px;
    padding-bottom: calc(10px + env(safe-area-inset-bottom));
  }

  .message-input-wrapper {
    gap: 6px;
    align-items: stretch; /* 移动端使用 stretch 对齐 */
  }

  .textarea-container {
    flex: 1;
    min-width: 0;
    max-width: calc(100% - 60px); /* 为发送按钮预留空间 */
  }

  .message-input :deep(.el-textarea__inner) {
    padding: 8px 12px;
    font-size: 16px; /* 防止 iPad 缩放 */
    overflow-y: auto;
    -webkit-overflow-scrolling: touch; /* iOS 平滑滚动 */
    /* 强制内容不换行到按钮下方 */
    display: block;
  }

  .send-button {
    flex-shrink: 0;
    width: 60px; /* 固定宽度 */
    min-width: 60px;
    min-height: 40px;
    padding: 6px 12px;
    font-size: 14px;
    /* 确保按钮始终可见 */
    position: relative;
    z-index: 10;
  }
}
</style>
