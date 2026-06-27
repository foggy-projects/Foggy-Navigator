---
quality_scope: feature
quality_mode: pre-coverage-audit
version: 1.3.1-SNAPSHOT
target: OPT-001-stage13-claude-narrow-port-bean
status: reviewed
decision: ready-with-risks
reviewed_by: Codex
reviewed_at: 2026-06-26
follow_up_required: yes
---

# Implementation Quality Gate

## Background

Stage 13 在 Stage 8 窄端口注入、Stage 10 LangGraph 迁移、Stage 11 Gemini 迁移和 Stage 12 Codex / Codex Biz 迁移之后，迁移最后一个生产聚合实现 Claude，使生产代码不再通过 `TaskQueryProvider` 暴露 provider bean。

Claude 与前几个 provider 的差异是：它当前仍实际承担 worker-session 查询能力。因此本阶段不拆物理 worker-session bean，只把接口声明从聚合接口改为显式窄端口列表。

## Check Basis

- Work item: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage13-claude-narrow-port-bean.md`
- Previous stage: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage12-codex-narrow-port-bean.md`
- Changed code:
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
  - `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/ClaudeTaskServiceAuthTest.java`

## Changed Surface

- `ClaudeTaskService` implements 列表从 `TaskQueryProvider` 改为 `TaskLookupProvider, TaskCommandProvider, TaskListingProvider, WorkerSessionQueryProvider`。
- `ClaudeTaskService` 的 providerType、capabilities、lookup、command、listing/search 和 worker-session 方法实现未改。
- 新增类型边界测试，断言 Claude task service 是 lookup / command / listing / worker-session provider，且不是 aggregate provider。
- 文档索引、root governance、coverage 和 acceptance 状态同步更新。

## Quality Checklist

- scope conformance: pass。改动只覆盖 Claude narrow-port bean migration，没有扩大到 worker-session 物理拆分或 listing strict typing。
- code hygiene: pass。未发现 debug、临时分支或临时 TODO。
- duplication and consolidation: pass。本阶段复用 Stage 10/11/12 类型边界测试模式，没有新增重复业务实现。
- complexity and abstraction: pass。只调整接口暴露边界，不引入新抽象。
- error handling and edge cases: pass。业务异常、session 恢复、listing/search、worker-session 查询和投影写入路径未改变。
- readability and maintainability: pass。Claude 的实际职责通过接口声明更清晰。
- critical logic documentation: pass。该切片无新增复杂业务规则，文档记录了 Claude 仍保留 worker-session 能力的差异。
- contract and compatibility: pass。REST/OpenAPI/SDK payload 未变，A2A abort wrapper 继续依赖 `TaskLookupProvider`。
- documentation and writeback: pass。workitem、README、governance、quality、coverage、acceptance 已回写。
- test alignment: pass。测试直接覆盖 Claude 类型边界、Claude focused regression、session registry/facade 和 affected reactor。
- release readiness: pass-with-risks。无阻断性实现问题。

## Findings

- No blocking implementation issues found.

## Risks / Follow-ups

- Claude worker-session 查询仍在 `ClaudeTaskService` 物理 bean 内，职责隔离弱于 LangGraph 的独立 worker-session provider。该问题属于后续物理拆分范围。
- `TaskCommandProvider#cancelTask` 仍是 deprecated fallback，Claude 继续实现该兼容方法。该问题属于后续统一删除 legacy command fallback 的范围。
- 未新增完整 Spring ApplicationContext 启动测试断言真实 provider bean list；当前以类型边界单测和受影响 reactor 回归保护。
- `TaskListingProvider` 仍使用 `Object` 返回类型，strictly typed method 迁移需后续处理。
- worker-session payload 仍是 Map，typed DTO / envelope 需后续处理。

## Recommended Next Skills

- `foggy-test-coverage-audit`
- `foggy-acceptance-signoff`

## Decision

decision=`ready-with-risks`。Stage 13 实现范围收口，未发现阻断性质量问题，可进入测试覆盖审计和功能级验收。上述风险均为后续架构收敛项，不阻断本阶段签收。
