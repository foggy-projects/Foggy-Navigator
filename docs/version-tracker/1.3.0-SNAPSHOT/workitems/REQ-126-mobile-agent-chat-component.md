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
- Status: uni-app plugin implemented locally, build and tests passed
- Owner: `@foggy/mobile` / `@foggy/navigator-chat-widget`
- Date: 2026-05-21

## Background

上游 Vue 3 / Vite 移动端 H5 或 App WebView 需要一个可直接挂载的 Agent 对话组件。现有 `NavigatorChat` 偏桌面布局，`packages/foggy-mobile` 是 uni-app App 工程，不能作为上游 Vue 组件库直接发布。

本次新增 `NavigatorMobileChat`，复用 `useNavigatorChat` 与现有 OpenAPI 轮询/session 能力，只负责浏览器端展示与事件上抛。BFF 仍负责身份、凭证、敏感字段清洗和业务跳转执行。

2026-05-21 issue comment 更新：TMS v3.2.1 APP 主线明确为 uni-app + Vue 3 + TypeScript + Pinia，Vant/普通 Vue mobile web 只作为 H5 fallback。因此 REQ-126 需要新增 uni-app 原生插件交付形态：`packages/foggy-mobile/src/uni_modules/foggy-navigator-chat`。

## Accepted V1 Contract: H5 fallback

`NavigatorMobileChat` 从 `@foggy/navigator-chat-widget` 导出，面向移动端浏览器/WebView：

- 全高移动面板，默认 `100dvh`，包含安全区 top/bottom 与底部输入区。
- 渲染 OpenAPI 消息类型：`USER`、`TEXT`、`TOOL_CALL`、`TOOL_RESULT`、`RESULT`、`STATE`、`ERROR`。
- 支持紧凑工具卡、业务动作按钮、执行报告摘要卡、历史会话抽屉和底部审批 sheet。
- 完整执行报告 Markdown 仅通过宿主传入的 `executionReportMarkdownLoader(reportRef)` 按需加载。
- 业务动作只 emit `action`，不在组件内硬编码业务路由。
- 审批只 emit `suspensionDecision`，不直接调用 Navigator resume API。
- 主题通过 `--nmc-*` CSS variables 覆盖。

## Accepted V2 Contract: uni-app primary APP component

`foggy-navigator-chat` 从 `packages/foggy-mobile/src/uni_modules/foggy-navigator-chat` 提供，面向 uni-app Vue 3 APP/H5：

- 插件目录符合 uni_modules 形态，组件名为 `<foggy-navigator-chat />`。
- UI 使用 uni-app primitives：`view`、`scroll-view`、`textarea`、`button`、`rich-text`。
- 默认客户端使用 `uni.request` 访问上游 BFF；也支持宿主通过 `client` prop 注入 BFF callbacks。
- 不依赖 `window`、`document`、DOM scroll API、browser `File`、`fetch` 或 `vue-router`。
- 渲染 OpenAPI 消息类型：`USER`、`TEXT`、`TOOL_CALL`、`TOOL_RESULT`、`RESULT`、`STATE`、`ERROR`。
- 支持历史会话/继续 `contextId`、弱网 polling retry、长消息换行、底部输入安全区和软键盘 `adjust-position`。
- 支持 suspension approve/reject sheet，只 emit `suspensionDecision`。
- 支持执行报告 digest-first 展示，完整 Markdown 只通过 BFF loader 按需加载。
- 支持 `structured_output.label/pageLabel` business action，并只 emit `action` 给上游用 `uni.navigateTo` / `uni.switchTab` 处理。

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

## Upstream uni-app Example

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

<script setup lang="ts">
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
  agentId: 'tms.mobile.agent',
  pollInterval: 4000,
  showToolCalls: true,
  showToolResults: true,
  executionReportMarkdownLoader: loadExecutionReportMarkdown,
}

async function loadExecutionReportMarkdown(reportRef: string) {
  const result = await uni.request({
    url: `/bff/navigator/execution-reports/${encodeURIComponent(reportRef)}/markdown`,
    method: 'GET',
  })
  const body = result.data as { code: number; msg?: string; data: { markdown: string } }
  if (body.code !== 0) throw new Error(body.msg || '执行报告读取失败')
  return body.data
}

function handleAction(action: FoggyNavigatorAction) {
  if (action.type === 'OPEN_TMS_PAGE') {
    const payload = action.payload as { id?: string } | undefined
    uni.navigateTo({ url: `/pages/tms/detail?id=${encodeURIComponent(String(payload?.id ?? ''))}` })
  }
}

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
</script>
```

## Implementation Scope

- `packages/navigator-chat-widget/src/components/NavigatorMobileChat.vue`
- `packages/navigator-chat-widget/src/components/NavigatorMobileChat.test.ts`
- `packages/navigator-chat-widget/src/dev/WidgetObservabilityDemo.vue`
- `packages/navigator-chat-widget/e2e/mobile-widget.spec.ts`
- `packages/navigator-chat-widget/playwright.config.ts`
- `packages/navigator-chat-widget/src/index.ts`
- `packages/navigator-chat-widget/src/types.ts`
- `packages/foggy-mobile/src/uni_modules/foggy-navigator-chat/package.json`
- `packages/foggy-mobile/src/uni_modules/foggy-navigator-chat/index.ts`
- `packages/foggy-mobile/src/uni_modules/foggy-navigator-chat/lib/client.ts`
- `packages/foggy-mobile/src/uni_modules/foggy-navigator-chat/lib/normalizer.ts`
- `packages/foggy-mobile/src/uni_modules/foggy-navigator-chat/lib/useFoggyNavigatorChat.ts`
- `packages/foggy-mobile/src/uni_modules/foggy-navigator-chat/lib/types.ts`
- `packages/foggy-mobile/src/uni_modules/foggy-navigator-chat/components/foggy-navigator-chat/foggy-navigator-chat.vue`
- `packages/foggy-mobile/src/uni_modules/foggy-navigator-chat/__tests__/*.test.ts`
- `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/02-frontend-component-quickstart.md`
- `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/36-mobile-chat-widget-bff-handoff-prompt.md`
- `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/37-uni-app-navigator-chat-bff-handoff-prompt.md`

## Acceptance Criteria

1. Upstream uni-app Vue 3 apps can copy/import `foggy-navigator-chat` as a uni_modules plugin.
2. Upstream ordinary Vue 3 H5 apps can still import and mount `NavigatorMobileChat` as fallback.
3. Mobile layout keeps header, message list, safe-area padding and bottom input usable in WebView/H5.
4. uni-app component uses uni primitives and avoids browser-only DOM assumptions.
5. OpenAPI message types render into user bubbles, assistant markdown, tool cards, state/error blocks and final result messages.
6. Tool calls/results are compact and expandable, with diagnostic details hidden unless debug/details mode is enabled.
7. Suspension approval UI is mobile bottom sheet/fullscreen style and emits approve/reject only.
8. Session history is mobile-friendly and uses the configured OpenAPI session APIs.
9. Execution report cards show digest first and load full Markdown only through host BFF callback.
10. Business action labels come from `structured_output.label/pageLabel`, and route execution is left to the host through `@action`.
11. Unit tests cover normalizer, uni BFF client, polling/session behavior and weak-network retry.

## Progress Tracking

- [x] Requirement evaluated from issue #126.
- [x] `NavigatorMobileChat` implemented and exported.
- [x] Widget observability demo can switch desktop/mobile variants.
- [x] Mobile component unit tests added.
- [x] Mobile Playwright viewport spec added.
- [x] Upstream integration documentation updated.
- [x] Upstream BFF handoff prompt documented.
- [x] 2026-05-21 issue comment evaluated: uni-app primary component requested.
- [x] `foggy-navigator-chat` uni_modules plugin implemented locally.
- [x] uni-app BFF client/composable/normalizer tests added.
- [x] uni-app BFF handoff prompt documented.

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

uni-app plugin targeted tests:

```powershell
pnpm --dir packages/foggy-mobile test -- src/uni_modules/foggy-navigator-chat/__tests__
```

uni-app plugin build/full test verification:

```powershell
pnpm --dir packages/foggy-mobile build:h5
pnpm --dir packages/foggy-mobile test
```
