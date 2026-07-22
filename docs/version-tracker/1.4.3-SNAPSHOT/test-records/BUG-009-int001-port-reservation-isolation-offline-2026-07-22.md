---
doc_type: test-record
version: 1.4.3-SNAPSHOT
ticket: BUG-009
date: 2026-07-22
result: offline-pass-independent-review-pending
---

# BUG-009 port reservation / historical private isolation offline verification

## Boundary

- This record covers only the approved offline port-reservation isolation implementation and verification. Runtime 4 `int001-bug009-20260722-484c6216` remained `SUSPENDED_BEFORE_EXECUTION`, unexecuted and unconsumed while these checks ran.
- No historical or current run `private/children/log/profile/payload/process/Docker` detail was read. No shared `8112`, real TMS/SIM, credential/profile, Worker, Gateway, Pool, identity, Codex route, external or production target was accessed or changed.
- `NAVIGATOR_EXTERNAL_ENABLED=true` remains only a disposable loopback Open API route gate. `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false` remains mandatory. Neither is Provider, Gateway external or production readiness evidence.

## Implemented contract

- Port coordination uses only the non-secret `.port-reservations/<runId>.ports` namespace below the validated INT-001 artifact root; fresh allocation does not inspect another run's private carrier.
- Reservation directory/files require current UID, non-symlink private shape; files additionally require `0600`, regular, single-link shape and the exact fixed eight-line schema containing version, runId and six unique validated ports.
- Unknown, malformed, duplicate, extra, mismatched, reserved, out-of-range, colliding, symlinked or hard-linked entries fail closed. Sequential and locked allocations cannot intentionally overlap.
- Failed prepare releases only its own newly created reservation; committed prepare retains it. Successful ownership-checked cleanup publishes the final receipt before releasing its exact reservation. A normally observable failure best-effort removes or replaces a misleading success receipt and retains the reservation. An uncatchable publish-to-release crash or failed compensation removal may retain both a success-shaped receipt and the reservation; harness and supervisor adopters reject that receipt because strict registry validation and exact reservation absence are required together under the registry lock.
- Receipt validity and reservation absence form one composite success invariant. An unsafe registry, malformed entry, collision or retained exact reservation rejects an otherwise valid `CLEANED/SIGNAL` receipt fail closed.
- The supervisor registry reader now matches the Bash authoritative parser's LF-only record-boundary semantics instead of Python `splitlines()`: CRLF, bare CR, NUL, Unicode/control separators and embedded/extra records are rejected. It also bounds by `fstat.st_size`, loops through short reads to EOF and rejects size/read mismatches or trailing/oversize content.
- Legacy runs without reservations are never migrated or consulted for allocation. Doctor/run reject a missing current reservation; ownership-checked legacy cleanup remains available without backfill.

## Offline validation

- `(cd business-agent-module/integration-tests && env -u INT001_SYNTHETIC_UPSTREAM_HARNESS -u INT001_RUNTIME_PROBE npm run test:synthetic -- tests/05-synthetic-upstream-bootstrap-safety.test.ts)` — PASS, `84/84`.
- `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py` — PASS, `82/82`; includes canonical LF/short-read acceptance and CRLF, bare CR, Unicode/control separator, NUL, embedded LF, trailing-content and oversize rejection.
- `(cd business-agent-module/integration-tests && env -u INT001_SYNTHETIC_UPSTREAM_HARNESS -u INT001_RUNTIME_PROBE npm run test:synthetic)` — PASS, `108 passed / 1 skipped`.
- `(cd business-agent-module/integration-tests && npm run typecheck)` — PASS.
- `bash -n tools/navigator-upstream/scripts/synthetic-upstream-harness.sh tools/navigator-upstream/scripts/synthetic-upstream-bootstrap.sh tools/navigator-upstream/scripts/synthetic-upstream-runtime-audit.sh` — PASS.
- `PYTHONPYCACHEPREFIX=temp/test-artifacts/BUG-009-lf-parser-pyc python3 -m py_compile tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py` — PASS.
- `git diff --check` — PASS.
- Scoped added-line and BUG-009 test-record high-confidence secret scan — PASS, `0 matches`.

## Independent review

- Security review: PASS after the first final review's Python `splitlines()` and single-read blockers were corrected with explicit regression coverage; final re-review found no blocking issue.
- Test-matrix review: PASS, no blocking finding; two defense-in-depth test additions remain non-blocking.
- Canonical/README/runbook consistency review: PASS, no blocking finding after the composite invariant, crash-window truth, changed paths, counts and residual risks were aligned.

## Residual risk

- Registry operations remain pathname-based and `flock` coordinates cooperating processes only. A hostile same-UID rename/swap race is outside the current single-operator local harness threat model. Directory-FD and `openat(O_NOFOLLOW)`/`fstat`/`unlinkat` hardening is deferred rather than expanding this bounded repair.
- Legacy prepared runs without reservations remain isolated from the registry; real bind and Docker/Compose startup collision checks preserve fail-closed compatibility without reading or auto-reclaiming their private state.
- Registry and receipt operations do not add directory/file `fsync`; sudden host power loss may lose the latest rename or unlink. This is a non-blocking durability risk for the disposable local harness, not production-readiness evidence.

## Current conclusion

- All required offline commands and three independent reviews passed after the strict parser/full-read correction. AC-15 is satisfied and the canonical surfaces may restore only exact Runtime 4 runId `int001-bug009-20260722-484c6216` for one execution.
- This result does not satisfy BUG-009 AC-2/AC-3, does not make INT-001 accepted, and does not prove real SIM/TMS integration, Worker Gateway external, Provider or production readiness.
