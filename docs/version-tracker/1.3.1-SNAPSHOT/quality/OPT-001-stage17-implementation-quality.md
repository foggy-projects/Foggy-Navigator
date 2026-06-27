---
quality_scope: feature
quality_mode: pre-coverage-audit
version: 1.3.1-SNAPSHOT
target: OPT-001 Stage 17 Legacy Provider Method Deprecation Gate
status: reviewed
decision: ready-with-risks
reviewed_by: Codex
reviewed_at: 2026-06-26
follow_up_required: yes
---

# Implementation Quality Gate

## Background

- 检查对象：`TaskListingProvider` / `WorkerSessionQueryProvider` legacy listing 与 worker-session 方法 deprecation gate。
- 当前阶段：Stage 17，位于 Stage 14 typed listing/search 与 Stage 15 typed worker-session 主链路迁移之后。
- 本次目标：在不删除兼容方法、不改变 REST payload 的前提下，让 legacy SPI 方法和内置 provider wrapper 明确进入 deprecated 迁移窗口。

## Check Basis

- requirement: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
- work item: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage17-legacy-provider-method-deprecation.md`
- implementation plan: Stage 17 workitem `Implementation Plan`
- progress: Stage 17 workitem `Progress Tracking`
- execution check-in: Stage 17 workitem `Execution Check-in`
- test result summary: targeted regression 131 tests pass；affected reactor 1528 tests pass；static scan 无生产 fan-out legacy 调用；`git diff --check` 无 whitespace error。

## Changed Surface

- changed files:
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskListingProvider.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/WorkerSessionQueryProvider.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeWorkerSessionQueryService.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexBizTaskProvider.java`
  - `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphWorkerSessionQueryService.java`
  - `session-module/src/test/java/com/foggy/navigator/session/service/TaskProviderLegacyContractTest.java`
- changed modules: `navigator-spi`、`session-module`、Claude/Codex/LangGraph worker addons。
- declared completed scope: legacy methods only marked deprecated with `forRemoval=false`；typed methods and REST compatibility retained。

## Quality Checklist

- scope conformance: pass。改动限定在 SPI legacy methods、provider legacy wrappers、deprecation contract test 与版本文档，没有扩大到 REST API 或删除兼容方法。
- code hygiene: pass。未发现 debug、临时分支或无关 TODO；`git diff --check` 无 whitespace error，仅既有 CRLF normalization warnings。
- duplication and consolidation: pass with risk。deprecation annotation 在多个 provider wrapper 重复出现，但这是兼容提示而非业务逻辑复制；当前不需要引入额外 helper。
- complexity and abstraction: pass。本阶段没有新增业务分支或状态机复杂度。
- error handling and edge cases: pass。未改动异常处理、payload 转换或边界输入处理。
- readability and maintainability: pass。SPI Javadoc 明确指出 typed replacement，后续调用方可直接迁移。
- critical logic documentation: pass。Stage 17 workitem 已记录 removal gate，说明为何 `forRemoval=false` 且本阶段不删除。
- contract and compatibility: pass with risk。保留 binary/source 兼容 surface；外部插件仍可能继续调用 legacy 方法，需 release note 与迁移窗口后才能 removal。
- documentation and writeback: pass。workitem、README、治理主文档、quality、coverage、acceptance 均已纳入收口。
- test alignment: pass。新增反射回归覆盖 SPI deprecation 契约，既有 facade/provider 测试覆盖行为不变，affected reactor 覆盖直接依赖链。
- release readiness: pass with risk。可进入覆盖审计与功能验收；不构成正式 removal。

## Findings

- finding 1: 未发现阻断实现问题。legacy 方法没有删除，`forRemoval=false` 与兼容窗口目标一致。
- finding 2: provider wrapper deprecation 与 SPI deprecation 已同步，避免直接依赖具体 service 时绕过迁移提示。
- finding 3: 当前仍会出现预期的 deprecation 编译提示，属于本阶段迁移信号；不影响测试通过。

## Risks / Follow-ups

- risk 1: 外部插件、SDK 或未纳入本仓测试的调用方可能仍依赖 legacy 方法；后续 removal 前必须单独做兼容公告和迁移窗口。
- risk 2: 本阶段未运行根目录仓库级全量 `mvn test`，以 affected reactor 覆盖直接依赖链。
- follow-up 1: 后续 removal 必须另起 workitem，并至少在一个版本周期后评估 `forRemoval=true`。
- follow-up 2: `TaskCommandProvider#cancelTask` deprecated fallback、生产 schema migration 工具化、Claude session projection service 化仍属于后续治理项。

## Recommended Next Skills

- `foggy-test-coverage-audit`: required，检查 Stage 17 requirement 与测试证据映射。
- `foggy-bug-regression-workflow`: not required，当前未发现 BUG。
- `plan-evaluator`: optional，进入 removal 阶段前可评估外部兼容窗口和测试范围。
- back to implementation: not required for Stage 17；后续另起 Stage 18。

## Decision

- decision: `ready-with-risks`
- can_enter_coverage_audit: yes
- follow_up_required: yes

## Lightweight Self-Check Note

- self_check_summary: scope、兼容边界、测试与文档已闭环；剩余风险均为后续 removal 前置条件，不阻断本阶段验收。
- self_check_decision: needs-formal-quality-gate
- self_check_follow_up: 已升级为正式 pre-coverage-audit 质量门并完成本记录。
