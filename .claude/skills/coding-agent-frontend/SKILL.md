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
│   ├── api/                # API 调用封装
│   │   ├── client.ts       # Axios 实例配置
│   │   ├── conversation.ts # 会话 API
│   │   ├── container.ts    # 容器 API
│   │   └── event.ts        # 事件 API
│   ├── components/         # 可复用组件
│   │   ├── ConversationList.vue
│   │   ├── ContainerCard.vue
│   │   ├── EventLog.vue
│   │   └── StatusBadge.vue
│   ├── views/              # 页面视图
│   │   ├── Dashboard.vue   # 系统监控首页
│   │   ├── Conversations.vue
│   │   ├── Containers.vue
│   │   └── Events.vue
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

### 4. SSE 事件流处理

```typescript
// src/api/event.ts
import type { Event } from '@/types'

export function subscribeToEvents(
  conversationId: string,
  handlers: {
    onEvent?: (event: Event) => void
    onError?: (error: any) => void
    onOpen?: () => void
  }
): EventSource {
  const baseURL = import.meta.env.VITE_API_BASE_URL || ''
  const url = `${baseURL}/api/v1/events/stream?conversationId=${conversationId}`

  const eventSource = new EventSource(url)

  eventSource.onopen = () => handlers.onOpen?.()

  eventSource.onmessage = (e) => {
    try {
      const event: Event = JSON.parse(e.data)
      handlers.onEvent?.(event)
    } catch (error) {
      console.error('Failed to parse event:', error)
    }
  }

  eventSource.onerror = (e) => handlers.onError?.(e)

  return eventSource
}
```

#### 在组件中使用 SSE

```vue
<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { subscribeToEvents } from '@/api/event'
import type { Event } from '@/types'

const props = defineProps<{
  conversationId: string
}>()

const events = ref<Event[]>([])
let eventSource: EventSource | null = null

onMounted(() => {
  eventSource = subscribeToEvents(props.conversationId, {
    onEvent: (event) => {
      events.value.unshift(event) // 新事件添加到顶部
      // 限制显示数量
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
