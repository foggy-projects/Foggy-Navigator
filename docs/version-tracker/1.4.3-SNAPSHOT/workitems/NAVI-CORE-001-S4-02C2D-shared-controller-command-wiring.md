---
workitem: NAVI-CORE-001-S4-02C2D
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
baseline: d15d5025
prerequisite: NAVI-CORE-001-S4-02C2C@d15d5025
coordination_freeze: 2450331
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: SHARED_CONTROLLER_CANONICAL_CREATE_COMMAND_WIRING
---

# NAVI-CORE-001 S4-02C2D Shared Controller command wiring

This slice wires only `POST /api/v1/shared/ask` to the accepted scoped Shared create command.
The route, `X-Sharing-Key`, request body, `RX<A2aTask>` response and other Shared APIs remain
compatible. An optional `X-Navigator-Client-Request-Id` is additive; omitted IDs are new per
ingress and only a supplied canonical ID supports cross-HTTP replay.

The raw Sharing Key enters the adapter mint call once and is then discarded. Controller identity
comes only from safe scope references and its authority-backed `SHARED_API` context. Ambient
Navigator `UserContext` is cleared for the full Shared operation and exactly restored in `finally`,
so an optional JWT/API key cannot become Shared owner, tenant or Agent authority.

The request carries question/context fields, `firstMsg`, and only a nonblank explicit body
`systemPrompt`; it carries no preflight/default policy and no `maxTurns`. After a valid effect
permit the adapter consumes locked quota and internally projects the latest policy. A typed nested
admission rejection preserves the legacy `RX.failA` response for Sharing Key business rejection
without catching unrelated Provider, plan or Coordinator `IllegalArgumentException` values.

Consultation persistence moves into fresh completion after Provider and canonical Task/context
persistence but before receipt result recording. It stays best-effort and uses only scope safe
identity plus the fresh durable DTO. Replay and every non-fresh outcome run no consultation write.

## Changed paths

- `session-module/src/main/java/com/foggy/navigator/session/controller/SharedAskController.java`
- `session-module/src/test/java/com/foggy/navigator/session/controller/SharedAskControllerTest.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/ScopedSharedTaskCreateCommandAdapter.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/ScopedSharedTaskCreateCommandAdapterTest.java`
- this work item

No SharingKey service/repository, Coordinator, Facade, pipeline, Shared Form/DTO/task controller,
authorization catalog, SPI, schema, migration, POM, addon, SDK, SIM or TMS path is changed.

## Validation boundary

Focused selectors cover Controller field/response compatibility, explicit-only pre-permit
metadata, typed admission versus Provider error mapping, Agent/readiness failure, ambient context
restoration, consultation best-effort behavior and replay zero-write behavior. One composition
selector uses the real `DefaultAgentSubmitPipeline`, scoped Shared adapter and create Coordinator
for two calls with the same canonical ID, proving Provider, quota and consultation each occur once
and the recorded replay returns the same durable Task without repeating them. The affected lane is
bounded to these two test classes and only directly necessary pipeline evidence. No whole module or
reactor, database, E2E, browser, live Provider or final joint full-validation cycle is included.

## Implementation evidence

- Affected production compilation passed in `15.835 s`.
- Nine exact focused selectors passed (`9/9`) in `27.015 s`: seven Controller selectors plus the
  real Coordinator/scoped-adapter/default-pipeline replay composition selector and the typed
  locked-admission selector.
- The single bounded affected lane passed in `18.483 s`: `SharedAskControllerTest` (`7/7`),
  `ScopedSharedTaskCreateCommandAdapterTest` (`9/9`) and `DefaultAgentSubmitPipelineTest` (`4/4`),
  for `20/20` tests with zero failure, error or skip.
- Three independent read-only reviews accepted the exact five paths with no P1/P2 finding. They
  separately checked Shared authority and ambient-context isolation, public Facade compatibility
  and error mapping, and zero duplicate quota/Provider/consultation effect on recorded replay.
- No whole-module, whole-reactor, E2E, browser, live-Provider or final joint full validation was
  run. The authorized final joint validation budget remains `0/3` consumed.

## Data and rollback boundary

Tests use mocks and in-memory state only. No service or Worker is started and no business/runtime
or historical data is read or mutated. No repair, backfill, data replay, reconciliation or deletion
is authorized. Rollback is one exact five-path commit revert and requires no data action.
