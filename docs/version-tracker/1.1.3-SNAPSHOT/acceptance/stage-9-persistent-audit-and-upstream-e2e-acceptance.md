---
title: Stage 9 Persistent Audit + Upstream E2E Acceptance
date: 2026-05-04
status: accepted
---

# Stage 9 Acceptance Record

## Context
Stage 9 introduces persistent runtime audit for business function lifecycle events and adds an E2E test that validates the REST adapter against a real local mocked upstream HTTP service.

## 1. Persistent Audit Model

### New Entity: `BusinessFunctionRuntimeAuditEntity`
- Table: `business_function_runtime_audit`
- Fields: `id`, `auditId`, `tenantId`, `clientAppId`, `upstreamUserId`, `taskId`, `sessionId`, `workerPoolId`, `skillId`, `functionId`, `functionVersion`, `suspendId`, `eventType`, `status`, `inputHash`, `outputHash`, `errorCode`, `errorMessage` (bounded 500), `durationMs`, `createdAt`.
- Does NOT store: plain task-scoped token, `adapterConfigJson`, `manifestJson`.
- Verified by reflection test `BusinessFunctionRuntimeAuditDTOSecurityTest`.

### New Repository: `BusinessFunctionRuntimeAuditRepository`
- Tenant-scoped queries by taskId, sessionId, and suspendId.

### New DTO: `BusinessFunctionRuntimeAuditDTO`
- Maps entity fields. Does not expose secrets.

## 2. Audit Service: `BusinessFunctionRuntimeAuditService`

Event types:
| Event Type | Trigger |
|---|---|
| INVOKE_STARTED | After authorization, before adapter execution |
| INVOKE_SUCCESS | Adapter returns SUCCESS |
| INVOKE_SUSPENDED | Approval-required function creates suspension |
| INVOKE_FAILED | Adapter throws, config error, or validation failure |
| TOOL_MESSAGE | Worker reports tool execution message |
| RESUME_REQUESTED | Control-plane calls resumeSuspension, before binding validation |
| RESUME_DISPATCHED | Resume event published successfully and entity marked RESUME_DISPATCHED |
| RESUME_FAILED | Any exception thrown during resume validation (expired, mismatched binding, etc.) or event publishing |

**Best-effort policy**: All audit writes catch exceptions and log warnings. They never block the primary execution result. Security validation remains fail-closed and occurs before any audit call. `WorkerGatewayResumeEvent` publishing is wrapped in a try/catch, guaranteeing `RESUME_FAILED` auditing if listeners throw exceptions.

## 3. Integration Points

### WorkerGatewayService
- `invokeBusinessFunction(...)`: INVOKE_STARTED → INVOKE_SUCCESS/INVOKE_SUSPENDED/INVOKE_FAILED
- `reportToolMessage(...)`: TOOL_MESSAGE (changed from `@Transactional(readOnly = true)` to `@Transactional`)

### BusinessFunctionSuspensionService
- `resumeSuspension(...)`: RESUME_REQUESTED → RESUME_DISPATCHED; RESUME_FAILED on binding rejection

## 4. Control-Plane Read API

Not added in this stage. The audit service exposes `findByTenantIdAndTaskId` and `findByTenantIdAndSuspendId` for internal test queries. A controller endpoint is documented as future work.

## 5. Upstream Mock E2E Verification

**Did an upstream mock E2E test exist before this work?** No. The existing `BusinessAgentE2ESampleTest` only tested the approval-suspension lifecycle with `LocalEchoBusinessFunctionAdapterInvoker`.

**New test: `RestAdapterUpstreamE2ETest`**
- Starts a JDK `HttpServer` on a random local port.
- Configures `StandardEnvironment` with `foggy.navigator.business.agent.upstreams.test-tms.url`.
- Imports a REST business function manifest with `upstream_ref: "test-tms"`.
- Full grant chain: ClientApp, model, user, skill, function grants, task.
- Invokes through `WorkerGatewayService.invokeBusinessFunction(...)`.
- Asserts: upstream received `POST /api/orders` with correct headers.
- Asserts: gateway returns `STATUS_SUCCESS` with `{"result":"accepted","orderId":"ORD-9001"}`.
- Asserts: audit repository received at least `INVOKE_STARTED` and `INVOKE_SUCCESS` records.
- Test is deterministic and local-only.

**New test: `BusinessFunctionRuntimeAuditRepositoryTest`**
- `@DataJpaTest` using an embedded H2 database.
- Validates the `BusinessFunctionRuntimeAuditRepository` interacts with the physical database schema and `business_function_runtime_audit` table.
- Covers tenant/task/session queries correctly.

## 6. Test Execution

```powershell
mvn test -pl business-agent-module -am
# Tests run: 176, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS

mvn test -pl addons/langgraph-biz-worker -am
# Tests run: 62, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS

mvn compile -pl launcher -am -DskipTests
# BUILD SUCCESS (19/19 modules)
```

## 7. Security Verification

- `grep` confirms `BusinessFunctionRuntimeAuditDTO` and `BusinessFunctionRuntimeAuditEntity` do not contain `adapterConfigJson`, `manifestJson`, or `task_scoped_token`.
- `BusinessFunctionRuntimeAuditDTOSecurityTest` uses reflection to verify the same.
- `errorMessage` is bounded at 500 characters.

## 8. Remaining Risks

1. **No control-plane read API**: Audit records are queryable in-service but not exposed via REST endpoint. Future stage can add a `GET /api/v1/business-agent/runtime-audits?taskId=...` controller.
2. **Best-effort semantics**: Audit write failures are silently absorbed. If the database is unavailable, audit records may be lost but the primary function invocation flow is unaffected.
3. **No circuit breaker on REST adapter**: The REST adapter uses Spring RestTemplate defaults. High-latency upstreams may cause timeout issues; configurable timeouts are a hardening item.

## Final Decision

**Accepted.** The persistent audit loop provides durable traceability for all function lifecycle events. The new upstream mock E2E test validates the REST adapter against a real HTTP server. All tests pass and no secrets are exposed.
