---
workitem: NAVI-CORE-001-S4-02B4
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
baseline: 4a1eaf81
prerequisite: NAVI-CORE-001-S4-02D0@4a1eaf81
coordination_freeze: 4e71002
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: NAVIGATOR_API_KEY_TRUSTED_CREATE
---

# NAVI-CORE-001 S4-02B4 Navigator API-key trusted create

This additive slice gives the existing Navigator user `X-API-Key` authentication mechanism its
own canonical credential lane. Existing Task/UI and Agent/A2A creates authenticated by a valid API
key now use the same trusted factory, owner-proven plan, once receipt and provider-effect gate as
their JWT equivalents.

The principal remains `NAVIGATOR_USER`; an API key is never represented as a JWT, ClientApp,
upstream-admin, task-token or Worker credential. The command fingerprint uses only the
server-derived user identity and a lane-specific domain. Raw credential material is never copied,
hashed into the envelope, logged or persisted.

## Changed paths

- `navigator-common/src/main/java/com/foggy/navigator/common/authorization/AuthorizationCredentialLane.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TrustedNavigatorTaskCreateCommandFactory.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/TrustedNavigatorTaskCreateCommandFactoryTest.java`
- this work item

## Compatibility and validation boundary

JWT bearer/query behavior and fingerprints remain unchanged. `AGENT_ASK + UI`, OpenAPI and Shared
routes keep their existing owners. Mixed credentials and invalid API keys fail before plan,
receipt or Provider work. Focused verification is limited to exact factory selectors followed by
at most one run of that same test class; no whole module/reactor, database, E2E, live Provider or
final joint validation is included.

## Data and rollback boundary

Tests use the real interceptor with mocked identity storage and otherwise use mocks only. No
service or Worker is started and no business/runtime or historical data is read or mutated.
Rollback is one exact four-path commit revert and requires no data action.

## Implementation evidence

- Production compile: `mvn -pl session-module -am -DskipTests compile` — PASS.
- Frozen focused selectors: 6 tests; the first run found one test-only expectation using the raw
  tenant ID instead of the pre-existing canonical tenant reference. The assertion was corrected
  without changing production behavior; the failed selector then passed.
- Affected factory lane: `TrustedNavigatorTaskCreateCommandFactoryTest` — 11 tests passed, zero
  failures/errors/skips.
- Three independent read-only reviews completed. One review found a fail-open edge for an explicit
  empty/blank API-key header; header presence now claims the canonical stage and rejects blank
  material before plan/receipt/Provider. Its exact selector passed and the finding was re-reviewed
  closed. Final review result: no open P1/P2.
- No database, runtime, Provider, E2E, whole-module or final joint validation was run. Final joint
  full-cycle budget remains `0/3`.
