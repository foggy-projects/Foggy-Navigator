---
workitem: NAVI-CORE-001-S4-BUS-D0E1
status: IMPLEMENTED_REVIEWED
date: 2026-08-04
baseline: 78c863bc
coordination_freeze: bc8d7ae
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: BUSINESS_CREATE_HTTP_CANONICAL_WIRING
---

# NAVI-CORE-001 S4-BUS-D0E1 HTTP canonical wiring

This provider-side slice makes D0D the only POST mutation path for Business Task creation. It
preserves the existing route, request body, response DTO and role while exposing the standard
optional request-identity header. SDK wiring remains blocked for D0E2.

## Exact paths

1. `business-agent-module/src/main/java/com/foggy/navigator/business/agent/controller/BusinessAgentTaskController.java`
2. `business-agent-module/src/test/java/com/foggy/navigator/business/agent/controller/BusinessAgentTaskControllerTest.java`
3. `business-agent-module/src/test/java/com/foggy/navigator/business/agent/controller/BizWorkerControlPlaneAuthorizationTest.java`
4. This work item.

## HTTP boundary

- POST `/api/v1/business-agent/tasks` remains guarded by `TENANT_ADMIN`, accepts the same
  `CreateBusinessAgentTaskForm`, and returns the same naked `CreatedBusinessAgentTaskDTO`.
- The optional header is `X-Navigator-Client-Request-Id`. The Controller passes its raw nullable
  value and the deserialized Form directly to `BusinessAgentTaskCreateCommandFacade`.
- The Controller never trims, parses, canonicalizes or generates an ID. D0D remains the only
  authority: absent means a newly minted ID; explicit blank or malformed input fails before any
  business read.
- Tenant and actor are not accepted as create method parameters. D0D obtains and exactly rechecks
  both from `UserContext` and the AuthInterceptor request attributes.
- The legacy `BusinessAgentTaskService.createTask` is no longer reachable from POST. The service
  remains injected only for the existing Task and Session read endpoints, which are unchanged.

## Focused validation

- Affected production compile: PASS.
- Controller focused test: PASS (`3/3`, failures/errors/skips all zero), covering explicit, absent
  and blank raw header delegation, response shape, legacy create exclusion and unchanged GET reads.
- Exact controller authorization/header reflection selector: PASS (`1/1`).
- Independent final read-only reviews: PASS (`3/3 ACCEPT`, no P1/P2). The reviews independently
  confirmed the exact four-path boundary, unique D0D production mutation path, raw optional header
  delegation, unchanged role/wire/read routes, and readiness to unlock D0E2.
- D0A-D0D evidence is reused without rerunning unchanged tests.
- No module/reactor, SDK, TMS, E2E, live runtime or final joint full validation is run. Final joint
  budget remains `0/3 consumed`.
- Tests use mocks and request-local fixtures only. No service/Worker or historical/existing data is
  read or mutated.

## Stop conditions

Stop and replan if a fifth path is required; POST retains a legacy create branch; the Controller
interprets or mints request identity; route/body/response/role or either GET changes; tenant/actor
becomes caller-controlled; D0D/service/DTO/Form/SDK/POM is changed; historical data or `BOOT-INF/`
is touched.
