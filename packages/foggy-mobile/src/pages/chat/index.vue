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
      <view v-if="loading && sessions.length === 0" class="loading-wrap">
        <text class="loading-text">加载中...</text>
      </view>
      <view v-if="sessions.length > 0">
        <view v-for="session in sessions" :key="session.id" class="session-swipe-wrap">
          <SessionItem
            :session="session"
            @tap="openSession(session)"
          />
        </view>
      </view>
      <view v-if="!loading && sessions.length === 0" class="empty-wrap">
        <EmptyState
          title="暂无会话"
          description="点击右上角 + 开始新对话"
        />
      </view>
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
const loading = computed(() => sessionStore.loading)

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
  padding: 12px 16px;
  background: #ffffff;
  border-bottom: 1px solid #f0f0f0;
}
.header-title {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}
.header-action {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #667eea;
  border-radius: 50%;
}
.action-icon {
  font-size: 18px;
  color: #ffffff;
  font-weight: 300;
}
.session-list {
  flex: 1;
}
.empty-wrap {
  padding: 24px;
}
.loading-wrap {
  padding: 24px;
  text-align: center;
}
.loading-text {
  font-size: 14px;
  color: #909399;
}
</style>
