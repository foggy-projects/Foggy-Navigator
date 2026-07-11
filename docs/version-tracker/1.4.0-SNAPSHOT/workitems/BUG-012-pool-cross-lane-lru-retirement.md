---
type: bug
bug_source: review-found
version: 1.4.0-SNAPSHOT
ticket: BUG-012
severity: major
status: fixed-isolated
reproduction_status: confirmed
test_strategy: unit-and-integration
automation_decision: required
owner: codex-app-server-worker
---

# Pool Cross-lane LRU Retirement

## Background

When the global app-server pool was full of idle instances belonging to other auth/home/base-URL lanes, a request for a new lane could be rejected even though capacity was reclaimable. This allowed idle lane saturation to starve a legitimate new lane.

## Correctness Contract

- At global capacity, a new lane may retire the least-recently-used idle instance across lanes.
- Busy instances are never selected for cross-lane retirement.
- Retirement finishes before replacement creation; concurrent replacements reserve capacity and never exceed the global maximum.
- A failed or slow close remains fail-closed and is visible to drain/metrics; ownership is not released early.
- Same-lane reuse, queue bounds, drain, TTL, max-task, crash and lane isolation semantics remain unchanged.

## Code Inventory

- `tools/codex-app-server-worker/src/app-server/pool.ts`
- `tools/codex-app-server-worker/tests/app-server-pool.test.ts`

## Fix Checklist

- [x] Select the cross-lane LRU idle instance when the pool is globally full.
- [x] Exclude busy instances from replacement.
- [x] Serialize slow closes and reserve global capacity during concurrent replacements.
- [x] Propagate close failure and drain timeout without releasing ownership.
- [x] Preserve existing same-lane reuse and bounded queue behavior.
- [x] Approve the post-BUG-008~012 final Worker full regression and release artifact.

## Verification

- Pool regressions cover LRU replacement, busy exclusion, concurrent slow-close replacement and close failure.
- Runtime-child cleanup tests also prove TTL/drain wait for descendant removal.
- The defect is fixed in isolated automation; final Worker is `200 total / 193 passed / 7 platform-skipped / 0 failed`, and the byte-identical v5 archive SHA-256 is `b6271e5a...c31d9`. Windows/WSL exact-package operations passed. P3 long-soak fairness evidence has not started.

## References

- [OPT-001 progress](./OPT-001-independent-codex-app-server-worker-progress.md#acceptance-defect-closure)
