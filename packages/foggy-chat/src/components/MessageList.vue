<template>
  <div ref="listRef" class="message-list" @scroll="handleScroll">
    <div v-if="messages.length === 0" class="empty-state">
      <slot name="empty">
        <div class="empty-hint">暂无消息</div>
      </slot>
    </div>
    <template v-for="msg in messages" :key="msg.id">
      <MessageBubble
        v-if="isBubble(msg)"
        :message="msg"
      />
      <ToolCallBlock
        v-else-if="isToolCall(msg)"
        :message="msg"
      />
      <ThinkingIndicator
        v-else-if="msg.type === AipMessageType.THINKING"
        :thought="msg.thought"
      />
      <ErrorBlock
        v-else-if="isError(msg)"
        :error="msg.error || msg.content"
      />
      <TaskCompletionCard
        v-else-if="msg.type === AipMessageType.TASK_COMPLETED"
        :message="msg"
      />
      <PermissionRequestCard
        v-else-if="msg.type === AipMessageType.CONFIRMATION_REQUEST"
        :message="msg"
        @respond="(pid, decision) => emit('permissionRespond', pid, decision)"
      />
      <MessageBubble
        v-else-if="msg.type === AipMessageType.STATE_SYNC"
        :message="msg"
      />
    </template>
    <ThinkingIndicator v-if="isThinking && !hasTrailingThinking" />
  </div>
</template>

<script setup lang="ts">
import { ref, watch, nextTick, computed, onMounted } from 'vue'
import { AipMessageType } from '../types/aip'
import type { ChatMessage } from '../types/chat'
import MessageBubble from './MessageBubble.vue'
import ToolCallBlock from './ToolCallBlock.vue'
import ThinkingIndicator from './ThinkingIndicator.vue'
import ErrorBlock from './ErrorBlock.vue'
import TaskCompletionCard from './TaskCompletionCard.vue'
import PermissionRequestCard from './PermissionRequestCard.vue'

const props = defineProps<{
  messages: ChatMessage[]
  isThinking?: boolean
}>()

const emit = defineEmits<{
  (e: 'permissionRespond', permissionId: string, decision: string): void
}>()

const listRef = ref<HTMLElement>()
const userScrolledUp = ref(false)

function isBubble(msg: ChatMessage) {
  return msg.type === AipMessageType.TEXT_COMPLETE || msg.type === AipMessageType.TEXT_CHUNK
}

function isToolCall(msg: ChatMessage) {
  return msg.type === AipMessageType.TOOL_CALL_START
    || msg.type === AipMessageType.TOOL_CALL_RESULT
}

function isError(msg: ChatMessage) {
  return (msg.type === AipMessageType.ERROR || msg.type === AipMessageType.TOOL_CALL_ERROR)
    && (msg.error || msg.content)
}

const hasTrailingThinking = computed(() => {
  const last = props.messages[props.messages.length - 1]
  return last?.type === AipMessageType.THINKING
})

function scrollToBottom() {
  nextTick(() => {
    if (listRef.value && !userScrolledUp.value) {
      listRef.value.scrollTop = listRef.value.scrollHeight
    }
  })
}

function handleScroll() {
  if (!listRef.value) return
  const { scrollTop, scrollHeight, clientHeight } = listRef.value
  userScrolledUp.value = scrollHeight - scrollTop - clientHeight > 80
}

// Watch messages array length
watch(() => props.messages.length, scrollToBottom)
// Watch last message content for TEXT_CHUNK streaming updates
watch(
  () => props.messages[props.messages.length - 1]?.content,
  scrollToBottom
)
watch(() => props.isThinking, scrollToBottom)
onMounted(scrollToBottom)
</script>

<style scoped>
.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
}

.empty-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
}

.empty-hint {
  text-align: center;
  color: #c0c4cc;
  font-size: 14px;
}
</style>
