---
acceptance_scope: feature
version: 1.3.1-SNAPSHOT
target: OPT-001-stage15-worker-session-typed-envelope
doc_role: acceptance-record
doc_purpose: 说明本文件用于 Stage 15 WorkerSession typed DTO / envelope 的功能级正式验收与签收结论记录
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
- purpose: 记录 OPT-001 Stage 15 WorkerSession typed DTO / envelope 的功能级正式验收结论与证据摘要。

## Background

- Version: 1.3.1-SNAPSHOT
- Target: OPT-001 Stage 15 WorkerSession typed DTO / envelope
- Owner: navigator-spi / session-module / claude-worker-agent / langgraph-biz-worker
- Goal: 将 worker-session provider fan-out 主链路从 legacy `Map` / `List<Map>` 方法迁移到 typed DTO / envelope 方法，同时保留旧 REST payload 与 legacy provider 兼容。

## Acceptance Basis

- Requirement: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
- Implementation plan / progress: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage15-worker-session-typed-envelope.md`
- Quality gate: `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage15-implementation-quality.md`
- Coverage audit: `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage15-coverage-audit.md`

## Checklist

- [x] scope 内功能点已全部交付
- [x] 原始 acceptance criteria 已逐项覆盖
- [x] 关键测试已通过
- [x] 体验验证已完成，或明确标记 `N/A`
- [x] 文档、配置、依赖项已闭环

## Evidence

- Requirement:
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage15-worker-session-typed-envelope.md`
- Implementation:
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/WorkerSessionQueryProvider.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/WorkerSessionSummary.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/WorkerSessionMessage.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/WorkerSessionMessageCount.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/WorkerSessionSyncResult.java`
  - `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
  - `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphWorkerSessionQueryService.java`
- Test:
  - `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
  - `addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/service/LanggraphWorkerSessionQueryServiceTest.java`
  - Targeted regression: 114 tests pass.
  - Affected direct reactor regression: 208 reports / 1461 tests pass, 0 failures, 0 errors, 0 skipped.
  - Broader Java worker reactor regression: 221 reports / 1535 tests pass, 0 failures, 0 errors, 0 skipped.
  - Static scan: production fan-out no longer directly calls legacy worker-session Map provider methods.
- Quality:
  - `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage15-implementation-quality.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage15-coverage-audit.md`
- Experience:
  - N/A。该切片为 Java 后端 SPI 契约收敛，未新增或修改 UI 页面、表单、按钮、权限可见性或前端交互。

## Failed Items

- none

## Risks / Open Items

- legacy Map worker-session 方法仍保留。Owner: navigator-spi / provider addons。Follow-up: 外部插件/调用方迁移完成后，再规划 deprecation / removal。
- REST worker-session payload 仍为 Map。Owner: session-module。Follow-up: 如需外部协议 typed 化，应另起兼容迁移项。
- typed adapter 读取 Map / public getter 字段，只有 Map 来源完整保留 raw attributes。Owner: navigator-spi。Follow-up: 非标准对象返回应推动 Provider 改为 typed result。
- Claude worker-session 查询仍在 `ClaudeTaskService` 物理 bean 内。Owner: claude-worker-agent。Follow-up: 后续可拆成独立 `WorkerSessionQueryProvider` bean。
- 未运行仓库级全量 `mvn test`。Owner: java-platform。Follow-up: 版本最终收口或发版前执行。

## Final Decision

本功能签收结论为 `accepted-with-risks`。

Stage 15 已完成 WorkerSession typed DTO / envelope 迁移：`WorkerSessionQueryProvider` 提供 typed 主方法，`TaskDispatchFacade` worker-session fan-out 改走 typed methods，Claude / LangGraph provider 提供 typed override，legacy Map 方法保留兼容并委派，旧 Map provider 可通过 SPI default adapter 被 typed path 读取。targeted regression、affected reactor、broader Java worker reactor、静态扫描和 diff check 均通过。遗留项均为后续架构收敛范围，不阻断当前阶段签收。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage15-worker-session-typed-envelope-acceptance.md
- blocking_items: none
- follow_up_required: yes
