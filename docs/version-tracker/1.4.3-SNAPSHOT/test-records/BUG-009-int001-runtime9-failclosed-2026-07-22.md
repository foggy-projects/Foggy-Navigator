---
record_type: runtime-test-record
version: 1.4.3-SNAPSHOT
ticket: BUG-009
date: 2026-07-22
run_id: int001-bug009-20260722-r9-33154d77
result: CONSUMED_FAIL_CLOSED
---

# BUG-009 Runtime 9 Fail-Closed Record

## Exact Execution

```bash
PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py --run-id int001-bug009-20260722-r9-33154d77
```

The command executed exactly once and exited `1`. Its authorization is permanently consumed; the runId must not be retried or replaced under that authorization.

## Allowed Redacted Result

- `controlledHealthPrecondition=true`
- `parentProof=commandLine+cwd+runId+uid+session+startTicks`
- `listenerProof=uid+java+argv+cwd+ancestor+socket+startTicks`
- `listenerIdentityDiagnostic=EXACT_CANDIDATE_FOUND`
- `listenerProofStageDiagnostic=FULL_ELIGIBLE`
- `listenerProofEverEligible=true`
- `termDispatches=1`
- `dispatchSafe=true`
- `supervisorInterruption=NONE`
- `exerciseExit=EXIT_2`
- receipt: absent/unaccepted
- `privateAbsent=true`
- root non-receipt residue `1`
- Docker container/network/volume residue `2/1/1`

## Success Gate Mapping

| Gate | Observed | Result |
|---|---|---|
| no supervisor interruption | `NONE` | PASS |
| controlled health | `true` | PASS |
| exact parent proof | exact fixed proof enum | PASS |
| exact listener proof | exact fixed proof enum | PASS |
| exact temporal listener stage | `FULL_ELIGIBLE` | PASS |
| listener ever eligible | `true` | PASS |
| exactly one safe TERM | `1`, dispatch safe | PASS |
| exact normal outer exit | `EXIT_2`, expected `EXIT_128` | FAIL |
| forced-SIGNAL receipt | absent/unaccepted | FAIL |
| private/root/Docker hygiene | absent / `1` / `2/1/1` | FAIL |

## Conclusion and Boundary

Runtime 9 proves that the mapped-loopback correction can reach the complete listener/health/dispatch gate in a real disposable runtime. It does not prove successful forced-SIGNAL cleanup: after one safe TERM, the outer exercise exited `2`, no acceptable root receipt was adopted, and run-owned root/Docker residue remained. AC-2 and AC-3 remain open.

No run `private/`, `children/`, log, profile, payload, process or Docker-object detail was read. No manual cleanup, retry or replacement runId is authorized. No shared `8112`, real TMS/SIM, profile/credential, Worker, Gateway, Pool, identity, Codex route, external exposure or production target was accessed. `NAVIGATOR_EXTERNAL_ENABLED=true` remained only the disposable loopback Open API route gate, and `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false` remained required.

The only next action authorized by this result is bounded static source/test diagnosis and regression-first offline repair. Any future runtime requires a new reviewed replan, complete offline gates and a distinct exact one-shot freeze.
