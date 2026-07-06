---
type: bug
bug_source: user-report
version: 1.3.2-SNAPSHOT
ticket: BUG-148
severity: major
status: ready-for-external-verification
reproduction_status: confirmed
test_strategy: unit-test+live-smoke
automation_decision: required
owner: claude-worker-agent
---

# BUG Work Item

## Background

GitHub issue #148 reports that the TMS business assistant can call
`POST /api/v1/open/agents/{agent}/ask` successfully for the first turn, but an
immediate second ask with the returned `contextId` fails with HTTP 500.

The reported environment is Navigator Upstream CLI `1.0.18`, branch
`qd-win11/dev`, commit `b5c1b41c`, tenant `nav_tms_88800`, agent
`tms-tenant-88800-root-agent`, upstream user `88801`.

## Reproduction

1. Send an OpenAPI ask without `contextId`.
2. Receive a business-style context id such as `bctx_...`.
3. Immediately send another OpenAPI ask with the same tenant, client app,
   upstream user, agent, and `contextId`.
4. The continuation path validates `business_agent_session` ownership before the
   row is guaranteed to exist and returns a default server error.

## Expected vs Actual

Expected: a `contextId` returned by OpenAPI ask can be reused immediately by the
same tenant, client app, upstream user, and agent. Invalid ownership should fail
with a clear RX error and should not submit a task.

Actual: the business session row can be missing because first-turn binding relied
only on `OpenApiSessionQueryService.resolveSessionId(...)`; when that mapping was
not visible at bind time, `BusinessAgentSessionService.getSession(...)` raised
`business agent session not found`, escaping as an HTTP 500.

Follow-up live smoke from GitHub issue comment `4885983136` showed a second HTTP
500 after rebuilding the first fix. The new stack trace was
`UnsupportedOperationException: resume not supported by langgraph-biz-worker`:
`TaskDispatchFacade.createTask()` treated any known `contextId` binding as a
provider `resumeTask(...)` call. For normal OpenAPI chat continuation on
`langgraph-biz-worker`, the expected behavior is to reuse the Navigator
session/context and create a new provider task, not call provider resume.

## Impact Scope

- OpenAPI ask continuation through `OpenApiController.askAgent`.
- Business assistant integrations using the returned `contextId` immediately.
- Security boundary remains `tenantId + clientAppId + upstreamUserId + agent
  owner`; unknown or conflicting contexts must still fail before task dispatch.

## Test Strategy

Unit-test regression in `OpenApiControllerMessageMappingTest`:

- First turn binds `business_agent_session` from the returned task metadata
  `sessionId`, even when the context query mapping is delayed.
- Continuation recovers a missing business session row only when the Navigator
  `contextId + agentOwnerUserId` mapping exists for the same target agent and an
  active OpenAPI task-scoped token exists for the same tenant, client app,
  upstream user, and context.
- Unknown `contextId` still returns an RX failure and does not call the agent.

Unit-test regression in `TaskDispatchFacadeTest`:

- A bound LangGraph Biz `contextId` without an explicit `resume` flag reuses the
  existing Navigator session and dispatches through `createTaskDirect(...)`.
- Explicit `resume=true` still preserves the existing provider resume behavior.

## Code Inventory

- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiControllerMessageMappingTest.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`

## Fix Checklist

- [x] Bind business session from submitted task metadata `sessionId` before
  falling back to `OpenApiSessionQueryService.resolveSessionId(...)`.
- [x] Validate requested `contextId` after resolving the agent owner, so recovery
  can use the same Navigator context owner dimension as dispatch.
- [x] Recover only the `business agent session not found` case and only when a
  Navigator context mapping exists for the same agent owner and target agent plus
  a same-upstream active OpenAPI task-scoped token.
- [x] Return RX failure for rejected continuation requests instead of allowing the
  service exception to become a default server error.
- [x] Do not auto-convert ordinary `createTask(...)` context continuation into
  provider `resumeTask(...)`; require explicit `resume=true` for that branch.
- [x] Add unit regression coverage.

## Verification

Command:

```powershell
mvn -pl addons/claude-worker-agent -am "-Dtest=OpenApiControllerMessageMappingTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Result: pass, `OpenApiControllerMessageMappingTest` ran 38 tests with 0 failures.

Command:

```powershell
mvn -pl session-module -am "-Dtest=TaskDispatchFacadeTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Result: pass, `TaskDispatchFacadeTest` ran 66 tests with 0 failures.

Live smoke:

```powershell
mvn clean package -pl launcher -am -DskipTests

.\tools\navigator-upstream\navi.ps1 upstream ask `
  --profile .navigator\tenants\navi-provisioning-selftest-a.env `
  --agent navigator-provisioning-selftest-agent-a `
  --skill-id navigator-provisioning-selftest-agent-a `
  --upstream-user-id navigator-provisioning-selftest-user-a `
  --message "BUG-148 smoke first 20260705-212043"

.\tools\navigator-upstream\navi.ps1 upstream ask `
  --profile .navigator\tenants\navi-provisioning-selftest-a.env `
  --agent navigator-provisioning-selftest-agent-a `
  --skill-id navigator-provisioning-selftest-agent-a `
  --upstream-user-id navigator-provisioning-selftest-user-a `
  --context-id bctx_20260705_21_212fbdbeae654176b1c6f95af46c4ceb `
  --message "BUG-148 smoke second 20260705-212043"
```

Result: pass on 2026-07-05 21:20:43 Asia/Shanghai. First ask returned
`taskId=lgt_58327848d90d4380`, `status=SUBMITTED`,
`contextId=bctx_20260705_21_212fbdbeae654176b1c6f95af46c4ceb`,
`workerBackend=LANGGRAPH_BIZ`, `providerType=langgraph-biz-worker`. Immediate
second ask with the same `contextId` returned `taskId=lgt_cb9e255122754867`,
the same `contextId`, `workerBackend=LANGGRAPH_BIZ`, and
`providerType=langgraph-biz-worker`.

No HTTP 500, `business agent session not found`, or
`resume not supported by langgraph-biz-worker` was observed.

Exact TMS tenant replay note: local `.navigator/upstream.env` still points to
legacy `capp_2852124a-...` / `tms-agent-v305`, where the agent is disabled. The
reported `tms-tenant-88800-root-agent` is present locally under
`capp_c958991d-...`, but the matching runtime credential profile is not present
in this workspace. Therefore the exact reported TMS profile replay remains an
environment credential follow-up; the equivalent OpenAPI + `LANGGRAPH_BIZ`
context-continuation path has passed.

## Quality Review

- Report: `docs/version-tracker/1.3.2-SNAPSHOT/quality/BUG-148-fix-quality-review.md`
- Decision: `ready-for-external-verification`
- Required follow-up before final external closure: rerun the same two-turn smoke
  in the reported TMS environment using the matching `capp_c958991d-...`
  runtime credential profile.

## References

- https://github.com/foggy-projects/Foggy-Navigator/issues/148
- https://github.com/foggy-projects/Foggy-Navigator/issues/148#issuecomment-4885983136
