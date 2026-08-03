---
workitem: NAVI-CORE-001-S4-02B3
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
baseline: 6f39aa7b
prerequisite: NAVI-CORE-001-S4-02B2@6f39aa7b
coordination_freeze: f986a36
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: NAVIGATOR_CREATE_INGRESS_REQUEST_ID_AND_POST_SUCCESS_PARTICIPATION
---

# NAVI-CORE-001 S4-02B3 Navigator create ingress

This slice completes the two trusted Navigator HTTP create adapters without changing their public
body or response contracts. Each accepts the optional `X-Navigator-Client-Request-Id` header and
copies it only to the B2 `AgentTaskSubmitRequest` carrier. The Task endpoint remains `UI`; Agent ask
is marked `A2A`, which activates the already-reviewed `A2A/NAVIGATOR_A2A` command factory lane.

Agent participation is now updated only after the submit pipeline returns a non-null Task and the
existing context-ID projection completes. Validation, binding, receipt, Provider or null-result
failures therefore leave the Session unchanged. A fresh result and a legitimate recorded replay
share the same idempotent post-success update.

## Changed paths

- `session-module/src/main/java/com/foggy/navigator/session/controller/TaskController.java`
- `session-module/src/main/java/com/foggy/navigator/session/controller/AgentDiscoveryController.java`
- `session-module/src/test/java/com/foggy/navigator/session/controller/TaskControllerTest.java`
- `session-module/src/test/java/com/foggy/navigator/session/controller/AgentDiscoveryControllerTest.java`
- this work item

No Factory, SPI, Facade, Coordinator, AuthInterceptor, addon, public DTO, POM, repository/entity or
schema is changed.

## Compatibility and authority boundary

- The header is not copied into `TaskDispatchRequest`, body, prompt, message, metadata, context,
  Provider parameters or response.
- Missing header remains absent at the Controller boundary so the B2 server factory mints it once.
  This does not promise automatic cross-HTTP deduplication or override the stable PlanBinding limit.
- JWT/API-key precedence, owner/tenant checks, Agent and Provider resolution, session metadata,
  context-ID handling, public RX/A2A/Task response and error propagation remain unchanged.
- Participation is not part of the command receipt and is never written from `catch` or `finally`.

## Validation record

- Six exact focused selectors passed `6/6`, failures `0`, errors `0`, skipped `0`, exit `0`,
  `BUILD SUCCESS` in `30.63 s`. They cover Task header present/absent carrier behavior, Agent A2A
  source and ordered post-success participation, submit conflict with zero Session mutation,
  repeated successful/replay-like result idempotency, and unowned-parent pre-mutation rejection.
- After focused acceptance, run the bounded affected session-create lane once with
  `TaskControllerTest`, `AgentDiscoveryControllerTest`,
  `TrustedNavigatorTaskCreateCommandFactoryTest` and `DefaultAgentSubmitPipelineTest`.
- The bounded affected session-create lane passed `52/52`, failures `0`, errors `0`, skipped `0`,
  exit `0`, `BUILD SUCCESS` in `18.87 s`.
- Two independent post-focused reviews concluded `ACCEPT / NO REMAINING P1/P2` for side-effect
  ordering and public/credential compatibility before the affected lane.
- No full module/reactor, E2E, live/provider or final joint validation is part of this slice.

## Data and rollback boundary

No service or Worker is started and no business/runtime or historical data is accessed or mutated.
Tests use mocks and in-memory request/entity fixtures. No repair, backfill, replay, reconciliation or
deletion is authorized. Rollback is one source-and-test commit revert and requires no data action.
