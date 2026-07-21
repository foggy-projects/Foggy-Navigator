---
doc_type: test-record
version: 1.4.3-SNAPSHOT
workitem: BUG-009
status: BLOCKED
run_id: int001-bug009-20260721-k6f8m2q9
scope: fresh-loopback-disposable-only
recorded_at: 2026-07-21
---

# BUG-009 Fresh Forced-SIGNAL Rehearsal — `k6f8m2q9`

## Result

The one permitted fresh post-topology-correction rehearsal exited `1` fail-closed. It did not satisfy the controlled-health or strict listener proof, so it did not prove the exercise parent and sent no TERM. This is not a `CLEANED/SIGNAL` success and does not meet BUG-009 AC-2 or AC-3.

Only the supervisor's redacted stdout projection was read:

| Field | Observed value |
| --- | --- |
| `controlledHealthPrecondition` | `false` |
| `parentProof` | `NOT_ATTEMPTED` |
| `listenerProof` | `socket-listener` |
| `termDispatches` / `dispatchSafe` | `0` / `false` |
| `exerciseExit` | `EXIT_2` |
| root receipt | `CLEANED/UNKNOWN`, schema `3`, redacted |
| `privateAbsent` / root non-receipt residue | `true` / `0` |
| Docker container/network/volume residue | `0` / `0` / `0` |
| `supervisorInterruption` | `NONE` |

`socket-listener` is a fixed redacted supervisor reason, not authorization to inspect additional run artifacts or weaken listener/ownership proof.

## Focused Verification

- `PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py` — PASS, 34 tests.
- `bash -n tools/navigator-upstream/scripts/synthetic-upstream-harness.sh tools/navigator-upstream/scripts/synthetic-upstream-bootstrap.sh tools/navigator-upstream/scripts/synthetic-upstream-runtime-audit.sh` — PASS.
- `cd business-agent-module/integration-tests && env -u INT001_SYNTHETIC_UPSTREAM_HARNESS -u INT001_RUNTIME_PROBE npm run test:synthetic` — PASS, 89 passed / 1 skipped.
- `cd business-agent-module/integration-tests && npm run typecheck` — PASS.
- BUG-009 changed-surface whitespace check and high-confidence conventional-secret scan — PASS, no diagnostics/findings.

## Regression Coverage

- Outer `exercise --forced-signal-rehearsal` → setsid `run-hold` → Launcher lineage remains covered by an offline real three-process fixture.
- Exact outer and held-child NUL argv are required; wrong, missing, reordered or extra argv never become signal-forwardable.
- Normal `exercise` sequencing is unchanged; rehearsal stops after `prepare → doctor → run-hold`.
- A fixed 180-second hold timeout takes ownership-checked cleanup to `CLEANED/UNKNOWN`, never `SIGNAL`.

## 2026-07-21 Offline Receipt-v4 Follow-up

- No fresh rehearsal was created or retried for this follow-up. The historical row above remains the original redacted schema-v3 observation; schema v4 applies only to newly emitted receipts and does not rewrite or upgrade that failed evidence.
- Added offline coverage for v4 exact receipt projection and rejection of legacy v3 / invalid lifecycle values; normal signal remains `NOT_REHEARSAL`, held timeout is `HOLD_TIMEOUT`, held wait failure is `HOLD_WAIT_FAILURE`, and an owned held-child outer-TERM fixture records `HOLD_SIGNAL_RECEIVED`.
- `HOLD_SIGNAL_RECEIVED` remains diagnostic only. It does not authorize TERM, prove ownership/cleanup, or become a completion prerequisite. `listenerProofEverEligible` is a redacted supervisor-summary-only boolean; a once-eligible A→B/final listener that later fails a specific socket re-proof still dispatches no TERM.
- `PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py` — PASS, 39 tests.
- `cd business-agent-module/integration-tests && env -u INT001_SYNTHETIC_UPSTREAM_HARNESS -u INT001_RUNTIME_PROBE npm run test:synthetic` — PASS, 92 passed / 1 skipped.
- `cd business-agent-module/integration-tests && npm run typecheck` — PASS.
- `git diff --check` — PASS.

## Boundary and Follow-up

- No real TMS/SIM, shared `8112`, real profile/credential, Worker, Gateway, Pool, identity or Codex route was read, modified or used. `NAVIGATOR_EXTERNAL_ENABLED=true` remains only the disposable target's Open API route gate; Worker Gateway external remains outside this run and disabled by the harness contract.
- No historical failed run was read, enumerated, retried, cleaned or otherwise touched. After this failure, no `private/`, `children/`, log, profile, payload, process detail or Docker object for this run was inspected, and no retry or manual cleanup was performed.
- BUG-009 remains `BLOCKED` and is not eligible for independent signoff. INT-001 remains `REJECTED`; any further diagnosis or fresh rehearsal requires a newly approved plan.

## Later Follow-up Reference

This record preserves the original `k6f8m2q9` redacted schema-v3 observation. The later owner-authorized fresh runs and their root-only evidence are recorded separately in [the follow-up record](./BUG-009-int001-forced-signal-runtime-follow-up-2026-07-21.md); they neither alter this historical result nor authorize reading its private artifacts.
