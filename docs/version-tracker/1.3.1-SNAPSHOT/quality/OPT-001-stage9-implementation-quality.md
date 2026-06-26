---
quality_scope: feature
quality_mode: pre-coverage-audit
version: 1.3.1-SNAPSHOT
target: OPT-001-stage9-langgraph-worker-session-split
status: reviewed
decision: ready-with-risks
reviewed_by: Codex
reviewed_at: 2026-06-25
follow_up_required: yes
---

# Implementation Quality Gate

## Background

- 检查对象：OPT-001 Stage 9 LangGraph worker-session 端口拆分。
- 当前阶段：实现完成，进入覆盖审计前质量检查。
- 本次目标：确认 LangGraph worker-session 查询能力已从任务生命周期服务拆出，作为独立 `WorkerSessionQueryProvider` bean 接入 session fan-out，同时保持外部 Map payload、异常语义和 task lifecycle 行为兼容。

## Check Basis

- requirement: `workitems/OPT-001-java-architecture-risk-governance.md`
- implementation plan: `workitems/OPT-001-stage9-langgraph-worker-session-split.md`
- progress: Stage 9 execution check-in
- test result summary:
  - `mvn test -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphWorkerSessionQueryServiceTest,LanggraphTaskServiceTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 26 tests pass.
  - `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 64 tests pass.
  - `mvn test -pl session-module,addons/langgraph-biz-worker -am`: affected reactor pass; Surefire XML total 162 reports / 1148 tests / 0 failures / 0 errors / 0 skipped.
  - `git diff --check`: no whitespace errors; only CRLF normalization warnings.

## Changed Surface

- changed files:
  - `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskService.java`
  - `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphWorkerSessionQueryService.java`
  - `addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskServiceTest.java`
  - `addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/service/LanggraphWorkerSessionQueryServiceTest.java`
  - `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
- changed modules: `addons/langgraph-biz-worker`, `session-module`
- declared completed scope: LangGraph worker-session list/count/messages/sync 迁移到独立 provider、capability 声明迁移、原 task service 行为回归、session facade 独立 worker-session provider 接入回归。

## Quality Checklist

- scope conformance: 符合 Stage 9 目标；未修改 REST / OpenAPI / SDK payload，未改 `WorkerSessionQueryProvider` SPI 签名。
- code hygiene: 未发现 debug 代码、临时分支或无关重构。
- duplication and consolidation: worker-session ownership 校验与 Map projection 已集中到新 service；`LanggraphTaskService` 不再重复承载 worker-session SPI 方法。
- complexity and abstraction: 新 service 只承担 worker-session 查询端口，依赖和职责边界清晰；未引入额外分派框架。
- error handling and edge cases: worker ownership 和 session ownership 校验继续通过 `IllegalArgumentException` 表达 not found；新增空 worker 防护，避免 worker 查询返回 null 时 NPE。
- readability and maintainability: task lifecycle capability 与 worker-session capability 分离，后续继续拆独立 provider bean 有可参考样例。
- critical logic documentation: 本阶段无复杂业务算法；Stage 9 workitem 记录了为何不移除 `SessionMessageRepository` 主服务依赖，因为它仍用于 recent conversation 和用户 prompt 持久化。
- contract and compatibility: worker-session Map 字段保持兼容；`LanggraphTaskService` 仍实现兼容聚合 `TaskQueryProvider`，但不声明 worker-session capability。
- documentation and writeback: Stage 9 workitem、OPT 主文档、README、质量门、覆盖审计和验收记录同步回写。
- test alignment: LangGraph provider 行为测试、session fan-out 独立 provider 测试和受影响 reactor 回归均匹配改动面。
- release readiness: 未发现阻断覆盖审计的问题。

## Findings

- 未发现阻断性实现缺陷。
- 初始计划中“移除 `SessionMessageRepository` 依赖”的表述过宽；实现复核确认该依赖仍被 `LanggraphTaskService` 用于 recent conversation 查询和用户 prompt 持久化，因此实际只迁出 worker-session 查询用法。该计划偏差已在 Stage 9 workitem 中修正，不影响实现目标。

## Risks / Follow-ups

- 当前只完成 LangGraph worker-session 独立 provider 样例；Claude/Codex/Gemini 仍主要通过聚合 `TaskQueryProvider` bean 兼容四类端口。
- 未新增完整 Spring ApplicationContext 启动测试专门验证 `LanggraphTaskService` 与 `LanggraphWorkerSessionQueryService` 两个 bean 同时被四类端口列表注入；当前通过编译、单测构造和 affected reactor 间接覆盖，不阻断验收。
- worker-session payload 仍是 Map 字段集合，typed DTO / typed envelope 不在本阶段范围。
- `TaskQueryProvider` 聚合接口仍保留，后续仍需继续做 provider 独立窄端口 bean 迁移和兼容接口退场规划。

## Recommended Next Skills

- `foggy-test-coverage-audit`: 建议执行，确认 Stage 9 acceptance criteria 与测试证据映射完整。
- `foggy-bug-regression-workflow`: 当前不需要；本阶段未发现需要独立建 BUG 的回归。
- `plan-evaluator`: 当前不需要；实现与 Stage 8 后续规划一致。
- back to implementation: 当前不需要；无阻断修复项。

## Decision

- decision: `ready-with-risks`
- can_enter_coverage_audit: yes
- follow_up_required: yes

## Lightweight Self-Check Note

- self_check_summary: Stage 9 为跨 LangGraph service 与 session facade 测试的职责拆分，涉及 Spring provider bean 边界和 capability 声明迁移，已升级为正式质量门。
- self_check_decision: `needs-formal-quality-gate`
- self_check_follow_up: 执行 Stage 9 测试覆盖审计。
