---
workitem: NAVI-CORE-001-S4-02C1B0B-alpha
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
baseline: 9362dcae
prerequisite: NAVI-CORE-001-S4-02C1B0A@9362dcae
coordination_freeze: dffceaa
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: ADDITIVE_ROUTE_PREPARATION_GATE_SEAM
---

# NAVI-CORE-001 S4-02C1B0B alpha route-preparation Gate

This slice adds an unactivated, package-private route-preparation phase to the accepted task-create
Provider-effect Gate. The additive four-argument `invokePrepared` overload runs generic route
preparation only after the receipt service grants the single valid `PERMITTED` attempt. It then
immediately rechecks both the bound plan and a rebuilt actual Provider identity before allowing any
fresh-task participant. This order lets later A2A and Direct slices defer context claims, Session
binding and lifecycle reservation without issuing an OpenAPI token or lease when route preparation
fails or drifts.

After the participant returns, the existing second plan check and identity-bound
`PreparedProviderEffect` remain mandatory. The Provider therefore consumes only its captured input,
and mutable request drift cannot bypass either the post-route or post-participant boundary. Any
route-preparation failure or drift happens after effect permission and is retained as `AMBIGUOUS`;
participant, artifact construction, Provider, completion and receipt result recording remain zero.
Recorded replay and all non-permitted begin-effect dispositions run no route preparation at all.

The previous three-argument `invokePrepared` delegates through a no-op route preparation. The
legacy value-identity `invoke`, coordinator execute overloads, receipt state machine and public API
remain compatible. No production caller is connected to the new overload in this slice.

## Changed paths

- `session-module/src/main/java/com/foggy/navigator/session/service/TaskCreateCommandCoordinator.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/TaskCreateCommandCoordinatorTest.java`
- this work item

No Facade, Normalizer, Resolver, Router, command factory, addon, controller, SDK, schema, database,
entity, runtime configuration or public HTTP contract is changed.

## Validation boundary

The affected production compile passed with `BUILD SUCCESS` in `16.543 s`. The first exact focused
run executed 17 selectors and reported two test-fixture errors only: both new tests constructed a
Mockito permit mock inside an unfinished `thenReturn` stubbing expression. No assertion or
production behavior failed. After constructing those permits before stubbing, the unchanged 17
selectors passed with failures `0`, errors `0`, skipped `0`, `BUILD SUCCESS` in `26.591 s`.

One independent review accepted the implementation directly. A second review found no P1 but
identified a P2 evidence gap: malformed/no-permit attempts and rebuilt actual-identity drift were
not independently exercised. The test was extended with a `PERMITTED` disposition lacking Provider
permission, null and blank attempt IDs, and a post-route Provider identity drift while the plan
itself remains matched. The final unchanged-scope run executed 19 exact selectors with failures
`0`, errors `0`, skipped `0`, `BUILD SUCCESS` in `26.435 s`. The second reviewer accepted the delta
as `P2 CLOSED / NO REMAINING P1/P2`.

The selectors cover strict route/participant/artifact/Provider/completion/record ordering;
non-permitted and invalid-attempt route/participant/artifact/Provider zero interaction; route
preparation throw; independent post-route plan and rebuilt actual-identity drift before participant;
post-participant artifact drift; captured Provider input;
single-use Gate; legacy no-op and real-participant rejection; result/completion/record failures and
all previously accepted receipt and result contracts. No whole class/module/reactor, E2E,
live/provider or final joint full-validation cycle was run.

## Data and rollback boundary

Validation used mocks only. No service or Worker was started; no business/runtime or historical
data was read or mutated. No repair, backfill, replay, reconciliation or deletion was performed.
Rollback is one three-path commit revert and needs no data action.
