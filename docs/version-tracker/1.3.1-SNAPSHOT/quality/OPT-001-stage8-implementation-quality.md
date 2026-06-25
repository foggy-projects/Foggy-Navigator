---
quality_scope: feature
quality_mode: pre-coverage-audit
version: 1.3.1-SNAPSHOT
target: OPT-001-stage8-provider-port-injection
status: reviewed
decision: ready-with-risks
reviewed_by: Codex
reviewed_at: 2026-06-25
follow_up_required: yes
---

# Implementation Quality Gate

## Background

- 检查对象：OPT-001 Stage 8 Provider port 注入收窄。
- 当前阶段：实现完成，进入覆盖审计前质量检查。
- 本次目标：确认 session 构造边界与 registry 内部集合不再以 `List<TaskQueryProvider>` 作为唯一 Provider 注入形态，同时保持现有聚合 Provider 实现兼容。

## Check Basis

- requirement: `workitems/OPT-001-java-architecture-risk-governance.md`
- implementation plan: `workitems/OPT-001-stage8-provider-port-injection.md`
- progress: Stage 8 execution check-in
- test result summary:
  - `mvn test -pl session-module -am "-Dtest=TaskQueryProviderRegistryTest,TaskDispatchFacadeTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 63 tests pass.
  - first affected reactor run exposed a stale constructor compatibility issue in Claude adapter tests; fixed by adding a deprecated compatibility constructor and migrating worker adapters to the lookup-port constructor.
  - `mvn test -pl addons/claude-worker-agent -am "-Dtest=ClaudeWorkerAgentProviderTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 10 tests pass.
  - `mvn test -pl navigator-spi,session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am`: affected reactor pass; Surefire XML total 221 reports / 1525 tests / 0 failures / 0 errors / 0 skipped.
  - `git diff --check`: no whitespace errors; only CRLF normalization warnings.

## Changed Surface

- changed files:
  - `session-module/src/main/java/com/foggy/navigator/session/service/TaskQueryProviderRegistry.java`
  - `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
  - `session-module/src/main/java/com/foggy/navigator/session/agent/AbortCoordinatingA2aAgent.java`
  - `session-module/src/test/java/com/foggy/navigator/session/service/TaskQueryProviderRegistryTest.java`
  - `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/adapter/ClaudeWorkerAgentProvider.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/adapter/CodexWorkerAgentProvider.java`
  - `addons/gemini-worker-agent/src/main/java/com/foggy/navigator/gemini/worker/adapter/GeminiWorkerAgentProvider.java`
- changed modules: `session-module`, `addons/claude-worker-agent`, `addons/codex-worker-agent`, `addons/gemini-worker-agent`
- declared completed scope: constructor port-list injection、registry port-list internal storage、lookup/command separated routing support、abort coordination lookup-port dependency、compatibility constructor、targeted and affected reactor regression.

## Quality Checklist

- scope conformance: 符合 Stage 8 目标；未删除 `TaskQueryProvider` 聚合接口，未改变 REST / OpenAPI / SDK payload。
- code hygiene: 未发现 debug 代码、临时分支或无关重构。
- duplication and consolidation: registry capability filtering 已抽成按端口列表工作的泛型 helper，避免四类端口重复过滤逻辑。
- complexity and abstraction: registry 仍保持轻量集合与查找职责，未引入额外框架；lookup 与 command 通过 providerType 连接，复杂度可控。
- error handling and edge cases: `findCommandProviderForTask` 支持 lookup provider 与 command provider 分离；未找到 task 或 command port 时返回 empty。
- readability and maintainability: `TaskDispatchFacade` 构造参数直接表达四类端口依赖，后续独立 bean 接入路径更明确。
- critical logic documentation: `AbortCoordinatingA2aAgent` 保留 deprecated 聚合接口兼容构造器，兼容原因通过 deprecated 标记体现。
- contract and compatibility: 现有 Provider 继续实现 `TaskQueryProvider`，可被 Spring 作为四类窄端口注入；worker adapter 已改用 lookup-port 构造。
- documentation and writeback: Stage 8 workitem、OPT 主文档、README、质量门、覆盖审计和验收记录同步回写。
- test alignment: registry 分离 lookup/command 单测、facade 既有 54 个聚合回归、Claude adapter 10 个回归和受影响 reactor 均已通过。
- release readiness: 未发现阻断覆盖审计的问题。

## Findings

- 未发现阻断性实现缺陷。
- 初次 affected reactor 暴露 `AbortCoordinatingA2aAgent` 构造签名变化对 Claude adapter 测试的二进制兼容风险。已通过保留 deprecated 兼容构造器并迁移 adapter 调用点修复，复跑 Claude targeted 和 affected reactor 均通过。

## Risks / Follow-ups

- `TaskQueryProvider` 聚合接口仍作为兼容入口存在，现有 Provider bean 仍一次性实现四类端口；后续可逐步拆成独立窄端口 bean。
- 当前没有专门的 Spring ApplicationContext 测试只验证四类泛型列表注入；受影响 reactor 已覆盖编译和模块回归，但生产启动级验证仍可在后续阶段补强。
- `TaskListingProvider` 方法签名仍返回 `Object`，Stage 8 不处理 strictly typed listing method 迁移。

## Recommended Next Skills

- `foggy-test-coverage-audit`: 建议执行，确认 Stage 8 acceptance criteria 与测试证据映射完整。
- `foggy-bug-regression-workflow`: 不需要；当前缺陷已在本阶段内修复并复跑通过，不形成独立 BUG。
- `plan-evaluator`: 不需要；当前方案与兼容性目标一致。
- back to implementation: 不需要；当前无阻断修复项。

## Decision

- decision: `ready-with-risks`
- can_enter_coverage_audit: yes
- follow_up_required: yes

## Lightweight Self-Check Note

- self_check_summary: 跨 session registry/facade、abort wrapper 和三个 worker adapter 的构造契约改动已通过自检；因涉及 SPI 注入边界和兼容构造，已升级为正式质量门。
- self_check_decision: `needs-formal-quality-gate`
- self_check_follow_up: 执行 Stage 8 测试覆盖审计。
