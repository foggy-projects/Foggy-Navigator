---
quality_scope: feature
quality_mode: pre-coverage-audit
version: 1.3.2-SNAPSHOT
target: OPT-001-codex-biz-route-readiness
status: reviewed
decision: ready-for-coverage-audit
reviewed_by: Codex
reviewed_at: 2026-06-29
follow_up_required: no
---

# Implementation Quality Gate

## Background

- 检查对象：`OPT-001: Codex Biz Route Readiness`
- 当前阶段：implementation completed, ready for coverage audit
- 本次目标：确认 `codex-biz-worker` readiness 的实现和测试回写已经收口，可进入测试覆盖审计。

## Check Basis

- requirement: `docs/version-tracker/1.3.2-SNAPSHOT/workitems/OPT-001-codex-biz-route-readiness.md`
- bug work item: N/A
- implementation plan: 同一 workitem 的 `Implementation Plan`
- progress: 同一 workitem 的 `Progress Tracking`
- execution check-in: 同一 workitem 的 `Execution Check-in`
- test result summary:
  - `npm --prefix tools/codex-agent-worker run typecheck`: pass, 2026-06-29
  - `npm --prefix tools/codex-agent-worker test`: pass, 70 tests, 2026-06-29
  - `mvn test -pl navigator-common -am "-Dtest=ProviderRouteRegistryTest" ...`: pass, 9 tests, 2026-06-29
  - `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest" ...`: pass, 59 tests, 2026-06-29
  - `mvn test -pl addons/codex-worker-agent -am "-Dtest=CodexBizTaskProviderTest,CodexTaskServiceTest,CodexStreamRelayTest,CodexWorkerClientTest,CodexTaskControllerTest" ...`: pass, 44 tests, 2026-06-29
  - `git diff --check`: pass, 2026-06-29

## Changed Surface

- changed files:
  - `docs/version-tracker/1.3.2-SNAPSHOT/README.md`
  - `docs/version-tracker/1.3.2-SNAPSHOT/workitems/OPT-001-codex-biz-route-readiness.md`
  - `tools/codex-agent-worker/docs/upstream-integration.md`
  - `tools/codex-agent-worker/src/models.ts`
  - `tools/codex-agent-worker/src/codex/sdk-wrapper.ts`
  - `tools/codex-agent-worker/src/routes/query.ts`
  - `tools/codex-agent-worker/src/routes/health.ts`
  - `tools/codex-agent-worker/tests/health.test.ts`
  - `tools/codex-agent-worker/scripts/codex-biz-smoke.ps1`
  - `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexBizTaskProviderTest.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexTaskServiceTest.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/client/CodexWorkerClientTest.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/controller/CodexTaskControllerTest.java`
- changed modules:
  - `tools/codex-agent-worker`
  - `session-module`
  - `addons/codex-worker-agent`
  - `docs/version-tracker/1.3.2-SNAPSHOT`
- declared completed scope:
  - Freeze Codex Biz invocation contract.
  - Protect session direct create / resume provider routing.
  - Protect Java addon provider filtering, Biz parameter normalization, and Worker body passthrough.
  - Expose non-sensitive Worker readiness diagnostics.
  - Provide smoke helper with live query opt-in.

## Quality Checklist

- scope conformance: pass. 改动面围绕 `codex-biz-worker` readiness、测试保护和文档证据，没有发现将该路线扩大成 LangGraph BizWorker 或独立可发现 Agent 的实现偏移。
- code hygiene: pass. 未发现阻断性的临时代码、debugger、未闭合 TODO/FIXME。现有 `console.log` 是 Worker 启动、请求摘要和 Codex 执行诊断输出，未输出 API key、auth 文件内容或 scoped home 真实路径。
- duplication and consolidation: pass. Worker 侧将缺少 `CODEX_BIZ_HOME_ROOT` 的稳定错误收敛到常量；Java addon 侧通过 provider-filtered service 方法和测试保护隔离 Biz / non-Biz 视图，未发现需要本阶段继续抽象的重复实现。
- complexity and abstraction: pass. 现有 provider route、session-bound resume 和 Worker scoped home 解析仍能通过既有 service/provider 分层表达；未出现需要新增策略或状态机才能继续维护的复杂度。
- error handling and edge cases: pass. 覆盖了缺 root 稳定错误、relative root 拒绝、`private_account_id` alias、`codexPolicy` 包装字段、普通 Codex 请求不携带 Biz 字段、session-bound provider 覆盖显式 provider 等边界。
- readability and maintainability: pass. 测试名能直接表达保护行为，文档明确区分 contract、non-goals、verification 和 remaining risk。
- critical logic documentation: pass. 上游接入文档说明了 scoped `CODEX_HOME`、`CODEX_BIZ_HOME_ROOT`、health readiness、auth 种子化和 opt-in smoke 的约束。
- contract and compatibility: pass. `codex-biz-worker` 保持复用 `OPENAI_CODEX` 模型配置，不破坏 `codex-worker`；普通 Codex Worker body 不被 Biz 字段污染。
- documentation and writeback: pass. workitem 已记录 implementation plan、progress、execution check-in、test status 和 acceptance readiness；本质量门禁补齐正式 quality 记录。
- test alignment: pass. 当前测试覆盖与改动面匹配，包含 Worker unit、provider registry、session routing、Codex addon regression 和 prior live smoke evidence。
- release readiness: pass with operational preflight. 代码与测试可进入覆盖审计；生产部署仍必须在目标环境配置 `CODEX_BIZ_HOME_ROOT` 并保护 scoped `auth.json`。

## Findings

- blocking findings: none
- non-blocking findings:
  - `CODEX_BIZ_HOME_ROOT` 是部署环境配置，不应写入仓库；发布前需要在目标 Worker 环境完成配置和 health readiness 验证。
  - Live actor A/B smoke 属于 opt-in 真实 Codex 调用，已有 2026-06-28 证据；签收前自动化回归未重复触发付费 live query。

## Risks / Follow-ups

- risk: 生产环境若未配置 `CODEX_BIZ_HOME_ROOT`，带 `codex_home_key` 的请求会按设计返回稳定 403；这是部署前置项，不是实现 blocker。
- risk: scoped home 中的 `auth.json` 属于凭证材料，必须由部署环境权限保护。
- follow-up: 在目标环境上线前执行 workitem 的 Deployment Preflight Checklist。

## Recommended Next Skills

- `foggy-test-coverage-audit`: proceed. 本质量门禁结论为 `ready-for-coverage-audit`。
- `foggy-bug-regression-workflow`: not needed. 未发现需要转 BUG 流程的问题。
- `plan-evaluator`: not needed. 当前方案与既定 non-goals 一致，无需重新评审架构。
- back to implementation: not needed before coverage audit.

## Decision

- decision: ready-for-coverage-audit
- can_enter_coverage_audit: yes
- follow_up_required: no

## Lightweight Self-Check Note

- self_check_summary: scope 已按 workitem 1~5 收口，自动化回归和文档回写完成。
- self_check_decision: needs-formal-quality-gate
- self_check_follow_up: 本文件即正式 `pre-coverage-audit` 质量门禁记录。
