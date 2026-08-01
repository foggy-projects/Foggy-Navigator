---
acceptance_scope: feature
version: 1.4.3-SNAPSHOT
target: ARCH-001-ACT-001 / arch001-act001-provisioning-20260801-06
status: signed-off
decision: accepted
signed_off_by: independent-reviewer-codex
signed_off_at: 2026-08-01
reviewed_by: N/A
blocking_items: []
follow_up_required: yes
evidence_count: 9
assurance_level: elevated
---

# ARCH-001-ACT-001 Independent Activation Signoff R2

## Document Purpose

- intended_for: execution owner / project owner
- purpose: 对 post-`-05` Sentinel fence 修复及 exact stopped `-06` target
  给出一次、bounded、非 production 的 activation 授权结论。

## Background

- delivery_spec:
  `docs/version-tracker/1.4.3-SNAPSHOT/workitems/ARCH-001-ACT-001-enforced-canary-activation-readiness.md`
- target_outcome: 判断 sealed `-06` 是否足以执行一次新建 synthetic
  Session/Task 的 real Codex bounded canary。
- signoff_scope: candidate HEAD
  `fdef79c9c55e7de9a5b01822c3c9dc0c75ca2e00`、pre-signoff documentation
  candidate digest `2340857a9c57607991ea921c28f609d9a1b81c83c83e74d36b697b00bbcd81a1`、
  Launcher artifact
  `235006b8e9fc4289f92b6adf08ea4251ad5c01fd999ca306d5064f0ed3c2f05b`、
  manifest `923f895db0a9eae264ec19e1de3cff987c2bea216eae02dfae355b842c732eba`
  与 seal `a2b105b7f6a226b30c3e4e4740a05916552c57e55d389346fd20433bd349993b`。
- critical_outcomes: exact authority、same-generation epoch rebind、Sentinel
  fence、atomic pre-provider admission、one-shot/no-retry、zero business access、
  exact cleanup 与 credential purge。
- non_blocking_or_waivable_items: local/gitignored evidence portability only;
  no waiver is used.

## Acceptance Basis

- changed paths / diff: post-`-04` authority V2/clean restart fixes plus
  `WorkerLifecycleSentinel.java` and its strict-fence regression test; canonical
  work item records the exact changed paths.
- test records: 22 focused activation/Sentinel tests; Session reactor 514 tests
  with 0 failure/error and one existing environment-gated skip; final Sentinel
  6/6; Launcher 14-module package success.
- exact target evidence: `-06` fresh/reapply/validate result, production-API
  provisioning result, Worker readiness result, stopped manifest/seal, doctor
  and cleanup-plan.
- prior live evidence: `-05` proved V2 manifest and authority epoch rebinding
  through registration/proof/readiness, and proved atomic rollback/zero provider
  effect when the newly found Sentinel fence rejected admission.

## Contract Conformance

| Item | Expected | Delivered | Evidence | Result |
|---|---|---|---|---|
| Exact candidate | current post-fix source and copied artifact | digest- and SHA-bound candidate/artifact | candidate V2 digest + manifest | pass |
| Fresh target | isolated MySQL 8.0.44, generated IDs, stopped seal | run/target `-06`, ports 18126/13056/13312 | schema/provision/readiness/seal | pass |
| Activation safety | switches false before execution; zero provider/model effect | all closed-provisioning counters zero | provisioning + stopped doctor | pass |
| Restart fencing | clean epoch rotation accepted; generation/physical drift closed | readiness fence used only for exact physical/same generation | focused + strict-fence tests | pass |
| One-shot boundary | at most one task-create/model opportunity, no retry | execution authorization is explicitly bounded | owner authorization + this record | pass |
| Cleanup ownership | exact manifest digest required; six lanes purgeable | stopped cleanup-plan has zero writes and exact project/root | cleanup-plan | pass |

## Implementation Quality

- scope and changed surface: minimal lifecycle-domain change; no API, migration,
  Worker wire, provider route or production boundary expansion.
- maintainability and duplication: current readiness identity is selected once;
  generation and physical identity remain explicit fail-closed guards.
- error handling and edge cases: null readiness identity, physical drift and
  generation reset reject before inventory; strict test port matches real Worker
  identity-fence behavior.
- contract, data and compatibility: default activation flags remain false;
  `SHADOW` compatibility and GOV-001-P3 production block remain unchanged.
- terminology and documentation: canonical work item records P1 cause,
  rollback, remediation, tests and cleanup outcome.

## Evidence Matrix

| Item | Classification | Risk | Evidence | Reused / New | Result |
|---|---|---|---|---|---|
| V2 manifest + authority restart bind | core-blocker | critical | `-05` live registration/proof/readiness | reused, inputs unchanged | pass |
| Sentinel epoch fence remediation | core-blocker | critical | strict 6-test lane + 22-test activation lane | new | pass |
| Admission atomicity | core-blocker | critical | 16-test integration lane + `-05` zero-row rollback | reused/new | pass |
| Affected module compatibility | core-blocker | major | Session 514-test reactor | new | pass |
| Artifact build integrity | core-blocker | critical | Launcher 14-module package + artifact SHA | new | pass |
| Fresh schema and target identity | core-blocker | critical | 93-table apply/reapply/validate + manifest/seal | new | pass |
| Credential/provider boundary | core-blocker | critical | six `0600` lanes, bootstrap purged, counters zero | new | pass |
| Exact cleanup authority | core-blocker | critical | stopped doctor + cleanup-plan | new | pass |
| Production/external promotion | out-of-scope | critical | GOV-001-P3 remains blocked | reused | pass |

## Evidence Sufficiency

- assurance_level: elevated
- why_existing_evidence_is_sufficient_or_not: every non-waivable pre-execution
  guard has current candidate and exact-target evidence. The only intentionally
  unexecuted item is the bounded canary itself, which this signoff authorizes.
- new_validation_that_could_change_decision: only the one real canary and its
  post-admission lifecycle/quarantine result.
- expensive_validation_omitted_and_reason: no additional replay, provider matrix,
  production or business-system smoke; these are outside scope and would not
  change this local one-shot decision.

## Optional Full-Chain Recommendation

- recommendation: user-requested
- qualifying_condition: exact sealed `-06` is the final local candidate for this
  activation attempt.
- estimated_wall_clock_and_basis: within the owner-approved 60-minute window;
  prior `-05` preparation and cleanup established the bound.
- scope_and_prerequisites: restart only exact `-06`, live watcher,
  registration/proof/readiness, one synthetic static no-tool task, quarantine,
  exact cleanup and six-profile purge.
- maximum_attempts: 1
- decision_impact: success closes the local activation canary; any fail-closed
  result stops without retry and is classified before further action.
- user_approval: approved
- execution_status: consumed-model-completed-lifecycle-blocked

## Waivers

| Waived Item | Authority | Reason | Bounded Impact | Non-Waivable Guards | Follow-up |
|---|---|---|---|---|---|
| none | | | | | |

## Failed Items

- none

## Risks / Follow-ups

- The canary remains local and disposable; it grants no production, external,
  dev-kvm, release or rollout authority.
- The signoff documentation itself is outside the frozen pre-signoff digest;
  it changes no compiled artifact or runtime input.

## Final Decision

- decision: `AUTHORIZED_FOR_ONE_BOUNDED_CANARY`
- rationale: all elevated-assurance pre-execution must-pass guards have current,
  exact evidence and no blocker or waiver remains.
- blocking_items: none
- follow_up_owner_and_due: execution owner performs exactly one `-06` attempt,
  then quarantines, cleans and records the terminal result on 2026-08-01.

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: independent-reviewer-codex
- signed_off_at: 2026-08-01
- acceptance_record:
  `docs/version-tracker/1.4.3-SNAPSHOT/evidence/ARCH-001-ACT-001-independent-activation-signoff-2026-08-01-r2.md`
- blocking_items: none
- follow_up_required: yes

## Post-Signoff Execution Record

- The exact `-06` authorization was consumed once with no retry. Task
  `20260801-e66e` reached real model status `COMPLETED`, but the external target
  watcher misclassified the Worker-owned Codex subprocess as an unknown
  controller and triggered `LIFECYCLE_ACTIVATION_CONTROLLER_DRIFT` during the
  task.
- The resulting ENFORCED Worker/Session/Task aggregates were authority-
  quarantined before terminal lifecycle convergence. The signoff decision was
  valid for its sealed inputs, but it cannot be reused: the one-shot allowance
  is consumed and the subsequent watcher remediation changes the candidate.
- The exact target resources were destroyed and all six credential profiles
  purged. A fresh independent signoff and fresh owner authorization are required
  before any further real-model canary.
