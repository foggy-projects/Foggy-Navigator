<template>
  <div class="message-input">
    <el-input
      v-model="inputText"
      :placeholder="placeholder"
      :disabled="disabled"
      type="textarea"
      :autosize="{ minRows: 1, maxRows: 4 }"
      resize="none"
      @keydown.enter.exact.prevent="handleSend"
    />
    <el-button
      type="primary"
      :disabled="disabled || !inputText.trim()"
      @click="handleSend"
    >
      发送
    </el-button>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'

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

const inputText = ref('')

function handleSend() {
  const text = inputText.value.trim()
  if (!text || props.disabled) return
  emit('send', text)
  inputText.value = ''
}
</script>

<style scoped>
.message-input {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid #e4e7ed;
  background: #fff;
}

.message-input :deep(.el-textarea) {
  flex: 1;
}

.message-input :deep(.el-textarea__inner) {
  box-shadow: none;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  padding: 8px 12px;
}
</style>
