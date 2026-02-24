<template>
  <scroll-view
    scroll-y
    :scroll-into-view="scrollTarget"
    :scroll-with-animation="true"
    class="message-list"
    @scrolltoupper="$emit('loadMore')"
  >
    <view v-if="messages.length === 0" class="empty-wrap">
      <slot name="empty">
        <EmptyState title="暂无消息" description="发送消息开始对话" />
      </slot>
    </view>
    <view v-for="msg in messages" :key="msg.id" :id="`msg-${msg.id}`">
      <MessageBubble
        :message="msg"
        @plan-respond="(pid, decision, denyMsg, planAction) => $emit('plan-respond', pid, decision, denyMsg, planAction)"
        @question-respond="(pid, answers) => $emit('question-respond', pid, answers)"
        @permission-respond="(pid, decision, scope) => $emit('permission-respond', pid, decision, scope)"
      />
    </view>
    <view v-if="isThinking" class="thinking-wrap">
      <ThinkingDots />
    </view>
    <view id="scroll-bottom" style="height: 2rpx;" />
  </scroll-view>
</template>

<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import type { ChatMessage } from '@foggy/chat-core'
import MessageBubble from './MessageBubble.vue'
import ThinkingDots from './ThinkingDots.vue'
import EmptyState from './EmptyState.vue'

const props = defineProps<{
  messages: ChatMessage[]
  isThinking: boolean
}>()

defineEmits<{
  (e: 'loadMore'): void
  (e: 'plan-respond', permissionId: string, decision: string, denyMessage?: string, planAction?: string): void
  (e: 'question-respond', permissionId: string, answers: Record<string, string>): void
  (e: 'permission-respond', permissionId: string, decision: string, scope: string): void
}>()

const scrollTarget = ref('scroll-bottom')

// 消息变化时自动滚到底部
watch(
  () => props.messages.length,
  () => {
    nextTick(() => {
      scrollTarget.value = ''
      nextTick(() => {
        scrollTarget.value = 'scroll-bottom'
      })
    })
  },
)

watch(
  () => props.isThinking,
  (val) => {
    if (val) {
      nextTick(() => {
        scrollTarget.value = ''
        nextTick(() => {
          scrollTarget.value = 'scroll-bottom'
        })
      })
    }
  },
)
</script>

<style scoped>
.message-list {
  flex: 1;
  height: 100%;
}
.empty-wrap {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}
.thinking-wrap {
  padding: 8rpx 24rpx;
  display: flex;
  justify-content: flex-start;
}
</style>
