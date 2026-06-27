---
quality_scope: feature
quality_mode: pre-coverage-audit
version: 1.3.1-SNAPSHOT
target: OPT-001-stage15-worker-session-typed-envelope
status: reviewed
decision: ready-with-risks
reviewed_by: Codex
reviewed_at: 2026-06-26
follow_up_required: yes
---

# Implementation Quality Gate

## Background

Stage 15 在 Stage 14 listing/search typed method contract 之后，继续收敛 `WorkerSessionQueryProvider` 的弱类型边界。此前 worker-session 主链路仍以 `Map` / `List<Map>` 表达，`TaskDispatchFacade` provider fan-out 直接调用 legacy 方法。

## Check Basis

- Work item: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage15-worker-session-typed-envelope.md`
- Previous stage: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage14-task-listing-typed-method.md`
- Changed code:
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/WorkerSessionQueryProvider.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/WorkerSessionSummary.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/WorkerSessionMessage.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/WorkerSessionMessageCount.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/WorkerSessionSyncResult.java`
  - `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
  - `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphWorkerSessionQueryService.java`

## Changed Surface

- `WorkerSessionQueryProvider` 新增 typed worker-session summary、message、message count 和 sync result 方法。
- 四个 worker-session typed records 支持从 typed instance、Map 和 public getter 对象读取字段，并为 REST 兼容提供 `toMap()`。
- `TaskDispatchFacade` worker-session fan-out 主链路改为 typed methods，再转回原 REST payload 形状。
- Claude / LangGraph worker-session provider 实现 typed override，legacy Map 方法仅保留为委派 wrapper。
- 测试覆盖 facade typed path、legacy Map provider adapter 和 LangGraph typed provider 返回。

## Quality Checklist

- scope conformance: pass。改动只覆盖 worker-session 查询端口契约，没有改变 REST API 响应形状。
- code hygiene: pass。未发现 debug 输出、临时分支或临时 TODO。
- duplication and consolidation: pass。字段读取复用 `TaskResultEnvelopeAdapters`，避免在 facade 继续扩散 Map key 读取。
- complexity and abstraction: pass。新增 records 是轻量 DTO / envelope，不引入新的 service 层抽象。
- error handling and edge cases: pass。null / empty collection 返回空集合或零值 envelope；Map attribute 允许 null value 并保持不可变副本。
- readability and maintainability: pass。typed 方法名和返回类型明确表达 worker-session 契约，legacy 方法注释指向 typed override。
- critical logic documentation: pass。workitem 记录了 legacy Map 方法和 REST payload 保留原因。
- contract and compatibility: pass。旧 REST / OpenAPI / SDK payload 未变，legacy Map provider 仍可通过 default adapter 工作。
- documentation and writeback: pass。workitem、README、governance、quality、coverage、acceptance 已回写。
- test alignment: pass。测试覆盖主链路迁移、provider typed override 和 legacy compatibility。
- release readiness: pass-with-risks。无阻断性实现问题。

## Findings

- No blocking implementation issues found.

## Risks / Follow-ups

- legacy Map 方法仍保留。后续需等待外部插件/调用方迁移完成后再规划 deprecation / removal。
- REST controller 仍返回 Map payload；本阶段仅收敛 Java SPI 主路径，不改变外部协议。
- typed adapter 读取 Map / public getter 字段；只有 Map 来源会完整保留原始 attributes。
- Claude worker-session 查询仍在 `ClaudeTaskService` 物理 bean 内，职责隔离可作为后续阶段拆分。
- deprecated task command fallback 与生产 schema migration 工具化仍属于后续治理项。

## Recommended Next Skills

- `foggy-test-coverage-audit`
- `foggy-acceptance-signoff`

## Decision

decision=`ready-with-risks`。Stage 15 实现范围收口，worker-session provider fan-out 主链路已切换到 typed DTO / envelope methods，legacy Map 方法保留兼容。剩余风险均为后续架构收敛项，不阻断本阶段签收。
