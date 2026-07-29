---
acceptance_scope: bug
version: 1.4.3-SNAPSHOT
target: BUG-035-open-sdk-typed-termination-reconciliation-contract
status: signed-off
decision: accepted
signed_off_by: Codex release reviewer (same-thread evidence audit)
signed_off_at: 2026-07-30
reviewed_by: project owner delivery request
blocking_items: []
follow_up_required: no
evidence_count: 13
assurance_level: elevated
---

# BUG-035 Delivery Signoff

## Document Purpose

- intended_for: signoff-owner / reviewer / project-root-session
- purpose: 对 Open SDK typed termination/readiness/request-ID reconciliation 公共契约形成可复核的正式签收结论。

## Background

- delivery_spec: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-035-open-sdk-typed-termination-reconciliation-contract.md`
- target_outcome: Java 调用方不读取或猜测 `Map<String,Object>` 字段即可完成 readiness、termination 和原 request-ID reconciliation。
- signoff_scope: implementation commits `f8b23eae43a30083add0d384d1efaa5cee93f6b3`、`bfd85203252febd1c98f89a069eb058d4678d2c3` 及其本地 binary/sources/test evidence。
- critical_outcomes: accepted 不冒充 terminal；receipt 开启时同 request ID 不二次有效 dispatch；reconcile 严格只读；unknown/null/disabled fail closed；旧 Map API 兼容。
- non_blocking_or_waivable_items: live Navigator/Worker/SIM/TMS 不属于 BUG-035 批准 scope，由 REL-003 和下游 SIM 联调分别处理。

## Acceptance Basis

- approved delivery spec: BUG-035，`assurance_level=elevated`，AC-1 至 AC-13 全部标记完成。
- changed paths / diff: `bc0b8871..bfd85203`，42 files，typed SDK/service/audit/config/tests/docs；`git diff --check` passed。
- test records: focused contract、affected launcher/BFF、route/config targeted tests、SDK clean install 均实际 exit `0`。
- experience evidence: `javap` 显示三个正式 typed method；`1.0.38-SNAPSHOT` binary/sources SHA 已记录。
- migration / compatibility evidence: endpoint/header 不变；deprecated Map API 与 legacy reconcile repair branch 保留；无 schema migration。

## Contract Conformance

| Item | Expected | Delivered | Evidence | Result |
|---|---|---|---|---|
| AC-1 | 三个正式 typed API 可由 `javap` 看见 | `RuntimeTerminationReadinessDTO`、`RuntimeTaskTerminationDTO`、`RuntimeTaskReconciliationDTO` 返回签名存在 | SDK binary `javap` | pass |
| AC-2 | readiness 覆盖 capability、Worker match、status、terminal、reason | 服务端正式产生字段，SDK unknown-safe 反序列化 | `RuntimeTaskTypedContractServiceTest`、`RuntimeTaskTypedContractTest` | pass |
| AC-3 | typed terminate form/result 字段完整 | form 包含 task/expected worker/reason/dryRun/confirm；result 包含 request ID/outcome/status/terminal/reason | SDK source、JSON fixture | pass |
| AC-4 | accepted 与 terminal 分离 | accepted fixture 明确 `canonicalTerminal=false`；rejected/already-terminal 独立 outcome | typed contract tests | pass |
| AC-5 | reconcile 区分六类以上状态且只读 | `NOT_FOUND/IN_PROGRESS/ACCEPTED/REJECTED/TERMINAL/AMBIGUOUS/UNKNOWN`，无 provider/audit/repair side effect | service no-interaction assertions | pass |
| AC-6 | 同 request ID 幂等且 scope mismatch fail closed | 串行/并发重放不二次调用 provider；task/operation mismatch 拒绝 | audit/service idempotency tests | pass |
| AC-7 | null/unknown/unsupported 稳定 | enum `UNKNOWN`、status/reason `UNKNOWN`、terminal null 保真、capability unavailable | Jackson/service fixtures | pass |
| AC-8 | 旧调用方兼容 | 三个 Map API 保留并 deprecated；legacy payload 继续编译运行 | SDK compatibility tests、affected BFF build | pass |
| AC-9 | 唯一 snapshot binary/sources 本机安装 | `1.0.38-SNAPSHOT` binary SHA `8002becd...d3441`；sources SHA `0f660a20...8901d` | Maven local repository | pass |
| AC-10 | receipt 默认开、关闭不阻断单次 termination | disabled fixture 返回真实 outcome，并显式 availability/persisted false | service/config tests | pass |
| AC-11 | receipt 关闭时 reconcile fail closed | `AMBIGUOUS` + `TERMINATION_REQUEST_RECEIPT_DISABLED`，不查 receipt/不调用 provider/不建议 replay | service no-interaction assertions | pass |
| AC-12 | termination receipt 7d、其他 audit 24h、cron cleanup | 默认 `7d`/`24h`/`0 0 2 * * *`；无 5 分钟/write-trigger cleanup | properties/scheduled/cleanup tests | pass |
| AC-13 | availability typed getters 与最终制品完整 | DTO getters、JSON、affected build、sources、`javap` 均通过 | SDK 203 tests；launcher/BFF affected test | pass |

## Implementation Quality

- scope and changed surface: 变更位于 SDK、业务闭环服务、request audit、launcher config/authorization evidence 和直接调用方；未修改 sibling workspace 或 Worker。
- maintainability and duplication: SDK DTO 直接匹配正式 wire contract；未以 Map wrapper 重新猜字段。legacy branch 由旧 payload 显式分流。
- error handling and edge cases: unknown/null、capability unavailable、Worker mismatch、receipt disabled/expired、scope mismatch 和并发重放均有 fail-closed 行为。
- contract, data and compatibility: endpoint/header 不变、无 schema migration、旧 Map 方法 deprecated 保留。
- terminology and documentation: accepted、canonical terminal、receipt、reconciliation、replay 语义在代码、测试和交付单一致。

## Evidence Matrix

| Item | Classification | Risk | Evidence | Reused / New | Result |
|---|---|---|---|---|---|
| AC-1/2/3/4/7 | core-blocker | major | focused service/SDK/Jackson tests与 `javap` | new | pass |
| AC-5/6/10/11 | core-blocker | critical | request-audit/service no-side-effect/idempotency fixtures | new | pass |
| AC-8 | core-blocker | major | deprecated API fixture、affected BFF/launcher test | new | pass |
| AC-9/13 | core-blocker | major | clean install、203 tests、binary/sources SHA、typed getters | new | pass |
| AC-12 | core-blocker | major | properties/time-basis/cleanup tests | new | pass |
| same-thread reviewer | process-gap | minor | reviewer identity explicitly recorded；conclusions based on actual diff/commands rather than implementer status text | new | disclosed |

## Evidence Sufficiency

- assurance_level: elevated
- why_existing_evidence_is_sufficient_or_not: every must-pass AC has executable or artifact evidence; critical no-redispatch/read-only/fail-closed outcomes use no-interaction assertions。
- new_validation_that_could_change_decision: none within BUG-035 local contract scope。
- expensive_validation_omitted_and_reason: live runtime/SIM/Worker/TMS was an explicit non-goal and is not needed to prove JSON/API/idempotency contract；SIM validation is a downstream integration activity。

## Optional Full-Chain Recommendation

- recommendation: not-needed
- qualifying_condition: none
- estimated_wall_clock_and_basis: not-estimated
- scope_and_prerequisites: none
- maximum_attempts: 1
- decision_impact: none for BUG-035 signoff
- user_approval: not-requested
- execution_status: not-run

## Waivers

| Waived Item | Authority | Reason | Bounded Impact | Non-Waivable Guards | Follow-up |
|---|---|---|---|---|---|
| none | | | | | |

## Failed Items

- none

## Risks / Follow-ups

- deprecated Map 与 legacy projection-repair 双模式仍存在；退出需要独立调用方迁移窗口。
- receipt disabled/expired 时不能证明丢失响应的原请求结果；调用方必须禁止自动重发并依赖 canonical task state 或人工处置。
- cleanup 每轮默认上限 20,000 条且使用 JVM timezone；高流量或非本地时区部署需显式调参。
- 本签收由同一 Codex 线程执行证据审计，未伪称独立第二审；这是透明流程限制，不削弱实际执行的产品证据。

## Final Decision

- decision: accepted
- rationale: AC-1 至 AC-13 全部有与 elevated 风险相称的实际证据，无 core blocker、契约 deviation、权限扩张或未披露兼容破坏。
- blocking_items: none
- follow_up_owner_and_due: none；OBS/8112/SIM handoff 由 REL-003 承接，不是 BUG-035 缺口。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: Codex release reviewer (same-thread evidence audit)
- signed_off_at: 2026-07-30
- acceptance_record: `docs/version-tracker/1.4.3-SNAPSHOT/evidence/BUG-035-delivery-signoff-2026-07-30.md`
- blocking_items: none
- follow_up_required: no
