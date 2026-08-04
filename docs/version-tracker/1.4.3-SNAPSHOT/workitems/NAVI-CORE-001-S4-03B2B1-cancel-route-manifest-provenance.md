# NAVI-CORE-001 S4-03B2B1 — Cancel Route Manifest and SDK Provenance

## Status

`IMPLEMENTED_PENDING_FOCUSED_VALIDATION`

## Scope

Synchronize the canonical authorization manifest and SDK source provenance with the accepted
OpenAPI Agent-cancel dual-lane route. This slice changes governance metadata only; it does not
change Controller, authorization, lifecycle, Provider, CLI command, SIM, or TMS behavior.

## Frozen Truth

- The launcher route remains one manifest entry with two explicitly labelled and mutually
  exclusive branches. Management accepts one Navigator JWT/query-token/API-key credential and
  uses the role gate, same-tenant durable owner, and `NON_ENFORCED` domain fence. Runtime accepts
  the ClientApp runtime-access triple and requires exact ownership plus `ENFORCED` Provider
  admission.
- Both branches invoke the canonical `task.terminate` effect. Accepted or replayed attempts remain
  `CANCEL_REQUESTED`; only durable canonical facts establish terminal state.
- The manifest remains 469 entries. Its canonical and evidence copies are byte-identical and use
  SHA-256 `f0d1e35004858f41dea3af676bb7c583318cefbceab7765562b5bd545dfb742d`.
- SDK source advances to `1.0.43-SNAPSHOT`; published provenance remains `1.0.40-SNAPSHOT` with
  `SOURCE_AHEAD_OF_PUBLISHED`. No installed or published distribution is changed.

## Exact Paths

1. `navigator-common/src/main/resources/authorization/route-manifest-v1.csv`
2. `docs/version-tracker/1.4.3-SNAPSHOT/evidence/GOV-001-p0.5-method-route-manifest.csv`
3. `navigator-common/src/main/java/com/foggy/navigator/common/authorization/AuthorizationRouteCatalog.java`
4. `navigator-common/src/test/java/com/foggy/navigator/common/authorization/AuthorizationContractTest.java`
5. `navigator-open-sdk/pom.xml`
6. `navigator-open-sdk/src/main/resources/com/foggy/navigator/sdk/cli/authorization-provenance.properties`
7. `navigator-open-sdk/src/test/java/com/foggy/navigator/sdk/cli/UpstreamCliTest.java`
8. This work item.

## Validation Budget

Only the two focused common authorization tests, the affected launcher manifest coverage selector,
the two exact SDK provenance/package selectors, an SDK package with tests skipped, and byte/count/
digest checks are authorized. Whole-module/reactor, CLI full-suite, E2E, live Provider, database,
and final joint full validation are excluded from this slice.

Historical and existing data remain read-only. No repair, backfill, replay, reconcile, cleanup
mutation, or generated historical fact is permitted.

## Implementation Result

Pending focused validation.
