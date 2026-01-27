<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>会话详情 - {{ conversationId }}</span>
          <el-button @click="$router.back()">返回</el-button>
        </div>
      </template>

      <el-skeleton v-if="loading" :rows="3" animated />

      <div v-else class="conversation-detail">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="会话ID">
            {{ conversation?.id }}
          </el-descriptions-item>
          <el-descriptions-item label="用户ID">
            {{ conversation?.userId }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <StatusBadge :status="conversation?.status || ''" />
          </el-descriptions-item>
          <el-descriptions-item label="容器ID">
            {{ conversation?.containerId || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">
            {{ conversation?.createdAt }}
          </el-descriptions-item>
          <el-descriptions-item label="更新时间">
            {{ conversation?.updatedAt }}
          </el-descriptions-item>
        </el-descriptions>

        <el-divider content-position="left">消息记录</el-divider>

        <div class="messages-container">
          <div
            v-for="msg in messages"
            :key="msg.id"
            :class="['message-item', msg.role.toLowerCase()]"
          >
            <div class="message-role">{{ msg.role === 'USER' ? '用户' : 'AI' }}</div>
            <div class="message-content">{{ msg.content }}</div>
            <div class="message-time">{{ msg.createdAt }}</div>
          </div>
        </div>

        <el-divider content-position="left">实时事件</el-divider>

        <div class="events-container">
          <div v-for="event in events" :key="event.id" class="event-item">
            <el-tag size="small">{{ event.type }}</el-tag>
            <span class="event-data">{{ event.data }}</span>
            <span class="event-time">{{ event.createdAt }}</span>
          </div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import StatusBadge from '@/components/StatusBadge.vue'
import { getConversation, getMessages } from '@/api/conversation'
import { subscribeToEvents } from '@/api/event'
import type { Conversation, Message, Event } from '@/types'

const route = useRoute()
const conversationId = route.params.id as string

const loading = ref(false)
const conversation = ref<Conversation>()
const messages = ref<Message[]>([])
const events = ref<Event[]>([])

let eventSource: EventSource | null = null

const loadData = async () => {
  loading.value = true
  try {
    const [convData, messagesData] = await Promise.all([
      getConversation(conversationId),
      getMessages(conversationId)
    ])
    conversation.value = convData
    messages.value = messagesData
  } catch (error: any) {
    ElMessage.error(error.message || '加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadData()

  eventSource = subscribeToEvents(conversationId, {
    onEvent: (event) => {
      events.value.unshift(event)
      if (events.value.length > 100) {
        events.value = events.value.slice(0, 100)
      }
    },
    onError: (error) => {
      console.error('SSE error:', error)
    }
  })
})

onUnmounted(() => {
  eventSource?.close()
})
</script>

<style scoped>
.page-container {
  padding: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.conversation-detail {
  margin-top: 20px;
}

.messages-container {
  max-height: 400px;
  overflow-y: auto;
  padding: 10px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
}

.message-item {
  margin-bottom: 15px;
  padding: 10px;
  border-radius: 4px;
}

.message-item.user {
  background-color: #e3f2fd;
  text-align: right;
}

.message-item.assistant {
  background-color: #f5f5f5;
}

.message-role {
  font-weight: bold;
  margin-bottom: 5px;
  font-size: 12px;
  color: #606266;
}

.message-content {
  margin-bottom: 5px;
  white-space: pre-wrap;
}

.message-time {
  font-size: 12px;
  color: #909399;
}

.events-container {
  max-height: 300px;
  overflow-y: auto;
  padding: 10px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
}

.event-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px;
  border-bottom: 1px solid #f0f0f0;
}

.event-item:last-child {
  border-bottom: none;
}

.event-data {
  flex: 1;
  font-size: 14px;
}

.event-time {
  font-size: 12px;
  color: #909399;
}
</style>
