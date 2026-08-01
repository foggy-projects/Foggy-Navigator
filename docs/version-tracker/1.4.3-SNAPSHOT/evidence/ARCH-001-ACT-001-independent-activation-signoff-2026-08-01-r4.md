---
acceptance_scope: bug
version: 1.4.3-SNAPSHOT
target: ARCH-001-ACT-001 / arch001-act001-provisioning-20260801-08
status: signed-off
decision: accepted-with-risks
signed_off_by: project-root-reviewer
signed_off_at: 2026-08-01
reviewed_by: same-session under explicit project-owner waiver
blocking_items: []
follow_up_required: yes
evidence_count: 9
assurance_level: elevated
---

# ARCH-001-ACT-001 Independent Activation Signoff R4

## Document Purpose

- intended_for: execution owner / project owner
- purpose: 审计 `-07` 暴露的 terminal fact payload 缺陷、其最小兼容修复和 fresh
  stopped `-08`，决定是否允许一次 bounded、非 production 的真实 Codex canary。
- verdict: `AUTHORIZED_FOR_ONE_BOUNDED_CANARY`
- this review performed model submissions: `0`

## Reviewed Boundary

- runId: `arch001-act001-provisioning-20260801-08`
- targetId: `arch001-act001-target-provisioning-20260801-08`
- model: `gpt-5.6-sol`
- execution window: `2026-08-01T17:02:25+08:00` through
  `2026-08-01T18:02:25+08:00`
- cost boundary: at most one model submission, no retry
- branch / HEAD: `main` /
  `fdef79c9c55e7de9a5b01822c3c9dc0c75ca2e00`
- pre-signoff candidate digest:
  `aa7ad663b6ca931a65e95f10b121df0e28374b4acd9be17b99afdd339da08c0c`
- pre-signoff inventory: 80 tracked candidate paths / 47 untracked candidate paths
- Launcher SHA-256:
  `8c7edbd77d635b7b6a0812a3466a549f8d18ec83dc7014a0eb1e6b6318a3d3fa`
- manifest digest:
  `5c9d192ef32cc6aa53b068fcf0cafcab0095fd71a223b53041b6519e2c10e259`
- seal digest:
  `27818aa895d6882aa370f7cbc9201f288d4fb1b0aa35d35bc7508218ab0aea38`
- controller digest:
  `d0cfef3449e3e4ccb2734cbe6bda7a41b53188d2652fb96266f8eded8ab05ece`

The immutable activation authority is the pre-signoff candidate digest plus the
single allowed documentation delta at this exact R4 path. Any later code, test,
configuration, migration, manifest, seal, exact-tuple or other evidence delta
invalidates this authorization.

## Contract Conformance

| Item | Expected | Delivered | Result |
|---|---|---|---|
| admission fact payload | newly written reserved/dispatched facts carry a full content-free lifecycle envelope | `LifecycleProductionAdmissionService` writes the complete `TaskLifecycleFact` identity/state envelope | pass |
| legacy compatibility | only the two historically emitted empty admission facts may be reconstructed | owner service allowlists `TASK_DISPATCH_RESERVED` and `TASK_DISPATCHED`; every other empty fact fails closed | pass |
| terminal convergence | Sentinel can re-read the admitted facts and commit terminal state | focused allow/reject regressions cover valid legacy convergence and unsupported-empty rejection | pass |
| fresh target | schema, IDs, Worker and seal are independent of `-07` | new `-08` database and three server-generated IDs; no direct DML | pass |
| default closed | provisioning and signoff cause no activation/provider/model effect | both switches false; all three counters are zero | pass |
| one-shot execution | only one Navigator task-create and no retry | exact owner authorization and bounded ledger required | pass |
| cleanup | exact target destruction and six-profile purge | stopped cleanup-plan proves the owned project/root | pass |

## Evidence Matrix

| Evidence | Classification | Result |
|---|---|---|
| final focused lifecycle suite | core-blocker | 24 passed, 0 failed |
| affected lifecycle suite before final compatibility narrowing | core-blocker | 70 total, 69 passed, 1 environment-gated skip |
| session-module affected/full suite before final compatibility narrowing | core-blocker | 515 total, 514 passed, 1 environment-gated skip |
| 14-module Launcher package | core-blocker | all reactor modules `SUCCESS` |
| exact MySQL 8.0.44 fresh apply/reapply | core-blocker | 0→93 and 93→93 tables |
| live Hibernate validation | core-blocker | `ddl-auto=validate`, health `UP`, artifact hash matched |
| closed production-API provisioning | core-blocker | 5 API calls, 3 server IDs, direct DML/effect/submission counts all zero |
| Worker readiness | core-blocker | v1.0.30, authenticated complete inventory, seven required capabilities, active tasks 0 |
| stopped seal / doctor / cleanup-plan | core-blocker | no listeners; manifest, seal, controller and sealed-input digests independently matched |

The 70-test and 515-test runs preceded the final compatibility narrowing. That
narrowing only changed unsupported empty payloads from reconstruction to
fail-closed rejection; the final 24-test run directly covers both the allowed
and rejected branches. Repeating the full suites would not add decision-changing
evidence.

## Failed-Iteration Closure

- `-07` is retained as a real-model failure, not rewritten as a pass. The model
  completed, but terminal persistence rolled back because admission facts were
  stored with `{}` payloads and later rejected as invalid lifecycle facts.
- severity remains `P1 / activation blocker`, not P0: every accepted ENFORCED
  task was affected, while the feature remained default-closed, isolated and
  fail-closed with no production rollout or business data access.
- the repaired `-08` artifact is new and is not inferred from the `-07` seal.

## Findings and Waiver

- blocking core findings: none
- scoped risk: the repaired terminal path has not yet been exercised against a
  real model; that is precisely the one bounded canary authorized here
- process-gap waiver: reviewer-session separation only. The project owner
  explicitly authorized direct continuation without a new session once the
  main capability had converged. This waiver does not relax exact target,
  candidate/seal integrity, one submission, no retry, credential separation,
  controller fail-closed behavior or cleanup.
- out of scope: production/GOV-001-P3, external exposure, shared databases,
  TMS/SIM, other providers, deployment and rollout

## Credential and Data Boundary

- No credential value, prompt body, model response, `accounts/` content,
  sibling repository or real business data was reviewed.
- Provider, runtime, Worker, Navigator, database and control profiles were
  checked only as exact target-owned regular files with mode `0600`.
- The bootstrap profile is absent after provisioning.
- The six credential profiles remain material, not evidence, and must be
  purged after the exact run.

## Final Decision

`AUTHORIZED_FOR_ONE_BOUNDED_CANARY`

This decision authorizes only the exact `-08` packet above, within the stated
window, with at most one model submission and no retry. It does not authorize a
second attempt, production use, external promotion, deployment or release.

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: project-root-reviewer
- signed_off_at: 2026-08-01
- acceptance_record:
  `docs/version-tracker/1.4.3-SNAPSHOT/evidence/ARCH-001-ACT-001-independent-activation-signoff-2026-08-01-r4.md`
- blocking_items: none
- follow_up_required: yes — execute once, verify terminal convergence, then
  quarantine, destroy the exact target and purge six credential profiles
