---
type: bug
bug_source: user-report
version: 1.1.3-SNAPSHOT
ticket: GH-105
severity: major
status: ready-for-verification
reproduction_status: confirmed
test_strategy: unit-test
automation_decision: required
owner: navigator-chat-widget
---

# NavigatorChat Structured Action Render Bug

## 文档作用

- doc_type: bug
- intended_for: execution-agent | reviewer | upstream-business-owner
- purpose: 记录 GitHub issue #105 的修复范围、测试策略、执行结果与验收状态

## Background

TMS X3 通过 `@foggy/navigator-chat-widget` 接入 Navigator Chat。Skill 使用 `submit_skill_result` 返回 `summary` 与 `structured_output`，其中 `structured_output.type=OPEN_TMS_PAGE`，并携带 `routeName` 和 `query.aiDraftId`。

实际表现是聊天窗口只显示 summary，没有渲染可点击业务动作按钮。宿主侧已经支持 `@action` 并处理 `OPEN_TMS_PAGE`，所以问题定位在 widget 的 action 提取逻辑。

## Reproduction

输入消息形态：

```json
{
  "type": "TOOL_RESULT",
  "metadata": {
    "args": {
      "summary": "已生成开单草稿，可点击打开补齐并确认。",
      "structured_output": {
        "type": "OPEN_TMS_PAGE",
        "routeName": "OrderWorkbench",
        "query": { "aiDraftId": "afd_xxx" }
      }
    }
  }
}
```

修复前：`extractActions` 不递归 `args.structured_output`，返回空 action 列表。

修复后：提取为 `NavigatorAction`，payload 保留完整 `routeName/query`，由既有 `NavigatorChat.vue` action button 渲染并通过 `@action` 抛给宿主。

## Expected vs Actual

- expected: `submit_skill_result.args.structured_output` 被识别为可点击业务动作，`OPEN_TMS_PAGE.query` 不丢失。
- actual before fix: 只显示 summary，业务动作被忽略。

## Impact Scope

- affected module: `packages/navigator-chat-widget`
- affected users: 使用 Navigator Chat Widget 的上游业务系统，尤其是 TMS X3。
- non-goals: 不改后端消息协议；不通过 HTML、Markdown link 或 LLM 文本链接承载主业务动作。

## Code Inventory

- `packages/navigator-chat-widget/src/composables/useNavigatorChat.ts`
- `packages/navigator-chat-widget/src/composables/useNavigatorChat.actions.test.ts`
- `packages/navigator-chat-widget/package.json`
- `packages/navigator-chat-widget/vite.config.ts`
- `packages/navigator-chat-widget/tsconfig.json`
- `pnpm-lock.yaml`

## Fix Checklist

- [x] 覆盖 `message.args`、`metadata.args`、`result.structured_output`、`result.structuredOutput`。
- [x] 递归扫描 `structured_output`、`structuredOutput`、`output`、`payload`、`args`。
- [x] 支持字符串化 JSON 容器。
- [x] action payload 保留完整业务参数，避免丢失 `OPEN_TMS_PAGE.query`。
- [x] dedupe 纳入 payload，避免同类型同 label 但 query 不同的动作被误合并。
- [x] 工具流中提取到的业务 action 先暂存，合并到后续最终 assistant 消息展示。
- [x] 同一业务 action 跨 `TOOL_RESULT` 与 `RESULT` 重复出现时只展示一次。
- [x] `OrderWorkbench` 等已知路由生成明确按钮文案，例如 `打开开单工作台`。
- [x] 任务终态时兜底收口仍处于 running 的 skill frame，避免对话结束后仍显示执行中。
- [x] 补充自动化回归测试。

## Progress Tracking

### Development Progress

- status: completed
- summary: `extractActions` 已扩展候选源与递归容器；`collectActions` 增加 WeakSet 防循环；dedupe 使用稳定序列化 payload。业务 action 展示改为 pending 后合并到最终回复，按钮文案按页面/路由生成，终态时补齐 skill frame 收口。

### Testing Progress

| Command | Result | Notes |
| --- | --- | --- |
| `pnpm --filter @foggy/navigator-chat-widget test` | pass | 5 tests passed |
| `pnpm --filter @foggy/navigator-chat-widget build` | pass | Vite build + declaration emit passed |
| `pnpm --filter @foggy/chat build` | pass | Shared chat build passed |
| `pnpm --filter @foggy/navigator-frontend build` | pass | Main frontend build passed; only existing chunk-size warnings |
| `bash scripts/build-frontend.sh` | blocked | WSL-side `pnpm` could not find `node`; PowerShell pnpm equivalent commands passed |

### UX Follow-up

- feedback_source: 上游截图反馈
- issue: 功能可用，但完成后 skill frame 仍显示 `执行中`；`打开页面` 按钮出现在工具流区域且重复；按钮文案不说明打开的具体页面。
- resolution: 工具消息中的业务 action 不再立即渲染，改为暂存并合并到最终业务回复；去重同一 action；默认按钮文案根据 `routeName/pageName` 生成，`OrderWorkbench` 显示为 `打开开单工作台`；任务进入终态时将仍 running 的 skill frame 兜底置为完成或失败。

### Upstream Action Guidance

- 上游无需强制改造即可使用本次修复；Widget 已对 `OrderWorkbench` 等常见路由提供默认文案。
- 为保证新页面或业务定制页面的按钮文案准确，上游 skill 返回 `structured_output` 时建议显式携带 `label`，或携带 `pageLabel/pageName/routeLabel`。
- 推荐示例：`{ "type": "OPEN_TMS_PAGE", "label": "打开开单工作台", "routeName": "OrderWorkbench", "query": { "aiDraftId": "..." } }`。

### Experience Progress

- experience_status: unit-covered
- reason: 本次改动不新增 UI 组件，复用既有 `NavigatorChat.vue` action button 渲染与 `@action` 事件；关键体验风险在 action 提取、去重、展示归位与 frame 终态收口，已由单测覆盖。

## Acceptance Criteria

- [x] `metadata.args.structured_output` 可生成 `OPEN_TMS_PAGE` action。
- [x] `result.structuredOutput` 可生成 action。
- [x] 字符串化 action 容器可解析。
- [x] 多个同类型同 label 但 query 不同的 action 不被误去重。
- [x] `TOOL_RESULT` 中的业务 action 合并到最终 assistant 回复展示，不在工具流中单独出现。
- [x] 重复 action 只展示一次，且 `OrderWorkbench` 按钮显示为 `打开开单工作台`。
- [x] 任务完成后 running skill frame 自动收口为完成。
- [x] widget 包构建通过。

## Verification

当前状态为 `ready-for-verification`。建议上游 TMS 使用真实 `submit_skill_result` 返回一次开单草稿，确认按钮点击后宿主收到 `OPEN_TMS_PAGE` action 且 `payload.query.aiDraftId` 与 skill 输出一致。

## References

- GitHub issue: https://github.com/foggy-projects/Foggy-Navigator/issues/105
