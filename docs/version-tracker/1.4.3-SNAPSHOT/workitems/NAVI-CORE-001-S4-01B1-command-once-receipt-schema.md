---
workitem: NAVI-CORE-001-S4-01B1
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
baseline: e3669413
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: CONTENT_FREE_RECEIPT_SCHEMA_SUBSTRATE_ONLY
---

# NAVI-CORE-001 S4-01B1 command once-receipt schema

This atomic slice adds the inert persistence substrate for future canonical command coordination.
It does not add a receipt service, command pipeline, ingress/provider wiring, scanner, callback,
historical-request synthesis or backfill.

## Frozen schema boundary

`command_once_receipts` mirrors the complete immutable S4-01A command binding and the first
verified authorization metadata. `client_request_id` alone is the unique once identity;
`idempotency_key` remains an immutable binding fact and is deliberately not unique. The first
authorization decision, policy, correlation and validity window are immutable provenance. Its
three `Instant` values are stored losslessly as epoch-second plus nano pairs; they never substitute
for a fresh canonical-authority verification and cannot be renewed or overwritten on the entity.

The schema stores separate binding and stable authorization-binding SHA-256 digests with explicit
versions. The latter excludes the decision identity and validity times by contract so a newly
issued, currently verified decision can match the same stable authorization binding without
rewriting the first provenance.

The mutable portion is limited to `PREPARED -> EFFECT_STARTED -> RESULT_RECORDED|AMBIGUOUS`, one
unique effect-attempt identity, an opaque result reference, a safe code, state timestamps and an
optimistic row version. The entity exposes only creation and guarded state transitions; it has no
generic public binding setter or authorization-renewal mutation. The marker repository explicitly
exposes only `saveAndFlush`, client-request/effect-attempt lookups and a receipt-id
pessimistic-write seam. It inherits no CRUD/JPA bulk read, scan, save-all or delete surface and
declares no state scanner or modifying transition query.

## Initial review and MAJOR correction

The first limited review returned `REJECT / MAJOR`: extending `JpaRepository` unintentionally made
generic `findAll`, bulk save and destructive delete methods publicly callable, while the original
`getDeclaredMethods` assertion inspected only locally declared methods and therefore falsely
claimed the complete public surface was narrow.

The correction changes the repository to Spring Data's marker `Repository` and explicitly declares
only one single-row `saveAndFlush` plus the three frozen lookups. A separate focused test now uses
`getMethods` to assert the complete inherited public interface allowlist and proves the type is not
assignable to either `CrudRepository` or `JpaRepository`. Entity and SQL paths are unchanged by this
review correction, so their first disposable-MySQL evidence remains valid and is not rerun.

The exact no-Docker correction proof ran:

`/usr/bin/time -p mvn -q -pl session-module -am
-Dtest='CommandOnceReceiptMigrationMySqlIntegrationTest#repositoryPublicSurfaceIsExactAndNonDestructive'
-Dnavi.command.receipt.mysql.integration=true
-Dsurefire.failIfNoSpecifiedTests=false test`

It exited `0` in `29.39 s`; Surefire ran the selected method `1/1` in `0.153 s` with failures,
errors and skips all zero. The selector compiled the corrected production repository and test, so a
separate production compile was unnecessary. It did not construct or start Testcontainers. No
database, service or Worker was accessed or changed during the correction proof.

The limited independent re-review returned `ACCEPT`. It confirmed that the complete inherited
public repository surface is exactly the four frozen methods, the `saveAndFlush` signature is
supported by Spring Data JPA's repository base implementation, the false-negative reflection test
is closed, and retaining the unchanged entity/SQL MySQL evidence without a container rerun is
risk-proportionate. No remaining direct finding or affected-lane need was identified.

## Changed paths

- `session-module/src/main/java/com/foggy/navigator/session/command/persistence/CommandOnceReceiptEntity.java`
- `session-module/src/main/java/com/foggy/navigator/session/command/repository/CommandOnceReceiptRepository.java`
- `docs/migration/2026-08-03-navi-core-command-once-receipts.sql`
- `session-module/src/test/java/com/foggy/navigator/session/command/CommandOnceReceiptMigrationMySqlIntegrationTest.java`
- this work item

No existing product, test, POM, configuration, table or row is changed.

## Validation record

Production compilation passed on its first run after the five-path implementation:

`/usr/bin/time -p mvn -q -pl session-module -am -DskipTests compile`

The command exited `0` in `17.33 s`. A later narrow entity correction made each guarded transition
validate every argument and timestamp into local variables before mutating any field; the focused
test compilation and execution below compiled that final production source.

Docker availability was checked read-only (`docker info --format '{{.ServerVersion}}'`) and reported
server `28.4.0`. The one authorized focused database proof then ran exactly:

`/usr/bin/time -p mvn -q -pl session-module -am
-Dtest=CommandOnceReceiptMigrationMySqlIntegrationTest
-Dnavi.command.receipt.mysql.integration=true
-Dsurefire.failIfNoSpecifiedTests=false test`

It exited `0` in `42.85 s`; Surefire ran `1/1` test in `13.801 s` with failures, errors and skips all
zero. The proof used `mysql:8.0.44` in a new Testcontainers database and established:

- the SQL has exactly one additive `CREATE TABLE IF NOT EXISTS` and no old-table DDL or DML;
- applying the same SQL twice preserves a pre-existing sentinel and leaves the new table initially
  empty;
- the engine/collation and exact column name/type/length/nullability set match the entity, including
  lossless epoch-second+nano authorization metadata, 320-character result reference and both
  versioned SHA-256 digests;
- Hibernate schema validation succeeds against the migrated table;
- only `client_request_id` and nullable `effect_attempt_id` are unique beyond the primary key;
  reusing an idempotency key with a different client request is accepted;
- actor discriminator, authorization correlation/time+nano and receipt state null-shape constraints
  reject invalid writes;
- entity transitions reject wrong attempts and invalid chronology without partially mutating the
  in-memory entity, and no public generic setter or repository state scanner is declared.

The MySQL fixture was stopped in `finally`; the exact container no longer existed after the run.
It was the only database touched. No service or Worker was started, stopped or changed. No affected
lane, full reactor, E2E, live/provider or final joint validation was run; this slice consumes none
of the three final joint cycles.

## Data and rollback boundary

The migration is one additive `CREATE TABLE IF NOT EXISTS` statement. It contains no old-table
DDL, DML, repair, replay, reconcile or backfill. Existing requests without receipts remain an
explicit residual fact. Code rollback may leave this inert table in place; any future table/row
deletion or historical synthesis requires separate user authorization.
