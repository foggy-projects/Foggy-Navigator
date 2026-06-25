---
acceptance_scope: feature
version: 1.3.1-SNAPSHOT
target: OPT-001-stage6-task-query-provider-port-split
doc_role: acceptance-record
doc_purpose: 说明本文件用于 Stage 6 TaskQueryProvider 窄端口治理的功能级正式验收与签收结论记录
status: signed-off
decision: accepted-with-risks
signed_off_by: Codex
signed_off_at: 2026-06-25
reviewed_by: Codex
blocking_items: []
follow_up_required: yes
evidence_count: 5
---

# Feature Acceptance

## Document Purpose

- doc_type: acceptance
- intended_for: signoff-owner / reviewer / owning-module
- purpose: 记录 OPT-001 Stage 6 `TaskQueryProvider` 窄端口治理的功能级正式验收结论与证据摘要。

## Background

- Version: 1.3.1-SNAPSHOT
- Target: OPT-001 Stage 6 TaskQueryProvider 窄端口治理
- Owner: session-module / navigator-spi / java-platform
- Goal: 在不破坏现有 Provider 实现和外部 API 契约的前提下，将 `TaskQueryProvider` 的 lookup、command、listing/search、worker session 职责拆成窄端口，并让 session 侧调用点优先依赖窄端口类型。

## Acceptance Basis

- Requirement: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
- Implementation plan / progress: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage6-task-query-provider-port-split.md`
- Quality gate: `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage6-implementation-quality.md`
- Coverage audit: `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage6-coverage-audit.md`
- Test records:
  - `mvn test -pl session-module -am "-Dtest=TaskQueryProviderRegistryTest,TaskDispatchFacadeTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 62 tests pass.
  - `mvn test -pl navigator-spi,session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am`: affected reactor pass; Surefire XML total 220 reports / 1520 tests / 0 failures / 0 errors / 0 skipped.

## Checklist

- [x] scope 内功能点已全部交付
- [x] 原始 acceptance criteria 已逐项覆盖
- [x] 关键测试已通过
- [x] 体验验证已完成，或明确标记 `N/A`
- [x] 文档、配置、依赖项已闭环

## Evidence

- Requirement:
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage6-task-query-provider-port-split.md`
- Implementation:
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskProviderPort.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskLookupProvider.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskCommandProvider.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskListingProvider.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/WorkerSessionQueryProvider.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskQueryProvider.java`
  - `session-module/src/main/java/com/foggy/navigator/session/service/TaskQueryProviderRegistry.java`
  - `session-module/src/main/java/com/foggy/navigator/session/service/TaskOperationRouter.java`
  - `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
  - `session-module/src/main/java/com/foggy/navigator/session/service/TaskCreateTargetResolver.java`
- Test:
  - `session-module/src/test/java/com/foggy/navigator/session/service/TaskQueryProviderRegistryTest.java`
  - `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
  - affected Java reactor command listed above
- Quality:
  - `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage6-implementation-quality.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage6-coverage-audit.md`
- Experience:
  - N/A。该切片为 Java SPI 和后端路由类型边界治理，未新增或修改 UI 页面、表单、按钮、权限可见性或前端交互。

## Failed Items

- none

## Risks / Open Items

- `TaskQueryProvider` 仍保留为兼容聚合接口；Provider bean 注入尚未迁移到独立窄端口集合。Owner: java-platform / session-module。Follow-up: 后续版本评估 Provider 按需实现独立端口的迁移策略。
- `TaskCommandProvider#cancelTask` 仍保留 deprecated 兼容方法。Owner: session-module。Follow-up: 等统一 A2A abort 链路完全覆盖后，再规划移除 legacy direct cancel 入口。
- 未新增 REST / OpenAPI / SDK E2E；本阶段未改变 controller 或 payload contract，当前不阻断验收。Owner: java-platform。Follow-up: 若后续 Provider 注册机制改变，应补 L3/API 集成测试。

## Final Decision

本功能签收结论为 `accepted-with-risks`。

Stage 6 已完成窄端口 SPI 定义、兼容聚合接口保留、Registry typed views、session command/listing/worker-session/lookup 调用点收窄，并通过 session 定向回归与受影响 Provider reactor 回归。遗留风险均为后续架构收敛项，不阻断当前版本验收。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-25
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage6-task-query-provider-port-split-acceptance.md
- blocking_items: none
- follow_up_required: yes
