---
name: coding-agent-frontend
description: 前端开发指导（Coding Agent 管理控制台 + Navigator 主前端）。当用户需要开发 Vue 3 前端功能、添加页面、修改组件、调用 API 时使用。覆盖 coding-agent/frontend（OpenHands 管理台）和 navigator-frontend（主前端含 ClaudeWorkerView）。触发词：/ca-frontend, /dev-frontend, 提及"前端开发"、"管理控制台"、"Vue组件"、"ClaudeWorkerView"。
---

# 前端开发指导

为两个 Vue 3 前端模块提供开发指导：
- `addons/coding-agent/frontend` — OpenHands 管理控制台
- `packages/navigator-frontend` — Navigator 主前端（含 ClaudeWorkerView、Setup 向导等）

## 使用场景

当用户需要以下操作时激活：
- 创建新的页面或组件
- 修改现有页面逻辑（ClaudeWorkerView、会话管理等）
- 调用后端 API（claudeWorker API、会话配置等）
- 处理 SSE 实时事件流
- 添加表单验证或交互功能
- 修改 composable（useClaudeWorker 等）
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
# Coding Agent 前端
cd addons/coding-agent/frontend && npm run dev
cd addons/coding-agent/frontend && npm run build

# Navigator 主前端
cd packages/navigator-frontend && pnpm dev
cd packages/navigator-frontend && pnpm exec vite build
```

---

## Navigator 主前端 (packages/navigator-frontend)

### 模块结构

```
packages/navigator-frontend/
├── src/
│   ├── api/
│   │   ├── client.ts            # Axios 实例（JWT 认证）
│   │   ├── claudeWorker.ts      # Claude Worker API（任务/目录/会话配置）
│   │   ├── fileBrowser.ts       # 文件浏览器 API（目录/文件/搜索/diff）
│   │   ├── session.ts           # 通用会话 API
│   │   ├── platform.ts          # 平台配置 API（Git/LLM/Agent Model）
│   │   ├── memory.ts            # 用户记忆 API
│   │   └── auth.ts              # 认证 API
│   ├── components/
│   │   └── file-browser/
│   │       └── FileSearchDialog.vue  # 文件搜索弹窗（文件名/内容搜索）
│   ├── composables/
│   │   └── useClaudeWorker.ts   # Claude Worker 组合函数
│   ├── views/
│   │   ├── ClaudeWorkerView.vue # Claude Worker 主页面（核心）
│   │   ├── FileBrowserView.vue  # 文件浏览器（目录树 + Monaco 编辑器 + 搜索）
│   │   ├── HomeView.vue         # 首页
│   │   ├── SetupView.vue        # 初始化向导
│   │   └── ...
│   ├── types/
│   │   └── index.ts             # 全局类型定义
│   ├── router/
│   │   └── index.ts
│   ├── App.vue
│   └── main.ts
├── index.html
├── package.json
└── vite.config.ts               # 端口 5174，代理到 8112
```

### ClaudeWorkerView.vue 核心概念

ClaudeWorkerView 是 Claude Worker 功能的主页面，采用左右分栏布局：

**左侧面板**：Worker 树 + 工作目录 + 会话历史
- Worker 列表（注册/编辑/删除/健康检查）
- 工作目录管理（添加/编辑/删除/同步 Git）
- Agent Teams 标签栏 + useTeams 开关
- 历史会话列表（按目录分组，支持置顶/标题/Auth 绑定）

**右侧面板**：对话区（ChatPanel from @foggy/chat）
- SSE 实时消息流
- 工具调用卡片
- 任务状态/费用显示

**核心数据结构**：

```typescript
// 会话分组
interface ConversationGroup {
  sessionId: string
  claudeSessionId: string
  tasks: ClaudeTask[]
  firstPrompt: string
  latestTask: ClaudeTask
  config?: ConversationConfig  // 合并自 conversationConfigs
}

// 任务表单
const taskForm = ref({
  prompt: '',
  model: '',
  maxTurns: undefined,
  useTeams: true,  // Agent Teams 开关
})
```

**排序规则**：
1. pinned 会话置顶（按 pinnedAt 排序）
2. 其余按 createdAt 降序

**显示优先级**：
- 标题：`config.customTitle` > `firstPrompt`（截断）

### FileBrowserView.vue — 文件浏览器

路由 `/#/files?directoryId=xxx`，从 ClaudeWorkerView 的「浏览文件」按钮跳转。

**架构**：三层透传
```
Vue (fileBrowser.ts) → Java (FileBrowserController) → Python Worker (files.py)
```

**功能模块**：
- **目录树**（左侧）：el-tree-v2 虚拟滚动，懒加载子目录
- **Monaco 编辑器**（右侧）：只读代码查看 + diff 视图
- **Git 改动**：侧边栏 tab 切换，显示变更文件列表 + diff 预览
- **文件搜索**（Ctrl+P）：`git ls-files` → 文件名模糊匹配，选中后 `loadFile()`
- **内容搜索**（Ctrl+Shift+F）：`git grep -F` → 全文检索，选中后跳转到对应行

**API 端点** (`fileBrowser.ts`)：

| 函数 | 端点 | 用途 |
|------|------|------|
| `listDirectory(id, sub)` | GET `/file-browser/list` | 列出目录 |
| `readFileContent(id, sub)` | GET `/file-browser/content` | 读取文件 |
| `searchFiles(id, q)` | GET `/file-browser/search` | 文件名搜索 |
| `searchContent(id, q)` | GET `/file-browser/search-content` | 内容全文搜索 |
| `getGitDiffSummary(id)` | GET `/file-browser/git-diff` | Git 变更摘要 |
| `getFileDiff(id, file)` | GET `/file-browser/git-diff/file` | 单文件 diff |

**搜索组件** (`FileSearchDialog.vue`)：
- `Teleport` 到 body 的模态弹窗，`mode` prop 控制文件/内容模式
- 300ms debounce 输入，↑↓ 键盘导航，Enter 选中，Esc 关闭
- 内容模式额外显示「区分大小写」复选框和上下文行

### Claude Worker API (api/claudeWorker.ts)

所有函数从 `RX<T>` 包装中提取 `data` 返回。

| 函数 | 方法 | 端点 |
|------|------|------|
| `listWorkers()` | GET | `/claude-workers` |
| `registerWorker(form)` | POST | `/claude-workers` |
| `updateWorker(id, form)` | PUT | `/claude-workers/{id}` |
| `deleteWorker(id)` | DELETE | `/claude-workers/{id}` |
| `triggerHealthCheck(id)` | POST | `/claude-workers/{id}/health-check` |
| `listDirectoriesByWorker(id)` | GET | `/working-directories/worker/{id}` |
| `createDirectory(form)` | POST | `/working-directories` |
| `updateDirectory(id, form)` | PUT | `/working-directories/{id}` |
| `deleteDirectory(id)` | DELETE | `/working-directories/{id}` |
| `syncDirectoryGitInfo(id)` | POST | `/working-directories/{id}/sync` |
| `listSkills(directoryId)` | GET | `/working-directories/{id}/skills` |
| `createTask(form)` | POST | `/claude-tasks` |
| `resumeTask(form)` | POST | `/claude-tasks/resume` |
| `abortTask(id)` | POST | `/claude-tasks/{id}/abort` |
| `deleteTask(id)` | DELETE | `/claude-tasks/{id}` |
| `listTasksByDirectory(id)` | GET | `/claude-tasks/directory/{id}` |
| `syncWorkerSessions(workerId)` | POST | `/claude-tasks/worker/{id}/sessions/sync` |
| `updateConversationPin(sid, pinned)` | PATCH | `/claude-tasks/conversations/{sid}/pin` |
| `updateConversationTitle(sid, title)` | PATCH | `/claude-tasks/conversations/{sid}/title` |
| `bindConversationAuth(sid, form)` | POST | `/claude-tasks/conversations/{sid}/bind-auth` |
| `listConversationConfigs(sids)` | GET | `/claude-tasks/conversation-configs` |
| `batchBindConversationAuth(form)` | POST | `/claude-tasks/conversations/batch-bind-auth` |

### useClaudeWorker Composable

```typescript
const {
  // 响应式状态
  workers, tasks, directories, loading,
  taskPage, taskSize, taskTotal,
  onlineWorkers,              // computed
  conversationConfigs,        // Map<sessionId, ConversationConfig>

  // Worker CRUD
  loadWorkers, registerWorker, updateWorker, deleteWorker, refreshWorkerStatus,

  // Task 操作
  loadTasks, loadTasksPage, createTask, resumeTask, abortTask, deleteTask,

  // Directory 操作
  loadDirectories, createDirectory, deleteDirectory, syncGitInfo, syncSessions,

  // Conversation Config 操作
  loadConversationConfigs, togglePin, setTitle, bindAuth, batchBindAuth,
} = useClaudeWorker()
```

### TypeScript 类型（types/index.ts）

```typescript
interface ClaudeWorker { workerId, name, baseUrl, authMode, status, ... }
interface ClaudeTask { taskId, sessionId, workerId, prompt, status, claudeSessionId, costUsd, ... }
interface WorkingDirectory { directoryId, workerId, projectName, path, agentTeamsConfig, ... }
interface ConversationConfig { sessionId, pinned, pinnedAt?, customTitle?, authMode?, authBound, baseUrl?, maskedAuthToken? }
interface WorkerSession { session_id, cwd, created_at, updated_at, slug?, git_branch? }
```

完整类型定义见 `packages/navigator-frontend/src/types/index.ts`。

### SSE 连接（Navigator 前端）

Navigator 前端通过 session-module 的 SSE 端点接收消息：

```typescript
// 开发模式直连后端
const sseBase = import.meta.env.DEV ? 'http://localhost:8112' : ''
const url = `${sseBase}/api/v1/sessions/${sessionId}/events/stream`
const eventSource = new EventSource(url)
eventSource.addEventListener('event', (e) => {
  const message = JSON.parse(e.data)
  // 处理 AgentMessage: TEXT_CHUNK, TEXT_COMPLETE, TOOL_CALL_START, etc.
})
```

## 参考文件

详细的技术参考请查看：
- [reference.md](./reference.md) - OpenHands 前端 API 类型和组件参考
- Claude Worker 详细参考见 `claude-worker-agent` 技能
