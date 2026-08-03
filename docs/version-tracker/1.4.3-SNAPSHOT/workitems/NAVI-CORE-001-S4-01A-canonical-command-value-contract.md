---
workitem: NAVI-CORE-001-S4-01A
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
baseline: cf5860f44ff448ac93a21487ea8a0671db5a6b65
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: VALUE_CONTRACT + PROCESS_LOCAL_CAPABILITY ONLY
---

# NAVI-CORE-001 S4-01A canonical command value contract

This slice adds only the Stage 4 command value contract and a process-local authorization
capability to `navigator-spi`. It does not create a command pipeline, composition-root authority,
receipt store, migration, coordinator or ingress/provider wiring. Existing commands therefore do
not gain or claim verified execution authorization from this work item alone.

## Frozen value contract

`CanonicalCommandEnvelope` is an immutable, fixed-schema and content-free value graph. Its only
top-level values are `schemaVersion`, an immutable `CommandBinding`, and safe serializable
`AuthorizationMetadata`. The nested enums freeze exactly:

- command kinds: `CREATE`, `TERMINATE`, `RESUME`, `RECONNECT`, `RESYNC`, `APPROVAL_RESUME`,
  `RUNTIME_RECOVERY`;
- ingresses: `A2A`, `DIRECT`, `OPENAPI`, `SHARED`, `SYSTEM_RECOVERY`;
- actor kinds: `AUTHENTICATED_PRINCIPAL`, `SERVER_PROCESS`;
- target kinds: `LOGICAL_AGENT`, `TASK`, `SESSION`, `APPROVAL`, `RUNTIME`.

The binding contains only typed command kind; ingress, bounded client surface and route; request,
idempotency and correlation identities; a discriminated actor; tenant/owner and nullable
client-app/upstream references; target kind/id and nullable logical Agent/provider/physical
Worker/model/Task/Session identity; and effect action/scope. Reference values are bounded to 256
characters, client surface to 128, and blank or control-character values fail construction.

Authenticated actors require a non-`UNKNOWN` canonical principal type and credential lane plus a
fingerprint, while their server-process authority reference must be absent. Server-process actors
have the inverse shape. Optional references use `null` as the single ABSENT meaning; the contract
does not distinguish an omitted JSON property from an explicit `null`. Logical-Agent, Task and
Session targets require a matching typed identity only when that identity is known. There is no
kind-by-ingress allow matrix, and `CREATE` does not prematurely require provider, Worker or model
resolution.

The graph contains no `Map`, `Collection`, `Object`, `JsonNode`, array or free-form carrier and has
no prompt, body, content, message, attachment, file/path/URL/environment, provider parameters,
business context, header, raw credential, token or secret field. JSON tests recursively enforce
the property allowlist without treating property order as a digest or security fact.

## Process-local capability boundary

`VerifiedCommandAuthorizationDecision` has one private constructor and hides the exact binding,
safe metadata and an identity-only seal. It exposes only `metadata()`; Jackson auto-detection is
explicitly disabled and `@JsonValue` serializes only the metadata. Serialized metadata, a boolean,
an identifier/prefix, a legacy decision shape or shadow `PolicyDecisionV1` cannot deserialize or
convert into the capability.

Each `ServerAuthority` instance owns a distinct private seal, a `Clock`, a bounded policy-version
reference and positive validity duration. `issue` creates a UUID decision identity and time window,
copies the binding's correlation identity, and seals the exact binding/metadata pair.
`requireVerified` first proves canonical authority identity, then exact schemas, binding, metadata,
correlation and the inclusive-not-before/exclusive-expiry window. Success returns the binding hidden
inside the capability rather than a boolean. Another authority, actor/target/correlation/metadata
drift, expiry, clock rollback before `notBefore`, or reflective reconstruction with a new seal all
fail closed.

This is an ordinary same-process object-capability boundary, not a JVM sandbox. It does not claim
protection from theft of the genuine private seal or hostile same-JVM mechanisms such as `Unsafe`.
The future composition root must retain the canonical authority instance; an envelope or actor's
server-process reference can never select the verifier.

## Changed paths

- `navigator-spi/src/main/java/com/foggy/navigator/spi/command/CanonicalCommandEnvelope.java`
- `navigator-spi/src/main/java/com/foggy/navigator/spi/command/VerifiedCommandAuthorizationDecision.java`
- `navigator-spi/src/test/java/com/foggy/navigator/spi/command/CanonicalCommandEnvelopeTest.java`
- `navigator-spi/src/test/java/com/foggy/navigator/spi/command/VerifiedCommandAuthorizationDecisionTest.java`
- this work item

No existing source, test, POM, schema, session, ingress, provider or policy-evaluator path changed.

## Focused validation

- Production compile passed on its first run:
  `/usr/bin/time -p mvn -q -pl navigator-spi -am -DskipTests compile`; exit `0`, wall `8.82 s`.
- The exact frozen pair was run with
  `/usr/bin/time -p mvn -q -pl navigator-spi -am
  -Dtest='CanonicalCommandEnvelopeTest,VerifiedCommandAuthorizationDecisionTest'
  -Dsurefire.failIfNoSpecifiedTests=false test`.
- First execution ran 21 tests with 20 passing and one test-assertion failure. The failing safe
  serialization assertion compared Jackson numeric `Instant` nodes lexically; equivalent exponent
  forms (`E+9` and `E9`) were considered unequal. No production behavior failed.
- The assertion was narrowed to deserialize the emitted safe metadata and compare its typed value.
  The same exact pair was rerun once and passed `21/21`, failures/errors/skips `0`; envelope `11`,
  capability `10`, Surefire `0.062 s` and `0.446 s`, observed wall `15.14 s`.
- Independent review then requested that the optional-target JSON test prove rather than assume the
  two distinct input shapes. The first tightened assertion incorrectly assumed the enclosing
  `NON_NULL` annotation also removed nested-record nulls; the exact pair consequently reported one
  test-only assertion failure (`20/21`) and demonstrated that no production behavior had failed.
- The test now constructs the shapes deterministically: it applies `putNull` to all five nullable
  target facts in one tree, removes the same properties from a copy, asserts each tree's shape, and
  then proves both deserialize to the same canonical value. The exact pair was rerun once and passed
  `21/21`, failures/errors/skips `0`; envelope `11`, capability `10`, Surefire `0.066 s` and
  `0.439 s`, observed wall `15.10 s`.

No affected lane, full reactor, E2E, live/provider, package or final joint validation was run. This
slice consumes none of the user-authorized final joint cycles.

## Scope and process boundaries

No service or Worker was started, stopped or changed. No database, business data, runtime data,
historical data or disposable fixture was read or mutated. No repair, backfill, replay, reconcile,
cleanup or migration occurred.

One read-only status command accidentally used Git's expanded untracked-file display and printed
the pre-existing filename `BOOT-INF/classes/application-docker.yml.example`. Its contents were never
opened or read, and the `BOOT-INF/` tree was not modified, staged or otherwise touched. All later
status checks use the ordinary collapsed `?? BOOT-INF/` entry, and any future staging must enumerate
only the five approved paths.

The implementation remains uncommitted and unpushed for independent review. Stage 4 is not wired;
the next receipt/pipeline/ingress slices must not treat this additive value type as evidence that
current command execution is already enforced.

## Independent review

The initial independent review found no production defect and one test-coverage MINOR: the
explicit-null/omitted test did not itself prove that its two JSON trees had different shapes. After
the deterministic two-tree correction and the final `21/21` focused result, a limited independent
re-review returned `ACCEPT`. It confirmed each nullable target property is present and null in one
tree, absent in the other, and both deserialize to the same canonical value. No MAJOR finding,
scope expansion or affected-lane need remains for S4-01A.
