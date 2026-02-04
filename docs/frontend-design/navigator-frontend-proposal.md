# Foggy Navigator 前端设计方案

## 一、概述

### 1.1 项目定位

| 项目 | 用途 | 目标用户 |
|------|------|----------|
| coding-agent/frontend | 开发调试、内部测试 | 开发人员 |
| **navigator-frontend** | 正式交付产品 | 最终用户 |

### 1.2 交互模型

```
用户 ←→ 前端 ←→ tutor-agent (唯一对话入口)
                       │
                       ├── 自行处理（引导配置、答疑）
                       └── 调度 coding-agent（代码任务）
                                 │
                                 └── 结果回传给 tutor-agent → 前端展示
```

**核心原则**：
- 用户只与 tutor-agent 对话，前端无需感知后端 Agent 调度
- tutor-agent 是唯一面向用户的智能体，由它决定是否调用 coding-agent
- 前端职责是提供对话界面和配置管理，不参与 Agent 路由决策

### 1.3 设计目标

1. **对话优先** - 首页即对话，一键开始
2. **简洁直观** - 用户不需要理解 Agent 概念
3. **国际化** - 支持中英文切换（vue-i18n）
4. **复用组件** - 充分利用 `@foggy/chat` 组件库

---

## 二、技术架构

### 2.1 技术栈

```
前端框架:     Vue 3.5 + TypeScript
UI 组件库:    Element Plus 2.x
聊天组件:     @foggy/chat (内部库)
状态管理:     Pinia 3.x
路由:         Vue Router 4.x
构建工具:     Vite 6.x
HTTP 客户端:  Axios
国际化:       vue-i18n 10.x
样式:         SCSS + CSS Variables (主题定制)
```

### 2.2 项目结构

```
packages/navigator-frontend/
├── public/
│   └── favicon.ico
├── src/
│   ├── adapters/                  # 事件适配器
│   │   └── TutorAgentAdapter.ts   # tutor-agent SSE 事件 → AIP 消息
│   │
│   ├── api/                       # API 层
│   │   ├── client.ts              # Axios 实例 (拦截器、Token)
│   │   ├── auth.ts                # POST /auth/login, /auth/me
│   │   ├── session.ts             # 会话 CRUD + 消息发送 (/api/v1/sessions)
│   │   ├── config.ts              # 数据源、Skill 配置
│   │   └── event.ts               # SSE 事件订阅
│   │
│   ├── components/                # 通用组件
│   │   ├── layout/
│   │   │   ├── AppLayout.vue      # 主布局（侧边栏 + 内容区）
│   │   │   ├── Sidebar.vue        # 侧边栏（导航 + 会话列表）
│   │   │   └── Header.vue         # 头部（用户信息、语言切换）
│   │   │
│   │   ├── conversation/
│   │   │   ├── ConversationList.vue    # 会话列表
│   │   │   ├── ConversationItem.vue    # 会话列表项
│   │   │   └── NewConversationBtn.vue  # 新建会话按钮
│   │   │
│   │   └── common/
│   │       ├── EmptyState.vue
│   │       └── LoadingState.vue
│   │
│   ├── composables/               # 组合式函数
│   │   ├── useAuth.ts             # 认证逻辑
│   │   └── useSession.ts          # 会话 + 聊天逻辑
│   │
│   ├── i18n/                      # 国际化
│   │   ├── index.ts               # vue-i18n 配置
│   │   ├── zh-CN.ts               # 中文
│   │   └── en-US.ts               # 英文
│   │
│   ├── router/
│   │   └── index.ts
│   │
│   ├── stores/
│   │   ├── auth.ts                # 认证状态 (token, userInfo)
│   │   ├── session.ts             # 会话列表状态
│   │   └── settings.ts            # 用户设置 (语言、主题)
│   │
│   ├── styles/
│   │   ├── variables.scss         # CSS 变量 (亮色/暗色)
│   │   └── global.scss            # 全局样式
│   │
│   ├── types/
│   │   └── index.ts               # 类型定义
│   │
│   ├── views/
│   │   ├── auth/
│   │   │   └── Login.vue
│   │   │
│   │   ├── chat/                  # 核心对话页
│   │   │   └── ChatView.vue
│   │   │
│   │   ├── config/                # 配置管理
│   │   │   ├── DatasourceConfig.vue
│   │   │   └── SkillConfig.vue
│   │   │
│   │   └── settings/
│   │       └── UserSettings.vue
│   │
│   ├── App.vue
│   └── main.ts
│
├── index.html
├── vite.config.ts
├── tsconfig.json
└── package.json
```

### 2.3 架构图

```
┌─────────────────────────────────────────────────────────┐
│                  Navigator Frontend                      │
│                                                          │
│  Views ─────────────────────────────────────────────     │
│  │ Login │ ChatView │ DatasourceConfig │ Settings │      │
│  ───────────────────────┬───────────────────────────     │
│                         │                                │
│  Components ────────────┤────────────────────────────    │
│  │ Layout │ ConversationList │ @foggy/chat (ChatPanel) │ │
│  ───────────────────────┤────────────────────────────    │
│                         │                                │
│  Stores ────────────────┤────────────────────────────    │
│  │ AuthStore │ SessionStore │ useChatStore(@foggy)    │  │
│  ───────────────────────┤────────────────────────────    │
│                         │                                │
│  API Layer ─────────────┤────────────────────────────    │
│  │ Axios Client │ SSE Client │ TutorAgentAdapter │       │
│  ───────────────────────┤────────────────────────────    │
│                         │                                │
│  i18n ──────────────────┤────────────────────────────    │
│  │ zh-CN │ en-US │                                       │
└─────────────────────────┼────────────────────────────────┘
                          │
                          ▼
              ┌───────────────────────┐
              │   Backend (port 8112) │
              │                       │
              │   tutor-agent ──────► coding-agent
              │   metadata-config     │
              │   user-auth           │
              └───────────────────────┘
```

---

## 三、页面设计

### 3.1 页面路由

| 路由 | 页面 | 说明 |
|------|------|------|
| `/login` | Login | 登录页 |
| `/` | ChatView (新会话) | 首页 = 新建对话 |
| `/c/:id` | ChatView (指定会话) | 继续已有对话 |
| `/config/datasources` | DatasourceConfig | 数据源配置 |
| `/config/skills` | SkillConfig | Skill 配置 |
| `/settings` | UserSettings | 用户设置（语言、主题） |

路由数量刻意精简：用户进来就是对话，不需要 Dashboard、会话列表页等中间页面。

### 3.2 整体布局

采用 **左侧边栏 + 右侧内容区** 的经典 IM 布局：

```
┌──────────┬─────────────────────────────────────────────┐
│          │                                              │
│  Sidebar │              Content Area                    │
│          │                                              │
│  ┌────┐  │  (ChatView / DatasourceConfig / Settings)    │
│  │新建│  │                                              │
│  └────┘  │                                              │
│          │                                              │
│  会话列表│                                              │
│  ────── │                                              │
│  会话 1  │                                              │
│  会话 2  │                                              │
│  会话 3  │                                              │
│  ...     │                                              │
│          │                                              │
│  ────── │                                              │
│  配置    │                                              │
│  设置    │                                              │
│          │                                              │
│  [中/EN] │                                              │
│  用户名  │                                              │
└──────────┴─────────────────────────────────────────────┘
```

### 3.3 侧边栏 (Sidebar) 详细设计

```
┌──────────────────┐
│  Foggy Navigator │  ← Logo + 产品名
│                  │
│  [+ 新建对话]    │  ← 主操作按钮
│                  │
│  ── 最近对话 ──  │
│                  │
│  ● 配置数据源    │  ← 会话列表项
│    2 分钟前      │     高亮 = 当前活跃
│                  │
│  ○ 帮我写个接口  │
│    昨天          │
│                  │
│  ○ 查询性能优化  │
│    3 天前        │
│                  │
│  ...             │
│                  │
│  ── 管理 ──────  │  ← 底部固定区域
│                  │
│  ⚙ 数据源配置    │
│  ⚙ Skill 配置   │
│  ⚙ 设置         │
│                  │
│  ── ────────── ─ │
│  🌐 中文 / EN    │  ← 语言切换
│  👤 admin [退出] │  ← 用户信息
└──────────────────┘
```

### 3.4 对话页 (ChatView) 详细设计

这是产品的核心页面，复用 `@foggy/chat` 的 `ChatPanel`。

#### 3.4.1 新会话状态 (路由 `/`)

```
┌─────────────────────────────────────────────────────────┐
│                                                          │
│                                                          │
│                    🧭                                    │
│              Foggy Navigator                             │
│                                                          │
│          我是您的智能助手，可以帮您：                      │
│                                                          │
│   ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│   │ (动态卡片 1)  │  │ (动态卡片 2)  │  │ (动态卡片 3)  │  │
│   │              │  │              │  │              │  │
│   │ 由 Agent 根据 │ │ 系统状态动态  │  │ 生成的引导   │  │
│   │ 返回          │  │ 内容          │  │ 建议          │  │
│   └──────────────┘  └──────────────┘  └──────────────┘  │
│                                                          │
│                                                          │
│  ┌──────────────────────────────────────────────── ┐     │
│  │  有什么我可以帮您的？                    [发送]  │     │
│  └──────────────────────────────────────────────── ┘     │
└─────────────────────────────────────────────────────────┘
```

**动态引导卡片机制**

引导卡片不是前端硬编码的，而是由会话对应的 Agent（默认为 tutor-agent）动态生成：

1. **触发时机**：用户打开新会话页面时，前端调用 `GET /api/v1/guide-cards` 请求引导卡片
2. **后端处理**：tutor-agent 通过预定义的 Skill 或 Prompt 分析当前系统状态：
   - 检查是否有已配置的数据源
   - 检查是否有可用的 Git 凭证
   - 检查会话历史，判断用户熟悉程度
   - 根据分析结果生成上下文相关的引导卡片
3. **前端渲染**：前端收到卡片数据后渲染，卡片可点击，点击后将 `prompt` 填充到输入框

**卡片数据结构**：
```typescript
interface GuideCard {
  id: string            // 卡片标识
  icon: string          // 图标名称（Element Plus icon 或 emoji）
  title: string         // 卡片标题
  description: string   // 卡片描述
  prompt: string        // 点击后填充到输入框的文本
  priority: number      // 排序优先级
}
```

**示例场景**：

| 系统状态 | Agent 返回的卡片 |
|----------|-----------------|
| 新系统，无数据源 | "配置数据源"、"添加 Git 凭证"、"了解系统功能" |
| 已有数据源，无 Git 凭证 | "添加 Git 凭证"、"查看数据表结构"、"配置语义层" |
| 配置完善 | "编写代码"、"数据查询"、"排查问题" |

**扩展性**：未来不同行业的导师 Agent 可以返回行业特定的引导卡片（如金融行业导师返回 "配置交易数据源"、"设置风控规则" 等）。

#### 3.4.2 对话进行中 (路由 `/c/:id`)

```
┌──────────────────────────────────────────────────────────┐
│  配置 MySQL 数据源                       ● 已连接         │
│──────────────────────────────────────────────────────────│
│                                                           │
│  👤 我想配置一个新的 MySQL 数据源                          │
│                                                           │
│  🤖 好的！我来帮您配置。请提供以下信息：                   │
│     1. 数据库主机地址                                     │
│     2. 端口号（默认 3306）                                │
│     3. 数据库名称                                         │
│     4. 用户名和密码                                       │
│                                                           │
│  👤 主机 192.168.1.100，端口 3306，                       │
│     数据库 foggy_db，用户名 admin，密码 123456            │
│                                                           │
│  🤖 收到，正在测试连接...                                 │
│                                                           │
│  ┌─ 🔧 test_connection ──────────────────────────────┐   │
│  │ > Connecting to 192.168.1.100:3306/foggy_db...    │   │
│  │ ✓ Connection successful                            │   │
│  │ Tables found: 23                                   │   │
│  └───────────────────────────────────────────────────┘   │
│                                                           │
│  🤖 连接测试成功！发现 23 张表。                          │
│     已为您保存数据源配置。                                │
│     接下来您可以：                                       │
│     - 查看表结构                                         │
│     - 配置语义层                                         │
│     - 继续配置其他数据源                                  │
│                                                           │
│  ⏳ ...                                                   │
│                                                           │
│  ┌───────────────────────────────────────────────── ┐    │
│  │  输入消息...                              [发送]  │    │
│  └───────────────────────────────────────────────── ┘    │
└──────────────────────────────────────────────────────────┘
```

### 3.5 配置管理页面

#### 数据源配置 (DatasourceConfig)

```
┌──────────────────────────────────────────────────────────┐
│  数据源配置                              [+ 新增数据源]    │
│──────────────────────────────────────────────────────────│
│                                                           │
│  ┌────────────────────┐  ┌────────────────────┐          │
│  │  🐬 MySQL          │  │  🍃 MongoDB        │          │
│  │                    │  │                    │          │
│  │  订单数据库        │  │  用户行为库        │          │
│  │  192.168.1.100     │  │  mongo.local       │          │
│  │                    │  │                    │          │
│  │  ● 已连接  23 表   │  │  ● 已连接  8 集合  │          │
│  │                    │  │                    │          │
│  │  [编辑]    [删除]  │  │  [编辑]    [删除]  │          │
│  └────────────────────┘  └────────────────────┘          │
│                                                           │
│  提示：您也可以在对话中告诉助手来配置数据源               │
│                                                           │
└──────────────────────────────────────────────────────────┘
```

配置管理页面保持简单的 CRUD，因为主要操作路径是通过对话完成。这些页面提供一个概览和手动管理入口。

### 3.6 登录页

```
┌──────────────────────────────────────────────────────────┐
│                                                           │
│                                                           │
│                      🧭                                   │
│                Foggy Navigator                            │
│                                                           │
│              ┌────────────────────────┐                   │
│              │  用户名                 │                   │
│              └────────────────────────┘                   │
│              ┌────────────────────────┐                   │
│              │  密码                   │                   │
│              └────────────────────────┘                   │
│                                                           │
│              [        登   录         ]                   │
│                                                           │
│                                                           │
└──────────────────────────────────────────────────────────┘
```

---

## 四、核心实现

### 4.1 事件适配器

tutor-agent 的 SSE 事件需要转换为 @foggy/chat 的 AIP 标准消息：

```typescript
// adapters/TutorAgentAdapter.ts
import { AipMessageType } from '@foggy/chat'
import type { AipMessage, EventAdapter } from '@foggy/chat'

export interface TutorEvent {
  id: string
  conversationId: string
  kind: string     // TEXT, TOOL_CALL, TOOL_RESULT, THINKING, STATUS, ERROR
  data: Record<string, unknown>
  createdAt: string
}

export const tutorAgentAdapter: EventAdapter<TutorEvent> = {
  convert(raw: TutorEvent, sessionId: string): AipMessage[] {
    const base = {
      sessionId,
      timestamp: new Date(raw.createdAt).getTime(),
    }

    switch (raw.kind) {
      case 'TEXT':
        return [{
          ...base,
          messageId: raw.id,
          type: AipMessageType.TEXT_COMPLETE,
          payload: { content: raw.data.content as string },
        }]

      case 'TEXT_CHUNK':
        return [{
          ...base,
          messageId: raw.id,
          type: AipMessageType.TEXT_CHUNK,
          payload: { content: raw.data.content as string },
        }]

      case 'TOOL_CALL':
        return [{
          ...base,
          messageId: raw.id,
          type: AipMessageType.TOOL_CALL_START,
          payload: {
            toolCallId: raw.data.toolCallId as string,
            toolName: raw.data.toolName as string,
            command: raw.data.command as string,
            thought: raw.data.thought as string,
          },
        }]

      case 'TOOL_RESULT':
        return [{
          ...base,
          messageId: raw.id,
          type: AipMessageType.TOOL_CALL_RESULT,
          payload: {
            toolCallId: raw.data.toolCallId as string,
            toolName: raw.data.toolName as string,
            output: raw.data.output as string,
          },
        }]

      case 'THINKING':
        return [{
          ...base,
          messageId: raw.id,
          type: AipMessageType.THINKING,
          payload: { thought: raw.data.thought as string },
        }]

      case 'STATUS':
        return [{
          ...base,
          messageId: raw.id,
          type: AipMessageType.STATE_SYNC,
          payload: { status: raw.data.status as string },
        }]

      case 'ERROR':
        return [{
          ...base,
          messageId: raw.id,
          type: AipMessageType.ERROR,
          payload: { error: raw.data.error as string },
        }]

      default:
        return []
    }
  },
}
```

### 4.2 会话管理 Composable

```typescript
// composables/useSession.ts
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useChatStore, createSseClient } from '@foggy/chat'
import { tutorAgentAdapter, type TutorEvent } from '@/adapters/TutorAgentAdapter'
import * as sessionApi from '@/api/session'
import { useSessionStore } from '@/stores/session'
import { getToken } from '@/utils/auth'

const DEFAULT_AGENT_ID = 'tutor-agent'

export function useSession(sessionId?: string) {
  const router = useRouter()
  const chatStore = useChatStore()
  const sessionStore = useSessionStore()
  const sending = ref(false)
  let eventSource: EventSource | null = null

  // 创建新会话并跳转
  async function createAndNavigate(firstMessage: string) {
    const session = await sessionApi.create({
      title: firstMessage.slice(0, 50),
      agentId: DEFAULT_AGENT_ID,
    })
    sessionStore.addSession(session)
    router.push(`/c/${session.id}`)
    return session.id
  }

  // 订阅 SSE（使用框架级 /api/v1/sessions 路径）
  function connectSse(sessId: string) {
    chatStore.setConnectionStatus('connecting')
    const token = getToken()
    const sseBase = import.meta.env.DEV
      ? 'http://localhost:8112/api/v1'
      : '/api/v1'
    const url = `${sseBase}/sessions/${sessId}/stream${token ? `?token=${token}` : ''}`

    eventSource = createSseClient<TutorEvent>({
      url,
      eventName: 'event',
      adapter: tutorAgentAdapter,
      sessionId: sessId,
      onMessage: (msgs) => {
        for (const m of msgs) chatStore.processAipMessage(m)
      },
      onConnected: () => chatStore.setConnectionStatus('connected'),
      onError: () => chatStore.setConnectionStatus('error'),
    })
  }

  // 发送消息
  async function send(content: string, sessId: string) {
    sending.value = true
    chatStore.addUserMessage(content, sessId)
    try {
      await sessionApi.sendMessage(sessId, { content })
    } finally {
      sending.value = false
    }
  }

  // 断开 SSE
  function disconnect() {
    eventSource?.close()
    chatStore.setConnectionStatus('disconnected')
  }

  return {
    sending: computed(() => sending.value),
    createAndNavigate,
    connectSse,
    send,
    disconnect,
  }
}
```

### 4.3 国际化

```typescript
// i18n/index.ts
import { createI18n } from 'vue-i18n'
import zhCN from './zh-CN'
import enUS from './en-US'

const savedLocale = localStorage.getItem('locale') || 'zh-CN'

export const i18n = createI18n({
  legacy: false,
  locale: savedLocale,
  fallbackLocale: 'zh-CN',
  messages: {
    'zh-CN': zhCN,
    'en-US': enUS,
  },
})

export function setLocale(locale: string) {
  i18n.global.locale.value = locale
  localStorage.setItem('locale', locale)
  document.documentElement.setAttribute('lang', locale)
}
```

```typescript
// i18n/zh-CN.ts
export default {
  common: {
    send: '发送',
    cancel: '取消',
    confirm: '确认',
    delete: '删除',
    edit: '编辑',
    save: '保存',
    loading: '加载中...',
    noData: '暂无数据',
  },
  auth: {
    login: '登录',
    logout: '退出登录',
    username: '用户名',
    password: '密码',
    logoutConfirm: '确认退出登录？',
  },
  chat: {
    newConversation: '新建对话',
    recentConversations: '最近对话',
    inputPlaceholder: '有什么我可以帮您的？',
    welcome: '我是您的智能助手',
    welcomeHint: '可以帮您配置数据源、编写代码、排查问题',
    connected: '已连接',
    connecting: '连接中',
    disconnected: '未连接',
    connectionError: '连接错误',
  },
  config: {
    datasources: '数据源配置',
    skills: 'Skill 配置',
    addDatasource: '新增数据源',
    connected: '已连接',
    disconnected: '未连接',
    tables: '表',
    collections: '集合',
    dialogHint: '您也可以在对话中告诉助手来配置',
  },
  guide: {
    loading: '正在分析系统状态...',
    loadError: '加载引导失败，请刷新重试',
    fallbackWelcome: '我可以帮您配置数据源、编写代码、排查问题',
  },
  settings: {
    title: '设置',
    language: '语言',
    theme: '主题',
    themeLight: '浅色',
    themeDark: '深色',
    themeSystem: '跟随系统',
  },
}
```

```typescript
// i18n/en-US.ts
export default {
  common: {
    send: 'Send',
    cancel: 'Cancel',
    confirm: 'Confirm',
    delete: 'Delete',
    edit: 'Edit',
    save: 'Save',
    loading: 'Loading...',
    noData: 'No data',
  },
  auth: {
    login: 'Login',
    logout: 'Logout',
    username: 'Username',
    password: 'Password',
    logoutConfirm: 'Are you sure you want to logout?',
  },
  chat: {
    newConversation: 'New Conversation',
    recentConversations: 'Recent',
    inputPlaceholder: 'How can I help you?',
    welcome: "I'm your intelligent assistant",
    welcomeHint: 'I can help you configure data sources, write code, and troubleshoot issues',
    connected: 'Connected',
    connecting: 'Connecting',
    disconnected: 'Disconnected',
    connectionError: 'Error',
  },
  config: {
    datasources: 'Data Sources',
    skills: 'Skills',
    addDatasource: 'Add Data Source',
    connected: 'Connected',
    disconnected: 'Disconnected',
    tables: 'tables',
    collections: 'collections',
    dialogHint: 'You can also configure via conversation',
  },
  guide: {
    loading: 'Analyzing system status...',
    loadError: 'Failed to load guide, please refresh',
    fallbackWelcome: 'I can help you configure data sources, write code, and troubleshoot issues',
  },
  settings: {
    title: 'Settings',
    language: 'Language',
    theme: 'Theme',
    themeLight: 'Light',
    themeDark: 'Dark',
    themeSystem: 'System',
  },
}
```

### 4.4 API 层

```typescript
// api/client.ts
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { getToken, clearAuth } from '@/utils/auth'
import router from '@/router'
import { i18n } from '@/i18n'

const client = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeout: 30000,
})

client.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

client.interceptors.response.use(
  (response) => response.data,
  (error) => {
    const status = error.response?.status

    if (status === 401) {
      clearAuth()
      router.push('/login')
    }

    const message = error.response?.data?.message || error.message
    ElMessage.error(message)
    return Promise.reject(error)
  },
)

export default client
```

```typescript
// api/session.ts
import client from './client'

export interface SessionDTO {
  id: string
  userId: string
  tenantId: string
  agentId: string
  title: string
  status: string
  lastMessagePreview?: string
  messageCount?: number
  createdAt: string
  updatedAt: string
}

export interface SessionMessageDTO {
  id: string
  sessionId: string
  role: 'USER' | 'ASSISTANT' | 'SYSTEM' | 'TOOL'
  content: string | null
  metadata?: Record<string, unknown>
  createdAt: string
}

export interface GuideCard {
  id: string
  icon: string
  title: string
  description: string
  prompt: string
  priority: number
}

export function listSessions(params?: { agentId?: string; status?: string }): Promise<SessionDTO[]> {
  return client.get('/sessions', { params })
}

export function create(data: { title: string; agentId?: string }): Promise<SessionDTO> {
  return client.post('/sessions', data)
}

export function getSession(id: string): Promise<SessionDTO> {
  return client.get(`/sessions/${id}`)
}

export function getMessages(id: string, params?: { limit?: number; before?: string }): Promise<SessionMessageDTO[]> {
  return client.get(`/sessions/${id}/messages`, { params })
}

export function sendMessage(id: string, data: { content: string }): Promise<SessionMessageDTO> {
  return client.post(`/sessions/${id}/messages`, data)
}

export function deleteSession(id: string): Promise<void> {
  return client.delete(`/sessions/${id}`)
}

export function getGuideCards(agentId?: string): Promise<GuideCard[]> {
  return client.get('/sessions/guide-cards', { params: { agentId } })
}
```

### 4.5 Stores

```typescript
// stores/session.ts
import { defineStore } from 'pinia'
import * as api from '@/api/session'
import type { SessionDTO } from '@/api/session'

const DEFAULT_AGENT_ID = 'tutor-agent'

export const useSessionStore = defineStore('session', {
  state: () => ({
    sessions: [] as SessionDTO[],
    loading: false,
  }),

  getters: {
    sorted: (state) =>
      [...state.sessions].sort(
        (a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime(),
      ),
  },

  actions: {
    async fetch() {
      this.loading = true
      try {
        this.sessions = await api.listSessions({ agentId: DEFAULT_AGENT_ID })
      } finally {
        this.loading = false
      }
    },

    addSession(session: SessionDTO) {
      this.sessions.unshift(session)
    },

    removeSession(id: string) {
      this.sessions = this.sessions.filter((s) => s.id !== id)
    },
  },
})
```

```typescript
// stores/settings.ts
import { defineStore } from 'pinia'
import { setLocale } from '@/i18n'

export type ThemeMode = 'light' | 'dark' | 'system'

export const useSettingsStore = defineStore('settings', {
  state: () => ({
    locale: localStorage.getItem('locale') || 'zh-CN',
    theme: (localStorage.getItem('theme') || 'light') as ThemeMode,
    sidebarCollapsed: false,
  }),

  actions: {
    setLanguage(locale: string) {
      this.locale = locale
      setLocale(locale)
    },

    setTheme(theme: ThemeMode) {
      this.theme = theme
      localStorage.setItem('theme', theme)
      this.applyTheme()
    },

    applyTheme() {
      const root = document.documentElement
      if (this.theme === 'dark') {
        root.classList.add('dark')
      } else if (this.theme === 'light') {
        root.classList.remove('dark')
      } else {
        // system
        const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
        root.classList.toggle('dark', prefersDark)
      }
    },

    toggleSidebar() {
      this.sidebarCollapsed = !this.sidebarCollapsed
    },
  },
})
```

---

## 五、与 coding-agent/frontend 的关系

| 方面 | coding-agent/frontend | navigator-frontend |
|------|----------------------|-------------------|
| 用途 | 开发调试 | 正式产品 |
| 对话入口 | 直连 coding-agent | 统一走 tutor-agent |
| 页面 | Dashboard、容器管理、事件日志 | 对话、配置管理 |
| 共享 | `@foggy/chat` | `@foggy/chat` |
| 适配器 | OpenHandsAdapter | TutorAgentAdapter |

### 代码复用

| 来源 | 复用内容 | 变化点 |
|------|---------|--------|
| `@foggy/chat` | ChatPanel, useChatStore, createSseClient, AIP 类型 | 不变 |
| coding-agent `client.ts` | Axios 拦截器模式、Token 管理 | 参考，重写 |
| coding-agent `event.ts` | SSE 订阅封装 | 参考，改用 TutorAgentAdapter，路径改为 /sessions |
| coding-agent `auth.ts` | 登录 API 调用 | 相同后端，直接复用逻辑 |

---

## 六、关键设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 会话管理 | 框架级 session-module | 任何 Agent 共用，tutor 只负责提示词和 Skill |
| 对话入口 | 仅 tutor-agent | 用户无需理解 Agent 概念，tutor 负责调度 |
| 首页 | 即是新建对话页 | 减少跳转，进入即可用 |
| 侧边栏 | 会话列表 + 导航 | 类 ChatGPT 布局，用户熟悉 |
| 配置管理 | 独立页面 + 对话辅助 | 提供两种操作路径 |
| 多租户 | 登录时确定，重新登录切换 | 简化前端逻辑 |
| 国际化 | vue-i18n，中英文 | 初期支持两种语言 |
| 主题 | CSS Variables，亮/暗/系统 | 低成本实现 |
| 权限 | 暂不实现 | 先跑通核心流程 |
| 移动端 | PC 完成后单独考虑 | 聚焦 PC 端交付 |

---

## 七、实施计划

### Phase 1: 基础框架搭建

- 项目初始化 (Vite + Vue 3 + TypeScript + pnpm workspace)
- 依赖安装 (Element Plus, Pinia, vue-i18n, @foggy/chat)
- 路由配置
- API 客户端封装 (Axios + 拦截器)
- 认证流程 (Login 页面, Token 管理)
- 国际化基础 (vue-i18n + 中英文 JSON)

### Phase 2: 核心对话功能

- 主布局 (AppLayout + Sidebar + Header)
- 侧边栏会话列表
- ChatView 页面 (新会话欢迎页 + 对话界面)
- TutorAgentAdapter
- SSE 集成
- ConversationStore

### Phase 3: 配置管理

- 数据源配置页面 (CRUD + 卡片展示)
- Skill 配置页面
- 用户设置页面 (语言、主题)

### Phase 4: 完善

- 深色模式
- 响应式基础适配
- 错误处理优化
- @foggy/chat 组件样式覆盖（适配 Navigator 品牌）

---

## 八、后端 API 需求

navigator-frontend 需要后端提供的 API（部分已有，部分需要新建）。

详细的后端 API 需求文档见：[backend-api-requirements.md](./backend-api-requirements.md)

**核心架构决策**：会话管理是框架级基础能力，由新建的 `session-module` 统一提供（实现 `agent-framework` 的 `SessionManager` 接口），不是某个 Agent 的职责。API 路径使用 `/api/v1/sessions`，通过 `agentId` 参数区分不同 Agent 的会话。

### 概览

| API | 状态 | 提供方 | 说明 |
|-----|------|--------|------|
| `POST /api/v1/auth/login` | ✅ 已有 | user-auth-module | 用户登录 |
| `GET /api/v1/auth/me` | ✅ 已有 | user-auth-module | 当前用户信息 |
| `POST /api/v1/sessions` | ❌ 需新建 | session-module | 创建会话（指定 agentId） |
| `GET /api/v1/sessions` | ❌ 需新建 | session-module | 会话列表（按 agentId 筛选） |
| `GET /api/v1/sessions/:id` | ❌ 需新建 | session-module | 会话详情 |
| `DELETE /api/v1/sessions/:id` | ❌ 需新建 | session-module | 删除会话 |
| `GET /api/v1/sessions/:id/messages` | ❌ 需新建 | session-module | 历史消息 |
| `POST /api/v1/sessions/:id/messages` | ❌ 需新建 | session-module | 发送消息（触发 Agent） |
| `GET /api/v1/sessions/:id/stream` | ❌ 需新建 | session-module | SSE 实时事件流 |
| `GET /api/v1/sessions/guide-cards` | ❌ 需新建 | session-module | 动态引导卡片 |
| `GET /api/config/datasources` | ⚠️ 需补充 | metadata-config-module | 数据源列表（当前缺少） |
| `POST /api/config/datasource` | ✅ 已有 | metadata-config-module | 创建数据源 |
| `PUT /api/config/datasource/:id` | ✅ 已有 | metadata-config-module | 修改数据源 |
| `DELETE /api/config/datasource/:id` | ✅ 已有 | metadata-config-module | 删除数据源 |
| `GET /api/config/skills` | ✅ 已有 | metadata-config-module | Skill 列表 |
