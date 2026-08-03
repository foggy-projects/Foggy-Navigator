---
workitem: NAVI-CORE-001-S4-02B2
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
baseline: 4b40ef75
prerequisite: NAVI-CORE-001-S4-02B1A@4b40ef75
coordination_freeze: 4859f1f
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: TRUSTED_NAVIGATOR_JWT_CREATE_COMMAND_FACTORY
---

# NAVI-CORE-001 S4-02B2 trusted create command factory

This slice installs one server-owned command factory as the penultimate Agent submit pipeline
stage. It accepts only a trusted Navigator MVC JWT create lane, reuses the B1 canonical execution
plan and the S4-02A once-effect coordinator, and leaves API-key, OpenAPI, Shared, System and
non-servlet calls on the existing terminal stage.

For the supported Task-UI and Agent-A2A pairs, the factory proves a JWT lane from the conjunction of the raw AuthInterceptor credential
precedence, exact MVC method/route, AuthInterceptor request attributes, `UserContext`, resolve
context and final plan owner/tenant. It never accepts caller-built envelopes or authorization
decisions. A strict UUID is canonicalized or minted once and is used only as the command request,
idempotency and correlation identity. It is not copied into prompt, message, metadata or Provider
parameters.

`Executed` results return the fresh DTO. `RecordedReplay` uses the opaque task reference only to
perform an owner-aware read and exact identity check; a missing or drifted durable Task fails closed
without repair or redispatch. The current UI-sourced Agent ask route is completely deferred before
new factory validation until B3 changes its server-owned source to A2A and moves participation
mutation after success/replay. This preserves its existing AuthInterceptor/legacy mixed-header
behavior for one bounded slice and avoids rejecting after the Controller has already mutated
participation.

## Changed paths

- `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/AgentTaskSubmitRequest.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TrustedNavigatorTaskCreateCommandFactory.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/TrustedNavigatorTaskCreateCommandFactoryTest.java`
- this work item

No Controller, AuthInterceptor, pipeline terminal stage, Coordinator, receipt service,
auto-configuration, POM, repository/entity/schema, addon or runtime configuration is changed.

## Compatibility and replay boundary

- `/api/v1/tasks` + `UI` enters the trusted `DIRECT/NAVIGATOR_UI` command ingress.
- `/api/v1/agents/{agentId}/ask` + `A2A` enters `A2A/NAVIGATOR_A2A`; the current `UI` pair is a
  bounded B2-to-B3 complete legacy defer, including mixed headers.
- Supported JWT candidates with invalid route, auth attributes, owner/tenant or mixed credential lanes fail
  before receipt or Provider effect and never fall through.
- API-key-only and non-trusted request sources execute the existing terminal path once.
- Replay requires the complete plan binding to remain stable. A fresh local-A2A request with no
  reusable context/session may mint a different binding on a later HTTP request; the same command
  ID then conflicts before a second Provider effect rather than promising header-only replay.

## Validation record

- Affected production compile: `mvn -pl session-module -am -DskipTests compile`, exit `0`,
  `BUILD SUCCESS` in `23.33 s`.
- After review deltas, the three impacted selectors passed `3/3`; the exact eight-selector focused
  command then passed `8/8`, failures `0`, errors `0`, skipped `0`, exit `0`,
  `BUILD SUCCESS` in `18.17 s`. It covers real `AuthInterceptor` Bearer/query proof,
  canonical/minted UUID and stable renewed-JWT binding, server-authority verification, fresh and
  read-only recorded results, missing/drifted durable Task rejection, receipt conflict/started/
  ambiguous no-fallback, API-key and foreign-source legacy compatibility, current UI Agent-ask
  complete defer including mixed headers, the repository's concrete management/runtime/task/
  Worker/TMS credential headers, route/attribute/context/plan drift, missing replay identity, and
  zero request-ID projection into the dispatch payload.
- Two independent post-delta reviews concluded `ACCEPT / NO REMAINING P1/P2`. Their initial reviews found
  incomplete foreign-credential coverage, nullable replay identity and a pre-B3 participation
  side-effect conflict; each was closed within the frozen five paths plus coordination freeze
  clarification `4859f1f` before the final exact selector run.
- No affected lane, full module/reactor, E2E, live/provider or final joint validation has run.

## Data and rollback boundary

No service or Worker was started and no business/runtime data was read or mutated. Tests are pure
mock/MVC-request fixtures. No repair, backfill, replay, reconciliation or deletion of historical
data occurred. Rollback is one source-and-test commit revert and requires no data action.
