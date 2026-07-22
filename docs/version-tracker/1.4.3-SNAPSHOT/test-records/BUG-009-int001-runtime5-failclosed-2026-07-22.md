---
record_type: runtime-test-record
version: 1.4.3-SNAPSHOT
ticket: BUG-009
date: 2026-07-22
run_id: int001-bug009-20260722-r5-9f3c7a2d
result: CONSUMED_FAIL_CLOSED
---

# BUG-009 Runtime 5 Fail-Closed Record

## Exact Execution

```bash
PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py --run-id int001-bug009-20260722-r5-9f3c7a2d
```

The command executed exactly once and exited `1`. Its authorization is permanently consumed; the runId must not be retried or replaced under that authorization.

## Allowed Redacted Result

- `controlledHealthPrecondition=false`
- `parentProof=commandLine+cwd+runId+uid+session+startTicks`
- `listenerProof=listener-candidate-absent`
- `listenerIdentityDiagnostic=NO_TRUSTED_JAVA_CANDIDATE`
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

## Conclusion and Boundary

Runtime 5 failed closed before controlled health or TERM authorization because the complete descendant-domain scan produced no trusted-Java candidate. BUG-009 AC-2/AC-3 remain open. No retry, replacement runId or manual cleanup is allowed.

No run `private/`, `children/`, log, profile, payload, process or Docker-object detail was read. No shared `8112`, real TMS/SIM, profile/credential, Worker, Gateway, Pool, identity, Codex route, external exposure or production target was accessed. `NAVIGATOR_EXTERNAL_ENABLED=true` remained only the disposable loopback Open API route gate, and `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false` remained required.

The only next action is static source/test diagnosis of `NO_TRUSTED_JAVA_CANDIDATE`. Any future runtime requires a bounded offline replan, regression-first correction, complete gates, independent reviews and a new exact runId frozen consistently in the canonical work item, version index and runbook.
