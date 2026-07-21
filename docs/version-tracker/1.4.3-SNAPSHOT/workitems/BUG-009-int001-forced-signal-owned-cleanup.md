---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-009
status: NEEDS_REPLAN
canonical: true
execution_mode: ultra
bug_source: acceptance-found
approved_by: project-owner-user-confirmed
approved_at: 2026-07-21
continued_authorization: 2026-07-21-project-owner-user-confirmed
previous_replan_approved_at: 2026-07-21
open_questions:
  - exact-launcher-candidate-absent-after-candidate-first-rehearsal
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

## Approved Replan: Fixed-Enum Execution Projection

- approval: Project Owner approved ordered execution of P0-P3 on 2026-07-21.
- target: Add one supervisor-owned, artifact-root-level execution projection for a new run so an absent stdout summary can be distinguished from an early supervisor/child failure without reading the run's `private/`, `children/`, logs, profile, payload, process tree or Docker objects.
- location and lifecycle:
  - The projection is a sibling of the run directory under the already verified `temp/test-artifacts/INT-001/` root, not an entry inside the run directory.
  - It is created only for a fresh requested runId, as a non-symlink regular file owned by the current user with mode `0600` and one link.
  - It may remain as diagnostic evidence after either success or failure; it is not counted as run-owned cleanup residue and must never be used to authorize cleanup.
- fixed contract:
  - The document contains only `schemaVersion`, `runId`, `phase`, `outcome`, `receiptState`, `rootSnapshotState`, `stdoutSummaryState`, and `secretsRedacted`.
  - `phase`, `outcome`, and the three state fields use code-defined allow-listed enums. Unknown fields, duplicate keys, wrong types, unknown enum values, unsafe file shape or mismatched runId fail closed.
  - No PID, argv, cwd, port, inode, socket identifier, filename inventory, log, exception text, profile, credential, payload, timestamp from a private source, or Docker identifier/count is permitted in the projection.
- authority boundary:
  - The projection is diagnostic only. `phase=COMPLETE` or any success-shaped projection value does not prove parent ownership, listener ownership, exact TERM dispatch, private-carrier removal, Docker cleanup or forced-SIGNAL success.
  - The existing completion gate remains unchanged: strict parent/listener proof, exactly one TERM, `dispatchSafe=true`, child completion, a valid root `CLEANED/SIGNAL` receipt, absent private carrier, zero run-root residue and zero run-owned Docker residue.
  - If the projection is absent, malformed, incomplete or contradicts the existing evidence, the rehearsal remains fail closed.
- execution limit:
  - Complete offline regression and static validation first.
  - After those gates pass, execute at most one new loopback-only disposable rehearsal. Read only its validated projection, root receipt and permitted root-name/redacted residue evidence. On failure, record and stop without retry.

## Approved Replan: Candidate-First Strict Listener Proof

- status: APPROVED on 2026-07-21 by Project Owner; implementation and offline validation are authorized, followed by at most one new rehearsal only if every offline gate passes.
- static_diagnosis:
  - The held child's historical `HEALTH_READY` and the supervisor's strict listener proof are different predicates, so the recorded states are not contradictory.
  - The harness first proves that its Launcher child remains alive, then records `HEALTH_READY` when the target URL returns a successful response. It does not bind that response socket to the Launcher PID and does not continue monitoring the listener during the 180-second hold.
  - The supervisor instead requires one exact loopback LISTEN inode, one current-user holder, trusted Java, exact argv/runId, run-owned cwd, descendant lineage and stable start ticks before it performs its stronger `200 + {status: UP}` health check or considers TERM.
  - Current evidence cannot establish whether the mismatch came from procfs/environment visibility, a listener lifecycle change, or a responder not bound to the proven Launcher. The existing final `socket-listener-absent` label is insufficient to choose among those causes.
- proposed_decisions:
  - Preserve `launcherReadinessObservation=HEALTH_READY` as a low-authority historical diagnostic meaning only “owned Launcher child alive at the probe plus successful endpoint response.” It must never authorize TERM or retroactively prove listener ownership.
  - Replace socket-first discovery with candidate-first strict proof: identify exactly one current-user descendant matching trusted Java, exact argv/runId, run-owned cwd, lineage and stable start ticks; then prove from that candidate's procfs network/FD view that it alone holds one IPv4 `127.0.0.1:<port>` LISTEN socket.
  - Preserve A/B/final identity and socket reproof, signal-mask commit point, exactly-one-parent-TERM limit, and fail-closed handling for zero/multiple candidates, unavailable/malformed procfs, wildcard/IPv6/ambiguous listeners, FD mismatch or any identity change.
  - Add fixed-enum, non-authorizing mismatch diagnostics only if required by offline regression. They must not persist PID, port, inode, argv, cwd, path, exception text or any private value.
  - Do not solve this by trusting held-child `HEALTH_READY`, moving health ahead of ownership, extending the hold, weakening listener predicates, targeting a port/child/process group, or reading `children/`, logs or private carriers.
- required_offline_gates_before_runtime:
  - A deterministic topology regression using test-owned processes and real `/proc`: outer parent → held child → exact IPv4 listener, with no Docker, real Launcher, profile, credential or external service.
  - Positive candidate/socket proof plus negative zero/multiple candidate, argv/cwd/exe/lineage/start-tick drift, missing/malformed procfs, wildcard/IPv6/duplicate listener, FD mismatch and listener-loss cases.
  - A cross-layer fail-closed regression for `HEALTH_READY + listener ineligible + HOLD_TIMEOUT + CLEANED/UNKNOWN + EXIT_2 + 0 TERM`.
  - All existing Python, synthetic TypeScript, typecheck, shell syntax, diff and scoped secret checks remain mandatory.
- runtime_gate_after_approval:
  - Only after implementation and every offline gate passes may one new loopback-only disposable rehearsal be considered authorized.
  - That rehearsal retains the existing projection/root-receipt/root-name evidence limits and stops on first failure without retry or manual cleanup.

## Acceptance Criteria

- [x] AC-1: A stable automated regression demonstrates the relevant parent-TERM ownership/cleanup classification and proves that unproven ownership remains fail closed.
- [ ] AC-2: A fresh, healthy, loopback-only harness run proves its exercise parent by command line, cwd and runId, receives exactly one parent `TERM`, and its root-level redacted receipt reports `CLEANED/SIGNAL`.
- [ ] AC-3: The AC-2 run leaves no private carrier and no run-owned process/container/volume/file resource; evidence records only redacted receipt fields and ownership checks.
- [x] AC-4: 除新建、loopback-only、run-owned disposable target 内的 `NAVIGATOR_EXTERNAL_ENABLED=true` `/api/v1/open/**` 路由门禁外，不得读取、修改、启用或使用任何共享/真实 target、upstream profile、existing Worker、TMS/SIM resource、Worker Gateway external 或 production configuration；该唯一例外不表示 Provider、Gateway 或 production readiness，且 `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false` 必须得到验证。
- [x] AC-5: Relevant unit/integration/script checks actually pass; the work item, runbook and test record state exact commands, results, deviations and residual risk. Completion is at most `READY_FOR_SIGNOFF`.
- [x] AC-6: Candidate-first listener proof has deterministic real-procfs topology coverage and preserves or strengthens every existing owner/listener and one-TERM constraint.
- [x] AC-7: The historical `HEALTH_READY + listener ineligible + HOLD_TIMEOUT + CLEANED/UNKNOWN + EXIT_2 + 0 TERM` combination is covered as an explicit fail-closed contract and cannot authorize success.

## Contract / Data / Security Constraints

- API or event contract: No Navigator public API, credential lane, task capability, Worker routing or Gateway contract changes are permitted.
- data and migration: Disposable data may be created and removed only inside the fresh run's owned namespace; no shared database DDL, seed, export or cleanup is allowed.
- compatibility and rollback: The correction must be removable with the harness-only code and leave no shared-state migration. If a new run cannot prove ownership, it must remain a cleanup failure rather than take a broad fallback path.
- permissions and secrets: The runtime child remains `env -i` allow-listed. Never print or persist real/synthetic credential values, full profiles, payloads or raw logs; retain root-level redacted metadata only.

## Receipt v4 and Redacted Diagnostics

- New root `cleanup-report.json` receipts use exact schema v4 fields: `schemaVersion`, `runId`, `result`, `failureStage`, `rehearsalLifecycleObservation`, `launcherReadinessObservation`, `launcherFailureClass`, `finishedAtUtc`, and `secretsRedacted`. Reader and parent-adoption validation reject v3, duplicate JSON object keys, unknown fields, non-string enums, and unknown enum values fail closed.
- `rehearsalLifecycleObservation` is an allow-listed, non-secret diagnostic only: `NOT_REHEARSAL`, `HOLD_ENTERED`, `HOLD_TIMEOUT`, `HOLD_WAIT_FAILURE`, or `HOLD_SIGNAL_RECEIVED`. Normal paths remain `NOT_REHEARSAL`; the final value only records what the held child observed in its own lifecycle.
- In particular, `HOLD_SIGNAL_RECEIVED` does not prove an outer parent's authorization, exact TERM dispatch, target ownership, private-carrier removal, Docker cleanup, or forced-SIGNAL success. It is not a supervisor completion condition and cannot change a failed run into a successful one.
- `listenerProofEverEligible` exists only in the redacted supervisor JSON, never in the root receipt. It is a boolean that an eligible listener proof was observed at least once; it contains no PID, argv, cwd, port, inode, or socket identifier. A later re-proof failure remains fail closed and this diagnostic cannot authorize TERM or cleanup.

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
- open_questions: Which exact Launcher identity predicate was ineligible at the candidate-first proof point. The fixed-enum evidence intentionally does not expose PID, argv, cwd, path or process detail, so this requires a separately approved diagnostic design rather than failed-run inspection.

## Ultra Execution Contract

- Implement only the approved candidate-first proof and offline topology gates. Do not start another rehearsal until all offline gates pass.
- First read this work item, root `AGENTS.md`, `CLAUDE.md`, the INT-001 runbook and `navigator-runtime-provisioning` skill.
- Set the work item to `ULTRA_EXECUTING` before implementation. Within scope, choose the smallest maintainable harness/test structure and prefer a failing regression before the fix.
- Never read or touch the failed run; only a new owned run may be created, signalled or cleaned.
- If diagnosis requires a public API/config/authorization change, shared target, real credential/profile, Worker/Pool/Identity action, Gateway/external enablement, or weaker ownership proof, set `NEEDS_REPLAN` and stop that expansion.
- Record changed paths, exact verification commands/results, evidence locations, deviations and residual risk. On completion set `READY_FOR_SIGNOFF`; do not set `ACCEPTED`.

## Implementation Result

- implementation_summary: The approved candidate-first replan is implemented. The supervisor first identifies exactly one current-user Launcher descendant by trusted Java executable, exact argv/runId, run-owned cwd, lineage and stable start ticks; it then reads that candidate's own `/proc/<pid>/net/tcp{,6}`, requires one exact IPv4 `127.0.0.1:<port>` LISTEN inode, candidate FD ownership and current-user unique-holder proof, and repeats identity/socket proof before authorization. Post-rehearsal security review additionally hardened identity discovery into explicit `MATCH / MISMATCH / PROC_UNAVAILABLE / PROC_MALFORMED` results: only confirmed disappearance or PID owner replacement is treated as a harmless race, while an existing current-user process with unreadable or malformed stat/cmdline/cwd/exe/lineage rejects the whole proof. Existing A/B/final reproof, signal-mask commit point, exactly-one-parent-TERM limit and fixed-enum projection remain unchanged. All offline gates passed, but the single authorized rehearsal still returned `listener-candidate-absent`, sent zero TERM and ended `CLEANED/UNKNOWN + HOLD_TIMEOUT`. The implementation therefore remains fail-closed but does not resolve AC-2/AC-3.
- changed_paths:
  - `tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py`
  - `tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py`
  - `business-agent-module/integration-tests/tests/05-synthetic-upstream-bootstrap-safety.test.ts`
  - `docs/version-tracker/1.4.3-SNAPSHOT/runbooks/INT-001-synthetic-upstream-integration-harness.md`
  - `docs/version-tracker/1.4.3-SNAPSHOT/test-records/BUG-009-int001-candidate-first-runtime-2026-07-21.md`
  - this canonical work item and the version index
- tests_and_results:
  - `PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py` — PASS, 61 offline tests, no skips. Coverage includes exact candidate identity, explicit unavailable/malformed/disappeared-race/PID-owner-replacement classification for stat/cmdline/cwd/exe/lineage, zero/one/multiple candidates, readable unrelated argv mismatch, procfs/table failures, wildcard/IPv6/ambiguous listeners, candidate FD and unique current-user holder proof, listener/identity drift, a test-owned outer-parent -> held-child -> IPv4 LISTEN real-procfs full `identity -> find -> prove` chain, and the historical fail-closed state combination.
  - `bash -n tools/navigator-upstream/scripts/synthetic-upstream-harness.sh tools/navigator-upstream/scripts/synthetic-upstream-bootstrap.sh tools/navigator-upstream/scripts/synthetic-upstream-runtime-audit.sh` — PASS.
  - `(cd business-agent-module/integration-tests && env -u INT001_SYNTHETIC_UPSTREAM_HARNESS -u INT001_RUNTIME_PROBE npm run test:synthetic)` — PASS, 94 passed / 1 skipped, including the explicit `HEALTH_READY + HOLD_TIMEOUT + CLEANED/UNKNOWN` low-authority regression.
  - `(cd business-agent-module/integration-tests && npm run typecheck)` — PASS.
  - `python3 -m py_compile tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py` — PASS.
  - `git diff --check` — PASS.
  - Scoped added-line high-confidence secret scan over all seven changed paths, including the new durable test record — PASS, `0 matches`.
  - Independent read-only security review — PASS, no blocking finding. It confirmed explicit fail-closed identity states, candidate-owned net/FD/holder proof, A/B/final reproof, signal-mask commit point and at-most-one TERM. Its two non-blocking test-precision suggestions (PID owner replacement and malformed lineage) were added before final verification.
- historical_runtime_results:
  - Fresh foreground supervisor rehearsal `int001-bug009-20260721-k6f8m2q9` — FAIL CLOSED, exit `1`; it did not dispatch TERM and cannot satisfy AC-2/AC-3.
  - Fresh foreground supervisor rehearsals `int001-bug009-20260721-r6a1p9k4` and `int001-bug009-20260721-v2m8q4z7` — NOT SUCCESSFUL; this session received no redacted supervisor summary from the execution wrapper. Each permitted root-name snapshot had no `cleanup-report.json`, retained `private/`, and had seven non-receipt entries. Neither can satisfy AC-2/AC-3.
  - Single approved fixed-enum rehearsal `int001-bug009-20260721-p7m3c6r2` — FAIL CLOSED, exit `1`; projection `COMPLETE/CHILD_EXITED_BEFORE_HEALTH`, supervisor listener proof `socket-listener-absent`, zero TERM, receipt `CLEANED/UNKNOWN` with `HOLD_TIMEOUT`, private absent, root residue `0`, redacted Docker residue counts `0/0/0`.
- candidate_first_runtime_result:
  - `int001-bug009-20260721-c4n8v2k6` — FAIL CLOSED, exit `1`; projection `COMPLETE/CHILD_EXITED_BEFORE_HEALTH`, listener proof `listener-candidate-absent`, listener never eligible, parent proof not attempted, zero TERM, `dispatchSafe=false`, receipt `CLEANED/UNKNOWN` with `HEALTH_READY + HOLD_TIMEOUT`, private absent, root residue `0`, Docker residue counts `0/0/0`.
  - durable_record: `../test-records/BUG-009-int001-candidate-first-runtime-2026-07-21.md`
- runtime_authorization: consumed. The exact runId executed once and must not be retried or replaced without a new approved plan.
- manual_or_experience_evidence: Only the new run's fixed-enum projection, redacted supervisor stdout, root receipt and permitted redacted residue counts were read. No historical run, private carrier, `children/` metadata, log, profile, payload, process detail or Docker object was inspected.
- deviations: The offline implementation matched the approved design, but the runtime did not produce an exact Launcher candidate. No owner/listener predicate was weakened and no retry was attempted.
- residual_risks:
  - POSIX cannot atomically combine the final pending-signal observation and `kill()`; the explicit commit-point rule remains fail closed.
  - No positive runtime evidence exists for exactly one TERM, `dispatchSafe=true`, `CLEANED/SIGNAL`, or the controlled-health/strict listener proof.
  - An unreadable or malformed identity component is retained only as the fixed redacted reason `listener-candidate-proc-unavailable` or `listener-candidate-proc-malformed`; it rejects discovery/reproof without exposing process detail, so diagnostic granularity remains intentionally limited.
  - The historical run and all three failed fresh runs remain read-prohibited beyond their recorded redacted facts; no private carrier, children metadata, log, profile, payload, process, Docker object, retry, or manual cleanup was accessed.
  - The new fixed reason proves only that no exact candidate satisfied every identity predicate at the proof point; it does not identify which predicate differed, and the approved evidence boundary forbids filling that gap from failed-run private/process data.
- readiness: NEEDS_REPLAN; candidate-first implementation and offline gates passed, but the sole runtime gate failed before health/parent proof and TERM dispatch.
- signoff_eligibility: no; AC-2 and AC-3 remain unmet.

## Historical Continuation Authorization

On 2026-07-21, after the recorded fail-closed rehearsal, the Project Owner explicitly authorized continued isolated diagnosis and fresh retries. That authorization remained inside the existing harness-only boundary: every retry used a new loopback-only disposable run, preserved strict ownership proof and redacted root-level evidence, and did not read, enumerate, retry, clean, or otherwise touch historical failed runs. It did not authorize access to real TMS/SIM, shared `8112`, profiles or credentials, Workers, Gateway, Pool, identity, Codex routing, external enablement, or production configuration. The fixed-enum `p7m3c6r2` runtime limit and later candidate-first `c4n8v2k6` runtime limit are both exhausted.

## Execution Stop and Replan Trigger

- The authorized follow-up runs `r6a1p9k4` and `v2m8q4z7` were each fresh, loopback-only, and evidence-limited. They did not produce the required redacted completion evidence; their permitted root snapshots showed no receipt, retained `private/`, and seven root non-receipt entries.
- Do not create a blind retry, reuse a historical runId or inspect the failed run beyond its already recorded allowed evidence. The candidate-first implementation and offline topology gates are complete, but the exact fresh runId recorded in `runtime_authorization` has executed and the window is closed. Any next diagnostic or runtime step requires a new approved plan preserving `env -i`, strict owner/listener proof, no private/children/log/profile/payload access, no manual cleanup, and all existing external/Gateway/production exclusions.
- The approved projection plan was implemented and the single permitted run `p7m3c6r2` completed with usable redacted evidence. It sent zero TERM because strict listener proof remained `socket-listener-absent`; the child later produced `CLEANED/UNKNOWN` with `HOLD_TIMEOUT`. That historical limit remains exhausted and is not reusable.

## Blocking Effect

- INT-001: independent signoff must be recorded as rejected on AC-2 until a fresh proof returns `CLEANED/SIGNAL`.
- BUG-008: independently signed off as `accepted-with-risks` on its normal owner-context proof. That risk-bounded decision explicitly excludes, does not accept, and does not remediate INT-001's rejected forced-SIGNAL cleanup or BUG-009.
- BUG-009: `NEEDS_REPLAN`; candidate-first and all offline gates passed, but the single runtime gate returned `listener-candidate-absent`, zero TERM and `CLEANED/UNKNOWN + HOLD_TIMEOUT`.

## References

- snapshot: `../evidence/INT-001-BUG-008-signoff-snapshot-2026-07-21.md`
- harness delivery spec: `INT-001-synthetic-upstream-integration-harness.md`
- owner-context delivery spec: `BUG-008-openapi-upstream-physical-langgraph-identity-context.md`
