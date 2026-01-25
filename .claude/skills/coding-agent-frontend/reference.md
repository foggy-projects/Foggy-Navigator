# Coding Agent 前端技术参考

## TypeScript 类型定义

### 核心类型

```typescript
// src/types/index.ts

// 会话相关
export interface Conversation {
  conversationId: string
  sandboxId?: string
  userId: string
  projectId: string
  status: ConversationStatus
  namespace?: string
  gitRepoUrl: string
  branchName: string
  createdAt: string
  updatedAt?: string
}

export type ConversationStatus =
  | 'STARTING'
  | 'READY'
  | 'RUNNING'
  | 'IDLE'
  | 'ERROR'
  | 'STOPPED'

export interface CreateConversationRequest {
  userId: string
  projectId: string
  gitRepoUrl: string
  branchName: string
  gitCredentials?: {
    username: string
    token: string
  }
  initialMessage?: string
}

// 消息相关
export interface Message {
  messageId: string
  conversationId: string
  content: string
  role: 'user' | 'assistant'
  timestamp: string
}

export interface SendMessageRequest {
  content: string
}

// 事件相关
export interface Event {
  id: string
  conversationId: string
  kind: EventKind
  timestamp: string
  data: Record<string, any>
}

export type EventKind =
  | 'CONVERSATION_STATUS'
  | 'MESSAGE_SENT'
  | 'AGENT_ACTION'
  | 'AGENT_OBSERVATION'
  | 'VALIDATION_TRIGGERED'
  | 'VALIDATION_RESULT'
  | 'ERROR'

// 容器相关
export interface Container {
  id: string
  name: string
  image: string
  status: string
  created: string
  labels: Record<string, string>
}

// 系统统计
export interface SystemStats {
  totalConversations: number
  activeConversations: number
  idleConversations: number
  errorConversations: number
  totalContainers: number
  runningContainers: number
}

// 验证相关
export interface ValidationResult {
  conversationId: string
  status: 'PENDING' | 'SUCCESS' | 'FAILURE'
  errors: ValidationError[]
  timestamp: string
}

export interface ValidationError {
  type: string
  message: string
  location?: string
}
```

## API 端点参考

### 会话 API

| 方法 | 端点 | 描述 |
|-----|------|------|
| GET | `/api/v1/conversations` | 获取会话列表 |
| GET | `/api/v1/conversations/:id` | 获取会话详情 |
| POST | `/api/v1/conversations` | 创建会话 |
| DELETE | `/api/v1/conversations/:id` | 删除会话 |
| POST | `/api/v1/conversations/:id/stop` | 停止会话 |

### 消息 API

| 方法 | 端点 | 描述 |
|-----|------|------|
| GET | `/api/v1/conversations/:id/messages` | 获取消息列表 |
| POST | `/api/v1/conversations/:id/messages` | 发送消息 |

### 事件 API

| 方法 | 端点 | 描述 |
|-----|------|------|
| GET | `/api/v1/conversations/:id/events` | 获取事件列表 |
| GET | `/api/v1/events/stream?conversationId=:id` | SSE 事件流 |

### 验证 API

| 方法 | 端点 | 描述 |
|-----|------|------|
| POST | `/api/v1/conversations/:id/validate` | 触发验证 |
| GET | `/api/v1/conversations/:id/validation/status` | 获取验证状态 |
| GET | `/api/v1/conversations/:id/validation/results` | 获取验证结果 |

### 恢复 API

| 方法 | 端点 | 描述 |
|-----|------|------|
| POST | `/api/v1/recovery/recover/:id` | 恢复单个会话 |
| POST | `/api/v1/recovery/recover-all` | 恢复所有会话 |
| GET | `/api/v1/recovery/recoverable` | 获取可恢复会话 |

## Vite 配置

### vite.config.ts

```typescript
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src')
    }
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8112',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
    sourcemap: false
  }
})
```

### package.json

```json
{
  "name": "coding-agent-frontend",
  "version": "1.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc --noEmit && vite build",
    "preview": "vite preview",
    "type-check": "vue-tsc --noEmit",
    "lint": "eslint src --ext .vue,.ts --fix"
  },
  "dependencies": {
    "vue": "^3.4.0",
    "vue-router": "^4.2.0",
    "element-plus": "^2.5.0",
    "axios": "^1.6.0",
    "@element-plus/icons-vue": "^2.3.0"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.0.0",
    "vite": "^5.0.0",
    "typescript": "^5.3.0",
    "vue-tsc": "^1.8.0",
    "@types/node": "^20.0.0"
  }
}
```

## Element Plus 常用组件

### 表格 (el-table)

```vue
<el-table :data="conversations" stripe v-loading="loading">
  <el-table-column prop="conversationId" label="ID" width="280" />
  <el-table-column prop="status" label="状态" width="100">
    <template #default="{ row }">
      <el-tag :type="getStatusType(row.status)">
        {{ row.status }}
      </el-tag>
    </template>
  </el-table-column>
  <el-table-column prop="createdAt" label="创建时间" width="180">
    <template #default="{ row }">
      {{ formatDate(row.createdAt) }}
    </template>
  </el-table-column>
  <el-table-column label="操作" fixed="right" width="150">
    <template #default="{ row }">
      <el-button link type="primary" @click="handleView(row)">
        查看
      </el-button>
      <el-popconfirm title="确认删除？" @confirm="handleDelete(row)">
        <template #reference>
          <el-button link type="danger">删除</el-button>
        </template>
      </el-popconfirm>
    </template>
  </el-table-column>
</el-table>
```

### 表单 (el-form)

```vue
<el-form :model="form" :rules="rules" ref="formRef" label-width="100px">
  <el-form-item label="用户 ID" prop="userId">
    <el-input v-model="form.userId" placeholder="请输入用户 ID" />
  </el-form-item>
  <el-form-item label="项目 ID" prop="projectId">
    <el-input v-model="form.projectId" placeholder="请输入项目 ID" />
  </el-form-item>
  <el-form-item label="Git 仓库" prop="gitRepoUrl">
    <el-input v-model="form.gitRepoUrl" placeholder="https://github.com/..." />
  </el-form-item>
  <el-form-item label="分支" prop="branchName">
    <el-input v-model="form.branchName" placeholder="main" />
  </el-form-item>
  <el-form-item>
    <el-button type="primary" @click="handleSubmit" :loading="submitting">
      创建
    </el-button>
    <el-button @click="handleReset">重置</el-button>
  </el-form-item>
</el-form>

<script setup lang="ts">
const rules = {
  userId: [{ required: true, message: '请输入用户 ID', trigger: 'blur' }],
  projectId: [{ required: true, message: '请输入项目 ID', trigger: 'blur' }],
  gitRepoUrl: [
    { required: true, message: '请输入 Git 仓库地址', trigger: 'blur' },
    { type: 'url', message: '请输入有效的 URL', trigger: 'blur' }
  ],
  branchName: [{ required: true, message: '请输入分支名', trigger: 'blur' }]
}
</script>
```

### 统计卡片 (el-statistic)

```vue
<el-row :gutter="20">
  <el-col :span="6">
    <el-card shadow="hover">
      <el-statistic title="总会话数" :value="stats.totalConversations">
        <template #prefix>
          <el-icon><ChatDotRound /></el-icon>
        </template>
      </el-statistic>
    </el-card>
  </el-col>
  <el-col :span="6">
    <el-card shadow="hover">
      <el-statistic
        title="活跃会话"
        :value="stats.activeConversations"
        :value-style="{ color: '#67C23A' }"
      />
    </el-card>
  </el-col>
  <el-col :span="6">
    <el-card shadow="hover">
      <el-statistic
        title="空闲会话"
        :value="stats.idleConversations"
        :value-style="{ color: '#909399' }"
      />
    </el-card>
  </el-col>
  <el-col :span="6">
    <el-card shadow="hover">
      <el-statistic
        title="错误会话"
        :value="stats.errorConversations"
        :value-style="{ color: '#F56C6C' }"
      />
    </el-card>
  </el-col>
</el-row>
```

### 时间线 (el-timeline)

```vue
<el-timeline>
  <el-timeline-item
    v-for="event in events"
    :key="event.id"
    :timestamp="formatDate(event.timestamp)"
    :type="getEventType(event.kind)"
    placement="top"
  >
    <el-card>
      <template #header>
        <el-tag size="small">{{ event.kind }}</el-tag>
      </template>
      <pre class="event-data">{{ JSON.stringify(event.data, null, 2) }}</pre>
    </el-card>
  </el-timeline-item>
</el-timeline>
```

## 状态标签颜色映射

```typescript
export function getStatusType(status: ConversationStatus): string {
  const map: Record<ConversationStatus, string> = {
    STARTING: 'warning',
    READY: 'success',
    RUNNING: 'primary',
    IDLE: 'info',
    ERROR: 'danger',
    STOPPED: 'info'
  }
  return map[status] || 'info'
}

export function getEventType(kind: EventKind): string {
  const map: Record<EventKind, string> = {
    CONVERSATION_STATUS: 'primary',
    MESSAGE_SENT: 'success',
    AGENT_ACTION: 'warning',
    AGENT_OBSERVATION: 'info',
    VALIDATION_TRIGGERED: 'warning',
    VALIDATION_RESULT: 'success',
    ERROR: 'danger'
  }
  return map[kind] || 'info'
}
```

## 工具函数

```typescript
// src/utils/format.ts

export function formatDate(dateStr: string): string {
  const date = new Date(dateStr)
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })
}

export function formatDuration(ms: number): string {
  const seconds = Math.floor(ms / 1000)
  const minutes = Math.floor(seconds / 60)
  const hours = Math.floor(minutes / 60)

  if (hours > 0) {
    return `${hours}小时 ${minutes % 60}分钟`
  }
  if (minutes > 0) {
    return `${minutes}分钟 ${seconds % 60}秒`
  }
  return `${seconds}秒`
}

export function truncateId(id: string, length = 8): string {
  if (id.length <= length) return id
  return `${id.substring(0, length)}...`
}
```

## 布局模板

### App.vue

```vue
<template>
  <el-container class="app-container">
    <el-aside width="200px">
      <el-menu
        :default-active="route.path"
        router
        class="app-menu"
      >
        <el-menu-item index="/dashboard">
          <el-icon><DataAnalysis /></el-icon>
          <span>系统监控</span>
        </el-menu-item>
        <el-menu-item index="/conversations">
          <el-icon><ChatDotRound /></el-icon>
          <span>会话管理</span>
        </el-menu-item>
        <el-menu-item index="/containers">
          <el-icon><Box /></el-icon>
          <span>容器管理</span>
        </el-menu-item>
        <el-menu-item index="/events">
          <el-icon><List /></el-icon>
          <span>事件日志</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-main>
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup lang="ts">
import { useRoute } from 'vue-router'
import {
  DataAnalysis,
  ChatDotRound,
  Box,
  List
} from '@element-plus/icons-vue'

const route = useRoute()
</script>

<style>
.app-container {
  height: 100vh;
}

.app-menu {
  height: 100%;
  border-right: none;
}
</style>
```

## 常见问题

### API 代理不生效

**问题**: 开发时 API 请求 404

**解决**:
1. 确认 vite.config.ts 中 proxy 配置正确
2. 确认后端服务运行在 8112 端口
3. API 路径必须以 `/api` 开头

### SSE 连接断开

**问题**: SSE 连接频繁断开重连

**解决**:
1. 检查后端 SSE 超时配置
2. 添加重连逻辑
3. 在 onError 中延迟重试

```typescript
let reconnectTimeout: number | null = null

function connect() {
  eventSource = subscribeToEvents(conversationId, {
    onError: () => {
      eventSource?.close()
      reconnectTimeout = window.setTimeout(connect, 3000)
    }
  })
}

onUnmounted(() => {
  eventSource?.close()
  if (reconnectTimeout) clearTimeout(reconnectTimeout)
})
```

### 构建路径问题

**问题**: 构建后资源路径 404

**解决**:
1. 使用 hash 路由模式 (`createWebHashHistory`)
2. 配置 vite.config.ts 中 `base: './'`
3. 确认 Spring Boot 静态资源配置正确
