# 前端组件快速上手

## 文档作用

- doc_type: integration-guide
- version: 1.1.3-SNAPSHOT
- status: draft
- date: 2026-05-06
- intended_for: upstream-frontend-developer
- purpose: 指导上游前端开发者使用 `@foggy/chat` 和 `@foggy/navigator-chat-widget` 集成聊天、工具调用和审批 UI

## 组件定位

Navigator 提供两个前端组件包，分别面向不同集成深度：

| 包名 | 定位 | 适用场景 |
| --- | --- | --- |
| `@foggy/chat` | 底层组件库 | 需要深度定制的上游前端 |
| `@foggy/navigator-chat-widget` | 开箱即用对话组件 | 快速集成、最小配置 |

### `@foggy/chat`

基于 AIP (Agent Interaction Protocol) 协议的 Vue 3 可复用聊天组件库，提供：

- **ChatPanel**：主聊天面板，整合消息列表、输入框和状态显示
- **MessageList / MessageBubble**：消息滚动列表和文本气泡
- **MessageInput**：输入框 + 发送按钮
- **ToolCallBlock**：工具调用卡片（命令 + 输出展示）
- **ChatPanel / MessageList 内置审批渲染**：通过内部审批卡片展示 Approve / Reject 按钮和状态
- **BusinessSuspensionDialog**：受控业务暂停弹窗，用于上游在聊天卡片外展示审批、人工确认等暂停交互
- **ThinkingIndicator**：思考中动画
- **StatusBadge**：状态徽章
- **useChatStore**：Pinia Store，管理消息状态
- **createSseClient**：SSE 客户端，支持命名事件
- **EventAdapter**：适配器接口，将不同后端事件格式转换为 AIP 消息

### `@foggy/navigator-chat-widget`

封装 `useNavigatorChat` composable 的开箱即用 Vue 3 组件，提供：

- **NavigatorChat**：完整对话组件，内置消息列表、输入、Markdown 渲染、状态标签
- 可选内置 **BusinessSuspensionDialog** 弹窗表面，由宿主传入清洗后的 suspension model 并处理决策事件
- 支持 `send` / `cancel` / `clear` 编程式控制
- 支持 `@send` / `@statusChange` / `@reply` / `@suspensionDecision` 事件

## 安全边界

> **⚠️ 前端安全红线**

上游前端组件**不得**：

- 直接调用 Worker Gateway（`/internal/worker-gateway/v1/**`）
- 持有或缓存 `task_scoped_token`
- 持有 admin token、provisioning credential 或 runtime credential
- 持有 App Secret 或任何服务端密钥

上游前端**应该**：

- 通过上游后端 BFF（Backend for Frontend）或平台公开的会话 API 与 Navigator 交互
- 仅使用上游 BFF 签发的浏览器会话凭据进行身份标识，不直接持有 Navigator API Key
- 在 BFF 层完成凭证注入和权限校验

```text
上游前端
  -> 上游 BFF / 平台公开 API
      -> Navigator Java Control Plane
          -> (内部) Worker Gateway
              -> Biz Worker
```

## 快速开始：`@foggy/chat`

### 1. 安装

```bash
npm install @foggy/chat
# peerDependencies: vue ^3.5.0, element-plus ^2.13.0, pinia ^3.0.0
```

### 2. 导入样式

```typescript
// main.ts
import '@foggy/chat/style.css'
```

如果使用 `@foggy/navigator-chat-widget` 的默认 suspension 弹窗，也需要导入 `@foggy/chat/style.css`，因为弹窗组件来自 `@foggy/chat`。

### 3. 基本使用

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
  // 通过 BFF 调用后端 API 发送消息
}
</script>
```

### 4. SSE 订阅（通过 BFF）

```typescript
import { createSseClient } from '@foggy/chat'
import type { AipMessage } from '@foggy/chat'

// SSE 连接应指向上游 BFF 代理的端点，不直接连 Navigator 内部 API
const eventSource = createSseClient({
  url: '/bff/api/events/stream?sessionId=xxx',
  eventName: 'event',
  adapter: yourAdapter,          // 实现 EventAdapter 接口
  sessionId: 'xxx',
  onMessage: (messages: AipMessage[]) => {
    for (const m of messages) {
      chatStore.processAipMessage(m)
    }
  },
})
```

### 5. 审批卡片与弹窗

`ChatPanel` / `MessageList` 会根据 `ChatMessage.approvalStatus` 自动渲染内部审批卡片：

- `pending`：显示 Approve / Reject 按钮
- `approved`：绿色已批准状态
- `rejected`：红色已拒绝状态

```vue
<ChatPanel
  :messages="chatStore.sortedMessages"
  @skill-approval-respond="handleApproval"
/>
```

审批决策通过 `skillApprovalRespond` 事件触发，应由上游 BFF 转发到 Navigator 控制面 API：

```text
POST /api/v1/business-agent/suspensions/{suspendId}/resume
```

如果上游需要更强的业务确认感，可以使用 `BusinessSuspensionDialog` 作为受控弹窗。组件只接收已清洗的展示模型，并只向宿主发出决策事件；BFF 负责把决策转成 Navigator resume 请求。

```vue
<template>
  <BusinessSuspensionDialog
    v-model="visible"
    :suspension="suspension"
    :submitting="submitting"
    @submit="handleDecision"
  />
</template>

<script setup lang="ts">
import { ref } from 'vue'
import {
  BusinessSuspensionDialog,
  type BusinessSuspensionDecisionPayload,
  type BusinessSuspensionDialogModel,
} from '@foggy/chat'

const visible = ref(true)
const submitting = ref(false)

const suspension = ref<BusinessSuspensionDialogModel>({
  suspendId: 'sus_xxx',
  suspensionType: 'APPROVAL_REQUIRED',
  status: 'pending',
  title: '签收确认',
  summary: '确认要执行自提签收操作',
  functionId: 'tms.fulfillment.selfPickupSign',
  version: 'v1',
  riskLevel: 'P1',
  displayFields: [
    { label: 'orderIdentifier', value: '1129' },
  ],
})

async function handleDecision(payload: BusinessSuspensionDecisionPayload) {
  submitting.value = true
  try {
    await fetch(`/bff/navigator/suspensions/${payload.suspendId}/decision`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        decision: payload.decision,
        comment: payload.comment,
      }),
    })
  } finally {
    submitting.value = false
  }
}
</script>
```

`BusinessSuspensionDialogModel.displayFields` 必须由上游 BFF 或 Navigator 控制面清洗后生成，不允许传入 token、凭据、`adapterConfigJson`、`manifestJson`、完整 binding context 或 input hash 原文。

## 快速开始：`@foggy/navigator-chat-widget`

### 1. 配置

```vue
<template>
  <NavigatorChat
    :config="chatConfig"
    title="业务助手"
    height="600px"
    v-model:suspension-dialog-visible="suspensionVisible"
    :suspension="currentSuspension"
    :suspension-submitting="submittingSuspension"
    @send="onSend"
    @status-change="onStatusChange"
    @suspension-decision="onSuspensionDecision"
  />
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { NavigatorChat } from '@foggy/navigator-chat-widget'
import type {
  BusinessSuspensionDecisionPayload,
  BusinessSuspensionDialogModel,
  NavigatorChatConfig,
} from '@foggy/navigator-chat-widget'

const chatConfig: NavigatorChatConfig = {
  baseUrl: '/bff/api',    // 上游 BFF 代理地址
  agentId: 'your-agent-id',
  pollInterval: 4000,     // TMS 初始接入先使用 4s 轮询，后续按体验和服务负载再调整
  // 不在浏览器中配置 Navigator admin/provisioning/runtime credential。
  // 如需鉴权，推荐通过 cookie 或自定义 fetch 与上游 BFF 会话绑定。
}

const suspensionVisible = ref(false)
const submittingSuspension = ref(false)
const currentSuspension = ref<BusinessSuspensionDialogModel | null>(null)

function onSend(content: string) {
  console.log('用户发送:', content)
}

function onStatusChange(status) {
  console.log('状态变化:', status)
}

async function onSuspensionDecision(payload: BusinessSuspensionDecisionPayload) {
  submittingSuspension.value = true
  try {
    await fetch(`/bff/navigator/suspensions/${payload.suspendId}/decision`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        decision: payload.decision,
        comment: payload.comment,
      }),
    })
  } finally {
    submittingSuspension.value = false
  }
}
</script>
```

`NavigatorChat` 默认采用 `ask -> poll task -> display result` 模式。TMS 初始接入建议显式传入 `pollInterval: 4000`，即浏览器轮询 TMS BFF，TMS BFF 再代理查询 Navigator task 状态。

`NavigatorChat` 不会自动从普通轮询任务结果中推断 suspension。上游宿主应从自己的 BFF、SSE 或任务消息中解析出 `BusinessSuspensionDialogModel` 后传入 `currentSuspension`，再打开 `suspensionVisible`。

### 2. 编程式控制

```typescript
const chatRef = ref()

// 编程式发送
chatRef.value.send('分析一下订单数据')

// 取消当前任务
chatRef.value.cancel()

// 清空对话
chatRef.value.clear()
```

## 组件能力矩阵

| 能力 | `@foggy/chat` | `@foggy/navigator-chat-widget` |
| --- | --- | --- |
| 消息展示 | ✅ ChatPanel + MessageList | ✅ 内置 |
| 用户输入 | ✅ MessageInput | ✅ 内置 |
| 流式文本 | ✅ TEXT_CHUNK / TEXT_COMPLETE | ✅ 内置 |
| 工具调用展示 | ✅ ToolCallBlock | — 需扩展 |
| 审批卡片 | ✅ ChatPanel/MessageList 内置审批渲染 | — 需扩展 |
| Suspension 弹窗 | ✅ BusinessSuspensionDialog | ✅ 可选默认弹窗 / slot 覆盖 |
| 思考动画 | ✅ ThinkingIndicator | ✅ 内置 |
| SSE 客户端 | ✅ createSseClient | ✅ 内置 |
| 状态管理 | ✅ useChatStore (Pinia) | ✅ useNavigatorChat |
| Markdown 渲染 | — 需宿主实现 | ✅ 内置 (markdown-it) |
| 自定义适配器 | ✅ EventAdapter 接口 | — 内置适配器 |

## 更多参考

- `@foggy/chat` 完整 API 和适配器示例：[packages/foggy-chat/README.md](../../../../packages/foggy-chat/README.md)
- `@foggy/navigator-chat-widget` 组件 Props 和事件：[packages/navigator-chat-widget/](../../../../packages/navigator-chat-widget/)
