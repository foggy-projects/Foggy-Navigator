---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-009
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
bug_source: acceptance-found
approved_by: project-owner-user-confirmed
approved_at: 2026-07-21
historical_continued_authorization: 2026-07-21-project-owner-user-confirmed-consumed
previous_replan_approved_at: 2026-07-21
candidate_domain_replan_approved_at: 2026-07-21
current_runtime_authorization: none
latest_consumed_runtime: consumed-success-int001-bug009-20260722-r10-9047a550
historical_runtime_authorization_basis: 2026-07-22-project-owner-continuation-through-bug009-signoff-consumed
port_reservation_replan_approved_at: 2026-07-22
stable_hold_topology_replan_approved_at: 2026-07-22
host_namespace_socket_holder_replan_approved_at: 2026-07-22
runtime9_postterm_replan_approved_at: 2026-07-22
runtime7_authorized_at: 2026-07-22
runtime8_authorized_at: 2026-07-22
runtime10_authorized_at: 2026-07-22
acceptance_record: ../evidence/BUG-009-independent-signoff-2026-07-22.md
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
- Historical note: INT-001's contract and runbook described failure retention as limited to a root-level redacted manifest/diagnostic summary, which was ambiguous against possible `children/` ownership-remediation metadata. This remains deferred documentation context only; it does not authorize artifact inspection or runtime and is not the current next action.
- This ambiguity is not a finding that a secret was exposed: no `children/`, private carrier, log, profile, credential, or runtime payload was read. It also cannot downgrade or reclassify the root receipt's `FAILED_CLEANUP/SIGNAL` result; the unresolved cleanup remains the INT-001 AC-2 failure.

## Goal

- version_goal: Restore a trustworthy internal synthetic-runtime prerequisite before real TMS/SIM integration is attempted, without allowing cleanup success to conceal an unproven owned resource.
- target_outcome: A newly created, provably harness-owned disposable stack can receive one controlled parent `TERM` and emit a root-level redacted receipt with `result=CLEANED` and `failureStage=SIGNAL`; an unknown or unprovable resource still fails closed.

## Scope

- scope_note: The disposable runtime bullets below describe the BUG-level target and final acceptance obligation. Runtime 4, 5, 6, 7, 8 and 9 each executed once and are permanently consumed fail-closed; Runtime 10 executed once and is permanently consumed success. None may be retried or replaced as though it were the same authorization. Independent BUG-level signoff rejected AC-1/AC-3 because successful cleanup proves only the recorded process-group leader PID dead, not the absence of TERM-resistant run-owned descendants; no runtime is authorized.
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
- external_dependencies: Docker/Compose and current local source builds were used by the already consumed disposable runtime commands, including Runtime 4 through Runtime 10. Runtime 4–9 are consumed fail-closed and Runtime 10 is consumed success; no current runtime, evidence expansion or production authority exists.

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
| Treat Runtime 4 as consumed and diagnose only static source plus its allowed redacted summary | Prior failed runs and Runtime 4 private artifacts remain prohibited inspection targets. | No retry, replacement runId, manual cleanup or private/process/Docker inspection; any future runtime requires a new bounded offline replan and full gate. |
| Preserve fail-closed cleanup | A resource whose ownership cannot be proved cannot be silently removed or reported as clean. | `FAILED_CLEANUP` remains correct for unknown/unprovable ownership. |
| Target only the proven exercise parent | Port scans and arbitrary child PIDs are not proof of ownership. | Command line, cwd and unique runId must establish ownership before one `TERM`. |
| Keep Open API/Gateway semantics unchanged | BUG-009 is a harness lifecycle repair, not an authorization or exposure change. | `NAVIGATOR_EXTERNAL_ENABLED=true` stays a disposable route gate; Gateway external remains false. |
| Keep evidence non-secret | Cleanup diagnosis must not trade confidentiality for observability. | No profile, credential, payload, private carrier, raw log or historical child artifact may enter durable evidence. |

## Approved Replan: Fixed-Enum Execution Projection

- historical_status: CONSUMED. This section records a past authorization and grants no current runtime permission.
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
  - The historical execution required offline regression and static validation first.
  - After those gates passed, it allowed at most one new loopback-only disposable rehearsal using only the validated projection, root receipt and permitted root-name/redacted residue evidence. That authorization was consumed and does not permit another rehearsal.

## Approved Replan: Candidate-First Strict Listener Proof

- historical_status: APPROVED_AND_CONSUMED on 2026-07-21 by Project Owner. This section records the past candidate-first implementation/runtime boundary and grants no current runtime permission.
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
- historical_runtime_gate:
  - The past authorization allowed one new loopback-only disposable rehearsal only after implementation and every offline gate passed.
  - That rehearsal retained the existing projection/root-receipt/root-name evidence limits and stopped on first failure without retry or manual cleanup. The authorization is consumed and cannot be reused or inferred from current offline success.

## Approved Diagnostic Slice 2: Fixed-Enum Identity Progress

- historical_status: `COMPLETED_AND_SUPERSEDED`. This section records an earlier offline-only diagnostic slice and grants no current authority.
- approval: Project Owner approved this bounded offline-only slice on 2026-07-21.
- target: Replace the undifferentiated `listener-candidate-absent` diagnostic with one non-authorizing fixed enum that identifies the furthest exact Launcher identity stage reached, or the fail-closed procfs class, without exposing process values.
- fixed enum contract:
  - `NOT_OBSERVED`
  - `NO_TRUSTED_JAVA_CANDIDATE`
  - `NO_EXACT_ARGV_MATCH`
  - `ARGV_MATCH_CWD_MISMATCH`
  - `ARGV_CWD_MATCH_EXE_MISMATCH`
  - `ARGV_CWD_EXE_MATCH_LINEAGE_MISMATCH`
  - `IDENTITY_STABILITY_MISMATCH`
  - `PROC_UNAVAILABLE`
  - `PROC_MALFORMED`
  - `EXACT_CANDIDATE_FOUND`
  - `EXACT_CANDIDATE_AMBIGUOUS`
- security and authority:
  - The enum is diagnostic only and must never authorize health, TERM, cleanup, success, or receipt acceptance.
  - It may appear only in the redacted supervisor summary/in-memory result. It must not change the root receipt, execution projection, Navigator API, Gateway, credential, permission, Worker or routing contracts.
  - It must not contain or derive a persisted PID, port, inode, argv, cwd, executable/path, exception text, process count, raw procfs value, profile, credential, payload, log or Docker identifier.
  - Zero/multiple exact candidates, unavailable/malformed procfs, identity drift and all existing A/B/final reproof failures remain fail closed; the existing exactly-one-TERM gate is unchanged.
- deterministic precedence:
  - `PROC_MALFORMED` and `PROC_UNAVAILABLE` take precedence over mismatch progress because incomplete enumeration cannot prove the candidate set.
  - Otherwise retain only the furthest fixed identity stage observed; exact one/multiple identity matches use `EXACT_CANDIDATE_FOUND` / `EXACT_CANDIDATE_AMBIGUOUS`. Neither implies socket eligibility, and the ambiguous class exposes no count.
- execution limit:
  - Add a failing regression first, implement the smallest supervisor/test change, run the complete existing offline gates, and obtain an independent read-only security review.
  - Stop after offline review. This slice authorizes no rehearsal, no new runId, no historical-run inspection and no manual cleanup.

## Approved Diagnostic Slice 3: Authoritative Launcher Argv Contract

- historical_status: `COMPLETED_AND_SUPERSEDED`. This section records an earlier offline-only diagnostic slice and grants no current authority.
- approval: Project Owner approved this offline-only replan on 2026-07-21.
- target: Explain and correct the `NO_EXACT_ARGV_MATCH` result by aligning the supervisor with the authoritative production harness Launcher invocation, while preserving an exact, fail-closed identity proof.
- in_scope:
  - Establish a failing automated regression that exercises a test-owned process through the same `setsid -> env -i -> resolved Java -> -Dint001.run-id -> -jar -> mock profile` argv shape used by the harness and observes its real `/proc` command line.
  - Compare only test-owned process values and current source contracts. Do not inspect any historical or failed rehearsal process, private carrier, metadata or log.
  - Implement the smallest maintainable correction in the harness/supervisor contract or its offline test coverage. Keep exact argv, trusted executable, cwd, lineage, stability, socket and one-TERM gates fail closed.
  - Run the complete Python, synthetic TypeScript, typecheck, shell syntax, compile, diff and scoped secret gates, followed by an independent read-only security review.
- non_goals:
  - No rehearsal, new runId, runtime authorization, historical-run inspection or manual cleanup.
  - No fuzzy/subsequence argv matching, wildcard Java/JAR/profile acceptance, held-child health promotion, port/PID/process-group targeting, or weaker owner/listener proof.
  - No Navigator API, credential, permission, Worker, Gateway, Pool, identity, Codex route, external, production, TMS or SIM change.
- acceptance:
  - The new regression fails against the pre-fix contract for the same fixed reason represented by `NO_EXACT_ARGV_MATCH`, then passes after the correction.
  - Positive coverage proves the authoritative harness Java argv can become `EXACT_CANDIDATE_FOUND`; negative variants for missing/reordered/extra JVM or application arguments remain rejected before health or TERM.
  - No process values are added to stdout, projection, receipt, durable evidence or tracked files.
  - Completion of this slice is offline-only and cannot satisfy AC-2/AC-3 or authorize another runtime.
- stop_rule: If the authoritative argv cannot be established without inspecting a failed run, weakening exact identity, changing the public/runtime contract, or expanding into another subsystem, set `NEEDS_REPLAN` and stop.

## Approved Replan: Exercise-Descendant Candidate Domain

- historical_status: `COMPLETED_AND_SUPERSEDED`. This section records an earlier offline-only replan and grants no current authority.
- approval: Project Owner approved continued BUG-009 work on 2026-07-21; this approval covers the offline-only implementation and review slice below.
- target: Build the Launcher candidate set only from a complete, stable snapshot of descendants rooted at the already start-tick-proven exercise parent, so unrelated current-user processes cannot create a global `PROC_UNAVAILABLE` result.
- confirmed security decisions:
  - Discover the bounded domain from the exercise parent outward; do not scan all current-user processes and do not treat an unreadable unrelated process as part of the authorization domain.
  - Prove the exercise parent start ticks before and after domain discovery. For every discovered in-domain process, require readable, well-formed procfs identity, current-user ownership, a stable PID/start-tick identity and a parent edge anchored to the exercise tree. Any in-domain `PROC_UNAVAILABLE`, `PROC_MALFORMED`, unstable edge, cycle, depth/size bound or incomplete task-child enumeration remains fail closed.
  - Require two equivalent domain snapshots before candidate evaluation. A process that disappears cleanly may be absent from both stable snapshots; any disagreement makes that proof attempt ineligible and cannot authorize health or TERM.
  - Apply the existing trusted Java, exact argv/runId, exact cwd, executable reproof, lineage and start-tick proof only to the stable in-domain set. Preserve exactly-one exact candidate semantics.
  - Preserve candidate FD ownership, unique current-user socket holder, exact IPv4 loopback listener, A/B/final identity and socket reproof, signal-mask commit point, `dispatchSafe` and the at-most-one-parent-TERM gate without relaxation.
  - Keep all diagnostics fixed-enum and non-authorizing. No PID, task/thread ID, parent edge, argv, cwd, executable, socket/inode, exception text or process count may enter stdout, projection, receipt or durable evidence.
- compatibility:
  - Harness-only internal behavior; no Navigator API, CLI, credential, permission, Worker, Gateway, Pool, identity, Codex route, external or production contract change.
  - Local disposable integration remains compatible. `NAVIGATOR_EXTERNAL_ENABLED=true` is still only the loopback Open API route gate, `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false` remains mandatory, and neither implies Provider, Gateway or production readiness.
- offline acceptance:
  - A regression fails against global current-user enumeration and passes when an unreadable out-of-domain process is excluded by the stable descendant domain.
  - In-domain unavailable/malformed identity or task-child data, unstable parent/start-tick edges, cycles/limits, snapshot disagreement and multiple exact candidates all fail closed with zero TERM.
  - One stable exact in-domain candidate can proceed to the unchanged socket and A/B/final reproof gates; all existing negative socket, health and dispatch tests remain passing.
  - Complete Python, synthetic TypeScript, typecheck, shell syntax, Python compile, diff and scoped secret gates pass, followed by an independent read-only security review.
- non_goals:
  - No runtime rehearsal, runId selection, historical-run inspection, manual cleanup or access to any run's `private/children/log/profile/payload/process/Docker` details.
  - No weaker global scan, best-effort omission of an unreadable in-domain process, session/cgroup/port-only trust, fuzzy argv matching, held-child health promotion or signal target change.
- historical_slice_runtime_authorization: none. This slice created or consumed no runId; Runtime 4 was separately approved later and remains governed only by its own current section.
- stop_rule: If a complete stable descendant domain cannot be proved using only the live exercise-parent procfs boundary, or if implementation would weaken an existing ownership/listener/dispatch predicate, return to `NEEDS_REPLAN` without runtime.

## Acceptance Criteria

- [ ] AC-1: A stable automated regression demonstrates the relevant parent-TERM ownership/cleanup classification and proves that unproven ownership remains fail closed. **Independent signoff failed:** no regression covers a process-group leader exiting while a TERM-resistant run-owned descendant remains.
- [x] AC-2: A fresh, healthy, loopback-only harness run proves its exercise parent by command line, cwd and runId, receives exactly one parent `TERM`, and its root-level redacted receipt reports `CLEANED/SIGNAL`.
- [ ] AC-3: The AC-2 run leaves no private carrier and no run-owned process/container/volume/file resource; evidence records only redacted receipt fields and ownership checks. **Independent signoff failed:** the current cleanup/success gates do not prove the signaled host process group is empty after its leader exits.
- [x] AC-4: 除新建、loopback-only、run-owned disposable target 内的 `NAVIGATOR_EXTERNAL_ENABLED=true` `/api/v1/open/**` 路由门禁外，不得读取、修改、启用或使用任何共享/真实 target、upstream profile、existing Worker、TMS/SIM resource、Worker Gateway external 或 production configuration；该唯一例外不表示 Provider、Gateway 或 production readiness，且 `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false` 必须得到验证。
- [x] AC-5: Relevant unit/integration/script checks actually pass; the work item, runbook and test record state exact commands, results, deviations and residual risk. Completion is at most `READY_FOR_SIGNOFF`.
- [x] AC-6: Candidate-first listener proof has deterministic real-procfs topology coverage and preserves or strengthens every existing owner/listener and one-TERM constraint.
- [x] AC-7: The historical `HEALTH_READY + listener ineligible + HOLD_TIMEOUT + CLEANED/UNKNOWN + EXIT_2 + 0 TERM` combination is covered as an explicit fail-closed contract and cannot authorize success.
- [x] AC-8: Every candidate discovery attempt first re-proves the exact exercise parent; an invalid or unstable parent prevents descendant discovery, health and TERM.
- [x] AC-9: Candidate identity is evaluated only within two identical, bounded snapshots of the exercise parent's complete transitive descendant domain; exactly one exact in-domain Launcher can continue to the existing listener proof.
- [x] AC-10: Out-of-domain current-user identity failures do not pollute candidate discovery, while any in-domain unavailable/malformed identity or task-child relation remains fail closed.
- [x] AC-11: Task-set churn, process/task/depth limits, cycles, conflicting parent edges, PID/start-tick drift, snapshot disagreement and domain failure mapping have deterministic regression coverage and short-circuit before candidate/socket/health/TERM work.
- [x] AC-12: Candidate FD proof, stable run-owned-domain unique socket-holder proof, readable out-of-domain exact-inode veto, exact IPv4 loopback listener, A/B/final reproof, signal-mask commit point and at-most-one parent TERM are enforced. Unreadable in-domain FD state fails closed; only unrelated unreadable out-of-domain procfs state is excluded under the approved single-same-UID-operator disposable-harness threat model.
- [x] AC-13: The historical descendant-domain slice's complete offline Python, synthetic TypeScript, typecheck, shell syntax, Python compile, diff and scoped secret gates passed, and its independent read-only security/test-matrix reviews had no blocking finding.
- [x] AC-14: The descendant-domain slice itself created no runId and granted no runtime authority. Runtime 4 was separately approved later; BUG-009 remains ineligible for signoff while AC-2/AC-3 are unmet.
- [x] AC-15: The current port-reservation isolation slice passed its complete offline gate and independent security/test-matrix/canonical reviews with no blocking finding before Runtime 4 eligibility was restored.

## Contract / Data / Security Constraints

- API or event contract: No Navigator public API, credential lane, task capability, Worker routing or Gateway contract changes are permitted.
- data and migration: Disposable data may be created and removed only inside the fresh run's owned namespace; no shared database DDL, seed, export or cleanup is allowed.
- compatibility and rollback: The correction must be removable with the harness-only code and leave no shared-state migration. If a new run cannot prove ownership, it must remain a cleanup failure rather than take a broad fallback path.
- permissions and secrets: The runtime child remains `env -i` allow-listed. Never print or persist real/synthetic credential values, full profiles, payloads or raw logs; retain root-level redacted metadata only.

## Receipt v4 and Redacted Diagnostics

- New root `cleanup-report.json` receipts use exact schema v4 fields: `schemaVersion`, `runId`, `result`, `failureStage`, `rehearsalLifecycleObservation`, `launcherReadinessObservation`, `launcherFailureClass`, `finishedAtUtc`, and `secretsRedacted`. Reader and parent-adoption validation reject v3, duplicate JSON object keys, unknown fields, non-string enums, and unknown enum values fail closed.
- `rehearsalLifecycleObservation` is an allow-listed, non-secret diagnostic only: `NOT_REHEARSAL`, `HOLD_ENTERED`, `HOLD_TIMEOUT`, `HOLD_WAIT_FAILURE`, or `HOLD_SIGNAL_RECEIVED`. Normal paths remain `NOT_REHEARSAL`; the final value only records what the held child observed in its own lifecycle.
- In particular, `HOLD_SIGNAL_RECEIVED` does not independently prove an outer parent's authorization, exact TERM dispatch, target ownership, private-carrier removal, Docker cleanup, or forced-SIGNAL success. The strict main completion gate now requires it only as one conjunct alongside exact parent/listener proof, one safe TERM, exact `EXIT_128`, `HEALTH_READY`, `CLEANED/SIGNAL`, reservation absence, and zero residue; it can never change an otherwise failed run into success.
- `listenerProofEverEligible` exists only in the redacted supervisor JSON, never in the root receipt. It is a boolean that an eligible listener proof was observed at least once; it contains no PID, argv, cwd, port, inode, or socket identifier. A later re-proof failure remains fail closed and this diagnostic cannot authorize TERM or cleanup.

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| lifecycle regression | false-clean or false-failure | focused automated regression before/after the correction | exact test command and result |
| fresh forced-SIGNAL run | orphaned owned resources | Runtime 10 executed once under the reviewed exact freeze and satisfied the complete strict gate; no current runtime is authorized | runId plus allowed redacted root evidence and non-secret reservation-absence result only |
| ownership fail-closed path | broad accidental cleanup | automated or deterministic negative ownership assertion | no deletion/cleanup success for unproven target |
| harness hygiene | secret leak or shared-state access | `npm run test:synthetic`, typecheck, Python fixture tests, three `bash -n` checks, `git diff --check`, scoped secret scan | exact output summary and any environment blocker |
| documentation | misleading recovery claim | update canonical work item/runbook/test record | changed paths and precise retention semantics |

## Bug Context

- bug_source: acceptance-found
- severity: major
- environment: fresh loopback-only disposable INT-001 stack; no shared 8112/database, real upstream profile, existing Worker, TMS or SIM resource.
- current_behavior: Runtime 10 proves the corrected path end to end: controlled health, exact parent/listener, `FULL_ELIGIBLE`, one TERM, dispatch safety, exact `EXIT_128`, valid `CLEANED/SIGNAL` receipt, private absent, root residue `0`, exact reservation absent and Docker residue `0/0/0`.
- expected_behavior: A healthy owned parent-TERM path reports `CLEANED/SIGNAL` only after its owned resources are proven absent; uncertain ownership remains failed.
- reproduction_steps: Create a new disposable target, establish the exercise parent by command line/cwd/runId, send one `TERM`, and inspect only its root-level redacted cleanup receipt.
- reproduction_status: confirmed
- existing_evidence: `int001-signal-20260721-a9b0c1` root receipt SHA-256 `917c868fa520ebccb7b9a3b7a6b1ace00f3b2c5b6039d4129cd73fbb6b68016e`; it remains read-prohibited beyond the recorded fields.
- existing_tests: INT-001 synthetic safety/config/lifecycle checks and the recorded normal disposable replay.
- regression_protection: required
- waiver_reason_and_risk: N/A

## Risks and Open Questions

- known_risks:
  - The descendant-domain implementation now also has one successful fresh disposable runtime proof, but that proof remains local harness evidence and is not a shared-host or production authorization.
  - A real cleanup defect can look fixed if ownership proof is weakened. Preserve explicit failure for unknown processes/resources.
  - This work does not make INT-001 accepted, real upstream integration ready, Gateway external, Provider ready or production ready. BUG-009 independent signoff is rejected and a later repair requires a new independent signoff.
- open_questions: none for this rejected signoff. Runtime 10 is consumed successfully and no runtime, retry, replacement, manual cleanup or restricted evidence access is authorized. A new approved `DRAFT` acceptance-found repair contract is required before implementation resumes.

## Historical Ultra Execution Contract — Offline Descendant-Domain Slice

- historical_status: `COMPLETED_AND_SUPERSEDED`. This section records the prior exercise-descendant candidate-domain implementation contract and grants no current authority.
- This authorization covered only the approved exercise-descendant candidate-domain implementation, offline regression, independent review and documentation sync. It never authorized a rehearsal, runId selection/replacement or historical-run inspection, even after every offline gate passed.
- First read this work item, root `AGENTS.md`, `CLAUDE.md`, the INT-001 runbook and `navigator-runtime-provisioning` skill.
- Within scope, choose the smallest maintainable harness/test structure and preserve the already approved parent, exact identity, socket-holder, A/B/final and one-TERM gates.
- Never read or touch any historical run. Do not create, signal or clean a new runtime target in this slice.
- If diagnosis requires a public API/config/authorization change, shared target, real credential/profile, Worker/Pool/Identity action, Gateway/external enablement, or weaker ownership proof, set `NEEDS_REPLAN` and stop that expansion.
- The slice recorded its changed paths, exact verification commands/results, evidence locations, deviations and residual risk, then historically returned the overall BUG to `NEEDS_REPLAN`; it did not set `READY_FOR_SIGNOFF` or `ACCEPTED` because AC-2/AC-3 remained unmet.

## Historical Candidate-First Implementation Result

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
- historical_candidate_first_runtime_authorization: consumed. That exact runId executed once and must not be retried or replaced.
- manual_or_experience_evidence: Only the new run's fixed-enum projection, redacted supervisor stdout, root receipt and permitted redacted residue counts were read. No historical run, private carrier, `children/` metadata, log, profile, payload, process detail or Docker object was inspected.
- deviations: The offline implementation matched the approved design, but the runtime did not produce an exact Launcher candidate. No owner/listener predicate was weakened and no retry was attempted.
- residual_risks:
  - POSIX cannot atomically combine the final pending-signal observation and `kill()`; the explicit commit-point rule remains fail closed.
  - No positive runtime evidence exists for exactly one TERM, `dispatchSafe=true`, `CLEANED/SIGNAL`, or the controlled-health/strict listener proof.
  - An unreadable or malformed identity component is retained only as the fixed redacted reason `listener-candidate-proc-unavailable` or `listener-candidate-proc-malformed`; it rejects discovery/reproof without exposing process detail, so diagnostic granularity remains intentionally limited.
  - The historical run and all three failed fresh runs remain read-prohibited beyond their recorded redacted facts; no private carrier, children metadata, log, profile, payload, process, Docker object, retry, or manual cleanup was accessed.
  - The new fixed reason proves only that no exact candidate satisfied every identity predicate at the proof point; it does not identify which predicate differed, and the approved evidence boundary forbids filling that gap from failed-run private/process data.
- historical_readiness: `NEEDS_REPLAN`; candidate-first implementation and offline gates passed, but that slice's sole runtime gate failed before health/parent proof and TERM dispatch.
- signoff_eligibility: no; AC-2 and AC-3 remain unmet.

## Diagnostic Slice 2 Implementation Result

- implementation_summary: The approved offline-only fixed-enum identity diagnostic is implemented. Candidate discovery now retains one deterministic `listenerIdentityDiagnostic` describing only the furthest identity-proof stage, exact-candidate ambiguity, or fail-closed `PROC_*` class. The value is carried in memory and emitted only in the redacted supervisor stdout summary; unknown values collapse to `NOT_OBSERVED`. It is not read by health, parent/listener authorization, TERM dispatch, cleanup, receipt, projection or completion gates.
- changed_paths:
  - `tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py`
  - `tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py`
  - `docs/version-tracker/1.4.3-SNAPSHOT/runbooks/INT-001-synthetic-upstream-integration-harness.md`
  - this canonical work item
  - `docs/version-tracker/1.4.3-SNAPSHOT/README.md`
- tests_and_results:
  - Failing regression was established first: the old `ListenerCandidateIdentityProbe` had no fixed diagnostic field and the new focused tests failed with `AttributeError` / unexpected constructor argument.
  - `PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py` — PASS, 64/64. Coverage includes every fixed stage, exact one/multiple candidate classes, mismatch order reversal, all 24 permutations of mixed progress/`PROC_*`, exact-plus-unavailable order reversal, unknown-value redaction, A/B/final diagnostic propagation and unchanged zero/one TERM behavior.
  - `(cd business-agent-module/integration-tests && env -u INT001_SYNTHETIC_UPSTREAM_HARNESS -u INT001_RUNTIME_PROBE npm run test:synthetic)` — PASS, 94 passed / 1 skipped.
  - `(cd business-agent-module/integration-tests && npm run typecheck)` — PASS.
  - `bash -n tools/navigator-upstream/scripts/synthetic-upstream-harness.sh tools/navigator-upstream/scripts/synthetic-upstream-bootstrap.sh tools/navigator-upstream/scripts/synthetic-upstream-runtime-audit.sh` — PASS.
  - `PYTHONPYCACHEPREFIX=temp/test-artifacts/BUG-009-fixed-enum-pyc python3 -m py_compile tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py` — PASS.
  - `git diff --check` — PASS.
  - Scoped added-line high-confidence secret scan — PASS, `0 matches`.
  - Independent read-only security review — PASS, no blocking finding. It confirmed order-independent fixed-rank aggregation, `PROC_*` fail-closed behavior, unchanged authorization/completion gates and allowlist-only stdout. Its permutation-coverage suggestion was added before final verification.
- deviations: none from the approved offline slice. No runtime, runId, historical artifact, process/Docker object, profile, credential, Worker, Gateway or shared service was accessed.
- residual_risks:
  - A process that disappears or changes owner between identity-field reads can be reported as the fixed stage where proof stopped. This may reduce diagnostic precision but remains non-authorizing and fail closed.
  - The new enum has not been observed in a real rehearsal. It cannot establish the BUG-009 root cause or satisfy AC-2/AC-3 without a separately approved single fresh runtime.
- slice_runtime_authorization: none granted or consumed by this historical slice. Runtime 4 was approved separately later.
- historical_readiness: the bounded diagnostic implementation was offline-complete, but canonical BUG-009 then remained `NEEDS_REPLAN` and not signoff-eligible because AC-2/AC-3 were unmet.

## Approved Diagnostic Runtime 2

- approval: Project Owner explicitly approved direct continuation through BUG-009 signoff on 2026-07-21.
- exact_run_id: `int001-bug009-20260721-99c88f80`
- execution_limit: Exactly one new loopback-only disposable rehearsal. This runId must not be retried or replaced after execution.
- allowed_evidence: The fixed-enum supervisor summary, the run's fixed-schema projection, root receipt, root non-receipt residue count, private-absent boolean and redacted Docker residue counts only.
- prohibited_actions: No historical failed-run access; no `private/children/log/profile/payload/process/Docker` inspection; no manual cleanup; no shared `8112`; no real TMS/SIM, credential/profile, Worker, Gateway, Pool, identity, Codex route, external or production change.
- stop_rule: On any failure, record the permitted evidence and stop without retry. Only a complete success gate may advance BUG-009 to `READY_FOR_SIGNOFF`.
- runtime_authorization: consumed. The exact runId executed once and must not be retried or replaced.
- runtime_result:
  - exit: `1` / fail closed.
  - projection: `COMPLETE/CHILD_EXITED_BEFORE_HEALTH`, receipt/root/stdout states all valid or complete; SHA-256 `f3a1e863b1ca23d6652cde6502804928a18f5dbded139817edc14f91294f75a1`.
  - supervisor: `listenerIdentityDiagnostic=NO_EXACT_ARGV_MATCH`, `listenerProof=listener-candidate-absent`, `listenerProofEverEligible=false`, `controlledHealthPrecondition=false`, `parentProof=NOT_ATTEMPTED`, `termDispatches=0`, `dispatchSafe=false`, `exerciseExit=EXIT_2`, `supervisorInterruption=NONE`.
  - receipt: `CLEANED/UNKNOWN`, `HEALTH_READY + HOLD_TIMEOUT`, `secretsRedacted=true`; mode/owner/links `0600 / 1001 / 1`; SHA-256 `6f7933e5f9dc8227ac745ac708174188a8311f8371047ec3fca23c38eeb4d953`.
  - residue: `privateAbsent=true`, root non-receipt residue `0`, Docker container/network/volume residue `0/0/0`.
  - evidence: `../test-records/BUG-009-int001-fixed-enum-runtime-2026-07-21.md`.
- historical_conclusion: The runtime emitted `NO_EXACT_ARGV_MATCH`, but the later approved offline slice established that this value was over-broad and did not prove a trusted Java candidate existed. AC-2/AC-3 remained unmet because no TERM was authorized or sent, so BUG-009 then returned to `NEEDS_REPLAN` and was not signoff-eligible.

## Diagnostic Slice 3 Implementation Result

- implementation_summary: The authoritative five-element Launcher argv generated by the harness and expected by the supervisor is byte-for-byte consistent for a real test-owned `setsid -> env -i -> Java -> -Dint001.run-id -> -jar -> mock profile` process. The defect was the diagnostic order: any current-user non-Java process could previously yield `NO_EXACT_ARGV_MATCH` before the trusted executable predicate was evaluated. Candidate identity now checks the trusted Java executable first, retains exact argv/cwd checks, and re-reads the executable after argv/cwd before lineage and start-tick completion. The new redacted enum `NO_TRUSTED_JAVA_CANDIDATE` distinguishes absence of a trusted Java candidate from a trusted Java candidate whose argv is not exact.
- failing_regression: Before the fix, `ListenerCandidateIdentityTest.test_non_trusted_executable_is_not_reported_as_an_argv_mismatch` failed because the probe returned `NO_EXACT_ARGV_MATCH`; after the fix it returns `NO_TRUSTED_JAVA_CANDIDATE` without reading that process's argv.
- changed_paths:
  - `tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py`
  - `tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py`
  - `business-agent-module/integration-tests/tests/05-synthetic-upstream-bootstrap-safety.test.ts`
  - `docs/version-tracker/1.4.3-SNAPSHOT/runbooks/INT-001-synthetic-upstream-integration-harness.md`
  - `docs/version-tracker/1.4.3-SNAPSHOT/test-records/BUG-009-int001-authoritative-launcher-argv-offline-2026-07-21.md`
  - this canonical work item and the version index
- tests_and_results:
  - `PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py` — PASS, 67/67. The real Java/JAR `/proc` case reaches `EXACT_CANDIDATE_FOUND`; missing application arg, reordered JVM arg and extra application arg remain `NO_EXACT_ARGV_MATCH`.
  - `(cd business-agent-module/integration-tests && env -u INT001_SYNTHETIC_UPSTREAM_HARNESS -u INT001_RUNTIME_PROBE npm run test:synthetic)` — PASS, 94 passed / 1 skipped.
  - `(cd business-agent-module/integration-tests && npm run typecheck)` — PASS.
  - `bash -n tools/navigator-upstream/scripts/synthetic-upstream-harness.sh tools/navigator-upstream/scripts/synthetic-upstream-bootstrap.sh tools/navigator-upstream/scripts/synthetic-upstream-runtime-audit.sh` — PASS.
  - `python3 -m py_compile tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py` — PASS.
  - `git diff --check` — PASS.
  - Scoped added-line high-confidence secret scan — PASS, `0 matches`.
  - Independent read-only security review — PASS, no blocking finding. It confirmed trusted-executable-first inspection, executable reproof, fail-closed fixed-rank aggregation, allowlist-only output and unchanged socket/parent/health/signal/one-TERM gates. Its low-risk suggestion to bind the harness capture to the complete five-element Launcher argv was incorporated before final verification.
- deviations: The approved slice assumed the authoritative argv might require alignment. Offline real-proc proof instead showed the argv contract already aligned; the smallest justified correction was the pre-argv trusted-executable classification. No fuzzy matching, argv relaxation or public/runtime contract change was introduced.
- security_effect: Non-Java current-user processes are rejected before their argv is read. Unavailable/malformed executable proof remains `PROC_UNAVAILABLE`/`PROC_MALFORMED`; a trusted executable must still pass exact argv and cwd, followed by executable reproof, lineage and stable start ticks. Socket ownership, A/B/final reproof, signal-mask commit point and the at-most-one-TERM gate are unchanged.
- durable_record: `../test-records/BUG-009-int001-authoritative-launcher-argv-offline-2026-07-21.md`
- slice_runtime_authorization: none for this historical slice. It did not create or consume a runId; Runtime 4 was approved separately later.
- historical_readiness: offline slice complete; BUG-009 then remained `NEEDS_REPLAN` and not signoff-eligible because AC-2/AC-3 still required new positive runtime evidence under separately approved authorization.

## Approved Diagnostic Runtime 3

- approval: Project Owner explicitly approved continuation on 2026-07-21 after the authoritative Launcher argv offline slice completed.
- exact_run_id: `int001-bug009-20260721-j7r4m9t2`
- execution_limit: Exactly one new loopback-only disposable rehearsal. This runId must not be retried, and no replacement runId is authorized by this approval.
- allowed_evidence: The supervisor's fixed-enum redacted stdout summary, this run's fixed-schema projection, root receipt, private-absent boolean, root non-receipt residue count and redacted Docker container/network/volume residue counts only.
- prohibited_actions: No historical failed-run access; no `private/children/log/profile/payload/process/Docker` inspection; no manual cleanup; no shared `8112`; no real TMS/SIM, credential/profile, Worker, Gateway, Pool, identity, Codex route, external or production change.
- fixed_runtime_boundary: The disposable harness may set `NAVIGATOR_EXTERNAL_ENABLED=true` only for its loopback `/api/v1/open/**` route gate. `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false` remains required. Neither condition implies Provider, Gateway or production readiness.
- stop_rule: On any failure, record only the permitted redacted evidence and stop without retry. Only a complete gate with exactly one TERM, `dispatchSafe=true`, `CLEANED/SIGNAL`, private absent and zero run-owned residue may advance BUG-009 to `READY_FOR_SIGNOFF`.
- runtime_authorization: consumed. The exact runId executed once and must not be retried or replaced.
- runtime_result:
  - exit: `1` / fail closed.
  - projection: `COMPLETE/CHILD_EXITED_BEFORE_HEALTH`, receipt/root/stdout states valid/complete/emitted; mode/owner/links `0600 / 1001 / 1`; SHA-256 `241d792b9859c3cc3c515d8171679bbbd294a05175e612a4bff917ec5b3c17d5`.
  - supervisor: `listenerIdentityDiagnostic=PROC_UNAVAILABLE`, `listenerProof=listener-candidate-proc-unavailable`, listener never eligible, controlled health false, parent proof not attempted, zero TERM, `dispatchSafe=false`, `exerciseExit=EXIT_2`, no supervisor interruption.
  - receipt: `CLEANED/UNKNOWN + HEALTH_READY + HOLD_TIMEOUT`, `secretsRedacted=true`; mode/owner/links `0600 / 1001 / 1`; SHA-256 `c34fb1e15f9915941d9e1c29f1d10d0d73b47263ca83153c5a6978885b69fc1f`.
  - residue: private absent, root non-receipt residue `0`, Docker container/network/volume residue `0/0/0`.
  - durable_record: `../test-records/BUG-009-int001-corrected-enum-runtime-2026-07-21.md`.
- historical_conclusion: The corrected enum prevented the prior argv misclassification, but candidate enumeration failed closed at `PROC_UNAVAILABLE`. The enum intentionally did not reveal which current-user process or field was unavailable. No TERM was authorized; at that point AC-2/AC-3 remained unmet and BUG-009 was not signoff-eligible.
- superseded_replan_boundary: This historical runtime originally required an offline exercise-parent descendant-domain replan. Diagnostic Slice 4 completed that offline work without runtime. Its historical next decision was whether the Project Owner would authorize one fresh loopback-only rehearsal; that decision was later superseded by Approved Diagnostic Runtime 4.

## Diagnostic Slice 4 Implementation Result

- implementation_summary: Candidate discovery now begins only after a fresh exact exercise-parent proof and evaluates Launcher identities only inside a bounded, stable descendant domain rooted at that parent. The supervisor traverses every task's live procfs `children` relation, binds each domain node to PID, parent edge and start ticks, detects task-set churn, requires two identical complete snapshots, and rejects in-domain unreadable/malformed data, PID reuse, parent/start-tick drift, cycles and size/depth limits. Unrelated current-user process identities are no longer read by candidate discovery. The current-user unique socket-holder proof remains global and unchanged.
- changed_paths:
  - `tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py`
  - `tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py`
  - `docs/version-tracker/1.4.3-SNAPSHOT/runbooks/INT-001-synthetic-upstream-integration-harness.md`
  - `docs/version-tracker/1.4.3-SNAPSHOT/test-records/BUG-009-int001-exercise-descendant-domain-offline-2026-07-21.md`
  - this canonical work item and the version index
- tests_and_results:
  - `python3 -m unittest tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py` — PASS, 79/79. Coverage includes all-task child union, task-set churn, task/child parse and ownership failures, exact task/process/depth bounds, self-cycle/conflicting parent edges, stable double-snapshot membership/parent/start-tick drift, second-snapshot `PROC_*`, domain failure mapping, out-of-domain identity exclusion, parent-before-discovery and unchanged A/B/final dispatch behavior.
  - `(cd business-agent-module/integration-tests && env -u INT001_SYNTHETIC_UPSTREAM_HARNESS -u INT001_RUNTIME_PROBE npm run test:synthetic)` — PASS, 94 passed / 1 skipped.
  - `(cd business-agent-module/integration-tests && npm run typecheck)` — PASS.
  - `bash -n tools/navigator-upstream/scripts/synthetic-upstream-harness.sh tools/navigator-upstream/scripts/synthetic-upstream-bootstrap.sh tools/navigator-upstream/scripts/synthetic-upstream-runtime-audit.sh` — PASS.
  - `PYTHONPYCACHEPREFIX=temp/test-artifacts/BUG-009-descendant-domain-pyc python3 -m py_compile tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py` — PASS.
  - `git diff --check` — PASS.
  - Scoped high-confidence secret scan over the BUG-009 changed surfaces — PASS, `0 matches`.
  - Independent read-only security review — PASS, no blocking finding. It confirmed full-task rooted traversal, two identical snapshots, exact parent/edge/start-tick binding, in-domain fail-closed behavior, unchanged global socket-holder proof, A/B/final reproof, signal-mask commit point and at-most-one TERM.
  - Independent read-only test-matrix review — PASS, 79/79 independently rerun and all previous blocking gaps closed.
- deviations: none from the approved offline-only slice. No runtime/rehearsal, new or replacement runId, historical run artifact, process/Docker detail, profile/credential, shared service, TMS/SIM, Worker, Gateway, Pool, identity, Codex route, external or production change was used.
- residual_risks:
  - POSIX cannot atomically combine the final pending-signal observation and `kill()`; the existing fail-closed commit-point rule remains unchanged.
  - The approved domain proof uses Linux live procfs task-child relations and bounded snapshots; a positive disposable Launcher runtime has not yet exercised the complete path.
  - Non-blocking defense-in-depth tests remain possible for symmetric task disappearance churn, duplicate child edges across tasks and explicit out-of-domain holder naming; none weakens the current denial behavior.
- durable_record: `../test-records/BUG-009-int001-exercise-descendant-domain-offline-2026-07-21.md`
- slice_runtime_authorization: none for this historical slice. It did not create or consume a runId and could not infer runtime permission from offline success.
- historical_readiness: offline descendant-domain slice complete; overall BUG-009 then remained `NEEDS_REPLAN`, not `READY_FOR_SIGNOFF`, because AC-2/AC-3 still required separately approved positive runtime evidence.

## Approved Diagnostic Runtime 4

- approval: On 2026-07-22 the Project Owner explicitly approved continuation and authorized the agent to approve the same bounded BUG-009 diagnostic steps through signoff. This section narrows that standing approval to exactly one fresh rehearsal.
- exact_run_id: `int001-bug009-20260722-484c6216`
- execution_limit: Exactly one new loopback-only disposable rehearsal. This runId must not be retried, and this authorization does not permit a replacement runId after execution.
- status: `CONSUMED_FAIL_CLOSED`. The exact command executed once and exited `1`; this runId cannot be retried or replaced under this authorization.
- prerequisite: was satisfied before execution. The approved Port Reservation / Historical Private Isolation slice, complete offline gates and independent security/test/contract reviews passed with no blocking finding.
- allowed_evidence: The supervisor's fixed-enum redacted stdout summary, this run's fixed-schema sibling projection, root receipt, the non-secret exact-reservation-absent boolean, private-absent boolean, root non-receipt residue count and redacted Docker container/network/volume residue counts only. Receipt acceptance is a composite check serialized under the reservation-registry shared lock: the strict registry must be valid and this exact run's reservation must be absent; an unsafe registry or retained exact reservation rejects an otherwise success-shaped receipt fail closed.
- prohibited_actions: No historical failed-run access; no run `private/children/log/profile/payload/process/Docker` inspection; no manual cleanup; no shared `8112`; no real TMS/SIM, credential/profile, Worker, Gateway, Pool, identity, Codex route, external or production change.
- fixed_runtime_boundary: The disposable harness may set `NAVIGATOR_EXTERNAL_ENABLED=true` only for its loopback `/api/v1/open/**` route gate. `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false` remains required. Neither condition implies Provider, Gateway or production readiness.
- stop_rule: On failure, record only the permitted redacted evidence and continue only through a new offline replan under the standing Project Owner authorization; never retry this runId, inspect prohibited evidence or perform manual cleanup. Only a complete gate with exactly one TERM, `dispatchSafe=true`, a valid `CLEANED/SIGNAL` receipt accepted under the shared registry lock while the strict registry is valid and the exact reservation is absent, private absent and zero run-owned residue may advance BUG-009 to independent signoff.
- runtime_authorization: consumed; no retry or replacement runId is authorized.

## Runtime 4 Execution Result

- executed_command: `PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py --run-id int001-bug009-20260722-484c6216`
- exit: `1`; the run completed fail closed and consumed the single-use authorization.
- allowed_redacted_result:
  - `controlledHealthPrecondition=false`
  - exact parent proof: `commandLine+cwd+runId+uid+session+startTicks`
  - listener proof: `listener-candidate-absent`
  - listener identity diagnostic: `IDENTITY_STABILITY_MISMATCH`
  - `listenerProofEverEligible=false`
  - `termDispatches=0`; `dispatchSafe=false`; supervisor interruption `NONE`
  - exercise exit `EXIT_2`
  - receipt: schema v4, `CLEANED/UNKNOWN`, `HEALTH_READY`, `HOLD_TIMEOUT`, `NOT_APPLICABLE`, `0600`, redacted
  - `privateAbsent=true`; root non-receipt residue `0`; Docker container/network/volume residue `0/0/0`
  - exact reservation absent after completion: `true`
- conclusion: AC-2/AC-3 remain unmet because no eligible listener proof or controlled health precondition was established and no TERM was dispatched. Cleanup and reservation release completed without retained run-owned residue, but that cannot promote this run to forced-SIGNAL success.
- boundary: no run `private/children/log/profile/payload/process/Docker` detail was read, no manual cleanup occurred, and the runId will not be retried.
- durable_record: `../test-records/BUG-009-int001-runtime4-failclosed-2026-07-22.md`
- next_step: static/offline diagnosis of the identity-stability mismatch only. A future fresh runtime requires an explicit bounded replan, regression-first correction, complete offline gates, independent reviews and a newly frozen exact runId.

## Approved Offline Replan: Stable Parent-Term Hold Topology

- approval: On 2026-07-22 the Project Owner's standing authorization through BUG-009 signoff approved this bounded offline replan after Runtime 4 failed closed.
- static_diagnosis: `hold_for_parent_term` currently creates a new `sleep 1` descendant every second for up to 180 seconds. The unchanged strict supervisor requires each process task set, one complete transitive descendant-domain snapshot, and two consecutive domain snapshots to be stable. The fixture therefore creates the same descendant churn that maps to `listener-candidate-absent + IDENTITY_STABILITY_MISMATCH` even after Launcher health is observed.
- target: Replace only the repeated one-second hold loop with one bounded `sleep "$PARENT_TERM_REHEARSAL_HOLD_SECONDS"`, preserving signal traps, lifecycle classification, exact parent proof, complete descendant-domain proof, trusted Java/exact argv/cwd/exe/lineage proof, listener FD/socket-holder proof, A/B/final reproof and at-most-one TERM.
- non_goals:
  - No weakening, filtering or bypass of descendant-domain stability, JVM task-set validation, exact argv, PID/startTicks, network/socket ownership, pending-signal or cleanup gates.
  - No health-derived authorization, direct port/PID/process-group cleanup, alternate argv matching, new public API/config/credential/Worker/Gateway/upstream/production behavior, or inspection of Runtime 4 private artifacts.
  - No listener-holder architecture rewrite in this slice. If a stable single-sleep topology still cannot satisfy the existing strict proof in test-owned real topology, stop as `NEEDS_REPLAN` before any runtime.
- acceptance:
  - Regression proves the hold invokes exactly one sleep with the exact fixed duration, rather than repeatedly creating descendants.
  - Zero-duration hold still produces `HOLD_TIMEOUT`; a non-zero sleep failure still produces `HOLD_WAIT_FAILURE` and owned fail-closed cleanup.
  - A test-owned real stable hold topology passes repeated complete descendant-domain snapshots; a deliberately churning topology remains rejected.
  - Existing outer-parent TERM forwarding remains exactly once and continues to require `HOLD_SIGNAL_RECEIVED` plus `CLEANED/SIGNAL` and zero residue for positive success.
  - All existing negative parent/listener/identity/socket/signal/receipt/reservation/cleanup tests remain unchanged or stricter.
- required_validation: targeted synthetic harness safety, complete Python supervisor suite, complete synthetic TypeScript suite, typecheck, three shell syntax checks, Python compile, `git diff --check`, scoped secret scan, then independent security/test-matrix/canonical reviews.
- runtime_authorization: none. Runtime 4 is consumed; offline success alone may only support a separately reviewed decision for a new exact runId.

### Stable Hold Topology Implementation Result

- implementation_summary: Replaced the repeated `sleep 1` loop with one bounded `sleep "$PARENT_TERM_REHEARSAL_HOLD_SECONDS"`. Signal traps, `HOLD_ENTERED`, nonzero wait classification as `HOLD_WAIT_FAILURE`, normal expiry as `HOLD_TIMEOUT`, owned lifecycle cleanup, exact delegated-child proof and outer-parent TERM forwarding remain unchanged. The regression shadows `sleep` and proves one exact-duration call. The isolated Linux procfs topology now includes a stable hold descendant and proves two identical complete descendant-domain snapshots containing exactly the outer parent, exact listener child and hold child.
- changed_paths:
  - `tools/navigator-upstream/scripts/synthetic-upstream-harness.sh`
  - `tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py`
  - `business-agent-module/integration-tests/tests/05-synthetic-upstream-bootstrap-safety.test.ts`
  - `docs/version-tracker/1.4.3-SNAPSHOT/runbooks/INT-001-synthetic-upstream-integration-harness.md`
  - `docs/version-tracker/1.4.3-SNAPSHOT/test-records/BUG-009-int001-stable-parent-term-hold-offline-2026-07-22.md`
  - this canonical work item and the version index
- regression_first: Before the harness correction, the new single-sleep regression failed exactly as intended: targeted suite `84 passed / 1 failed`; a seven-second test duration observed seven `sleep 1` calls instead of one `sleep 7` call.
- tests_and_results:
  - Targeted synthetic harness safety suite — PASS, `85/85`.
  - Complete Python supervisor suite — PASS, `82/82`; the stable real procfs topology includes the fixed hold descendant and returns `domainStatus=MATCH` for the exact three-process domain.
  - Complete synthetic TypeScript suite — PASS, `109 passed / 1 skipped`.
  - TypeScript typecheck, three shell syntax checks and Python compile — PASS.
  - Final `git diff --check` — PASS; scoped high-confidence secret scan — PASS, `0 matches`.
  - Existing deterministic task-set churn and complete-domain snapshot disagreement cases remain fail-closed; existing exactly-one outer TERM, `HOLD_SIGNAL_RECEIVED`, `CLEANED/SIGNAL`, timeout, wait-failure and cleanup contracts remain passing.
- deviations: No real churning procfs timing test was added because it would be race-sensitive. The existing deterministic task-set churn and snapshot-disagreement tests satisfy the approved rejection obligation without introducing a flaky gate. No other deviation from the approved offline-only scope.
- residual_risks:
  - The correction removes harness-created churn, but no new real Launcher runtime has yet proved the complete positive forced-SIGNAL path.
  - AC-2/AC-3 remain unmet. Offline evidence cannot prove exactly one TERM, `dispatchSafe=true`, `CLEANED/SIGNAL`, private absence or zero runtime residue for a fresh run.
- durable_record: `../test-records/BUG-009-int001-stable-parent-term-hold-offline-2026-07-22.md`
- review_status: `PASS`; independent security/code and contract/test-matrix reviews found no blocker, and the canonical consistency blocker about historical Runtime 4 tense was corrected before runtime authorization. The stable procfs positive topology uses listener/hold siblings while transitive depth and the production-like three-layer one-TERM behavior remain separately covered; the deliberately timing-sensitive real-churn test remains replaced by deterministic task-set churn and snapshot-disagreement rejection.
- runtime_authorization: none. Runtime 4 remains `CONSUMED_FAIL_CLOSED`; no retry or replacement runId is authorized.
- readiness: `ULTRA_EXECUTING`; this offline slice is implemented and locally verified, but BUG-009 is not `READY_FOR_SIGNOFF` while AC-2/AC-3 remain open.

## Approved Diagnostic Runtime 5

- approval: On 2026-07-22, after the stable parent-term hold regression, complete offline gate and independent security/code, contract/test-matrix and canonical consistency reviews all passed, the Project Owner's standing authorization through BUG-009 signoff permits exactly this one fresh rehearsal.
- exact_run_id: `int001-bug009-20260722-r5-9f3c7a2d`
- exact_command: `PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py --run-id int001-bug009-20260722-r5-9f3c7a2d`
- execution_limit: Exactly one loopback-only disposable execution of the exact command. The authorization is consumed when the command starts, regardless of exit or evidence shape. This runId must never be retried, and failure grants no replacement runId without another bounded offline replan and review.
- status: `CONSUMED_FAIL_CLOSED`. The exact command executed once and exited `1`; this runId cannot be retried or replaced under this authorization.
- success_gate: `controlledHealthPrecondition=true`; exact parent proof `commandLine+cwd+runId+uid+session+startTicks`; exact listener proof `uid+java+argv+cwd+ancestor+socket+startTicks`; `listenerProofEverEligible=true`; `termDispatches=1`; `dispatchSafe=true`; normal exact `EXIT_128`; strict schema-v4 redacted receipt `CLEANED/SIGNAL` with `HOLD_SIGNAL_RECEIVED`; `privateAbsent=true`; root non-receipt residue `0`; Docker container/network/volume residue `0/0/0`; exact reservation absent under the valid shared registry lock.
- allowed_evidence: Only the supervisor fixed-enum/redacted stdout summary, fixed-schema sibling projection, root receipt fixed fields, private-absent boolean, root non-receipt residue count, redacted Docker residue counts and exact-reservation-absent boolean. No raw identifiers beyond this exact runId may enter durable evidence.
- prohibited_actions: No historical or current run `private/children/log/profile/payload/process/Docker` detail; no manual cleanup; no retry; no shared `8112`; no real TMS/SIM, profile/credential, Worker, Gateway, Pool, identity, Codex route, external exposure or production change.
- fixed_runtime_boundary: The disposable child may set `NAVIGATOR_EXTERNAL_ENABLED=true` only as its loopback `/api/v1/open/**` route gate and must keep `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false`. This cannot prove Provider, Gateway or production readiness.
- failure_rule: Any missing success-gate item is `CONSUMED_FAIL_CLOSED`; record only allowed redacted evidence, keep AC-2/AC-3 open and return to offline replan. Never inspect restricted evidence or clean manually.

## Runtime 5 Execution Result

- executed_command: `PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py --run-id int001-bug009-20260722-r5-9f3c7a2d`
- exit: `1`; the command executed exactly once and permanently consumed the authorization.
- allowed_redacted_result:
  - `controlledHealthPrecondition=false`
  - exact parent proof: `commandLine+cwd+runId+uid+session+startTicks`
  - listener proof: `listener-candidate-absent`
  - listener identity diagnostic: `NO_TRUSTED_JAVA_CANDIDATE`
  - `listenerProofEverEligible=false`
  - `termDispatches=0`; `dispatchSafe=false`; supervisor interruption `NONE`
  - exercise exit `EXIT_2`
  - receipt: schema v4, `CLEANED/UNKNOWN`, `HEALTH_READY`, `HOLD_TIMEOUT`, `NOT_APPLICABLE`, `0600`, redacted
  - `privateAbsent=true`; root non-receipt residue `0`; Docker container/network/volume residue `0/0/0`
- conclusion: AC-2/AC-3 remain unmet because no trusted-Java listener candidate or controlled health precondition was established and no TERM was dispatched. The valid receipt was adopted only through the existing strict reservation-registry composite check, but reservation absence is not a separately emitted stdout field and is not promoted into an independent observed value here.
- boundary: no run `private/children/log/profile/payload/process/Docker` detail was read, no manual cleanup occurred, and the runId will never be retried.
- durable_record: `../test-records/BUG-009-int001-runtime5-failclosed-2026-07-22.md`
- next_step: the bounded static/offline diagnosis and production-like Java full-supervisor seam below are complete. Any future runtime still requires independent review and a newly frozen exact runId; Runtime 6 is not authorized by this result.

## Approved Offline Replan: Production-Like Java Full-Supervisor Seam

- approval: On 2026-07-22 the Project Owner's standing authorization through BUG-009 signoff approved an offline-only regression and correction for Runtime 5's trusted-Java candidate gap. This authorization excludes any new runtime, runId, Docker rehearsal, restricted evidence access, or manual cleanup.
- pre_fix_behavior: A test-owned Java 17 listener launched through the real `exercise_invoke_child()` -> held child -> `start_child()` nesting reached the real `supervise_exercise()` path, but five consecutive isolated executions failed closed with `listener-candidate-absent` and `IDENTITY_STABILITY_MISMATCH`. One transient JVM task-set change during a complete descendant-domain read could prevent a later stable pair from being considered.
- target: Permit bounded, independent sampling attempts so transient JVM task churn cannot permanently hide a later stable complete descendant domain, without merging evidence or weakening any identity, socket, health, A/B/final, signal-mask, or at-most-one-TERM gate.
- acceptance:
  - Every attempt starts with one complete descendant-domain/task-set snapshot. Only after that first snapshot succeeds may the same attempt take a second snapshot, and only an identical complete pair from that attempt may succeed.
  - No snapshot, PID, edge, task set, or identity may be merged, unioned, or reused across attempts.
  - `PROC_UNAVAILABLE` and `PROC_MALFORMED` remain immediate failures; bounded exhaustion remains `IDENTITY_MISMATCH`.
  - A production-like test-owned Java 17 seam invokes the real `supervise_exercise()` path and proves health, parent/domain/listener identity, A/B/final reproof, exactly one TERM, `dispatchSafe=true`, and its test-custom `EXIT_143`.
  - Runtime 5 remains consumed; Runtime 6 and any replacement runId remain unauthorized.
- non_goals: No real Launcher/Docker lifecycle, root receipt or residue acceptance; no TMS/SIM, API, permission, credential, Worker, Gateway, Pool, identity, Codex route, external, or production change.

## Production-Like Java Full-Supervisor Seam Implementation Result

- implementation_summary:
  - `stable_exercise_descendant_domain()` now makes at most eight attempts with a 50 ms interval. A first-snapshot mismatch ends that attempt; a successful first snapshot must be followed by a complete identical second snapshot from the same attempt before the domain can succeed.
  - `PROC_UNAVAILABLE` / `PROC_MALFORMED` still fail immediately. Incomplete or mismatched attempts are never composed, and final exhaustion remains fail closed as `IDENTITY_MISMATCH`.
  - Unit regressions cover first-snapshot mismatch delay, cross-attempt non-composition, same-attempt success, retry exhaustion, and no retry for `PROC_*`.
  - The production-like seam builds a test-owned Java 17 JAR with a minimal loopback health listener, traverses the real nested harness launch chain inside an isolated PID namespace, and invokes the real `supervise_exercise()`. Only the test JAR's expected argv is patched; trusted Java, domain, socket, health, and signal gates are real. Its custom outer exits `143`, so it proves the supervision path only and is deliberately ineligible for the real `main()` completion contract.
- changed_paths_for_this_slice:
  - `tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py`
  - `tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py`
  - `docs/version-tracker/1.4.3-SNAPSHOT/test-records/BUG-009-int001-production-like-java-seam-offline-2026-07-22.md`
  - this canonical work item, the INT-001 runbook, and the version index
- tests_and_results:
  - Production-like full-supervisor Java seam — PASS, `1/1`.
  - Complete Python supervisor suite — PASS, `85/85` after the strict `main()` completion-gate regression was added.
  - Targeted synthetic harness safety suite — PASS, `85/85`.
  - Complete synthetic TypeScript suite — PASS, `109 passed / 1 skipped`.
  - TypeScript typecheck — PASS.
  - Shell syntax (`bash -n`) — PASS.
  - Python compile (`py_compile`) — PASS.
  - `git diff --check` — PASS.
  - Scoped high-confidence secret scan — PASS, `0 matches`.
- durable_record: `../test-records/BUG-009-int001-production-like-java-seam-offline-2026-07-22.md`
- historical_readiness: At this offline checkpoint, Runtime 5 was `CONSUMED_FAIL_CLOSED` and Runtime 6 had been frozen but not yet executed. Runtime 6 has since executed and is also permanently consumed; this historical checkpoint grants no current runtime authority. AC-2/AC-3 remain open until a separately authorized real disposable runtime satisfies the complete success gate.

## Strict Main Completion Gate Offline Repair

- blocker: Independent test-readiness review found that `main()` accepted any non-null child wait status and did not explicitly require the exact parent/listener proof reasons, exact listener identity, `listenerProofEverEligible`, `HOLD_SIGNAL_RECEIVED`, or `HEALTH_READY`. The prior positive unit fixture used `NOT_REHEARSAL` and `EXIT_0`, so it could report `SUCCESS_GATE_MET` for evidence that the real forced-SIGNAL harness cannot produce as success.
- real_exit_contract: Both the held `run-hold` `lifecycle_signal_cleanup()` and the outer `exercise_signal_cleanup()` explicitly `exit 128` after owned cleanup. The exact successful supervisor wait status is therefore a normal exit with `WEXITSTATUS=128`, rendered as `EXIT_128`. The production-like seam's custom `EXIT_143` is not this contract.
- implementation:
  - Added one explicit `forced_signal_completion_gate_met()` predicate used by `main()` after the final pending-signal latch.
  - Success now requires no supervisor interruption; controlled health; exact parent reason `commandLine+cwd+runId+uid+session+startTicks`; exact listener reason `uid+java+argv+cwd+ancestor+socket+startTicks`; identity `EXACT_CANDIDATE_FOUND`; `listenerProofEverEligible=true`; exactly one TERM; `dispatchSafe=true`; normal exact `EXIT_128`; strict receipt `CLEANED/SIGNAL + HOLD_SIGNAL_RECEIVED + HEALTH_READY + NOT_APPLICABLE`; private absent; root residue zero; exact reservation absent; and Docker `0/0/0`.
  - Receipt comparisons use missing-safe field reads, so a partial or mocked receipt fails closed instead of raising. Regression-first negative coverage rejects each missing required receipt field as well as interruption, missing/incorrect health and proofs, wrong reason/identity/eligibility, TERM/dispatch mismatch, child status `None`, `EXIT_0`, `EXIT_1`, `EXIT_143`, signal termination, wrong receipt enums, private/root/reservation residue, and each Docker failure shape.
  - The former `NOT_REHEARSAL + EXIT_0` success test now uses the exact real-harness receipt and `EXIT_128` contract.
- tests_and_results:
  - Regression before implementation — FAIL as expected because the exact completion constants/helper did not exist.
  - Strict completion helper matrix — PASS, `2/2` test methods with all negative subcases.
  - Supervisor orchestration suite — PASS, `29/29`.
  - Complete Python supervisor suite — PASS, `85/85`.
  - Complete synthetic TypeScript suite — PASS, `109 passed / 1 skipped`.
  - TypeScript typecheck — PASS.
  - Three shell syntax checks and Python compile — PASS.
  - Final `git diff --check` — PASS.
  - Scoped high-confidence secret scan over tracked added lines and untracked BUG-009 durable records — PASS, `0 matches`.
- offline_gate_status: complete; independent read-only code/security, test-readiness, and canonical/docs re-authorization reviews all passed with no blocking finding. These reviews did not execute or consume Runtime 6.
- historical_runtime_boundary: No Runtime 6, Docker rehearsal, shared service, restricted evidence, manual cleanup, or new runId was used during that re-authorization. At that historical checkpoint Runtime 6 became `AUTHORIZED_NOT_EXECUTED` for exactly one execution of the frozen command under the fixed evidence and failure boundaries below. It has since executed and is permanently consumed; this paragraph cannot reactivate it.

## Diagnostic Runtime 6 Execution Result

- historical_approval_basis: On 2026-07-22, the Project Owner's standing authorization through BUG-009 signoff permitted that bounded step. The production-like seam and complete offline gate had passed, followed by independent read-only code/security, test-readiness, and canonical/docs re-authorization reviews with no blocking finding. That authorization permitted only the exact single execution below; it has since been exercised and permanently consumed.
- exact_run_id: `int001-bug009-20260722-r6-4c8e1d7a`
- exact_command: `PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py --run-id int001-bug009-20260722-r6-4c8e1d7a`
- execution_limit: Exactly one loopback-only disposable execution of the exact command. Authorization is consumed when the command starts, regardless of exit or evidence shape. This runId must never be retried; a failure grants no replacement runId without another bounded offline replan, complete gate and independent review.
- status: `CONSUMED_FAIL_CLOSED`; the exact command executed once and exited `1`. This runId is permanently consumed and cannot be retried or replaced under this authorization.
- success_gate: no supervisor interruption; `controlledHealthPrecondition=true`; exact parent proof `commandLine+cwd+runId+uid+session+startTicks`; exact listener proof `uid+java+argv+cwd+ancestor+socket+startTicks` with `EXACT_CANDIDATE_FOUND`; `listenerProofEverEligible=true`; `termDispatches=1`; `dispatchSafe=true`; normal exact `EXIT_128`; strict schema-v4 redacted receipt `CLEANED/SIGNAL` with `HOLD_SIGNAL_RECEIVED`, `HEALTH_READY`, and `NOT_APPLICABLE`; `privateAbsent=true`; root non-receipt residue `0`; Docker container/network/volume residue `0/0/0`; exact reservation absent under the valid shared registry lock.
- allowed_evidence: Only the supervisor fixed-enum/redacted stdout summary, fixed-schema sibling projection, root receipt fixed fields, private-absent boolean, root non-receipt residue count, redacted Docker residue counts and exact-reservation-absent boolean. No raw identifiers beyond this exact runId may enter durable evidence.
- prohibited_actions: No historical or current run `private/children/log/profile/payload/process/Docker` detail; no manual cleanup; no retry; no shared `8112`; no real TMS/SIM, profile/credential, Worker, Gateway, Pool, identity, Codex route, external exposure or production change.
- fixed_runtime_boundary: The disposable child may set `NAVIGATOR_EXTERNAL_ENABLED=true` only as its loopback `/api/v1/open/**` route gate and must keep `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false`. This cannot prove Provider, Gateway or production readiness.
- failure_rule: Any missing success-gate item is `CONSUMED_FAIL_CLOSED`; record only allowed redacted evidence, keep AC-2/AC-3 open and return to offline replan. Never inspect restricted evidence or clean manually.
- allowed_redacted_result:
  - `controlledHealthPrecondition=false`
  - exact parent proof `commandLine+cwd+runId+uid+session+startTicks`
  - `listenerProof=listener-candidate-absent`; `listenerIdentityDiagnostic=NO_TRUSTED_JAVA_CANDIDATE`; `listenerProofEverEligible=false`
  - `termDispatches=0`; `dispatchSafe=false`; `supervisorInterruption=NONE`; `exerciseExit=EXIT_2`
  - schema-v4 receipt `CLEANED/UNKNOWN + HOLD_TIMEOUT + HEALTH_READY + NOT_APPLICABLE`, mode `0600`, redacted
  - `privateAbsent=true`; root non-receipt residue `0`; Docker container/network/volume residue `0/0/0`
- conclusion: Runtime 6 did not satisfy controlled health, listener proof, eligibility, TERM, exit or receipt success gates. AC-2/AC-3 remain open. The next action is bounded static source/test diagnosis only; no retry, replacement runId, restricted evidence access or manual cleanup is authorized.
- durable_record: `../test-records/BUG-009-int001-runtime6-failclosed-2026-07-22.md`

## Approved Offline Replan: Host-Namespace Socket-Holder Proof

- approval: On 2026-07-22 the Project Owner's standing authorization through BUG-009 signoff approved this bounded harness-only security-contract amendment after Runtime 6 failed closed. It authorizes offline regression and correction only; it does not authorize Runtime 7, Docker execution, restricted evidence access or manual cleanup.
- superseded_contract: This amendment replaces the earlier BUG-009 statements that the current-user socket-holder scan must remain globally complete or cannot be bounded. Historical sections continue to describe what was implemented and reviewed at that time, but they no longer define the current holder authorization contract. Candidate FD ownership, exact-inode uniqueness, readable out-of-domain holder veto, A/B/final reproof and one-TERM limits remain mandatory.
- regression_evidence: The existing production-like Java seam passes in its isolated PID namespace but fails deterministically when the same test-owned topology runs in the host PID namespace. The host-mode fixed-enum result reaches `EXACT_CANDIDATE_FOUND` and then fails `socket-owner`; nineteen holder checks returned `UNAVAILABLE`. This proves unrelated host same-UID procfs unreadability, not Java/argv/cwd/lineage drift, is the immediate false-negative boundary. Runtime 6's terminal `NO_TRUSTED_JAVA_CANDIDATE` is not promoted as root cause because the supervision loop overwrote earlier diagnostics after the 180-second hold cleanup.
- target:
  - Bind complete socket-holder proof to the already proven, stable exercise descendant domain. Every in-domain process and FD view remains mandatory and fail-closed; the exact socket holder inside that domain must be only the exact Launcher candidate.
  - Continue scanning readable out-of-domain current-user FD views and veto any observed exact-inode holder, but do not let an unrelated out-of-domain unreadable or transient procfs entry invalidate a fully proven run-owned domain. This relies only on the already documented local disposable harness threat model that trusts the single same-UID operator; it does not broaden Navigator runtime authority.
  - Preserve candidate-owned IPv4 loopback listener/inode proof, exact Java/argv/cwd/exe/lineage/startTicks, A/B/final reproof, pending-signal commit point, at-most-one TERM and all receipt/residue gates.
  - Preserve the furthest fixed-enum listener identity diagnostic observed during the live supervision window so hold cleanup cannot overwrite a stronger earlier diagnosis. Diagnostics never authorize TERM or completion.
- regression_first_acceptance:
  - The non-isolated production-like seam fails before the correction and passes after it; the isolated seam remains passing.
  - An unreadable in-domain process or FD, another in-domain exact-inode holder, or a readable out-of-domain exact-inode holder rejects the proof.
  - An unreadable unrelated out-of-domain process does not reject an otherwise exact run-owned proof.
  - A sequence such as `EXACT_CANDIDATE_FOUND/socket-owner` followed by cleanup-time `NO_TRUSTED_JAVA_CANDIDATE` reports the furthest safe diagnostic without changing the zero-TERM failure decision.
- non_goals: No PID witness channel, task-set relaxation, new API/credential/Worker/Gateway/Pool/identity/Codex route, production exposure, real TMS/SIM access, or success-gate relaxation.

## Host-Namespace Socket-Holder Implementation Result

- implementation_summary:
  - Socket-holder exclusivity is now anchored to two identical complete snapshots of the already proven exercise descendant domain. The exact Launcher candidate must be the only in-domain exact-inode holder, and any unreadable in-domain FD view fails closed.
  - Readable out-of-domain current-user FD views still veto an observed shared exact-inode holder. Only an unrelated out-of-domain unreadable/transient procfs view is omitted under the approved single-same-UID-operator disposable-harness threat model.
  - The supervision outcome retains the furthest temporal fixed-enum identity progress. An earlier exact candidate is not overwritten by cleanup-time absence, and a later exact observation supersedes an earlier procfs failure; this diagnostic never changes TERM or completion eligibility.
  - The production-like Java seam passes both directly in the host PID namespace and through its isolated PID-namespace wrapper. Candidate FD, exact IPv4 loopback, Java/argv/cwd/exe/lineage/startTicks, A/B/final, pending-signal, one-TERM, receipt, reservation and residue gates remain in place.
- changed_paths:
  - `tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py`
  - `tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py`
  - `docs/version-tracker/1.4.3-SNAPSHOT/runbooks/INT-001-synthetic-upstream-integration-harness.md`
  - `docs/version-tracker/1.4.3-SNAPSHOT/test-records/BUG-009-int001-host-namespace-socket-holder-offline-2026-07-22.md`
  - this canonical work item and the version index
- tests_and_results:
  - Host PID namespace production-like seam with `ResourceWarning` promoted to error — PASS, `1/1`.
  - Isolated PID namespace production-like seam — PASS, `1/1`.
  - Complete Python supervisor suite — PASS, `93/93`.
  - Complete synthetic TypeScript suite — PASS, `109 passed / 1 skipped`.
  - TypeScript typecheck, three shell syntax checks, Python compile, `git diff --check` and scoped high-confidence secret scan — PASS; secret scan `0 matches`.
- deviations: none from the approved bounded amendment. No runtime, Docker stack, shared service, restricted run evidence, manual cleanup, real upstream, credential, Worker/Gateway/Pool/identity/Codex route, external exposure or production target was used.
- residual_risks:
  - Ignoring an unreadable out-of-domain same-UID process depends on the explicit local disposable harness assumption that one trusted operator controls that UID. It is not suitable evidence for shared-host or production authorization.
  - The seam proves the supervised ownership/signal path, not the full Docker Launcher receipt/residue completion contract. AC-2/AC-3 remain open until a separately authorized runtime succeeds.
- durable_record: `../test-records/BUG-009-int001-host-namespace-socket-holder-offline-2026-07-22.md`
- review_status: PASS. Independent code/security, test/runtime-readiness and canonical/docs reviews reported no blocking finding. Each review allows the exact-freeze step only; none treats offline success as runtime or production acceptance.
- runtime_authorization: Historical at this implementation-result checkpoint, Runtime 7 was frozen as one exact one-shot command. It has since executed and is `CONSUMED_FAIL_CLOSED`; Runtime 4/5/6/7/8 are permanently consumed, and this section grants no current runtime authority.

## Runtime 7 Exact Authorization Freeze

- historical_authorization_basis: On 2026-07-22 the Project Owner explicitly authorized the agent to directly approve bounded continuation items through BUG-009 signoff. After all three independent host-holder reviews passed, that standing authorization permitted one exact disposable Runtime 7 execution and no broader action; it has since been consumed and grants no current runtime authority.
- exact_run_id: `int001-bug009-20260722-r7-6d3f8a1c`
- exact_command: `PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py --run-id int001-bug009-20260722-r7-6d3f8a1c`
- preflight:
  - runId matches the strict pattern; exact run directory, sibling projection and reservation path are absent;
  - canonical artifact root is current-UID, non-symlink and `0700`;
  - strict reservation registry validation passes and the exact reservation is absent;
  - the fixed local Docker Unix socket is safe;
  - exact-run Docker container/network/volume residue counts are `0/0/0`.
- one_shot_rule: Execute the exact command once only. On any exit or interruption the authorization becomes consumed. Never retry this runId, substitute another runId, manually clean up, or inspect `private/children/log/profile/payload/process/Docker` details.
- success_gate: no supervisor interruption; controlled health; exact parent `commandLine+cwd+runId+uid+session+startTicks`; exact listener `uid+java+argv+cwd+ancestor+socket+startTicks` with `EXACT_CANDIDATE_FOUND`; `listenerProofEverEligible=true`; exactly one TERM; `dispatchSafe=true`; normal exact `EXIT_128`; schema-v4 redacted `CLEANED/SIGNAL + HOLD_SIGNAL_RECEIVED + HEALTH_READY + NOT_APPLICABLE`; private absent; root non-receipt residue `0`; exact reservation absent; Docker `0/0/0`.
- evidence_boundary: Only the supervisor fixed-enum/redacted stdout, fixed-schema sibling projection, root receipt fixed fields, private-absent boolean, root residue count, exact-reservation-absent result and redacted Docker counts may be used. Do not access shared `8112`, real TMS/SIM, credentials, Workers, Gateway, Pool, identities, Codex routes, or production configuration. The child-only `NAVIGATOR_EXTERNAL_ENABLED=true` remains a loopback Open API route gate and `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false` remains mandatory.
- status: `CONSUMED_FAIL_CLOSED`. The exact command executed once and cannot be retried or replaced under this authorization. BUG-009 remains `ULTRA_EXECUTING`; AC-2/AC-3 remain open.

## Runtime 7 Execution Result

- exact_run_id: `int001-bug009-20260722-r7-6d3f8a1c`
- exact_command: `PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py --run-id int001-bug009-20260722-r7-6d3f8a1c`
- execution_consumption: Executed exactly once and exited `1`; authorization is permanently consumed.
- allowed_redacted_result:
  - no supervisor interruption; exact parent proof `commandLine+cwd+runId+uid+session+startTicks`;
  - temporal identity diagnostic `EXACT_CANDIDATE_FOUND`, but terminal listener proof `listener-candidate-absent` and `listenerProofEverEligible=false`;
  - `controlledHealthPrecondition=false`, `termDispatches=0`, `dispatchSafe=false`, exact outer result `EXIT_2`;
  - schema-v4 receipt `CLEANED/UNKNOWN + HOLD_TIMEOUT + HEALTH_READY + NOT_APPLICABLE`, mode `0600`, secrets redacted;
  - private absent, root non-receipt residue `0`, Docker container/network/volume residue `0/0/0`.
- gate_result: FAIL. `EXACT_CANDIDATE_FOUND` is temporal diagnostic progress only. The run never completed listener proof, never authorized or sent TERM, did not exit `128`, and did not produce `CLEANED/SIGNAL + HOLD_SIGNAL_RECEIVED`; AC-2/AC-3 remain open.
- evidence_note: Exact reservation absence was not emitted as a standalone stdout field and is not invented as an independently observed result. Receipt adoption still requires the source-level shared-lock reservation-absence contract.
- durable_record: `../test-records/BUG-009-int001-runtime7-failclosed-2026-07-22.md`
- boundary: Runtime 7 may not be retried, replaced, manually cleaned or supplemented by reading `private/children/log/profile/payload/process/Docker` details. No shared `8112`, real TMS/SIM, credential, Worker, Gateway, Pool, identity, Codex route, external exposure or production target was used.

## Approved Offline Replan: Temporal Listener Proof Stage Diagnostic

- approval: The Project Owner's standing authorization through BUG-009 signoff permits this bounded offline-only replan after Runtime 7 failed closed. It does not authorize a new runtime, runId, Docker rehearsal, restricted evidence access or manual cleanup.
- problem: Runtime 7 proved an exact candidate existed at least once, but the terminal listener reason was cleanup-time absence. The existing redacted output retains furthest identity progress but not the furthest post-identity listener-proof stage, so allowed evidence cannot distinguish socket-table, socket ownership, holder exclusivity or later reproof failure.
- target: Add one fixed-enum, non-authorizing temporal listener-proof stage diagnostic that preserves the furthest safe stage reached across supervision time without exposing PID, port, inode, argv, cwd, path, counts, exception text or raw reason values.
- stage_contract: `NOT_OBSERVED`, `EXACT_IDENTITY_FOUND`, `LISTENER_SOCKET_FOUND`, `INITIAL_OWNERSHIP_PROVED`, `FULL_ELIGIBLE`. The value is diagnostic only and cannot change `listenerProofEverEligible`, health order, A/B/final reproof, pending-signal checks, TERM authorization, completion, cleanup or receipt adoption.
- required_validation:
  - regression-first coverage for every stage and for later cleanup-time candidate absence preserving the furthest earlier stage;
  - unknown diagnostic values collapse to `NOT_OBSERVED` and never echo raw input;
  - host-namespace and isolated production-like seams remain passing, followed by the complete Python and synthetic TypeScript suites, typecheck, shell syntax, Python compile, diff check and scoped secret scan;
  - independent code/security, test/runtime-readiness and canonical/docs reviews are required before any future exact runtime freeze.
- stop_rule: If implementation requires weakening any candidate, socket, FD, holder, descendant-domain, identity, A/B/final, signal-mask, one-TERM, receipt, reservation or residue gate, stop and replan. Offline success alone grants no runtime authority.

## Temporal Listener Proof Stage Implementation Result

- implementation_summary:
  - `ListenerProof` now carries one in-memory fixed stage enum, and `RehearsalOutcome` retains only the furthest allow-listed stage observed across supervision time.
  - stdout adds `listenerProofStageDiagnostic` with exactly `NOT_OBSERVED`, `EXACT_IDENTITY_FOUND`, `LISTENER_SOCKET_FOUND`, `INITIAL_OWNERSHIP_PROVED` or `FULL_ELIGIBLE`. Unknown values collapse to `NOT_OBSERVED` without echo.
  - stage advancement mirrors existing proof order only. It is not consulted by listener eligibility, health ordering, A/B/final reproof, TERM dispatch, receipt adoption or the strict completion gate.
  - cleanup-time candidate absence cannot erase an earlier post-identity stage, while the terminal `listenerProof` reason and `listenerProofEverEligible` continue to report the live fail-closed result.
- changed_paths:
  - `tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py`
  - `tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py`
  - `docs/version-tracker/1.4.3-SNAPSHOT/test-records/BUG-009-int001-runtime7-failclosed-2026-07-22.md`
  - `docs/version-tracker/1.4.3-SNAPSHOT/test-records/BUG-009-int001-temporal-listener-stage-offline-2026-07-22.md`
  - this canonical work item, the version index and the INT-001 runbook.
- tests_and_results:
  - host PID namespace production-like seam — PASS, `1/1`;
  - isolated PID namespace production-like seam — PASS, `1/1`;
  - complete Python supervisor suite — PASS, `96/96`;
  - complete synthetic TypeScript suite — PASS, `109 passed / 1 skipped`;
  - TypeScript typecheck, three shell syntax checks, Python compile and `git diff --check` — PASS;
  - scoped high-confidence secret scan — PASS, `0 matches`.
- deviations: none from the approved offline-only scope. No runtime, Docker rehearsal, restricted evidence, manual cleanup, shared service, real upstream, credential, Worker/Gateway/Pool/identity/Codex route, external exposure or production target was used.
- durable_record: `../test-records/BUG-009-int001-temporal-listener-stage-offline-2026-07-22.md`
- readiness: offline implementation and local gates pass. Independent code/security, test/runtime-readiness and canonical/docs reviews all passed after the document-consistency corrections. Each review permits only the separate exact-freeze step; none satisfies AC-2/AC-3 or BUG-level acceptance.
- review_status: PASS. No blocking code, security, test-readiness or canonical-document finding remains.
- runtime_authorization: Runtime 4/5/6/7/8 are permanently consumed. This temporal-stage offline result grants no current runtime authority; Runtime 8's historical exact freeze and consumed result are recorded below.

## Runtime 8 Exact Authorization Freeze

- historical_authorization_basis: On 2026-07-22 the Project Owner explicitly authorized direct bounded continuation through BUG-009 signoff. After the temporal-stage amendment passed all three independent reviews, that standing authorization permitted one exact disposable Runtime 8 execution and no broader action; it has since been consumed and grants no current runtime authority.
- exact_run_id: `int001-bug009-20260722-r8-212fde1c`
- exact_command: `PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py --run-id int001-bug009-20260722-r8-212fde1c`
- preflight:
  - runId matches the strict pattern; exact run directory, sibling projection and reservation path are absent;
  - canonical artifact root is current-UID, non-symlink and `0700`;
  - strict reservation registry validation passes and the exact reservation is absent;
  - the fixed local Docker Unix socket is safe;
  - exact-run Docker container/network/volume residue counts are `0/0/0`.
- preflight_note: The first read-only Python import wrapper stopped before any check because the module was not registered in `sys.modules`; the corrected read-only wrapper passed every item above and created no run, projection or reservation artifact.
- one_shot_rule: Execute the exact command once only. On any exit or interruption the authorization becomes consumed. Never retry this runId, substitute another runId, manually clean up, or inspect `private/children/log/profile/payload/process/Docker` details.
- success_gate: no supervisor interruption; controlled health; exact parent `commandLine+cwd+runId+uid+session+startTicks`; exact listener `uid+java+argv+cwd+ancestor+socket+startTicks` with `EXACT_CANDIDATE_FOUND`; `listenerProofStageDiagnostic=FULL_ELIGIBLE`; `listenerProofEverEligible=true`; exactly one TERM; `dispatchSafe=true`; normal exact `EXIT_128`; schema-v4 redacted `CLEANED/SIGNAL + HOLD_SIGNAL_RECEIVED + HEALTH_READY + NOT_APPLICABLE`; private absent; root non-receipt residue `0`; exact reservation absent; Docker `0/0/0`.
- evidence_boundary: Only the supervisor fixed-enum/redacted stdout, fixed-schema sibling projection, root receipt fixed fields, private-absent boolean, root residue count, exact-reservation-absent result and redacted Docker counts may be used. Do not access shared `8112`, real TMS/SIM, credentials, Workers, Gateway, Pool, identities, Codex routes, or production configuration. The child-only `NAVIGATOR_EXTERNAL_ENABLED=true` remains a loopback Open API route gate and `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false` remains mandatory.
- status: `CONSUMED_FAIL_CLOSED`. The exact command executed once and cannot be retried or replaced. BUG-009 remains `ULTRA_EXECUTING`; AC-2/AC-3 remain open.

## Runtime 8 Execution Result

- exact_run_id: `int001-bug009-20260722-r8-212fde1c`
- exact_command: `PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py --run-id int001-bug009-20260722-r8-212fde1c`
- execution_consumption: Executed exactly once and exited `1`; authorization is permanently consumed.
- allowed_redacted_result:
  - no supervisor interruption; exact parent proof `commandLine+cwd+runId+uid+session+startTicks`;
  - temporal identity `EXACT_CANDIDATE_FOUND` and stage `EXACT_IDENTITY_FOUND`, but terminal listener `listener-candidate-absent` and `listenerProofEverEligible=false`;
  - `controlledHealthPrecondition=false`, `termDispatches=0`, `dispatchSafe=false`, exact outer result `EXIT_2`;
  - schema-v4 receipt `CLEANED/UNKNOWN + HOLD_TIMEOUT + HEALTH_READY + NOT_APPLICABLE`, mode `0600`, secrets redacted;
  - private absent, root non-receipt residue `0`, Docker container/network/volume residue `0/0/0`.
- gate_result: FAIL. The run reached exact identity but never reached `LISTENER_SOCKET_FOUND` or `FULL_ELIGIBLE`, never authorized or sent TERM, did not exit `128`, and did not produce `CLEANED/SIGNAL + HOLD_SIGNAL_RECEIVED`. AC-2/AC-3 remain open.
- evidence_note: Exact reservation absence was not emitted as a standalone stdout field and is not invented as an independently observed result. Receipt adoption still requires the source-level shared-lock reservation-absence contract.
- durable_record: `../test-records/BUG-009-int001-runtime8-failclosed-2026-07-22.md`
- boundary: Runtime 8 may not be retried, replaced, manually cleaned or supplemented by reading `private/children/log/profile/payload/process/Docker` details. No shared `8112`, real TMS/SIM, credential, Worker, Gateway, Pool, identity, Codex route, external exposure or production target was used.

## Approved Offline Replan: IPv4-Mapped Loopback Listener Representation

- approval: The Project Owner's standing authorization through BUG-009 signoff permits this bounded offline-only replan after Runtime 8 failed closed. It does not authorize Runtime 9, a new runId, Docker rehearsal, restricted evidence access or manual cleanup.
- source_diagnosis:
  - Runtime 8 safely proves only that exact Launcher identity was reached while no accepted listener socket stage was observed. It does not itself prove the concrete socket-family cause.
  - Static source review shows the supervisor rejects every matching `tcp6` LISTEN row as non-loopback/IPv6, while the real Tomcat/JDK path may represent a literal `127.0.0.1` bind as the canonical IPv4-mapped IPv6 address in `/proc/<pid>/net/tcp6` when IPv6 is available.
  - The current production-like Java seam explicitly uses `ServerSocketChannel.open(StandardProtocolFamily.INET)`, forcing an IPv4 socket and therefore not exercising the real no-argument JDK/Tomcat channel behavior.
- target: Treat only the Linux procfs canonical IPv4-mapped representation of literal `127.0.0.1` as equivalent to the existing strict IPv4 loopback row, without accepting native IPv6, wildcard, non-loopback or ambiguous listeners.
- required_regression_first:
  - Add a parser regression that fails before the correction for one `tcp6` canonical IPv4-mapped `127.0.0.1` LISTEN row and passes after it.
  - Preserve rejection of IPv4/IPv6 wildcard, native `::1`, non-loopback IPv4-mapped addresses, malformed rows, tcp+tcp6 duplicates and multiple listener inodes.
  - Change the test-owned production-like Java listener to the no-argument `ServerSocketChannel.open()` used by the real Tomcat path; run it in both host and existing isolated PID-namespace seams.
  - Add a real-procfs negative seam for exact identity with no bound listener, proving `EXACT_IDENTITY_FOUND`, `0 TERM` and fail-closed behavior if practical without weakening or mocking ownership/socket/signal predicates. If this materially broadens the slice, record it as a separately reviewable test follow-up before any future runtime.
- implementation_constraint:
  - Accept only `0100007F` in `/proc/.../tcp` or `0000000000000000FFFF00000100007F` in `/proc/.../tcp6`, case-insensitively, for the exact requested port and LISTEN state.
  - Continue to require exactly one accepted inode. Candidate FD ownership, complete descendant-domain holder proof, readable out-of-domain exact-inode veto, exact identity, A/B/final reproof, pending-signal mask, at-most-one TERM, receipt, reservation and residue gates remain unchanged.
  - Do not add `java.net.preferIPv4Stack`, change Launcher network behavior, trust health alone, accept native IPv6 loopback, or broaden diagnostics with raw address/socket values.
- validation:
  - focused parser regression red-before/green-after;
  - host and isolated production-like seams;
  - complete Python supervisor suite, synthetic TypeScript suite, typecheck, three shell syntax checks, Python compile, `git diff --check` and scoped high-confidence secret scan;
  - independent code/security, test/runtime-readiness and canonical/docs reviews before any exact runtime freeze.
- stop_rule: If the correction requires accepting any address beyond literal IPv4 loopback semantics, weakening unique-inode/FD/holder/reproof/signal gates, changing public/runtime authorization, or inspecting restricted runtime artifacts, stop and replan. Offline success alone grants no runtime authority.

## IPv4-Mapped Loopback Listener Implementation Result

- implementation_summary:
  - `listener_socket_probe_from_tables()` now accepts only literal IPv4 loopback `0100007F` from `tcp` or canonical IPv4-mapped loopback `0000000000000000FFFF00000100007F` from `tcp6`, case-insensitively, for the exact requested LISTEN port.
  - Native IPv6, `::1`, wildcard, non-loopback mapped addresses, malformed rows, `tcp` plus `tcp6` duplication and multiple inodes remain fail closed.
  - The production-like Java seam now uses no-argument `ServerSocketChannel.open()`. The additional `TEST_BIND_GATE` seam proves exact identity without a listener stays at `EXACT_IDENTITY_FOUND`, sends `0 TERM`, never becomes dispatch-safe, and tears down only its test-owned session.
  - Candidate FD ownership, stable descendant-domain holder exclusivity, readable out-of-domain holder veto, exact identity, A/B/final reproof, pending-signal mask, one-TERM, receipt, reservation and residue gates were not weakened.
- regression_first:
  - Before the correction, `strict-mapped-loopback-listener` and `tcp-and-mapped-tcp6-ambiguous` failed as expected.
  - After the correction, focused parser, positive production-like seam and no-listener negative seam all pass in host and isolated PID namespaces.
- final_validation:
  - focused parser — PASS, `1/1`;
  - host production-like happy plus no-listener negative seams — PASS, `2/2`;
  - isolated production-like happy plus no-listener negative seams — PASS, `2/2`;
  - complete Python supervisor suite — PASS, `97/97`;
  - complete synthetic TypeScript suite — PASS, `109 passed / 1 skipped`;
  - TypeScript typecheck, three shell syntax checks, Python compile and `git diff --check` — PASS;
  - scoped high-confidence secret scan — PASS, `0 matches`;
  - final exact test-owned production-like Java residue check — PASS, `0`.
- residue_note: Two earlier happy-seam Java orphans were proven test-owned by exact runId, trusted Java, repository test-artifact path, UID, process group/session and start ticks, then terminated only through their test-owned process groups. Both exited; no Runtime 8, shared service, Worker, TMS or SIM process was touched.
- durable_record: `../test-records/BUG-009-int001-ipv4-mapped-loopback-offline-2026-07-22.md`
- review_status: `PASS`. Independent code/security, test/runtime-readiness and canonical/docs reviews found no blocker after the historical-authorization wording corrections. Each review permits only the separate exact-freeze step and does not satisfy AC-2/AC-3.
- current_status: Runtime 4/5/6/7/8/9 remain permanently `CONSUMED_FAIL_CLOSED`; Runtime 9 is the latest consumed runtime. AC-2/AC-3 remain open and current runtime authorization is none.

## Runtime 9 Exact Authorization Freeze

- freeze_status: `CONSUMED_FAIL_CLOSED`. Independent code/security, test/readiness and canonical/docs exact-freeze reviews passed, the command executed once, and the authorization is permanently consumed.
- historical_authorization_basis: The Project Owner's standing authorization through BUG-009 signoff established eligibility to freeze this bounded disposable rehearsal after the IPv4-mapped loopback offline gate and all three independent reviews passed. That one-shot authority was exercised and permanently consumed; this paragraph cannot reactivate it or authorize any later runtime.
- exact_run_id: `int001-bug009-20260722-r9-33154d77`
- exact_command: `PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py --run-id int001-bug009-20260722-r9-33154d77`
- preflight:
  - strict runId passed; exact run directory, sibling projection and reservation path are absent;
  - canonical artifact root is current-UID, non-symlink and `0700`;
  - strict reservation registry validation passed and the exact reservation is absent;
  - fixed local Docker Unix socket is safe;
  - exact-run Docker container/network/volume residue counts are `0/0/0`;
  - exact test-owned production-like Java residue is `0` after all offline tests.
- historical_one_shot_rule: Runtime 9 was permitted to execute this exact command once only. It has executed and the authorization is permanently consumed. Never retry this runId, substitute another runId, manually clean up, inspect `private/children/log/profile/payload/process/Docker` details or treat the historical conditions as reactivation criteria.
- success_gate: no supervisor interruption; controlled health; exact parent `commandLine+cwd+runId+uid+session+startTicks`; exact listener `uid+java+argv+cwd+ancestor+socket+startTicks` with `EXACT_CANDIDATE_FOUND`; `listenerProofStageDiagnostic=FULL_ELIGIBLE`; `listenerProofEverEligible=true`; exactly one TERM; `dispatchSafe=true`; normal exact `EXIT_128`; schema-v4 redacted `CLEANED/SIGNAL + HOLD_SIGNAL_RECEIVED + HEALTH_READY + NOT_APPLICABLE`; private absent; root non-receipt residue `0`; exact reservation absent; Docker `0/0/0`.
- evidence_boundary: Only fixed-enum/redacted stdout, the fixed-schema sibling projection, root receipt fixed fields, private-absent boolean, root residue count, exact-reservation-absent result and redacted Docker counts may be used. Do not access shared `8112`, real TMS/SIM, credentials, Workers, Gateway, Pool, identities, Codex routes, or production configuration. Child-only `NAVIGATOR_EXTERNAL_ENABLED=true` remains a disposable loopback Open API route gate and `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false` remains mandatory.
- execution_result:
  - the exact command executed once and exited `1`;
  - controlled health and exact parent/listener proof passed; temporal listener stage was `FULL_ELIGIBLE`, listener was ever eligible, exactly one TERM was sent, and `dispatchSafe=true`;
  - supervisor interruption was `NONE`, but the outer result was `EXIT_2`, no receipt was accepted, private was absent, root non-receipt residue was `1`, and Docker container/network/volume residue was `2/1/1`;
  - the success gate failed, so AC-2/AC-3 remain open.
- durable_record: `../test-records/BUG-009-int001-runtime9-failclosed-2026-07-22.md`
- current_runtime_authorization: none. Runtime 9 is permanently consumed; no retry, replacement runId, manual cleanup, restricted evidence access or runtime is authorized.

## Approved Offline Replan: Runtime 9 Post-TERM Cleanup and Receipt

- approval: The Project Owner's standing authorization through BUG-009 signoff permits this bounded offline-only replan after Runtime 9 failed closed. It does not authorize Runtime 10, Docker execution, a new runId, restricted evidence access or manual cleanup.
- observed_boundary: Use only Runtime 9's already recorded redacted result: controlled health, exact parent/listener, `FULL_ELIGIBLE`, one TERM and dispatch safe; outer `EXIT_2`; no accepted receipt; private absent; root residue `1`; Docker residue `2/1/1`.
- diagnosis:
  - delegated child fail-closed cleanup retains its reservation by design; the parent's receipt adoption called `assert_current_port_reservation_absent()`, whose `die()` escaped the signal handler and overwrote terminal `EXIT_128` with `EXIT_2`;
  - after final ownership proof and before TERM syscall commit, an exact owned child may exit naturally; the old path failed immediately on the syscall result even when the exact recorded PID could be independently proven dead.
- correction_contract:
  - keep strict registry and reservation-absence validation, reject any receipt while the reservation remains, and never release or fabricate cleanup success in the adoption path;
  - contain the reservation assertion's fatal control flow so a delegated cleanup rejection cannot replace the outer signal exit contract;
  - accept a TERM commit race only after the exact recorded PID is independently proven dead; live, inaccessible or substituted processes remain fail closed;
  - prove the real four-service child cleanup, receipt and reservation finalization path in an isolated offline seam, with Docker and manifest operations stubbed only at the external boundary.
- required_validation: regression-first focused tests, four-service lifecycle seam, complete synthetic TypeScript and Python suites, typecheck, shell/Python syntax, `git diff --check`, scoped high-confidence secret scan, exact test-owned Java residue check, then independent code/security, test/runtime-readiness and canonical/docs reviews.
- stop_rule: Offline PASS alone grants no runtime. Any weakening of ownership, reservation, receipt, one-TERM, residue or evidence boundaries requires replan. Runtime 9 remains permanently consumed and prohibited from inspection or cleanup.

## Runtime 9 Post-TERM Offline Implementation Result

- implementation_summary:
  - `assert_cleaned_cleanup_receipt()` now evaluates the complete composite adoption proof in one controlled subshell: expected stage validation, shared-lock acquisition, strict registry and reservation absence, receipt file validation and fixed-schema parsing. The shared lock covers the whole proof; any internal `die()` becomes a normal rejection and cannot replace the parent signal handler's terminal exit.
  - `stop_owned_child()` now handles only the narrow TERM commit race where the exact recorded PID is independently proven dead after the syscall failure. It removes only that exact metadata and sends no KILL; every unproven state remains fail closed.
  - the four-service seam uses a real isolated reservation, four test-owned `setsid` services and the real `start_child`/`stop_owned_child` plus receipt/reservation finalization path. It stubs profile loading with fixed non-secret test values and stubs Docker ownership/down/residue plus manifest writing at the external boundary. It proves one outer TERM, four service TERM observations, `CLEANED/SIGNAL + HOLD_SIGNAL_RECEIVED`, reservation absence and zero non-receipt root residue.
- regression_first:
  - `keeps the outer signal exit at 128 when child cleanup fails closed with its reservation retained` failed with actual `2` before the correction and passes afterward;
  - `accepts a TERM commit race only after the exact owned child PID is proven dead` failed closed with retained metadata before the correction and passes afterward with one TERM attempt, one dead proof, zero KILL and metadata removal.
  - independent code/security review found shared-lock acquisition remained outside the controlled adoption boundary. `keeps the signal exit at 128 when receipt-adoption lock or registry setup fails closed` then failed with actual `2`; after the complete adoption boundary correction it passes with `128`.
- tests_and_results:
  - focused lock/registry regression: `pnpm --dir business-agent-module/integration-tests exec vitest run --config vitest.synthetic.config.ts tests/05-synthetic-upstream-bootstrap-safety.test.ts -t "keeps the signal exit at 128 when receipt-adoption lock or registry setup fails closed"` — pre-fix FAIL, expected `128`, actual `2`; post-fix PASS, `1 passed / 87 skipped`;
  - targeted synthetic harness safety: `pnpm --dir business-agent-module/integration-tests exec vitest run --config vitest.synthetic.config.ts tests/05-synthetic-upstream-bootstrap-safety.test.ts` — PASS, `88/88`;
  - complete synthetic TypeScript: `pnpm --dir business-agent-module/integration-tests run test:synthetic` — PASS, `112 passed / 1 skipped`;
  - complete Python supervisor — PASS, `97/97`;
  - `PYTHONDONTWRITEBYTECODE=1 python3 -W error::ResourceWarning -m unittest tools.navigator-upstream.fixtures.synthetic-integration.test_forced_signal_supervisor -v` — PASS, `97/97`;
  - `pnpm --dir business-agent-module/integration-tests run typecheck` — PASS;
  - `bash -n tools/navigator-upstream/scripts/synthetic-upstream-harness.sh tools/navigator-upstream/scripts/synthetic-upstream-bootstrap.sh tools/navigator-upstream/scripts/synthetic-upstream-runtime-audit.sh` — PASS;
  - `PYTHONPYCACHEPREFIX=temp/test-artifacts/BUG-009-runtime9-postterm-pyc python3 -m py_compile tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py` — PASS;
  - `git diff --check` — PASS;
  - scoped high-confidence secret scan — PASS, `0 matches`;
  - exact test-owned production-like Java residue check — PASS, `0`.
- durable_record: `../test-records/BUG-009-int001-runtime9-postterm-cleanup-offline-2026-07-22.md`
- deviations: none from the offline-only replan. No runtime, Docker stack, shared service, real upstream, credential, Worker/Gateway/Pool/identity/Codex route, external exposure or production target was accessed.
- review_status: `PASS`. Independent code/security, test/runtime-readiness and canonical/docs reviews found no blocking issue after the historical Runtime 6 authorization wording was made explicitly consumed. Each review permits only the distinct Runtime 10 exact-freeze review and does not satisfy AC-2/AC-3.
- current_status: `REJECTED`; Runtime 4/5/6/7/8/9 remain consumed fail-closed and Runtime 10 remains consumed successfully, but independent BUG-level signoff found that the implemented success gate can miss a surviving run-owned process-group descendant. AC-1/AC-3 remain unsatisfied and no runtime is authorized.

## Runtime 10 Exact Authorization Freeze

- freeze_status: `CONSUMED_SUCCESS`. Independent code/security, runtime-safety and canonical/docs exact-freeze reviews all passed; the exact command executed once, exited `0`, and can never be retried or replaced.
- authorization_basis: The Project Owner's standing authorization through BUG-009 signoff permits this bounded disposable execution after all Runtime 9 post-TERM offline reviews and all Runtime 10 exact-freeze reviews passed. It permits only the exact command and runId below.
- exact_run_id: `int001-bug009-20260722-r10-9047a550`
- exact_command: `PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py --run-id int001-bug009-20260722-r10-9047a550`
- preflight:
  - strict runId passed;
  - canonical artifact root is current-UID, non-symlink and `0700`;
  - exact run directory, sibling projection and exact reservation path are absent;
  - strict reservation registry validation proves the exact reservation absent;
  - fixed local Docker Unix socket is safe;
  - exact-run Docker container/network/volume residue is `0/0/0`;
  - exact test-owned production-like Java residue is `0`.
- one_shot_rule: Execute the exact command once. Authorization is consumed when the command starts, regardless of exit or evidence shape. Never retry this runId, substitute another runId, manually clean up, or inspect `private/children/log/profile/payload/process/Docker` details.
- success_gate: no supervisor interruption; controlled health; exact parent `commandLine+cwd+runId+uid+session+startTicks`; exact listener `uid+java+argv+cwd+ancestor+socket+startTicks` with `EXACT_CANDIDATE_FOUND`; `listenerProofStageDiagnostic=FULL_ELIGIBLE`; `listenerProofEverEligible=true`; exactly one TERM; `dispatchSafe=true`; normal exact `EXIT_128`; schema-v4 redacted `CLEANED/SIGNAL + HOLD_SIGNAL_RECEIVED + HEALTH_READY + NOT_APPLICABLE`; private absent; root non-receipt residue `0`; exact reservation absent; Docker `0/0/0`.
- evidence_boundary: Only fixed-enum/redacted stdout, the fixed-schema sibling projection, root receipt fixed fields, private-absent boolean, root residue count, exact-reservation-absent result and redacted Docker counts may be used. Do not access shared `8112`, real TMS/SIM, credentials, Workers, Gateway, Pool, identities, Codex routes, or production configuration. Child-only `NAVIGATOR_EXTERNAL_ENABLED=true` remains a disposable loopback Open API route gate and `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false` remains mandatory.
- failure_rule: Any missing success-gate item is `CONSUMED_FAIL_CLOSED`; record only the allowed redacted evidence, keep AC-2/AC-3 open and return to bounded offline diagnosis. Failure grants no retry or replacement runId.
- exact_freeze_review_status: `PASS`; independent code/security, runtime-safety and canonical/docs reviews found no blocker. This review permits only the exact one-shot execution and does not satisfy AC-2/AC-3.
- execution_result:
  - exact command executed once and exited `0`;
  - supervisor interruption `NONE`, controlled health true, exact parent/listener proof, `EXACT_CANDIDATE_FOUND`, `FULL_ELIGIBLE`, ever eligible true, exactly one TERM, `dispatchSafe=true`, exact outer `EXIT_128`;
  - schema-v4 redacted receipt `CLEANED/SIGNAL + HOLD_SIGNAL_RECEIVED + HEALTH_READY + NOT_APPLICABLE`, mode `0600`;
  - private absent, root non-receipt residue `0`, exact reservation absent, Docker `0/0/0`;
  - fixed-schema projection completed as `SUCCESS_GATE_MET`.
- durable_record: `../test-records/BUG-009-int001-runtime10-success-2026-07-22.md`
- completion: Runtime 10 satisfies the recorded AC-2 redacted forced-SIGNAL result, but independent signoff found that its success gate does not prove AC-3 process-resource absence. BUG-009 is `REJECTED`; no current runtime authority remains.

## Approved Offline Replan: Port Reservation / Historical Private Isolation

- approval: On 2026-07-22 the Project Owner authorized continued bounded BUG-009 replans through signoff. This section freezes the resulting offline-only repair after two independent read-only reviews blocked Runtime 4 before execution.
- pre_fix_behavior: `prepare` allocated or validated each port by globbing every other run's `private/stack.env` and parsing that credential-bearing carrier. A fresh run therefore crossed the explicit historical-run evidence boundary before it created its own directory.
- target: Replace cross-run private-carrier inspection with a root-level, non-secret, strict port-reservation contract while preserving unique prepared-run ports, loopback bind checks, the prepare lock, Compose/startup collision checks and all existing fail-closed ownership gates.
- in_scope:
  - Add one fixed reservation namespace directly under the validated `temp/test-artifacts/INT-001/` root. It may contain only current-user-owned, `0600`, single-link, non-symlink regular reservation files with a fixed schema of version, runId and the six validated TCP ports.
  - Under the existing artifact-root prepare lock, validate every reservation entry, reject malformed/unknown/unsafe/stale-ambiguous entries fail closed, allocate or validate six unique ports, and atomically establish the current run's reservation without reading any run directory or private carrier.
  - Keep the reservation for a successfully prepared run; release only the exact current-run reservation after verified successful cleanup. A normal pre-service prepare failure may release only the exact reservation created by that invocation. Cleanup failure or uncertain ownership must retain it conservatively.
  - Preserve compatibility with legacy prepared runs that have no reservation: do not inspect or migrate their private carrier. Live bind and actual Docker/Compose startup checks remain the fail-closed collision boundary for such runs.
- non_goals:
  - No historical/current run `private/children/log/profile/payload/process/Docker` read, migration or cleanup; no runtime, runId replacement, manual cleanup or shared service access.
  - At the time of this historical port-reservation slice, there was no weakening of reserved-port, loopback bind, Compose label, process ownership, exact argv/cwd/exe/lineage/startTicks, socket/FD, the then-global holder gate, A/B/final, signal-mask or at-most-one-TERM gates. The later approved Host-Namespace Socket-Holder amendment above supersedes only that historical global-holder shape.
  - No Navigator API, permission, credential, Worker, WorkerHost, BizWorkerIdentity, WorkerPool, Codex route, external/Gateway, TMS/SIM or production change.
- acceptance:
  - A regression proves fresh allocation never opens or parses another run's private carrier, including when such a carrier exists.
  - Sequential and concurrent prepares cannot reserve an overlapping port; explicit and dynamic ports use the same registry and bind checks.
  - Unknown filenames, malformed/duplicate/extra fields, wrong runId/filename, reserved/out-of-range/duplicate ports, symlink, hardlink and unsafe reservation directory/file shape fail closed without private-carrier fallback.
  - A pre-service prepare failure releases only its exact safe reservation; successful prepare retains it; verified successful cleanup releases it; cleanup failure or uncertain ownership does not silently release it.
  - Legacy prepared runs without reservations remain readable only through their normal current-run lifecycle path; the allocator never opens them, and a real bind/start collision still fails closed.
- required_validation:
  - Add the failing regression first, then the smallest harness/test correction.
  - Run the complete Python supervisor suite, synthetic TypeScript suite, typecheck, three shell syntax checks, Python compile, `git diff --check` and scoped high-confidence secret scan.
  - Obtain independent read-only security, test-matrix and canonical-contract reviews. At this historical pre-execution point, any blocking finding kept Runtime 4 suspended.
- historical_stop_rule: If this required reading/migrating historical private carriers, auto-reclaiming an ambiguous reservation, weakening a collision/ownership gate, or executing a runtime, the slice had to stop and replan. At that historical pre-execution point, offline success alone did not execute Runtime 4 and could only support an explicit restoration decision for its then-unconsumed exact command. Runtime 4 has since executed and is `CONSUMED_FAIL_CLOSED`; this section grants no current or restorable runtime authority.

## Port Reservation / Historical Private Isolation Implementation Result

- implementation_summary:
  - Fresh port allocation no longer globs, opens or parses another run's `private/stack.env`. Port coordination now uses only `.port-reservations/<runId>.ports` below the validated artifact root.
  - The reservation directory and files are current-UID, non-symlink, private objects. Each file is a `0600`, single-link regular file with exactly the schema version, exact runId and six validated unique TCP ports. Unknown entries and unsafe, malformed, duplicate, reserved, out-of-range or colliding values fail closed.
  - Prepare serializes registry establishment under the artifact-root lock; doctor/run validate the current reservation under a shared registry lock; cleanup upgrades to exclusive finalization. Failed prepare releases only a reservation created by that invocation, successful prepare retains it, and cleanup failure or uncertain ownership retains it.
  - Successful cleanup stages and atomically publishes the root receipt before releasing the exact reservation. A normally observable publication or release failure best-effort removes or replaces a misleading success receipt, records fail-closed cleanup and retains the reservation. An uncatchable crash between publication and release, or a failed compensation removal, may leave a success-shaped receipt, but the composite adopter rejects it because the exact reservation remains or the registry is unsafe. Legacy runs without a reservation may use only ownership-checked cleanup and are never migrated or used as allocation input.
  - Harness and supervisor receipt adoption both serialize against the reservation registry and require its strict validation plus absence of this exact run's reservation. Receipt validity and reservation absence are one composite success invariant; neither may be accepted independently.
  - The supervisor's FD-based registry reader now preserves the Bash authoritative parser's LF-only record-boundary rule rather than using Python `splitlines()`. It rejects CRLF, bare CR, NUL, Unicode/control separators, embedded/extra records, oversize files and incomplete/size-mismatched reads; bounded looped reads must reach EOF before a reservation can participate in receipt adoption.
- changed_paths:
  - `tools/navigator-upstream/scripts/synthetic-upstream-harness.sh`
  - `tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py`
  - `tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py`
  - `business-agent-module/integration-tests/tests/05-synthetic-upstream-bootstrap-safety.test.ts`
  - `docs/version-tracker/1.4.3-SNAPSHOT/runbooks/INT-001-synthetic-upstream-integration-harness.md`
  - `docs/version-tracker/1.4.3-SNAPSHOT/test-records/BUG-009-int001-port-reservation-isolation-offline-2026-07-22.md`
  - this canonical work item and the version index
- tests_and_results:
  - Targeted synthetic harness safety suite — PASS, `84/84`.
  - Complete Python supervisor suite — PASS, `82/82`, including canonical LF/short-read acceptance and fail-closed CRLF, bare CR, Unicode/control separator, NUL, embedded LF, trailing-content and oversize cases.
  - Complete synthetic TypeScript suite — PASS, `108 passed / 1 skipped`.
  - TypeScript typecheck, three shell syntax checks, Python compile, `git diff --check` and scoped high-confidence secret scan — PASS; secret scan `0 matches`.
- deviations: none from the approved offline-only scope. At the time this historical port-reservation slice completed, Runtime 4 had not yet executed or been consumed, and no historical/current run private carrier, child artifact, log, profile, payload, process/Docker detail, shared service, real upstream, credential, Worker/Gateway/Pool/identity/Codex route, external or production target was accessed. Runtime 4 has since executed once and is `CONSUMED_FAIL_CLOSED` under its later dedicated section.
- residual_risks:
  - Reservation validation and removal remain path-based and the advisory locks coordinate cooperating processes only. A hostile same-UID actor could race pathname replacement; the current local disposable harness threat model trusts the single same-UID operator. Hardening to directory-FD plus `openat(O_NOFOLLOW)`/`fstat`/`unlinkat` is deferred because it materially expands this harness-only slice.
  - Legacy prepared runs without reservations cannot contribute their allocated ports to the registry. Live loopback bind and Docker/Compose startup collision checks remain the conservative compatibility boundary; ambiguous legacy state is never auto-reclaimed.
  - Registry and receipt commits do not add directory/file `fsync`; sudden host power loss may lose the latest rename or unlink. This non-blocking durability risk is accepted for the disposable local harness and does not weaken fail-closed process/runtime behavior.
- durable_record: `../test-records/BUG-009-int001-port-reservation-isolation-offline-2026-07-22.md`
- review_status: PASS. Independent security, test-matrix and canonical consistency reviews all reported no blocking finding after the LF-only/full-read correction.
- runtime_authorization: At the completion of this historical port-reservation slice, Runtime 4 was `CONSUMED_FAIL_CLOSED` and no replacement runId was authorized by this slice. The later Runtime 8 authorization is governed only by its dedicated exact-freeze section above.

## Historical Continuation Authorization

Historical record only; all referenced retry authority and Runtime 4/5/6/7/8 authorizations are consumed, and this paragraph grants no current runtime permission. On 2026-07-21, after the recorded fail-closed rehearsal, the Project Owner explicitly authorized continued isolated diagnosis and fresh retries. That authorization remained inside the existing harness-only boundary: every retry used a new loopback-only disposable run, preserved strict ownership proof and redacted root-level evidence, and did not read, enumerate, retry, clean, or otherwise touch historical failed runs. It did not authorize access to real TMS/SIM, shared `8112`, profiles or credentials, Workers, Gateway, Pool, identity, Codex routing, external enablement, or production configuration. The fixed-enum `p7m3c6r2` runtime limit and later candidate-first `c4n8v2k6` runtime limit are both exhausted.

## Execution Stop and Replan Trigger

- The authorized follow-up runs `r6a1p9k4` and `v2m8q4z7` were each fresh, loopback-only, and evidence-limited. They did not produce the required redacted completion evidence; their permitted root snapshots showed no receipt, retained `private/`, and seven root non-receipt entries.
- Do not create a blind retry, reuse a historical runId or inspect any failed run beyond its already recorded allowed evidence. Runtime 4 exact runId `int001-bug009-20260722-484c6216` is `CONSUMED_FAIL_CLOSED`; its historical next step stopped at offline diagnosis without retry, replacement or manual cleanup. Runtime 8 exists only under its later dedicated exact freeze.
- The approved projection plan was implemented and the single permitted run `p7m3c6r2` completed with usable redacted evidence. It sent zero TERM because strict listener proof remained `socket-listener-absent`; the child later produced `CLEANED/UNKNOWN` with `HOLD_TIMEOUT`. That historical limit remains exhausted and is not reusable.
- The approved diagnostic runtime `99c88f80` completed with usable redacted evidence and identified `NO_EXACT_ARGV_MATCH`. It sent zero TERM and returned `CLEANED/UNKNOWN` with `HEALTH_READY + HOLD_TIMEOUT`. Its authorization is exhausted; do not retry or replace it. At that historical point, correction of the expected/runtime Launcher argv contract required a new offline replan before another runtime could be considered.

## Blocking Effect

- INT-001: remains independently rejected pending a separate re-signoff. Runtime 10 supplies `CLEANED/SIGNAL`; BUG-010 now closes the identified host process-group false-clean implementation gap offline, but this implementation session cannot accept INT-001.
- BUG-008: independently signed off as `accepted-with-risks` on its normal owner-context proof. That risk-bounded decision explicitly excludes, does not accept, and does not remediate INT-001's rejected forced-SIGNAL cleanup or BUG-009.
- BUG-009: `READY_FOR_SIGNOFF`; Runtime 4–9 remain consumed fail-closed and Runtime 10 remains consumed successfully. BUG-010 adds exact PGID-absence proof before metadata removal, durable leader-dead/live-descendant fail-closed regression, and complete offline validation. No runtime is authorized or required for this re-signoff.

## BUG-010 Process-Group-Empty Repair Incorporation

- repair_work_item: `BUG-010-int001-process-group-empty-proof.md`
- pre_fix_regression: The focused test failed as required with exit `72`, `TERM=1`, `KILL=0`, `groupProbes=0`, and metadata absent, proving the rejected false-clean path.
- implementation: Cleanup now removes metadata only after the exact leader is proven dead and kernel signal-0 returns `ESRCH` for the exact recorded PGID. A surviving/unprovable group retains metadata and fails cleanup. KILL escalation remains possible only while the exact leader is still ownership-proven.
- real_process_evidence: A test-owned `setsid` leader exits on TERM while a same-PGID descendant ignores TERM. The real harness path returns failure, retains metadata, sends no later broad signal, and the fixture performs bounded exact-PID teardown.
- validation: targeted safety `92/92`; synthetic `116 passed / 1 skipped`; Python supervisor `97/97`; typecheck, shell syntax, Python compile, diff check and scoped secret scan passed; secret scan `0`.
- durable_record: `../test-records/BUG-010-int001-process-group-empty-offline-2026-07-22.md`
- runtime_boundary: No Runtime 10 retry/replacement or new runtime occurred. Current runtime authorization remains `none`.

## Acceptance Status

- acceptance_status: ready_for_resignoff
- acceptance_decision: pending_independent_resignoff
- previous_signed_off_by: Independent Signoff Reviewer (Codex)
- previous_signed_off_at: 2026-07-22
- previous_acceptance_record: `docs/version-tracker/1.4.3-SNAPSHOT/evidence/BUG-009-independent-signoff-2026-07-22.md`
- previous_blocking_items: `BUG-009 AC-1/AC-3 process-group residue proof`
- current_blocking_items: none claimed by implementation; independent re-signoff required
- follow_up_required: independent BUG-009 re-signoff; no runtime

## References

- snapshot: `../evidence/INT-001-BUG-008-signoff-snapshot-2026-07-21.md`
- harness delivery spec: `INT-001-synthetic-upstream-integration-harness.md`
- owner-context delivery spec: `BUG-008-openapi-upstream-physical-langgraph-identity-context.md`
