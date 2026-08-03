---
workitem: NAVI-CORE-001-S4-03A1
status: IMPLEMENTED_REVIEWED
date: 2026-08-04
baseline: 3248ea00
coordination_freeze: ddc6506
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: TRUSTED_NAVIGATOR_COMMAND_INGRESS_AUTHORITY
---

# NAVI-CORE-001 S4-03A1 Trusted Navigator command ingress authority

This equivalence slice extracts the existing trusted Navigator MVC credential, ambient identity,
route/source, principal fingerprint, and client request identity checks from the Task CREATE
factory. The process-local result is immutable and content-free. It contains no servlet request,
raw credential, Task payload, Provider route, Worker identity, or execution capability.

The existing Task/UI, Agent/A2A, and Session Forward CREATE lanes retain their prior behavior. The
factory still owns CREATE target/effect composition and dispatches through the same command
coordinator. The extracted authority introduces no new command caller and does not yet wire a
termination endpoint.

## Changed paths

- `session-module/src/main/java/com/foggy/navigator/session/service/TrustedNavigatorCommandIngressAuthority.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TrustedNavigatorTaskCreateCommandFactory.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/TrustedNavigatorCommandIngressAuthorityTest.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/TrustedNavigatorTaskCreateCommandFactoryTest.java`
- this work item

## Validation evidence

- The affected production compile passed in `17.617 s`.
- The final focused authority class and nine selected CREATE equivalence selectors passed `14/14`
  with zero failure, error, or skip in `31.004 s`.
- Initial review found that a null resolve context had changed from the existing safe security code
  to an NPE. The final implementation restores the exact validation order and
  `TRUSTED_NAVIGATOR_RESOLVE_CONTEXT_CONFLICT`, with direct and Forward regression evidence.
- Three independent final read-only reviews accepted the exact five paths with no remaining P1/P2.
  The final API also owns its finite route/source descriptors and labels routing probes and the
  CREATE-specific blank-ID policy so later command families cannot mistake them for authorization.
- No whole class beyond the new focused class, whole module/reactor test suite, database, E2E,
  live Provider/runtime, or final joint full-validation cycle was run.

## Data and compatibility boundary

No service or Worker was started. No business/runtime or historical data was read or mutated, and
no repair, backfill, replay, reconciliation, cleanup, or deletion was performed. Public HTTP,
schema, SPI, provider addons, Business/Shared/OpenAPI, SIM, TMS, and the user-owned `BOOT-INF/`
directory were not changed.
