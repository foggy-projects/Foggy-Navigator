---
doc_role: acceptance-record
doc_purpose: Stage 7A/7B runtime token injection, resume listener hardening, and Stage 7C runtime token hardening acceptance.
acceptance_scope: feature
version: 1.1.3-SNAPSHOT
target: Stage 7A Runtime Task Token Injection + Stage 7B Resume Event Listener Binding Hardening + Stage 7C Runtime Token Hardening
status: signed-off
decision: accepted-with-risks
signed_off_by: Codex reviewer
signed_off_at: 2026-05-03
reviewed_by: Codex reviewer
blocking_items: []
follow_up_required: yes
evidence_count: 47
implementation_date: 2026-05-03
---

# Feature Acceptance – Stage 7 Runtime Token Injection & Hardening

## Background

Stage 6 was accepted with two open risks:

1. `task_scoped_token` was exposed in LLM-visible tool schemas, allowing the LLM to see, log, or manipulate it.
2. `LanggraphWorkerResumeEventListener` only validated `taskId/sessionId` — tenant binding was not checked even though `WorkerGatewayResumeEvent` carries `tenantId`.

Stage 7A closes token exposure at the tool schema and resolver level, and the production execution path now injects the task scoped token into `runtimeContext` before `BuiltInTool.execute`. Stage 7B closes tenant binding validation for the resume listener. Stage 7C closes runtime token expiry alignment, tenant fallback removal, and exact task lookup fail-closed behavior.

## Acceptance Basis

- [08-implementation-plan.md](../08-implementation-plan.md)
- [stage-6-e2e-sample-acceptance.md](stage-6-e2e-sample-acceptance.md) — prior risks now addressed
- [ToolExecutionRequest.java](../../../../agent-framework/src/main/java/com/foggy/navigator/agent/framework/tool/ToolExecutionRequest.java)
- [TaskScopedTokenResolver.java](../../../../addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/tool/TaskScopedTokenResolver.java)
- [LanggraphWorkerResumeEventListener.java](../../../../addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphWorkerResumeEventListener.java)

---

## Stage 7A — Runtime Task Token Injection

### Approach

- Added `Map<String, Object> runtimeContext` to `ToolExecutionRequest`.
  - Documented as framework-injected, NOT LLM-visible.
- Created `TaskScopedTokenResolver` utility:
  - Reads token from `request.getRuntimeContext().get("task_scoped_token")` only.
  - Returns `null` (not empty string) if absent or blank.
  - Single constant key: `TOKEN_KEY = "task_scoped_token"`.
- Updated all 4 tools (`ListBusinessFunctionsTool`, `GetBusinessFunctionSchemaTool`, `InvokeBusinessFunctionTool`, `RunBusinessScriptTool`):
  - `getParameters()` no longer declares `task_scoped_token` as a property.
  - `getParameters().required` no longer contains `task_scoped_token`.
  - `execute()` reads token exclusively from `runtimeContext` via `TaskScopedTokenResolver`.
  - LLM-supplied `task_scoped_token` in `parameters` is silently ignored (not used as fallback).
  - Missing runtime token → `MISSING_TOKEN` error; gateway is not called.

### Stage 7A Checklist

- [x] `task_scoped_token` not in `getParameters()` properties for any tool.
- [x] `task_scoped_token` not in `getParameters()` required list for any tool.
- [x] Each tool reads token from `runtimeContext`, not `parameters`.
- [x] Missing runtime token → `MISSING_TOKEN` fail-closed, no gateway call.
- [x] LLM-supplied token in parameters is ignored; gateway receives runtime token.
- [x] `RunBusinessScriptTool` still best-effort reports Java tool message via runtime token.
- [x] `ToolExecutionRequest` backward-compatible (new field is optional/null-safe).
- [x] Production runtime injection path populates `runtimeContext.task_scoped_token` before calling `BuiltInTool.execute(request)`.
- [x] `DefaultAgentInvoker` provider exception does not fall back to LLM-controlled parameters.

---

## Stage 7B — Resume Event Listener Binding Hardening

### Approach

- `LanggraphWorkerResumeEventListener` now validates `tenantId` from the event:
  - If `event.getTenantId()` is present AND `task.getTenantId()` is absent → **fail closed**, no dispatch.
  - If both present and mismatch → **fail closed**, no dispatch.
  - If event has no `tenantId` → backward-compatible pass-through with debug log (future hardening item).
- Session validation unchanged (existing).
- No new fields added to `LanggraphTaskEntity` (`tenantId` was already present).
- `clientAppId`, `functionId`, `inputHash` not on `LanggraphTaskEntity` — noted in code as future hardening.

### Stage 7B Checklist

- [x] Tenant mismatch → rejected; `workerService` not called.
- [x] Event has tenantId but task has none → rejected fail-closed.
- [x] Matching tenantId passes through to dispatch.
- [x] Event without tenantId → backward-compatible pass-through (logged).
- [x] Session mismatch still rejected (unchanged).
- [x] Missing task still ignored (unchanged).
- [x] Missing workerId still ignored (unchanged).

---

## Stage 7C — Runtime Token Hardening

### Approach

- `BusinessAgentTaskScopedTokenRuntimeStore.registerToken(...)` now accepts explicit `expiresAt` matching the DB token expiry.
- `BusinessAgentTaskService.createTask(...)` uses one `expiresAt` value for both `BusinessTaskScopedTokenEntity` and the runtime store registration.
- `ToolRuntimeContextRequest` now has optional `taskId`.
- `BusinessAgentToolRuntimeContextProvider` no longer falls back from missing `tenantId` to `userId`.

### Stage 7C Checklist

- [x] Runtime token memory expiry is sourced from the same `expiresAt` as the persisted DB token.
- [x] Missing tenantId returns empty runtime context and does not query the token store with userId.
- [x] Runtime store can register and resolve task-scoped exact keys.
- [x] A provided taskId with no exact runtime token match fails closed instead of falling back to session scope.
- [x] Stage 7C documentation records runtime taskId propagation as a non-blocking future hardening item.

---

## Test Evidence

### New / Updated Tests

| Test Class | Count | What's new |
|---|---|---|
| `ListBusinessFunctionsToolTest` | 7 | schema/required checks, runtimeContext success, missing-token fail-closed, malicious-param-ignored |
| `GetBusinessFunctionSchemaToolTest` | 7 | schema/required checks, runtimeContext success, missing-token fail-closed, malicious-param-ignored |
| `InvokeBusinessFunctionToolTest` | 9 | schema/required checks, runtimeContext success, missing-token fail-closed, malicious-param-ignored, tool-message reporting |
| `RunBusinessScriptToolTest` | 8 | schema/required checks, runtimeContext success, missing-token fail-closed, malicious-param-ignored |
| `LanggraphWorkerResumeEventListenerTest` | 7 | updated success (with tenantId), backward-compat no-tenantId, tenant mismatch, missing task tenant (fail-closed) |
| `DefaultAgentInvokerRuntimeContextTest` | 2 | provider injection into real tool execution request, provider exception does not fall back to params |
| `BusinessAgentTaskScopedTokenRuntimeStoreTest` | 4 | tenant/session keyed token lookup and miss cases |
| `BusinessAgentToolRuntimeContextProviderTest` | 3 | provider injects runtime token from trusted store and returns empty when absent |
| `BusinessAgentTaskScopedTokenRuntimeStoreTest` (Stage 7C update) | 7 | explicit expiresAt, task key registration, exact task miss fail-closed, session fallback only when taskId is absent |

### Commands and Results

```
mvn test -pl agent-framework -am
  → Tests run: 210, Failures: 0, Errors: 0

mvn test -pl business-agent-module -am
  → Tests run: 135, Failures: 0, Errors: 0

mvn test -pl addons/langgraph-biz-worker -am
  → Tests run: 62, Failures: 0, Errors: 0  (15 new + 47 existing)

mvn compile -pl launcher -am -DskipTests
  → BUILD SUCCESS
```

### Static Checks

- `task_scoped_token` in production tool source: only appears in error message strings and `TOKEN_KEY` constant — NOT in any tool schema property definition. ✅
- No `UpstreamApp` / `upstream_app` in `addons/langgraph-biz-worker`. ✅

---

## Resolved Items

1. **Stage 7A runtime injection is wired into the production execution path.**
   - `DefaultAgentInvoker.findAndExecuteTool(...)` now builds a `ToolRuntimeContextRequest`, invokes all configured `ToolRuntimeContextProvider` beans, and sets the merged map on `ToolExecutionRequest.runtimeContext`.
   - `BusinessAgentTaskService.createTask(...)` registers the generated plain task scoped token into `BusinessAgentTaskScopedTokenRuntimeStore`.
   - `BusinessAgentToolRuntimeContextProvider` resolves the token from the runtime store and returns it under `ToolRuntimeContextKeys.TASK_SCOPED_TOKEN`.
   - `TaskScopedTokenResolver` still reads only from `runtimeContext`, so LLM-controlled `parameters` remain ignored.

---



## Remaining Risks / Open Items

1. **Real adapter invocation** — `invokeBusinessFunction` still returns `ADAPTER_NOT_IMPLEMENTED` for non-approval-required paths. Stage 7C/7D.
2. **Tool message persistence** — `reportToolMessage` logs at INFO; no persistent audit storage. Separate future stage.
3. **Resume binding: clientAppId/functionId/inputHash** — These cannot be validated in `LanggraphWorkerResumeEventListener` because `LanggraphTaskEntity` does not carry them. Future hardening if `LanggraphTaskEntity` is extended.
4. **Event tenantId absent** — Backward-compatible pass-through is logged but not blocked. This could be promoted to fail-closed once all callers populate tenantId in events.

*Note: Risk 5 (Runtime taskId propagation) was completely resolved in Stage 7D.*

## Final Decision

**Accepted.** Stage 7A now has a trusted production injection path for `runtimeContext.task_scoped_token`, and Stage 7B tenant binding validation is acceptable. Stage 7C successfully closed the remaining fail-closed availability / hardening risks within its implemented surface. Stage 7D propagated `Message.taskId` to the runtime context, ensuring the exact task binding constraint is used in production. The business execution chain is secure.
