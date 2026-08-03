# NAVI-CORE-001 S4-02D2A2 — Forward outcome store

- Status: `IMPLEMENTED_REVIEWED`
- Parent: `NAVI-CORE-001`
- Stage: `4`
- Final joint full-cycle budget: `0/3 consumed`

## Purpose

Provide the dormant insert-only/read-exact relation seam later used by D2B after the D1 once-effect
decision. This slice persists only the canonical outcome projection already owned by
`session_relations`; it does not dispatch, create a receipt, expose HTTP behavior or recover old
data.

## Exact scope

- Add package-local `SessionForwardOutcomeStore`.
- `insertFresh` uses an independent write transaction, locks the D0-created deterministic target
  Session, rejects any existing canonical outcome, then performs exactly one `persist + flush`.
- `requireExactReplay` uses an independent read-only transaction, reads at most two canonical
  relations by deterministic target Session, requires exactly one and checks every persisted field.
- Build the relation spec directly from the accepted immutable forward plan and D1-verified Task
  result, including deterministic compatible metadata.

## Deliberate non-scope

- No entity, repository, schema, migration, unique-index or check-constraint change.
- No duplicate `clientRequestId`, semantic fingerprint or target task ID columns. D0 target identity,
  the once receipt and owner-aware Task facts remain their respective canonical authorities.
- No source content, prompt body, images, teams JSON, credential, token, header or new Message row is
  stored in the relation. The existing bounded prompt preview/length metadata shape is retained.
- No update, merge, delete, repair, reconcile, replay-effect or broad scan API exists.
- No historical/existing relation is changed or treated as a delivery blocker.

## Transaction boundary

The target Session row is locked only to serialize fresh insert attempts for that deterministic
target. A committed outcome survives caller rollback. If a later receipt completion fails, the
newly committed relation remains as forward evidence and is never auto-deleted or repaired; the
command must remain fail-closed/ambiguous.

## Validation budget

1. Compile `session-module` production sources with dependencies and tests skipped.
2. Run only `SessionForwardOutcomeStoreTest` against its disposable H2 database.
3. Obtain three independent read-only P1/P2 reviews of the exact three-path diff.

Do not run a whole module/reactor, MySQL migration, E2E/live Provider lane, or a final joint full
cycle in this slice.

## Implementation evidence

- Production compile: `mvn -pl session-module -am -DskipTests compile` — PASS (`17.081s`).
- Initial focused context boot failed before any test logic because the minimal no-repository Spring
  context does not expose `EntityManager` as a constructor bean. JPA-standard `@PersistenceContext`
  injection closed the test/production assembly mismatch without adding a dependency or path.
- The next focused run passed six of seven scenarios; its only failure showed that the in-memory
  `@PrePersist` nanoseconds exceeded the durable H2 `datetime(6)` precision. Refreshing the inserted
  row after `flush` made the returned snapshot represent the durable value.
- Final focused `SessionForwardOutcomeStoreTest`: PASS (`8/8`, `30.609s`), including independent
  transaction survival, six-way concurrent single-row proof, and real H2 persistence/replay of a
  nullable Provider projection with null/non-null drift rejection.
- Review found that D1/legacy relations permit a nullable Provider projection. The store now
  preserves null as unknown instead of turning an already-returned Provider effect ambiguous; a
  real H2 insert/replay test now proves null persistence plus exact-null replay and rejects a later
  non-null expectation as drift.
- Three independent final read-only reviews accepted the exact staged three-path diff with no
  remaining P1/P2, including transaction/concurrency, durable field fidelity, and historical-data
  mutation boundaries.
- Whole module/reactor, MySQL migration, E2E/live Provider and final joint full validation were not
  run. Final joint budget remains `0/3 consumed`.

## Stop conditions

Stop and replan if implementation requires a fourth path, entity/repository/schema/DDL/DML change,
a second ledger, copying request/digest/task identity, source-message materialization, Provider or
receipt execution, existing-row mutation, historical-data repair, or a broad/destructive API.
