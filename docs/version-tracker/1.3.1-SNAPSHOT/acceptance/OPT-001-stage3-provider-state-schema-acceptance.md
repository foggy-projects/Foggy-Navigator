---
acceptance_scope: feature
version: v1.0
tracked_version: 1.3.1-SNAPSHOT
target: OPT-001-stage3-provider-state-schema
doc_role: acceptance-record
doc_purpose: 记录 OPT-001 Stage 3 Provider 状态 schema 化切片的正式验收结论与证据摘要
status: signed-off
decision: accepted-with-risks
signed_off_by: Codex
signed_off_at: 2026-06-25
reviewed_by: N/A
blocking_items: []
follow_up_required: yes
evidence_count: 9
---

# Feature Acceptance

## Document Purpose

- doc_type: acceptance
- intended_for: signoff-owner / reviewer / java-platform
- purpose: 记录 `OPT-001` Stage 3 Provider 状态 schema 化切片的正式验收结论与证据摘要。

## Background

- Version: `1.3.1-SNAPSHOT`
- Target: `OPT-001-stage3-provider-state-schema`
- Owner: `java-platform`
- Goal: 验收 Stage 3 对 `providerStateJson` / `taskStateJson` 的 schema/version/codec 治理，以及 Claude、Codex、Gemini、LangGraph 当前核心 Provider 状态读写兼容策略。

本次验收范围仅包含 Stage 3 Provider 状态 schema 化。Stage 4 SSE 部署边界、Stage 5 运行配置硬化、`TaskQueryProvider` 窄端口拆分、LangGraph worker session endpoint 拆分和 typed provider envelope 设计不纳入本次签收。

## Acceptance Basis

- `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
- `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage3-provider-state-schema.md`
- `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-method-responsibility-inventory.md`
- `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage3-implementation-quality.md`
- `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage3-coverage-audit.md`

## Checklist

- [x] Stage 3 子计划具备文档作用、目标、验收标准、进度、测试和 execution check-in。
- [x] `ProviderStateCodec` 已定义 `schemaVersion=1`、`providerType` 和核心字段常量。
- [x] 新写入的 Provider state JSON 在已迁移路径中包含 `schemaVersion=1`。
- [x] legacy JSON 字段仍可读取，不破坏已有 session/task 恢复。
- [x] 坏 JSON 按空状态降级，不导致恢复链路异常扩散。
- [x] 未知字段在 merge 时被保留，空值写入按删除处理。
- [x] 清空 Claude/Codex/Gemini Provider session id 时能移除对应字段。
- [x] Claude/Codex/Gemini/LangGraph 的 `taskStateJson` 写入已迁移到共享 codec。
- [x] `DispatchTaskDTO` 对 schema v1 `taskStateJson` 的直接投影已有回归测试覆盖。
- [x] 实现质量门已执行，结论为 `ready-with-risks`，无阻断项。
- [x] 测试覆盖审计已执行，结论为 `ready-with-gaps`，`can_enter_acceptance=yes`。
- [x] 体验验证为 `N/A`，本切片为 Java 后端状态 schema 治理，不涉及 UI 交互变更。
- [x] Stage 3 workitem、总治理 workitem、quality、coverage 和 README 均已回写。

## Evidence

- Requirement / root workitem:
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
- Stage 3 execution plan:
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage3-provider-state-schema.md`
- Method inventory:
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-method-responsibility-inventory.md`
- Quality gate:
  - `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage3-implementation-quality.md`
- Coverage audit:
  - `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage3-coverage-audit.md`
- Shared codec / session projection evidence:
  - Stage 3.1 targeted regression：common 14 tests、session 53 tests pass；session full reactor 242 tests pass。
- Provider session state evidence:
  - Stage 3.2 Codex/Gemini targeted regression：ProviderStateCodec 5、CodexTaskService 20、GeminiTaskServiceAuthResolution 8 tests pass；Codex/Gemini addon full reactor pass。
  - Stage 3.2 Claude targeted regression：ProviderStateCodec 5、ClaudeTaskServiceAuth 20、ClaudeTaskServiceRewind 5、ClaudeTaskServiceSync 7、ConversationConfigService 13 tests pass；Claude addon full reactor 312 tests pass。
- Provider task state evidence:
  - Stage 3.3 targeted regression：ClaudeTaskServiceAuth 21、CodexTaskService 20、GeminiTaskServiceAuthResolution 8、LanggraphTaskService 24 tests pass；Provider addon reactor 1503 tests pass。
- Latest Stage 3.4 test evidence:
  - `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：54 tests pass。
  - `mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am`：219 suites / 1504 tests，0 failures，0 errors，0 skipped。

## Failed Items

- none

## Risks / Open Items

本次 Stage 3 验收无阻断项。

accepted-with-risks 的风险项均为后续架构收敛范围，不影响当前 Provider 状态 schema 化切片签收：

- `UnifiedSessionTaskProjectionService` 仍按普通 Map/string key 读取 schema v1 同名业务字段；typed provider envelope / typed constants 后续设计时需保留当前直接投影回归。
- LangGraph worker session endpoints 仍未作为独立端口拆分，本轮只覆盖统一任务 `taskStateJson` 的 context/state schema 化。
- `TaskQueryProvider` 查询、生命周期、恢复、worker session passthrough 仍在宽端口内，后续需要按风险排序拆分。
- `GeminiTaskService` 私有 `JsonSupport` 可作为 cleanup 候选，但删除前需确认模块内非 task state 写入路径引用。

## Final Decision

decision: `accepted-with-risks`

`OPT-001` Stage 3 Provider 状态 schema 化切片已满足当前验收标准：核心实现完成、质量门通过、覆盖审计通过、关键测试证据完整、体验验证明确为 `N/A`、文档已回写。遗留项均为非阻断架构收敛工作，不影响本切片签收。

本次签收不代表整个 `OPT-001` 完成，后续仍需按 Stage 4/5 继续推进 SSE 部署边界与生产配置硬化。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-25
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage3-provider-state-schema-acceptance.md
- blocking_items: none
- follow_up_required: yes
