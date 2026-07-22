---
doc_type: test-record
version: 1.4.3-SNAPSHOT
ticket: BUG-009
date: 2026-07-21
result: fail-closed
run_id: int001-bug009-20260721-99c88f80
---

# BUG-009 fixed-enum forced-SIGNAL rehearsal

## Boundary and authorization

- This was the one loopback-only disposable rehearsal authorized after the fixed-enum identity diagnostic and all offline gates passed.
- It did not use real TMS/SIM, shared `8112`, real profiles/credentials, existing Workers, Worker Gateway external or production configuration.
- Only the fixed-enum supervisor summary, fixed-schema projection, root receipt and permitted redacted residue counts were read. No historical run or any `private/children/log/profile/payload/process/Docker` detail was inspected, and no manual cleanup was performed.

## Command and result

```bash
PYTHONDONTWRITEBYTECODE=1 python3 \
  tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py \
  --run-id int001-bug009-20260721-99c88f80
```

- exit: `1`
- retry: none; this exact runId authorization is consumed and cannot be replaced.

## Allowed evidence

- projection:
  - `phase=COMPLETE`
  - `outcome=CHILD_EXITED_BEFORE_HEALTH`
  - `receiptState=VALID`
  - `rootSnapshotState=COMPLETE`
  - `stdoutSummaryState=EMITTED`
  - mode/owner/links: `0600 / 1001 / 1`
  - SHA-256: `f3a1e863b1ca23d6652cde6502804928a18f5dbded139817edc14f91294f75a1`
- redacted supervisor summary:
  - `listenerIdentityDiagnostic=NO_EXACT_ARGV_MATCH`
  - `listenerProof=listener-candidate-absent`
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
  - SHA-256: `6f7933e5f9dc8227ac745ac708174188a8311f8371047ec3fca23c38eeb4d953`

## Conclusion

- The fixed enum worked as intended and narrowed candidate rejection to the exact Launcher argv predicate without exposing process values.
- No parent TERM was authorized or sent, so this run cannot satisfy AC-2 or AC-3. `CLEANED/UNKNOWN` is cleanup evidence only, not forced-SIGNAL success.
- BUG-009 returns to `NEEDS_REPLAN`. The next permitted work is an offline contract comparison between the authoritative runtime Launcher argv and the supervisor expectation; no new runtime is authorized.
