---
doc_type: test-record
version: 1.4.3-SNAPSHOT
ticket: BUG-009
date: 2026-07-22
result: runtime-fail-closed-needs-offline-replan
---

# BUG-009 Runtime 4 fail-closed record

## Boundary

- Exact single-use runId: `int001-bug009-20260722-484c6216`.
- No shared `8112`, real TMS/SIM, credential/profile, Worker, Gateway, Pool, identity, Codex route, external or production target was accessed or changed.
- No run `private/children/log/profile/payload/process/Docker` detail was read. No manual cleanup, retry or replacement runId was used.
- `NAVIGATOR_EXTERNAL_ENABLED=true` remained only the disposable loopback Open API route gate; `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false` remained required.

## Execution

- Command: `PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py --run-id int001-bug009-20260722-484c6216`
- Exit: `1`; the authorization is consumed and the runId must not be retried.

## Allowed redacted result

- `controlledHealthPrecondition=false`
- Parent proof: `commandLine+cwd+runId+uid+session+startTicks`
- Listener proof: `listener-candidate-absent`
- Listener identity diagnostic: `IDENTITY_STABILITY_MISMATCH`
- `listenerProofEverEligible=false`
- `termDispatches=0`; `dispatchSafe=false`; supervisor interruption `NONE`
- Exercise exit: `EXIT_2`
- Receipt: schema v4, mode `0600`, `CLEANED/UNKNOWN`, `HEALTH_READY`, `HOLD_TIMEOUT`, launcher failure class `NOT_APPLICABLE`, redacted
- `privateAbsent=true`; root non-receipt residue `0`
- Docker container/network/volume residue: `0/0/0`
- Exact reservation absent after completion: `true`

## Conclusion

- Runtime 4 failed closed before any TERM dispatch. The strict listener/identity gate correctly prevented signal authorization.
- Cleanup and reservation release completed with no retained run-owned residue, but AC-2/AC-3 remain unmet because the run did not establish an eligible listener proof, controlled health precondition, exactly one TERM or `CLEANED/SIGNAL`.
- The only permitted next step is static/offline diagnosis of the identity-stability mismatch. Any future runtime requires a new bounded replan, regression-first implementation, complete offline gate, independent reviews and a newly frozen exact runId.
