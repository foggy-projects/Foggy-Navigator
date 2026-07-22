---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-010
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
bug_source: acceptance-found
approved_by: project-owner-continuation-through-bug009-signoff
approved_at: 2026-07-22
open_questions: []
---

# Delivery Spec: INT-001 process-group-empty cleanup proof

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: Repair the single blocking defect found by BUG-009 independent signoff without changing Navigator permissions, exposure, Worker routing, or runtime authority.
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-010-int001-process-group-empty-proof.md`

## Goal

- version_goal: Make forced-SIGNAL cleanup fail closed whenever any process remains in a run-owned child process group.
- target_outcome: `stop_owned_child` removes child metadata and permits `CLEANED` only after the exact recorded PGID is proven absent; a leader that exits while a TERM-resistant same-PGID descendant remains must retain metadata and return failed cleanup.

## Scope

- in_scope:
  - Add a deterministic offline regression for a dedicated process-group leader that exits on TERM while a same-PGID descendant ignores TERM and remains alive.
  - Add the smallest harness-only process-group absence proof needed before child metadata removal and cleanup success.
  - Preserve the existing leader ownership proof, exact process-group TERM target, bounded wait, TERM commit-race handling, optional KILL escalation only while ownership remains provable, reservation retention, and redacted failure receipt behavior.
  - Re-run the complete BUG-009 offline gate and update BUG-009/BUG-010 durable evidence.
- affected_modules:
  - `tools/navigator-upstream/scripts/`
  - `business-agent-module/integration-tests/`
  - `docs/version-tracker/1.4.3-SNAPSHOT/`
- external_dependencies: none; this repair and its acceptance gate are offline only.

## Non-Goals

- out_of_scope:
  - Any new disposable runtime, Runtime 10 retry/replacement, Docker/network execution, real TMS/SIM integration, Provider readiness, Worker Gateway external, or production readiness.
  - Navigator API/auth changes, Worker/WorkerHost/BizWorkerIdentity/WorkerPool changes, Codex routing, shared 8112, sibling workspaces, or real credentials.
  - Broad cleanup by port, fuzzy PID, shared UID, global process inventory, or killing a still-existing group after its recorded leader can no longer be re-proved.
- do_not_touch:
  - Runtime 4-10 private/children/log/profile/payload/process/Docker details and all shared or real upstream resources.

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| Require an exact kernel process-group absence result before success | Leader death does not imply descendant death. | Success is allowed only for `ESRCH`; permission, syscall, or interpretation failures remain unproven. |
| Retain metadata and fail closed when the leader is dead but its PGID still exists | With the ownership anchor gone, later destructive escalation risks acting on an unprovable/reused identity. | No metadata deletion, reservation release, or `CLEANED` receipt in this state. |
| Permit existing KILL escalation only after the exact leader is still re-proven owned | This preserves current bounded cleanup for a leader that ignores TERM without widening the target. | The group must still be proven absent after escalation before metadata removal. |
| Keep the TERM commit-race narrow | A TERM syscall failure may coincide with natural leader exit, but the group may still contain descendants. | The race succeeds only when both leader death and exact PGID absence are proven. |
| No runtime authority follows from this repair | Runtime 10 is permanently consumed and the defect is deterministically testable offline. | A future runtime requires a separate decision after offline repair/signoff. |

## Acceptance Criteria

- [x] AC-1: A pre-fix automated regression proves that leader death plus a surviving same-PGID TERM-resistant descendant is currently misclassified as successful cleanup or loses its metadata.
- [x] AC-2: After the fix, that regression returns failed cleanup, retains the exact child metadata, does not issue a broad or second destructive signal after leader ownership is lost, and the test-owned fixture performs its own bounded teardown.
- [x] AC-3: Cooperative termination, a leader that remains owned through KILL escalation, the exact TERM commit race, malformed/unprovable metadata, and no-metadata/proven-dead cases retain their prior intended outcomes.
- [x] AC-4: Child metadata is removed only after both the recorded leader is proven dead and the exact recorded process group is proven absent; any non-`ESRCH` group probe result is fail closed.
- [x] AC-5: The complete synthetic TypeScript suite, targeted safety suite, Python supervisor suite, typecheck, shell syntax, Python compile, diff check, and scoped high-confidence secret scan pass with exact results recorded.
- [x] AC-6: BUG-009 is returned to `READY_FOR_SIGNOFF` only after BUG-010 is implementation-complete; implementation does not set either item to `ACCEPTED` and does not claim INT-001, TMS/SIM, Gateway, Provider, or production readiness.

## Contract / Data / Security Constraints

- API or event contract: no Navigator public API, credential, capability, Worker, or Gateway change.
- data and migration: no database or shared-state migration.
- compatibility and rollback: harness-only, removable with this slice; uncertain cleanup remains a retained `FAILED_CLEANUP` state.
- permissions and secrets: do not read or persist historical runtime internals or any credential/profile/payload/log. Test fixtures use only test-owned processes and paths.

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| AC-1/AC-2 | critical false-clean | deterministic real-process offline regression before and after fix | failing-before/passing-after command and assertions |
| AC-3/AC-4 | cleanup regression or unsafe widening | focused ownership/race tests plus source-contract assertions | exact targeted test output |
| AC-5 | cross-harness regression | full existing offline gate | commands, exit status, counts, secret scan result |
| AC-6 | misleading readiness | canonical/index review | updated status and explicit remaining boundaries |

## Bug Context

- bug_source: acceptance-found
- severity: major
- environment: offline test-owned dedicated process group only.
- current_behavior: `stop_owned_child` waits for the recorded leader PID, then removes metadata and returns success even if a same-PGID descendant survives TERM.
- expected_behavior: cleanup success requires the recorded leader dead and its exact process group absent; otherwise metadata/reservation are retained and cleanup fails closed.
- reproduction_steps: start a dedicated group whose leader exits on TERM and whose descendant ignores TERM, register the leader through the real child metadata path, call `stop_owned_child`, and check return status, metadata, descendant liveness, and signal count.
- reproduction_status: confirmed by independent source audit; automated regression required before repair.
- existing_evidence: `../evidence/BUG-009-independent-signoff-2026-07-22.md`.
- existing_tests: BUG-009 targeted synthetic safety and supervisor suites; none currently covers a TERM-resistant descendant after leader exit.
- regression_protection: required
- waiver_reason_and_risk: N/A

## Risks and Open Questions

- known_risks: Process-group probing must distinguish `ESRCH` from permission or unexpected syscall failure; shell status alone must not turn an unproven state into success.
- open_questions: none

## Ultra Execution Contract

- Read this work item, root guidance, BUG-009 canonical/signoff, and the applicable runtime-provisioning constraints first.
- Establish the failing regression before the implementation change.
- Keep implementation local and avoid broad process enumeration or cleanup.
- If the fix requires changing the approved security boundary or runtime topology, set `NEEDS_REPLAN` and stop.
- Record changed paths, exact checks, deviations, and residual risks below; finish at `READY_FOR_SIGNOFF`, never `ACCEPTED`.

## Implementation Result

- implementation_summary:
  - Added exact kernel PGID absence proof with only `ESRCH` classified as absent; permission and unexpected failures remain fail closed.
  - Required leader death plus PGID absence before every child-metadata removal path, including dead-at-entry, TERM syscall race, cooperative TERM, and post-KILL finalization.
  - A leader-dead/live-group state now retains metadata and returns failed cleanup without a later destructive signal; KILL escalation remains limited to an exact leader that still passes ownership re-proof.
  - Added deterministic state-machine coverage and a real Linux dedicated-process-group fixture whose descendant ignores TERM; fixture teardown uses only its recorded exact PID/start ticks.
- changed_paths:
  - `tools/navigator-upstream/scripts/synthetic-upstream-harness.sh`
  - `business-agent-module/integration-tests/tests/05-synthetic-upstream-bootstrap-safety.test.ts`
  - this work item, BUG-009 canonical, version index, and durable test record
- tests_and_results:
  - pre-fix focused regression: FAIL as required, exit `72`, `groupProbes=0`, metadata absent;
  - targeted safety file: PASS, `92/92`;
  - complete synthetic TypeScript: PASS, `116 passed / 1 skipped`;
  - Python supervisor: PASS, `97/97`;
  - TypeScript typecheck, three-script shell syntax, Python compile, diff check: PASS;
  - scoped high-confidence conventional-secret scan: PASS, `0 matches`.
- manual_or_experience_evidence: no runtime is authorized or required
- deviations: none
- residual_risks: The disposable harness retains its already documented single-same-UID-operator threat model. The new kernel PGID probe avoids global process enumeration and does not widen cleanup authority.
- durable_record: `../test-records/BUG-010-int001-process-group-empty-offline-2026-07-22.md`
- readiness: READY_FOR_SIGNOFF

## References

- related work items: `BUG-009-int001-forced-signal-owned-cleanup.md`, `INT-001-synthetic-upstream-integration-harness.md`
- independent finding: `../evidence/BUG-009-independent-signoff-2026-07-22.md`
