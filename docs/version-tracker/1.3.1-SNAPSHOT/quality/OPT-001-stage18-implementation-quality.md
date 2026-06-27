---
quality_scope: feature
quality_mode: pre-coverage-audit
version: 1.3.1-SNAPSHOT
target: OPT-001 Stage 18 Task Command Cancel Direct Method
status: reviewed
decision: ready-with-risks
reviewed_by: Codex
reviewed_at: 2026-06-26
follow_up_required: yes
---

# Implementation Quality Gate

## Background

- 检查对象：`TaskCommandProvider#cancelTask(String, String)` legacy fallback 与 provider direct cancel 主调用契约。
- 当前阶段：Stage 18，位于 Stage 17 legacy provider method deprecation gate 之后。
- 本次目标：在不删除兼容方法、不改变 A2A / REST cancel 语义的前提下，为 provider command route 建立非 deprecated `cancelTaskDirect` 主路径。

## Check Basis

- requirement: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
- work item: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage18-task-command-cancel-direct-method.md`
- implementation plan: Stage 18 workitem `Implementation Plan`
- progress: Stage 18 workitem `Progress Tracking`
- execution check-in: Stage 18 workitem `Execution Check-in`
- test result summary: targeted regression 159 tests pass；affected reactor 1545 tests pass；session provider-route legacy cancel scan 无匹配；`TaskCommandProvider` legacy cancel 已无 `forRemoval=true`；`git diff --check` 无 whitespace error。

## Changed Surface

- changed files:
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskCommandProvider.java`
  - `session-module/src/main/java/com/foggy/navigator/session/service/TaskOperationRouter.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexBizTaskProvider.java`
  - `addons/gemini-worker-agent/src/main/java/com/foggy/navigator/gemini/worker/service/GeminiTaskService.java`
  - `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskService.java`
  - `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/adapter/LanggraphWorkerInnerA2aAgent.java`
  - `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
  - `session-module/src/test/java/com/foggy/navigator/session/service/TaskProviderLegacyContractTest.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexBizTaskProviderTest.java`
  - `addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskServiceTest.java`
  - `addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/adapter/LanggraphWorkerInnerA2aAgentTest.java`
- changed modules: `navigator-spi`、`session-module`、Claude/Codex/Gemini/LangGraph worker addons。
- declared completed scope: provider command cancel 主链路迁移到 `cancelTaskDirect`；legacy `cancelTask(String, String)` 保留兼容 wrapper，`forRemoval=false`。

## Quality Checklist

- scope conformance: pass。改动限定在 provider command cancel 契约、内置 provider direct method、session provider-route 和相关测试，没有扩大到 REST / A2A API 重命名。
- code hygiene: pass。未发现 debug、临时分支或无关 TODO；`git diff --check` 无 whitespace error，仅 CRLF normalization warnings。
- duplication and consolidation: pass with risk。内置 provider 都保留同形 legacy wrapper，这是 SPI 兼容策略需要；真实业务逻辑已集中到 direct method。
- complexity and abstraction: pass。本阶段没有新增状态机或分支复杂度；新增 default method 用于兼容旧插件 override。
- error handling and edge cases: pass。各 provider 原有权限校验、状态判断和 abort 行为保持原样。
- readability and maintainability: pass。`cancelTaskDirect` 与 A2A `cancelTask(String)` 的边界通过 Javadoc 和 route 改动明确。
- critical logic documentation: pass。workitem 记录了为什么不删除 legacy cancel、为什么暂不 `forRemoval=true`。
- contract and compatibility: pass with risk。外部旧插件若只 override legacy `cancelTask`，default `cancelTaskDirect` 会委托 legacy method，当前仍兼容；真正 removal 仍需迁移窗口。
- documentation and writeback: pass。Stage 18 workitem、quality、coverage、acceptance 和治理索引将统一回写。
- test alignment: pass。反射回归覆盖 SPI 契约；facade/provider tests 覆盖 direct route；affected reactor 覆盖受影响模块。
- release readiness: pass with risk。可进入覆盖审计与功能验收；不构成 legacy cancel removal。

## Findings

- finding 1: 未发现阻断实现问题。session provider-route 已调用 `cancelTaskDirect`，静态扫描确认不再直接调用 `provider.cancelTask(...)`。
- finding 2: 内置 provider 的真实取消逻辑已迁移到 `cancelTaskDirect`，legacy `cancelTask` 只做兼容委托。
- finding 3: A2A `A2aAgent#cancelTask(String)` / `InnerA2aAgent#cancelTask(String)` 未被重命名或改变语义。
- finding 4: `TaskCommandProvider#cancelTask(String, String)` 已从 `forRemoval=true` 收敛为 `forRemoval=false`，避免主链路未完全迁移前释放错误删除信号。

## Risks / Follow-ups

- risk 1: 外部插件、SDK 或非本仓调用方可能仍直接调用 legacy `cancelTask(String, String)`；removal 前必须提供迁移窗口与 release note。
- risk 2: 本阶段未运行根目录仓库级全量 `mvn test`；已运行 direct affected reactor，覆盖 Stage 18 涉及模块及其 `-am` 依赖。
- risk 3: 由于 legacy wrapper 仍保留，编译期可能继续出现预期的 deprecation warning；这是兼容迁移信号，不代表测试失败。
- follow-up 1: 后续 removal 必须另起 workitem，并至少在一个版本周期后评估是否恢复 `forRemoval=true`。
- follow-up 2: 生产 schema migration 工具化、Claude sync 本地投影 service 化仍属于后续治理项。

## Recommended Next Skills

- `foggy-test-coverage-audit`: required，检查 Stage 18 acceptance item 与测试证据映射。
- `foggy-bug-regression-workflow`: not required，当前未发现 BUG。
- `plan-evaluator`: optional，进入 legacy command cancel removal 阶段前可评估兼容策略。
- back to implementation: not required for Stage 18；后续另起治理项。

## Decision

- decision: `ready-with-risks`
- can_enter_coverage_audit: yes
- follow_up_required: yes

## Lightweight Self-Check Note

- self_check_summary: scope、兼容边界、测试与文档已闭环；剩余风险均为后续 removal 和外部迁移窗口治理项，不阻断本阶段验收。
- self_check_decision: needs-formal-quality-gate
- self_check_follow_up: 已升级为正式 pre-coverage-audit 质量门并完成本记录。
