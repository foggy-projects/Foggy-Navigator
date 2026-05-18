---
type: bug
bug_source: user-report
version: 1.3.0-SNAPSHOT
ticket: BUG-028
severity: major
status: in-progress
reproduction_status: partial
test_strategy: e2e-test
automation_decision: required
owner: upstream-integration + biz-worker-runtime
---

# BUG-028: TMS Ticket Agent Attachment Not Delivered

## Background

2026-05-18 TMS 业务助手联调中，用户在第二条消息中发送“你可以帮我提个工单吗”，并附带图片 `image.png`。聊天 UI 已展示附件卡片，但 Worker 侧调查显示，进入 `tms-ticket-agent` 子技能时只有文本 instruction，没有附件 URL、附件摘要或附件引用。

已有附件合同见 `1.1.3-SNAPSHOT` 上游接入文档：TMS 前端 upload-on-submit，TMS BFF ask 携带顶层 `attachments`，Navigator 透传到 Worker，Worker 至少应持有附件元数据并可注入脱敏摘要。

## Reproduction

1. TMS 业务助手中选择或粘贴图片附件。
2. 发送“你可以帮我提个工单吗”。
3. 检查 Navigator/Worker 任务数据与 skill tool-call 日志。

Current reproduction is partial: the child boundary absence is confirmed, but current日志还不能区分附件是在 TMS BFF ask 前未上传/未转发，还是在 Navigator OpenAPI、Java relay、Python query state、root-to-child handoff 中途丢失。

## Evidence

- UI 截图显示用户消息包含附件卡片 `image.png 32.7 KB`。
- 超时任务为 `lgt_33fddb45f23c4e21`。
- `data/logs/skill-tool-calls/lgt_33fddb45f23c4e21.jsonl` 的 `invoke_business_skill` request 参数只有：

```json
{
  "skill_id": "tms-ticket-agent",
  "instruction": "你可以帮我提个工单吗"
}
```

- `tms-ticket-agent` child frame 没有可见的 attachment URL/ref/summary。
- 针对该任务数据搜索 `image.png`、`32.7`、`attachment` 时，没有发现真实附件元数据；仅发现 skill 文档中与 `tms-attachment-agent` 相关的能力描述。

## Expected Vs Actual

Expected:

- 若 TMS upload hook 成功返回附件元数据，ask payload 应带顶层 `attachments`。
- Navigator OpenAPI、A2A metadata、LangGraph Java Worker client、Python `QueryRequest`、root state 均应保留附件数组。
- root skill 调用 `tms-ticket-agent` 时，子技能应继承本轮附件上下文，至少能看到脱敏附件摘要和可审计 attachment ref。
- 如果当前策略要求附件先由 `tms-attachment-agent` 处理，root skill 应显式路由到该技能，或把附件上下文传给 `tms-ticket-agent` 后由其按规则请求附件处理。

Actual:

- UI 有附件卡片。
- `tms-ticket-agent` 子技能边界没有附件信息。
- 当前无法通过 Worker 数据确认上游 ask body 是否曾包含附件。

## 2026-05-18 Worker Runtime Update

已确认 Worker 侧存在独立缺口：`run_skill` 可把 `attachments` 放入 child `runtime_context`，但 `LlmSkillAgent._build_user_prompt` 只渲染 `Skill input` 和可见 root context，没有把 `runtime_context.attachments` 注入 child user prompt。因此当 root 的 `invoke_business_skill` tool-call 参数只有 `instruction` 时，child agent 无法看到附件摘要或 attachment ref。

本次修复：

- 抽出 `runtime/attachment_context.py`，统一构建脱敏附件摘要；signed URL 只保留 scheme、host、path，过滤 token、secret、password、api key 等敏感 metadata。
- root prompt 与 child prompt 复用同一套脱敏附件摘要逻辑。
- child skill prompt 从 `runtime_context.attachments` 注入附件摘要，覆盖 `invoke_business_skill` 参数未带附件的场景。
- 新增 Worker scripted E2E，覆盖带 `image.png` 附件的 query 经 root `invoke_business_skill` 进入 `tms-ticket-agent` 后，child LLM request 可见脱敏附件摘要/ref。

剩余待验证：真实 TMS BFF ask payload 是否包含顶层 `attachments`，以及现场 E2E 中 `tms-ticket-agent` 是否能看到脱敏附件摘要/ref。

## Impact Scope

- 业务用户以为已上传图片，但开工单、附件处理、OCR 或凭证留存链路无法使用该图片。
- 子技能可能根据缺失附件做出错误判断，或停在无法继续的 LLM 推理状态。
- 上游联调很难定位问题，因为缺少每一跳的脱敏附件计数和边界日志。

## Test Strategy

Automation is required because this issue spans widget、TMS BFF、OpenAPI、Java Worker relay、Python Worker and root-child skill handoff.

- Widget test：发送前上传成功后 ask body 含顶层 `attachments`。
- OpenAPI/SDK test：top-level `attachments` 与 `metadata.attachments` 归一化进入 A2A metadata。
- Java LangGraph client test：Worker HTTP body 含附件数组。
- Python query test：`QueryRequest.attachments` 进入 root state，并注入脱敏附件摘要。
- Root skill runtime test：`invoke_business_skill` 调用 child skill 时继承附件上下文。
- E2E test：TMS 模拟图片附件 ask 后，`tms-ticket-agent` 或按策略选中的附件技能能读取附件摘要/ref。

## Code Inventory

- `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/25-tms-attachment-upload-on-submit-and-worker-pass-through.md`
- `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/31-openapi-model-config-and-attachment-e2e-bug.md`
- `packages/navigator-chat-widget/src/composables/useNavigatorChat.ts`
- `packages/navigator-chat-widget/src/api/navigatorApi.ts`
- `addons/claude-worker-agent/src/main/java/.../OpenApiController.java`
- `session-module/src/main/java/.../TaskDispatchFacade.java`
- `addons/langgraph-biz-worker/src/main/java/.../LanggraphWorkerClient.java`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/models.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_router.py`

## Fix Checklist

- [ ] 为每一跳补充脱敏附件观测：只记录 count、kind、name、size、provider 和 attachment id/ref，不记录完整 signed URL。
- [ ] 在 TMS BFF / widget observability 页面确认真实 ask body 是否包含顶层 `attachments`。
- [ ] 检查 OpenAPI ask 是否正确合并 top-level `attachments` 与 `metadata.attachments`。
- [x] 检查 LangGraph Java relay 是否把附件放入 Worker HTTP body。
- [x] 检查 Python Worker 是否把 `attachments` 写入 root state。
- [x] 检查 root-to-child `invoke_business_skill` 是否把附件上下文传给 child frame。
- [ ] 明确 `tms-ticket-agent` 与 `tms-attachment-agent` 的职责边界：附件是由 root 先路由到附件技能，还是由工单技能消费附件上下文。
- [x] 补齐 Worker scripted E2E 回归测试，防止 Worker 收到附件但 child skill 不可见。
- [ ] 补齐或执行真实 TMS BFF/widget 到 Navigator 的现场 E2E，防止 UI 显示附件但 ask payload 未携带附件。

## Verification

- [ ] TMS 本地发送图片附件后，Navigator ask body 能看到脱敏附件计数。
- [ ] Worker task data 能看到附件数组或 attachment refs。
- [x] Worker scripted E2E 中，`tms-ticket-agent` child prompt context 能看到脱敏附件摘要/ref。
- [x] Worker direct real-LLM smoke 中，`system.root` 真实调用 `tms-ticket-agent`，child frame 返回脱敏附件 ref。
- [ ] 若路由到 `tms-attachment-agent`，对应 child frame 能看到附件并产出可供工单技能引用的结果。

### Verification Evidence 2026-05-18

- Python worker regression:

```powershell
$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests/test_llm_skill_agent.py::test_llm_agent_child_skill_receives_sanitized_attachment_context tests/test_query.py::test_attachment_context_prompt_sanitizes_url_and_keeps_metadata tests/test_query.py::test_query_generator_preserves_model_config_id_and_attachments_in_state
```

Result: `3 passed in 0.08s`.

- Worker scripted E2E:

```powershell
$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_tms_ticket_child_receives_attachment_context tests/test_llm_skill_agent.py::test_llm_agent_child_skill_receives_sanitized_attachment_context tests/test_query.py::test_attachment_context_prompt_sanitizes_url_and_keeps_metadata tests/test_query.py::test_query_generator_preserves_model_config_id_and_attachments_in_state -q
```

Result: `4 passed in 1.65s`; scripted E2E asserts the second mock LLM request for `tms-ticket-agent` contains `att-028`、`image.png`、`tms-bff`、sanitized URL path and excludes `token=secret`、`accessToken`、`hidden`.

- Java relay regression:

```powershell
mvn -pl addons/langgraph-biz-worker -am '-Dtest=LanggraphWorkerClientTest,LanggraphBusinessAgentWorkerTaskLauncherTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Result: `BUILD SUCCESS`; `LanggraphWorkerClientTest` 2 tests passed, `LanggraphBusinessAgentWorkerTaskLauncherTest` 4 tests passed.

- Worker direct real-LLM smoke:

```powershell
powershell -ExecutionPolicy Bypass -File .\start.ps1 -EnvFile .env.qwen35-plus.local
# Then POST /api/v1/query with a synthetic image.png attachment and real qwen3.5-plus LLM.
```

Result: PASS. Test record: `docs/version-tracker/1.3.0-SNAPSHOT/test-records/real-llm-attachment-ticket/20260518-162515-65bf00/summary.json`.

Key evidence:

- `system.root` emitted `invoke_business_skill` for `tms-ticket-agent`.
- `tms-ticket-agent` child frame `frm_2909a9ed2a81` completed and called `submit_skill_result`.
- Child structured output contained `att-028-real`, `image.png`, `tms-bff`, `https://tms.example.com/files/image.png`, `trace-real-bug028`, and `safe-note`.
- Artifact scan found no `token=secret`, `accessToken`, or `hidden` sensitive pattern hits.

## References

- `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/25-tms-attachment-upload-on-submit-and-worker-pass-through.md`
- `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/31-openapi-model-config-and-attachment-e2e-bug.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/BUG-027-biz-worker-llm-call-timeout-fuse-missing.md`
