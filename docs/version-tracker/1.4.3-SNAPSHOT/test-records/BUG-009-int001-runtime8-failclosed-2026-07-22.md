---
record_type: runtime-test-record
version: 1.4.3-SNAPSHOT
ticket: BUG-009
date: 2026-07-22
run_id: int001-bug009-20260722-r8-212fde1c
result: CONSUMED_FAIL_CLOSED
---

# BUG-009 Runtime 8 Fail-Closed Record

## Exact Execution

```bash
PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py --run-id int001-bug009-20260722-r8-212fde1c
```

The command executed exactly once and exited `1`. Its authorization is permanently consumed; the runId must not be retried or replaced under that authorization.

## Allowed Redacted Result

- `controlledHealthPrecondition=false`
- `parentProof=commandLine+cwd+runId+uid+session+startTicks`
- `listenerProof=listener-candidate-absent`
- `listenerIdentityDiagnostic=EXACT_CANDIDATE_FOUND`
- `listenerProofStageDiagnostic=EXACT_IDENTITY_FOUND`
- `listenerProofEverEligible=false`
- `termDispatches=0`
- `dispatchSafe=false`
- `supervisorInterruption=NONE`
- `exerciseExit=EXIT_2`
- receipt: schema v4, `result=CLEANED`, `failureStage=UNKNOWN`, `rehearsalLifecycleObservation=HOLD_TIMEOUT`, `launcherReadinessObservation=HEALTH_READY`, `launcherFailureClass=NOT_APPLICABLE`, mode `0600`, `secretsRedacted=true`
- `privateAbsent=true`
- root non-receipt residue `0`
- Docker container/network/volume residue `0/0/0`

The supervisor source requires the exact reservation to be absent under the valid shared registry lock before adopting a receipt. Reservation absence is not separately emitted in the stdout schema, so this record does not invent a separate observed field.

## Success Gate Mapping

| Gate | Observed | Result |
|---|---|---|
| no supervisor interruption | `NONE` | PASS |
| exact parent proof | exact fixed proof enum | PASS |
| controlled health after complete listener proof | `false` | FAIL |
| exact listener stage | `EXACT_IDENTITY_FOUND`, expected `FULL_ELIGIBLE` | FAIL |
| exact listener and ever eligible | terminal candidate absent; ever eligible false | FAIL |
| exactly one safe TERM | `0`, dispatch unsafe | FAIL |
| exact normal outer exit | `EXIT_2`, expected `EXIT_128` | FAIL |
| forced-SIGNAL receipt | `CLEANED/UNKNOWN + HOLD_TIMEOUT`, expected `CLEANED/SIGNAL + HOLD_SIGNAL_RECEIVED` | FAIL |
| private/root/Docker hygiene | absent / `0` / `0/0/0` | PASS for timeout cleanup only |

## Conclusion and Boundary

Runtime 8 failed closed after exact Launcher identity was observed, before any listener socket stage became eligible. The temporal stage proves only that supervision reached exact identity; it does not identify a socket-family or process-level cause and cannot authorize TERM or success. AC-2 remains open. AC-3 also remains open because it is explicitly tied to the successful AC-2 forced-SIGNAL run, even though this timeout cleanup left private/root/Docker residue at absent/0/0-0-0.

No run `private/`, `children/`, log, profile, payload, process or Docker-object detail was read. No shared `8112`, real TMS/SIM, profile/credential, Worker, Gateway, Pool, identity, Codex route, external exposure or production target was accessed. `NAVIGATOR_EXTERNAL_ENABLED=true` remained only the disposable loopback Open API route gate, and `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false` remained required.

The only next action authorized by this runtime result is bounded offline source/test diagnosis and regression. Any future runtime requires a separate reviewed replan, complete offline gates and a new exact one-shot freeze.
