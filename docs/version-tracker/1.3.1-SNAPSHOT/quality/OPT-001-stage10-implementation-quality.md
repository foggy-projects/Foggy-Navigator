---
quality_scope: feature
quality_mode: pre-coverage-audit
version: 1.3.1-SNAPSHOT
target: OPT-001-stage10-langgraph-narrow-port-bean
status: reviewed
decision: ready-with-risks
reviewed_by: Codex
reviewed_at: 2026-06-26
follow_up_required: yes
---

# Implementation Quality Gate

## Background

- 检查对象：OPT-001 Stage 10 LangGraph narrow port bean migration。
- 当前阶段：实现完成，进入覆盖审计前质量检查。
- 本次目标：确认 `LanggraphTaskService` 已退出聚合 `TaskQueryProvider`，仅按实际支持能力作为 `TaskLookupProvider` 与 `TaskCommandProvider` 注册，同时保持 Stage 9 已拆出的 `LanggraphWorkerSessionQueryService` 独立承接 worker-session 查询。

## Check Basis

- requirement: `workitems/OPT-001-java-architecture-risk-governance.md`
- implementation plan: `workitems/OPT-001-stage10-langgraph-narrow-port-bean.md`
- progress: Stage 10 execution check-in
- test result summary:
  - `mvn test -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphTaskServiceTest,LanggraphWorkerSessionQueryServiceTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 27 tests pass.
  - `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 64 tests pass.
  - `mvn test -pl session-module,addons/langgraph-biz-worker -am`: affected reactor pass; Surefire XML total 162 reports / 1149 tests / 0 failures / 0 errors / 0 skipped.
  - `git diff --check`: no whitespace errors; only CRLF normalization warnings.

## Changed Surface

- changed files:
  - `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskService.java`
  - `addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskServiceTest.java`
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage10-langgraph-narrow-port-bean.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/README.md`
- changed modules: `addons/langgraph-biz-worker`, `session-module` test coverage
- declared completed scope: LangGraph task provider 从聚合接口迁移为 lookup / command 窄端口注册，类型边界测试与受影响 reactor 回归完成。

## Quality Checklist

- scope conformance: 符合 Stage 10 目标；未修改 REST / OpenAPI / SDK payload，未改 SPI 方法签名。
- code hygiene: 未发现 debug 代码、临时分支或无关重构。
- duplication and consolidation: 本阶段没有新增重复逻辑；仅收窄实现接口，复用现有 task lifecycle 方法。
- complexity and abstraction: 改动降低了 Spring provider 列表中的无效端口暴露；没有引入额外适配层或复杂抽象。
- error handling and edge cases: create/cancel/delete/lookup 原有异常语义未改。
- readability and maintainability: `LanggraphTaskService` 类注释和 implements 列表能直接表达实际端口职责；Stage 9 worker-session service 继续保持独立。
- critical logic documentation: 本阶段为接口边界收敛，无复杂业务算法；Stage 10 workitem 记录了不拆物理 lookup/command bean 的非目标。
- contract and compatibility: 聚合 `TaskQueryProvider` 仍保留给其他 Provider；LangGraph task service 不再被 listing / worker-session 列表误收集。
- documentation and writeback: Stage 10 workitem、README、质量门、覆盖审计和验收记录已同步回写。
- test alignment: 类型边界单测、LangGraph focused regression、session registry/facade regression 和 affected reactor 与改动面匹配。
- release readiness: 未发现阻断覆盖审计的问题。

## Findings

- 未发现阻断性实现缺陷。
- 当前实现只把 LangGraph task service 从聚合接口退到 lookup / command 窄端口，没有进一步拆成两个物理 bean。这符合本阶段 Non-Goals，也避免在 task lifecycle service 内部尚未继续分解前制造低收益代理层。

## Risks / Follow-ups

- Claude/Codex/Gemini 仍直接实现 `TaskQueryProvider` 聚合接口，后续仍会被四类端口列表同时收集。
- LangGraph lookup 与 command 仍由同一个 task service bean 承接；如果后续要进一步降低服务体积，可另起阶段拆物理 adapter 或拆 service 内部职责。
- 未新增专门的 Spring ApplicationContext 注入断言测试；当前通过类型边界单测、session focused regression 和 affected reactor 间接覆盖。
- `TaskListingProvider` strictly typed method 与 worker-session typed DTO/envelope 仍未处理。

## Recommended Next Skills

- `foggy-test-coverage-audit`: 建议执行，确认 Stage 10 acceptance criteria 与测试证据映射完整。
- `foggy-bug-regression-workflow`: 当前不需要；本阶段未发现需要独立建 BUG 的回归。
- `plan-evaluator`: 当前不需要；实现与 Stage 10 scope 一致。
- back to implementation: 当前不需要；无阻断修复项。

## Decision

- decision: `ready-with-risks`
- can_enter_coverage_audit: yes
- follow_up_required: yes

## Lightweight Self-Check Note

- self_check_summary: Stage 10 为 provider 注册边界收敛，涉及 Spring bean 类型列表和 SPI 兼容边界，已升级为正式质量门。
- self_check_decision: `needs-formal-quality-gate`
- self_check_follow_up: 执行 Stage 10 测试覆盖审计。
