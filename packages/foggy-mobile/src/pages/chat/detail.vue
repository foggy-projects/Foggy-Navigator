<template>
  <view class="chat-detail-page">
    <!-- 连接状态横幅 -->
    <view v-if="connectionStatus !== 'connected'" class="connection-banner" :class="connectionStatus">
      <text class="connection-text">
        {{ connectionStatus === 'connecting' ? '连接中...' : '连接断开' }}
      </text>
      <text v-if="connectionStatus === 'disconnected'" class="connection-retry" @tap="handleReconnect">
        重试
      </text>
    </view>

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
const connectionStatus = computed(() => chatStore.connectionStatus)

onLoad((options) => {
  sessionId.value = options?.id || ''
  if (sessionId.value) {
    connectToSession(sessionId.value)
    loadGuideCards()
    // Restore draft
    const draft = loadDraft()
    if (draft) chatInput.value = draft
    // 动态设置导航栏标题
    uni.setNavigationBarTitle({ title: '对话' })
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
  clearDraft()
  chatInput.value = ''
}

function handleReconnect() {
  if (sessionId.value) {
    connectToSession(sessionId.value)
  }
}
</script>

<style scoped>
.chat-detail-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - var(--window-top, 0px));
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
.connection-banner {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16rpx;
  padding: 12rpx 24rpx;
  font-size: 24rpx;
}
.connection-banner.connecting {
  background: #fdf6ec;
  color: #e6a23c;
}
.connection-banner.disconnected {
  background: #fef0f0;
  color: #f56c6c;
}
.connection-text {
  font-size: 24rpx;
}
.connection-retry {
  font-size: 24rpx;
  color: #409eff;
  text-decoration: underline;
}
</style>
