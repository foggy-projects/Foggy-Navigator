# BUG-009 Runtime 10 forced-SIGNAL success

- Date: 2026-07-22
- RunId: `int001-bug009-20260722-r10-9047a550`
- Scope: fresh loopback-only disposable INT-001 runtime; no shared `8112`, real TMS/SIM, credential, Worker, Gateway, Pool, identity, Codex route or production target.
- Authorization: exact one-shot freeze passed independent code/security, runtime-safety and canonical/docs review. Authorization was consumed when the command started; retry or replacement is prohibited.

## Preflight

Immediately before execution, the read-only preflight passed:

- strict runId;
- safe canonical artifact root;
- exact run directory, projection and reservation path absent;
- strict registry proved exact reservation absent;
- fixed local Docker socket safe;
- exact-run Docker residue `0/0/0`;
- test-owned production-like Java residue `0`;
- `git diff --check` PASS.

## Exact execution

```bash
PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py --run-id int001-bug009-20260722-r10-9047a550
```

Result: exit `0`.

```json
{"controlledHealthPrecondition":true,"dispatchSafe":true,"dockerResidueCounts":{"container":0,"network":0,"volume":0},"exerciseExit":"EXIT_128","listenerIdentityDiagnostic":"EXACT_CANDIDATE_FOUND","listenerProof":"uid+java+argv+cwd+ancestor+socket+startTicks","listenerProofEverEligible":true,"listenerProofStageDiagnostic":"FULL_ELIGIBLE","parentProof":"commandLine+cwd+runId+uid+session+startTicks","privateAbsent":true,"receipt":{"failureStage":"SIGNAL","launcherFailureClass":"NOT_APPLICABLE","launcherReadinessObservation":"HEALTH_READY","mode":"0600","rehearsalLifecycleObservation":"HOLD_SIGNAL_RECEIVED","result":"CLEANED","schemaVersion":4,"secretsRedacted":true},"rootNonReceiptResidueCount":0,"runId":"int001-bug009-20260722-r10-9047a550","schemaVersion":1,"supervisorInterruption":"NONE","termDispatches":1}
```

## Allowed postflight

Only the fixed-schema projection, fixed receipt fields and aggregate absence/count checks were read. The postflight passed:

- projection `COMPLETE / SUCCESS_GATE_MET`;
- receipt state `VALID`, root snapshot state `COMPLETE`, stdout state `EMITTED`;
- receipt `CLEANED/SIGNAL + HOLD_SIGNAL_RECEIVED + HEALTH_READY + NOT_APPLICABLE`, schema v4, mode `0600`, redacted;
- private absent;
- root non-receipt residue `0`;
- exact reservation absent;
- Docker container/network/volume residue `0/0/0`.

## Conclusion

Runtime 10 satisfies BUG-009 AC-2 and AC-3 and the frozen strict success gate. It proves only the disposable local forced-SIGNAL harness lifecycle. It does not make Navigator Provider ready, Worker Gateway external, real TMS/SIM accepted or production ready. `NAVIGATOR_EXTERNAL_ENABLED=true` remained only the child loopback `/api/v1/open/**` route gate; `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false` remained mandatory.
