---
type: bug
bug_source: user-report
version: 1.3.0-SNAPSHOT
ticket: BUG-039
severity: major
status: open
reproduction_status: observed
test_strategy: e2e-test
automation_decision: required
---

# BUG-039 Chat message attachments are lost after session reopen

## Summary

用户在会话中发送的消息原本显示 2 张图片附件；刷新页面并重新打开该会话后，消息气泡上的图片附件丢失。

该问题影响会话回看、排障、审计和用户确认。BizWorker runtime memory 只保留 LLM 运行时所需的可见语义消息，不应作为 UI 完整 transcript 的唯一来源；重新打开会话时，Java/BFF 保存的完整 transcript 应恢复附件 metadata 并正确渲染附件卡片。

## Evidence

- Context ID: `bctx_20260521_9f_9f6d8c4ba443485d9cd0f2b894ad3b59`
- Session path: `tools/langgraph-biz-worker/data/runtime/sessions/by-date/2026/05/21/9f/bctx_20260521_9f_9f6d8c4ba443485d9cd0f2b894ad3b59`
- Root frame: `frm_cee6d631753b`
- User screenshot before/around send shows 2 attachment chips under the user message.
- Reopen screenshot shows the message text remains, but attachment chips are not restored.

BizWorker inspection:

- Root frame `input` did not contain a top-level `attachments` / `attachmentRefs` list.
- `runtime_context_memory.visible_messages` records semantic user/assistant turns, but not the UI attachment chip list.
- 全会话 BizWorker artifacts 只出现 1 个真实附件 ID，这与 BUG-038 的 BusinessFunction 入参丢图问题有关，但本 BUG 单独跟踪“重新打开会话后消息附件展示丢失”。

## Expected Behavior

刷新页面或重新打开会话后：

- 用户历史消息应恢复原始附件 metadata。
- 简洁模式下仍应显示用户消息上的附件卡片。
- 附件展示不应依赖 BizWorker runtime memory 的 bounded visible window。

## Current Inference

优先排查方向：

- Java/BFF 会话 transcript 持久化是否保存了附件列表。
- 会话历史加载 API 是否返回附件 metadata。
- 前端历史消息渲染路径是否与实时发送路径使用了不同的数据结构。
- 是否存在只在 live SSE/send state 中保留附件、刷新后从历史 API 丢失附件的路径差异。

## Regression Coverage Required

建议补一条浏览器 E2E 回归：

1. 新建会话。
2. 发送一条携带 2 张图片的用户消息。
3. 等待消息进入历史。
4. 刷新页面或重新进入该会话。
5. 断言该用户消息仍渲染 2 个附件卡片，且附件名称、大小、类型可用。

