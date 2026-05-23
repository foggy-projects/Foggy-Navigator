# uni-app Navigator Chat BFF Handoff Prompt

## 文档作用

- doc_type: handoff-prompt
- version: 1.1.3-SNAPSHOT / 1.3.0-SNAPSHOT
- status: draft
- date: 2026-05-21
- intended_for: upstream-uni-app-developer, upstream-bff-developer, upstream-llm-coding-agent
- purpose: 给上游 IDE Agent 一段可直接执行的提示词，用于把 uni-app 原生插件 `foggy-navigator-chat` 接入 TMS APP 并完成 BFF 联调。

## 相关文档

- [00-overview.md](./00-overview.md) - 上游接入总览与安全边界。
- [02-frontend-component-quickstart.md](./02-frontend-component-quickstart.md) - 前端组件快速上手，含 uni-app 插件示例。
- [06-session-task-message-flow.md](./06-session-task-message-flow.md) - ask、task messages、session history 协议。
- [07-approval-flow.md](./07-approval-flow.md) - Suspension 审批/确认流。
- [09-security-boundaries.md](./09-security-boundaries.md) - 上游前后端安全红线。
- [24-chat-session-history-and-client-context.md](./24-chat-session-history-and-client-context.md) - 历史会话与 clientContext。
- [REQ-126-mobile-agent-chat-component.md](../../1.3.0-SNAPSHOT/workitems/REQ-126-mobile-agent-chat-component.md) - issue #126 移动组件验收记录。

## 可直接给上游 Agent 的提示词

```markdown
# Development Handoff: 接入 uni-app foggy-navigator-chat

你在上游 TMS v3.2.1 uni-app 项目中工作。目标是在 APP 首屏或主要 Agent 页面接入 Navigator 提供的 `foggy-navigator-chat` uni_modules 插件，并通过 TMS BFF 与 Navigator OpenAPI 联调。

重要约束：

- 项目技术栈是 uni-app + Vue 3 + TypeScript + Pinia。
- UI 和页面能力优先使用 uni-app primitives：`view`、`scroll-view`、`textarea`、`button`、`pages.json`、`uni.navigateTo` / `uni.switchTab`。
- 不要引入普通 Web-only 组件作为 APP 主方案，不要依赖 `window`、`document`、`File`、DOM scroll API 或 `vue-router`。
- APP 只访问 TMS BFF，不直接访问 Navigator OpenAPI、Worker Gateway 或任何内部网关。
- APP 端不得保存或展示 `task_scoped_token`、runtime credential、ClientApp secret、admin/provisioning token、Worker Gateway URL、`adapterConfigJson`、raw `manifestJson`。

## 先读文档和代码

Navigator 侧参考：

- `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/00-overview.md`
- `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/02-frontend-component-quickstart.md`
- `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/06-session-task-message-flow.md`
- `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/07-approval-flow.md`
- `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/09-security-boundaries.md`
- `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/24-chat-session-history-and-client-context.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/REQ-126-mobile-agent-chat-component.md`

组件位置：

- `packages/foggy-mobile/src/uni_modules/foggy-navigator-chat`

如果上游仓库没有这些文件，请向 Navigator 对接人索取插件目录或发布包。

## 前端接入任务

1. 将 `foggy-navigator-chat` 放入上游 uni-app 项目的 `src/uni_modules/foggy-navigator-chat`。
2. 在 `pages.json` 注册 Agent 页面，例如 `pages/agent/index`。
3. 在页面使用组件：

```vue
<template>
  <foggy-navigator-chat
    :config="chatConfig"
    title="TMS 移动助手"
    subtitle="当前账号的业务 Agent"
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

4. 配置只指向 TMS BFF：

```ts
import { ref } from 'vue'
import type {
  BusinessSuspensionDecisionPayload,
  BusinessSuspensionDialogModel,
  FoggyNavigatorAction,
  FoggyNavigatorChatConfig,
} from '@/uni_modules/foggy-navigator-chat'

const suspensionVisible = ref(false)
const submittingSuspension = ref(false)
const currentSuspension = ref<BusinessSuspensionDialogModel | null>(null)

const chatConfig: FoggyNavigatorChatConfig = {
  baseUrl: '/bff/navigator',
  agentId: '<tms-mobile-agent-id>',
  pollInterval: 4000,
  maxTurns: 3,
  showToolCalls: true,
  showToolResults: true,
  executionReportMarkdownLoader: loadExecutionReportMarkdown,
}
```

默认组件会通过 `uni.request` 调用：

```text
POST   /bff/navigator/api/v1/open/agents/{agentId}/ask
GET    /bff/navigator/api/v1/open/agents/{agentId}/tasks/{taskId}/messages?cursor=&limit=
POST   /bff/navigator/api/v1/open/agents/{agentId}/tasks/{taskId}/cancel
GET    /bff/navigator/api/v1/open/agents/{agentId}/sessions?cursor=&limit=
GET    /bff/navigator/api/v1/open/agents/{agentId}/sessions/{contextId}/messages?cursor=&limit=
DELETE /bff/navigator/api/v1/open/agents/{agentId}/sessions/{contextId}
```

如果 TMS BFF 路由、鉴权或签名方式不同，通过 `client` prop 注入完整 `FoggyNavigatorChatClient` callbacks，不要修改组件内部去硬编码业务系统。

5. 执行报告 Markdown 只通过 TMS BFF 按需读取：

```ts
async function loadExecutionReportMarkdown(reportRef: string) {
  const result = await uni.request({
    url: `/bff/navigator/execution-reports/${encodeURIComponent(reportRef)}/markdown`,
    method: 'GET',
  })
  const body = result.data as { code: number; msg?: string; data: { markdown: string } }
  if (body.code !== 0) throw new Error(body.msg || '执行报告读取失败')
  return body.data
}
```

6. 业务动作只由 APP 宿主路由处理：

```ts
function handleAction(action: FoggyNavigatorAction) {
  if (action.type === 'OPEN_TMS_PAGE') {
    const payload = action.payload as { id?: string; page?: string } | undefined
    uni.navigateTo({ url: `/pages/tms/detail?id=${encodeURIComponent(String(payload?.id ?? ''))}` })
  }
}
```

7. Suspension 只把决策交回 TMS BFF：

```ts
async function handleSuspensionDecision(payload: BusinessSuspensionDecisionPayload) {
  submittingSuspension.value = true
  try {
    await uni.request({
      url: `/bff/navigator/suspensions/${encodeURIComponent(payload.suspendId)}/decision`,
      method: 'POST',
      data: {
        decision: payload.decision,
        comment: payload.comment,
      },
    })
  } finally {
    submittingSuspension.value = false
  }
}
```

`currentSuspension` 必须来自 TMS BFF 或控制面清洗后的 `BusinessSuspensionDialogModel`，只包含可展示字段：标题、摘要、functionId/functionDisplayName、riskLevel、expiresAt、displayFields 等。不要把 token、manifest、adapter config、完整 binding context 或 input hash 原文传给 APP。

## BFF 接入任务

1. 基于当前 TMS 登录态解析稳定 `upstreamUserId`。
2. 在服务端保存 ClientApp/runtime credential，不把任何 secret 返回 APP。
3. ask 前完成必要授权或校验：

```text
current TMS user
  -> upstreamUserId
  -> ensure upstream user grant
  -> exchange/runtime auth on server
  -> call Navigator OpenAPI with ClientApp headers
```

4. 代理 OpenAPI ask/task messages/session APIs，响应保持 `{ code: 0, data }` envelope。
5. 提供执行报告 Markdown 读取接口，只允许读取当前用户/当前 ClientApp 可见的 reportRef。
6. 提供 suspension decision 接口，把 approve/reject 转成 Navigator resume/decision 调用。
7. 对所有响应做脱敏，不返回内部 token、adapterConfigJson、manifestJson、Worker Gateway URL、原始错误栈。

## 验收检查

前端 APP/H5：

- 页面首屏就是可用的 Agent 对话面板。
- 键盘弹起后底部输入仍可用；Android/iOS WebView 都要检查。
- `USER/TEXT/TOOL_CALL/TOOL_RESULT/RESULT/STATE/ERROR` 都有合理展示。
- 弱网和重复 polling 不产生重复消息，任务未完成时能从历史会话继续查看。
- 历史会话能打开、选择、恢复 `contextId`。
- 执行报告先展示 digest，点击后才通过 BFF 加载完整 Markdown。
- suspension sheet 可以 approve/reject，事件只包含 `suspendId/suspensionType/decision/comment`。
- business action 使用 `uni.navigateTo` / `uni.switchTab` 由宿主处理。

安全：

- APP storage、network response、页面渲染内容中搜索不到 `task_scoped_token`、`adapterConfigJson`、`manifestJson`、`client_app_secret`、runtime credential。
- APP 不请求 Worker Gateway。
- BFF 日志和错误响应不输出敏感 token 或签名 URL。

建议测试：

- 组件/逻辑测试覆盖 action、suspension decision、report loader、session history、弱网 polling。
- uni-app H5 构建通过。
- Android/iOS WebView 手工或自动化截图覆盖长消息、工具卡、报告、审批、键盘弹起。

## 交付物

完成后回复：

- 修改的前端/BFF 文件列表。
- BFF 暴露的接口路径。
- 运行过的测试命令和结果。
- APP/H5 截图或自动化证据。
- 明确说明 APP 侧未暴露 Navigator/Worker 敏感凭证。
```
