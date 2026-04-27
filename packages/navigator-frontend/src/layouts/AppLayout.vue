<template>
  <div class="app-layout">
    <header v-show="!isSessionFullscreen" class="app-header">
      <div class="header-brand" @click="router.push('/')">道同</div>
      <el-menu
        :default-active="activeMenu"
        mode="horizontal"
        :ellipsis="false"
        class="header-menu"
        router
      >
        <el-menu-item index="/">Workers</el-menu-item>
        <el-menu-item index="/chat">会话</el-menu-item>
        <el-menu-item index="/tasks">任务</el-menu-item>
        <el-menu-item index="/cross-tasks">跨项目</el-menu-item>
        <el-menu-item index="/monitoring">监控</el-menu-item>
        <el-menu-item index="/users">用户</el-menu-item>
        <el-menu-item index="/settings">设置</el-menu-item>
      </el-menu>
      <div class="header-right">
        <el-badge :value="unreadCount" :hidden="unreadCount === 0" :max="99" class="notification-badge">
          <el-icon class="header-icon" title="通知" @click="handleNotificationClick">
            <Bell />
          </el-icon>
        </el-badge>
        <el-dropdown @command="handleCommand">
          <span class="user-dropdown">
            {{ userInfo?.username || 'user' }}
            <el-icon class="el-icon--right"><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>
    <div class="app-content">
      <router-view v-slot="{ Component }">
        <keep-alive :max="8">
          <component :is="Component" :key="routeCacheKey" />
        </keep-alive>
      </router-view>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Bell, ArrowDown } from '@element-plus/icons-vue'
import { getUserInfo, clearAuth } from '@/utils/auth'
import { resetSetupStatus, clearSetupSkipped } from '@/router'
import { useNotifications } from '@/composables/useNotifications'
import { useSessionFullscreen } from '@/composables/useSessionFullscreen'

const route = useRoute()
const router = useRouter()
const userInfo = getUserInfo()
const { unreadCount, connect: connectNotifications, markAllRead, requestPermission } = useNotifications()
const { isSessionFullscreen } = useSessionFullscreen()

const activeMenu = computed(() => {
  const path = route.path
  // For /c/:id routes, highlight the chat menu item
  if (path.startsWith('/c/')) return '/chat'
  return path
})

// Home and Chat both use ChatView — share one cache key so keep-alive reuses the same instance
const routeCacheKey = computed(() => {
  const name = route.name as string
  if (name === 'Home' || name === 'Chat') return 'ChatView'
  return name
})

onMounted(() => {
  connectNotifications()
  requestPermission()
})

function handleNotificationClick() {
  markAllRead()
}

function handleCommand(command: string) {
  if (command === 'logout') {
    clearAuth()
    resetSetupStatus()
    clearSetupSkipped()
    router.push('/login')
  }
}
</script>

<style scoped>
.app-layout {
  display: flex;
  flex-direction: column;
  height: 100vh;
  height: 100dvh; /* iOS/iPadOS: use dynamic viewport height to avoid Safari toolbar overlap */
}

.app-header {
  height: 48px;
  display: flex;
  align-items: center;
  padding: 0 16px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  flex-shrink: 0;
}

.header-brand {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
  cursor: pointer;
  white-space: nowrap;
  margin-right: 24px;
}

.header-brand:hover {
  color: #409eff;
}

.header-menu {
  flex: 1;
  border-bottom: none !important;
  height: 48px;
}

.header-menu .el-menu-item {
  height: 48px;
  line-height: 48px;
  font-size: 14px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-left: 16px;
}

.header-icon {
  font-size: 18px;
  color: #606266;
  cursor: pointer;
  transition: color 0.2s;
}

.header-icon:hover {
  color: #409eff;
}

.notification-badge :deep(.el-badge__content) {
  font-size: 10px;
  height: 16px;
  line-height: 16px;
  padding: 0 4px;
}

.user-dropdown {
  display: flex;
  align-items: center;
  font-size: 14px;
  color: #606266;
  cursor: pointer;
  white-space: nowrap;
}

.user-dropdown:hover {
  color: #409eff;
}

.app-content {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
</style>
