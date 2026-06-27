---
quality_scope: feature
quality_mode: pre-coverage-audit
version: 1.3.1-SNAPSHOT
target: OPT-001-stage14-task-listing-typed-method
status: reviewed
decision: ready-with-risks
reviewed_by: Codex
reviewed_at: 2026-06-26
follow_up_required: yes
---

# Implementation Quality Gate

## Background

Stage 14 在 Stage 7 typed envelope 与 Stage 8-13 窄端口治理之后，继续收敛 `TaskListingProvider` 的方法契约。此前 typed envelope 已存在，但 `TaskDispatchFacade` 的 provider fan-out 仍调用返回 `Object` 的 legacy listing/search 方法。

## Check Basis

- Work item: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage14-task-listing-typed-method.md`
- Previous stage: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage13-claude-narrow-port-bean.md`
- Changed code:
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskListingProvider.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskPageResult.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskSearchResult.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskResultEnvelopeAdapters.java`
  - `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexBizTaskProvider.java`

## Changed Surface

- `TaskListingProvider` 新增 typed methods，并通过 default implementation 适配 legacy `Object` 方法。
- `TaskPageResult` / `TaskSearchResult` 新增 `from(...)`，将 Map / public JavaBean getter envelope 转换为 typed result。
- `TaskDispatchFacade` listing/search fan-out 不再直接调用 `listTasksPaged`、`searchSessions`、`listTasksByDirectoryPaged` legacy 方法。
- Claude / Codex / Codex Biz 实现 typed methods，legacy 方法只委派到 typed 方法。
- 测试覆盖 typed facade path、legacy adapter 和 Codex service typed listing。

## Quality Checklist

- scope conformance: pass。改动只覆盖 listing/search 端口契约，没有删除 legacy 方法或改变 REST payload。
- code hygiene: pass。未发现 debug、临时分支或临时 TODO。
- duplication and consolidation: pass。legacy envelope 适配集中在 SPI result/adapters，避免继续扩散到 facade 主链路。
- complexity and abstraction: pass。新增的 `TaskResultEnvelopeAdapters` 是小型 package-private helper，只承担 Map / getter 读取。
- error handling and edge cases: pass。旧 Map / JavaBean getter 兼容被测试覆盖；null / missing field 按空结果与默认 page/size 处理。
- readability and maintainability: pass。typed 方法名与返回类型明确表达契约，legacy 方法注释指向 typed override。
- critical logic documentation: pass。workitem 记录了为何不在本阶段删除或 deprecate legacy 方法。
- contract and compatibility: pass。外部 REST / OpenAPI / SDK payload 未变，legacy `Object` 方法仍可调用。
- documentation and writeback: pass。workitem、README、governance、quality、coverage、acceptance 已回写。
- test alignment: pass。测试覆盖 SPI compatibility、session facade typed path、Codex typed service 和 affected reactor。
- release readiness: pass-with-risks。无阻断性实现问题。

## Findings

- No blocking implementation issues found.

## Risks / Follow-ups

- legacy `Object` 方法仍保留。后续可等外部插件/调用方完成迁移后再进入 deprecation / removal。
- legacy adapter 只承诺 Map 与 public JavaBean getter envelope；非标准对象不应继续作为支持契约。
- `UnifiedSessionTaskProjectionService` 仍保留 legacy fallback，属于 REST/session-store 兼容边界，后续可单独收敛。
- worker-session payload 仍是 Map，typed DTO / envelope 需要后续处理。
- Claude worker-session 查询仍在 `ClaudeTaskService` 物理 bean 内，职责隔离可后续拆分。

## Recommended Next Skills

- `foggy-test-coverage-audit`
- `foggy-acceptance-signoff`

## Decision

decision=`ready-with-risks`。Stage 14 实现范围收口，主 fan-out 链路已切到 typed listing/search 方法，legacy fallback 保持兼容。遗留风险均为后续架构收敛项，不阻断本阶段签收。
