# REQ-126 Mobile Agent Chat Component

## 文档作用

- doc_type: workitem
- intended_for: navigator-widget-maintainer, upstream-frontend-developer, reviewer
- purpose: 记录 GitHub issue #126 的移动端公共 Agent 聊天组件范围、实现边界、验收标准与测试证据。

## Status

- Version: `1.3.0-SNAPSHOT`
- Source: https://github.com/foggy-projects/Foggy-Navigator/issues/126
- Type: requirement / public frontend component
- Priority: high
- Status: implemented locally, widget tests passed
- Owner: `@foggy/navigator-chat-widget`
- Date: 2026-05-20

## Background

上游 Vue 3 / Vite 移动端 H5 或 App WebView 需要一个可直接挂载的 Agent 对话组件。现有 `NavigatorChat` 偏桌面布局，`packages/foggy-mobile` 是 uni-app App 工程，不能作为上游 Vue 组件库直接发布。

本次新增 `NavigatorMobileChat`，复用 `useNavigatorChat` 与现有 OpenAPI 轮询/session 能力，只负责浏览器端展示与事件上抛。BFF 仍负责身份、凭证、敏感字段清洗和业务跳转执行。

## Accepted V1 Contract

`NavigatorMobileChat` 从 `@foggy/navigator-chat-widget` 导出，面向移动端浏览器/WebView：

- 全高移动面板，默认 `100dvh`，包含安全区 top/bottom 与底部输入区。
- 渲染 OpenAPI 消息类型：`USER`、`TEXT`、`TOOL_CALL`、`TOOL_RESULT`、`RESULT`、`STATE`、`ERROR`。
- 支持紧凑工具卡、业务动作按钮、执行报告摘要卡、历史会话抽屉和底部审批 sheet。
- 完整执行报告 Markdown 仅通过宿主传入的 `executionReportMarkdownLoader(reportRef)` 按需加载。
- 业务动作只 emit `action`，不在组件内硬编码业务路由。
- 审批只 emit `suspensionDecision`，不直接调用 Navigator resume API。
- 主题通过 `--nmc-*` CSS variables 覆盖。

## Security Boundary

浏览器端不得持有或显示：

- `task_scoped_token`
- admin/provisioning/runtime credential
- `adapterConfigJson`
- raw `manifestJson`
- Worker Gateway 内部 URL、auth header 或 token

`BusinessSuspensionDialogModel.displayFields` 必须是 BFF 或控制面清洗后的展示字段。组件只展示该模型中的 safe fields，并通过事件把 approve/reject 决策交回宿主。

## Upstream Vue 3 Example

```vue
<template>
  <NavigatorMobileChat
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

function handleAction(action: NavigatorAction) {
  if (action.type === 'OPEN_TMS_PAGE') {
    const payload = action.payload as { page?: string; waybillNo?: string } | undefined
    const target = payload?.page ?? payload?.waybillNo ?? ''
    window.location.href = `/mobile/tms/${encodeURIComponent(target)}`
  }
}

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
</script>

<style>
.tms-mobile-agent {
  --nmc-primary: #0f66d0;
  --nmc-bg: #f7f8fa;
  --nmc-surface: #ffffff;
}
</style>
```

## Implementation Scope

- `packages/navigator-chat-widget/src/components/NavigatorMobileChat.vue`
- `packages/navigator-chat-widget/src/components/NavigatorMobileChat.test.ts`
- `packages/navigator-chat-widget/src/dev/WidgetObservabilityDemo.vue`
- `packages/navigator-chat-widget/e2e/mobile-widget.spec.ts`
- `packages/navigator-chat-widget/playwright.config.ts`
- `packages/navigator-chat-widget/src/index.ts`
- `packages/navigator-chat-widget/src/types.ts`
- `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/02-frontend-component-quickstart.md`
- `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/36-mobile-chat-widget-bff-handoff-prompt.md`

## Acceptance Criteria

1. Upstream Vue 3 apps can import and mount `NavigatorMobileChat`.
2. Mobile layout keeps header, message list, safe-area padding and bottom input usable in WebView/H5.
3. OpenAPI message types render into user bubbles, assistant markdown, tool cards, state/error blocks and final result messages.
4. Tool calls/results are compact and expandable, with diagnostic details hidden unless debug/details mode is enabled.
5. Suspension approval UI is mobile bottom sheet/fullscreen style and emits approve/reject only.
6. Session history is mobile-friendly and uses the configured OpenAPI session APIs.
7. Execution report cards show digest first and load full Markdown only through host BFF callback.
8. Business action labels come from `structured_output.label/pageLabel`, and route execution is left to the host through `@action`.
9. CSS variables allow upstream theme alignment without forking the component.
10. Unit and mobile viewport tests cover key interactions.

## Progress Tracking

- [x] Requirement evaluated from issue #126.
- [x] `NavigatorMobileChat` implemented and exported.
- [x] Widget observability demo can switch desktop/mobile variants.
- [x] Mobile component unit tests added.
- [x] Mobile Playwright viewport spec added.
- [x] Upstream integration documentation updated.
- [x] Upstream BFF handoff prompt documented.

## Test Evidence

```powershell
pnpm --filter @foggy/navigator-chat-widget test
pnpm --filter @foggy/navigator-chat-widget exec vue-tsc --noEmit
pnpm --filter @foggy/navigator-chat-widget build
```

Mobile viewport E2E:

```powershell
pnpm --filter @foggy/navigator-chat-widget test:e2e -- --project=mobile-chromium e2e/mobile-widget.spec.ts
```
