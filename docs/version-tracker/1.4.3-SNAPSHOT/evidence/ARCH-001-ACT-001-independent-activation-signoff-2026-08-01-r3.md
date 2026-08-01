---
acceptance_scope: bug
version: 1.4.3-SNAPSHOT
target: ARCH-001-ACT-001 / arch001-act001-provisioning-20260801-07
status: signed-off
decision: accepted-with-risks
signed_off_by: project-root-reviewer
signed_off_at: 2026-08-01
reviewed_by: same-session under explicit project-owner waiver
blocking_items: []
follow_up_required: yes
evidence_count: 8
assurance_level: elevated
---

# ARCH-001-ACT-001 Independent Activation Signoff R3

## Document Purpose

- intended_for: execution owner / project owner
- purpose: 审计 `-06` watcher 子进程误判修复和 fresh stopped `-07`，决定是否允许
  一次 bounded、非 production 的真实 Codex canary。

## Background

- delivery_spec:
  `docs/version-tracker/1.4.3-SNAPSHOT/workitems/ARCH-001-ACT-001-enforced-canary-activation-readiness.md`
- target_outcome: Worker 合法任务子进程不触发 controller drift，同时独立/孤立进程仍
  fail closed；随后允许 exact `-07` 一次真实模型验证。
- signoff_scope: HEAD `fdef79c9c55e7de9a5b01822c3c9dc0c75ca2e00`、pre-signoff
  candidate digest `41f273293b86484668645f8f8123443b6118b618dd15e7b0a6cc93f609082fe3`、
  Launcher SHA `235006b8e9fc4289f92b6adf08ea4251ad5c01fd999ca306d5064f0ed3c2f05b`、
  manifest `8f68d5e159ab99160fc017f3cbcda123e9c8f3d7055d6683c7dc34e97c717ae5`
  与 seal `eee41dd6711c51a8aad9c5f5c0bc4f6e87ee1ee2623329fdb41ca646f03b1105`。
- critical_outcomes: runtime-descendant ownership、orphan fail-closed、exact target、
  one-shot/no-retry、zero business access、exact cleanup and credential purge。

## Contract Conformance

| Item | Expected | Delivered | Evidence | Result |
|---|---|---|---|---|
| Worker child classification | exact runtime descendants are workload | parent-chain proof excludes only Navigator/Worker descendants | real `/proc` regression | pass |
| Unknown controller safety | independent/orphan/re-parent/cycle remains unknown | no inferred ownership without exact ancestor | unit + live scan regression | pass |
| Existing runtime correctness | prior Sentinel epoch fix remains valid | no Java/runtime artifact change | `-06` live evidence + unchanged artifact SHA | pass |
| Fresh target | isolated schema/provisioning/readiness/seal | new generated IDs and stopped `-07` | target results + doctor | pass |
| One-shot boundary | one task-create maximum, no retry | owner authorization retained | user authorization + ledger protocol | pass |
| Cleanup | exact manifest and six-profile purge | stopped cleanup plan passed | `-07` cleanup-plan | pass |

## Implementation Quality

- scope and changed surface: only target watcher process classification, its tests and explanatory
  documentation changed; no API, migration, Worker wire, Java runtime or production boundary changed.
- maintainability: one bounded ancestor-walk helper with cycle detection; unreadable/missing parent
  data remains fail closed.
- edge cases: direct and transitive children pass; orphan, PID 1 re-parenting and cyclic ancestry fail.
- compatibility/security: PID-file runtime ownership remains exact, target root remains `0700`,
  credential values are not evidence.

## Evidence Matrix

| Item | Classification | Risk | Evidence | Reused / New | Result |
|---|---|---|---|---|---|
| watcher P1 regression | core-blocker | critical | 21-test harness suite | new | pass |
| real process-tree wiring | core-blocker | critical | exact worker child + independent rogue process | new | pass |
| Sentinel epoch remediation | core-blocker | critical | `-06` SHADOW/READY/NONE live cycle | reused | pass |
| Java artifact integrity | core-blocker | critical | unchanged artifact SHA and prior package/reactor results | reused | pass |
| fresh schema/runtime identity | core-blocker | critical | 93-table fresh/reapply/validate + new generated IDs | new | pass |
| stopped target ownership | core-blocker | critical | doctor + cleanup-plan, zero listeners | new | pass |
| reviewer separation | process-gap | minor | same session explicitly allowed by project owner | waived | waived |
| production promotion | out-of-scope | critical | activation/GOV-001-P3 remain closed | reused | pass |

## Evidence Sufficiency

- assurance_level: elevated
- why sufficient: every security and runtime must-pass item has direct current evidence; the only
  waiver concerns reviewer-session separation, not implementation, artifact, authority or data safety.
- expensive validation omitted: no repeated Java reactor/package run because the watcher-only Python
  change cannot alter the unchanged Launcher artifact; repeating it would not affect the decision.
- next decision-changing validation: the single exact `-07` real canary and its terminal lifecycle
  convergence while the watcher remains live.

## Waivers

| Waived Item | Authority | Reason | Bounded Impact | Non-Waivable Guards | Follow-up |
|---|---|---|---|---|---|
| separate reviewer session | project owner | user explicitly authorized direct continuation without a new session for converged edge fixes | process independence only; no expansion of model, cost, target or production scope | exact target, one submission, no retry, credential isolation, fail-closed observer, cleanup | preserve transparent same-session attribution |

## Failed Items

- none

## Final Decision

- decision: `AUTHORIZED_FOR_ONE_BOUNDED_CANARY`
- rationale: all elevated-assurance technical must-pass guards pass; the bounded process waiver does
  not weaken runtime safety or evidence truthfulness.
- blocking_items: none
- follow_up_owner_and_due: execution owner runs exactly one `-07` attempt, verifies terminal lifecycle
  convergence, then quarantines, destroys and records the result on 2026-08-01.

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: project-root-reviewer
- signed_off_at: 2026-08-01
- acceptance_record:
  `docs/version-tracker/1.4.3-SNAPSHOT/evidence/ARCH-001-ACT-001-independent-activation-signoff-2026-08-01-r3.md`
- blocking_items: none
- follow_up_required: yes
