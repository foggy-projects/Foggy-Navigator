---
doc_type: test-record
version: 1.4.3-SNAPSHOT
ticket: BUG-009
date: 2026-07-21
result: fail-closed
run_id: int001-bug009-20260721-c4n8v2k6
---

# BUG-009 candidate-first forced-SIGNAL rehearsal

## Boundary and authorization

- This was the one rehearsal authorized after every candidate-first offline gate passed.
- The target was a fresh, loopback-only, run-owned disposable stack. It did not use real TMS/SIM, shared `8112`, real profiles/credentials, existing Workers, Worker Gateway external or production configuration.
- Only the fixed-enum projection, redacted supervisor stdout, root receipt and permitted redacted residue counts were read. No `private/`, `children/`, log, profile, payload, process detail or Docker object was inspected.

## Command

```bash
PYTHONDONTWRITEBYTECODE=1 python3 \
  tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py \
  --run-id int001-bug009-20260721-c4n8v2k6
```

- exit: `1`
- retry: none; the one-run authorization is exhausted.

## Allowed evidence

- projection:
  - `phase=COMPLETE`
  - `outcome=CHILD_EXITED_BEFORE_HEALTH`
  - `receiptState=VALID`
  - `rootSnapshotState=COMPLETE`
  - `stdoutSummaryState=EMITTED`
  - SHA-256: `8508c9c15439b1786c8c2724b81c3bfb4d2009ae7390f9d2e762a30bee9b5b4a`
- redacted supervisor summary:
  - `controlledHealthPrecondition=false`
  - `listenerProof=listener-candidate-absent`
  - `listenerProofEverEligible=false`
  - `parentProof=NOT_ATTEMPTED`
  - `termDispatches=0`
  - `dispatchSafe=false`
  - `exerciseExit=EXIT_2`
  - `supervisorInterruption=NONE`
  - `privateAbsent=true`
  - `rootNonReceiptResidueCount=0`
  - Docker container/network/volume residue counts: `0/0/0`
- root receipt:
  - `schemaVersion=4`
  - `result=CLEANED`
  - `failureStage=UNKNOWN`
  - `rehearsalLifecycleObservation=HOLD_TIMEOUT`
  - `launcherReadinessObservation=HEALTH_READY`
  - `launcherFailureClass=NOT_APPLICABLE`
  - `secretsRedacted=true`
  - SHA-256: `455029b6c70e094f6fa5e96aa465aadfab4c90671e1b1161e6bb1204f9a8fb68`

## Conclusion

- Result: fail closed. No parent TERM was authorized or sent, so `CLEANED/UNKNOWN` cannot satisfy forced-SIGNAL AC-2 or AC-3.
- The candidate-first change removed the supervisor-network-namespace socket-first dependency, but the strict exact Launcher candidate was still absent at the supervisor proof point while the held child retained its lower-authority `HEALTH_READY` observation.
- After this rehearsal, a static security review found that unreadable or malformed current-user procfs identity fields needed explicit fail-closed classification. That implementation was hardened and 61 offline Python regressions passed, including PID owner replacement, malformed lineage and a real-procfs full candidate-first proof chain. No runtime was rerun; this post-rehearsal hardening does not alter or upgrade the recorded result.
- BUG-009 returns to `NEEDS_REPLAN`. Further runtime, retry, private artifact inspection or diagnostic expansion requires a new approved plan.
