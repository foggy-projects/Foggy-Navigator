---
workitem: NAVI-CORE-001-S4-02C2C
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
baseline: 9fe2e38e
prerequisite: NAVI-CORE-001-S4-02C2B@9fe2e38e
coordination_freeze: 207bd28
coordination_amendment: c1875f4
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: SCOPED_SHARED_TASK_CREATE_COMMAND
---

# NAVI-CORE-001 S4-02C2C scoped Shared task-create command

This slice adds a dormant, process-local Shared command stage before the accepted OpenAPI and
trusted Navigator stages. It is selected only while its own non-inheritable scope is active;
unscoped `SHARED_API` submissions retain the legacy path until the Controller wiring slice.

The raw Sharing Key enters only the C2B read-only mint operation and is discarded. The immutable
scope retains the same-service authority plus safe owner, tenant, Agent, row, and request facts.
It does not copy or expose provisional policy. Its canonical command uses the dedicated
`SHARE_GRANTEE` principal and `SHARING_KEY_CAPABILITY` lane, a content-free actor fingerprint, the
existing plan binding and the single composition-root command authority.

Fresh quota consumption occurs only in the C2A post-permit/pre-route hook. The adapter itself
projects the locked latest policy before route mutation, preserving a nonblank explicit request
prompt override while replacing stale defaults and `maxTurns`; existing preparation and completion
callbacks remain on their accepted sides of the Provider effect. Those callbacks receive no mutable
request, so they cannot overwrite the locked policy; completion receives only the fresh Task.
Recorded replay runs no policy projection or callback and hydrates the durable Task with the
authority owner/tenant context and exact identity checks.

## Changed paths

- `navigator-common/src/main/java/com/foggy/navigator/common/authorization/AuthorizationPrincipalType.java`
- `navigator-common/src/main/java/com/foggy/navigator/common/authorization/AuthorizationCredentialLane.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/ScopedSharedTaskCreateCommandAdapter.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/ScopedSharedTaskCreateCommandAdapterTest.java`
- this work item

No Controller, C2B service, Coordinator, OpenAPI/Trusted stage, pipeline, DTO, schema, POM, addon,
SDK, SIM, or TMS path is changed.

## Validation boundary

Focused selectors cover scope minting, canonical UUIDs, exact request/context/plan authority,
non-nesting/single use/finally cleanup, dedicated actor and ingress, raw/content exclusion, strict
fresh callback order with locked latest policy, post-permit ambiguity, zero-effect replay and
non-permit states, owner-aware exact hydrate, unscoped compatibility, and stage order. At least one
selector composes the real Coordinator and receipt/effect Gate instead of manually invoking mocked
participants. Only affected production compile and exact selectors are allowed here; the Shared
Controller affected lane remains deferred to C2D. No whole class/module/reactor, database race,
E2E, live/provider, or final joint cycle is included.

## Data and rollback boundary

Tests use mocks and in-memory state only. No service or Worker is started, no business/runtime or
historical data is read or mutated, and no repair, backfill, replay, reconciliation, or deletion is
authorized. Rollback is one exact five-path commit revert and requires no data action.

## Implementation result

- Production and test compilation passed inside the final exact-selector command.
- Initial expanded run: `8` selectors, `7` passed and one Mockito fixture error caused by nested
  mock construction inside `thenReturn`; there was no product assertion failure. The fixture was
  changed to construct the permit before stubbing.
- Impacted rerun: `initialAndGateNonPermitStatesHaveZeroSharedEffects` passed `1/1` in `28.109s`.
- Final exact run: all `8/8` selectors passed with zero failures/errors/skips; Maven reactor
  `BUILD SUCCESS` in `31.193s`. This includes real Coordinator ordering, initial and gate replay,
  initial/gate non-permit states, binding conflict, locked quota ambiguity, owner-aware recorded
  hydrate with missing plus all eight identity drifts, request/context/plan tamper, nested
  foreign/null poisoning, real unscoped legacy pipeline fallback, and locked-policy closure tamper
  rejection before Provider invocation.
- Three independent final read-only reviews accepted the exact five-path result with no remaining
  P1/P2. The enum naming remains the canonical route-manifest naming; the public facade, real
  Coordinator evidence, zero-effect replay/non-permit behavior, and C2D wiring boundary were all
  separately checked. One optional private unused-parameter cleanup was classified as non-blocking
  style work and deliberately not used to trigger another edit/test cycle.
- No whole test class/module/reactor test set, affected Shared Controller lane, E2E, database,
  browser, live Provider, or final joint full-validation cycle was run. Final joint usage remains
  `0/3`.
