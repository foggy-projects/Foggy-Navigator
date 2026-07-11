---
type: bug
bug_source: acceptance-found
version: 1.4.0-SNAPSHOT
ticket: BUG-013
severity: major
status: closed-isolated
reproduction_status: confirmed
test_strategy: unit-test
automation_decision: required
owner: codex-app-server-worker
---

# Windows Process-Tree Termination Settle Race

## Background

Windows v4 exact-package operations passed install, startup, real Ultra and native-subtask execution, but the running same-package update was blocked twice during pre-drain candidate validation. Both failures preserved the old Worker and left no lifecycle residue.

## Reproduction

1. Install v4 SHA-256 `71a0b37e1b3c1613d996b30e1422b73280ab76b3bebf59efb7b0987025b6bc54` into a Windows path containing spaces and `#`.
2. Start the Worker, complete a real `codex-ultra` task and retain one idle app-server instance.
3. Run `update.ps1` with the same v4 ZIP.
4. Candidate `npm test` fails before drain at `app-server-pool.test.ts` case `pool drain does not resolve until a tracked app-server descendant is gone`.

The exact case passed 10/10 when executed independently. The failure was repeatable only in freshly extracted update candidates while the real idle tree was present.

## Expected vs Actual

- Expected: forced Windows tree termination waits within a bounded deadline until every snapshotted exact identity disappears, then candidate validation and update continue.
- Actual: `killWindowsTree` waited fixed intervals of 50ms and 100ms after `taskkill`; a still-terminating exact identity was immediately reported as residue, producing an `AggregateError`.

## Impact Scope

- Windows same-package update availability is blocked before drain.
- Safety is preserved: no old Worker drain, package swap, identity mutation or residue occurred.
- WSL v4 exact-package install, real Ultra, running update and stop remain independently passed.

## Test Strategy

- Add a unit regression proving bounded cleanup polling tolerates delayed disappearance.
- Re-run process-tree and pool descendant tests.
- Rebuild a deterministic release and repeat the complete Windows exact-package operations matrix with a real idle app-server tree.
- Re-run the WSL exact-package operations matrix because the release identity changes.

## Code Inventory

- `tools/codex-app-server-worker/scripts/process-tree.mjs`
- `tools/codex-app-server-worker/tests/process-tree.test.ts`
- `tools/codex-app-server-worker/tests/app-server-pool.test.ts`
- `tools/codex-app-server-worker/update.ps1`

## Fix Checklist

- [x] Replace fixed post-`taskkill` waits with exact-identity bounded polling.
- [x] Preserve descendant-first retry and fail-closed residue reporting.
- [x] Add delayed-disappearance regression.
- [x] Run focused process-tree and pool drain tests.
- [x] Run final Worker full regression and deterministic release build.
- [x] Pass the Windows and WSL exact-package operations matrix using the rebuilt archive.

## Verification

- before: Windows v4 candidate update failed twice before drain; old Worker/state/version remained unchanged and final cleanup residue was 0.
- focused-after: process-tree `8 passed / 1 platform-skipped`; tracked-descendant pool drain passed; independent pre-fix case stress was 10/10 and establishes the candidate-context boundary.
- final-regression: `200 total / 193 passed / 7 platform-skipped / 0 failed`; schema digest `6f2550bb528581f17c4c3a3857dca92c860406aa3274e314cfa726c32e395d8f`; typecheck/build passed.
- final-package: byte-identical v5 builds produced SHA-256 `b6271e5a3220b0253d97b6d05c9fe5f5561331655e27faccd8ad254fbf6c31d9`, `1,500,249` bytes and `168` safe entries.
- final-operations: both Windows and WSL passed fresh install, bundled validation, real Ultra/native execution, running same-package update, old-tree removal, stable replacement identity, stop and zero residue. Windows update completion was verified independently after the outer wrapper remained attached; no old exact identity or runtime descendant remained.
- decision: closed for isolated P0-P2 acceptance. This exact-package matrix is release validation, not the production P4 Ultra-default stage; P3/P4 production gates remain unstarted.

## References

- [BUG-009](./BUG-009-lifecycle-process-tree-and-stop-outcome.md)
- [Acceptance](../acceptance/OPT-001-p0-p7-acceptance.md)
