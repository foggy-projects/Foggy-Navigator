---
type: bug
bug_source: user-report
version: 1.3.0-SNAPSHOT
ticket: BUG-036
severity: major
status: open
reproduction_status: confirmed-from-local-logs
test_strategy: e2e-test
automation_decision: required
owner: upstream-integration + biz-worker-runtime + navigator-business-agent
related: BUG-028, BUG-034, REQ-030, OPT-032
---

# BUG-036: TMS Ticket Attachment Follow-up False Negative After Detail Query Failure

## Background

2026-05-20 TMS 业务助手真实链路 smoke 中，用户先要求生成工单，系统进入等待用户补充信息状态；随后用户带图片附件回复，希望创建“系统优化建议”类平台反馈工单。

最初从用户可见结果看，工单最终创建成功，但后续追问：

```text
工单中有我刚才提交的图片吗
```

系统回答该工单不包含任何附件。

2026-05-20 后续重启后复测确认：创建工单时附件其实已经随 `tms.ticket.createPlatformFeedback` 传入，并且业务函数返回的 `outputJson.data.attachmentRefs` 也包含同一张 `image.png`。当前问题不再是“附件未提交”，而是第三轮追问时 `tms.ticket.detail` / `tms.ticket.events` 连续失败后，child/root 在证据不足的情况下错误断言“无附件”。

本问题与已关闭的 BUG-028 不完全相同。BUG-028 已证明普通 TMS BFF + Navigator + Worker + child skill + TMS ticket creation 路径可以保留附件 URL/ref。本次问题发生在 `WAITING_FOR_USER_INPUT` 后的用户回复恢复路径以及 completed frame 后的追问查询路径。

## Reproduction

当前复现来自手工真实链路 smoke，尚未拆成稳定自动化用例。

1. 清理 `tools/langgraph-biz-worker/data`。
2. 重启相关服务。
3. 在 TMS 业务助手发送：

```text
帮我生成一个工单
```

4. 等待系统询问工单类型、标题和详细描述。
5. 用户发送带图片附件的回复：

```text
帮我提交一个系统优化建议，图中的历史会话，标题不要用未命名会话
```

6. 如果中途 root LLM 出错，点击 `继续`。
7. 工单创建完成后追问：

```text
工单中有我刚才提交的图片吗
```

实际结果显示创建响应包含附件，但追问查询因业务能力失败而误报不包含附件。

## Evidence

本轮 smoke 关键对象：

- conversation/context: `20260520-566e`
- session: `c82dfb5c-0a11-43a5-9500-39046ed9213a`
- root frame: `frm_c171a7768c96`
- first child frame: `frm_5a29f1a1daa3`
- duplicate child frame: `frm_b9fc9af50fab`
- first ticket: `TKT20260520115128081580553`
- duplicate ticket: `TKT20260520115656625A7A498`
- follow-up query child: `frm_48490a60d56b`

已确认点：

1. 首个 child frame 能在等待用户补充后恢复，并且能看到用户带附件的回复上下文。
2. BUG-034 修复前，首个 child 完成后 root 继续运行，调用 `analyze_attachment`，又第二次 `invoke_business_skill` 创建 duplicate child。
3. duplicate child 创建了第二张工单，最终用户追问时系统查询的是 `TKT20260520115656625A7A498`。
4. 查询结果显示该工单不包含图片附件。

当前不确定点：

- 第一张工单 `TKT20260520115128081580553` 是否包含附件。
- 附件是在 root-to-child 恢复路径、child-to-business-function payload、TMS ticket API adapter，还是 duplicate child 的二次编排路径中丢失。
- BUG-034 direct-return 修复后，如果不再创建 duplicate child，图片是否会随第一张工单正确提交。

### Smoke Evidence 2026-05-20: `20260520-9ba4`

重启相关服务后再次执行真实链路 smoke，没有触发 root LLM 异常，也没有复现 duplicate ticket。

关键对象：

- conversation/context: `20260520-9ba4`
- root frame: `frm_dda16c69f757`
- create turn task: `lgt_e002f7bb2c58440d`
- create child frame: `frm_2867124dbf54`
- create function frame: `fn_a97172cac7bd`
- follow-up query turn task: `lgt_533c79937a5a4d5d`
- follow-up child frame: `frm_499b760265f7`
- ticket: `TKT2026052012510533115FB51`

创建工单链路确认 PASS：

1. `tms.ticket.createPlatformFeedback` request 含 `attachmentRefs[0]`。
2. 附件字段包含 `attachmentName=image.png`、`attachmentType=IMAGE`、`contentType=image/png`、`sizeBytes=19397`、`width=330`、`height=706`。
3. `attachmentUrl` 为 `http://192.168.31.119:12580/x3-web/tenant/attachment/local/88800/88834/3e996f291b6c4f76ac976b87ba7853ab.png`。
4. `tms.ticket.createPlatformFeedback` response `outputJson.data.attachmentRefs` 同样包含该图片。

第三轮追问异常：

1. root 调用 `tms-ticket-agent`，instruction 为查询工单 `TKT2026052012510533115FB51` 的详情并确认是否包含图片。
2. child 依次调用：
   - `tms.ticket.detail` -> HTTP 400 / business code `600` / `B600`
   - `tms.ticket.detail` -> HTTP 400 / business code `600` / `B600`
   - `tms.ticket.events` -> HTTP 400 / business code `600` / `B600`
3. child 最终 `submit_skill_result`：

```json
{
  "ticket_id": "TKT2026052012510533115FB51",
  "has_attachments": false,
  "query_status": "error",
  "error_message": "系统异常，请稍后重试 (B600)"
}
```

4. root 最终回答 `工单 TKT2026052012510533115FB51 不包含图片附件。`

结论：当前证据证明附件创建链路正常；错误发生在 follow-up 查询失败后的结果判定。查询实时数据失败时，LLM/skill 不应把“未能查询到”转换成“确认没有附件”。如果需要回答，应基于上一轮创建函数返回中的 `attachmentRefs`，或者明确告知实时查询失败但创建响应中记录了 `image.png`。

### TMS Confirmation 2026-05-20

TMS 方人工确认数据库中存在该工单的图片附件值。因此当前问题拆分为两层：

1. TMS 查询链路问题：`tms.ticket.detail` / `tms.ticket.events` 对 `TKT2026052012510533115FB51` 查询失败或未返回附件字段，需要 TMS 协助排查接口、adapter 或查询 DTO 映射。
2. BizWorker 回答策略问题：业务函数失败时，agent 不能假设业务事实，必须向用户报告查询失败或基于已有创建响应证据回答。

当前 Worker 调用姿势从本机日志看是正常的：`tms.ticket.detail` 与 `tms.ticket.events` 均只传入公开工单号字段：

```json
{
  "ticketId": "TKT2026052012510533115FB51"
}
```

失败返回为 TMS 业务侧错误：

```text
HTTP 400: {"code":600,"exCode":"B600","msg":"Rest adapter execution failed with business code 600 (B600): 系统异常，请稍后重试"}
```

需要把该错误交给 TMS 侧确认：接口是否支持平台反馈工单详情查询、`ticketId` 是否应使用 ticketNo 或内部 id、附件字段是否在查询 DTO/adapter 输出中遗漏，以及 B600 的真实异常栈。

## Expected Vs Actual

Expected:

- 对于“提交工单并附图”类请求，图片应默认作为原始附件引用随工单业务函数 payload 传递。
- 不需要用户显式要求解析图片内容，也不需要默认调用 `analyze_attachment`。
- `WAITING_FOR_USER_INPUT` 恢复后的同一个 child frame 应继承用户回复中的附件上下文，并在创建工单时保留 attachment ref / URL。
- 工单创建完成后，查询该工单应能看到对应附件。
- 如果实时查询能力失败，系统应返回“不确定/查询失败”，不能断言“无附件”。
- 如果上一轮创建函数输出已经包含 `attachmentRefs`，后续追问可以引用该创建证据回答。

Actual:

- 用户消息 UI 中存在图片附件。
- 工单创建成功。
- 创建函数 request/response 均包含图片附件。
- 后续查询该工单时，详情和事件业务能力连续失败。
- child/root 在查询失败情况下仍回答不包含图片附件。

## Impact Scope

- 用户会被错误告知图片没有提交，实际创建响应中已经包含图片附件。
- 平台反馈、BUG 报告、物流异常等依赖截图或现场照片的工单会出现错误追问结果。
- 查询能力临时失败时，LLM 可能把未知状态误报成确定结论。

## Test Strategy

需要 E2E 级别回归，优先使用脚本化 LLM/tool response 缩小变量，再补真实链路 smoke。

推荐测试分层：

1. Python scripted E2E：构造 root -> child `WAITING_FOR_USER_INPUT` -> 用户带图片回复 -> 同一 child resume -> business function create ticket，断言业务函数 payload 含 `attachmentRefs`。
2. Python scripted E2E：覆盖 child 完成后 direct-return，不再由 root 二次调用 `analyze_attachment` 或再次 `invoke_business_skill`。
3. Python scripted E2E：创建响应含 `attachmentRefs`，后续用户追问附件，实时 detail/events 工具返回失败时，最终回答不能断言 `has_attachments=false`。
4. Java relay / OpenAPI boundary：复核用户第二轮消息的 top-level `attachments` 是否进入同一个 `contextId` 的 worker request。
5. 真实 TMS smoke：用真实图片创建工单后，通过 TMS 查询接口确认附件存在；若查询接口失败，回答应明确失败而非误报无附件。

## Code Inventory

- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/attachment_context.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_child_recovery.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`
- `tools/langgraph-biz-worker/tests/test_e2e_scripted_tool_call_streaming.py`
- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/adapter/LanggraphWorkerInnerA2aAgent.java`
- TMS BFF ask endpoint / attachment upload hook
- TMS ticket business function adapter for `attachmentRefs`

## Fix Checklist

- [x] 在 BUG-034 direct-return 修复后重新执行真实链路 smoke，确认未再创建 duplicate ticket。
- [x] 检查创建工单业务函数 payload，确认包含 `attachmentRefs`。
- [x] 检查创建工单业务函数 response，确认返回 `attachmentRefs`。
- [ ] 检查用户第二轮带图片回复的 worker request / jsonl，确认 top-level `attachments` 是否存在。
- [ ] 检查 resumed child frame 的 LLM prompt / runtime context，确认附件摘要是否仍可见。
- [x] 检查 child 调用 TMS 创建工单业务函数的 payload，确认包含 `attachmentRefs`。
- [ ] 修复或约束 follow-up 查询失败后的推理：不能把 B600 查询失败解释为无附件。
- [ ] 让后续追问在必要时能读取上一轮创建函数 output / frame report 中的 `attachmentRefs`，或在无法读取时明确证据不足。
- [ ] 将 `tms.ticket.detail` / `tms.ticket.events` 的 B600 失败和附件查询缺失证据提交给 TMS 方协助排查。
- [ ] 增加 scripted E2E 回归，覆盖 WAITING_USER 恢复路径中附件随工单直传。
- [ ] 增加 scripted E2E 回归，覆盖 detail/events 查询失败时不误报 `has_attachments=false`。

## Verification

2026-05-20 当前验证：

- 真实创建工单链路中，`tms.ticket.createPlatformFeedback` request 和 response 均包含 `image.png` 附件。
- 未复现 BUG-034 的 duplicate ticket。
- follow-up 查询链路复现 `tms.ticket.detail` / `tms.ticket.events` 连续 B600 后误报无附件。

最小验收标准：

- 同一 `contextId` 中，用户第二轮带图片回复后，同一个 awaiting child frame 恢复并创建工单。
- 创建工单业务函数 payload 包含原始附件 ref / URL。
- 最终业务系统工单包含该图片附件。
- root 不再因为 child final result 二次编排而创建第二张工单。
- 查询接口失败时，系统不输出“确认无附件”。
- 如果上一轮创建响应中有 `attachmentRefs`，追问时能基于该证据回答“创建响应中包含图片附件”。

## References

- `BUG-028-tms-ticket-agent-attachment-not-delivered.md`
- `BUG-034-biz-worker-child-result-parent-synthesis-degradation.md`
- `REQ-030-biz-worker-on-demand-attachment-analysis-and-vision-model-config.md`
- `OPT-032-attachment-preprocessing-governance-follow-up.md`
