---
type: bug
bug_source: regression-found
version: 1.3.0-SNAPSHOT
ticket: BUG-021-follow-up-3
severity: major
status: closed
reproduction_status: confirmed
test_strategy: integration-test
automation_decision: required
owner: langgraph-biz-worker
---

# BUG Work Item

## Background

TMS upstream re-verified BUG-021 follow-up-2 after Navi rebuilt and restarted with `LanggraphBusinessFunctionResultMessageListener` included in the runtime jar.

The deterministic `business_function_result_message` was written back to the visible `lgt_*` task/context, and logs showed `LanggraphTaskService - Task failed: taskId=...`. However, the corresponding `langgraph_tasks` row still remained `RUNNING`, with `error_message = NULL` and unchanged `updated_at`.

## Reproduction

1. Run an approvalRequired business function path:
   - root skill -> child skill -> approvalRequired business function.
2. Approve the suspension.
3. Let adapter execution fail, for example with HTTP 401.
4. Confirm that `business_function_result_message` is published with `workerTaskId = lgt_*`.
5. Confirm listener logs call into `LanggraphTaskService.failTask`.
6. Query `langgraph_tasks` for the visible `lgt_*` task.

## Expected vs Actual

Expected:

- Adapter success result message terminalizes the visible `lgt_*` task as `COMPLETED`.
- Adapter failure result message terminalizes the visible `lgt_*` task as `FAILED`.
- `error_message`, `result_text`, `structured_output`, and `updated_at` are persisted as applicable.
- `session_tasks` projection follows the same terminal status.

Actual:

- Listener executes and logs `Task failed`.
- The `langgraph_tasks` row remains `RUNNING`.
- `error_message` remains `NULL`.
- `updated_at` does not move.

## Impact Scope

- ApprovalRequired business function resume path.
- Deterministic business function result reconciliation.
- Visible task terminal status shown to upstream CLI, mobile, web UI, and task polling.
- Long-running `RUNNING` task cleanup and readiness diagnostics.

## Test Strategy

Add a Spring/JPA integration test in `addons/langgraph-biz-worker` that:

- persists a real `LanggraphTaskEntity` in H2,
- publishes `business_function_result_message` from a transaction `afterCommit` callback, matching the real `BusinessFunctionSuspensionService` behavior,
- asserts the reloaded `langgraph_tasks` row is terminalized,
- asserts the shared `session_tasks` projection is also updated.

This test is required because the existing listener unit test only verifies `taskService.failTask(...)` is invoked on a mock.

## Code Inventory

- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessFunctionSuspensionService.java`
  - publishes `business_function_result_message` after transaction commit.
- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphBusinessFunctionResultMessageListener.java`
  - consumes result messages and calls terminal task updates.
- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskService.java`
  - persists task terminal status and session projection.
- `addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/service/LanggraphBusinessFunctionResultMessageListenerIntegrationTest.java`
  - new regression coverage.

## Fix Checklist

- [x] Reproduce the persistence gap with an integration test.
- [x] Ensure terminal status updates invoked from `afterCommit` run in a real new transaction.
- [x] Cover both failure and success result messages.
- [x] Run targeted Maven tests for listener, task service, and approval resume flow.
- [x] Update this work item with verification evidence.

## Verification

Root cause:

- `BusinessFunctionSuspensionService` publishes `business_function_result_message` from a transaction `afterCommit` callback.
- `LanggraphBusinessFunctionResultMessageListener` handled that event synchronously and called `LanggraphTaskService.completeTask(...)` or `failTask(...)`.
- Those methods used default `@Transactional` propagation. In the `afterCommit` callback, Spring can still have the already-committed transaction resources bound to the thread, so the method appears to run and logs `Task completed/failed`, but the dirty entity state is not committed to the database.

Fix:

- `LanggraphTaskService.completeTask(...)` now uses `@Transactional(propagation = Propagation.REQUIRES_NEW)`.
- `LanggraphTaskService.failTask(...)` now uses `@Transactional(propagation = Propagation.REQUIRES_NEW)`.
- This forces the terminal status reconciliation triggered by a post-commit business result message to persist in an independent transaction.

Regression evidence:

- Before the fix, `LanggraphBusinessFunctionResultMessageListenerIntegrationTest` reproduced the issue: logs showed task completion/failure, but the reloaded `langgraph_tasks` row stayed `RUNNING`.
- After the fix, the same integration test passes for both success and failure result messages and verifies `langgraph_tasks` plus `session_tasks` terminal status persistence.

Test commands:

```bash
mvn -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphBusinessFunctionResultMessageListenerIntegrationTest,LanggraphBusinessFunctionResultMessageListenerTest,LanggraphTaskServiceTest,LanggraphStreamRelayTest,LanggraphWorkerResumeEventListenerTest,BusinessFunctionApprovalResumeFlowTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Result: `Tests run: 40, Failures: 0, Errors: 0, Skipped: 0`.

```bash
mvn -pl addons/langgraph-biz-worker -am "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Result: `files=118 tests=860 failures=0 errors=0 skipped=0`.

## Upstream Verification

TMS X6 completed the final follow-up verification on 2026-05-17 against the rebuilt and restarted latest Navi backend/Worker runtime.

Failure path:

- task: `lgt_39af29732e074324`.
- context: `20260517-dd80`.
- suspension: `sus_138fbec19452469baab435bc4a30f9e9`.
- deterministic `post_approval_message` was written back to the visible `lgt_*` task/context.
- deterministic `business_function_result_message` was written back to the visible `lgt_*` task/context.
- resume/result message did not fall back to `obt_*`.
- adapter 401 failure terminalized both projections:
  - `langgraph_tasks.status=FAILED`.
  - `session_tasks.status=FAILED`.
  - `error_message` contained `HTTP 401 UNAUTHORIZED`.

Success path:

- task: `lgt_99f3807b4a034ed6`.
- context: `20260517-d158`.
- suspension: `sus_15581b71472b4b76905a16b78731e1cb`.
- deterministic `post_approval_message` was written back to the visible `lgt_*` task/context.
- deterministic `business_function_result_message` was written back to the visible `lgt_*` task/context.
- resume/result message did not fall back to `obt_*`.
- adapter success terminalized both projections:
  - `langgraph_tasks.status=COMPLETED`.
  - `session_tasks.status=COMPLETED`.
  - `result_text=业务函数执行完成。`
  - `structured_output` contained `executionStatus=COMPLETED`, `outputCode=200`, and `hasOutputData=true`.

The previous symptom, where the deterministic result message was visible but `langgraph_tasks.status` stayed `RUNNING`, was not reproduced. TMS also confirmed the intermediate adapter 401 was caused by an upstream user grant token refresh/configuration issue and is not a Navi follow-up defect.

## References

- `docs/version-tracker/1.3.0-SNAPSHOT/14-biz-worker-root-skill-upstream-e2e-regression-bug.md`
