---
type: bug
bug_source: review-found
version: 1.4.0-SNAPSHOT
ticket: BUG-009
severity: critical
status: closed-isolated
reproduction_status: confirmed
test_strategy: integration-and-operations
automation_decision: required
owner: codex-app-server-worker
---

# Lifecycle Process Tree And Stop Outcome

## Background

Tracking only the Worker PID cannot prove that app-server descendants have exited. A stale success marker can also make stop/update treat a failed shutdown as graceful. These gaps can release state/cwd ownership or swap an installation while an old runtime child is still alive, including during a pre-0.1.1 first-hop update.

## Correctness Contract

- Worker and runtime children use exact process-tree snapshots bound to PID, creation identity and command SHA-256. Raw command lines are never persisted.
- Stop requests carry a fresh nonce/request ID. Success or failure outcomes are atomically replaced and accepted only when the outcome contains the matching ID.
- Start failure, forced stop, update and rollback must kill and verify the complete tracked tree before releasing ownership or swapping files.
- Legacy first-hop update must use the new updater and enforce the same cleanup boundary.
- Each app-server runtime extends its tracked tree around turn/start, interrupt and close. `executeTurn`, pool TTL retirement and drain do not resolve until descendants are gone.
- Identity mismatch, cleanup failure and close failure are fail-closed; they cannot be reported as graceful shutdown.

## Code Inventory

- `tools/codex-app-server-worker/scripts/process-tree.mjs`
- `tools/codex-app-server-worker/src/stop-request.ts`
- `tools/codex-app-server-worker/src/app-server/runtime.ts`
- `tools/codex-app-server-worker/src/app-server/pool.ts`
- `tools/codex-app-server-worker/src/index.ts`
- `tools/codex-app-server-worker/start.ps1`, `start.sh`
- `tools/codex-app-server-worker/stop.ps1`, `stop.sh`
- `tools/codex-app-server-worker/update.ps1`, `update.sh`
- `tools/codex-app-server-worker/tests/process-tree.test.ts`
- `tools/codex-app-server-worker/tests/operations-scripts.test.ts`
- `tools/codex-app-server-worker/tests/stop-request.test.ts`
- `tools/codex-app-server-worker/tests/app-server-runtime.test.ts`
- `tools/codex-app-server-worker/tests/app-server-pool.test.ts`

## Fix Checklist

- [x] Add sanitized exact-identity process-tree snapshot/extend/kill/verify support.
- [x] Add nonce-bound atomic shutdown success/failure outcomes.
- [x] Fail closed when self-shutdown fails or descendants remain.
- [x] Clean the verified tree on startup failure.
- [x] Make runtime close/abort and pool TTL/drain wait for descendant cleanup.
- [x] Focused runtime lifecycle `16/16` and pool `12/12` passed; typecheck/build/diff-check passed and test temp residue was 0.
- [x] Final Worker full regression is `200 total / 193 passed / 7 platform-skipped / 0 failed`.
- [x] Rebuilt deterministic v5 archive: SHA-256 `b6271e5a3220b0253d97b6d05c9fe5f5561331655e27faccd8ad254fbf6c31d9`, `1,500,249` bytes, `168` entries.
- [x] Rerun Windows/WSL start-real-Ultra-running-update-stop using that exact v5 archive.
- [x] Rerun a real runtime child lifecycle and verify zero residue before final live signoff.

## Verification

- Process-tree tests cover hashed snapshots, PID identity mismatch refusal and exact descendant cleanup.
- Operations tests cover forced stop, failed self-shutdown, startup failure and start refusal after an unclean stop.
- Runtime/pool tests cover stubborn descendants, abort ownership, TTL retirement and drain completion boundaries.
- Final automation also covers lifecycle operation mutual exclusion, owner-bound nonces, no-clobber snapshots, running drain, Linux unreadable runtime evidence and Windows delayed termination visibility. v3 and v4 were blocked and cleaned with zero residue.
- The byte-identical v5 archive (`b6271e5a...c31d9`, `1,500,249` bytes, `168` entries) passed the complete Windows and WSL exact-package operations matrix: fresh install, bundled tests/schema/typecheck/build, real Ultra/native execution, running same-package update, old-tree removal, stable replacement identity, stop and zero residue. The Windows update wrapper was independently verified after its outer command remained attached; old root/runtime identities were gone, the replacement stayed READY, and final stop left zero residue.
- This closes the isolated lifecycle defect. It does not approve P3 production observation or production routing.

## References

- [OPT-001 progress](./OPT-001-independent-codex-app-server-worker-progress.md#acceptance-defect-closure)
