---
workitem: NAVI-CORE-001-S4-02C1B0A
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
baseline: 55f73ce6
prerequisite: NAVI-CORE-001-S4-02C1A@55f73ce6
coordination_freeze: 171a759
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: TASK_CREATE_PARTICIPANT_EFFECT_GATE
---

# NAVI-CORE-001 S4-02C1B0A task-create participant Gate

This slice closes the concurrent `PREPARED -> beginEffect` window before OpenAPI token and lease
issuance is connected to the canonical create coordinator. Fresh preparation now runs inside the
single-use Provider-effect Gate only after `beginEffect` returns `PERMITTED` with a usable attempt.
Recorded replay, already-started, ambiguous and pre-permit binding failures therefore run no
participant or Provider callback.

The new package-private participant contract has one preparation callback and one completion
callback. The existing coordinator overload remains source- and behavior-compatible through a
no-op participant. A real participant is forbidden from using the legacy value-identity Gate API;
it must provide a post-preparation `PreparedProviderEffect` that binds one immutable identity to an
effect callback over already captured route input. The prepared lane checks the actual identity
before the atomic permit, runs preparation once, then re-runs plan matching and validates the
prepared artifact identity before allowing its Provider supplier. Completion runs only after the
facade returns a fresh result that satisfies the existing exact-result guard and before the receipt
result is recorded. A null-preserving snapshot of task, Agent, Provider, Worker, model, Session and
Directory fields must remain exactly value-equal after completion. Any post-permit preparation,
target, Provider, result, completion or record failure is retained as ambiguous and cannot become
an automatic redispatch.

## Changed paths

- `session-module/src/main/java/com/foggy/navigator/session/service/TaskCreateCommandCoordinator.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/TaskCreateCommandCoordinatorTest.java`
- this work item

No facade/router payload construction, scoped OpenAPI adapter, business token service, addon,
controller, SDK, public HTTP DTO, repository/entity/schema or runtime configuration is changed.

## Validation boundary

The initial affected production compile passed in `22.292 s`; after review-driven hardening, the
final compile passed in `16.163 s`. The first 12-selector run had only three nested-Mockito fixture
errors and the corrected set passed `12/12`. Two independent reviews then found the legacy identity
and mutable-effect-input bypass plus null/blank completion mutation gap. After the prepared artifact,
legacy fail-closed rule and exact result snapshot were added, the first expanded run passed 13 of 14;
the sole failure was an invalid-result test still invoking the intentionally rejected legacy API.
After moving that fixture to the prepared API, the unchanged final 14-selector set passed `14/14`,
failures `0`, errors `0`, skipped `0`, `BUILD SUCCESS` in `26.295 s`. It covers initial receipt replay
and begin-effect races with explicit zero-interaction participants, legacy no-op compatibility,
legacy rejection for real participants, strict hook ordering, captured effect input under mutable
request drift, single-use Gate, pre-permit/preparation/post-preparation/Provider failures, actual null
and conflicting Provider results, all frozen completion identity fields including null/blank clears,
completion failure and receipt-record failure. No full class/module/reactor, E2E, live/provider or
final joint validation belongs to this slice.

Two independent read-only delta reviews concluded `ACCEPT / NO REMAINING P1/P2` after the fixes.
They separately verified the permit/preparation race and captured-input boundary, and the legacy
compatibility/result-snapshot/test evidence boundary.

## Data and rollback boundary

Validation uses mocks only. No service or Worker is started and no business/runtime or historical
data is read or mutated. No repair, backfill, replay, reconciliation or deletion is authorized.
Rollback is one three-path commit revert and requires no data action.
