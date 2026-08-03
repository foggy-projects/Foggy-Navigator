---
workitem: NAVI-CORE-001-S4-02D2B0
status: IMPLEMENTED_REVIEWED
date: 2026-08-04
baseline: 3ba1d298
prerequisite: NAVI-CORE-001-S4-02D2A3@3ba1d298
coordination_freeze: d091c7d
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: DORMANT_FORWARD_TRANSACTION_BOUNDARY
---

# NAVI-CORE-001 S4-02D2B0 forward transaction boundary

This dormant Spring-proxied seam separates the existing-target transaction contract from the
new-target no-outer-transaction contract. D2B1 will compose it around the two production service
branches; this slice does not change forward behavior by itself.

## Exact scope

- Add one stateless Spring component with two public callback methods.
- Preserve `READ_COMMITTED` and `noRollbackFor=TaskStateRepairedException` for the existing-target
  callback.
- Use `NOT_SUPPORTED` for the new-target callback so an existing transaction is suspended and
  restored around the callback.
- Prove the annotations through a real Spring AOP proxy and a disposable H2 transaction manager.

## Deliberate non-scope

- No change to `SessionForwardService`, D0 reservation, D1 factory, D2A2 outcome store, Controller,
  DTO, entity, repository, schema, migration, Provider addon or POM.
- No business branching, command decision, persistence call, Provider call, retry, compensation,
  exception translation or transaction-state emulation inside the boundary.
- No production service is claimed to consume this seam until D2B1.
- No repair, backfill, replay effect, reconciliation or historical-data mutation.

## Validation budget

1. Compile affected production sources with tests skipped.
2. Run only `SessionForwardTransactionBoundaryTest`.
3. Obtain three independent read-only P1/P2 reviews of the exact three-path diff.

The focused test may create and destroy only its own in-memory H2 table. Do not run a service,
whole module/reactor, E2E/live Provider lane, runtime, or final joint full cycle. Final joint budget
remains `0/3 consumed`.

## Implementation evidence

- Affected production compile: PASS (`17.262s`).
- `SessionForwardTransactionBoundaryTest`: PASS (`2/2`, `27.399s`). The real AOP proxy proved
  existing-target `READ_COMMITTED`, ordinary runtime rollback and repaired-state no-rollback, plus
  new-target no-transaction execution and outer transaction suspend/resume/rollback isolation.
- Validation created and destroyed only the test-owned in-memory H2 table. It did not start a
  service or Worker and did not read or mutate business, runtime, historical or existing data.
- Whole module/reactor, E2E/live Provider, runtime and final joint full validation were not run;
  final joint budget remains `0/3 consumed`.
- Three independent read-only reviews of the final exact staged diff accepted the Spring proxy,
  transaction propagation, exception policy and pure composition-seam boundaries with no P1/P2
  findings.

## Stop conditions

Stop and replan if this needs a fourth path, a second production transaction manager, service or
business logic, self-invocation, an active new-target outer transaction, swallowed exceptions,
automatic retry/compensation, real business/runtime data access, or historical/existing data
mutation.
