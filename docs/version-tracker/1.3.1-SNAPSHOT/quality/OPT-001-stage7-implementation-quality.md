---
quality_scope: feature
quality_mode: pre-coverage-audit
version: 1.3.1-SNAPSHOT
target: OPT-001-stage7-provider-listing-envelope
status: reviewed
decision: ready-with-risks
reviewed_by: Codex
reviewed_at: 2026-06-25
follow_up_required: yes
---

# Implementation Quality Gate

## Background

- 检查对象：OPT-001 Stage 7 Provider listing/search typed envelope 治理。
- 当前阶段：实现完成，进入覆盖审计前质量检查。
- 本次目标：确认 Provider listing/search 聚合链路有 typed envelope 主路径，`UnifiedSessionTaskProjectionService` 不再主要依赖 Map key / JavaBean getter 反射读取，同时保持旧 Provider 兼容。

## Check Basis

- requirement: `workitems/OPT-001-java-architecture-risk-governance.md`
- implementation plan: `workitems/OPT-001-stage7-provider-listing-envelope.md`
- progress: Stage 7 execution check-in
- test result summary:
  - `mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent -am "-Dtest=UnifiedSessionTaskProjectionServiceTest,TaskDispatchFacadeTest,ClaudeTaskServiceTest,CodexTaskServiceTest,CodexBizTaskProviderTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: session-module 58 tests pass; codex-worker-agent 28 tests pass; Claude module compile and reactor participation pass.
  - `mvn test -pl navigator-spi,session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am`: affected reactor pass; Surefire XML total 221 reports / 1524 tests / 0 failures / 0 errors / 0 skipped.
  - `git diff --check`: no whitespace errors; only CRLF normalization warnings.

## Changed Surface

- changed files:
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskPageResult.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskSearchResult.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskListingProvider.java`
  - `session-module/src/main/java/com/foggy/navigator/session/service/UnifiedSessionTaskProjectionService.java`
  - `session-module/src/test/java/com/foggy/navigator/session/service/UnifiedSessionTaskProjectionServiceTest.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/ClaudeTaskController.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexBizTaskProvider.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexTaskServiceTest.java`
- changed modules: `navigator-spi`, `session-module`, `addons/claude-worker-agent`, `addons/codex-worker-agent`
- declared completed scope: typed listing/search envelope、session projection typed-first 解析、Claude/Codex/Codex Biz SPI 返回迁移、legacy fallback 回归测试、受影响 reactor 回归。

## Quality Checklist

- scope conformance: 符合 Stage 7 目标；未改变 REST / OpenAPI / SDK 对外字段。
- code hygiene: 未发现 debug 代码、临时分支或无关重构。
- duplication and consolidation: listing/search envelope 结构集中到 `TaskPageResult` / `TaskSearchResult`，Codex 不再手写 `Map.of(...)` 分页结构。
- complexity and abstraction: 新增 record 是低复杂度值对象；`UnifiedSessionTaskProjectionService` typed-first 后保留旧 fallback，迁移风险可控。
- error handling and edge cases: typed envelope 支持 `empty(...)` 和 null collection 降级为空列表；旧 Map / getter fallback 仍有单测固定。
- readability and maintainability: Claude 历史 controller 仍使用模块 DTO，SPI override 明确包装 typed envelope，避免 controller 契约与 SPI 契约互相牵连。
- critical logic documentation: `TaskListingProvider` Javadocs 已说明新实现应返回 typed envelope，legacy DTO/Map 仅为兼容路径。
- contract and compatibility: 外部 response 字段仍由 session 聚合层输出 `content`、`totalSessions`、`results`、`total`、`page`、`size`；旧 Provider 返回 Map/bean 仍可解析。
- documentation and writeback: Stage 7 workitem、OPT 主文档、README、质量门、覆盖审计和验收记录同步回写。
- test alignment: session projection typed/fallback 单测、Codex list typed 断言、TaskDispatchFacade 既有聚合回归和 affected reactor 均已通过。
- release readiness: 未发现阻断覆盖审计的问题。

## Findings

- 未发现阻断性实现缺陷。
- `TaskListingProvider` 方法签名仍返回 `Object`，这是本阶段为兼容旧 Provider 保留的边界；typed envelope 已成为新实现和已迁移 Provider 的主路径，但编译期尚未强制所有 Provider 返回 typed record。

## Risks / Follow-ups

- legacy Map / JavaBean getter fallback 仍保留在 `UnifiedSessionTaskProjectionService`。后续若确认外部 Provider 已迁移，可另起阶段收敛或删除 fallback。
- Claude / Codex 大型 TaskService 仍未拆分，本阶段只处理 listing/search envelope，不改变服务内部职责复杂度。
- 当前没有专门覆盖 Claude SPI wrapper 的行为单测；affected reactor 已覆盖编译兼容，后续如继续收紧接口签名，应补更细粒度 provider wrapper 测试。

## Recommended Next Skills

- `foggy-test-coverage-audit`: 建议执行，确认 Stage 7 requirement、quality finding 与测试证据映射完整。
- `foggy-bug-regression-workflow`: 不需要；当前未发现缺陷修复场景。
- `plan-evaluator`: 不需要；当前方案与兼容性目标一致。
- back to implementation: 不需要；当前无阻断修复项。

## Decision

- decision: `ready-with-risks`
- can_enter_coverage_audit: yes
- follow_up_required: yes

## Lightweight Self-Check Note

- self_check_summary: 跨 `navigator-spi`、`session-module` 和 Worker Provider 的 SPI 契约改动已通过自检，因改动跨模块并属于阶段性交付，已升级为正式质量门。
- self_check_decision: `needs-formal-quality-gate`
- self_check_follow_up: 执行 Stage 7 测试覆盖审计。
