---
record_type: runtime-test-record
version: 1.4.3-SNAPSHOT
ticket: BUG-009
date: 2026-07-22
run_id: int001-bug009-20260722-r7-6d3f8a1c
result: CONSUMED_FAIL_CLOSED
---

# BUG-009 Runtime 7 Fail-Closed Record

## Exact Execution

```bash
PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py --run-id int001-bug009-20260722-r7-6d3f8a1c
```

The command executed exactly once and exited `1`. Its authorization is permanently consumed; the runId must not be retried or replaced under that authorization.

## Allowed Redacted Result

- `controlledHealthPrecondition=false`
- `parentProof=commandLine+cwd+runId+uid+session+startTicks`
- `listenerProof=listener-candidate-absent`
- `listenerIdentityDiagnostic=EXACT_CANDIDATE_FOUND`
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
| exact listener and ever eligible | temporal identity exact; terminal candidate absent; ever eligible false | FAIL |
| exactly one safe TERM | `0`, dispatch unsafe | FAIL |
| exact normal outer exit | `EXIT_2`, expected `EXIT_128` | FAIL |
| forced-SIGNAL receipt | `CLEANED/UNKNOWN + HOLD_TIMEOUT`, expected `CLEANED/SIGNAL + HOLD_SIGNAL_RECEIVED` | FAIL |
| private/root/Docker hygiene | absent / `0` / `0/0/0` | PASS for timeout cleanup only |

`EXACT_CANDIDATE_FOUND` records only the furthest identity stage observed during supervision. It does not establish socket ownership, listener eligibility, controlled health, TERM authorization or success.

## Conclusion and Boundary

Runtime 7 failed closed after exact candidate identity was observed at least once but before any complete listener proof became eligible. The terminal `listener-candidate-absent` may reflect cleanup-time state and cannot identify the earlier post-identity blocker. AC-2/AC-3 remain open and BUG-009 is not signoff-eligible.

No run `private/`, `children/`, log, profile, payload, process or Docker-object detail was read. No shared `8112`, real TMS/SIM, profile/credential, Worker, Gateway, Pool, identity, Codex route, external exposure or production target was accessed. `NAVIGATOR_EXTERNAL_ENABLED=true` remained only the disposable loopback Open API route gate, and `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false` remained required.

The only next action authorized by this runtime result was bounded offline diagnosis and regression for a fixed-enum temporal listener-proof stage. Any future runtime requires regression-first correction, complete offline gates, independent reviews and a new exact runId frozen consistently in the canonical work item, version index and runbook.
