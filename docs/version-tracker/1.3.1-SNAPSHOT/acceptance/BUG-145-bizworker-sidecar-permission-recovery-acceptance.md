---
acceptance_scope: bug
version: 1.3.1-SNAPSHOT
target: BUG-145
doc_role: acceptance-record
doc_purpose: 说明本文件用于 BUG-145 正式验收与签收结论记录
status: signed-off
decision: accepted-with-risks
signed_off_by: codex
signed_off_at: 2026-06-26
reviewed_by: N/A
blocking_items: []
follow_up_required: yes
evidence_count: 5
---

# Feature Acceptance

## Document Purpose

- doc_type: acceptance
- intended_for: signoff-owner / reviewer / owning-module
- purpose: 记录 BUG-145 的正式验收结论与证据摘要

## Background

- Version: 1.3.1-SNAPSHOT
- Target: BUG-145
- Owner: langgraph-biz-worker
- Goal: BizWorker sidecar 写文件权限错误后必须进入可恢复失败状态，并把错误传播给 Java relay/A2A 上游，避免任务长时间停留 `SUBMITTED`。

## Acceptance Basis

- [feature requirement] GitHub issue #145
- [feature implementation plan] BUG-145 work item
- [progress record] BUG-145 progress section
- [test record] BUG-145 coverage audit
- [experience record] WSL permission smoke
- [acceptance evidence] Python and Java regression test output

## Checklist

- [x] scope 内功能点已全部交付
- [x] 原始 acceptance criteria 已逐项覆盖
- [x] 关键测试已通过
- [x] 体验验证已完成，或明确标记 `N/A`
- [x] 文档、配置、依赖项已闭环

## Evidence

- Requirement:
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/BUG-145-bizworker-sidecar-permission-recovery.md`
- Test:
  - Python full target suite: 152 passed, 2 skipped
  - Python focused permission nodes: 4 passed
  - Java focused relay/A2A suite: 23 passed
- Experience:
  - WSL root-owned task directory smoke returned expected `Permission denied`
- Artifact:
  - `docs/version-tracker/1.3.1-SNAPSHOT/quality/BUG-145-implementation-quality.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/coverage/BUG-145-coverage-audit.md`

## Failed Items

- none

## Risks / Open Items

- Live SIM task replay has not been executed after the fix. Owner: SIM rehearsal owner / langgraph-biz-worker maintainer. Follow-up: verify in next rehearsal that the task transitions to failed with `storage_permission_denied` metadata instead of remaining `SUBMITTED`.

## Final Decision

BUG-145 is `accepted-with-risks`. The core failure path is covered by unit regressions, Java relay/A2A tests, and real WSL permission smoke. The remaining risk is an environment-level SIM replay, not a blocking implementation gap.

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/BUG-145-bizworker-sidecar-permission-recovery-acceptance.md
- blocking_items: none
- follow_up_required: yes
