---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-009
status: ULTRA_EXECUTING
canonical: true
execution_mode: ultra
bug_source: acceptance-found
approved_by: project-owner-user-confirmed
approved_at: 2026-07-21
continued_authorization: 2026-07-21-project-owner-user-confirmed
open_questions: []
---

# Delivery Spec: INT-001 forced-SIGNAL owned cleanup returns FAILED_CLEANUP

## Document Purpose

- intended_for: ultra-implementation / project-owner / later independent signoff
- purpose: Freeze the approved, harness-only diagnosis and correction of the INT-001 forced-SIGNAL cleanup defect without weakening ownership or secret boundaries.
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-009-int001-forced-signal-owned-cleanup.md`

## Acceptance-Failure Observation

- discovered_by: INT-001 / BUG-008 independent signoff on 2026-07-21.
- environment: a new harness-owned disposable loopback stack only; no shared 8112, shared database, real upstream profile, existing Worker, TMS, or SIM resource was used.
- controlled_action: after the isolated Launcher health endpoint became reachable, the test proved the `exercise` parent by cwd, command line, and runId, then sent exactly one `TERM` to that parent. It did not target a port process or a child PID.
- safe_root_evidence:
  - runId: `int001-signal-20260721-a9b0c1`
  - cleanup_receipt_sha256: `917c868fa520ebccb7b9a3b7a6b1ace00f3b2c5b6039d4129cd73fbb6b68016e`
  - receipt_mode_owner_links: `0600 / 1001 / 1`
  - receipt_fields: `result=FAILED_CLEANUP`, `failureStage=SIGNAL`, `secretsRedacted=true`, `launcherReadinessObservation=NOT_OBSERVED`, `launcherFailureClass=NOT_APPLICABLE`
  - private_carrier: absent after the failure path.
- prohibited_evidence_access: No `private/` file, process log, child ownership metadata, credential, profile, or runtime payload was read. No manual cleanup or retry was performed after the failed result.

## Expected Versus Actual

| Item | Expected contract | Observed result | Impact |
|---|---|---|---|
| Parent-TERM forced failure cleanup | An owned disposable stack reaches `CLEANED` with `failureStage=SIGNAL`, redacted receipt, and no private carrier/resources retained. | The redacted root receipt reports `FAILED_CLEANUP/SIGNAL`; private carrier is absent. | INT-001 AC-2 is not met; cleanup cannot be accepted as complete. |

## Evidence and Documentation Ambiguity

- Static review, without opening the failed run's `children/` directory, establishes that a cleanup failure may retain `children/` `0600` non-secret ownership-remediation metadata (PID/start ticks/PGID/SID/CWD/command fragment) while `private/` is scrubbed.
- INT-001's contract and runbook currently say failure retention is limited to a root-level redacted manifest/diagnostic summary. That wording is therefore ambiguous against the implementation's possible `children/` metadata retention and must be resolved by the future approved BUG-009 scope.
- This ambiguity is not a finding that a secret was exposed: no `children/`, private carrier, log, profile, credential, or runtime payload was read. It also cannot downgrade or reclassify the root receipt's `FAILED_CLEANUP/SIGNAL` result; the unresolved cleanup remains the INT-001 AC-2 failure.

## Goal

- version_goal: Restore a trustworthy internal synthetic-runtime prerequisite before real TMS/SIM integration is attempted, without allowing cleanup success to conceal an unproven owned resource.
- target_outcome: A newly created, provably harness-owned disposable stack can receive one controlled parent `TERM` and emit a root-level redacted receipt with `result=CLEANED` and `failureStage=SIGNAL`; an unknown or unprovable resource still fails closed.

## Scope

- in_scope:
  - Diagnose the parent-signal, delegated lifecycle, lock and ownership-cleanup transition through static source review plus new disposable runs and root-level redacted receipts only.
  - Add durable automated regression coverage for the lifecycle classification/ownership rule, then make the smallest harness-only correction required for a fresh healthy parent-TERM run to reach `CLEANED/SIGNAL`.
  - Verify the new run's private carrier is absent after cleanup and that only its run-owned process/container/volume/file namespace was considered for cleanup; record identifiers, classifications and command results only.
  - Align the INT-001 harness/runbook retention wording with the implemented redacted ownership-remediation behavior if the correction makes that distinction material.
- affected_modules:
  - `tools/navigator-upstream/scripts/`
  - `tools/navigator-upstream/fixtures/synthetic-integration/`
  - `business-agent-module/integration-tests/`
  - `docs/version-tracker/1.4.3-SNAPSHOT/`
- external_dependencies: Docker/Compose and the current local source build are permitted only in a newly created loopback-only disposable target with unique run ownership.

## Non-Goals

- out_of_scope:
  - Any manual cleanup, inspection of the failed run's private/log/children artifacts, retry of `int001-signal-20260721-a9b0c1`, or modification of shared host processes/resources.
  - TMS/SIM, current 8112, real profiles/credentials, Worker/WorkerHost/BizWorkerIdentity/WorkerPool, Codex routing, Gateway external, Open API authorization semantics, provider readiness, or production enablement.
  - Treating `NAVIGATOR_EXTERNAL_ENABLED=true` as anything beyond the disposable Open API route gate, or enabling `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED`.
- do_not_touch:
  - Existing shared databases, ports, workers, sibling workspaces, real `.navigator` profiles, or external credentials/accounts.
  - The failed run's filesystem, carrier, child metadata, logs, containers, volumes or process tree.

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| Diagnose only a new owned run and static source | The prior failed run remains evidence-only and must not become an inspection target. | Root-level redacted receipts are the only runtime evidence surface. |
| Preserve fail-closed cleanup | A resource whose ownership cannot be proved cannot be silently removed or reported as clean. | `FAILED_CLEANUP` remains correct for unknown/unprovable ownership. |
| Target only the proven exercise parent | Port scans and arbitrary child PIDs are not proof of ownership. | Command line, cwd and unique runId must establish ownership before one `TERM`. |
| Keep Open API/Gateway semantics unchanged | BUG-009 is a harness lifecycle repair, not an authorization or exposure change. | `NAVIGATOR_EXTERNAL_ENABLED=true` stays a disposable route gate; Gateway external remains false. |
| Keep evidence non-secret | Cleanup diagnosis must not trade confidentiality for observability. | No profile, credential, payload, private carrier, raw log or historical child artifact may enter durable evidence. |

## Acceptance Criteria

- [x] AC-1: A stable automated regression demonstrates the relevant parent-TERM ownership/cleanup classification and proves that unproven ownership remains fail closed.
- [ ] AC-2: A fresh, healthy, loopback-only harness run proves its exercise parent by command line, cwd and runId, receives exactly one parent `TERM`, and its root-level redacted receipt reports `CLEANED/SIGNAL`.
- [ ] AC-3: The AC-2 run leaves no private carrier and no run-owned process/container/volume/file resource; evidence records only redacted receipt fields and ownership checks.
- [x] AC-4: 除新建、loopback-only、run-owned disposable target 内的 `NAVIGATOR_EXTERNAL_ENABLED=true` `/api/v1/open/**` 路由门禁外，不得读取、修改、启用或使用任何共享/真实 target、upstream profile、existing Worker、TMS/SIM resource、Worker Gateway external 或 production configuration；该唯一例外不表示 Provider、Gateway 或 production readiness，且 `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false` 必须得到验证。
- [ ] AC-5: Relevant unit/integration/script checks actually pass; the work item, runbook and test record state exact commands, results, deviations and residual risk. Completion is at most `READY_FOR_SIGNOFF`.

## Contract / Data / Security Constraints

- API or event contract: No Navigator public API, credential lane, task capability, Worker routing or Gateway contract changes are permitted.
- data and migration: Disposable data may be created and removed only inside the fresh run's owned namespace; no shared database DDL, seed, export or cleanup is allowed.
- compatibility and rollback: The correction must be removable with the harness-only code and leave no shared-state migration. If a new run cannot prove ownership, it must remain a cleanup failure rather than take a broad fallback path.
- permissions and secrets: The runtime child remains `env -i` allow-listed. Never print or persist real/synthetic credential values, full profiles, payloads or raw logs; retain root-level redacted metadata only.

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| lifecycle regression | false-clean or false-failure | focused automated regression before/after the correction | exact test command and result |
| fresh forced-SIGNAL run | orphaned owned resources | one newly created, proven exercise parent receives one `TERM` | runId plus root redacted receipt status/classification only |
| ownership fail-closed path | broad accidental cleanup | automated or deterministic negative ownership assertion | no deletion/cleanup success for unproven target |
| harness hygiene | secret leak or shared-state access | `npm run test:synthetic`, typecheck, Python fixture tests, three `bash -n` checks, `git diff --check`, scoped secret scan | exact output summary and any environment blocker |
| documentation | misleading recovery claim | update canonical work item/runbook/test record | changed paths and precise retention semantics |

## Bug Context

- bug_source: acceptance-found
- severity: major
- environment: fresh loopback-only disposable INT-001 stack; no shared 8112/database, real upstream profile, existing Worker, TMS or SIM resource.
- current_behavior: A proven exercise parent receives one `TERM`, but the root receipt reports `FAILED_CLEANUP/SIGNAL` despite the private carrier being absent.
- expected_behavior: A healthy owned parent-TERM path reports `CLEANED/SIGNAL` only after its owned resources are proven absent; uncertain ownership remains failed.
- reproduction_steps: Create a new disposable target, establish the exercise parent by command line/cwd/runId, send one `TERM`, and inspect only its root-level redacted cleanup receipt.
- reproduction_status: confirmed
- existing_evidence: `int001-signal-20260721-a9b0c1` root receipt SHA-256 `917c868fa520ebccb7b9a3b7a6b1ace00f3b2c5b6039d4129cd73fbb6b68016e`; it remains read-prohibited beyond the recorded fields.
- existing_tests: INT-001 synthetic safety/config/lifecycle checks and the recorded normal disposable replay.
- regression_protection: required
- waiver_reason_and_risk: N/A

## Risks and Open Questions

- known_risks:
  - The original failure may not reproduce on a fresh run. In that case do not claim a fix; record the exact result and use `BLOCKED` or `NEEDS_REPLAN` if a new scope is necessary.
  - A real cleanup defect can look fixed if ownership proof is weakened. Preserve explicit failure for unknown processes/resources.
  - This work does not make INT-001 accepted, real upstream integration ready, Gateway external, Provider ready or production ready without a new independent signoff.
- open_questions: none; implementation root cause and local file structure are Ultra decisions inside the approved boundary.

## Ultra Execution Contract

- First read this work item, root `AGENTS.md`, `CLAUDE.md`, the INT-001 runbook and `navigator-runtime-provisioning` skill.
- Set the work item to `ULTRA_EXECUTING` before implementation. Within scope, choose the smallest maintainable harness/test structure and prefer a failing regression before the fix.
- Never read or touch the failed run; only a new owned run may be created, signalled or cleaned.
- If diagnosis requires a public API/config/authorization change, shared target, real credential/profile, Worker/Pool/Identity action, Gateway/external enablement, or weaker ownership proof, set `NEEDS_REPLAN` and stop that expansion.
- Record changed paths, exact verification commands/results, evidence locations, deviations and residual risk. On completion set `READY_FOR_SIGNOFF`; do not set `ACCEPTED`.

## Implementation Result

- implementation_summary: The approved harness-only topology correction keeps the supervisor's exact outer `exercise --forced-signal-rehearsal` parent alive while a separately-sessioned canonical `run --hold-for-parent-term` child owns the healthy Launcher lifecycle. The outer process forwards its one received TERM only after re-proving that held child; the held child then uses the existing ownership-checked cleanup path. Offline regression coverage passes, but the sole permitted post-correction fresh rehearsal did not reach the controlled-health and strict listener proof. It therefore sent no TERM and remains blocked rather than ready for signoff.
- changed_paths:
  - `tools/navigator-upstream/scripts/synthetic-upstream-harness.sh`
  - `tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py`
  - `tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py`
  - `business-agent-module/integration-tests/tests/05-synthetic-upstream-bootstrap-safety.test.ts`
  - `docs/version-tracker/1.4.3-SNAPSHOT/runbooks/INT-001-synthetic-upstream-integration-harness.md`
  - `docs/version-tracker/1.4.3-SNAPSHOT/test-records/BUG-009-int001-forced-signal-runtime-2026-07-21-k6f8m2q9.md`
  - this canonical work item and the version index
- tests_and_results:
  - `PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py` — PASS, 34 offline tests.
  - `bash -n tools/navigator-upstream/scripts/synthetic-upstream-harness.sh tools/navigator-upstream/scripts/synthetic-upstream-bootstrap.sh tools/navigator-upstream/scripts/synthetic-upstream-runtime-audit.sh` — PASS.
  - `(cd business-agent-module/integration-tests && env -u INT001_SYNTHETIC_UPSTREAM_HARNESS -u INT001_RUNTIME_PROBE npm run test:synthetic)` — PASS, 89 passed / 1 skipped.
  - `(cd business-agent-module/integration-tests && npm run typecheck)` — PASS.
  - Fresh foreground supervisor rehearsal `int001-bug009-20260721-k6f8m2q9` — FAIL CLOSED, exit `1`; it did not dispatch TERM and cannot satisfy AC-2/AC-3.
- manual_or_experience_evidence: Only the new supervisor's redacted stdout projection was read. `controlledHealthPrecondition=false`, `parentProof=NOT_ATTEMPTED`, `listenerProof=socket-listener`, `termDispatches=0`, `dispatchSafe=false`, `exerciseExit=EXIT_2`, `receipt=CLEANED/UNKNOWN/redacted`, `privateAbsent=true`, `rootNonReceiptResidueCount=0`, Docker residue counts all `0`, and `supervisorInterruption=NONE`.
- deviations: The fresh rehearsal was deliberately not retried. No private carrier, `children/` metadata, log, profile, payload, process detail or Docker object was inspected after its redacted projection. The `socket-listener` enum is only the supervisor's final fixed reason and is not a license to weaken listener or ownership proof.
- residual_risks:
  - POSIX cannot atomically combine the final pending-signal observation and `kill()`; the explicit commit-point rule remains fail closed.
  - No positive runtime evidence exists for exactly one TERM, `dispatchSafe=true`, `CLEANED/SIGNAL`, or the required controlled-health/strict listener proof.
  - Both failed fresh runs and the historical failed run remain read-prohibited beyond their already recorded redacted facts; no private carrier, children metadata, log, profile, payload, process, Docker object, retry, or manual cleanup was accessed.
- readiness: ULTRA_EXECUTING
- signoff_eligibility: no; AC-2 and AC-3 still require new positive runtime evidence after the approved topology correction.

## Continuation Authorization

On 2026-07-21, after the recorded fail-closed rehearsal, the Project Owner explicitly authorized continued isolated diagnosis and fresh retries. This reopens execution only inside the existing harness-only boundary: every retry must use a new loopback-only disposable run, preserve strict ownership proof and redacted root-level evidence, and must not read, enumerate, retry, clean, or otherwise touch either historical failed run. It does not authorize access to real TMS/SIM, shared `8112`, profiles or credentials, Workers, Gateway, Pool, identity, Codex routing, external enablement, or production configuration.

## Blocking Effect

- INT-001: independent signoff must be recorded as rejected on AC-2 until a fresh proof returns `CLEANED/SIGNAL`.
- BUG-008: independently signed off as `accepted-with-risks` on its normal owner-context proof. That risk-bounded decision explicitly excludes, does not accept, and does not remediate INT-001's rejected forced-SIGNAL cleanup or BUG-009.

## References

- snapshot: `../evidence/INT-001-BUG-008-signoff-snapshot-2026-07-21.md`
- harness delivery spec: `INT-001-synthetic-upstream-integration-harness.md`
- owner-context delivery spec: `BUG-008-openapi-upstream-physical-langgraph-identity-context.md`
