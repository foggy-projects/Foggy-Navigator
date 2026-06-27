---
acceptance_scope: feature
version: 1.3.1-SNAPSHOT
target: OPT-001-stage16-claude-worker-session-bean
doc_role: acceptance-record
doc_purpose: 说明本文件用于 Stage 16 Claude worker-session provider bean split 的功能级正式验收与签收结论记录
status: signed-off
decision: accepted-with-risks
signed_off_by: Codex
signed_off_at: 2026-06-26
reviewed_by: Codex
blocking_items: []
follow_up_required: yes
evidence_count: 12
---

# Feature Acceptance

## Document Purpose

- doc_type: acceptance
- intended_for: signoff-owner / reviewer / owning-module
- purpose: 记录 OPT-001 Stage 16 Claude worker-session provider bean split 的功能级正式验收结论与证据摘要。

## Background

- Version: 1.3.1-SNAPSHOT
- Target: OPT-001 Stage 16 Claude worker-session provider bean split
- Owner: claude-worker-agent / session-module
- Goal: 将 Claude worker-session 查询能力从 `ClaudeTaskService` 物理 bean 拆到独立 `WorkerSessionQueryProvider` bean，使 Claude 与 LangGraph 的 worker-session 职责边界保持一致。

## Acceptance Basis

- Requirement: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
- Implementation plan / progress: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage16-claude-worker-session-bean.md`
- Quality gate: `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage16-implementation-quality.md`
- Coverage audit: `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage16-coverage-audit.md`

## Checklist

- [x] scope 内功能点已全部交付
- [x] 原始 acceptance criteria 已逐项覆盖
- [x] 关键测试已通过
- [x] 体验验证已完成，或明确标记 `N/A`
- [x] 文档、配置、依赖项已闭环

## Evidence

- Requirement:
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage16-claude-worker-session-bean.md`
- Implementation:
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeWorkerSessionQueryService.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
- Test:
  - `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/ClaudeWorkerSessionQueryServiceTest.java`
  - `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/ClaudeTaskServiceAuthTest.java`
  - Targeted regression: 100 tests pass.
  - Affected reactor regression: 183 reports / 1328 tests pass, 0 failures, 0 errors, 0 skipped.
  - Static scan: `ClaudeTaskService.java` 不再匹配 worker-session provider 端口或 capabilities。
  - Static scan: `ClaudeWorkerSessionQueryService.java` 独立实现 `WorkerSessionQueryProvider` 并声明 worker-session capabilities。
  - `git diff --check`: pass，仅 CRLF normalization warnings。
- Quality:
  - `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage16-implementation-quality.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage16-coverage-audit.md`
- Experience:
  - N/A。该切片为 Java 后端 provider bean 职责拆分，未新增或修改 UI 页面、表单、按钮、权限可见性或前端交互。

## Failed Items

- none

## Risks / Open Items

- `ClaudeWorkerSessionQueryService` sync path 仍复用 `ClaudeTaskService.syncLocalSessions(...)`。Owner: claude-worker-agent。Follow-up: 如后续继续压低 service 间耦合，可单独抽 `ClaudeSessionProjectionService`。
- legacy worker-session Map 方法仍保留。Owner: navigator-spi / provider addons。Follow-up: 外部插件/调用方迁移完成后，再规划 deprecation / removal。
- REST worker-session payload 仍为 Map。Owner: session-module。Follow-up: 如需外部协议 typed 化，应另起兼容迁移项。
- 未运行仓库级根目录全量 `mvn test`。Owner: java-platform。Follow-up: 版本最终收口或发版前执行。

## Final Decision

本功能签收结论为 `accepted-with-risks`。

Stage 16 已完成 Claude worker-session provider bean split：`ClaudeWorkerSessionQueryService` 独立实现 `WorkerSessionQueryProvider`，承接 list/count/messages/sync typed 与 legacy 方法；`ClaudeTaskService` 不再实现 worker-session 端口，也不再声明 worker-session capabilities。targeted regression、affected reactor、静态扫描和 diff check 均通过。遗留项均为后续架构收敛范围，不阻断当前阶段签收。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage16-claude-worker-session-bean-acceptance.md
- blocking_items: none
- follow_up_required: yes
