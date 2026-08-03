---
workitem: NAVI-CORE-001-S4-02C1B0B-beta
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
baseline: 9f7714f2
prerequisite: NAVI-CORE-001-S4-02C1B0B-alpha@9f7714f2
coordination_freeze: f019137
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: GUARDED_A2A_CAPTURED_EFFECT_ARTIFACT
---

# NAVI-CORE-001 S4-02C1B0B beta guarded A2A effect artifact

This slice connects the accepted route-preparation Gate to the guarded A2A create lane without
changing the legacy public create path or the guarded Direct lane. Existing canonical contexts
retain their read-only sealed proof. A missing context is represented before permission by an
immutable `PendingContextClaim` containing copied scalar facts only; it carries no live persistence
entity and performs no insert, flush, binding, lifecycle reservation or Provider operation.

For the single valid `PERMITTED` attempt, route preparation claims and revalidates the pending
context, binds the Session and reserves lifecycle ingress. The participant then prepares a deep,
defensive request snapshot and a separate A2A message copy. The internal canonical proof is added
only to the message copy for consumption by the existing context-resolving decorator; it is not
added to public metadata, the caller request or persistence input. The captured artifact owns the
exact Provider input and the post-Provider task-field persistence, context completion/persistence
and reservation confirmation sequence.

The execution order is therefore:

`PERMITTED -> claim/revalidate -> Session bind -> lifecycle reserve -> participant -> defensive
capture -> Provider -> task persistence -> context completion/persistence -> confirm -> receipt`.

The plan carries either an existing proof or a pending claim, never both, and is not replaced after
permission. Fresh or settled concurrent winners must match context, Session, owner, tenant, agent,
Provider, Worker, directory and model identity exactly. Every post-permission failure remains
`AMBIGUOUS`; this lane does not release, retry, fall back to legacy execution or redispatch. Every
non-permitted or malformed attempt performs zero claim, bind, reserve, payload construction,
Provider, persistence, context completion and confirmation work.

## Changed paths

- `session-module/src/main/java/com/foggy/navigator/session/service/TaskCreateContextNormalizer.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskCreateTargetResolver.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
- this work item

No Coordinator, receipt state machine, Router, Direct implementation, command factory, addon,
controller, SDK, schema, entity, database migration, runtime configuration or public HTTP contract
is changed.

## Validation boundary

Production compilation passed three times: the first implementation compile in `15.781 s`, the
initial unchanged-scope compile in `10.006 s`, and the reviewed delta compile in `16.431 s`. During focused construction, the first
eight-selector run had seven passes and one Mockito unnecessary-stubbing fixture error; an expanded
20-selector run had nineteen passes and the same kind of fixture-only error in the denial case.
Removing or marking the deliberately unread denial stub lenient closed those harness issues; no
production assertion failed in either run.

Three independent read-only reviews were then run. The Gate/receipt review accepted directly. The
authority and compatibility reviews independently found the same P1: a pending context targeting
an already persisted Session reused its Gate-time `SessionFacts`, so a pre-claim concurrent Session
identity drift could be detected only after Provider effect. The authority review also identified a
P2 package-private bypass because `sealForResolution` still accepted a pending inspection.

The delta makes `sealForResolution` existing-context-only. At the sole post-permit claim entry, an
existing Session is cleared from the claim transaction persistence context, re-read under a
`PESSIMISTIC_READ` lock and compared as an exact immutable `SessionFacts` record before any context
insert. Missing or drifted owner, tenant, Agent, Provider, Worker, directory, model configuration,
model, task/provider state, status or deletion facts therefore fail before binding, reservation,
participant and Provider. Two direct negative selectors cover the durable re-read fence and the
sealed pre-permit bypass; an eight-selector delta run passed `8/8` in `26.731 s`.

Both finding owners independently reviewed the delta and concluded `P1/P2 CLOSED / ACCEPT`; the
Gate/receipt reviewer remained accepted. No reviewer found a new P1 or P2.

The final unchanged-scope focused run executed 29 exact selectors: 23 Facade/Normalizer/Resolver
behaviors and six accepted alpha Gate boundaries. It passed with failures `0`, errors `0`, skipped
`0`, `BUILD SUCCESS` in `19.487 s`. Coverage includes pending-context read-only deferral; exact
claim/adopt fences; PlanBinding stability; strict claim/bind/reserve/participant/Provider/persist/
completion/confirm ordering; denial and malformed-attempt zero effects; route-preparation failure;
post-permission failure retention; deep mutable-request isolation; internal-proof consumption and
non-leakage; synthetic failure handling; and unchanged legacy A2A and guarded Direct behavior.

No whole class/module/reactor, E2E, live/provider or final joint full-validation cycle was run.

## Data and rollback boundary

Validation used mocks only. No service or Worker was started; no business/runtime or historical
data was read or mutated. No repair, backfill, replay, reconciliation or deletion was performed.
Rollback is one five-path commit revert and needs no data action.
