---
quality_scope: feature
quality_mode: pre-coverage-audit
version: 1.3.1-SNAPSHOT
target: OPT-001-stage6-task-query-provider-port-split
status: reviewed
decision: ready-for-coverage-audit
reviewed_by: Codex
reviewed_at: 2026-06-25
follow_up_required: yes
---

# Implementation Quality Gate

## Background

- 检查对象：OPT-001 Stage 6 `TaskQueryProvider` 窄端口治理。
- 当前阶段：实现完成，进入覆盖审计前质量检查。
- 本次目标：确认 SPI 拆分保持兼容，session 侧调用面开始依赖窄端口，且未改变外部 REST / OpenAPI / SDK payload。

## Check Basis

- requirement: `workitems/OPT-001-java-architecture-risk-governance.md`
- implementation plan: `workitems/OPT-001-stage6-task-query-provider-port-split.md`
- progress: Stage 6 execution check-in
- test result summary:
  - `mvn test -pl session-module -am "-Dtest=TaskQueryProviderRegistryTest,TaskDispatchFacadeTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 62 tests pass.
  - `mvn test -pl navigator-spi,session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am`: affected reactor pass; Surefire XML total 220 reports / 1520 tests / 0 failures / 0 errors / 0 skipped.

## Changed Surface

- changed files:
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
  - `session-module/src/test/java/com/foggy/navigator/session/service/TaskQueryProviderRegistryTest.java`
- changed modules: `navigator-spi`, `session-module`
- declared completed scope: 兼容式窄端口 SPI、registry typed views、session command/listing/worker-session/lookup 调用点收窄、定向与受影响 reactor 回归。

## Quality Checklist

- scope conformance: 符合 Stage 6 目标；未删除 `TaskQueryProvider` 或改变 provider bean 注入方式。
- code hygiene: 未发现 debug 代码、临时分支或无关重构。
- duplication and consolidation: capability fallback 仍集中在 `TaskQueryProviderRegistry`，未在 Facade/Router 复制筛选逻辑。
- complexity and abstraction: 新增端口是职责边界抽象，未引入新的运行时分派机制；复杂度可控。
- error handling and edge cases: legacy provider 空 capability fallback 保留；providerType 为空、task 归属查找和 unsupported default throw 语义未改变。
- readability and maintainability: Router 的命令路径已使用 `TaskCommandProvider`，Facade 的列表和 worker-session fan-out 已使用对应窄端口，调用意图更清晰。
- critical logic documentation: `TaskQueryProvider` 聚合接口注释已说明新代码应优先依赖窄端口。
- contract and compatibility: 对外 payload、Spring bean 类型和现有 provider 实现保持兼容；受影响 provider reactor 已通过。
- documentation and writeback: Stage 6 workitem、OPT 主文档和 README 需要同步回写本质量门与测试证据。
- test alignment: 定向测试覆盖 registry typed accessor 和 Facade 既有行为；affected reactor 覆盖 provider 编译和主要回归。
- release readiness: 未发现阻断进入覆盖审计的问题。

## Findings

- 未发现阻断性实现缺陷。
- `TaskCommandProvider#cancelTask` 仍保留 deprecated 兼容方法；本阶段只是从宽接口迁移到命令端口，取消链路彻底移除 legacy direct-provider fallback 需要另行规划。

## Risks / Follow-ups

- `TaskQueryProvider` 仍是兼容聚合接口，Provider 实现尚未迁移到按需声明独立窄端口；这是有意保留的兼容边界。
- `UnifiedSessionTaskProjectionService` 的 provider page/search envelope 仍是反射兼容读取，不在 Stage 6 范围。
- `TaskProviderPort#getCapabilities()` 仍使用空集合表示 legacy fallback，新 provider 若漏声明 capability 仍可能进入 fallback fan-out；后续可在 provider 接入规范中强化。

## Recommended Next Skills

- `foggy-test-coverage-audit`: 建议执行，确认 Stage 6 requirement、quality finding 与测试证据映射完整。
- `foggy-bug-regression-workflow`: 不需要；当前未发现缺陷修复场景。
- `plan-evaluator`: 不需要；当前方案与兼容性目标一致。
- back to implementation: 不需要；当前无阻断修复项。

## Decision

- decision: `ready-for-coverage-audit`
- can_enter_coverage_audit: yes
- follow_up_required: yes

## Lightweight Self-Check Note

- self_check_summary: 跨 `navigator-spi` 与 `session-module` 的 SPI 边界改动已通过自检，因改动跨模块并属于阶段性交付，已升级为正式质量门。
- self_check_decision: `needs-formal-quality-gate`
- self_check_follow_up: 执行 Stage 6 测试覆盖审计。
