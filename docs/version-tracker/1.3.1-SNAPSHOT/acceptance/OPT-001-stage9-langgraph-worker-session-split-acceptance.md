---
acceptance_scope: feature
version: 1.3.1-SNAPSHOT
target: OPT-001-stage9-langgraph-worker-session-split
doc_role: acceptance-record
doc_purpose: 说明本文件用于 Stage 9 LangGraph worker-session 端口拆分的功能级正式验收与签收结论记录
status: signed-off
decision: accepted-with-risks
signed_off_by: Codex
signed_off_at: 2026-06-25
reviewed_by: Codex
blocking_items: []
follow_up_required: yes
evidence_count: 7
---

# Feature Acceptance

## Document Purpose

- doc_type: acceptance
- intended_for: signoff-owner / reviewer / owning-module
- purpose: 记录 OPT-001 Stage 9 LangGraph worker-session 端口拆分的功能级正式验收结论与证据摘要。

## Background

- Version: 1.3.1-SNAPSHOT
- Target: OPT-001 Stage 9 LangGraph worker-session 端口拆分
- Owner: langgraph-biz-worker / session-module
- Goal: 在 Stage 8 已支持四类窄端口列表注入的基础上，为 LangGraph 落地真实独立 `WorkerSessionQueryProvider` bean，使 task lifecycle 服务不再承载 worker-session 查询 capability。

## Acceptance Basis

- Requirement: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
- Implementation plan / progress: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage9-langgraph-worker-session-split.md`
- Quality gate: `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage9-implementation-quality.md`
- Coverage audit: `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage9-coverage-audit.md`
- Test records:
  - `mvn test -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphWorkerSessionQueryServiceTest,LanggraphTaskServiceTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 26 tests pass.
  - `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 64 tests pass.
  - `mvn test -pl session-module,addons/langgraph-biz-worker -am`: affected reactor pass; Surefire XML total 162 reports / 1148 tests / 0 failures / 0 errors / 0 skipped.
  - `git diff --check`: no whitespace errors; only CRLF normalization warnings.

## Checklist

- [x] scope 内功能点已全部交付
- [x] 原始 acceptance criteria 已逐项覆盖
- [x] 关键测试已通过
- [x] 体验验证已完成，或明确标记 `N/A`
- [x] 文档、配置、依赖项已闭环

## Evidence

- Requirement:
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage9-langgraph-worker-session-split.md`
- Implementation:
  - `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskService.java`
  - `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphWorkerSessionQueryService.java`
- Test:
  - `addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskServiceTest.java`
  - `addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/service/LanggraphWorkerSessionQueryServiceTest.java`
  - `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
  - affected Java reactor command listed above
- Quality:
  - `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage9-implementation-quality.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage9-coverage-audit.md`
- Experience:
  - N/A。该切片为 Java 后端 Provider 职责拆分，未新增或修改 UI 页面、表单、按钮、权限可见性或前端交互。

## Failed Items

- none

## Risks / Open Items

- 当前只完成 LangGraph worker-session 独立 provider 样例。Owner: java-platform / worker providers。Follow-up: 后续按风险继续迁移 Claude/Codex/Gemini 或其他端口到独立窄 bean。
- 未新增完整 Spring ApplicationContext 启动测试验证独立 LangGraph provider bean 的真实注入列表。Owner: session-module。Follow-up: 后续拆更多独立 bean 或引入第三方 Provider 时补启动级回归。
- worker-session payload 仍为 Map 字段集合。Owner: navigator-spi / session-module。Follow-up: 可另起 typed worker-session DTO / envelope 兼容迁移。
- `TaskQueryProvider` 聚合接口仍保留。Owner: navigator-spi。Follow-up: 后续阶段继续规划兼容接口退场。

## Final Decision

本功能签收结论为 `accepted-with-risks`。

Stage 9 已完成 LangGraph worker-session 查询端口拆分：`LanggraphWorkerSessionQueryService` 作为独立 `WorkerSessionQueryProvider` bean 承接 list/count/messages/sync，`LanggraphTaskService` 不再声明 worker-session capability，session facade 已补独立 provider 接入回归。遗留项均为后续架构收敛范围，不阻断当前版本验收。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-25
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage9-langgraph-worker-session-split-acceptance.md
- blocking_items: none
- follow_up_required: yes
