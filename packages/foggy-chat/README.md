# @foggy/chat

Vue 3 可复用聊天组件库，基于 AIP (Agent Interaction Protocol) 协议，支持 LLM 对话场景。

## 特性

- **AIP 协议标准化**：统一的消息类型定义，支持文本、工具调用、思考过程等
- **适配器模式**：通过 `EventAdapter` 接口适配不同后端格式
- **SSE 实时通信**：支持命名事件的 SSE 客户端
- **Pinia 状态管理**：开箱即用的 Store，处理消息累积、状态同步
- **完整 UI 组件**：ChatPanel、消息气泡、工具调用卡片等
- **业务暂停交互**：内置审批卡片和受控 `BusinessSuspensionDialog`

## 安装

```bash
# 在 workspace 项目中
npm install @foggy/chat
# 或
pnpm add @foggy/chat
```

### peerDependencies

确保项目中已安装以下依赖：

```json
{
  "vue": "^3.5.0",
  "element-plus": "^2.13.0",
  "pinia": "^3.0.0"
}
```

## 快速开始

### 1. 导入样式

```typescript
// main.ts
import '@foggy/chat/style.css'
```

### 2. 基本使用

```vue
<template>
  <ChatPanel
    :messages="chatStore.sortedMessages"
    :is-thinking="chatStore.isThinking"
    :connection-status="chatStore.connectionStatus"
    @send="handleSend"
  />
</template>

<script setup lang="ts">
import { ChatPanel, useChatStore } from '@foggy/chat'

const chatStore = useChatStore()

function handleSend(content: string) {
  chatStore.addUserMessage(content)
  // 调用你的后端 API 发送消息
}
</script>
```

## 核心概念

### AIP 消息类型

AIP (Agent Interaction Protocol) 定义了 LLM 交互的标准消息格式：

```typescript
enum AipMessageType {
  TEXT_CHUNK = 'TEXT_CHUNK',       // 流式文本片段
  TEXT_COMPLETE = 'TEXT_COMPLETE', // 完整文本消息
  TOOL_CALL_START = 'TOOL_CALL_START',   // 工具调用开始
  TOOL_CALL_RESULT = 'TOOL_CALL_RESULT', // 工具调用结果
  TOOL_CALL_ERROR = 'TOOL_CALL_ERROR',   // 工具调用错误
  THINKING = 'THINKING',           // 思考过程
  STATE_SYNC = 'STATE_SYNC',       // 状态同步
  ERROR = 'ERROR',                 // 错误信息
}

interface AipMessage<T = unknown> {
  messageId: string
  sessionId: string
  timestamp: number
  type: AipMessageType
  payload: T
}
```

### EventAdapter 适配器模式

由于不同后端的事件格式各异，`@foggy/chat` 使用适配器模式将原始事件转换为 AIP 消息：

```typescript
interface EventAdapter<TRaw = unknown> {
  convert(raw: TRaw, sessionId: string): AipMessage[]
}
```

**适配器工作流程**：

```
Backend SSE Event { kind, data }
        ↓
YourAdapter.convert()         ← 你实现的适配器
        ↓
AipMessage { type, payload }  ← 标准 AIP 消息
        ↓
useChatStore.processAipMessage()
        ↓
ChatPanel 渲染
```

## API 参考

### ChatPanel 组件

主聊天面板，整合了消息列表、输入框和状态显示。

```typescript
interface ChatPanelProps {
  messages: ChatMessage[]           // 消息列表
  isThinking?: boolean              // 是否显示思考动画
  connectionStatus?: ConnectionStatus // 连接状态
  conversationStatus?: string       // 会话状态文本
  inputDisabled?: boolean           // 禁用输入
  placeholder?: string              // 输入框占位符
  showHeader?: boolean              // 是否显示头部
}

interface ChatPanelEmits {
  (e: 'send', content: string): void // 发送消息事件
}
```

**插槽**：

- `#header` - 完全自定义头部
- `#header-left` - 头部左侧内容
- `#header-right` - 头部右侧内容（默认显示状态徽章和连接指示器）

### useChatStore

Pinia Store，管理聊天状态和消息处理。

```typescript
const chatStore = useChatStore()

// 状态
chatStore.messages          // 原始消息数组
chatStore.sortedMessages    // 按时间排序的消息（computed）
chatStore.connectionStatus  // 连接状态: 'disconnected' | 'connecting' | 'connected' | 'error'
chatStore.conversationStatus // 会话状态文本
chatStore.isThinking        // 是否正在思考

// 方法
chatStore.processAipMessage(aipMessage)  // 处理 AIP 消息
chatStore.addUserMessage(content)        // 添加用户消息
chatStore.setConnectionStatus(status)    // 设置连接状态
chatStore.clearMessages()                // 清空消息
```

### createSseClient

创建支持命名事件的 SSE 客户端。

```typescript
interface SseClientOptions<TRaw> {
  url: string                              // SSE 端点 URL
  eventName?: string                       // 事件名称，默认 'event'
  adapter: EventAdapter<TRaw>              // 事件适配器
  sessionId: string                        // 会话 ID
  onMessage: (messages: AipMessage[]) => void  // 消息回调
  onConnected?: () => void                 // 连接成功回调
  onError?: (error: Event) => void         // 错误回调
}

function createSseClient<TRaw>(options: SseClientOptions<TRaw>): EventSource
```

**注意**：后端使用 `.name("event")` 发送命名事件时，前端需要用 `addEventListener('event', ...)` 监听，而非 `onmessage`。`createSseClient` 已处理此问题。

### 其他组件

| 组件 | 用途 |
|------|------|
| `MessageList` | 消息滚动列表 |
| `MessageBubble` | 文本消息气泡 |
| `MessageInput` | 输入框 + 发送按钮 |
| `ToolCallBlock` | 工具调用卡片（命令 + 输出） |
| `BusinessSuspensionDialog` | 受控业务暂停弹窗，提交 approve/reject 决策 |
| `ThinkingIndicator` | 思考中动画 |
| `ErrorBlock` | 错误提示 |
| `StatusBadge` | 状态徽章 |

## 完整示例：接入 OpenHands

以下是 `coding-agent` 前端接入 OpenHands 后端的完整示例。

### 1. 实现适配器

```typescript
// src/adapters/OpenHandsAdapter.ts
import { AipMessageType } from '@foggy/chat'
import type { AipMessage, EventAdapter } from '@foggy/chat'

// 后端 SSE 事件结构
export interface OhRawEvent {
  id: string
  conversationId: string
  kind: string              // MESSAGE_SENT, AGENT_ACTION, AGENT_OBSERVATION, etc.
  data: Record<string, unknown>
  createdAt: string
}

export const openHandsAdapter: EventAdapter<OhRawEvent> = {
  convert(raw: OhRawEvent, sessionId: string): AipMessage[] {
    const messages: AipMessage[] = []
    const base = { sessionId, timestamp: new Date(raw.createdAt).getTime() }
    const data = raw.data ?? {}

    switch (raw.kind) {
      case 'MESSAGE_SENT': {
        // Agent 文本消息
        const content = extractText(data.llm_message?.content)
        if (content) {
          messages.push({
            ...base,
            messageId: raw.id,
            type: AipMessageType.TEXT_COMPLETE,
            payload: { content },
          })
        }
        break
      }

      case 'AGENT_ACTION': {
        // 工具调用或思考
        const command = data.command as string
        const thought = data.thought as string

        if (command) {
          messages.push({
            ...base,
            messageId: raw.id,
            type: AipMessageType.TOOL_CALL_START,
            payload: {
              toolCallId: data.tool_call_id ?? raw.id,
              toolName: data.tool_name ?? 'terminal',
              command,
              thought,
            },
          })
        } else if (thought) {
          messages.push({
            ...base,
            messageId: raw.id,
            type: AipMessageType.THINKING,
            payload: { thought },
          })
        }
        break
      }

      case 'AGENT_OBSERVATION': {
        // 工具执行结果
        messages.push({
          ...base,
          messageId: raw.id,
          type: AipMessageType.TOOL_CALL_RESULT,
          payload: {
            toolCallId: data.tool_call_id ?? raw.id,
            toolName: data.tool_name ?? 'observation',
            output: extractText(data.content),
            exitCode: data.exit_code,
          },
        })
        break
      }

      case 'CONVERSATION_STATUS': {
        messages.push({
          ...base,
          messageId: raw.id,
          type: AipMessageType.STATE_SYNC,
          payload: { status: data.status as string },
        })
        break
      }

      case 'ERROR': {
        messages.push({
          ...base,
          messageId: raw.id,
          type: AipMessageType.ERROR,
          payload: { error: data.error as string },
        })
        break
      }
    }

    return messages
  },
}

function extractText(value: unknown): string {
  if (typeof value === 'string') return value
  if (Array.isArray(value)) {
    return value
      .filter((item: any) => item?.type === 'text')
      .map((item: any) => item.text)
      .join('\n')
  }
  return ''
}
```

### 2. 封装 SSE 订阅

```typescript
// src/api/event.ts
import { createSseClient } from '@foggy/chat'
import type { SseClientOptions } from '@foggy/chat'
import { openHandsAdapter, type OhRawEvent } from '@/adapters/OpenHandsAdapter'

export function subscribeToConversation(
  conversationId: string,
  handlers: {
    onMessage?: SseClientOptions<OhRawEvent>['onMessage']
    onConnected?: () => void
    onError?: (error: Event) => void
  }
): EventSource {
  const url = `/api/v1/events/stream?conversationId=${conversationId}`

  return createSseClient<OhRawEvent>({
    url,
    eventName: 'event',           // 后端 .name("event") 发送
    adapter: openHandsAdapter,
    sessionId: conversationId,
    onMessage: handlers.onMessage ?? (() => {}),
    onConnected: handlers.onConnected,
    onError: handlers.onError,
  })
}
```

### 3. 页面集成

```vue
<!-- src/views/ConversationDetail.vue -->
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
          <el-button text @click="router.push('/conversations')">
            ← 返回
          </el-button>
          <span class="conv-id">{{ conversationId }}</span>
        </div>
        <div class="header-right">
          <StatusBadge :status="chatStore.conversationStatus" />
          <span :class="['connection-dot', chatStore.connectionStatus]" />
        </div>
      </template>
    </ChatPanel>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChatPanel, StatusBadge, useChatStore } from '@foggy/chat'
import { subscribeToConversation } from '@/api/event'
import { sendMessage } from '@/api/conversation'

const route = useRoute()
const router = useRouter()
const conversationId = route.params.id as string
const chatStore = useChatStore()
const sending = ref(false)

let eventSource: EventSource | null = null

// 连接 SSE
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

// 发送消息
async function handleSend(content: string) {
  sending.value = true
  chatStore.addUserMessage(content)
  try {
    await sendMessage({ conversationId, content })
  } finally {
    sending.value = false
  }
}

onMounted(() => {
  chatStore.clearMessages()
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
}
</style>
```

## 类型导出

```typescript
// 类型
export { AipMessageType } from './types/aip'
export type {
  AipMessage,
  TextPayload,
  ToolCallStartPayload,
  ToolCallResultPayload,
  ToolCallErrorPayload,
  ThinkingPayload,
  StateSyncPayload,
  ErrorPayload,
} from './types/aip'
export type { EventAdapter } from './types/adapter'
export type { ChatMessage, ConnectionStatus } from './types/chat'
export type {
  BusinessSuspensionDialogModel,
  BusinessSuspensionDecisionPayload,
} from './types/suspension'

// SSE
export { createSseClient } from './sse/SseClient'
export type { SseClientOptions } from './sse/SseClient'

// Store
export { useChatStore } from './store/useChatStore'

// Components
export { ChatPanel, MessageList, MessageBubble, MessageInput }
export { ToolCallBlock, BusinessSuspensionDialog, ThinkingIndicator, ErrorBlock, StatusBadge }
```

## 开发

```bash
# 构建
cd packages/foggy-chat
npm run build

# 监听模式开发
npm run dev
```

## 架构图

```
┌─────────────────────────────────────────────────────────┐
│                    消费方项目                            │
│  ┌─────────────────────────────────────────────────┐   │
│  │                  YourAdapter                     │   │
│  │         implements EventAdapter<TRaw>           │   │
│  │     convert(raw, sessionId) → AipMessage[]      │   │
│  └─────────────────────────────────────────────────┘   │
│                         │                               │
│                         ▼                               │
│  ┌─────────────────────────────────────────────────┐   │
│  │               @foggy/chat                        │   │
│  │  ┌───────────┐  ┌───────────┐  ┌─────────────┐  │   │
│  │  │ SseClient │→│ ChatStore │→│  ChatPanel   │  │   │
│  │  └───────────┘  └───────────┘  └─────────────┘  │   │
│  │        ↑                              │          │   │
│  │   EventAdapter                   Vue Components  │   │
│  │   AipMessage                     MessageBubble   │   │
│  │   ChatMessage                    ToolCallBlock   │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

## License

Private - Internal Use Only
