---
workitem: NAVI-CORE-001-S4-BUS-D0A
status: IMPLEMENTED_REVIEWED
date: 2026-08-04
baseline: e9f588ac
coordination_freeze: be16c20
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: NEUTRAL_COMMAND_RECEIPT_PORT
---

# NAVI-CORE-001 S4-BUS-D0A neutral command receipt port

This slice exposes Navigator's existing durable command receipt authority through a
provider-neutral SPI. It does not create another receipt implementation and does not connect the
Business Task create ingress.

## Exact scope

- Add a content-free `CanonicalCommandReceiptPort` to `navigator-spi`.
- Add one session-owned adapter that delegates all five operations to the existing
  `CommandOnceReceiptService`.
- Preserve every receipt disposition, state, safe reference, authorization timestamp and
  operational timestamp exactly.
- Keep the existing receipt service, persistence model, repository, migration, authority,
  transaction boundaries and component scan unchanged.

## Deliberate non-scope

- No Business Task plan, coordinator, facade, Controller or SDK wiring.
- No Provider, Task, Session, token, audit, cleanup, retry, repair or reconciliation behavior.
- No schema, migration, POM, entity, repository or historical-data change.

## Validation budget

1. Compile the affected production graph with tests skipped.
2. Run only `CanonicalCommandReceiptPortAdapterTest`.
3. Reuse the exact existing receipt selectors for single effect permit, outer transaction
   independence and started/ambiguous no-replay behavior once.
4. Obtain three independent read-only P1/P2 reviews of the exact four-path diff.

Do not run a whole class other than the new adapter test, a whole module/reactor, MySQL migration,
E2E/live Provider/runtime or final joint full cycle. Final joint budget remains `0/3 consumed`.

## Implementation evidence

- Affected production compile: PASS (`23.44s`).
- New adapter focused test: PASS (`4/4`, failures/errors/skips all zero, `27.35s`).
- Existing receipt authority seam: PASS (`3/3`, failures/errors/skips all zero, `22.20s`),
  covering concurrent single permit, started/ambiguous no-replay and independent commit across an
  outer rollback.
- The adapter only delegates and maps immutable values. It does not catch delegate failures or
  add a transaction, authority, callback, persistence surface or business dependency.
- Validation used mocks and the existing test-owned disposable H2 receipt fixture. No service,
  Worker or runtime was started; no business, historical or existing data was read or mutated.
- Whole module/reactor, MySQL migration, E2E/live Provider/runtime and final joint full validation
  were not run; final joint budget remains `0/3 consumed`.
- Three independent final read-only reviews accepted the exact staged four-path diff with no P1/P2
  across SPI/module boundaries, Spring/delegation semantics and receipt state/transaction safety.

## Stop conditions

Stop and replan if this needs a fifth path, changes the existing receipt implementation or schema,
introduces a second ledger/authority/transaction, creates a Business↔Session module dependency,
adds payload or secret fields, performs any business side effect, touches `BOOT-INF/`, or reads or
mutates historical/existing business data.
