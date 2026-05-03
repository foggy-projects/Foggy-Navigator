---
title: Stage 8A Business Function Adapter Invocation Minimal Loop Acceptance
date: 2026-05-03
status: accepted
---

# Stage 8A Acceptance Record

## Context
Stage 8A replaces the `ADAPTER_NOT_IMPLEMENTED` placeholder for non-approval function invocations with a real adapter execution path, starting with a `LocalEchoBusinessFunctionAdapterInvoker`.

## Verification Steps Completed

1. **Adapter Interface & Result DTO**
   - Added `BusinessFunctionAdapterInvoker` and `BusinessFunctionAdapterResult`.
   - `WorkerGatewayInvokeResponseDTO` extended with `outputJson` and `STATUS_SUCCESS`.

2. **Local Echo Adapter Implementation**
   - Created `LocalEchoBusinessFunctionAdapterInvoker` that looks for `{"type":"echo"}` or `{"adapterType":"ECHO"}` in the function's `adapterConfigJson`.
   - Unsupported or missing config properly throws an `IllegalArgumentException` to ensure fail-closed behavior.
   - No broad exception catching masking bad configurations.

3. **Service Integration**
   - In `WorkerGatewayService.invokeBusinessFunction()`, if `approvalRequired` is `false`, the adapter is invoked.
   - Result is propagated back as `SUCCESS` with `outputJson`.
   - Non-success or null adapter results are rejected fail-closed instead of being wrapped as successful Gateway responses.
   - Approval paths remain untouched and continue to return `SUSPENDED`.

4. **Security & Governance**
   - Verified that Worker-facing DTOs (`WorkerGatewayFunctionSchemaDTO`, `WorkerGatewayInvokeResponseDTO`) do not contain fields for `adapterConfigJson` or `manifestJson`. This was verified both by reflection-based unit tests and `grep_search`.

## Test Execution

```powershell
mvn test -pl business-agent-module -am
mvn compile -pl launcher -am -DskipTests
```
**Results:** All tests passed (business-agent-module: 143 tests, agent-framework: 213 tests). Launcher compiled successfully.

## Remaining Risks / Future Work

1. **Outbound REST Adapter**: The current adapter is only a local echo. The real outbound REST adapter, handling external network requests and interpreting the full `manifestJson` routing rules, remains to be implemented in a future stage.
2. **Persistent Execution Audit**: Executions are currently not persistently audited (only logged via `log.info` in the adapter and message reporter).

## Final Decision

**Accepted.** The `ADAPTER_NOT_IMPLEMENTED` placeholder has been successfully replaced for the echo path. Fail-closed exception handling ensures invalid adapter configurations do not silently succeed. DTOs are verified safe from leaking internal adapter configurations.
