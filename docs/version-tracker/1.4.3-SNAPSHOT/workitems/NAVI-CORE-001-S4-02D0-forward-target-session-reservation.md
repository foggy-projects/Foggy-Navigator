---
workitem: NAVI-CORE-001-S4-02D0
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
baseline: a09c9c19
prerequisite: NAVI-CORE-001-S4-03A0@a09c9c19
coordination_freeze: 570e12b
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: FORWARD_TARGET_SESSION_RESERVATION
---

# NAVI-CORE-001 S4-02D0 Forward target Session reservation

This dormant seam reserves the deterministic target Session for a future `NEW_SESSION` forward
command. The identity is derived from the owner, normalized tenant and canonical client request
UUID supplied by the future authenticated D1 adapter; D0 itself is not an authorization boundary.
A call either inserts one new Session in an independent transaction or verifies an exact existing
row without updating it.

The seam does not yet wire the forward HTTP route, create a Task or relation, call a Provider, or
use the canonical command receipt. It does not change `EXISTING_SESSION` resume behavior and is not
the completion of S4-02D.

## Changed paths

- `session-module/src/main/java/com/foggy/navigator/session/repository/SessionForwardTargetSessionReservationRepository.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/SessionForwardTargetSessionReservationService.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/SessionForwardTargetSessionReservationServiceTest.java`
- this work item

## Validation boundary

The exact focused test covers deterministic identity, sequential and barrier-controlled concurrent
exact replay, concurrent immutable-binding conflict, deleted rows, assigned-ID insert recovery,
outer rollback isolation and the absence of any update/delete/scan API. No existing Session test
class, affected module lane, whole reactor, database migration, E2E, live Provider or final joint
validation is included.

## Implementation evidence

- The affected production compile passed on the first run in `15.994 s`.
- The initial focused shape passed `5/5`. Transaction review then required a deterministic insert
  barrier and integrity-only recovery. The first tightened run deliberately exposed the exception
  translation gap (`6/8`, two failures); after walking only Spring integrity exceptions or SQLState
  class `23`, the same focused class passed `8/8` with zero failure, error or skip in `36.358 s`.
- The final tests force all eight exact callers through the assigned-ID insert boundary, force a
  two-caller binding-drift race, and prove the inner reservation commit survives an outer rollback.
- Three independent final read-only reviews returned `ACCEPT` with no P1/P2. They separately
  checked identity/data safety, transaction/concurrency behavior, and architecture/scope.
- No affected test lane was required by the frozen D0 gate. No whole module/reactor, migration,
  E2E, live Provider or final joint full-validation cycle ran; the joint budget remains `0/3`.

## Data and rollback boundary

Validation may only use its disposable in-memory database. No service or Worker is started and no
business/runtime or historical data is read or mutated. The code never repairs, updates, deletes or
scans existing Sessions. Rollback is one exact four-path commit revert and carries no data-cleanup
authorization.
