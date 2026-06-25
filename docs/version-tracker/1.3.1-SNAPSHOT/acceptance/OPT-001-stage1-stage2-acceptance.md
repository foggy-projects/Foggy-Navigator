---
acceptance_scope: feature
version: v1.0
tracked_version: 1.3.1-SNAPSHOT
target: OPT-001-stage1-stage2-java-dispatch-governance
doc_role: acceptance-record
doc_purpose: 记录 OPT-001 Stage 1/2 Java dispatch governance 切片的正式验收结论与证据摘要
status: signed-off
decision: accepted
signed_off_by: Codex
signed_off_at: 2026-06-25
reviewed_by: N/A
blocking_items: []
follow_up_required: no
evidence_count: 8
---

# Feature Acceptance

## Document Purpose

- doc_type: acceptance
- intended_for: signoff-owner / reviewer / java-platform
- purpose: 记录 `OPT-001` Stage 1/2 Java 侧 dispatch governance 切片的正式验收结论与证据摘要。

## Background

- Version: `1.3.1-SNAPSHOT`
- Target: `OPT-001-stage1-stage2-java-dispatch-governance`
- Owner: `java-platform`
- Goal: 验收已完成的 Stage 1 Provider route / backend / modelConfig 映射收口，以及 Stage 2 `TaskDispatchFacade` 职责边界拆分。

本次验收范围仅包含 Stage 1/2。Stage 3 Provider 状态 schema、Stage 4 SSE 部署边界和 Stage 5 运行配置硬化仍属于后续交付范围，不纳入本次签收。

## Acceptance Basis

- `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
- `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-method-responsibility-inventory.md`
- `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-implementation-quality.md`
- `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage1-stage2-coverage-audit.md`
- `docs/a2a-agent-architecture.md`

## Checklist

- [x] Stage 1 Provider route / backend / modelConfig 统一入口已交付。
- [x] Stage 1 涉及 session、metadata-config、business-agent、OpenAPI readiness / diagnostics 和 worker adapter 的重复映射已收口。
- [x] Stage 2 Provider 查找、create 目标推导、统一 session-store 投影、任务操作路由已从 `TaskDispatchFacade` 拆出明确边界。
- [x] REST / OpenAPI / SDK payload 与 `DispatchTaskDTO` 对外语义保持兼容。
- [x] cancel、resume、rewind、reconnect、resync、scan checkpoints 等高风险路径已有回归断言。
- [x] 实现质量门已执行，结论为 `ready-with-risks`，风险均为 Stage 3+ 后续治理项或非阻断实现风险。
- [x] 测试覆盖审计已执行，结论为 `ready-for-acceptance`。
- [x] 体验验证为 `N/A`，本切片为 Java 后端架构治理，不涉及 UI 交互变更。
- [x] 版本 README、workitem、方法职责清单、架构文档、质量门和覆盖审计均已回写。

## Evidence

- Requirement / Workitem:
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
- Method inventory:
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-method-responsibility-inventory.md`
- Architecture doc:
  - `docs/a2a-agent-architecture.md`
- Quality gate:
  - `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-implementation-quality.md`
- Coverage audit:
  - `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage1-stage2-coverage-audit.md`
- Latest targeted test evidence:
  - `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：56 tests pass。
- Latest session full test evidence:
  - `mvn test -pl session-module -am`：242 tests pass。
- Historical affected reactor evidence:
  - Stage 2.2~2.4 受影响 Java reactor：1525 tests pass，记录于主 workitem。

## Failed Items

- none

## Risks / Open Items

本次 Stage 1/2 验收无阻断项。

后续非本次签收范围事项：

- Stage 3：为 `providerStateJson` / `taskStateJson` 定义 Provider 级 schema、版本字段和 typed codec。
- Stage 3：评估 `TaskQueryProvider` 是否拆分为 query、task lifecycle、recovery、worker session 等更窄端口。
- Stage 3：为 Provider page/search 返回引入 typed envelope 前，继续保留当前反射兼容层和回归测试。
- Stage 4/5：继续治理 SSE 部署边界与生产 profile 配置硬化。

## Final Decision

decision: `accepted`

`OPT-001` Stage 1/2 Java dispatch governance 切片已满足当前验收标准：核心实现完成、质量门通过、覆盖审计通过、测试证据完整、文档已回写。本次签收不代表整个 `OPT-001` 完成，后续继续按 Stage 3~5 推进。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: Codex
- signed_off_at: 2026-06-25
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage1-stage2-acceptance.md
- blocking_items: none
- follow_up_required: no
