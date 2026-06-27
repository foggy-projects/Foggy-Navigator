---
audit_scope: bug
audit_mode: pre-acceptance-check
version: 1.3.1-SNAPSHOT
target: BUG-145
status: reviewed
conclusion: ready-with-gaps
reviewed_by: codex
reviewed_at: 2026-06-26
follow_up_required: yes
---

# Test Coverage Audit

## Background

- 审计对象：BUG-145 BizWorker sidecar 写文件权限错误恢复
- 当前阶段：实现质量门后，验收前覆盖审计
- 审计目标：确认 GitHub issue #145 的失败语义、重试边界、Java 失败传播和操作手册均有证据

## Audit Basis

- requirement: GitHub issue #145
- implementation plan: 按 1~5 顺序推进的 BUG 修复计划
- progress: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/BUG-145-bizworker-sidecar-permission-recovery.md`
- bug work items: BUG-145
- acceptance basis: 权限错误必须变成可恢复失败，不允许任务长时间 `SUBMITTED`
- test records: Python pytest、Java Maven、WSL permission smoke
- manual evidence: WSL `root:root 755` 目录下 `navigator` 写 `REPORT.md` 返回 `Permission denied`

## Coverage Matrix

| Item | Risk | Unit | Integration | E2E | Playwright | Manual | Evidence Path | Coverage |
|------|------|------|-------------|-----|------------|--------|---------------|----------|
| File-tool PermissionError becomes `storage_permission_denied` without absolute path leak | critical | yes | no | no | no | no | `tests/test_account_file_tools.py` | covered |
| Dispatcher marks storage permission failure recoverable, upstream-action-required, no LLM retry | critical | yes | no | no | no | no | `tests/test_llm_tool_dispatcher.py` | covered |
| LLM agent stops current turn instead of diagnosing logs/source/system dirs | critical | yes | no | no | no | no | `tests/test_llm_skill_agent.py` | covered |
| Runtime memory projection keeps recoverability and error metadata | major | yes | no | no | no | no | `tests/test_root_graph.py` | covered |
| Java SSE relay publishes failed task event with storage permission metadata | critical | yes | no | no | no | no | `LanggraphStreamRelayTest` | covered |
| A2A `getTask` reports failed LangGraph task as `FAILED`, not `SUBMITTED` | critical | yes | no | no | no | no | `LanggraphWorkerInnerA2aAgentTest` | covered |
| Real root-owned WSL task directory denies writes for BizWorker runtime user | major | no | no | no | no | yes | WSL smoke command in work item | covered |
| Live SIM task replay confirms no long-running `SUBMITTED` residue | major | no | no | no | no | no | next SIM rehearsal | gap |

## Evidence Summary

- 已有自动化测试：
  - `.venv\Scripts\python.exe -m pytest tests/test_account_file_tools.py tests/test_llm_tool_dispatcher.py tests/test_llm_skill_agent.py tests/test_root_graph.py -q` -> 152 passed, 2 skipped
  - Focused permission nodes -> 4 passed
  - `mvn test -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphStreamRelayTest,LanggraphWorkerInnerA2aAgentTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"` -> 23 passed
- 已有手工验证：
  - WSL smoke: `root:root 755` directory blocks `navigator` from touching `REPORT.md`
- 已有回归保护：
  - Python runtime stops on `storage_permission_denied`
  - Java relay fails task and publishes metadata
  - A2A status query returns `FAILED`

## Gaps

- 缺口 1：未在真实 SIM 演练任务上做完整端到端重放。
- 缺口 2：未覆盖 Java frontend UI 展示字段，因为本次 BUG 要求停留在任务状态和上游错误传播。

## Recommended Next Skills

- `integration-test`: 下一次可用于补真实服务链路回放
- `playwright-cli`: 不需要，未涉及 UI 交互改动
- `foggy-bug-regression-workflow`: 已执行
- `foggy-acceptance-signoff`: 执行
- `plan-evaluator`: 不需要

## Conclusion

- conclusion: ready-with-gaps
- can_enter_acceptance: yes
- follow_up_required: yes
