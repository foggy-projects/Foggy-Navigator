# Coding Agent 前端管理控制台

基于 Vue 3 + TypeScript + Element Plus 构建的 Coding Agent 管理控制台。

## 技术栈

- **框架**: Vue 3 (Composition API + `<script setup>`)
- **UI 库**: Element Plus
- **类型**: TypeScript
- **构建**: Vite
- **HTTP**: Axios
- **路由**: Vue Router

## 目录结构

```
src/
├── api/                # API 调用封装
│   ├── client.ts       # Axios 实例配置
│   ├── conversation.ts # 会话 API
│   ├── container.ts    # 容器 API
│   └── event.ts        # 事件 API
├── components/         # 可复用组件
│   └── StatusBadge.vue # 状态标签组件
├── views/              # 页面视图
│   ├── Dashboard.vue          # 系统监控首页
│   ├── Conversations.vue      # 会话管理
│   ├── ConversationDetail.vue # 会话详情
│   ├── Containers.vue         # 容器管理
│   └── Events.vue             # 事件日志
├── types/              # TypeScript 类型定义
│   └── index.ts
├── router/             # 路由配置
│   └── index.ts
├── App.vue
└── main.ts
```

## 功能特性

### 1. 系统监控 (Dashboard)
- 会话、容器、消息统计
- 最近会话和事件展示
- 实时数据概览

### 2. 会话管理 (Conversations)
- 会话列表查看
- 创建新会话
- 停止/删除会话
- 跳转到会话详情

### 3. 会话详情 (ConversationDetail)
- 会话基本信息
- 消息记录展示（用户/AI对话）
- 实时事件流订阅（SSE）

### 4. 容器管理 (Containers)
- 容器列表查看
- 容器状态监控
- 删除容器

### 5. 事件日志 (Events)
- 事件列表查看
- 按会话ID/类型筛选
- 实时事件展示

## 开发指南

### 安装依赖

```bash
cd addons/coding-agent/frontend
npm install
```

### 开发模式

```bash
npm run dev
```

访问 http://localhost:5173

API 请求会自动代理到 http://localhost:8112

### 构建生产版本

```bash
npm run build
```

构建输出到 `../src/main/resources/static` 目录，可直接被 Spring Boot 静态托管。

### 类型检查

```bash
npm run type-check
```

## API 调用示例

### 获取会话列表

```typescript
import { listConversations } from '@/api/conversation'

const conversations = await listConversations({
  userId: 'user123',
  status: 'ACTIVE'
})
```

### 订阅 SSE 事件流

```typescript
import { subscribeToEvents } from '@/api/event'

const eventSource = subscribeToEvents(conversationId, {
  onEvent: (event) => {
    console.log('收到事件:', event)
  },
  onError: (error) => {
    console.error('SSE 错误:', error)
  }
})

// 组件卸载时关闭连接
onUnmounted(() => {
  eventSource.close()
})
```

## 环境配置

### 开发环境 (`.env.development`)
```
VITE_API_BASE_URL=
```
使用 Vite 代理，请求 `/api` 自动转发到 `http://localhost:8112`

### 生产环境 (`.env.production`)
```
VITE_API_BASE_URL=/api/v1
```
直接使用相对路径，由 Spring Boot 提供 API 服务

## 代码规范

- 使用 Composition API + `<script setup>` 语法
- TypeScript 严格模式
- 组件命名使用 PascalCase
- API 函数命名使用 camelCase
- 加载状态使用 `el-skeleton` 或 `v-loading`
- 错误提示使用 `ElMessage.error()`
- 确认操作使用 `ElMessageBox.confirm()`

## 后续优化建议

1. 添加 ESLint + Prettier 代码检查
2. 添加单元测试（Vitest）
3. 添加 E2E 测试（Playwright）
4. 添加状态管理（Pinia）用于跨组件状态共享
5. 优化 SSE 连接管理和重连机制
6. 添加消息发送功能
7. 添加代码高亮和 Markdown 渲染
8. 添加文件上传/下载功能
