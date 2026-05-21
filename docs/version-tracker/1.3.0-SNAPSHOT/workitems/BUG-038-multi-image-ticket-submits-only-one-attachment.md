---
type: bug
bug_source: user-report
version: 1.3.0-SNAPSHOT
ticket: BUG-038
severity: major
status: open
reproduction_status: partial
test_strategy: e2e-test
automation_decision: required
---

# BUG-038 Multi-image ticket creation submits only one attachment

## Summary

用户在 Navigator 会话中发送了一条带 2 张图片的消息，请求创建 TMS 平台反馈工单并检查图片数量。前端发送消息气泡中显示 2 个 `image.png` 附件，但最终创建并查询到的 TMS 工单只包含 1 张图片。

该问题需要独立跟踪。当前 BizWorker 本地证据显示：进入 `tms.ticket.createPlatformFeedback` 的函数调用 payload 时已经只有 1 个 `attachmentRefs`，因此不能先假设是 TMS 工单 Controller 丢失第二张图片。

## Evidence

- Context ID: `bctx_20260521_9f_9f6d8c4ba443485d9cd0f2b894ad3b59`
- Session path: `tools/langgraph-biz-worker/data/runtime/sessions/by-date/2026/05/21/9f/bctx_20260521_9f_9f6d8c4ba443485d9cd0f2b894ad3b59`
- Task ID: `lgt_db307ad53ae04ea9`
- Root frame: `frm_cee6d631753b`
- Child skill frames:
  - `frm_081ae3298cf4`
  - `frm_d920e88ea257`
- Function frames:
  - `fn_3c1b308c5b20`
  - `fn_ea0733adee97`
- Tool-call log: `logs/skill-tool-calls/lgt_db307ad53ae04ea9.jsonl`
- Created ticket from latest child result: `TKT202605210958117326C1D1B`

Observed attachment evidence in BizWorker artifacts:

- 全会话 frame/report/log 中只出现 1 个真实附件 ID:
  - `local/tenant-88800/org-88834/2026/05/21/d74215c009254f33a26ff177eaf00f61.png`
- `tms.ticket.createPlatformFeedback` 被调用 2 次，两次 `attachmentRefs` 数量均为 1。
- Root 调用 `tms-ticket-agent` 的 instruction 中也只传入了 1 个 `attachment_id`。
- 最新 frame report 结论为：工单 `TKT202605210958117326C1D1B` 当前包含 1 张图片，非 2 张。

## Expected Behavior

当用户消息携带 2 张图片附件时：

- Navigator 上游消息 transcript 应保留 2 个附件 metadata。
- BizWorker root prompt / skill handoff 应能看到 2 个附件引用。
- `invoke_business_function(tms.ticket.createPlatformFeedback)` payload 中 `attachmentRefs.length` 应为 2。
- TMS 工单创建后详情查询应返回 2 张图片。

## Current Inference

从当前 BizWorker 本地证据看，第二张图片在进入 BusinessFunction 调用前已经丢失。优先排查方向：

- 前端消息发送 payload 是否实际包含 2 个附件。
- Java/BFF 会话消息保存或转发给 BizWorker 时是否只保留了第一个附件。
- Navigator attachment normalization 是否按文件名、content type、临时 ID 或 display name 做了错误去重。
- Skill handoff 构造用户附件上下文时是否只取了第一个 attachment。

## Regression Coverage Required

建议补一条端到端回归：

1. 在会话中上传 2 张图片，允许同名 `image.png`。
2. 发送创建平台反馈工单请求。
3. 断言 Java transcript / send payload 中附件数量为 2。
4. 断言 BizWorker root 到 child skill 的附件上下文数量为 2。
5. 断言 `invoke_business_function` payload 中 `attachmentRefs.length == 2`。
6. 断言 TMS ticket detail 返回 2 张图片。

## Related Workitems

- `BUG-028-tms-ticket-agent-attachment-not-delivered.md`
- `BUG-036-tms-ticket-agent-attachment-missing-after-awaiting-user-resume.md`

