---
name: coding-agent-frontend
description: Coding Agent 前端管理控制台开发指导。当用户需要开发 coding-agent 模块的 Vue 3 前端功能、添加页面、修改组件、调用 API 时使用。触发词：/ca-frontend, /dev-frontend, 提及"前端开发"、"管理控制台"、"Vue组件"。
---

# Coding Agent 前端开发指导

为 `addons/coding-agent/frontend` 模块提供 Vue 3 管理控制台开发的标准化指导。

## 使用场景

当用户需要以下操作时激活：
- 创建新的页面或组件
- 修改现有页面逻辑
- 调用后端 API
- 处理 SSE 实时事件流
- 添加表单验证或交互功能
- 优化界面或修复 Bug

## 技术栈

- **框架**: Vue 3 (Composition API + `<script setup>`)
- **UI 库**: Element Plus
- **类型**: TypeScript
- **构建**: Vite
- **HTTP**: Axios
- **路由**: Vue Router
- **状态**: Pinia (可选)

## 模块结构

```
addons/coding-agent/frontend/
├── src/
│   ├── adapters/           # 事件适配器
│   │   └── OpenHandsAdapter.ts  # OH 事件 → AIP 消息转换
│   ├── api/                # API 调用封装
│   │   ├── client.ts       # Axios 实例配置
│   │   ├── auth.ts         # 认证 API
│   │   ├── conversation.ts # 会话 API
│   │   ├── container.ts    # 容器 API
│   │   └── event.ts        # SSE 事件流（使用 @foggy/chat）
│   ├── components/         # 业务组件
│   │   ├── ContainerCard.vue
│   │   └── EventLog.vue
│   ├── views/              # 页面视图
│   │   ├── Dashboard.vue   # 系统监控首页
│   │   ├── Conversations.vue
│   │   ├── ConversationDetail.vue  # 对话聊天界面
│   │   ├── Containers.vue
│   │   ├── Events.vue
│   │   └── Login.vue
│   ├── types/              # TypeScript 类型定义
│   │   └── index.ts
│   ├── router/             # 路由配置
│   │   └── index.ts
│   ├── App.vue
│   └── main.ts
├── public/
├── index.html
├── package.json
├── vite.config.ts
└── tsconfig.json

packages/foggy-chat/         # 共享聊天组件库（workspace 依赖）
├── src/
│   ├── types/              # AIP 协议类型
│   │   ├── aip.ts          # AipMessage、AipMessageType
│   │   ├── adapter.ts      # EventAdapter 接口
│   │   └── chat.ts         # ChatMessage UI 模型
│   ├── sse/
│   │   └── SseClient.ts    # SSE 客户端（支持命名事件）
│   ├── store/
│   │   └── useChatStore.ts # Pinia store（消息处理 + 状态管理）
│   ├── components/         # 聊天 UI 组件
│   │   ├── ChatPanel.vue       # 顶层聊天面板
│   │   ├── MessageList.vue     # 消息滚动列表
│   │   ├── MessageBubble.vue   # 文本消息气泡
│   │   ├── ToolCallBlock.vue   # 工具调用卡片
│   │   ├── ThinkingIndicator.vue
│   │   ├── ErrorBlock.vue
│   │   ├── StatusBadge.vue
│   │   └── MessageInput.vue
│   └── index.ts            # 主入口导出
├── dist/                   # 构建输出
├── package.json
└── vite.config.ts
```

## 执行流程

### 1. 环境准备

```bash
# 进入前端目录
cd addons/coding-agent/frontend

# 安装依赖
npm install

# 开发模式
npm run dev

# 构建到 static 目录
npm run build
```

### 2. 创建新页面

#### 页面组件模板

```vue
<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>页面标题</span>
          <el-button type="primary" @click="handleAction">
            操作按钮
          </el-button>
        </div>
      </template>

      <!-- 加载状态 -->
      <el-skeleton v-if="loading" :rows="5" animated />

      <!-- 数据展示 -->
      <el-table v-else :data="dataList" stripe>
        <el-table-column prop="id" label="ID" width="200" />
        <el-table-column prop="status" label="状态">
          <template #default="{ row }">
            <StatusBadge :status="row.status" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button link type="primary" @click="handleView(row)">
              查看
            </el-button>
            <el-button link type="danger" @click="handleDelete(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusBadge from '@/components/StatusBadge.vue'
import { fetchData, deleteItem } from '@/api/example'
import type { DataItem } from '@/types'

const loading = ref(false)
const dataList = ref<DataItem[]>([])

const loadData = async () => {
  loading.value = true
  try {
    dataList.value = await fetchData()
  } catch (error: any) {
    ElMessage.error(error.message || '加载失败')
  } finally {
    loading.value = false
  }
}

const handleView = (row: DataItem) => {
  // 查看逻辑
}

const handleDelete = async (row: DataItem) => {
  await ElMessageBox.confirm('确认删除？', '警告', {
    type: 'warning'
  })
  await deleteItem(row.id)
  ElMessage.success('删除成功')
  loadData()
}

const handleAction = () => {
  // 操作逻辑
}

onMounted(() => {
  loadData()
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
</style>
```

### 3. API 调用封装

#### Axios 客户端配置

```typescript
// src/api/client.ts
import axios from 'axios'
import { ElMessage } from 'element-plus'

const client = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeout: 30000
})

client.interceptors.response.use(
  response => response.data,
  error => {
    const message = error.response?.data?.message || error.message
    ElMessage.error(message)
    return Promise.reject(error)
  }
)

export default client
```

#### API 模块示例

```typescript
// src/api/conversation.ts
import client from './client'
import type { Conversation, CreateConversationRequest } from '@/types'

export async function listConversations(params?: {
  userId?: string
  status?: string
}): Promise<Conversation[]> {
  return client.get('/conversations', { params })
}

export async function getConversation(id: string): Promise<Conversation> {
  return client.get(`/conversations/${id}`)
}

export async function createConversation(
  request: CreateConversationRequest
): Promise<Conversation> {
  return client.post('/conversations', request)
}

export async function deleteConversation(id: string): Promise<void> {
  return client.delete(`/conversations/${id}`)
}

export async function stopConversation(id: string): Promise<void> {
  return client.post(`/conversations/${id}/stop`)
}
```

### 4. 聊天界面（使用 @foggy/chat）

对话详情页使用共享的 `@foggy/chat` 聊天组件库。

#### ConversationDetail.vue 示例

```vue
<template>
  <div class="chat-page">
    <ChatPanel
      :messages="chatStore.sortedMessages"
      :is-thinking="chatStore.isThinking"
      :connection-status="chatStore.connectionStatus"
      :conversation-status="chatStore.conversationStatus"
      :input-disabled="sending"
      @send="handleSend"
    >
      <template #header>
        <div class="header-left">
          <el-button text @click="router.push('/conversations')">← 返回</el-button>
          <span class="conv-id">{{ shortId }}</span>
        </div>
        <div class="header-right">
          <StatusBadge v-if="chatStore.conversationStatus" :status="chatStore.conversationStatus" />
          <span :class="['connection-dot', chatStore.connectionStatus]" />
        </div>
      </template>
    </ChatPanel>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChatPanel, StatusBadge, useChatStore, AipMessageType } from '@foggy/chat'
import { getConversation, getMessages, sendMessage } from '@/api/conversation'
import { subscribeToConversation } from '@/api/event'

const route = useRoute()
const router = useRouter()
const conversationId = route.params.id as string
const chatStore = useChatStore()
const sending = ref(false)
let eventSource: EventSource | null = null

async function loadHistory() {
  const [conv, msgs] = await Promise.all([
    getConversation(conversationId),
    getMessages(conversationId),
  ])
  chatStore.conversationStatus = conv.status
  for (const m of msgs) {
    chatStore.messages.push({
      id: m.messageId,
      type: AipMessageType.TEXT_COMPLETE,
      sender: m.role === 'ASSISTANT' ? 'assistant' : 'user',
      content: m.content,
      timestamp: new Date(m.timestamp).getTime(),
    })
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
    onConnected: () => chatStore.setConnectionStatus('connected'),
    onError: () => chatStore.setConnectionStatus('error'),
  })
}

async function handleSend(content: string) {
  sending.value = true
  chatStore.addUserMessage(content)
  try {
    await sendMessage({ conversationId, content })
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
```

#### SSE 订阅（使用 @foggy/chat SseClient）

```typescript
// src/api/event.ts
import { createSseClient } from '@foggy/chat'
import type { AipMessage } from '@foggy/chat'
import { openHandsAdapter } from '@/adapters/OpenHandsAdapter'

// 开发模式直连后端（绕过 Vite 代理，避免 SSE 问题）
const sseBase = import.meta.env.DEV
  ? 'http://localhost:8112/api/v1'
  : (import.meta.env.VITE_API_BASE_URL || '/api/v1')

export function subscribeToConversation(
  conversationId: string,
  handlers: {
    onMessage: (messages: AipMessage[]) => void
    onConnected?: () => void
    onError?: (error: Event) => void
  }
): EventSource {
  return createSseClient({
    url: `${sseBase}/events/stream?conversationId=${conversationId}`,
    eventName: 'event',  // 匹配后端 .name("event")
    adapter: openHandsAdapter,
    sessionId: conversationId,
    onMessage: handlers.onMessage,
    onConnected: handlers.onConnected,
    onError: handlers.onError,
  })
}
```

#### OpenHands 适配器

```typescript
// src/adapters/OpenHandsAdapter.ts
import { AipMessageType } from '@foggy/chat'
import type { AipMessage, EventAdapter } from '@foggy/chat'

export interface OhRawEvent {
  id: string
  conversationId: string
  kind: string  // MESSAGE_SENT, AGENT_ACTION, AGENT_OBSERVATION, CONVERSATION_STATUS, ERROR
  data: Record<string, unknown>
  createdAt: string
}

export const openHandsAdapter: EventAdapter<OhRawEvent> = {
  convert(raw: OhRawEvent, sessionId: string): AipMessage[] {
    // 根据 raw.kind 和 raw.data 结构转换为 AipMessage[]
    // 详见实际代码 addons/coding-agent/frontend/src/adapters/OpenHandsAdapter.ts
  },
}
```

### 5. 路由配置

```typescript
// src/router/index.ts
import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    redirect: '/dashboard'
  },
  {
    path: '/dashboard',
    name: 'Dashboard',
    component: () => import('@/views/Dashboard.vue'),
    meta: { title: '系统监控' }
  },
  {
    path: '/conversations',
    name: 'Conversations',
    component: () => import('@/views/Conversations.vue'),
    meta: { title: '会话管理' }
  },
  {
    path: '/conversations/:id',
    name: 'ConversationDetail',
    component: () => import('@/views/ConversationDetail.vue'),
    meta: { title: '会话详情' }
  },
  {
    path: '/containers',
    name: 'Containers',
    component: () => import('@/views/Containers.vue'),
    meta: { title: '容器管理' }
  },
  {
    path: '/events',
    name: 'Events',
    component: () => import('@/views/Events.vue'),
    meta: { title: '事件日志' }
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

export default router
```

## 约束条件

### 代码规范
- 使用 Composition API + `<script setup>` 语法
- TypeScript 严格模式，所有参数和返回值必须有类型
- 组件命名使用 PascalCase（如 `ConversationList.vue`）
- API 函数命名使用 camelCase（如 `listConversations`）

### UI 规范
- 使用 Element Plus 组件，保持风格一致
- 加载状态使用 `el-skeleton` 或 `v-loading`
- 错误提示使用 `ElMessage.error()`
- 确认操作使用 `ElMessageBox.confirm()`
- 表格操作按钮使用 `link` 类型

### 状态管理
- 简单状态使用组件内 `ref`/`reactive`
- 跨组件共享状态考虑使用 Pinia
- SSE 连接在 `onUnmounted` 中关闭

### 构建配置
- 开发环境 API 代理到 `http://localhost:8112`
- 生产构建输出到 `../src/main/resources/static`
- 使用 hash 路由模式（`createWebHashHistory`）

## 决策规则

- 如果需要调用 API → 在 `src/api/` 下创建或使用对应模块
- 如果需要新页面 → 在 `src/views/` 创建，并添加路由
- 如果组件可复用 → 提取到 `src/components/`
- 如果需要实时数据 → 使用 SSE 订阅
- 如果需要类型定义 → 在 `src/types/index.ts` 添加
- 如果修改了页面 → 确保加载状态和错误处理完整

## 常用命令

```bash
# 开发
cd addons/coding-agent/frontend && npm run dev

# 构建
cd addons/coding-agent/frontend && npm run build

# 类型检查
cd addons/coding-agent/frontend && npm run type-check

# 代码检查
cd addons/coding-agent/frontend && npm run lint
```

## 参考文件

详细的技术参考请查看：
- [reference.md](./reference.md) - API 类型和组件参考
