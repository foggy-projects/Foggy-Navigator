---
type: bug
bug_source: user-report
version: 1.3.0-SNAPSHOT
ticket: BUG-040
severity: minor
status: open
reproduction_status: observed
test_strategy: e2e-test
automation_decision: required
---

# BUG-040 Concise mode shows raw tool events after session reopen

## Summary

用户刷新页面并重新打开会话后，在简洁模式下仍能看到 `invoke_business_skill`、`invoke_business_function`、`submit_skill_result` 等原始工具事件气泡。

这些事件适合出现在详情或调试视图中，不应在简洁模式作为普通消息展示。该问题会让普通用户误以为工具名是 Agent 回复内容，并放大内部执行细节。

## Evidence

- Context ID: `bctx_20260521_9f_9f6d8c4ba443485d9cd0f2b894ad3b59`
- Session path: `tools/langgraph-biz-worker/data/runtime/sessions/by-date/2026/05/21/9f/bctx_20260521_9f_9f6d8c4ba443485d9cd0f2b894ad3b59`
- Root frame: `frm_cee6d631753b`
- Tool-call log: `logs/skill-tool-calls/lgt_db307ad53ae04ea9.jsonl`
- Screenshot shows concise mode下可见以下内部事件：
  - `invoke_business_skill`
  - `invoke_business_function`
  - `submit_skill_result`
  - `Opening frame for skill: ...`
  - `Reusing frame for skill: ...`

BizWorker inspection:

- Raw tool events are correctly retained in frame/report/log for evidence and debugging.
- `runtime_context_memory.visible_messages` 只包含用户与助手的语义轮次，没有 raw tool-call messages。
- 因此该问题更可能发生在 Java/BFF 历史消息投影或前端 reopen 渲染过滤层，而不是 runtime memory 本身。

## Expected Behavior

简洁模式下：

- 展示用户消息与最终助手回复。
- 允许展示简化后的进度/状态卡片，但不直接展示 raw tool name。
- Raw tool events 只在详情/调试模式出现。
- live stream 与 reopen/history render 应使用一致的显示投影规则。

## Current Inference

优先排查方向：

- 会话历史 API 是否返回了未投影的内部 event stream。
- 前端重新打开会话时是否绕过了 live stream 使用的 display-mode filter。
- 简洁/详情/调试三种模式是否只作用于实时消息，不作用于历史消息。
- `Opening frame` / `Reusing frame` 这类 frame lifecycle 事件是否应统一归类为 debug-only。

## Regression Coverage Required

建议补一条浏览器 E2E 回归：

1. 创建会话并触发一次 skill + function 调用。
2. 等待最终回复完成。
3. 刷新页面或重新打开会话。
4. 切换到简洁模式。
5. 断言页面不包含 `invoke_business_skill`、`invoke_business_function`、`submit_skill_result` 等 raw tool event 文案。
6. 切换到详情/调试模式时，再验证内部事件按预期可见。

