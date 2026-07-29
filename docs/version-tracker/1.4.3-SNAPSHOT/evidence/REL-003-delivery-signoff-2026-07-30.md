---
acceptance_scope: feature
version: 1.4.3-SNAPSHOT
target: REL-003-navigator-upstream-cli-1.0.39-snapshot-typed-termination
status: signed-off
decision: accepted
signed_off_by: Codex release reviewer (same-thread evidence audit)
signed_off_at: 2026-07-30
reviewed_by: project owner delivery request
blocking_items: []
follow_up_required: no
evidence_count: 8
assurance_level: elevated
---

# REL-003 Delivery Signoff

## Document Purpose

- intended_for: signoff-owner / reviewer / project-root-session
- purpose: 对 typed termination SDK 的 main/OBS/current-dev deployment 与 SIM handoff 形成正式发布签收结论。

## Background

- delivery_spec: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/REL-003-navigator-upstream-cli-1.0.39-snapshot-typed-termination.md`
- target_outcome: clean main candidate、SDK binary/sources、OBS CLI、当前 8112 和 SIM handoff 同时可复核。
- signoff_scope: release candidate `efbe55262bd3e8a2a207fc6e348ff152bb128594`、OBS `1.0.39-SNAPSHOT`、PID `2405362` 的 8112 launcher。
- critical_outcomes: immutable version、clean provenance、remote byte integrity、current-workspace process ownership、no Worker/sibling/credential mutation。
- non_blocking_or_waivable_items: Windows native wrapper execution；已由 archive structure/metadata/SHA 覆盖，不是 acceptance criterion。

## Acceptance Basis

- approved delivery spec: REL-003，`assurance_level=elevated`，AC-1 至 AC-8。
- changed paths / diff: release-only SDK version/provenance、packager parsing/features、observer dependency、signoff/release docs。
- test records: focused SDK/CLI、SDK clean install、BFF compile、shell syntax、package/offline install、OBS remote smoke、launcher build/health。
- experience evidence: remote manifest/download SHA、`javap`、process cwd/argv、actuator health/info。
- migration / compatibility evidence: endpoint/header/legacy Map 不变；CLI legacy repair 明确保留；无 schema/Worker/resource change。

## Contract Conformance

| Item | Expected | Delivered | Evidence | Result |
|---|---|---|---|---|
| AC-1 | BUG-035 可交付签收 | elevated AC-1..13 signoff `accepted` | `BUG-035-delivery-signoff-2026-07-30.md` | pass |
| AC-2 | 唯一 SDK GAV、binary/sources、typed signatures | `1.0.39-SNAPSHOT`；binary/sources SHA；三个 typed method `javap` 可见 | Maven local repository、`javap` | pass |
| AC-3 | snapshot 版本解析正确 | resolver 输出 `1.0.39-SNAPSHOT`，不再误读 `2.0.12`；实际 package 成功 | shell syntax、package output | pass |
| AC-4 | clean 双平台 CLI candidate | BUILD_INFO `efbe5526`/dirty false；双 archive 内嵌 SDK 与 Maven binary 同 SHA；offline install passed | package/archive/offline smoke | pass |
| AC-5 | main fast-forward push | `bc0b8871..efbe5526 main -> main`，local/remote candidate hash一致 | git fetch/push/rev-parse | pass |
| AC-6 | OBS pointer/archive/installers 完整 | 五个 upload HTTP 200；remote installer smoke；remote GET hash与manifest一致 | upload output、remote verification | pass |
| AC-7 | 当前 8112 属于本仓并运行候选 | PID/cwd/argv verified；health/MySQL UP；info `main@efbe5526` dirty false | process/actuator evidence | pass |
| AC-8 | SIM handoff fail-closed | final handoff固定 loopback URL、GAV/hash、typed methods、原 request ID、禁止重发和 no-provision 边界 | REL-003 contract/final prompt | pass |

## Implementation Quality

- scope and changed surface: release-only tooling/metadata/docs；server contract code沿用已签收 BUG-035。
- maintainability and duplication: Linux 版本解析绑定 project 坐标并校验允许格式；没有复制一套 SDK contract。
- error handling and edge cases: upload 拒绝 dirty/same-or-older numeric release；remote installer校验 SHA；SIM protocol 对 ambiguous/unknown fail closed。
- contract, data and compatibility: typed Java API 是正式 contract；shell CLI legacy reconcile 明确不冒充 typed request-ID reconciliation。
- terminology and documentation: SDK/CLI snapshot、receipt、accepted、terminal、reconcile 与 release provenance 术语一致。

## Evidence Matrix

| Item | Classification | Risk | Evidence | Reused / New | Result |
|---|---|---|---|---|---|
| AC-1/2 | core-blocker | critical | BUG-035 signoff、SDK tests/install/javap/hash | reused + new | pass |
| AC-3/4 | core-blocker | critical | parser/package/archive/offline install | new | pass |
| AC-5/6 | core-blocker | critical | non-force push、OBS upload/remote hashes/smoke | new | pass |
| AC-7 | core-blocker | major | PID/cwd/argv、launcher build、health/info | new | pass |
| AC-8 | core-blocker | major | bounded SIM prompt contract | new | pass |
| same-thread reviewer | process-gap | minor | reviewer identity disclosed，actual command/artifact evidence audited | new | disclosed |

## Evidence Sufficiency

- assurance_level: elevated
- why_existing_evidence_is_sufficient_or_not: every release authority boundary has actual readback；artifact identity、remote pointer、runtime provenance and health are independently queryable。
- new_validation_that_could_change_decision: none within Navigator release scope；SIM live task remains downstream integration evidence。
- expensive_validation_omitted_and_reason: no large authority/replay/full-chain required；real SIM/TMS/Worker operations were explicitly out of scope。

## Optional Full-Chain Recommendation

- recommendation: not-needed
- qualifying_condition: none
- estimated_wall_clock_and_basis: not-estimated
- scope_and_prerequisites: none
- maximum_attempts: 1
- decision_impact: none for Navigator release signoff
- user_approval: not-requested
- execution_status: not-run

## Waivers

| Waived Item | Authority | Reason | Bounded Impact | Non-Waivable Guards | Follow-up |
|---|---|---|---|---|---|
| none | | | | | |

## Failed Items

- none

## Risks / Follow-ups

- OBS `latest.json` 指向 development snapshot，仅用于当前开发联调，不是 production promotion。
- 8112 只监听 loopback；远端 SIM 不可连接，且本发布不扩大网络面。
- CLI shell `runtime task-reconcile` 仍是 deprecated legacy projection repair；新 reconciliation 必须走 Java typed API。
- 启动日志存在历史 Codex task reconnect WARN，但 actuator/DB UP、`backend-error.log` 为空；下游必须使用新的 SIM-owned disposable task。
- 同一 Codex 线程承担 release evidence audit；未伪称独立第二审。

## Final Decision

- decision: accepted
- rationale: main、SDK、CLI、OBS、current launcher 和 handoff 的所有 must-pass 均有真实可回读证据；无 secret、Worker、sibling、schema 或 production 边界扩张。
- blocking_items: none
- follow_up_owner_and_due: SIM owner 按 handoff 自行执行 downstream integration；该测试不阻断 Navigator release acceptance。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: Codex release reviewer (same-thread evidence audit)
- signed_off_at: 2026-07-30
- acceptance_record: `docs/version-tracker/1.4.3-SNAPSHOT/evidence/REL-003-delivery-signoff-2026-07-30.md`
- blocking_items: none
- follow_up_required: no
