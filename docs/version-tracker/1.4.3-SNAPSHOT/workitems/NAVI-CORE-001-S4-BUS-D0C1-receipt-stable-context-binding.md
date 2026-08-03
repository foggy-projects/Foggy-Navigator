---
workitem: NAVI-CORE-001-S4-BUS-D0C1
status: IMPLEMENTED_REVIEWED
date: 2026-08-04
baseline: ff72ab9a
coordination_freeze: 7486cc9
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: BUSINESS_CREATE_RECEIPT_STABLE_CONTEXT_BINDING
---

# NAVI-CORE-001 S4-BUS-D0C1 receipt-stable context binding

This narrow correction keeps a Business create receipt stable when a new Session starts without a
caller-supplied context and the fresh transaction generates its durable context before an exact
same-request replay. D0D remains blocked until this slice is accepted.

## Exact paths

1. `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskCreatePlan.java`
2. `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskCreateCommandCoordinator.java`
3. `business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskCreateCommandCoordinatorTest.java`
4. This work item.

## Two distinct fingerprints

- The existing `navi.business-task-create-plan.v1` semantic fingerprint, record shape and exact
  revalidation remain unchanged. They continue to bind the runtime-resolved context and all other
  effect-time facts.
- A package-visible receipt fingerprint uses the independent
  `navi.business-task-create-receipt-plan.v1` domain and the same canonical fields, order, null
  markers and list encoding. Its only substitution is the raw requested context in the context
  slot.
- Raw null, empty, blank, padded and exact contexts remain distinct. The raw value is hashed only;
  it is never emitted in an envelope, receipt, reference, log or safe projection.
- `PlanBinding` can only be created from the full prepared command. No plan-only construction path
  remains available for a later caller to accidentally bind a resolved context.

## Effect fence

The coordinator still sends the original prepared command to the Spring-managed fresh executor.
The executor performs a fresh resolution and uses the unchanged full semantic plan for exact
revalidation before any mutation or Provider effect. The receipt fingerprint identifies the
caller command; it is not an effect authorization truth and cannot relax context, Worker, model,
workspace or input drift checks.

## Focused validation

- Affected production compile: PASS.
- Coordinator focused test: PASS (`13/13`, failures/errors/skips all zero).
- Existing semantic-fingerprint golden selector: PASS (`1/1`), proving the full plan digest remains
  pinned.
- Existing fresh context-drift selector: PASS (`1/1`), proving resolved effect drift still fails
  before mutation or Provider effect.
- Three independent final read-only reviews: ACCEPT with no P1/P2. They confirmed the original
  semantic digest is byte-stable, the receipt digest replaces only the context slot, all other
  plan groups remain bound, plan-only binding construction is gone, content remains redacted, and
  the change is sufficient to unblock D0D.
- No module/reactor, E2E, live Provider/runtime or final joint full validation is authorized for
  this slice. Final joint budget remains `0/3 consumed`.
- No service/Worker or historical/existing business/runtime data is used or mutated.

## Stop conditions

Stop and replan if a fifth path is required; the existing semantic fingerprint, constructor shape
or exact revalidation changes; any non-context plan fact is omitted; raw context is normalized or
leaked; a second binding factory, ledger or compatibility dual-read appears; D0A/D0B service,
fresh executor, receipt schema, HTTP, SDK or D0D is changed; historical data or `BOOT-INF/` is
touched.
