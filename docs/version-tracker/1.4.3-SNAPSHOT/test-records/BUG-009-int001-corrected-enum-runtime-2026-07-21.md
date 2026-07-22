---
doc_type: test-record
version: 1.4.3-SNAPSHOT
ticket: BUG-009
date: 2026-07-21
result: fail-closed
run_id: int001-bug009-20260721-j7r4m9t2
---

# BUG-009 corrected-enum forced-SIGNAL rehearsal

## Boundary and authorization

- This was the single loopback-only disposable rehearsal authorized after the authoritative Launcher argv offline correction. The exact runId was executed once and must not be retried or replaced under this authorization.
- It did not use real TMS/SIM, shared `8112`, real profiles/credentials, existing Workers, Worker Gateway external or production configuration.
- Only the fixed-enum supervisor summary, sibling projection, root receipt and permitted redacted residue facts were read. No historical run or `private/children/log/profile/payload/process/Docker` detail was inspected, and no manual cleanup was performed.

## Command and result

```bash
PYTHONDONTWRITEBYTECODE=1 python3 \
  tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py \
  --run-id int001-bug009-20260721-j7r4m9t2
```

- exit: `1`
- retry: none; authorization consumed.

## Allowed evidence

- projection:
  - `phase=COMPLETE`
  - `outcome=CHILD_EXITED_BEFORE_HEALTH`
  - `receiptState=VALID`
  - `rootSnapshotState=COMPLETE`
  - `stdoutSummaryState=EMITTED`
  - mode/owner/links: `0600 / 1001 / 1`
  - SHA-256: `241d792b9859c3cc3c515d8171679bbbd294a05175e612a4bff917ec5b3c17d5`
- redacted supervisor summary:
  - `listenerIdentityDiagnostic=PROC_UNAVAILABLE`
  - `listenerProof=listener-candidate-proc-unavailable`
  - `listenerProofEverEligible=false`
  - `controlledHealthPrecondition=false`
  - `parentProof=NOT_ATTEMPTED`
  - `termDispatches=0`
  - `dispatchSafe=false`
  - `exerciseExit=EXIT_2`
  - `supervisorInterruption=NONE`
  - `privateAbsent=true`
  - root non-receipt residue: `0`
  - Docker container/network/volume residue: `0/0/0`
- root receipt:
  - `schemaVersion=4`
  - `result=CLEANED`
  - `failureStage=UNKNOWN`
  - `rehearsalLifecycleObservation=HOLD_TIMEOUT`
  - `launcherReadinessObservation=HEALTH_READY`
  - `launcherFailureClass=NOT_APPLICABLE`
  - `secretsRedacted=true`
  - mode/owner/links: `0600 / 1001 / 1`
  - SHA-256: `c34fb1e15f9915941d9e1c29f1d10d0d73b47263ca83153c5a6978885b69fc1f`

## Conclusion

- The corrected enum no longer misclassified this run as an argv mismatch. Candidate enumeration failed closed at `PROC_UNAVAILABLE`; the fixed enum intentionally exposes no PID, path, argv, process count or failing field.
- No parent TERM was authorized or sent. `CLEANED/UNKNOWN` confirms owned cleanup only and cannot satisfy AC-2 or AC-3.
- BUG-009 remains not signoff-eligible. Any next work requires a separately approved offline replan; this run must not be inspected, retried or manually cleaned.
