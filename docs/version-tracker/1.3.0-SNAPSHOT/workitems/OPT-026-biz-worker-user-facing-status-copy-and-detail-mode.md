# OPT-026 BizWorker User-Facing Status Copy And Detail Mode

## 文档作用

- doc_type: optimization
- intended_for: execution-agent, reviewer, product-owner
- purpose: 记录 TMS 业务助手中 BizWorker 执行状态文案、技能/工具展示名与详情层级的体验优化需求。

## Status

- Version: `1.3.0-SNAPSHOT`
- Source: user feedback with screenshot, 2026-05-18
- Type: optimization / frontend UX / execution observability
- Priority: medium
- Status: implemented locally, targeted tests and builds passed
- Owner: Navigator frontend + BizWorker runtime status mapping

## Background

当前 TMS 业务助手在任务启动和执行过程中会直接暴露底层实现名，例如：

- `Connecting to LangGraph worker...`
- `技能 system.root`
- `工具 submit_skill_result`
- `Frame 原始 JSON`

这些信息对开发调试有价值，但对业务用户不友好。用户看到的是技术栈、内部 skill id 和工具函数名，而不是“系统正在做什么”。在业务助手场景里，默认信息应该解释任务阶段；底层实现细节应放在可展开详情或调试视图中。

## Problem Statement

1. 连接态文案没有本地化，也暴露 `LangGraph worker`。
2. root skill 名 `system.root` 被直接展示，用户无法理解其业务含义。
3. `submit_skill_result` 这类工具名展示为执行步骤，偏工程实现，不像用户任务进度。
4. 当前 debug 信息和用户可见信息边界不够清晰：如果完全隐藏会影响排障，如果默认展示又损害体验。

## Target Outcome

默认聊天界面展示业务友好的状态文案；执行详情保留可读的阶段信息；debug 模式继续保留原始技术标识、耗时和 JSON。

## Proposed Copy Mapping

| Raw state / id | Default display | Detail display | Debug display |
| --- | --- | --- | --- |
| `Connecting to LangGraph worker...` | `正在连接至道同。` | `正在连接至道同业务执行服务。` | `Connecting to LangGraph worker...` |
| `技能 system.root` | `开始处理任务` | `任务处理主流程` | `技能 system.root` |
| `工具 submit_skill_result` running | `正在准备结果` | `正在整理本次处理结果` | `工具 submit_skill_result` |
| `工具 submit_skill_result` success | `处理完成` | `结果已准备完成` | `工具 submit_skill_result 成功` |
| `Frame 原始 JSON` | hidden | hidden by default, available through debug toggle | visible in debug |

## Display Mode Recommendation

建议采用三层展示，而不是只有普通模式和 debug 模式：

1. `简洁模式`（默认）
   - 面向业务用户。
   - 只展示当前阶段和最终答复。
   - 不展示 skill id、tool name、frame JSON、worker 技术栈名称。
2. `执行详情模式`
   - 面向希望知道处理进度但不需要原始 JSON 的用户。
   - 展示友好的时间线、耗时、成功/失败状态、可读步骤名称。
   - 不展示 raw JSON；内部 id 可通过 tooltip 或复制诊断信息间接获取。
3. `调试模式`
   - 面向开发、排障和验收。
   - 保留 `system.root`、`submit_skill_result`、raw frame JSON、耗时、tool args/result 摘要。
   - 默认折叠高噪声内容。

这样可以避免把 debug 模式承担成唯一的“更多信息”入口，也避免业务用户被内部实现名打断。

## Additional UX Optimizations

1. 状态文案按阶段变化，而不是静态展示工具名：
   - 连接中：`正在连接至道同。`
   - 分析中：`正在理解你的需求。`
   - 调用业务能力：`正在调用业务能力。`
   - 等待授权：`需要你确认后继续。`
   - 生成结果：`正在准备结果。`
   - 完成：`处理完成。`
2. 技能和工具展示应由后端或前端映射表提供 `displayName` / `displayDescription`，不要直接使用内部 id。
3. 失败态需要用户可读说明：
   - 默认：`处理遇到问题，已停止。`
   - 详情：显示失败阶段和简短原因。
   - debug：显示错误栈、tool result、frame id。
4. 耗时信息默认弱化：
   - 简洁模式不展示 `0.0s` 这类细节。
   - 执行详情模式展示耗时。
   - debug 模式展示完整 timing。
5. “查看执行报告”按钮只在有 report 可读时显示；`RUNNING` 应换成中文状态，例如 `生成中`、`已生成`、`生成失败`。
6. 附件卡片建议统一本地化：
   - `image.png 12.6 KB` 保留文件名和大小。
   - 附件关系文案使用 `你上传了 1 个图片附件` 或 `已收到图片附件`。

## Scope And Ownership

- Frontend owns display mode switch, localized labels, collapsed sections and user-facing timeline layout.
- BizWorker/runtime owns stable status codes or metadata if frontend cannot reliably infer stages from raw ids.
- Existing debug information must remain accessible for developers and test evidence.
- This optimization should not change task execution semantics, frame lifecycle, tool invocation, approval flow or report generation.

## Acceptance Criteria

1. Default UI no longer displays `LangGraph worker`, `system.root`, `submit_skill_result` or raw frame JSON.
2. Connection text is localized to `正在连接至道同。` or the final approved Chinese copy.
3. Root skill is displayed as `开始处理任务` or the final approved friendly stage name.
4. `submit_skill_result` running/success states are displayed as friendly result-preparation/completion states.
5. Execution details mode shows friendly timeline and timing without raw JSON.
6. Debug mode preserves raw ids, raw JSON access and diagnostic timing.
7. Existing task execution, approval resume and report generation behavior remains unchanged.
8. UI experience is validated with at least one screenshot or Playwright walkthrough covering default mode and debug/details expansion.

## Constraints / Non-Goals

- Do not remove raw diagnostic data from runtime storage.
- Do not rename actual skill ids or tool ids as part of this UX-only optimization.
- Do not make `道同` hardcoded in lower-level runtime if the assistant/app name should be tenant configurable.
- Do not require users to enable debug mode just to see a readable progress timeline.

## Progress Tracking

### Development Progress

- [x] Optimization recorded from screenshot feedback.
- [x] Confirmed frontend-first boundary: backend protocol stays unchanged unless current event payload is insufficient or wrong.
- [x] Identified widget rendering chain: `useNavigatorChat.ts`, `NavigatorChat.vue`, `SkillFrameBlockView.vue`.
- [x] Added `details` display mode alongside `business` and `debug`.
- [x] Added frontend display mapping for `system.root`, `submit_skill_result`, `invoke_business_function`, and LangGraph connection text.
- [x] Added details/debug visibility rules: non-debug modes hide raw args/result/trace/frame JSON; debug keeps raw diagnostics.
- [x] Added configurable component mode switcher with `showDisplayModeSwitcher` and `displayModeOptions`.
- [x] Verified enabled switcher can move between `business` / `details` / `debug` against the same backend task payload without protocol changes.
- [x] Fixed real feedback where `Connecting to LangGraph worker...` could arrive as a normal `TEXT/RESULT` assistant message and bypass the earlier state/progress mapping.

Touched code paths:

- `packages/navigator-chat-widget/src/types.ts`
- `packages/navigator-chat-widget/src/composables/useNavigatorChat.ts`
- `packages/navigator-chat-widget/src/components/NavigatorChat.vue`
- `packages/navigator-chat-widget/src/components/SkillFrameBlockView.vue`
- `packages/navigator-chat-widget/src/composables/useNavigatorChat.ux.test.ts`

### Testing Progress

- [x] `pnpm --filter @foggy/navigator-chat-widget test`
  - Result: pass, `14 passed`.
- [x] `pnpm --filter @foggy/navigator-chat-widget build`
  - Result: pass.
- [x] `pnpm build:frontend`
  - Result: pass.
  - Notes: Vite emitted existing chunk size / dynamic import warnings.
- [ ] `bash scripts/build-frontend.sh`
  - Result: not runnable in current bash environment because `/mnt/c/Users/oldse/AppData/Roaming/npm/pnpm` could not find `node`.
- [ ] Playwright/manual UI verification not run yet.

### Experience Progress

- [x] Component-level details mode test verifies friendly labels and hidden raw diagnostic identifiers.
- [ ] Default mode screenshot verified.
- [ ] Execution details mode screenshot verified.
- [ ] Debug mode screenshot verified.

## Execution Check-in

- Completed work: implemented frontend-only status copy mapping and display-mode split for Navigator Chat Widget.
- Protocol decision: backend/Worker protocol was not changed; current payload provides enough `skillId`, `toolName`, `status`, frame id, report ref and digest data for this UI layer.
- Component switcher:
  - Disabled by default.
  - Enable with `showDisplayModeSwitcher: true`.
  - Limit visible modes with `displayModeOptions`, for example `['business', 'details']`.
  - When enabled, the component keeps runtime/tool event data in memory and filters it by current mode, so users can switch display modes without asking the backend for another protocol shape.
- User-facing mappings implemented:
  - `Connecting to LangGraph worker...` style state text -> `正在连接至道同。`
  - `system.root` -> `开始处理任务`
  - `submit_skill_result` running/success/failed -> `正在准备结果` / `处理完成` / `结果准备失败`
  - `invoke_business_function` -> friendly business capability call labels
- Details/debug split:
  - `business`: default concise mode, no automatic runtime event expansion.
  - `details`: friendly timeline and durations, no raw args/result/trace/frame JSON.
  - `debug`: raw ids and diagnostic blocks remain visible.
- Residual risk: live TMS page should still be checked visually because current verification is component/unit/build level, not browser screenshot evidence.

## Open Questions

1. `正在连接至道同。` 是否应固定为平台品牌文案，还是按当前业务助手名称动态显示？
2. 执行详情模式的入口命名用 `执行详情`、`处理详情` 还是 `过程详情`？
3. debug 模式是否仅管理员/开发者可见，还是普通用户也可以开启？
