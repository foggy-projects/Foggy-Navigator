---
doc_type: test-record
version: 1.4.3-SNAPSHOT
workitem: BUG-009
status: FAILED_CLOSED
run_id: int001-bug009-20260721-p7m3c6r2
scope: fresh-loopback-disposable-only
recorded_at: 2026-07-21
---

# BUG-009 Fixed-Enum Projection Runtime Evidence

## Result

The single approved fresh rehearsal exited `1` and did not dispatch TERM. It does not satisfy BUG-009 AC-2 or AC-3.

- validated projection: `phase=COMPLETE`, `outcome=CHILD_EXITED_BEFORE_HEALTH`, `receiptState=VALID`, `rootSnapshotState=COMPLETE`, `stdoutSummaryState=EMITTED`, `secretsRedacted=true`.
- redacted supervisor summary: `controlledHealthPrecondition=false`, `listenerProof=socket-listener-absent`, `listenerProofEverEligible=false`, `parentProof=NOT_ATTEMPTED`, `termDispatches=0`, `dispatchSafe=false`, `exerciseExit=EXIT_2`.
- root receipt: schema v4, `result=CLEANED`, `failureStage=UNKNOWN`, `rehearsalLifecycleObservation=HOLD_TIMEOUT`, `launcherReadinessObservation=HEALTH_READY`, `launcherFailureClass=NOT_APPLICABLE`, `secretsRedacted=true`.
- permitted residue evidence: `privateAbsent=true`, root non-receipt residue `0`, and redacted Docker container/network/volume residue counts each `0`; the root-name snapshot contained only `cleanup-report.json`.
- projection and receipt were current-user-owned `0600` single-link regular files.

The projection solved the earlier observability gap: stdout was emitted and captured, the child completed, and a valid receipt/root snapshot existed. The remaining mismatch is bounded to the supervisor never obtaining its strict listener proof while the held child independently recorded Launcher health readiness and then timed out without parent TERM. This is a diagnostic fact, not permission to weaken listener or ownership proof.

## Offline Gates Before Runtime

- `PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py` — PASS, 46 tests.
- `bash -n tools/navigator-upstream/scripts/synthetic-upstream-harness.sh tools/navigator-upstream/scripts/synthetic-upstream-bootstrap.sh tools/navigator-upstream/scripts/synthetic-upstream-runtime-audit.sh` — PASS.
- `cd business-agent-module/integration-tests && env -u INT001_SYNTHETIC_UPSTREAM_HARNESS -u INT001_RUNTIME_PROBE npm run test:synthetic` — PASS, 93 passed / 1 skipped.
- `cd business-agent-module/integration-tests && npm run typecheck` — PASS.
- `git diff --check` — PASS.
- scoped high-confidence secret scan over the changed projection/code/docs diff — PASS, no matches.

## Boundary and Decision

- No historical failed run was read, enumerated, retried or cleaned. No `private/`, `children/`, log, profile, payload, process detail or Docker object was inspected.
- No real TMS/SIM, shared `8112`, credential, Worker, Gateway, Pool, identity or Codex route was used. `NAVIGATOR_EXTERNAL_ENABLED=true` remained only the disposable Open API route gate and `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false` remained enforced.
- The approved run limit is exhausted. Do not retry this runId or start another rehearsal under the completed projection plan.
- BUG-009 returns to `NEEDS_REPLAN`. A future plan must reconcile the strict supervisor listener proof with the held child's independent `HEALTH_READY` observation using static source and new fixed-enum evidence only; it must not weaken ownership proof or inspect prohibited artifacts.
