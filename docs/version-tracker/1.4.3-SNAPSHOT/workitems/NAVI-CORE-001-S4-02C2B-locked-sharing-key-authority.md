---
workitem: NAVI-CORE-001-S4-02C2B
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
baseline: 9d13ffb6
prerequisite: NAVI-CORE-001-S4-02C2A@9d13ffb6
coordination_freeze: 98a27d7
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: LOCKED_SHARING_KEY_ASK_AUTHORITY
---

# NAVI-CORE-001 S4-02C2B locked SharingKey ask authority

This slice establishes the server-owned authority used by the later Shared command adapter. The
plain Sharing Key is accepted only by a read-only mint operation, validated with the existing
enabled, expiry, operation, owner, and tenant semantics, and then discarded. The resulting
immutable authority carries only the stable row ID, owner, tenant, Agent, and a provisional policy;
its constructor is private, its type and operations are package-private, and its string form is
content-redacted. The later command adapter remains the only intended holder outside this service.

Fresh consumption accepts only an authority minted by the same service instance. It locks the
SharingKey row by primary key, rechecks row identity, owner, Agent, enabled, expiry, the fixed `ask`
operation, and the owner's current tenant, then uses the existing day rollover and quota update.
The returned immutable policy contains the latest locked `maxTurns` and system prompt so later
wiring never executes with a stale preflight policy. No raw key or JPA entity crosses this seam.

## Changed paths

- `session-module/src/main/java/com/foggy/navigator/session/repository/SharingKeyRepository.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/SharingKeyService.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/SharingKeyServiceTest.java`
- this work item

No entity, schema, migration, Controller, command adapter, authorization enum, SDK, addon, POM, or
runtime configuration is changed.

## Validation boundary

Focused tests cover read-only minting even when quota is exhausted, invalid capability/owner/tenant
facts, private immutable content-redacted values, exact pessimistic-lock repository metadata,
foreign-service/null authority, locked row absence, identity and tenant drift, locked
enabled/expiry/operation/quota rejection, sequential last-slot behavior over the same mocked
locked-row state, day rollover, one save, and the latest policy snapshot. This proves the lock
contract and sequential behavior; it does not claim a live two-transaction database race. Only
affected production compile and exact focused selectors are allowed here—no
whole class/module/reactor, database integration, E2E, live/provider, or final joint cycle.

The affected production compile passed with `BUILD SUCCESS` in `10.697 s`. The initial six exact
selectors passed `6/6`, failures `0`, errors `0`, skipped `0`, with `BUILD SUCCESS` in `30.040 s`.
They covered exact lock metadata, read-only minting, operation/owner/tenant rejection, serialized
last-slot behavior, locked identity/tenant/operation drift, disabled/expired recheck, day rollover,
one save, and latest locked policy.

Three independent read-only reviews found no production lock, transaction, quota, compatibility,
or data-boundary defect. They requested two bounded hardenings: reduce the capability holder from a
public API to the `session.service` package, and directly prove foreign/null issuer, missing locked
row, row-ID/Agent drift, exact JPQL, and `@Param("id")`. Those changes touched only the existing
production service and test paths. The final same six selectors passed `6/6`, failures `0`, errors
`0`, skipped `0`, with production and test compilation plus `BUILD SUCCESS` in `34.979 s`. All three
finding owners then returned `ACCEPT / NO REMAINING P1/P2`; no broader test set was rerun.

## Compatibility, data, and rollback

The existing `expiresAt.isBefore(now)` boundary, blank/null allowed-operations allow-all rule,
error messages, and day rollover behavior remain unchanged. Tests use mocks only. No service or
Worker is started, no business/runtime or historical data is read or mutated, and no repair,
backfill, replay, reconciliation, or deletion is authorized. Rollback is one four-path commit
revert and requires no data action.
