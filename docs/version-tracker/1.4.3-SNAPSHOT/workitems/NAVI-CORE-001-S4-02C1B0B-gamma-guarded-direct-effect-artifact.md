---
workitem: NAVI-CORE-001-S4-02C1B0B-gamma
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
baseline: e475c210
prerequisite: NAVI-CORE-001-S4-02C1B0B-beta@e475c210
coordination_freeze: 30daf77
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: GUARDED_DIRECT_CAPTURED_EFFECT_ARTIFACT
---

# NAVI-CORE-001 S4-02C1B0B gamma guarded Direct effect artifact

This slice connects the guarded Direct create lane to the accepted four-phase Provider-effect Gate.
Before the Gate, the Router performs only request/model compatibility checks and an exact registry
lookup of the real `TaskCommandProvider`; it does not reserve lifecycle ingress, construct params,
add the runtime-affinity marker, call a Provider or persist task/context state.

For the single valid `PERMITTED` attempt, route preparation reserves lifecycle ingress exactly once.
The Gate then rebuilds actual identity from the real Provider and checks it against the immutable
plan before invoking the fresh-task participant. Only after participant completion does the Facade
create a deep persistence snapshot and a second independent Provider snapshot. The Router derives
params from the Provider snapshot and adds the JVM-local affinity marker only to those params.

The captured artifact executes the only guarded Direct mutation sequence:

`Provider -> task request persistence -> context persistence -> reservation confirmation`.

Actual Provider identity is read before permission, after route preparation, while capturing the
prepared artifact and immediately before the Provider call. A missing, padded or changed identity
fails closed. Any valid-permit failure remains ambiguous and this lane performs no release,
fallback, retry or redispatch. A non-permitted or malformed attempt performs zero reserve/release,
params/marker construction, Provider, task/context persistence and confirmation work.

The old package-private guarded Router overload and value-identity Gate call are removed. The
legacy three-argument Direct route, the public two-argument Facade create path and the accepted
guarded A2A path retain their existing behavior.

## Changed paths

- `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskOperationRouter.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
- this work item

No Coordinator, receipt state machine, participant, request-params helper, command factory, SPI,
addon, controller, SDK, schema, entity, database migration, runtime configuration or public HTTP
contract is changed.

## Validation boundary

The affected production compile passed with `BUILD SUCCESS` in `15.856 s`. The first four-selector
run had two passes and two expected characterization failures: the old tests still stubbed and
verified the removed value-identity `invoke` and pre-Gate reservation release. No production
assertion failed. After updating those fixtures to the frozen four-phase semantics, the direct
focused set passed `6/6` in `26.961 s`; the expanded Direct/A2A set passed `9/9` in `30.983 s`.

The final unchanged-scope focused run executed 27 exact selectors: 21 Facade/Router behaviors and
six accepted alpha Gate boundaries. It passed with failures `0`, errors `0`, skipped `0`,
`BUILD SUCCESS` in `19.353 s`. Coverage includes strict permit/reserve/Provider/persist/context/
confirm ordering; non-permit route/payload/effect zero interaction; two independent nested request
snapshots; participant-time state capture and caller/Provider drift isolation; affinity-marker
non-leakage; actual Provider drift after route preparation and after artifact capture; retained
reservation on uncertain Provider failure; accepted guarded A2A compatibility; and legacy Codex,
CodexBiz, Gemini and LangGraph Direct routing/model/scoped-home behavior.

Three independent read-only implementation reviews concluded `ACCEPT / NO P1/P2`: authority and
bypass review, payload/order/compatibility review, and Gate/identity/evidence review.

No whole class/module/reactor, E2E, live/provider or final joint full-validation cycle was run.

## Data and rollback boundary

Validation used mocks only. No service or Worker was started; no business/runtime or historical
data was read or mutated. No repair, backfill, replay, reconciliation or deletion was performed.
Rollback is one four-path commit revert and needs no data action.
