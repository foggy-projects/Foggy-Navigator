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
- 由 BFF 基于当前登录用户按需补齐 Navigator upstream-user grant，而不是让浏览器提交或缓存 grant/token

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
  NavigatorAttachmentResult,
} from '@foggy/navigator-chat-widget'

const chatConfig: NavigatorChatConfig = {
  baseUrl: '/bff/api',    // 上游 BFF 代理地址
  agentId: 'your-agent-id',
  pollInterval: 4000,     // TMS 初始接入先使用 4s 轮询，后续按体验和服务负载再调整
  uploadAttachment: uploadTmsAttachment,
  acceptedAttachmentTypes: ['image/*', 'application/pdf', '.xlsx', '.xls', '.doc', '.docx', '.txt', '.csv', '.zip'],
  // 不在浏览器中配置 Navigator admin/provisioning/runtime credential。
  // 如需鉴权，推荐通过 cookie 或自定义 fetch 与上游 BFF 会话绑定。
}

const suspensionVisible = ref(false)
const submittingSuspension = ref(false)
const currentSuspension = ref<BusinessSuspensionDialogModel | null>(null)

async function uploadTmsAttachment(file: File): Promise<NavigatorAttachmentResult> {
  const form = new FormData()
  form.append('file', file)
  const resp = await fetch('/x3-web/tenant/attachment/upload', {
    method: 'POST',
    body: form,
  })
  const json = await resp.json()
  if (!resp.ok || json.code !== 0) {
    throw new Error(json.msg || '附件上传失败')
  }
  return json.data
}

function onSend(content: string, attachments?: NavigatorAttachmentResult[]) {
  console.log('用户发送:', content, attachments)
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

附件采用 upload-on-submit：用户选择、拖拽或粘贴文件时，组件只保留浏览器本地 `File` 和预览信息；点击发送后先调用 `uploadAttachment(file)`，上传成功后把返回的 `NavigatorAttachmentResult[]` 放入 ask 请求顶层 `attachments`。任一附件上传失败时不会发送 ask，附件会保留在输入区供用户重试或删除。

本仓库提供本地观测页用于区分“上游未注入 hook”和“组件自身未显示入口”：

```bash
pnpm --dir packages/navigator-chat-widget dev:observe
```

打开页面后切换“已注入 / 未注入”模式。已注入模式应显示回形针按钮，并在发送后展示最近 ask body 中的顶层 `attachments`；未注入模式应隐藏附件入口。组件根节点同时带有 `data-upload-hook="present|missing"` 和 `data-attachments-enabled="true|false"`，可在浏览器 Elements 面板直接确认。

观测页默认绑定 `0.0.0.0:5179`，本机可访问 `http://127.0.0.1:5179/`，局域网内其他设备可访问 `http://<本机IP>:5179/`。

`NavigatorChat` 不会自动从普通轮询任务结果中推断 suspension。上游宿主应从自己的 BFF、SSE 或任务消息中解析出 `BusinessSuspensionDialogModel` 后传入 `currentSuspension`，再打开 `suspensionVisible`。

### 1.1 移动端 `NavigatorMobileChat`

移动端 H5 / App WebView 推荐使用 `NavigatorMobileChat`。它复用同一套 `NavigatorChatConfig` 和 OpenAPI 轮询/session API，但布局是移动全高面板：顶部状态栏、消息滚动区、底部输入区、历史会话 sheet、执行报告 sheet 和业务确认 sheet。

```vue
<template>
  <NavigatorMobileChat
    class="tms-mobile-agent"
    :config="chatConfig"
    title="TMS 移动助手"
    subtitle="当前账号的业务 Agent"
    show-history
    show-tool-calls
    show-tool-results
    v-model:suspension-dialog-visible="suspensionVisible"
    :suspension="currentSuspension"
    :suspension-submitting="submittingSuspension"
    @action="onBusinessAction"
    @suspension-decision="onSuspensionDecision"
  />
</template>

<script setup lang="ts">
import { ref } from 'vue'
import {
  NavigatorMobileChat,
  type BusinessSuspensionDecisionPayload,
  type BusinessSuspensionDialogModel,
  type ExecutionReportMarkdownPayload,
  type NavigatorAction,
  type NavigatorChatConfig,
} from '@foggy/navigator-chat-widget'
import '@foggy/navigator-chat-widget/style.css'

const suspensionVisible = ref(false)
const submittingSuspension = ref(false)
const currentSuspension = ref<BusinessSuspensionDialogModel | null>(null)

const chatConfig: NavigatorChatConfig = {
  baseUrl: '/bff/navigator',
  agentId: 'tms.mobile.agent',
  pollInterval: 4000,
  showToolCalls: true,
  showToolResults: true,
  executionReportMarkdownLoader: loadExecutionReportMarkdown,
}

async function loadExecutionReportMarkdown(reportRef: string): Promise<ExecutionReportMarkdownPayload> {
  const resp = await fetch(`/bff/navigator/execution-reports/${encodeURIComponent(reportRef)}/markdown`)
  const json = await resp.json()
  if (!resp.ok || json.code !== 0) {
    throw new Error(json.msg || '执行报告读取失败')
  }
  return json.data
}

function onBusinessAction(action: NavigatorAction) {
  if (action.type === 'OPEN_TMS_PAGE') {
    // 业务路由由上游实现，组件只 emit action。
    const payload = action.payload as { page?: string; waybillNo?: string } | undefined
    const target = payload?.page ?? payload?.waybillNo ?? ''
    window.location.href = `/mobile/tms/${encodeURIComponent(target)}`
  }
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

<style>
.tms-mobile-agent {
  --nmc-primary: #0f66d0;
  --nmc-bg: #f7f8fa;
  --nmc-surface: #ffffff;
}
</style>
```

移动端接入要点：

- 浏览器仍只连上游 BFF，不直接连 Worker Gateway，不持有 `task_scoped_token`、runtime credential、App Secret 或 admin token。
- `TOOL_CALL` / `TOOL_RESULT` 默认以紧凑卡片展示；`showToolCalls`、`showToolResults` 可按业务需要打开。
- `structured_output.label` / `pageLabel` 会作为按钮文案，点击后只触发 `@action`，路由跳转由宿主处理。
- 执行报告默认只展示 digest；完整 Markdown 必须通过 `executionReportMarkdownLoader(reportRef)` 从上游 BFF 按需读取。
- suspension sheet 只展示 `BusinessSuspensionDialogModel` 中的清洗字段，并只发出 approve/reject 决策事件。
- 样式可通过 `--nmc-*` CSS variables 覆盖，不需要 fork 组件。

给上游 IDE Agent / LLM coding agent 的移动端接入提示词见 [36-mobile-chat-widget-bff-handoff-prompt.md](./36-mobile-chat-widget-bff-handoff-prompt.md)。

### 1.2 BFF ask 前置动作

上游 BFF 在代理 `ask` 前必须基于当前登录态执行服务端前置动作：

```text
resolve current user
  -> upstreamUserId = stable TMS user/staff id
  -> upstreamUserToken = current server-side TMS user token/session token
  -> ensureUpstreamUserGrant(tenantId, clientAppId, upstreamUserId, upstreamUserToken)
  -> exchange runtime credential for ClientApp access token
  -> call Navigator ask with X-Client-App-Key, X-Client-App-Access-Token, X-Upstream-User-Id
```

`ensureUpstreamUserGrant` 必须是幂等 upsert：同一个 `tenantId + clientAppId + upstreamUserId` 重复调用应保持 `ENABLED`，并在 token 轮换时更新服务端保存的上游用户 token。BFF 不应在启动时枚举所有 TMS 用户批量授权，也不应把 token、ClientApp secret 或 access token 传给浏览器。

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
| 附件上传 | 宿主自定义 | ✅ 选择/粘贴/拖拽 + upload-on-submit |
| 思考动画 | ✅ ThinkingIndicator | ✅ 内置 |
| SSE 客户端 | ✅ createSseClient | ✅ 内置 |
| 状态管理 | ✅ useChatStore (Pinia) | ✅ useNavigatorChat |
| Markdown 渲染 | — 需宿主实现 | ✅ 内置 (markdown-it) |
| 自定义适配器 | ✅ EventAdapter 接口 | — 内置适配器 |

## 更多参考

- `@foggy/chat` 完整 API 和适配器示例：[packages/foggy-chat/README.md](../../../../packages/foggy-chat/README.md)
- `@foggy/navigator-chat-widget` 组件 Props 和事件：[packages/navigator-chat-widget/](../../../../packages/navigator-chat-widget/)
