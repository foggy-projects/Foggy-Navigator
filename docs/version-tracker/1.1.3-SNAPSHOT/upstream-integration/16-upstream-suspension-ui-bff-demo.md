# 上游 Suspension UI + BFF Demo

## 文档作用

- doc_type: integration-demo
- version: 1.1.3-SNAPSHOT
- status: draft
- date: 2026-05-06
- intended_for: upstream-frontend-developer | upstream-backend-developer
- purpose: 给出上游前端和 BFF 如何组合 `BusinessSuspensionDialog` / `NavigatorChat` 并转发 resume 决策的最小示例

## 目标边界

本 Demo 只说明上游 UI 与 BFF 的职责：

- 前端展示清洗后的 suspension model。
- 前端提交 approve/reject decision。
- BFF 调用 Navigator SDK 或 Control Plane resume API。
- Java Suspension service 执行业务副作用。

前端不接触：

- `task_scoped_token`
- runtime credential / provisioning credential / admin token
- 上游用户 token
- `adapterConfigJson`
- `manifestJson`
- 完整 binding context
- input hash 原文

## BFF API

### 查询清洗后的 suspension

```text
GET /bff/navigator/suspensions/{suspendId}
```

返回：

```json
{
  "suspendId": "sus_xxx",
  "suspensionType": "APPROVAL_REQUIRED",
  "status": "pending",
  "title": "签收确认",
  "summary": "确认要执行自提签收操作",
  "functionId": "tms.fulfillment.selfPickupSign",
  "version": "v1",
  "riskLevel": "P1",
  "expiresAt": "2026-05-06T23:59:59+08:00",
  "displayFields": [
    { "label": "orderIdentifier", "value": "1129" }
  ]
}
```

BFF 负责从 Navigator、业务消息或本地 projection 中构造该模型。它不得把 binding context、input hash 原文或任何 token 透传给浏览器。

### 提交用户决策

```text
POST /bff/navigator/suspensions/{suspendId}/decision
Content-Type: application/json

{
  "decision": "approved",
  "comment": "确认签收"
}
```

BFF 服务端再调用：

```java
client.businessAgent().resumeSuspension(suspendId, form);
```

`form` 中需要的 binding context 应由 BFF 在服务端持有、回查或由 Navigator 控制面解析；浏览器请求体只传 decision 和 comment。

## NavigatorChat 默认弹窗 Demo

```vue
<template>
  <NavigatorChat
    v-model:suspension-dialog-visible="suspensionVisible"
    :config="chatConfig"
    :suspension="currentSuspension"
    :suspension-submitting="submitting"
    title="TMS 业务助手"
    height="640px"
    @suspension-decision="submitDecision"
  />
</template>

<script setup lang="ts">
import { ref } from 'vue'
import '@foggy/navigator-chat-widget/style.css'
import '@foggy/chat/style.css'
import {
  NavigatorChat,
  type BusinessSuspensionDecisionPayload,
  type BusinessSuspensionDialogModel,
  type NavigatorChatConfig,
} from '@foggy/navigator-chat-widget'

const chatConfig: NavigatorChatConfig = {
  baseUrl: '/bff/navigator',
  agentId: 'business-agent',
}

const suspensionVisible = ref(false)
const submitting = ref(false)
const currentSuspension = ref<BusinessSuspensionDialogModel | null>(null)

async function openSuspension(suspendId: string) {
  const resp = await fetch(`/bff/navigator/suspensions/${suspendId}`)
  currentSuspension.value = await resp.json()
  suspensionVisible.value = true
}

async function submitDecision(payload: BusinessSuspensionDecisionPayload) {
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
    suspensionVisible.value = false
  } finally {
    submitting.value = false
  }
}
</script>
```

`openSuspension` 可以由上游自己的 SSE、任务消息解析或业务页面按钮触发。

## ChatPanel 内联卡片 Demo

`@foggy/chat` 的 `ChatPanel` / `MessageList` 仍适合内联审批卡片。上游收到 `skillApprovalRespond` 后不要直接在浏览器调用 Navigator resume，而是转给 BFF：

```vue
<template>
  <ChatPanel
    :messages="messages"
    @skill-approval-respond="submitInlineApproval"
  />
</template>

<script setup lang="ts">
import '@foggy/chat/style.css'
import { ChatPanel, type ChatMessage } from '@foggy/chat'

const messages: ChatMessage[] = []

async function submitInlineApproval(taskId: string, decision: string, comment: string) {
  await fetch('/bff/navigator/suspensions/by-task-decision', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ taskId, decision, comment }),
  })
}
</script>
```

BFF 可根据 `taskId` 查找当前 pending suspension，再调用 Navigator resume。若前端消息中已经有 `suspendId`，也可以直接调用 `/bff/navigator/suspensions/{suspendId}/decision`。

## 幂等体验

- 前端提交后设置 `submitting=true`，禁用按钮。
- BFF 对相同 `suspendId` 的重复 decision 应返回当前已处理状态或业务错误摘要。
- Navigator Java 侧仍是最终幂等保护边界；UI 禁用只是用户体验优化。
- 如果用户刷新页面，BFF 应重新查询 suspension 状态并返回终态，避免重新展示可点击按钮。
