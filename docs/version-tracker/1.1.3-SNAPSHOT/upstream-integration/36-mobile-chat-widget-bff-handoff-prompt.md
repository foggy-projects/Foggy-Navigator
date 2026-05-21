# Mobile Chat Widget BFF Handoff Prompt

## 文档作用

- doc_type: handoff-prompt
- version: 1.1.3-SNAPSHOT / 1.3.0-SNAPSHOT
- status: draft
- date: 2026-05-20
- intended_for: upstream-frontend-developer, upstream-bff-developer, upstream-llm-coding-agent
- purpose: 给上游 IDE Agent 一段可直接执行的提示词，用于把 H5 fallback `NavigatorMobileChat` 接入移动端 Vue 3 / Vite 应用并完成 BFF 联调。

> TMS v3.2.1 APP 主线已转向 uni-app。uni-app 项目优先使用 [37-uni-app-navigator-chat-bff-handoff-prompt.md](./37-uni-app-navigator-chat-bff-handoff-prompt.md) 和 `packages/foggy-mobile/src/uni_modules/foggy-navigator-chat`；本文只适用于普通 Vue 3 / Vite H5 fallback。

## 相关文档

- [00-overview.md](./00-overview.md) - 上游接入总览与安全边界。
- [02-frontend-component-quickstart.md](./02-frontend-component-quickstart.md) - 前端组件快速上手，含 uni-app `foggy-navigator-chat` 与 H5 `NavigatorMobileChat` 示例。
- [37-uni-app-navigator-chat-bff-handoff-prompt.md](./37-uni-app-navigator-chat-bff-handoff-prompt.md) - uni-app 主线接入提示词。
- [06-session-task-message-flow.md](./06-session-task-message-flow.md) - ask、task messages、session history 协议。
- [07-approval-flow.md](./07-approval-flow.md) - Suspension 审批/确认流。
- [09-security-boundaries.md](./09-security-boundaries.md) - 上游前后端安全红线。
- [24-chat-session-history-and-client-context.md](./24-chat-session-history-and-client-context.md) - 历史会话与 clientContext。
- [35-navigator-chat-observer-bff-real-mode.md](./35-navigator-chat-observer-bff-real-mode.md) - Navigator 侧本地 Observer BFF real mode，可作为联调参照。
- [REQ-126-mobile-agent-chat-component.md](../../1.3.0-SNAPSHOT/workitems/REQ-126-mobile-agent-chat-component.md) - issue #126 移动组件验收记录。

## 可直接给上游 Agent 的提示词

```markdown
# Development Handoff: 接入 NavigatorMobileChat 移动端 Agent 组件

你在上游业务系统的 Vue 3 / Vite 移动端 H5 fallback 项目中工作。目标是在普通移动端 H5 或非 uni-app WebView 页面接入 `@foggy/navigator-chat-widget` 的 `NavigatorMobileChat`，并通过上游 BFF 与 Navigator OpenAPI 联调。若当前项目是 uni-app，请停止使用本文，改按 `37-uni-app-navigator-chat-bff-handoff-prompt.md` 执行。

重要：不要假设你能看到 Navigator 团队的历史聊天、Codex/Claude memory、CLAUDE.md、AGENTS.md 或本地 skills。先阅读本项目已有接入文档和当前上游项目约定，再改代码。不要回滚用户已有改动，先检查 `git status --short`。

## 先读文档

Navigator 侧参考文档：

- `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/00-overview.md`
- `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/02-frontend-component-quickstart.md`
- `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/06-session-task-message-flow.md`
- `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/07-approval-flow.md`
- `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/09-security-boundaries.md`
- `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/24-chat-session-history-and-client-context.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/REQ-126-mobile-agent-chat-component.md`

如果这些文档不在当前上游仓库中，请向 Navigator 对接人索取，或按本文的接口/安全边界执行。

## 背景和边界

`NavigatorMobileChat` 是 Vue 3 组件，适用于移动 H5 / App WebView。组件只负责：

- 展示用户/助手消息、OpenAPI `USER/TEXT/TOOL_CALL/TOOL_RESULT/RESULT/STATE/ERROR` 消息。
- 展示移动端工具卡、历史会话 sheet、执行报告 digest、业务确认 suspension sheet。
- 通过事件把业务动作、审批决策、附件点击等交给上游处理。

组件不负责：

- 持有 Navigator runtime credential、ClientApp secret、admin/provisioning token。
- 直接访问 Worker Gateway。
- 在组件内部硬编码上游业务路由。
- 自动调用 suspension resume API。

浏览器端不得出现或持有：

- `task_scoped_token`
- `adapterConfigJson`
- raw `manifestJson`
- runtime credential / App Secret / admin token / provisioning credential
- Worker Gateway 内部 URL、auth header 或 token

## 前端接入任务

1. 安装并导入组件包：

```ts
import {
  NavigatorMobileChat,
  type BusinessSuspensionDecisionPayload,
  type BusinessSuspensionDialogModel,
  type ExecutionReportMarkdownPayload,
  type NavigatorAction,
  type NavigatorChatConfig,
} from '@foggy/navigator-chat-widget'
import '@foggy/navigator-chat-widget/style.css'
```

2. 在移动端页面挂载：

```vue
<template>
  <NavigatorMobileChat
    class="upstream-mobile-agent"
    :config="chatConfig"
    title="业务助手"
    subtitle="当前账号"
    show-history
    show-tool-calls
    show-tool-results
    v-model:suspension-dialog-visible="suspensionVisible"
    :suspension="currentSuspension"
    :suspension-submitting="submittingSuspension"
    @action="handleAction"
    @suspension-decision="handleSuspensionDecision"
  />
</template>
```

3. 配置只指向上游 BFF，不在浏览器放 Navigator 密钥：

```ts
const chatConfig: NavigatorChatConfig = {
  baseUrl: '/bff/navigator',
  agentId: '<your-openapi-agent-or-business-skill-id>',
  pollInterval: 4000,
  maxTurns: 3,
  showToolCalls: true,
  showToolResults: true,
  executionReportMarkdownLoader: loadExecutionReportMarkdown,
}
```

组件会请求：

```text
POST   /bff/navigator/api/v1/open/agents/{agentId}/ask
GET    /bff/navigator/api/v1/open/agents/{agentId}/tasks/{taskId}/messages?cursor=&limit=
POST   /bff/navigator/api/v1/open/agents/{agentId}/tasks/{taskId}/cancel
GET    /bff/navigator/api/v1/open/agents/{agentId}/sessions?cursor=&limit=
GET    /bff/navigator/api/v1/open/agents/{agentId}/sessions/{contextId}/messages?cursor=&limit=
DELETE /bff/navigator/api/v1/open/agents/{agentId}/sessions/{contextId}  # 仅 showHistoryDelete 开启时需要
```

4. 执行报告 Markdown 必须通过上游 BFF 按需读取：

```ts
async function loadExecutionReportMarkdown(reportRef: string): Promise<ExecutionReportMarkdownPayload> {
  const resp = await fetch(`/bff/navigator/execution-reports/${encodeURIComponent(reportRef)}/markdown`)
  const json = await resp.json()
  if (!resp.ok || json.code !== 0) {
    throw new Error(json.msg || '执行报告读取失败')
  }
  return json.data
}
```

5. 业务动作只由上游路由执行：

```ts
function handleAction(action: NavigatorAction) {
  if (action.type === 'OPEN_TMS_PAGE') {
    const payload = action.payload as { page?: string; waybillNo?: string } | undefined
    const target = payload?.page ?? payload?.waybillNo ?? ''
    router.push(`/mobile/tms/${encodeURIComponent(target)}`)
  }
}
```

6. Suspension 只提交决策给上游 BFF：

```ts
async function handleSuspensionDecision(payload: BusinessSuspensionDecisionPayload) {
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
```

`currentSuspension` 必须来自 BFF 或控制面清洗后的 `BusinessSuspensionDialogModel`，只包含标题、摘要、functionId/functionDisplayName、riskLevel、expiresAt、displayFields 等展示字段，不得包含 token、manifest、adapter 配置、完整 binding context 或 input hash 原文。

7. 如需附件，使用上游自己的上传接口：

```ts
const chatConfig: NavigatorChatConfig = {
  // ...
  uploadAttachment: uploadAttachmentToBff,
  enableAttachments: true,
  acceptedAttachmentTypes: ['image/*', '.pdf', '.xlsx', '.xls', '.doc', '.docx', '.txt'],
}

async function uploadAttachmentToBff(file: File) {
  const form = new FormData()
  form.append('file', file)
  const resp = await fetch('/bff/navigator/attachments', { method: 'POST', body: form })
  const json = await resp.json()
  if (!resp.ok || json.code !== 0) throw new Error(json.msg || '附件上传失败')
  return json.data
}
```

## BFF 接入任务

1. BFF 读取当前上游登录态，解析稳定的 `upstreamUserId`。
2. BFF 服务端保存 ClientApp/runtime credential，不把任何 secret 返回浏览器。
3. BFF 在 ask 前完成必要授权或校验：

```text
current user -> upstreamUserId
  -> ensure upstream user grant
  -> exchange/runtime auth on server
  -> call Navigator OpenAPI with ClientApp headers
```

4. BFF 代理 OpenAPI ask/task messages/session APIs，响应保持 `{ code: 0, data }` envelope。
5. BFF 提供执行报告 Markdown 读取接口，只允许读取当前用户/当前 ClientApp 可见的 reportRef。
6. BFF 提供 suspension decision 接口，把 approve/reject 转成 Navigator resume/decision 调用。
7. BFF 对所有响应做脱敏，不把内部 token、adapterConfigJson、manifestJson、Worker Gateway URL、原始错误栈返回给浏览器。

## 验收检查

前端移动视口：

- 页面首屏就是可用的移动 Agent 对话面板，不是营销页或空壳。
- 键盘弹起后底部输入仍可用；iOS/Android WebView 下检查 `100dvh` 和 safe-area。
- `USER/TEXT/TOOL_CALL/TOOL_RESULT/RESULT/STATE/ERROR` 都有合理展示。
- 工具卡紧凑展示，诊断详情默认不暴露。
- 历史会话能打开、选择、恢复上下文。
- 点击 action 只触发上游路由，不在组件内写死 URL。
- 执行报告先显示 digest，点击后才通过 BFF 加载完整 Markdown。
- suspension sheet 可以 approve/reject，事件只包含 `suspendId/suspensionType/decision/comment`。

安全检查：

- 浏览器 localStorage/sessionStorage/network response/DOM 中搜索不到 `task_scoped_token`、`adapterConfigJson`、`manifestJson`、`client_app_secret`、`runtime credential`。
- 浏览器不请求 Worker Gateway。
- BFF 日志和错误响应不输出敏感 token 或签名 URL。

建议测试：

- 单元测试或组件测试覆盖 action、suspension decision、report loader。
- Playwright 移动视口覆盖发送消息、历史会话、工具卡、报告、审批。
- 真实 BFF 联调覆盖 ask -> poll -> result、继续会话 contextId、失败 ERROR、session history、report markdown、suspension decision。

## 交付物

完成后回复：

- 修改的前端/BFF 文件列表。
- BFF 暴露的接口路径。
- 运行过的测试命令和结果。
- 移动端截图或 Playwright 证据。
- 明确说明浏览器侧未暴露 Navigator/Worker 敏感凭证。
```
