---
acceptance_scope: feature
version: 1.3.1-SNAPSHOT
target: OPT-001-stage14-task-listing-typed-method
doc_role: acceptance-record
doc_purpose: 说明本文件用于 Stage 14 TaskListingProvider typed method contract 的功能级正式验收与签收结论记录
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
- purpose: 记录 OPT-001 Stage 14 TaskListingProvider typed method contract 的功能级正式验收结论与证据摘要。

## Background

- Version: 1.3.1-SNAPSHOT
- Target: OPT-001 Stage 14 TaskListingProvider typed method contract
- Owner: navigator-spi / session-module / claude-worker-agent / codex-worker-agent
- Goal: 将 listing/search provider fan-out 主链路从 legacy `Object` 方法迁移到 typed `TaskPageResult` / `TaskSearchResult` 方法，同时保留旧 Map / JavaBean envelope 与 legacy 方法兼容。

## Acceptance Basis

- Requirement: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
- Implementation plan / progress: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage14-task-listing-typed-method.md`
- Quality gate: `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage14-implementation-quality.md`
- Coverage audit: `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage14-coverage-audit.md`

## Checklist

- [x] scope 内功能点已全部交付
- [x] 原始 acceptance criteria 已逐项覆盖
- [x] 关键测试已通过
- [x] 体验验证已完成，或明确标记 `N/A`
- [x] 文档、配置、依赖项已闭环

## Evidence

- Requirement:
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage14-task-listing-typed-method.md`
- Implementation:
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskListingProvider.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskPageResult.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskSearchResult.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskResultEnvelopeAdapters.java`
  - `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexBizTaskProvider.java`
- Test:
  - `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
  - `session-module/src/test/java/com/foggy/navigator/session/service/UnifiedSessionTaskProjectionServiceTest.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexTaskServiceTest.java`
  - Targeted regression: 121 tests pass.
  - Affected reactor regression: 191 suites / 1380 tests pass, 0 failures, 0 errors, 0 skipped.
  - Static scan: production fan-out no longer directly calls legacy listing `Object` provider methods.
- Quality:
  - `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage14-implementation-quality.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage14-coverage-audit.md`
- Experience:
  - N/A。该切片为 Java 后端 SPI 契约收敛，未新增或修改 UI 页面、表单、按钮、权限可见性或前端交互。

## Failed Items

- none

## Risks / Open Items

- legacy `Object` listing/search 方法仍保留。Owner: navigator-spi / provider addons。Follow-up: 外部插件/调用方迁移完成后，再规划 deprecation / removal。
- legacy adapter 只承诺 Map 与 public JavaBean getter envelope。Owner: navigator-spi。Follow-up: 非标准对象返回应视为 unsupported，并推动 Provider 改为 typed result。
- `UnifiedSessionTaskProjectionService` 仍保留 legacy fallback。Owner: session-module。Follow-up: 外部契约稳定后可进一步收缩反射读取。
- worker-session payload 仍为 Map。Owner: navigator-spi / session-module / provider addons。Follow-up: 规划 typed DTO / envelope 迁移。
- Claude worker-session 查询仍在 `ClaudeTaskService` 物理 bean 内。Owner: claude-worker-agent。Follow-up: 如需更强职责隔离，后续拆成独立 `WorkerSessionQueryProvider` bean。
- 未运行仓库级全量 `mvn test`。Owner: java-platform。Follow-up: 版本最终收口或发版前执行。

## Final Decision

本功能签收结论为 `accepted-with-risks`。

Stage 14 已完成 `TaskListingProvider` typed method contract 迁移：session fan-out 主链路改为 typed 方法，Claude / Codex / Codex Biz 提供 typed override，legacy `Object` 方法保留兼容并委派，旧 Map / JavaBean envelope 可通过 SPI adapter 被 typed default 方法读取。targeted regression、affected reactor、静态扫描和 diff check 均通过。遗留项均为后续架构收敛范围，不阻断当前阶段签收。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage14-task-listing-typed-method-acceptance.md
- blocking_items: none
- follow_up_required: yes
