---
type: bug
bug_source: regression-found
version: 1.3.0-SNAPSHOT
ticket: BUG-021
severity: major
status: closed
reproduction_status: confirmed
test_strategy: integration-test
automation_decision: required
owner: langgraph-biz-worker
reported_by: tms-x6-upstream
upstream_source: upstream-e2e
---

# BUG Work Item

## Background

TMS X6 上游在 Navigator 升级后重新验证 BizWorker root skill / business function 新链路，覆盖：

1. root agent 普通自然语言回复。
2. root skill 调用业务 skill，再调用业务函数虚拟 frame。
3. `contextVisibility=summary` 透传与 materialize。
4. approvalRequired 函数的 suspend/resume 与确定性消息。
5. adapter 执行完成后的业务函数结果消息。

本轮验证中 root 自然语言、普通业务函数调用、函数 visible、skill sync、agent sync、readiness 均已通过，但发现两个 Navi 侧需要处理的回归/契约问题。

## Environment

- Navigator base URL: local `http://localhost:8112`
- TMS Web base URL: local `http://localhost:12580`
- CLI: `navigator-upstream-cli 1.0.1`
- `agentCode`: `tms-root-router-agent`
- ClientApp: TMS X6 local upstream client app
- Business skills involved:
  - `tms-order-agent`
  - `tms-fulfillment-agent`
  - `foggy-query-agent`

No token, adapter config, full manifest JSON, or runtime credential is included in this issue.

## Reproduction

### 1. Sync skills with `contextVisibility=summary`

TMS business skill manifests were updated with:

```json
{
  "contextVisibility": "summary"
}
```

Representative updated manifests:

- `tms-order-agent`
- `tms-fulfillment-agent`
- `foggy-query-agent`
- `tms-basic-agent`
- `tms-route-agent`

Sync commands:

```powershell
navi upstream skill sync --scope client-app-public --manifest .navigator/acceptance-skill-sync/tms-order-agent.json
navi upstream agent sync --manifest .navigator/agent-bundle.json
navi upstream verify-agent-readiness --agent-code tms-root-router-agent
```

Observed:

- skill sync succeeds.
- agent sync succeeds.
- readiness succeeds.

### 2. Ask root agent and ordinary function

Root natural language smoke:

- `taskId=lgt_51a29c6e96d647f5`
- `contextId=20260516-1e6a`
- result: `COMPLETED`

Business function smoke:

- `taskId=lgt_288c837e840c413d`
- `contextId=20260516-3640`
- function: `tms.order.createOpeningDraft`
- version: `v1`
- result: `COMPLETED`
- adapter result: `Adapter execution successful`

This confirms the root skill -> business skill -> business function virtual frame path can work for non-approval functions.

### 3. Ask approvalRequired function

Approval smoke:

- `taskId=lgt_268c825777d64f58`
- `contextId=20260516-bfe6`
- skill: `tms-fulfillment-agent`
- function: `tms.vehicle.create`
- version: `v1`
- `approvalRequired=true`
- suspend id: `sus_0c9b9b2a1cbb486b8efa52f946a7c2db`

Observed messages include:

```text
status=SUSPENDED
approvalRequired=true
message=Approval required, execution suspended.
```

Then the root/child frame chain continues into invalid transitions:

```text
Child skill ended in AWAITING_APPROVAL
Cannot transition from WAITING_CHILD to AWAITING_APPROVAL
submit_persistent_turn_result requires RUNNING, got WAITING_CHILD
LLM skill agent reached max iterations without valid submit
Frame ended in FAILED
```

Final task status: `FAILED`.

## Expected vs Actual

Expected:

1. After `contextVisibility=summary` skill sync and materialize, business skill metadata should reflect `context-visibility: summary`, and child skill should receive the root/root skill compressed summary.
2. When an approvalRequired business function suspends, the parent/root frame should deterministically enter the waiting approval state and stop further LLM tool execution.
3. After approval resume, runtime should emit the deterministic `post_approval_message`.
4. After adapter execution completes, runtime should emit the deterministic `business_function_result_message`.
5. The task should not fail because the parent frame is in `WAITING_CHILD` while the child frame is `AWAITING_APPROVAL`.

Actual:

1. The synced manifest contains `contextVisibility: "summary"`, but runtime `read_skill_resource` still returns materialized skill metadata with:

```yaml
metadata:
  context-visibility: isolated
```

Observed for at least:

- `tms-order-agent`
- `tms-fulfillment-agent`

2. ApprovalRequired function can create a suspension successfully, but parent/child state transition fails immediately after suspension.
3. No `post_approval_message` was observed.
4. No post-resume `business_function_result_message` was observed.
5. The task ends as `FAILED`.

## Impact Scope

Severity: `major`

Reason:

- `contextVisibility=summary` is part of the new root skill / business skill isolation contract. If materialization stays `isolated`, upstream skills that rely on root/main summary cannot work deterministically.
- ApprovalRequired business functions are a key safety path. Suspending successfully but then failing the root/child frame chain blocks approval resume verification and prevents deterministic post-approval messaging.
- The failure happens in the upgraded BizWorker root/function frame path, not in a purely TMS-local adapter path.

## Current Assessment

### Navigator confirmation

2026-05-16 Navi side confirmation:

1. This is a valid Navi-side regression report, not a TMS adapter-only issue.
2. `contextVisibility=summary` is present in the current Navigator source path from SDK/control-plane form to Worker materialize request, but upstream evidence shows the running sync/materialize path still produced `context-visibility: isolated`. Treat this as a contract propagation or artifact freshness bug until the deployed backend/CLI artifact version is verified.
3. The approval failure is an implementation gap in the nested path `root skill -> child business skill -> approvalRequired business function`. The child skill can enter `AWAITING_APPROVAL` while the root frame is still `WAITING_CHILD`, and the current parent-child close/resume path only handles `COMPLETED` children.
4. Existing closure tests covered direct root/function and Java resume behavior, but did not cover the nested child-skill approval suspension path. A failing Worker integration test should be added before or with the fix.

### 1. `contextVisibility` may be accepted by CLI/control plane but lost before Worker materialize

Evidence:

- `navi upstream skill sync` succeeds with manifests containing `contextVisibility: "summary"`.
- Navigator Open SDK source in current Navi repo contains `contextVisibility` on skill bundle forms.
- Worker code supports `context_visibility` and materializes it into `context-visibility`.
- Runtime read still shows `context-visibility: isolated`.

Likely boundaries to inspect:

- CLI manifest parse -> SDK form field mapping
- control plane skill bundle persistence
- `SkillRegistryService` materialize payload
- worker `/api/v1/skills/materialize` input
- materialized `SKILL.md` metadata cache refresh

### 2. Approval suspend should probably short-circuit parent/root tool loop

The child business skill reaches `AWAITING_APPROVAL`, but the parent root frame remains in `WAITING_CHILD` and continues receiving tool calls. This produces invalid state transitions and eventually max-iteration failure.

Likely boundaries to inspect:

- `invoke_business_function` result handling when `approval_wait=true`
- child skill frame close semantics for `AWAITING_APPROVAL`
- parent root frame transition from `WAITING_CHILD` to approval-waiting state
- deterministic approval resume message injection
- task terminal/non-terminal status while awaiting approval

## Verification Already Passed

The following checks passed in the same environment:

- `navi upstream function visible`
  - `functionVisibleCount=59`
  - includes `tms.order.createOpeningDraft` v1
  - includes `tms.vehicle.create` v1 with `approvalRequired=true`
- skill sync for TMS public skills.
- agent sync for `tms-root-router-agent`.
- `verify-agent-readiness`:
  - `AGENT_REGISTERED=OK`
  - `CLIENT_APP_SKILL_GRANT=OK`
  - `UPSTREAM_USER_GRANT=OK`
  - `MODEL_CONFIG_GRANT=OK`
- ordinary ask:
  - `taskId=lgt_51a29c6e96d647f5`
  - `contextId=20260516-1e6a`
  - `COMPLETED`
- ordinary business function ask:
  - `taskId=lgt_288c837e840c413d`
  - `contextId=20260516-3640`
  - `COMPLETED`

## Related Upstream Test Results

TMS X6 local regression results:

- `x3-web-agent` Navigator Java tests: 22 passed.
- `x3-web-tms` Navigator front-end tests: 12 passed.
- Navigator deterministic E2E group:
  - root router deterministic: passed
  - routing deterministic: passed
  - golden chain deterministic: passed
  - REST adapter P0: passed
  - opening draft skill BFF resync: failed due `BusinessObject not found: platform.tenant`

The `platform.tenant` sync failure appears to be a separate control-plane/business-object bootstrap issue, but it was found during the same upstream validation and may need a contract decision.

## Acceptance Criteria

This bug can be closed when:

1. A skill synced with `contextVisibility=summary` is materialized with `context-visibility: summary`.
2. `read_skill_resource` confirms the materialized metadata is `summary`.
3. A child business skill invoked from root can see the intended root/root skill compressed summary when `summary` is configured.
4. An approvalRequired business function suspends without causing parent/root frame invalid transitions.
5. The task remains in a deterministic awaiting-approval state instead of `FAILED`.
6. After approval resume, runtime emits `post_approval_message`.
7. After adapter completion, runtime emits `business_function_result_message`.
8. Regression tests cover both `contextVisibility=summary` materialization and approval suspend/resume under root -> child skill -> function frame.

## Suggested Tests

- CLI/SDK integration test:
  - sync skill bundle with `contextVisibility=summary`
  - assert persisted DTO and materialize payload contain the value
  - assert materialized `SKILL.md` has `context-visibility: summary`

- Worker integration test:
  - root skill opens child business skill with `summary`
  - child receives visible root context summary

- Approval frame test:
  - root skill -> child skill -> approvalRequired function
  - function returns `SUSPENDED`
  - parent/root frame enters awaiting approval cleanly
  - resume emits `post_approval_message`
  - adapter completion emits `business_function_result_message`

## Navigator Fix

Implemented on 2026-05-16:

1. Worker state machine now allows a parent frame in `WAITING_CHILD` to enter `AWAITING_APPROVAL` when its child skill suspends for approval.
2. `invoke_business_skill` now treats child `AWAITING_APPROVAL` as a deliberate suspension and bubbles the approval request to the parent/root frame instead of returning `Child skill ended in AWAITING_APPROVAL` as a tool error.
3. Resume lookup now prefers a skill frame that contains `pending_child_approval_frame_id`, so Java resume by `taskId` restores the root approval boundary deterministically.
4. `resume_from_approval` now cascades resume into the pending child approval frame and restores the pending business function frame from journal when needed.
5. `navigator-open-sdk` was bumped to `1.0.2`, and local upstream CLI package `navigator-upstream-cli-1.0.2-windows.zip` was generated. The packaged SDK jar contains `get/setContextVisibility` on skill bundle forms.

## Verification

Automated verification completed on 2026-05-16:

- Worker full test suite:
  - `tools/langgraph-biz-worker/.venv/Scripts/python -m pytest tests`
  - Result: `365 passed, 6 skipped, 10 warnings`
- SDK CLI/API targeted tests:
  - `mvn test -pl navigator-open-sdk "-Dtest=UpstreamCliTest,BusinessAgentApiSmokeTest"`
  - Result: `56 passed`
- CLI package inspection:
  - `navigator-upstream-cli-1.0.2-windows.zip`
  - `javap` confirmed `SyncSkillBundleForm`, `SyncBusinessAgentBundleForm`, and `SyncAccountSkillBundleForm` expose `getContextVisibility` / `setContextVisibility`.

Published artifact:

- OBS latest: `https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/navigator-upstream-cli/latest.json`
- Version: `1.0.2`
- Windows package: `1.0.2/navigator-upstream-cli-1.0.2-windows.zip`
- SHA256: `3708c6f12a8752a7eaec7ef63296da6f660836f84163f8542a3cfb65d2ea15a8`
- Remote install smoke: passed

Remaining upstream verification:

1. Install or self-update to Navigator Upstream CLI `1.0.2` before re-running TMS X6 E2E.
2. Re-run `skill sync`, `agent sync`, `read_skill_resource`, and approval resume E2E against the restarted fixed Navigator backend and Worker.

## Follow-up: OpenAPI Resume Routing Regression

TMS X6 re-ran the E2E verification after Navigator Upstream CLI `1.0.2` and the BUG-021 first-stage fix.

Verified as fixed:

- skill manifests were re-synced for `tms-order-agent`, `tms-fulfillment-agent`, `foggy-query-agent`, `tms-basic-agent`, `tms-route-agent`, `tms-pay-agent`, and `tms-attachment-agent`.
- `agent sync` passed for `agentCode=tms-root-router-agent`.
- readiness passed for `AGENT_REGISTERED`, `CLIENT_APP_SKILL_GRANT`, `UPSTREAM_USER_GRANT`, and `MODEL_CONFIG_GRANT`.
- materialized `tms-fulfillment-agent/SKILL.md` now reports `metadata.context-visibility: summary`.
- approval pre-suspend path no longer reports:
  - `Child skill ended in AWAITING_APPROVAL`
  - `Cannot transition from WAITING_CHILD to AWAITING_APPROVAL`
  - `submit_persistent_turn_result requires RUNNING, got WAITING_CHILD`
  - `Frame ended in FAILED`

Residual failure reported by TMS:

- visible LangGraph task: `lgt_01583b8746944706`
- context: `20260516-47db`
- suspension: `sus_749b71f3318442d18de87e1bdc355277`
- function: `tms.vehicle.create` `v1`
- business task: `obt_0d5bb56013924e3199de2a20565fef34`
- resume API returned `status=resume_dispatched`.
- deterministic `post_approval_message` was not written back to the visible root task.
- `business_function_result_message` was written under the `obt_*` business task/session, not the visible `lgt_*` root task.
- backend log showed: `Task not found for taskId: obt_0d5bb56013924e3199de2a20565fef34, ignoring resume event`.
- DB evidence: the related suspension had `worker_task_id = NULL`.
- final state: visible task stayed `RUNNING`, suspension became `EXECUTE_FAILED`, and business execution became `FAILED`.

Additional TMS-side adapter evidence:

- adapter execution failed with `HTTP 401 UNAUTHORIZED`.
- This may be a TMS adapter credential/configuration issue, but Navigator should still route the failed adapter result back as a deterministic business function result message on the visible root task.

### Root Cause

Confirmed Navi-side bug:

1. The OpenAPI ask path issued an OpenAPI task-scoped business runtime token before the actual A2A/LangGraph task existed.
2. That token was initially scoped to an `obt_*` business task and the request context id.
3. After `agent.sendTask(...)`, the visible LangGraph `lgt_*` worker task id and worker session id were known, but the token was not rebound to them.
4. Suspension creation copies `worker_task_id` and `worker_session_id` from the runtime token. Because the OpenAPI token had not been rebound, the persisted suspension stored `worker_task_id = NULL`.
5. Approval resume and business function result routing therefore fell back to the `obt_*` business task id, which the LangGraph Worker cannot resolve as a visible task.

### Follow-up Fix

Implemented on 2026-05-16:

1. `OpenApiController.askAgent(...)` now captures the issued OpenAPI business runtime token.
2. After `agent.sendTask(...)` returns the visible A2A task, OpenAPI binds the task-scoped token to the visible worker task id.
3. The worker session id is resolved from `task.metadata["sessionId"]`, with `task.contextId` as a fallback.
4. `BusinessAgentTaskService.bindOpenApiTaskScopedTokenToWorkerTask(...)` persists `worker_task_id` and `worker_session_id` on the token entity.
5. The same method registers runtime token aliases for both the original context session and the actual worker session, so later suspension creation, approval resume, and result message publishing can resolve the visible `lgt_*` task.

Expected behavior after this fix:

- new suspensions created from OpenAPI ask tasks should persist `worker_task_id = lgt_*`.
- approval resume should dispatch back to the visible LangGraph task instead of `obt_*`.
- `post_approval_message` should be emitted deterministically on the visible root task.
- successful or failed adapter results should both route as `business_function_result_message` to the visible root task/context.

### Follow-up Regression Tests

Regression was established before the fix by adding:

- `OpenApiControllerMessageMappingTest#askAgent_bindsOpenApiBusinessRuntimeTokenToVisibleWorkerTask`

The first run failed at compile time because `BusinessAgentTaskService.bindOpenApiTaskScopedTokenToWorkerTask(...)` did not exist yet.

Automated verification after the fix:

- `mvn test -pl business-agent-module -am "-Dtest=BusinessAgentTaskServiceTest#bindOpenApiTaskScopedTokenToWorkerTask_persistsMappingAndRegistersWorkerAlias" "-Dsurefire.failIfNoSpecifiedTests=false"`
  - Result: passed, 1 test.
- `mvn test -pl addons/claude-worker-agent -am "-Dtest=OpenApiControllerMessageMappingTest#askAgent_bindsOpenApiBusinessRuntimeTokenToVisibleWorkerTask" "-Dsurefire.failIfNoSpecifiedTests=false"`
  - Result: passed, 1 test.
- `mvn test -pl business-agent-module -am "-Dtest=BusinessAgentTaskServiceTest,BusinessFunctionSuspensionServiceTest,WorkerGatewayServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
  - Result: passed, 53 tests.
- `mvn test -pl addons/claude-worker-agent -am "-Dtest=OpenApiControllerMessageMappingTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
  - Result: passed, 11 tests.
- `mvn test -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphWorkerResumeEventListenerTest,BusinessFunctionApprovalResumeFlowTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
  - Result: passed, 12 tests.

## Follow-up 2: LangGraph Task Terminal Status

TMS X6 verified the OpenAPI resume routing follow-up fix successfully:

- new approvalRequired suspension persisted `worker_task_id = lgt_*`.
- `worker_session_id` was non-empty.
- resume event no longer fell back to `obt_*`.
- deterministic `post_approval_message` was written to the visible `lgt_*` task/context.
- deterministic `business_function_result_message` was written to the visible `lgt_*` task/context.
- suspension status became `COMPLETED`.
- business execution status became `COMPLETED`.
- adapter returned `outputCode=200`.

Successful TMS sample:

- task: `lgt_951048da0b574a23`
- context: `20260516-0953`
- suspension: `sus_4e401a3099994ada94eaa4d3e7b12d52`
- worker task: `lgt_951048da0b574a23`
- worker session: `4d7bb71d-4d35-4702-972a-8f921287ee57`
- business task: `obt_bb15e5315def4815a049c57021595ba4`
- business session: `20260516-0953`

Residual observation:

- `langgraph_tasks.status` remained `RUNNING` after the deterministic business function result was written back and the suspension completed.

### Root Cause

Confirmed Navi-side behavior gap:

1. `LanggraphStreamRelay` only marks a LangGraph task terminal when it receives the Python Worker SSE `result` event.
2. In approvalRequired business function resume, deterministic `post_approval_message` is published by `LanggraphWorkerResumeEventListener`.
3. Deterministic `business_function_result_message` is published by `BusinessFunctionSuspensionService`.
4. Before this follow-up, the LangGraph module did not listen to `business_function_result_message`, so its own `langgraph_tasks` projection stayed `RUNNING` even though the business execution had completed.

### Follow-up 2 Fix

Implemented on 2026-05-16:

1. Added `LanggraphBusinessFunctionResultMessageListener`.
2. The listener consumes Spring `AgentMessage` events with:
   - `type = TEXT_COMPLETE`
   - `payload.subtype = business_function_result_message`
   - non-empty `payload.workerTaskId`
3. For `executionStatus=COMPLETED` or `status=SUCCESS`, it calls `LanggraphTaskService.completeTask(workerTaskId, ...)`.
4. For `executionStatus=FAILED` or `status=FAILED`, it calls `LanggraphTaskService.failTask(workerTaskId, ...)`.
5. Messages without `workerTaskId` are ignored, so the module does not accidentally terminalize an `obt_*` business task.

Expected behavior after this fix:

- approvalRequired adapter success: visible `lgt_*` task becomes `COMPLETED`.
- approvalRequired adapter failure: visible `lgt_*` task becomes `FAILED`.
- deterministic result message remains visible in the session exactly as before.
- ordinary LangGraph SSE `result` completion remains unchanged.

### Follow-up 2 Regression Tests

Regression was established before the fix by adding:

- `LanggraphBusinessFunctionResultMessageListenerTest`

The first run failed at compile time because `LanggraphBusinessFunctionResultMessageListener` did not exist yet.

Automated verification after the fix:

- `mvn test -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphBusinessFunctionResultMessageListenerTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
  - Result: passed, 4 tests.
- `mvn test -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphBusinessFunctionResultMessageListenerTest,LanggraphWorkerResumeEventListenerTest,BusinessFunctionApprovalResumeFlowTest,LanggraphStreamRelayTest,LanggraphTaskServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
  - Result: passed, 36 tests.

### Follow-up 2 Publish Verification

TMS first re-verification on 2026-05-16 showed the local runtime still used the old build artifact:

- source code contained `LanggraphBusinessFunctionResultMessageListener`.
- runtime `launcher-1.0.0-SNAPSHOT.jar` and `langgraph-biz-worker-1.0.0-SNAPSHOT.jar` did not contain the listener class.
- backend/worker process start time still pointed to the previous runtime, so the result was not a valid verification of the follow-up 2 fix.

Navi local runtime was rebuilt and restarted on 2026-05-16:

- `mvn -pl addons/langgraph-biz-worker -Dtest=LanggraphBusinessFunctionResultMessageListenerTest test`
  - Result: passed, 4 tests.
- `powershell -ExecutionPolicy Bypass -File start-launcher.ps1`
  - Result: rebuilt launcher and restarted service.
- `addons/langgraph-biz-worker/target/langgraph-biz-worker-1.0.0-SNAPSHOT.jar`
  - Last modified: `2026-05-16 19:39:43`.
  - Contains `com/foggy/navigator/langgraph/worker/service/LanggraphBusinessFunctionResultMessageListener.class`.
- `launcher/target/launcher-1.0.0-SNAPSHOT.jar`
  - Last modified: `2026-05-16 19:39:53`.
  - Contains nested `BOOT-INF/lib/langgraph-biz-worker-1.0.0-SNAPSHOT.jar`.
  - The nested jar contains `LanggraphBusinessFunctionResultMessageListener.class`.
- Backend process:
  - PID: `34504`.
  - Start time: `2026-05-16 19:39:53`.
  - Health: `UP`.

Note: direct `jar tf launcher/target/launcher-1.0.0-SNAPSHOT.jar | grep LanggraphBusinessFunctionResultMessageListener` does not show nested classes inside Spring Boot `BOOT-INF/lib/*.jar`; verify the nested `langgraph-biz-worker` jar instead.

## Final Upstream Verification

TMS X6 completed the latest BUG-021 follow-up verification on 2026-05-17 against the rebuilt and restarted Navi backend/Worker runtime.

Environment:

- Navigator Upstream CLI: `1.0.2`.
- Agent: `tms-root-router-agent`.
- Function: `tms.vehicle.create` `v1`.
- Link: root skill -> `tms-fulfillment-agent` -> `tms.vehicle.create` `v1`.
- Backend/Worker latest artifact was rebuilt and restarted.
- Actuator health: `UP`.

Sync and readiness:

- TMS skills and root agent were re-synced.
- `tms-order-agent`, `tms-fulfillment-agent`, `foggy-query-agent`, `tms-basic-agent`, `tms-route-agent`, `tms-pay-agent`, and `tms-attachment-agent` materialized `contextVisibility=summary`.
- `tms-root-router-agent` remained `isolated`.
- `verify-agent-readiness` returned OK.
- `function visible` confirmed `tms.vehicle.create` `v1` with `approvalRequired=true` and `idempotencyRequired=true`.

Failure path passed:

- task: `lgt_39af29732e074324`.
- context: `20260517-dd80`.
- suspension: `sus_138fbec19452469baab435bc4a30f9e9`.
- approval pre-suspend messages included normal `invoke_business_skill`, `invoke_business_function`, and `TOOL_RESULT status=SUSPENDED approvalRequired=true`.
- suspension persisted `worker_task_id=lgt_39af29732e074324`.
- `worker_session_id` was non-empty.
- after approval, deterministic `post_approval_message` and `business_function_result_message` were written back to the visible `lgt_*` task/context.
- no resume/result message fell back to `obt_*`.
- adapter 401 failure terminalized:
  - `suspension.status=EXECUTE_FAILED`.
  - `business_execution_status=FAILED`.
  - `langgraph_tasks.status=FAILED`.
  - `session_tasks.status=FAILED`.
  - `error_message` contained `HTTP 401 UNAUTHORIZED`.

Success path passed:

- task: `lgt_99f3807b4a034ed6`.
- context: `20260517-d158`.
- suspension: `sus_15581b71472b4b76905a16b78731e1cb`.
- approval pre-suspend messages included normal `invoke_business_skill`, `invoke_business_function`, and `TOOL_RESULT status=SUSPENDED approvalRequired=true`.
- suspension persisted `worker_task_id=lgt_99f3807b4a034ed6`.
- `worker_session_id` was non-empty.
- after approval, deterministic `post_approval_message` and `business_function_result_message` were written back to the visible `lgt_*` task/context.
- no resume/result message fell back to `obt_*`.
- adapter success terminalized:
  - `suspension.status=COMPLETED`.
  - `business_execution_status=COMPLETED`.
  - `langgraph_tasks.status=COMPLETED`.
  - `session_tasks.status=COMPLETED`.
  - `result_text=业务函数执行完成。`
  - `structured_output` contained `executionStatus=COMPLETED`, `outputCode=200`, and `hasOutputData=true`.

The upstream verification did not observe any of the original or follow-up regressions:

- result message written back to `lgt_*` while `langgraph_tasks.status` stayed `RUNNING`.
- resume/result message written back to `obt_*`.
- `Child skill ended in AWAITING_APPROVAL`.
- `Cannot transition from WAITING_CHILD to AWAITING_APPROVAL`.
- `submit_persistent_turn_result requires RUNNING, got WAITING_CHILD`.
- `Frame ended in FAILED`.

The intermediate adapter 401 was confirmed by TMS as an upstream user grant token refresh/configuration issue. After refreshing the TMS upstream user grant token, the success path passed. This is not classified as a Navi BUG-021 issue.

Final status: `closed`.
