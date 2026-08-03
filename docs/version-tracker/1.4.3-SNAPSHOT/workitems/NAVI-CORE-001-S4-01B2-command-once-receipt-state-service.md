---
workitem: NAVI-CORE-001-S4-01B2
status: IMPLEMENTED_REVIEWED_AFFECTED_GREEN
date: 2026-08-03
baseline: 12fa23ae17a03024ba81462bf240a79e88f879fa
coordination_freeze: b165e46
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: CONTENT_FREE_COMMAND_ONCE_RECEIPT_STATE_AUTHORITY
---

# NAVI-CORE-001 S4-01B2 command once-receipt state service

This slice implements the durable, content-free state authority over the S4-01B1 receipt. It does
not dispatch a Provider operation and owns no callback, payload, token, cleanup, reconcile,
lifecycle outbox, scanner or historical-request synthesis.

## Frozen behavior

The public service surface is exactly `prepare`, `beginEffect`, `recordResult`, `markAmbiguous`
and `find`. Every database operation uses a separate `REQUIRES_NEW` transaction; reads are marked
read-only. The only effect capability is returned by `beginEffect` after a pessimistic receipt
lock changes `PREPARED` to `EFFECT_STARTED`. Its `PERMITTED` result is a closed final class with a
private constructor, so callers cannot mint a permit from a boolean, disposition or identifier.
An already-started, result-recorded or ambiguous receipt never grants another effect permit.

`prepare` and `beginEffect` require the envelope and the in-process
`VerifiedCommandAuthorizationDecision`. The single injected server authority verifies the hidden
binding before any database access. The server then independently digests the envelope and hidden
binding and requires equality. Callers cannot provide a receipt ID, digest or effect-attempt ID.
The receipt ID is a domain-separated SHA-256 of `clientRequestId`; every digest field uses an
explicit present/null tag followed by a four-byte big-endian UTF-8 byte length. Enums use their
canonical names.

The binding digest covers the outer schema and all command kind, ingress, request, actor,
ownership, target and effect fields in fixed order. The stable authorization digest covers only
authorization schema, policy and correlation plus binding-digest version/value; decision identity
and validity times are deliberately excluded. A newly issued, currently verified decision with
the same stable digest is accepted as an authorization renewal without overwriting the first
decision provenance. Different authority, expired decision, policy drift or any binding drift
fails closed.

`recordResult` and `markAmbiguous` run after the effect and therefore do not require a still-live
decision. They lock the row and require the exact server-minted attempt. Exact terminal repeats
are idempotent; reference/code drift, attempt mismatch and cross-terminal transitions fail closed.

## Production registration

`SessionModuleAutoConfiguration` now explicitly scans the command component, persistence and
repository packages. At the composition root it defines a named UTC `Clock` and a single
replaceable `ServerAuthority` bean with a fixed policy and positive validity period. The service
injects that exact named clock and the unique authority; it never creates or accepts an authority
per call.

The focused proof combines exact reflection over the production auto-configuration declarations
with a minimal Spring/H2 slice that instantiates the real command entity, Spring Data marker
repository proxy, service and transaction manager. This avoids starting unrelated session
components while still preventing a test-only `@Import` from hiding a production scan gap.

## Concurrent-insert finding and correction

The first focused run exposed a real persistence issue rather than a flaky assertion. With an
assigned receipt ID and primitive `@Version long`, Spring Data classified a new entity as existing
and used merge semantics. Two concurrent prepares whose initial reads both missed the row could
therefore both report `CREATED`, bypassing the required duplicate-insert recovery path.

The coordinated five-path correction changes only the Java version field to nullable `Long` while
retaining `@Column(nullable = false)`, the same `BIGINT` DDL and a content-free primitive getter
that reports zero before persistence. A new entity now has a null version, so the unchanged marker
repository `saveAndFlush` uses persist semantics. The deterministic test wraps, but never mocks,
the real repository proxy: a barrier makes both initial reads observe missing, all saves and
lookups delegate to the real proxy, the loser receives the primary-key violation, its failed
transaction exits, and the service performs the recovery lookup in a second new transaction.

The S4-01B1 MySQL proof was not repeated: this Java-only newness correction changes neither its
migration SQL nor the resulting column type/nullability. No Testcontainers or existing database
was accessed.

## Changed paths

- `session-module/src/main/java/com/foggy/navigator/session/command/CommandOnceReceiptService.java`
- `session-module/src/main/java/com/foggy/navigator/session/command/persistence/CommandOnceReceiptEntity.java`
- `session-module/src/main/java/com/foggy/navigator/session/config/SessionModuleAutoConfiguration.java`
- `session-module/src/test/java/com/foggy/navigator/session/command/CommandOnceReceiptServiceTest.java`
- this work item

No repository API, migration, command ingress, Provider adapter, public HTTP/SDK contract or old
row is changed.

## Validation record

Production compilation passed on the first run:

`/usr/bin/time -p mvn -q -pl session-module -am -DskipTests compile`

It exited `0` in `17.50 s`.

The first exact focused run was:

`/usr/bin/time -p mvn -q -pl session-module -am
-Dtest='CommandOnceReceiptServiceTest'
-Dsurefire.failIfNoSpecifiedTests=false test`

It completed in `34.37 s` with `5/6` tests passing and one concurrency assertion failing. Both
callers returned `CREATED`, which led directly to the primitive-version/merge diagnosis above;
there were no compilation, Spring wiring, H2 schema or other behavioral failures.

After the nullable-version correction and deterministic real-repository barrier, the same exact
command exited `0` in `34.48 s`. Surefire ran `6/6` tests in `5.744 s` with failures, errors and
skips all zero. The database log contained the expected losing insert's H2 `23505` primary-key
violation, and the green result proves the post-rollback recovery read succeeded. The proof covers:

- production component/entity/repository scans and conditional authority/clock bean declarations;
- one actual service, entity mapping, repository proxy and `saveAndFlush` persistence path;
- the exact five-method API, four-method non-destructive repository and unforgeable permit shape;
- deterministic receipt identity and full binding/null-tag conflict matrix;
- exact replay, stable current renewal, immutable first provenance, changed policy, foreign
  authority and expired-decision rejection;
- deterministic concurrent single row and single effect permit;
- cross-service/current-authority restart with no replay after effect start;
- exact result and ambiguous idempotence, drift and attempt mismatch;
- post-expiry terminal recording without a live decision; and
- prepare, begin and result commits surviving an outer rollback.

The coordination root's implementation pre-review identified the forgeable public-record form of
`EffectPermit` before the focused run and narrowed it to the private-constructor final form above.
The subsequent independent early production review examined that corrected form, returned
`EARLY_ACCEPT`, and found no additional production issue. Reflection evidence locks the final
constructor and factory surface.

The formal independent review found no production or test defect and one evidence-accuracy P2:
the first draft incorrectly attributed the permit finding to the independent reviewer. After the
attribution correction above, the limited re-review returned `ACCEPT`. No additional focused test,
database proof or scope expansion was requested.

After review, the one frozen filtered affected lane ran exactly once:

`/usr/bin/time -p mvn -q -pl session-module -am
-Dtest='CommandOnceReceiptServiceTest,LifecycleActivationAuthorityContractTest,
IsolatedEnforcedLifecycleContractTest,TaskTerminalCommitServiceTest'
-Dsurefire.failIfNoSpecifiedTests=false test`

It exited `0` in `25.94 s`. Surefire ran `16/16` tests with failures, errors and skips all zero:
command receipt `6`, lifecycle activation authority `6`, isolated enforcement `2`, and terminal
commit `2`. The expected concurrent losing insert again emitted H2 `23505` and recovered without a
second permit. This was the only affected execution; B1 MySQL was not repeated. No full reactor,
full module, E2E, live/provider or final joint validation was run, so the slice consumes none of
the three final joint cycles.

## Data and rollback boundary

The only runtime data used was newly created disposable H2 receipt fixture data, deleted between
focused cases. No existing or historical business/runtime row was read, repaired, reconciled,
replayed, backfilled or deleted. Existing requests without a receipt remain an explicit residual
fact and are never synthesized from audit, lifecycle, task or outbox data.

Code rollback may leave the already-approved inert B1 table and any future receipt rows in place.
Dropping, clearing, repairing or backfilling that table remains separately authorization-gated.
