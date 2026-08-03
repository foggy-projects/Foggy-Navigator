---
workitem: NAVI-CORE-001-S4-02C2A
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
baseline: 0315b49d
prerequisite: NAVI-CORE-001-S4-02C1C@0315b49d
coordination_freeze: ff859a8
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: PRE_ROUTE_FRESH_PARTICIPANT_GATE
---

# NAVI-CORE-001 S4-02C2A pre-route fresh participant Gate

This slice adds one narrow fresh-only phase to the accepted task-create coordinator. The phase is
reachable only after `beginEffect` has returned a real provider-effect permit with a durable attempt
ID, and it runs before route preparation can claim a pending context, bind a Session, or reserve
lifecycle state. Existing participants inherit a default no-op, so their established
route-then-prepare ordering is unchanged.

After the new hook returns, the coordinator immediately rechecks both the immutable execution plan
and the actual Provider-effect identity before allowing route preparation. A participant may update
non-binding execution policy such as `maxTurns` or approved metadata, but cannot drift Agent,
Session, Provider, Worker, model, or Directory identity under the already permitted command.

The seam is required by the Shared create lane: its quota must remain ahead of lifecycle and
Provider mutation, while a recorded replay must not consume quota again. A pre-permit callback
would violate replay semantics, and the existing post-route preparation callback would leave
unnecessary route mutations when quota is rejected. C2B will consume the locked SharingKey through
this seam; C2A itself has no SharingKey, controller, Provider, schema, or public contract behavior.

## Changed paths

- `session-module/src/main/java/com/foggy/navigator/session/service/TaskCreateCommandCoordinator.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/TaskCreateCommandCoordinatorTest.java`
- this work item

## Validation boundary

Focused evidence must prove the exact fresh order
`beginEffect -> pre-route participant -> route preparation -> existing preparation -> Provider ->
completion -> recordResult`; all recorded/started/ambiguous and invalid-permit paths keep every
participant and route callback at zero. A pre-route failure must mark the exact attempt ambiguous
while route preparation, existing preparation, Provider, completion, and result recording remain
zero. A hook that tampers with a plan-bound field must fail the immediate recheck with the same zero
downstream effects; a separate identity-source drift must likewise fail the immediate actual-effect
identity recheck. An existing real-Coordinator captured-input selector is strengthened to show that
a participant which does not override the new hook still runs route preparation before its existing
preparation callback. No whole class/module/reactor, E2E, live/provider, or final joint validation
is part of this slice.

The first eight-selector run reached six green selectors, including the existing scoped OpenAPI
ordering proof. The two new negative selectors stopped in test setup because a helper that creates
and stubs an `EffectPermit` mock was invoked inside another Mockito `thenReturn` expression. This is
a test-only nested-stubbing error; the fixtures were separated before rerunning only those impacted
selectors, and the failed run is not acceptance evidence.

The affected production compile passed with `BUILD SUCCESS` in `18.742 s`. After separating the
fixtures, the two impacted selectors passed `2/2` in `30.278 s`. The unchanged final focused set
then passed `8/8`, failures `0`, errors `0`, skipped `0`, with `BUILD SUCCESS` in `21.843 s`: seven
Coordinator selectors cover receipt and begin-effect replay states, invalid permits, exact fresh
ordering, pre-route failure, plan-bound drift, and post-permit Provider ambiguity. The included
scoped OpenAPI selector remained green but uses a mocked Coordinator, so it is retained only as
bridge compatibility evidence and is not claimed as proof of real default-hook ordering. A
review-requested real-Coordinator selector now provides that exact default no-op ordering evidence.
No whole class/module/reactor, E2E, live/provider, or final joint cycle ran.

The same review also noted that the first drift negative was stopped by the plan recheck before it
could exercise the adjacent actual-effect identity recheck. A second negative now keeps the plan
unchanged while drifting only the identity supplier after the hook. Together with the strengthened
default-hook selector, this bounded delta passed `2/2`, failures `0`, errors `0`, skipped `0`, with
`BUILD SUCCESS` in `30.102 s`; both prove route preparation and all later effects stay at zero or in
the accepted order. No broader test set was rerun for this test-only evidence delta.

Three independent read-only reviews accepted the production ordering and ambiguity boundary. One
review initially rejected only the claimed default-hook evidence because the cited OpenAPI test
mocked the Coordinator; a second review also requested an identity-only drift negative. The two
bounded test changes above closed both findings, and both finding owners returned `ACCEPT / NO
REMAINING P1/P2`. The production diff remained unchanged during that evidence delta.

## Data and rollback boundary

Tests use mocks only. No service or Worker is started, no business/runtime or historical data is
read or mutated, and no repair, backfill, replay, reconciliation, or deletion is authorized.
Rollback is one three-path commit revert and requires no data action.
