---
audit_scope: feature
audit_mode: pre-acceptance-check
version: 1.3.0-SNAPSHOT
target: OPT-031-biz-worker-llm-skill-agent-module-governance
status: reviewed
conclusion: ready-with-gaps
reviewed_by: Codex
reviewed_at: 2026-05-18
follow_up_required: yes
---

# Test Coverage Audit

## Background

本次审计覆盖 OPT-031 BizWorker `llm_skill_agent.py` 模块治理实现。治理目标是把原本混合在单文件中的 prompt、tool schema、tool call codec、business adapter、tool dispatcher 和 child recovery 边界拆出，同时不改变 BUG-027 的 LLM 调用超时、重试、deadline、熔断和失败 child frame 用户“继续”恢复语义。

## Audit Basis

- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/OPT-031-biz-worker-llm-skill-agent-module-governance.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/quality/OPT-031-llm-skill-agent-module-governance-implementation-quality.md`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_tool_dispatcher.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_child_recovery.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_business_function_adapter.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_tool_schemas.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_agent_prompts.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_tool_call_codec.py`
- `tools/langgraph-biz-worker/tests/test_llm_skill_agent.py`
- `tools/langgraph-biz-worker/tests/test_llm_call_guard.py`
- `tools/langgraph-biz-worker/tests/test_frame_interruption.py`
- `tools/langgraph-biz-worker/tests/test_frame_lifecycle.py`
- `tools/langgraph-biz-worker/tests/test_artifact_context_governance.py`
- `tools/langgraph-biz-worker/tests/test_e2e_scripted_tool_call_streaming.py`

## Coverage Matrix

| Requirement / Acceptance Item | Risk | Evidence Layer | Evidence | Conclusion |
| --- | --- | --- | --- | --- |
| `llm_skill_agent.py` 缩减为高层 orchestration 文件，目标低于 600 行 | major | manual-evidence / unit-test | Stage E 后 `llm_skill_agent.py` 599 lines；Post-Stage-E focused/stage/full pytest 全部通过 | covered |
| 抽取模块拥有清晰职责且没有形成循环导入 | major | unit-test / manual-evidence | `llm_agent_prompts.py`、`llm_tool_schemas.py`、`llm_tool_call_codec.py`、`llm_business_function_adapter.py`、`llm_tool_dispatcher.py`、`llm_child_recovery.py`；full worker suite import/runtime 通过 | covered |
| BUG-027 LLM timeout/retry/deadline/fuse 语义保持 | critical | unit-test | `test_llm_agent_times_out_hung_model_and_fails_frame`、`test_llm_agent_retries_transient_timeout_and_completes_frame`、`tests/test_llm_call_guard.py`、Stage safety set | covered |
| child skill 失败后用户“继续”仍能在失败 frame 上恢复 | critical | unit-test / e2e-test | `test_persistent_root_child_timeout_records_recoverable_interruption`、`test_persistent_root_continue_reopens_failed_child_frame_after_timeout`、`test_llm_agent_root_resumes_pending_recoverable_child_frame`、`test_e2e_scripted_tool_call_streaming.py` | covered |
| business function direct invoke、approval bubble、suspension payload 保持兼容 | critical | unit-test / e2e-test | `test_llm_agent_root_skill_can_invoke_business_function_directly`、`test_llm_agent_suspends_business_function_call`、`test_llm_agent_bubbles_child_business_function_approval_to_root`、Stage C add-on E2E | covered |
| prompt/schema/codec 纯抽取不改变可见上下文、工具曝光和 tool-call 编解码 | major | unit-test | `test_llm_skill_agent.py`、`test_artifact_context_governance.py`、Post-P1 targeted/stage safety set | covered |
| artifact/file/public resource/attachment analysis 工具分发行为保持 | major | unit-test / e2e-test | `test_llm_agent_artifact_tool_available_with_account_context`、attachment analysis tests、`test_artifact_context_governance.py`、full worker suite | covered |
| tool audit JSONL 写入和 tool-use/tool-result event payload 保持 | major | unit-test / manual-evidence | `_execute_tool_call` 保留 event orchestration；tool audit moved without payload rewrite；full worker suite and implementation quality review | partially-covered |
| `LlmToolDispatcher` 和 `llm_child_recovery` 新模块拥有直接模块级单测 | minor | unit-test | 当前主要由 `test_llm_skill_agent.py` 和 E2E 间接覆盖，未新增独立 dispatcher/recovery unit tests | partially-covered |
| 最终 Python Worker full suite 通过 | major | unit-test | `.\.venv\Scripts\python -m pytest tests -q` -> 436 passed, 6 skipped, 10 warnings in 22.30s | covered |

## Evidence Summary

- Post-P1 targeted: `tests/test_llm_skill_agent.py tests/test_artifact_context_governance.py -q` -> 42 passed in 1.67s.
- Post-P1 stage safety set: `tests/test_llm_skill_agent.py tests/test_llm_call_guard.py tests/test_frame_interruption.py tests/test_frame_lifecycle.py tests/test_artifact_context_governance.py -q` -> 73 passed in 2.46s.
- Pre-P2 full worker baseline: `tests -q` -> 436 passed, 6 skipped, 10 warnings in 20.62s.
- Post-P2 focused/stage/add-on/full suite: focused 38 passed；stage safety 73 passed；Stage C add-on 49 passed, 3 warnings；full suite 436 passed, 6 skipped, 10 warnings.
- Post-P3/P4/P5 focused extraction runs: `tests/test_llm_skill_agent.py -q` remained green after each dispatcher/recovery extraction step.
- Post-P5 stage/add-on/full suite: stage safety 73 passed；Stage C add-on 49 passed, 3 warnings；full suite 436 passed, 6 skipped, 10 warnings.
- Post-Stage-E focused/stage/full suite: focused 38 passed；stage safety 73 passed；full suite 436 passed, 6 skipped, 10 warnings.
- Implementation quality gate concluded `ready-for-coverage-audit` with no blocking implementation findings.

## Gaps

- 没有在 P1 之前捕获 targeted baseline；已用 post-P1 targeted、pre-P2 full baseline 和后续 staged evidence 补足抽取安全证据。
- `LlmToolDispatcher` 和 `llm_child_recovery` 目前主要通过 `test_llm_skill_agent.py` 与 E2E 间接覆盖；如果后续继续拆 `_call_tool` 或 `_finalize_business_function_call`，应先补模块级单测。
- tool audit JSONL 写入路径已在 Stage E 移动，但没有新增直接断言 audit 文件字段的专门测试；现有证据来自 full suite 通过、实现 review 和保持 payload 字段不改的代码检查。

## Recommended Next Skills

- `foggy-acceptance-signoff`：正式签收 OPT-031 前使用。
- 项目 pytest 单测补充：后续继续治理 dispatcher/recovery 内部语义时，先补 `llm_tool_dispatcher` / `llm_child_recovery` 的直接单测。

## Conclusion

`ready-with-gaps`。核心验收项和 BUG-027 高风险恢复语义已有自动化测试承接，可以进入正式验收签收；剩余缺口是非阻断性的测试粒度问题，应作为后续继续拆分或语义修改前的前置补强。
