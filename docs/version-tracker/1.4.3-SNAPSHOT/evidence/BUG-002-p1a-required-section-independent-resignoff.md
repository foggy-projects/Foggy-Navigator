---
acceptance_scope: bug-and-p1a-repair
version: 1.4.3-SNAPSHOT
target: BUG-002 / GOV-001 P1A
status: signed-off
decision: accepted
signed_off_by: Independent Signoff Reviewer
signed_off_at: 2026-07-19
reviewed_by: project-root-session
blocking_items: []
follow_up_required: no
evidence_count: 7
---

# BUG-002 / GOV-001 P1A Independent Re-signoff

## Background

- delivery_spec: `../workitems/BUG-002-p1a-required-section-contract.md`
- parent_spec: `../workitems/GOV-001-upstream-permission-and-trust-boundary.md`
- target_outcome: 修复 P1A required-section 合同缺失与 runtime capability 误分类，并按 Owner 批准的 catalog-and-test-only amendment 重新签核 P1A。
- historical_record: `GOV-001-p1a-independent-signoff.md` 保留原 `rejected` 结论，不被本记录覆盖。

## Contract Conformance

| Item | Delivered | Evidence | Result |
|---|---|---|---|
| AC-1 | 415 条均有显式声明；loader 拒绝空值、未知/重复 token、`NONE` 混用及同 action 分叉 | manifest 独立统计；catalog regression tests | pass |
| AC-2 | 20 条 `runtime.*` 仅 `runtime.ask` 要求 capability；4 条 Gateway 均要求 `CAPABILITY + WORKER_ROUTE` | manifest 独立统计与逐 action tests | pass |
| AC-3 | canonical context 可稀疏表达全部 10 类 typed section | `AuthorizationContextV1` / closed enum review | pass |
| AC-4 | missing/conflict 为 `DENY`，unverified/unknown 为 `UNKNOWN`，均有稳定 reason code | validator/evaluator review与 validation tests | pass |
| AC-5 | legacy adapter 不伪造 authority、delegation/grant、platform/tenant authority、capability 或 Worker route | adapter source review与 9 tests | pass |
| AC-6 | common、auth、launcher、Observer BFF 必需测试均由 reviewer 实际复跑通过 | 下方执行记录 | pass |
| AC-7 | 保持 non-binding shadow；未改 BFF runtime、CLI/SKILL、Worker/Codex route 或 external/Gateway 默认值 | scoped status/diff/secret scan、Actuator contracts | pass |

## Independent Execution Evidence

- `mvn test -pl navigator-common` — exit 0；94 tests，0 failures，0 errors，3 skipped。
- `mvn test -pl user-auth-module -am` — exit 0；197 tests，0 failures，0 errors，3 skipped。
- `mvn test -pl launcher -am` — exit 0；14-module reactor 2,652 tests，0 failures，0 errors，5 skipped；Surefire shutdown-delay warning 不影响 Maven `BUILD SUCCESS`。
- `mvn test -pl tools/navigator-chat-observer-bff -am -Dtest=ObserverBffRouteManifestCoverageTest,ObserverBffContextContinuityTest -Dsurefire.failIfNoSpecifiedTests=false` — exit 0；BFF 3/3，reactor success。
- source/evidence manifest：416 行含 header、415 entries、字节一致，SHA-256 均为 `ef4c32ac4ca25ee695dff7bacd9845301266807d71fbcafe35ebba4872aadc7d`；0 blank、6 explicit `NONE`、0 unknown token、0 duplicate-action section conflict。
- `git diff --check` — exit 0；仅既有 CRLF conversion warning。高置信 changed-path secret scan 0 命中。

## Scope and Risk Review

- Observer BFF `src/main` 无 changed path；其 `navigator-common` 依赖保持 test scope。
- CLI/SKILL、Worker/Codex routing、Worker/BizWorkerIdentity/WorkerPool 均未改动或创建。
- `NAVIGATOR_EXTERNAL_ENABLED` 与 `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED` 默认仍为 `false`。本签核不表示 Open API external、Provider、Worker Gateway external、Worker external 或 production ready。
- Observer BFF runtime shadow/audit、production hardening、tamper-evident audit、typed credential issuance、P1B/P1C 与 enforcement cutover 仍是已披露的后续范围，不构成本次 P1A 阻断项。
- 共享 dirty worktree 中 `packages/navigator-frontend/src/views/ClaudeWorkerView.vue` 属于用户保留的无关改动，未纳入本次签核。

## Final Decision

- decision: `accepted`
- rationale: BUG-002 的全部关键 acceptance criteria 均有独立静态证据与实际测试结果；历史 P1A-3 阻断已关闭，P1A-6 Observer BFF 范围已由 Owner 明确修订且实现未越界。
- blocking_items: none
- follow_up_owner_and_due: P1B 仍需 Project Owner 单独授权；本签核不自动启动后续阶段。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: Independent Signoff Reviewer
- signed_off_at: 2026-07-19
- acceptance_record: `docs/version-tracker/1.4.3-SNAPSHOT/evidence/BUG-002-p1a-required-section-independent-resignoff.md`
- blocking_items: none
- follow_up_required: no
