<template>
  <div class="chat-layout">
    <!-- 左侧 sidebar -->
    <aside class="sidebar">
      <div class="sidebar-header">
        <el-button type="primary" style="width: 100%" @click="handleNewSession">
          + 新建会话
        </el-button>
      </div>
      <div class="session-list">
        <div
          v-for="session in sessionStore.sessions"
          :key="session.id"
          :class="['session-item', { active: session.id === sessionStore.activeSessionId }]"
          @click="switchSession(session.id)"
        >
          <div class="session-title">{{ session.taskName || '新会话' }}</div>
          <div class="session-meta">{{ formatTime(session.createdAt) }}</div>
          <el-icon
            class="session-delete"
            @click.stop="handleDeleteSession(session.id)"
          >
            <Close />
          </el-icon>
        </div>
        <div v-if="sessionStore.sessions.length === 0" class="empty-hint">
          暂无会话，点击上方按钮新建
        </div>
      </div>
      <div class="sidebar-footer">
        <span class="username">{{ userInfo?.username }}</span>
        <el-button text size="small" @click="handleLogout">退出</el-button>
      </div>
    </aside>

    <!-- 右侧聊天区域 -->
    <main class="chat-main">
      <template v-if="sessionStore.activeSessionId">
        <ChatPanel
          :messages="chatStore.sortedMessages"
          :is-thinking="chatStore.isThinking"
          :connection-status="chatStore.connectionStatus"
          :conversation-status="chatStore.conversationStatus"
          placeholder="输入消息，按回车发送..."
          @send="handleSend"
        >
          <template #header-left>
            <span class="chat-title">{{ activeSession?.taskName || '会话' }}</span>
          </template>
          <template #empty>
            <div class="guide-cards-container">
              <h3 class="guide-title">你可以这样开始</h3>
              <div class="guide-cards">
                <div
                  v-for="card in guideCards"
                  :key="card.title"
                  class="guide-card"
                  @click="handleGuideClick(card)"
                >
                  <span class="guide-icon">{{ card.icon }}</span>
                  <div class="guide-text">
                    <div class="guide-card-title">{{ card.title }}</div>
                    <div class="guide-card-desc">{{ card.description }}</div>
                  </div>
                </div>
              </div>
            </div>
          </template>
        </ChatPanel>
      </template>
      <template v-else>
        <div class="empty-state">
          <h2>Foggy Navigator</h2>
          <p>选择一个会话或新建会话开始对话</p>
        </div>
      </template>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Close } from '@element-plus/icons-vue'
import { ChatPanel, useChatStore } from '@foggy/chat'
import { useSessionStore } from '@/stores/sessionStore'
import { useSession, setRouteRequestHandler } from '@/composables/useSession'
import { getUserInfo, clearAuth } from '@/utils/auth'
import * as sessionApi from '@/api/session'
import type { GuideCard, RoutePayload } from '@/types'

const route = useRoute()
const router = useRouter()
const sessionStore = useSessionStore()
const chatStore = useChatStore()
const { connectToSession, sendMessage, disconnectSession } = useSession()

const userInfo = getUserInfo()
const guideCards = ref<GuideCard[]>([])

const activeSession = computed(() =>
  sessionStore.sessions.find((s) => s.id === sessionStore.activeSessionId),
)

onMounted(async () => {
  await sessionStore.loadSessions()

  // 加载引导卡片
  try {
    guideCards.value = await sessionApi.getGuideCards('tutor-agent')
  } catch {
    // 加载失败使用默认卡片
    guideCards.value = [
      { icon: '🔧', title: '检查配置', description: '帮我检查系统配置状态' },
      { icon: '📊', title: '数据源', description: '查看数据源连接情况' },
      { icon: '📐', title: '语义层', description: '了解语义层模型' },
    ]
  }

  // 设置路由请求处理器（用于处理 Agent 委托跳转）
  setRouteRequestHandler(handleRouteRequest)

  // 如果 URL 带有 session id，自动连接
  const id = route.params.id as string | undefined
  if (id) {
    sessionStore.setActiveSession(id)
    await connectToSession(id)
  }
})

onUnmounted(() => {
  disconnectSession()
  setRouteRequestHandler(null) // 清理路由请求处理器
})

// 监听路由参数变化
watch(
  () => route.params.id,
  async (newId) => {
    const id = newId as string | undefined
    if (id && id !== sessionStore.activeSessionId) {
      sessionStore.setActiveSession(id)
      await connectToSession(id)
    } else if (!id) {
      sessionStore.setActiveSession(null)
      disconnectSession()
      chatStore.clearMessages()
    }
  },
)

async function switchSession(id: string) {
  if (id === sessionStore.activeSessionId) return
  router.push(`/c/${id}`)
}

async function handleNewSession() {
  try {
    const session = await sessionStore.createSession('新会话')
    router.push(`/c/${session.id}`)
  } catch {
    ElMessage.error('创建会话失败')
  }
}

async function handleDeleteSession(id: string) {
  try {
    await ElMessageBox.confirm('确认删除该会话？', '提示', {
      type: 'warning',
      confirmButtonText: '确认',
      cancelButtonText: '取消',
    })
    await sessionStore.deleteSession(id)
    if (sessionStore.activeSessionId === null) {
      router.push('/')
    }
  } catch {
    // 用户取消
  }
}

function handleSend(content: string) {
  const id = sessionStore.activeSessionId
  if (!id) return
  sendMessage(id, content)
}

function handleGuideClick(card: GuideCard) {
  const id = sessionStore.activeSessionId
  if (!id) return
  sendMessage(id, card.description)
}

function handleLogout() {
  clearAuth()
  router.push('/login')
}

/** 处理 Agent 发起的路由请求（委托跳转） */
async function handleRouteRequest(payload: RoutePayload) {
  console.log('Handling route request:', payload)

  if (payload.action === 'DELEGATE' && payload.target.sessionId) {
    // 委托跳转：导航到新的子会话
    const targetSessionId = payload.target.sessionId

    // 刷新会话列表以显示新创建的子会话
    await sessionStore.loadSessions()

    // 根据 mode 决定跳转方式
    if (payload.mode === 'NEW_TAB') {
      // 在新标签页打开
      window.open(`/c/${targetSessionId}`, '_blank')
    } else {
      // 替换当前页面（默认行为）
      router.push(`/c/${targetSessionId}`)
    }

    ElMessage.success(`已转接到 ${payload.target.agentId || '专业助手'}`)
  } else if (payload.action === 'RETURN' && payload.target.sessionId) {
    // 返回父会话
    router.push(`/c/${payload.target.sessionId}`)
    ElMessage.info('已返回上级会话')
  } else if (payload.action === 'NAVIGATE' && payload.target.url) {
    // 导航到指定 URL
    if (payload.mode === 'NEW_TAB') {
      window.open(payload.target.url, '_blank')
    } else {
      window.location.href = payload.target.url
    }
  }
}

function formatTime(dateStr: string): string {
  const d = new Date(dateStr)
  const now = new Date()
  if (d.toDateString() === now.toDateString()) {
    return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  return d.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
}
</script>

<style scoped>
.chat-layout {
  display: flex;
  height: 100vh;
}

.sidebar {
  width: 240px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: #f7f8fa;
  border-right: 1px solid #e4e7ed;
}

.sidebar-header {
  padding: 12px;
  border-bottom: 1px solid #e4e7ed;
}

.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.session-item {
  position: relative;
  padding: 10px 30px 10px 12px;
  margin-bottom: 4px;
  border-radius: 6px;
  cursor: pointer;
  transition: background-color 0.2s;
}

.session-item:hover {
  background: #ebeef5;
}

.session-item.active {
  background: #e1eaff;
}

.session-title {
  font-size: 14px;
  color: #303133;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.session-meta {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.session-delete {
  position: absolute;
  right: 8px;
  top: 50%;
  transform: translateY(-50%);
  font-size: 14px;
  color: #c0c4cc;
  opacity: 0;
  transition: opacity 0.2s;
}

.session-item:hover .session-delete {
  opacity: 1;
}

.session-delete:hover {
  color: #f56c6c;
}

.empty-hint {
  text-align: center;
  color: #909399;
  font-size: 13px;
  padding: 24px 0;
}

.sidebar-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 12px;
  border-top: 1px solid #e4e7ed;
  font-size: 13px;
}

.username {
  color: #606266;
}

.chat-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.chat-main > :deep(.chat-panel) {
  flex: 1;
  border: none;
  border-radius: 0;
}

.chat-title {
  font-weight: 600;
  font-size: 15px;
  color: #303133;
}

.empty-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  color: #909399;
}

.empty-state h2 {
  font-size: 24px;
  color: #606266;
  margin-bottom: 8px;
}

.empty-state p {
  font-size: 14px;
}

/* Guide Cards */
.guide-cards-container {
  text-align: center;
  max-width: 600px;
  width: 100%;
}

.guide-title {
  color: #606266;
  font-size: 16px;
  font-weight: 500;
  margin-bottom: 20px;
}

.guide-cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.guide-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 18px;
  background: #f7f8fa;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
  text-align: left;
}

.guide-card:hover {
  background: #ecf5ff;
  border-color: #409eff;
}

.guide-icon {
  font-size: 24px;
  flex-shrink: 0;
}

.guide-text {
  flex: 1;
  min-width: 0;
}

.guide-card-title {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
  margin-bottom: 2px;
}

.guide-card-desc {
  font-size: 13px;
  color: #909399;
}
</style>
