<template>
  <view class="chat-list-page">
    <!-- 头部 -->
    <view class="page-header">
      <text class="header-title">会话</text>
      <view class="header-action" :class="{ disabled: creating }" @tap="createNewSession">
        <text class="action-icon">{{ creating ? '...' : '+' }}</text>
      </view>
    </view>

    <!-- 会话列表 -->
    <view class="session-list">
      <view v-if="loading && sessions.length === 0" class="loading-wrap">
        <text class="loading-text">加载中...</text>
      </view>
      <view v-if="sessions.length > 0">
        <view v-for="session in sessions" :key="session.id" class="session-swipe-wrap">
          <SessionItem
            :session="session"
            @tap="openSession(session)"
            @delete="showSessionActions(session)"
          />
        </view>
      </view>
      <view v-if="!loading && sessions.length === 0" class="empty-wrap">
        <EmptyState
          title="暂无会话"
          description="点击右上角 + 开始新对话"
        />
      </view>
    </view>

    <!-- #ifdef APP-PLUS -->
    <UpgradePopup />
    <!-- #endif -->
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app'
import { useSessionStore } from '@/stores/session'
import type { Session } from '@/api/types'
import SessionItem from '@/components/SessionItem.vue'
import EmptyState from '@/components/EmptyState.vue'
// #ifdef APP-PLUS
import UpgradePopup from '@/components/UpgradePopup.vue'
// #endif

const sessionStore = useSessionStore()
const creating = ref(false)
const loading = computed(() => sessionStore.loading)

const sessions = computed(() =>
  [...sessionStore.sessions].sort(
    (a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime(),
  ),
)

onShow(() => {
  sessionStore.loadSessions()
})

onPullDownRefresh(async () => {
  await sessionStore.loadSessions()
  uni.stopPullDownRefresh()
})

async function createNewSession() {
  if (creating.value) return
  creating.value = true
  try {
    const session = await sessionStore.createSession('新会话')
    if (session) {
      uni.navigateTo({ url: `/pages/chat/detail?id=${session.id}` })
    }
  } finally {
    creating.value = false
  }
}

function openSession(session: Session) {
  uni.navigateTo({ url: `/pages/chat/detail?id=${session.id}` })
}

function showSessionActions(session: Session) {
  uni.showActionSheet({
    itemList: ['删除会话'],
    success: async (res) => {
      if (res.tapIndex === 0) {
        uni.showModal({
          title: '确认删除',
          content: `确定删除「${session.taskName || '新会话'}」？`,
          success: async (modalRes) => {
            if (modalRes.confirm) {
              await sessionStore.removeSession(session.id)
              uni.showToast({ title: '已删除', icon: 'success' })
            }
          },
        })
      }
    },
  })
}
</script>

<style scoped>
.chat-list-page {
  min-height: 100vh;
  background: #f5f5f5;
}
.page-header {
  position: sticky;
  top: 0;
  z-index: 10;
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
.header-action.disabled {
  opacity: 0.5;
}
.session-list {
  /* native page scroll, no fixed height */
}
.empty-wrap {
  padding: 48rpx;
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
