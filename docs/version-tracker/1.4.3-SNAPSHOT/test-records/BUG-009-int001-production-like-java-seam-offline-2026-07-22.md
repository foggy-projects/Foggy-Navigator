---
record_type: offline-test-record
version: 1.4.3-SNAPSHOT
ticket: BUG-009
date: 2026-07-22
result: PASS_WITH_RUNTIME_PENDING
runtime_authorization: none
---

# BUG-009 Production-Like Java Full-Supervisor Seam Offline Record

## Result

The production-like offline seam now completes the real forced-SIGNAL supervisor path. A test-owned Java 17 JAR exposes only a minimal loopback `/actuator/health` response and is launched through the real nested process chain:

`exercise_invoke_child()` -> `setsid/env -i` -> held child -> `start_child()` -> `setsid/env -i` -> Java.

Inside an isolated PID namespace the test invokes the real `supervise_exercise()` implementation. It proves controlled health, exact parent identity, complete descendant domain, exact Java/argv/cwd/lineage/socket ownership, A/B/final reproof, exactly one parent TERM, `dispatchSafe=true`, and its test-custom `EXIT_143` child result. The test patches only `expected_launcher_argv` for the test-owned JAR; it does not patch trusted Java resolution, descendant-domain collection, socket ownership, health, or signal dispatch. Because the seam uses custom TERM traps and does not invoke `main()`, its `EXIT_143` proves only the supervision path and is not eligible for real runtime completion.

This is offline repair evidence, not a real disposable Launcher rehearsal. Runtime 5 `int001-bug009-20260722-r5-9f3c7a2d` remains permanently `CONSUMED_FAIL_CLOSED`. Runtime 6 is not authorized, no new runId was created, and BUG-009 AC-2/AC-3 remain open.

## Regression First and Correction

Before the correction, the production-like seam consistently failed closed with `listener-candidate-absent` and `IDENTITY_STABILITY_MISMATCH`; five consecutive isolated executions reproduced the failure. The JVM may change its native task set while one complete `/proc/<pid>/task/*/children` snapshot is collected, so a single transient mismatch could permanently hide a later stable complete domain.

The bounded correction preserves fail-closed identity semantics:

- at most eight attempts, separated by 50 ms;
- every attempt starts with an independent complete descendant-domain/task-set snapshot, and a first-snapshot mismatch ends that attempt;
- after a successful first snapshot, success still requires a complete identical second snapshot from the same attempt;
- snapshots are never merged, unioned, or reused across attempts;
- `PROC_UNAVAILABLE` and `PROC_MALFORMED` still fail immediately without retry;
- exhaustion still returns `IDENTITY_MISMATCH` and cannot authorize a candidate or TERM.

Unit regressions cover first-snapshot mismatch delay, no cross-attempt composition, success only from one identical complete pair, exhaustion, and immediate first- or second-snapshot `PROC_*` failure.

## Commands and Results

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest \
  tools.navigator-upstream.fixtures.synthetic-integration.test_forced_signal_supervisor.ProductionLikeFullSupervisorJavaSeamTest.test_real_harness_nesting_completes_full_supervisor_one_term_path -v
# PASS: 1 test
```

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest \
  tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py
# PASS: 83 tests
```

```bash
npm --prefix business-agent-module/integration-tests run test:synthetic -- \
  --run tests/05-synthetic-upstream-bootstrap-safety.test.ts
# PASS: 85 tests
```

```bash
env -u INT001_SYNTHETIC_UPSTREAM_HARNESS \
    -u INT001_RUNTIME_PROBE \
    npm --prefix business-agent-module/integration-tests run test:synthetic
# PASS: 109 passed, 1 skipped

npm --prefix business-agent-module/integration-tests run typecheck
# PASS
```

The final shell syntax, Python compile, `git diff --check`, and scoped high-confidence secret scan are recorded in the canonical BUG-009 work item after the documentation update.

## Strict Main Completion Gate Follow-up

An independent test-readiness review found that the real `main()` predicate previously accepted any non-null child wait status and did not explicitly require the exact proof reasons, exact listener identity, `listenerProofEverEligible`, `HOLD_SIGNAL_RECEIVED`, or `HEALTH_READY`. The positive fixture consequently allowed `NOT_REHEARSAL + EXIT_0`.

Source audit confirmed that both real harness signal-cleanup layers explicitly `exit 128`; the exact runtime completion value is therefore normal `EXIT_128`, not the seam's custom `EXIT_143`. Regression-first coverage was added before implementation and initially failed because the strict completion helper/constants did not exist.

The corrected `main()` gate now requires all of the following together: no supervisor interruption; controlled health; exact parent and listener proof reasons; listener identity `EXACT_CANDIDATE_FOUND`; `listenerProofEverEligible=true`; exactly one TERM; `dispatchSafe=true`; normal `EXIT_128`; strict `CLEANED/SIGNAL + HOLD_SIGNAL_RECEIVED + HEALTH_READY + NOT_APPLICABLE`; private absence; root residue zero; reservation absence; and Docker `0/0/0`.

The negative matrix rejects wrong or missing facts including `EXIT_0`, `EXIT_1`, `EXIT_143`, signal termination, absent required receipt fields, `NOT_REHEARSAL`, non-`HEALTH_READY`, wrong proof reasons/diagnostic, and every residue plane. Missing-safe receipt comparisons make partial evidence return false instead of raising. The strict helper matrix passed `2/2`, the orchestration suite passed `29/29`, and the complete Python suite passed `85/85`.

The final offline gate also passed: synthetic TypeScript `109 passed / 1 skipped`; TypeScript typecheck; three shell syntax checks; Python compile; `git diff --check`; and a scoped high-confidence secret scan with `0 matches`. Runtime 6 was not executed, and this offline result alone does not authorize it.

## Boundary and Residual Risk

- No Runtime 6, Docker rehearsal, shared service, real TMS/SIM, credential, Worker, Gateway, Pool, identity, Codex route, external exposure, or production target was accessed.
- No historical or current run `private/`, `children/`, log, profile, payload, process, or Docker evidence was read. Runtime 5 was not retried and no manual cleanup occurred.
- The seam proves that the corrected identity-stability logic can traverse the real local supervisor and harness nesting to one safe TERM. It does not prove the complete real Launcher/Docker lifecycle, root receipt adoption, private absence, or zero run-owned runtime residue required by AC-2/AC-3.
- `NAVIGATOR_EXTERNAL_ENABLED=true` remains only the disposable loopback Open API route gate. It does not imply Provider, Worker Gateway, or production readiness. `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false` remains unchanged.
