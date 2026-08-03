---
workitem: NAVI-CORE-001-S4-03A4
status: REVIEWED_READY_TO_COMMIT
date: 2026-08-04
baseline: 981df30b
coordination_freeze: 143976e
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: SHARED_TERMINATION_INGRESS
---

# NAVI-CORE-001 S4-03A4 Shared termination ingress

The existing Shared task-cancel route now enters the canonical termination coordinator through a
dedicated Sharing Key authority adapter. It retains the required `X-Sharing-Key`, route, no-body
shape, `RX<String>` response, and `Task cancelled` success wording while adding the same optional
client request ID header used by the other command ingresses.

## Changed paths

- `session-module/src/main/java/com/foggy/navigator/session/service/SharingKeyService.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/ScopedSharedTaskTerminationCommandAdapter.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/ScopedSharedTaskTerminationCommandAdapterTest.java`
- `session-module/src/main/java/com/foggy/navigator/session/controller/SharedTaskController.java`
- `session-module/src/test/java/com/foggy/navigator/session/controller/SharedTaskControllerTest.java`
- this work item

## Authority and command boundary

Sharing Key validation now mints a process-local, content-free termination authority containing
only the stable key row, owner, tenant, and logical Agent references. The raw key is discarded and
never enters context, fingerprint, envelope, receipt, result, exception, log, or `toString` output.
Cancellation does not consume ask quota. After owner-qualified plan resolution and exact Task,
owner, tenant, and key-Agent fences, the current key row is revalidated read-only immediately
before authorization and receipt admission. No database lock spans receipt or Provider I/O.

The adapter fixes `SHARED / NAVIGATOR_SHARED_API`, `SHARE_GRANTEE`, and
`SHARING_KEY_CAPABILITY`, derives the actor fingerprint only from tenant, owner, and key row ID,
uses the immutable termination `PlanBinding`, and fixes force to false. It cannot accept caller
ownership, routing, Provider identity, context, force, envelope, or authorization decisions.

## Controller and compatibility

The cancel branch no longer builds authority from a mutable key entity or calls the legacy Facade
cancel path. It clears and finally restores ambient `UserContext`, so an optional Navigator identity
cannot influence Sharing Key ownership. Typed Shared admission failures preserve existing key and
not-found fail-A messages. Unsupported or internal routing arguments are safely generalized;
ambiguous and other safe termination state codes remain fail-B, and unsafe state text is hidden.
There is no Task re-read, automatic retry, or inferred terminal success on concurrent failure.

Absent and blank request IDs each mint a fresh UUID. A valid explicit UUID is normalized after
trimming; malformed input is rejected before plan, receipt, or effect. Fresh, recorded replay, and
already-terminal results all retain the existing `Task cancelled` public wording.

## Validation evidence

- `git diff --check` passed.
- Affected production compile passed in 15.570 seconds:
  `mvn -pl session-module -am -DskipTests compile`.
- Exact focused selectors passed in 27.619 seconds: 18 tests, 0 failures, 0 errors, 0
  skipped. The run selected seven new adapter/authority tests, four Shared cancel Controller
  tests, four termination-coordinator tests, and three capability/terminal plan tests.
- Initial final review found one P2: requested Task identity drift used a safe state code instead
  of the frozen anti-enumeration not-found response. The adapter now emits the typed
  `Task not found: <requestedTaskId>` admission before current-key revalidation or receipt, and
  the existing plan-drift selector covers zero revalidation/coordinator behavior.
- After that revision, affected production compile passed in 17.669 seconds and the changed
  selector passed 1/1 in 27.167 seconds. Three revised independent read-only reviews accepted
  the implementation with no remaining reproducible P1/P2.
- No whole test class, module/reactor, database, E2E, live Provider, or final joint full-validation
  cycle is part of this slice.

## Data boundary

No service or Worker was started. No business/runtime or historical data was read or mutated, and
no repair, backfill, replay, reconciliation, cleanup, or deletion was performed. SIM, TMS, and the
user-owned `BOOT-INF/` directory were not changed.
