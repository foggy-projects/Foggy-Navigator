---
quality_scope: bug
quality_mode: pre-coverage-audit
version: 1.3.1-SNAPSHOT
target: BUG-145
status: reviewed
decision: ready-for-coverage-audit
reviewed_by: codex
reviewed_at: 2026-06-26
follow_up_required: no
---

# Implementation Quality Gate

## Background

- 检查对象：BUG-145 BizWorker sidecar 写文件权限错误恢复
- 当前阶段：修复已实现，进入覆盖审计前检查
- 本次目标：确认权限错误分类、LLM 停止策略、Java relay 失败传播和运行手册写回已经闭环

## Check Basis

- requirement: GitHub issue #145
- bug work item: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/BUG-145-bizworker-sidecar-permission-recovery.md`
- implementation plan: 按 1~5 顺序推进的 BUG 修复计划
- progress: work item progress 记录
- execution check-in: Python + Java 聚焦回归、WSL permission smoke
- test result summary: Python 152 passed / 2 skipped；Java 23 passed

## Changed Surface

- changed files:
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/account_file_tools.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_tool_dispatcher.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`
  - `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphStreamRelay.java`
  - focused Python and Java regression tests
- changed modules: LangGraph Biz Worker Python runtime, LangGraph Biz Worker Java addon
- declared completed scope: terminal recoverable storage permission failure and upstream-visible failed task propagation

## Quality Checklist

- scope conformance: pass，改动集中在权限错误分类与错误传播链路
- code hygiene: pass，新增 helper 复用现有 payload 构造风格
- duplication and consolidation: pass，Java 仅补布尔字段 helper；Python 统一 PermissionError helper
- complexity and abstraction: pass，没有引入新的 sandbox 或权限修复机制
- error handling and edge cases: pass，覆盖 `mkstemp`、write/edit/patch/read PermissionError 和 Java worker error event
- readability and maintainability: pass，错误码稳定且 detail 不泄漏绝对路径
- critical logic documentation: pass，work item 增加 sidecar materialization runbook
- contract and compatibility: pass，A2A 映射逻辑未改，仅增加失败态回归
- documentation and writeback: pass，版本文档已更新
- test alignment: pass，Python/Java/WSL smoke 均有证据
- release readiness: pass，可进入覆盖审计

## Findings

- finding 1: 未发现需要返工的问题。
- finding 2: Java relay 现在透传 `errorCode`、`recoverable`、`llmRetryAllowed`、`requiresUpstreamAction` 等字段，满足前端和上游判断需求。

## Risks / Follow-ups

- risk 1: 尚未在真实 SIM 任务中做完整重放；该风险属于环境级验证，不影响代码路径质量门通过。
- follow-up 1: 下一次 SIM 演练验证旧任务不会继续表现为长时间 `SUBMITTED`。

## Recommended Next Skills

- `foggy-test-coverage-audit`: 执行
- `foggy-bug-regression-workflow`: 已用于 BUG 记录与回归决策
- `plan-evaluator`: 不需要
- back to implementation: 不需要

## Decision

- decision: ready-for-coverage-audit
- can_enter_coverage_audit: yes
- follow_up_required: no

## Lightweight Self-Check Note

- self_check_summary: 权限错误分类、LLM 终止、Java relay 失败传播、A2A 失败态查询、运行手册均已覆盖。
- self_check_decision: needs-formal-quality-gate
- self_check_follow_up: none
