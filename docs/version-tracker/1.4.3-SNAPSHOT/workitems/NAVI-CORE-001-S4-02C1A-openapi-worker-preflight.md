---
workitem: NAVI-CORE-001-S4-02C1A
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
baseline: 93b02b8f
prerequisite: NAVI-CORE-001-S4-02B3@93b02b8f
coordination_freeze: 50c2126
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: READ_ONLY_OPENAPI_WORKER_PREFLIGHT
---

# NAVI-CORE-001 S4-02C1A OpenAPI Worker preflight

This slice separates exact physical-Worker resolution from task-scoped token and lease issuance.
The new preflight reuses the existing server-owned ClientApp, upstream-user, Skill, model and
Worker visibility checks, but performs no token lifecycle operation, entity save, Worker launch or
Provider call. Its only output-side mutation is the caller-owned in-memory launch request, allowing
the later canonical command plan to bind the exact Worker before preparing a once-effect receipt.

`prepareOpenApiTaskScopedToken` remains a compatibility overload for existing callers and is not a
preflight authority boundary. The canonical OpenAPI command lane must call the separately named
`prepareOpenApiTaskScopedTokenAfterPreflight` with the service-minted immutable preflight. That
required lane rejects mutable selected-Worker/lease/token tampering, resolves the safe binding
again, and compares Worker, model, pool, backend and upstream-system scope exactly before generating
a lease or issuing a token. Selection or authority drift therefore fails before any durable or
Provider effect.

## Changed paths

- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskService.java`
- `business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskServiceTest.java`
- this work item

No launcher SPI/provider, repository/entity/schema, addon, controller, command coordinator,
receipt, SDK or public HTTP DTO is changed.

## Validation boundary

Production compilation passed in `16.423 s`. The final exact focused set passed `7/7`, failures `0`,
errors `0`, skipped `0`, `BUILD SUCCESS` in `28.966 s`. It covers read-only pooled selection,
matching preflight-to-issuance, Worker drift, mutable selected-Worker tampering, safe
model/upstream binding drift, missing/reserved preflight capability fields and the no-launcher
compatibility path. Two independent read-only delta reviews concluded `ACCEPT / NO REMAINING P1`;
the first authority review's mutable-request finding was fixed by the service-minted immutable
preflight and required lane before acceptance. No full class/module/reactor, E2E, live/provider or
final joint validation belongs to C1A.

## Data and rollback boundary

Tests use mocks only. No service or Worker is started and no business/runtime or historical data is
read or mutated. No repair, backfill, replay, reconciliation or deletion is authorized. Rollback is
one three-path commit revert and requires no data action.
