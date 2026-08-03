---
workitem: NAVI-CORE-001-S4-03A3B
status: REVIEWED_READY_TO_COMMIT
date: 2026-08-04
baseline: 37d1629a
coordination_freeze: 293ae1d
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: UI_A2A_TERMINATION_CONTROLLER_WIRING
---

# NAVI-CORE-001 S4-03A3B UI/A2A termination wiring

The existing Task UI and Agent A2A cancel routes now enter the trusted termination adapter and the
canonical once-effect coordinator. Both routes add the same optional client request ID header while
keeping their HTTP paths, authentication, body shapes, return type, and success wording.

## Changed paths

- `session-module/src/main/java/com/foggy/navigator/session/controller/TaskController.java`
- `session-module/src/test/java/com/foggy/navigator/session/controller/TaskControllerTest.java`
- `session-module/src/main/java/com/foggy/navigator/session/controller/AgentDiscoveryController.java`
- `session-module/src/test/java/com/foggy/navigator/session/controller/AgentDiscoveryControllerTest.java`
- this work item

## Controller convergence

Task cancellation no longer reads a mutable Task projection to decide terminal state or routing.
It passes only Task ID, force intent, and the optional header to the trusted UI adapter. Accepted
normal and force results retain their existing wording, while a canonical terminal result retains
the existing terminal no-op wording. The old Facade cancel call and the deadlock re-read that could
declare success outside the receipt pipeline are removed.

Task not-found and malformed request-ID validation remain distinct from known unsupported Provider
capability. Safe `TERMINATION_*` state failures, including ambiguous post-permit outcomes, remain
fail-B codes; unsafe internal state text is not exposed. A raw pre-permit pessimistic lock keeps the
existing retry failure message but performs no Task re-read, retry, or terminal inference.

Agent cancellation keeps the existing owner/tenant/path-Agent resource-access fence first, then
passes the path Agent, Task, and optional header to the trusted A2A adapter. The adapter independently
binds the immutable plan and closes the read race. Accepted, terminal, and recorded outcomes all
retain the existing `Task cancel requested` response. No Controller can reach the legacy Facade or
an `A2aAgent.cancelTask` mutation path.

## Validation evidence

- `git diff --check` passed.
- Affected production compile passed in 15.560 seconds:
  `mvn -pl session-module -am -DskipTests compile`.
- Exact Controller/A3A focused selectors passed in 27.015 seconds: 20 tests, 0 failures,
  0 errors, 0 skipped. The run selected only the seven Task cancel tests, six Agent cancel
  tests, and seven trusted-adapter tests.
- Three independent final read-only reviews accepted the implementation with no reproducible P1/P2:
  permission and public compatibility, canonical once-effect/terminal truth, and Spring/test-risk
  coverage were reviewed separately.
- No whole Controller class, module/reactor, Shared/OpenAPI, database, E2E, live Provider, or final
  joint full-validation cycle is part of this slice.

## Compatibility and data boundary

The optional header is additive; absent and blank values remain valid and are passed unchanged to
the server-owned adapter. Existing JWT/API-key authentication and Agent ownership/path denial remain
unchanged. Shared and OpenAPI termination continue on their existing paths until their dedicated
slices.

No service or Worker was started. No business/runtime or historical data was read or mutated, and
no repair, backfill, replay, reconciliation, cleanup, or deletion was performed. SIM, TMS, and the
user-owned `BOOT-INF/` directory were not changed.
