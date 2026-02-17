<template>
  <view class="chat-list-page">
    <!-- 头部 -->
    <view class="page-header">
      <text class="header-title">会话</text>
      <view class="header-action" @tap="createNewSession">
        <text class="action-icon">+</text>
      </view>
    </view>

    <!-- 会话列表 -->
    <scroll-view
      scroll-y
      class="session-list"
      :refresher-enabled="true"
      :refresher-triggered="refreshing"
      @refresherrefresh="onRefresh"
    >
      <view v-if="sessionStore.loading && sessions.length === 0" class="loading-wrap">
        <text class="loading-text">加载中...</text>
      </view>
      <template v-else-if="sessions.length > 0">
        <view v-for="session in sessions" :key="session.id" class="session-swipe-wrap">
          <SessionItem
            :session="session"
            @tap="openSession(session)"
          />
        </view>
      </template>
      <EmptyState
        v-else
        title="暂无会话"
        description="点击右上角 + 开始新对话"
      />
    </scroll-view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useSessionStore } from '@/stores/session'
import type { Session } from '@/api/types'
import SessionItem from '@/components/SessionItem.vue'
import EmptyState from '@/components/EmptyState.vue'

const sessionStore = useSessionStore()
const refreshing = ref(false)

const sessions = computed(() =>
  [...sessionStore.sessions].sort(
    (a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime(),
  ),
)

onShow(() => {
  sessionStore.loadSessions()
})

async function onRefresh() {
  refreshing.value = true
  await sessionStore.loadSessions()
  refreshing.value = false
}

async function createNewSession() {
  const session = await sessionStore.createSession('新会话')
  if (session) {
    uni.navigateTo({ url: `/pages/chat/detail?id=${session.id}` })
  }
}

function openSession(session: Session) {
  uni.navigateTo({ url: `/pages/chat/detail?id=${session.id}` })
}
</script>

<style scoped>
.chat-list-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: #f5f5f5;
}
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 24rpx 32rpx;
  background: #ffffff;
  border-bottom: 2rpx solid #f0f0f0;
}
.header-title {
  font-size: 36rpx;
  font-weight: 600;
  color: #303133;
}
.header-action {
  width: 56rpx;
  height: 56rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #667eea;
  border-radius: 50%;
}
.action-icon {
  font-size: 36rpx;
  color: #ffffff;
  font-weight: 300;
}
.session-list {
  flex: 1;
}
.loading-wrap {
  padding: 48rpx;
  text-align: center;
}
.loading-text {
  font-size: 28rpx;
  color: #909399;
}
</style>
