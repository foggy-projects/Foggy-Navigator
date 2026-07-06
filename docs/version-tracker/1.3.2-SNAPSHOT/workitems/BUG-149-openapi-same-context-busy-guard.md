---
type: bug
bug_source: regression-found
version: 1.3.2-SNAPSHOT
ticket: BUG-149
severity: major
status: ready-for-external-verification
reproduction_status: confirmed
test_strategy: unit-test+live-smoke
automation_decision: required
owner: session-module
---

# BUG Work Item

## Background

GitHub issue #148 follow-up comment `4886350570` reports that commit
`2ad79ea5` fixed the original OpenAPI HTTP 500 and the accidental provider
`resumeTask(...)` path, but the real TMS runtime still fails when a second
OpenAPI ask reuses the same `contextId` immediately before the first task
reaches a terminal state.

The second ask is accepted as `SUBMITTED`, then the LangGraph Biz runtime later
fails the worker task with:

```text
context runtime is busy but no active root frame was found
```

Control runs where the second ask waits until the first task is terminal succeed.

## Reproduction

1. Send `POST /api/v1/open/agents/{agent}/ask` without `contextId`.
2. Receive `taskId` and `contextId`.
3. Immediately send another ask with the same tenant, client app, upstream user,
   agent, and `contextId` before the first task is terminal.
4. Navigator accepts the second task, but LangGraph Biz Worker later marks it
   `FAILED`.

## Expected vs Actual

Expected: same-context continuation is deterministic while the runtime is busy.
The platform should either queue it or reject it synchronously with a retryable
business error before a worker task is created.

Actual: the request is accepted as a new task and fails asynchronously in the
BizWorker runtime, producing a confusing `SUBMITTED` then `FAILED` sequence for
the upstream caller.

## Impact Scope

- OpenAPI ask continuation for business agents backed by `LANGGRAPH_BIZ`.
- Direct provider dispatch through `TaskDispatchFacade` when a bound
  `contextId` reuses an existing Navigator session.
- TMS integration flow that sends the second user turn immediately after the
  first `ask` response.

## Test Strategy

- Unit-test `TaskDispatchFacade` so a bound LangGraph Biz `contextId` with an
  active task in the same Navigator session throws `CONTEXT_RUNTIME_BUSY` and
  does not call `createTaskDirect(...)`.
- Unit-test that the same direct continuation still dispatches when no active
  task is present.
- Unit-test `OpenApiController.askAgent` so a submit-layer busy rejection returns
  `RX.failB(...)` instead of propagating as a server error.
- Manual/live smoke should verify immediate second ask returns deterministic
  busy/conflict and delayed continuation still succeeds.

## Code Inventory

- `navigator-common/src/main/java/com/foggy/navigator/common/repository/SessionTaskRepository.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiControllerMessageMappingTest.java`

## Fix Checklist

- [x] Add a unified session-task query for active tasks by session, user,
  provider, and status.
- [x] Reject LangGraph Biz direct context continuation before worker dispatch
  when the same Navigator session still has an active task.
- [x] Return a structured `CONTEXT_RUNTIME_BUSY` message with the active task id.
- [x] Convert OpenAPI submit-stage busy rejection into an RX business failure.
- [x] Add unit regression coverage.
- [x] Run targeted Maven tests.
- [x] Run local smoke where possible.

## Verification

- PASS: `mvn -pl session-module -am "-Dtest=TaskDispatchFacadeTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
  (`TaskDispatchFacadeTest`: 67 tests, 0 failures, 0 errors).
- PASS: `mvn -pl addons/claude-worker-agent -am "-Dtest=OpenApiControllerMessageMappingTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
  (`OpenApiControllerMessageMappingTest`: 39 tests, 0 failures, 0 errors).
- PASS: `mvn -pl launcher -am -DskipTests package` after stopping the old
  local launcher process that was locking the jar.
- PASS: local launcher restarted with `--spring.profiles.active=docker`;
  `GET /actuator/health` returned `UP` with MySQL and Rabbit `UP`.
- PASS: local selftest immediate same-context continuation returned
  `CONTEXT_RUNTIME_BUSY` for active task `lgt_84e2d28d05ed4111` and context
  `bctx_20260705_32_32498bb5b7f747d18f5607a861385d69`.
- PASS: after that first task reached terminal state, a delayed same-context
  continuation was accepted as a new task (`lgt_d528d9924d4541fc`) and was not
  rejected by the busy guard.

External verification required: TMS should rerun the real-runtime reproduction
from issue #148 comment `4886350570` and confirm that the immediate second ask
now receives the synchronous `CONTEXT_RUNTIME_BUSY` business failure instead of
`SUBMITTED` followed by asynchronous LangGraph runtime failure.

## References

- https://github.com/foggy-projects/Foggy-Navigator/issues/148#issuecomment-4886350570
- `docs/version-tracker/1.3.2-SNAPSHOT/workitems/BUG-148-openapi-ask-context-continuation.md`
