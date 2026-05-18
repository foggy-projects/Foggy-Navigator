---
acceptance_scope: feature
version: 1.3.0-SNAPSHOT
target: OPT-031-biz-worker-llm-skill-agent-module-governance
doc_role: acceptance-record
doc_purpose: 说明本文件用于 OPT-031 功能级正式验收与签收结论记录
status: signed-off
decision: accepted-with-risks
signed_off_by: Codex
signed_off_at: 2026-05-18
reviewed_by: N/A
blocking_items: []
follow_up_required: yes
evidence_count: 9
---

# Feature Acceptance

## Document Purpose

- doc_type: acceptance
- intended_for: biz-worker-runtime / reviewer / release-owner
- purpose: 记录 OPT-031 BizWorker `llm_skill_agent.py` 模块治理的正式验收结论、证据摘要和非阻断风险。

## Background

- Version: 1.3.0-SNAPSHOT
- Target: OPT-031-biz-worker-llm-skill-agent-module-governance
- Owner: biz-worker-runtime
- Goal: 在不改变 BUG-027 timeout/retry/deadline/fuse 与失败 child frame 用户“继续”恢复语义的前提下，将 `llm_skill_agent.py` 从大文件拆分为清晰的 prompt、schema、codec、dispatcher、child recovery 和 business adapter 模块边界。

## Acceptance Basis

- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/OPT-031-biz-worker-llm-skill-agent-module-governance.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/quality/OPT-031-llm-skill-agent-module-governance-implementation-quality.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/coverage/OPT-031-llm-skill-agent-module-governance-coverage-audit.md`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_tool_dispatcher.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_child_recovery.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_business_function_adapter.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_tool_schemas.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_agent_prompts.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_tool_call_codec.py`

## Checklist

- [x] Stage B through Stage E extraction已完成，且未引入功能重写。
- [x] `llm_skill_agent.py` 已缩减到 599 行，满足目标“ideally under 600 lines”。
- [x] BUG-027 相关 timeout/retry/deadline/fuse 入口仍保留在 `LlmSkillAgent.run` 调用 `invoke_chat_model` 的控制路径上。
- [x] 失败 child frame 用户“继续”恢复语义已有 focused tests 覆盖，并在 Stage D/E 后保持通过。
- [x] business function direct invoke、approval bubble、suspension payload 和 monkeypatch 兼容点保持可用。
- [x] prompt/schema/codec/dispatcher/recovery/business adapter 模块职责已在 workitem、quality gate 和 coverage audit 中登记。
- [x] focused、stage safety、Stage C add-on 和 full Python worker suite 均已记录通过。
- [x] UI 体验验证为 `N/A`，本次为后端 Python runtime 模块治理，无前端用户流程变化。
- [x] 非阻断测试粒度缺口已在 coverage audit 中明确列出。

## Evidence

- Requirement / progress: `docs/version-tracker/1.3.0-SNAPSHOT/workitems/OPT-031-biz-worker-llm-skill-agent-module-governance.md`
- Implementation quality: `docs/version-tracker/1.3.0-SNAPSHOT/quality/OPT-031-llm-skill-agent-module-governance-implementation-quality.md`，decision 为 `ready-for-coverage-audit`。
- Coverage audit: `docs/version-tracker/1.3.0-SNAPSHOT/coverage/OPT-031-llm-skill-agent-module-governance-coverage-audit.md`，conclusion 为 `ready-with-gaps`。
- Commits: `4af44515`、`543665ef`、`8ce456d8`、`6b891d68`、`51109ced`、`6ecf7b76`。
- Post-Stage-E focused test: `tests/test_llm_skill_agent.py -q` -> 38 passed in 1.65s.
- Post-Stage-E stage safety set: `tests/test_llm_skill_agent.py tests/test_llm_call_guard.py tests/test_frame_interruption.py tests/test_frame_lifecycle.py tests/test_artifact_context_governance.py -q` -> 73 passed in 2.55s.
- Final full Python worker suite: `tests -q` -> 436 passed, 6 skipped, 10 warnings in 22.30s.
- Stage C add-on after P5: `tests/test_llm_skill_agent.py tests/test_e2e_scripted_tool_call_streaming.py -q` -> 49 passed, 3 warnings in 13.40s.
- Review result: no blocking findings were found in the post-implementation code review.

## Failed Items

- none

## Risks / Open Items

- `LlmToolDispatcher` 和 `llm_child_recovery` 目前主要由 `test_llm_skill_agent.py` 与 E2E 间接覆盖；后续如果继续拆 `_call_tool` 或 `_finalize_business_function_call`，应先补直接模块级单测。
- tool audit JSONL 写入路径在 Stage E 移动后未新增专门字段断言测试；当前签收接受该风险，因为路径和 payload 由实现 review 与 full suite 间接验证。
- P1 之前没有 captured targeted baseline；后续治理类任务应在首次抽取前先记录 targeted baseline。

## Final Decision

OPT-031 签收结论为 `accepted-with-risks`。核心治理目标已经达成，BUG-027 高风险恢复语义和 business function 路径有自动化测试证据承接；剩余问题是非阻断性的测试粒度和基线记录缺口，不阻止本次模块治理签收。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-05-18
- acceptance_record: docs/version-tracker/1.3.0-SNAPSHOT/acceptance/OPT-031-llm-skill-agent-module-governance-acceptance.md
- blocking_items: none
- follow_up_required: yes
