---
acceptance_scope: feature
version: 1.1.6-SNAPSHOT
target: bizworker-runtime-context-phase1
status: signed-off
decision: accepted-with-risks
signed_off_by: Codex
signed_off_at: 2026-05-21
reviewed_by: Codex
blocking_items: []
follow_up_required: yes
evidence_count: 6
doc_role: acceptance-signoff
doc_purpose: 签收 BizWorker Runtime Context Phase 1 实现，并记录附带 BusinessFunction 配置错误反馈修复的验收证据。
---

# Feature Acceptance

## Background

本次验收对象是 BizWorker Runtime Context Phase 1：BizWorker 按 `contextId` 自主管理 Root LLM runtime-visible conversation，普通多轮对话不再依赖 Java `recentConversation` 作为 prompt source of truth。附带验收 BusinessFunction `upstream_ref` 配置错误反馈修复。

## Acceptance Basis

- [../README.md](../README.md)
- [../06-normal-turn-runtime-context-design.md](../06-normal-turn-runtime-context-design.md)
- [../07-normal-turn-runtime-context-implementation-plan.md](../07-normal-turn-runtime-context-implementation-plan.md)
- [../05-business-function-upstream-ref-error-feedback-bug.md](../05-business-function-upstream-ref-error-feedback-bug.md)
- [../quality/runtime-context-phase1-implementation-quality.md](../quality/runtime-context-phase1-implementation-quality.md)

## Checklist

- accepted: BizWorker 已新增 `ContextRuntimeMemory`，Root frame `private_working_state.runtime_context_memory` 是 Phase 1 事实存储。
- accepted: 第二轮 Root prompt 使用 `_runtime_visible_conversation`，且 E2E 证明没有 Java `recentConversation` 也能看到上一轮 `U1 -> A1`。
- accepted: Java `recentConversation` 只在 memory 为空时 bootstrap，一旦 revision > 0 不覆盖 BizWorker memory。
- accepted: raw tool / system message 不进入 runtime visible semantic window。
- accepted: Root turn 成功、等待用户、interruption / approval / error 路径都有 commit 或 abandon 行为。
- accepted: `/api/v1/query` 已按 `contextId` 做进程内互斥；Phase 1 并发请求返回 `CONTEXT_RUNTIME_BUSY`。
- accepted: BusinessFunction 配置错误不再被当成 LLM 可修复入参盲目重试，并已加入 readiness 前置校验。

## Evidence

1. `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/context_memory.py`：新增 runtime memory 抽象与 Root frame 存储封装。
2. `tools/langgraph-biz-worker/tests/test_context_memory.py`、`tests/test_root_graph.py`、`tests/test_e2e_scripted_tool_call_streaming.py`：覆盖 begin/commit、bootstrap once、runtime prompt、busy、E2E 二轮连续性。
3. `cd tools/langgraph-biz-worker; $env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests -q`：`556 passed, 6 skipped`。
4. `cd tools/langgraph-biz-worker; $env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests/test_e2e_scripted_tool_call_streaming.py -q`：`18 passed`。
5. `mvn -pl addons/langgraph-biz-worker -am -Dtest=InvokeBusinessFunctionToolTest "-Dsurefire.failIfNoSpecifiedTests=false" test`：`10 tests, 0 failures, 0 errors`。
6. `mvn -pl addons/claude-worker-agent -am -Dtest=OpenApiAgentReadinessServiceTest "-Dsurefire.failIfNoSpecifiedTests=false" test`：`11 tests, 0 failures, 0 errors`。

## Failed Items

无阻断项。

## Risks / Open Items

- Phase 1 使用进程内锁；多进程/多实例部署需要 Phase 3 或独立任务补文件锁 / Redis 锁。
- Phase 1 执行中追加消息返回 busy，不做 pending queue；pending queue + checkpoint 是 Phase 3 范围。
- head-tail + LLM summarizer 压缩尚未实现，是 Phase 4 范围。
- 实际 TMS 工单链路仍建议在 `nav_tms_3` 修复 `upstream_ref` 配置后再做一次真实环境复验。

## Final Decision

accepted-with-risks

本阶段已满足 Phase 1 验收标准，可以合入并进入 Phase 2/3 设计与开发。列出的风险均属于已确认后续阶段范围，不阻断 Phase 1。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-05-21
- acceptance_record: docs/version-tracker/1.1.6-SNAPSHOT/acceptance/runtime-context-phase1-acceptance.md
- blocking_items: none
- follow_up_required: yes
