---
acceptance_scope: feature
version: 1.3.1-SNAPSHOT
target: OPT-001-stage10-langgraph-narrow-port-bean
doc_role: acceptance-record
doc_purpose: 说明本文件用于 Stage 10 LangGraph 窄端口 bean 迁移的功能级正式验收与签收结论记录
status: signed-off
decision: accepted-with-risks
signed_off_by: Codex
signed_off_at: 2026-06-26
reviewed_by: Codex
blocking_items: []
follow_up_required: yes
evidence_count: 7
---

# Feature Acceptance

## Document Purpose

- doc_type: acceptance
- intended_for: signoff-owner / reviewer / owning-module
- purpose: 记录 OPT-001 Stage 10 LangGraph narrow port bean migration 的功能级正式验收结论与证据摘要。

## Background

- Version: 1.3.1-SNAPSHOT
- Target: OPT-001 Stage 10 LangGraph narrow port bean migration
- Owner: langgraph-biz-worker / session-module
- Goal: 在 Stage 8 窄端口注入和 Stage 9 LangGraph worker-session 独立 provider 的基础上，让 LangGraph task lifecycle service 退出聚合 `TaskQueryProvider`，只按实际支持能力作为 lookup / command 窄端口注册。

## Acceptance Basis

- Requirement: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
- Implementation plan / progress: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage10-langgraph-narrow-port-bean.md`
- Quality gate: `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage10-implementation-quality.md`
- Coverage audit: `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage10-coverage-audit.md`
- Test records:
  - `mvn test -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphTaskServiceTest,LanggraphWorkerSessionQueryServiceTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 27 tests pass.
  - `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 64 tests pass.
  - `mvn test -pl session-module,addons/langgraph-biz-worker -am`: affected reactor pass; Surefire XML total 162 reports / 1149 tests / 0 failures / 0 errors / 0 skipped.
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
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage10-langgraph-narrow-port-bean.md`
- Implementation:
  - `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskService.java`
- Test:
  - `addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskServiceTest.java`
  - `addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/service/LanggraphWorkerSessionQueryServiceTest.java`
  - `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
  - `session-module/src/test/java/com/foggy/navigator/session/service/TaskQueryProviderRegistryTest.java`
  - affected Java reactor command listed above
- Quality:
  - `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage10-implementation-quality.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage10-coverage-audit.md`
- Experience:
  - N/A。该切片为 Java 后端 Provider 注册边界收敛，未新增或修改 UI 页面、表单、按钮、权限可见性或前端交互。

## Failed Items

- none

## Risks / Open Items

- Claude/Codex/Gemini 仍直接实现 `TaskQueryProvider` 聚合接口。Owner: java-platform / worker providers。Follow-up: 后续继续按风险迁移。
- LangGraph lookup 与 command 仍由同一个 task service bean 承接。Owner: langgraph-biz-worker。Follow-up: 如后续 service 体积继续膨胀，再拆物理 adapter 或内部 service。
- 未新增完整 Spring ApplicationContext 启动测试断言真实 provider bean list。Owner: session-module。Follow-up: 后续拆更多独立 bean 时补启动级回归。
- `TaskListingProvider` strictly typed method 与 worker-session typed DTO/envelope 仍未处理。Owner: navigator-spi / session-module。Follow-up: 后续阶段规划。

## Final Decision

本功能签收结论为 `accepted-with-risks`。

Stage 10 已完成 LangGraph task provider 从聚合 `TaskQueryProvider` 到实际支持的 lookup / command 窄端口迁移。测试证明 task service 不再作为 aggregate/listing/worker-session provider 暴露，Stage 9 的 worker-session 独立 provider 行为仍通过回归保护，session facade/registry 窄端口路由继续通过。遗留项均为后续架构收敛范围，不阻断当前版本验收。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage10-langgraph-narrow-port-bean-acceptance.md
- blocking_items: none
- follow_up_required: yes
