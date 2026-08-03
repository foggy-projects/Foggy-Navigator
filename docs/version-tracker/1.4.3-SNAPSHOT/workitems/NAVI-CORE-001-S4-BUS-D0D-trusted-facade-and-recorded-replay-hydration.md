---
workitem: NAVI-CORE-001-S4-BUS-D0D
status: IMPLEMENTED_REVIEWED
date: 2026-08-04
baseline: 80a16d75
coordination_freeze: dc00da1
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: BUSINESS_CREATE_TRUSTED_FACADE_AND_REPLAY_HYDRATION
---

# NAVI-CORE-001 S4-BUS-D0D trusted facade and replay hydration

This slice composes the D0B read/fresh split, D0C once coordinator and D0C1 receipt-stable binding
behind one trusted Business MVC facade. It deliberately has no production HTTP caller; D0E owns
the additive Controller and SDK request-identity wiring.

## Exact paths

1. `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskCreateCommandFacade.java`
2. `business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskCreateCommandFacadeTest.java`
3. This work item.

## Trusted composition order

```text
trusted MVC auth before business read
→ canonical request identity
→ read-only prepareFreshCreate
→ exact plan tenant/actor ownership
→ exact ambient auth recheck
→ prepared-only PlanBinding
→ server authority issue
→ D0C execute
→ fresh passthrough OR owner-aware read-only recorded hydration
```

- The public method accepts only a nullable client request ID and the existing Form. Tenant and
  actor always come from the current `UserContext` plus exact servlet request attributes.
- Only POST `/api/v1/business-agent/tasks` is accepted for a tenant or super administrator.
  Bearer/query token uses `NAVIGATOR_JWT`; `X-API-Key` uses `NAVIGATOR_API_KEY`. Blank, mixed and
  foreign credential lanes fail before any business read.
- Actor fingerprinting uses only a lane-specific domain and the server-derived user ID. Raw
  credentials, request body, client context and token never enter the envelope or safe evidence.
- A missing request ID mints a distinct canonical UUID for each invocation. An explicitly supplied
  blank or invalid value fails; a valid value is trimmed/canonicalized and used identically as the
  client request, idempotency and correlation ID.

## Result boundary

- Fresh `Executed` returns the exact D0C-validated DTO, including its one-time token, without copy
  or reissue.
- Recorded replay reads the Task by current tenant, recovers the durable context through the
  prepared identity/input, and reads the exact Business Session under its current grant.
- Task and Session stable identity/resource facts must match the prepared plan. Existing
  model-variant and Worker-ID trim-to-null semantics are reused. Mutable Task status/timestamps are
  returned as current values; mutable Session latest Task, Worker, Provider, status, timestamps and
  client context are neither trusted nor copied.
- The recorded DTO is newly projected from the Task plus the durable Session context and always
  has `taskScopedToken=null`.

## Focused validation

- Affected production compile: PASS.
- New focused facade test: PASS (`8/8`, failures/errors/skips all zero), covering trusted credential
  lanes and rejection matrix, request identity, plan/auth drift, fresh passthrough, recorded
  hydration, mutable Session exclusion, stable Task/Session drift and token-null replay.
- Three independent final read-only reviews: ACCEPT with no P1/P2. They confirmed the trusted MVC
  and server-authority boundary, once/replay ordering, stable hydration facts, token semantics,
  exact three-path scope and readiness for D0E.
- D0A-D0C1 evidence is reused without rerunning unchanged tests.
- No module/reactor, E2E, live Provider/runtime or final joint full validation is run. Final joint
  budget remains `0/3 consumed`.
- Tests use mocks and request-local fixtures only. No service/Worker or historical/existing
  business/runtime data is read or mutated.

## Deliberate non-scope and stop conditions

No existing service/coordinator/receipt, Controller, Form/DTO, SDK, schema, POM, addon or
session-module path is changed. Stop and replan if a fourth path is required; tenant/user becomes a
caller parameter; a second authority/ledger, raw-credential fingerprint, facade transaction or
token operation appears; recorded replay trusts mutable Session fields or returns a token; HTTP or
SDK is wired early; historical data or `BOOT-INF/` is touched.
