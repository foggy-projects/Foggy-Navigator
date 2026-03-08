<template>
  <div class="chat-page">
    <ChatPanel
      :messages="chatStore.sortedMessages"
      :is-thinking="chatStore.isThinking"
      :connection-status="chatStore.connectionStatus"
      :conversation-status="chatStore.conversationStatus"
      :input-disabled="sending"
      placeholder="输入消息..."
      @send="handleSend"
    >
      <template #header>
        <div class="header-left">
          <el-button text @click="router.push('/conversations')">
            <span style="margin-right:4px">&larr;</span>返回
          </el-button>
          <span class="conv-id">{{ shortId }}</span>
        </div>
        <div class="header-right">
          <StatusBadge v-if="chatStore.conversationStatus" :status="chatStore.conversationStatus" />
          <span
            :class="['connection-dot', chatStore.connectionStatus]"
            :title="connectionLabel"
          />
        </div>
      </template>
    </ChatPanel>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ChatPanel, StatusBadge, useChatStore, AipMessageType } from '@foggy/chat'
import type { ConnectionStatus } from '@foggy/chat'
import { getConversation, getMessages, sendMessage } from '@/api/conversation'
import { subscribeToConversation } from '@/api/event'

const route = useRoute()
const router = useRouter()
const conversationId = route.params.id as string
const chatStore = useChatStore()
const sending = ref(false)

let eventSource: EventSource | null = null

const shortId = computed(() =>
  conversationId.length > 12 ? conversationId.slice(0, 12) + '...' : conversationId
)

const connectionLabel = computed(() => {
  const map: Record<ConnectionStatus, string> = {
    disconnected: '未连接',
    connecting: '连接中',
    connected: '已连接',
    error: '连接错误',
  }
  return map[chatStore.connectionStatus] ?? ''
})

async function loadHistory() {
  try {
    const [conv, msgs] = await Promise.all([
      getConversation(conversationId),
      getMessages(conversationId),
    ])
    chatStore.conversationStatus = conv.status
    // Convert existing messages to chat messages
    // Backend returns: { messageId, conversationId, content, timestamp }
    // Messages from this API are user-sent; agent replies come via SSE events
    for (const m of msgs) {
      // Handle Java timestamp with nanosecond precision (trim to ms)
      const tsStr = m.timestamp?.replace(/(\.\d{3})\d*$/, '$1')
      chatStore.messages.push({
        id: m.messageId,
        type: AipMessageType.TEXT_COMPLETE,
        sender: m.role === 'ASSISTANT' ? 'assistant' : 'user',
        content: m.content,
        timestamp: tsStr ? new Date(tsStr).getTime() : Date.now(),
      })
    }
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : '加载历史消息失败'
    ElMessage.error(msg)
  }
}

function connectSse() {
  chatStore.setConnectionStatus('connecting')
  eventSource = subscribeToConversation(conversationId, {
    onMessage: (aipMessages) => {
      for (const m of aipMessages) {
        chatStore.processAipMessage(m)
      }
    },
    onConnected: () => {
      chatStore.setConnectionStatus('connected')
    },
    onError: () => {
      chatStore.setConnectionStatus('error')
    },
  })
}

async function handleSend(content: string) {
  sending.value = true
  chatStore.addUserMessage(content, conversationId)
  try {
    await sendMessage({ conversationId, content })
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : '发送失败'
    ElMessage.error(msg)
  } finally {
    sending.value = false
  }
}

onMounted(async () => {
  chatStore.clearMessages()
  await loadHistory()
  connectSse()
})

onUnmounted(() => {
  eventSource?.close()
  chatStore.setConnectionStatus('disconnected')
})
</script>

<style scoped>
.chat-page {
  height: calc(100vh - 60px);
  padding: 16px;
  box-sizing: border-box;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.conv-id {
  font-weight: 600;
  font-size: 14px;
  color: #303133;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.connection-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}

.connection-dot.connected {
  background-color: #67c23a;
}

.connection-dot.connecting {
  background-color: #e6a23c;
  animation: blink 1s infinite;
}

.connection-dot.disconnected {
  background-color: #c0c4cc;
}

.connection-dot.error {
  background-color: #f56c6c;
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}
</style>
