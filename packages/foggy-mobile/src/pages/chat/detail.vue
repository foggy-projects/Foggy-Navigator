<template>
  <view class="chat-detail-page">
    <!-- 消息列表 -->
    <view class="message-area">
      <MessageList
        :messages="sortedMessages"
        :is-thinking="isThinking"
      >
        <template #empty>
          <view v-if="guideCards.length > 0" class="guide-cards">
            <text class="guide-title">试试这些...</text>
            <view
              v-for="(card, idx) in guideCards"
              :key="idx"
              class="guide-card"
              @tap="sendGuideMessage(card.description)"
            >
              <text class="guide-card-title">{{ card.title }}</text>
              <text class="guide-card-desc">{{ card.description }}</text>
            </view>
          </view>
          <EmptyState v-else title="开始对话" description="输入消息开始与 AI 助手对话" />
        </template>
      </MessageList>
    </view>

    <!-- 底部输入栏 -->
    <ChatInput
      v-model="chatInput"
      :disabled="!isConnected"
      :history-items="historyItems"
      @send="handleSend"
      @sent="onSent"
    />
  </view>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { onLoad, onUnload } from '@dcloudio/uni-app'
import { useChatStore } from '@/stores/chat'
import { useSession } from '@/composables/useSession'
import { useInputMemory } from '@/composables/useInputMemory'
import * as sessionApi from '@/api/session'
import type { GuideCard } from '@/api/types'
import MessageList from '@/components/MessageList.vue'
import ChatInput from '@/components/ChatInput.vue'
import EmptyState from '@/components/EmptyState.vue'

const chatStore = useChatStore()
const { connectToSession, sendMessage, disconnectSession } = useSession()

const sessionId = ref('')
const guideCards = ref<GuideCard[]>([])
const chatInput = ref('')

// Draft & history
const memoryScope = computed(() => sessionId.value ? 'chat-' + sessionId.value : '')
const { saveDraft, loadDraft, clearDraft, addToHistory, recentItems } = useInputMemory(memoryScope)

const historyItems = computed(() => recentItems(10))

watch(chatInput, (val) => {
  saveDraft(val)
})

const sortedMessages = computed(() => chatStore.sortedMessages)
const isThinking = computed(() => chatStore.isThinking)
const isConnected = computed(() => chatStore.connectionStatus === 'connected')

onLoad((options) => {
  sessionId.value = options?.id || ''
  if (sessionId.value) {
    connectToSession(sessionId.value)
    loadGuideCards()
    // Restore draft
    const draft = loadDraft()
    if (draft) chatInput.value = draft
  }
})

onUnload(() => {
  disconnectSession()
})

async function loadGuideCards() {
  try {
    guideCards.value = await sessionApi.getGuideCards()
  } catch {
    // ignore
  }
}

function handleSend(content: string) {
  if (!sessionId.value) return
  sendMessage(sessionId.value, content)
}

function onSent(content: string) {
  addToHistory(content)
  clearDraft()
  chatInput.value = ''
}

function sendGuideMessage(content: string) {
  handleSend(content)
  addToHistory(content)
}
</script>

<style scoped>
.chat-detail-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: #f5f5f5;
}
.message-area {
  flex: 1;
  overflow: hidden;
}
.guide-cards {
  padding: 32rpx 24rpx;
}
.guide-title {
  font-size: 28rpx;
  color: #909399;
  margin-bottom: 24rpx;
  display: block;
  text-align: center;
}
.guide-card {
  background: #ffffff;
  border-radius: 16rpx;
  padding: 24rpx 28rpx;
  margin-bottom: 16rpx;
  box-shadow: 0 2rpx 8rpx rgba(0, 0, 0, 0.04);
}
.guide-card-title {
  font-size: 28rpx;
  color: #303133;
  font-weight: 500;
  display: block;
  margin-bottom: 8rpx;
}
.guide-card-desc {
  font-size: 26rpx;
  color: #909399;
}
</style>
