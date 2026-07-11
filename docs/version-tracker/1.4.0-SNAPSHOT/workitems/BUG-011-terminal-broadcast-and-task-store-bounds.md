---
type: bug
bug_source: review-found
version: 1.4.0-SNAPSHOT
ticket: BUG-011
severity: major
status: fixed-isolated
reproduction_status: confirmed
test_strategy: unit-and-integration
automation_decision: required
owner: codex-app-server-worker
---

# Terminal Broadcast And TaskStore Bounds

## Background

Terminal tasks retained `EventBroadcast` instances and full encrypted request payloads in resident maps. Every state transition also appended the ciphertext again. Long-lived Workers with many large prompts could therefore grow memory with task history and amplify journal writes even after tasks were terminal.

## Correctness Contract

- A terminal broadcast closes live subscribers, is removed from the resident map, and is loaded on demand only for terminal SSE replay.
- Concurrent terminal replays receive the complete durable sequence; the on-demand broadcast is retired again after use.
- Startup does not create resident broadcasts for terminal histories. Tombstone and cleanup purge durable event journals without materializing a broadcast.
- TaskStore keeps request ciphertext durable for recovery/idempotency but removes it from resident terminal summaries.
- The acceptance record writes ciphertext once. Later journal snapshots carry a presence marker instead of repeating the payload; legacy full snapshots remain readable and migrate forward without another copy.

## Code Inventory

- `tools/codex-app-server-worker/src/persistence/event-store.ts`
- `tools/codex-app-server-worker/src/persistence/task-store.ts`
- `tools/codex-app-server-worker/src/task-manager.ts`
- `tools/codex-app-server-worker/tests/event-store.test.ts`
- `tools/codex-app-server-worker/tests/task-store.test.ts`
- `tools/codex-app-server-worker/tests/task-manager-recovery.test.ts`
- `tools/codex-app-server-worker/tests/http-contract.test.ts`

## Fix Checklist

- [x] Retire terminal broadcasts and expose resident broadcast count in health metrics.
- [x] Preserve complete concurrent terminal SSE replay without permanent residency.
- [x] Bound restart behavior across 105 large terminal histories and concurrent replay cleanup.
- [x] Remove encrypted request payloads from resident terminal summaries.
- [x] Persist request ciphertext once and preserve legacy journal compatibility.
- [x] Purge tombstoned event journals without creating a resident broadcast.
- [x] Approve the post-BUG-008~012 final Worker full regression and release artifact.

## Verification

- Large-history recovery proves terminal summaries remain bounded across restart and concurrent replay.
- Journal tests prove large payload durability, single ciphertext persistence and legacy snapshot compatibility.
- HTTP tests prove concurrent terminal SSE completeness and on-demand broadcast release.
- Isolated automation covers the defect; final Worker is `200 total / 193 passed / 7 platform-skipped / 0 failed`, and the byte-identical v5 archive SHA-256 is `b6271e5a...c31d9`. Windows/WSL exact-package operations passed. P3 long-soak memory evidence has not started.

## References

- [OPT-001 progress](./OPT-001-independent-codex-app-server-worker-progress.md#acceptance-defect-closure)
