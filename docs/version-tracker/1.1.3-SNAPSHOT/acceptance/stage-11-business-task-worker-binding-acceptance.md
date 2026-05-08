# Stage 11 Business Task Worker Binding Acceptance

- doc_type: acceptance-record
- version: 1.1.3-SNAPSHOT
- status: accepted
- date: 2026-05-05
- owner: navigator-java | langgraph-biz-worker

## Scope

This stage fixes the Navigator-side gap found during the TMS P1 approval-required invoke verification.

The failed runtime evidence showed that approval suspension and control-plane resume could be created successfully, but the resume event could not continue the real LangGraph worker execution because the Business Agent task id (`bt_*`) was not bound to a LangGraph worker task id (`lgt_*`).

## Root Cause

1. `BusinessAgentTaskService.createTask` created only the Java Business Agent task and task-scoped token.
2. No real `LanggraphTaskEntity` was created for that business task.
3. `BusinessFunctionSuspensionService.resumeSuspension` published the business task id in `WorkerGatewayResumeEvent.taskId`.
4. `LanggraphWorkerResumeEventListener` looks up `langgraph_tasks` by task id, so it could not find a row for `bt_*`.
5. Even if a LangGraph task existed, runtime token lookup needed a token alias bound to the worker task id because the agent runtime passes `Message.taskId` into `ToolRuntimeContextRequest.taskId`.

## Implemented Changes

1. Added a `BusinessAgentWorkerTaskLauncher` SPI in `business-agent-module`.
2. Added nullable worker binding fields:
   - `BusinessAgentTaskEntity.workerTaskId`
   - `BusinessAgentTaskEntity.workerId`
   - `BusinessAgentTaskEntity.workerProviderType`
   - `BusinessTaskScopedTokenEntity.workerTaskId`
   - `BusinessFunctionSuspensionEntity.workerTaskId`
3. Added `LanggraphBusinessAgentWorkerTaskLauncher` in `addons/langgraph-biz-worker`.
   - Selects an enabled worker pool member.
   - Creates a real `LanggraphTaskEntity` through `LanggraphTaskService`.
   - Stores only non-secret business context.
   - Does not expose task scoped token in LangGraph task context.
4. `BusinessAgentTaskService.createTask` now:
   - launches a backend worker task when a matching launcher exists;
   - stores the returned worker task binding;
   - registers task-scoped token aliases for both `bt_*` and `lgt_*`.
5. `BusinessFunctionSuspensionService.createSuspension` captures `workerTaskId`.
6. `BusinessFunctionSuspensionService.resumeSuspension` dispatches resume events to `workerTaskId` when present, with fallback to the legacy business task id for old records.

## Acceptance Criteria

| Check | Result |
| --- | --- |
| New Business Agent task can bind to a LangGraph worker task | passed by unit test |
| Task scoped token is registered under both business task and worker task ids | passed by unit test |
| Suspension stores worker task id from token context | passed by unit test |
| Resume event dispatches worker task id when bound | passed by unit test |
| LangGraph task context does not contain task scoped token | passed by unit test |
| Existing business-agent-module tests remain green | passed |
| Existing langgraph-biz-worker tests remain green | passed |
| Launcher compile remains green | passed |

## Verification

```powershell
mvn clean test -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphBusinessAgentWorkerTaskLauncherTest" "-Dsurefire.failIfNoSpecifiedTests=false"
# 4 tests, 0 failures

mvn test -pl business-agent-module -am
# business-agent-module: 186 tests, 0 failures

mvn test -pl addons/langgraph-biz-worker -am
# langgraph-biz-worker: 66 tests, 0 failures

mvn compile -pl launcher -am -DskipTests
# BUILD SUCCESS
```

## Runtime Retest Evidence

This stage fixes the code path but does not retroactively patch already-created tasks or suspensions. The TMS P1 approval verification was re-run after Navigator was rebuilt and restarted with this code, using a newly-created Business Agent task with populated worker binding.

Live evidence from 2026-05-06:

1. New Business Agent task: `bt_d5bb14f52a3743ec9902468f0534c890`.
2. Bound LangGraph task: `lgt_06ea66392d264cd0`.
3. Business session: `nav-p1-approval-1124-20260506090054`.
4. Worker session: `1056ba4e-029b-4036-856b-61d1a45a1e68`.
5. Approval invoke returned `SUSPENDED`.
6. Suspension id: `sus_911102b800b94f91b2273a2f21a11e9b`.
7. Control-plane resume returned `resume_dispatched` with a resume ref.
8. Approved suspension executed through the Java Gateway fallback path after the worker reported no active awaiting approval frame.
9. Final Gateway status: `SUCCESS`.
10. TMS code: `200`.
11. TMS data: yes.
12. Leak check: false for token, task scoped token, adapter config, manifest, controlled headers, and internal ids.

The live retest also exposed a stale TMS Web runtime jar: the first run reached the REST adapter but TMS returned `code=600` because the running `x3-web-agent` jar lacked `selfPickupSign`. After rebuilding and restarting TMS Web on `http://localhost:12580`, the endpoint mapped to `AgentTmsOrderController#selfPickupSign` and returned `code=200`.

## Remaining Risks

1. The launcher currently selects the first enabled pool member by creation order. Round-robin or health-weighted selection is future work.
2. If no backend launcher bean exists, Business Agent task creation remains compatible and does not fail. A stricter fail-closed policy can be added later for production-only `LANGGRAPH_BIZ` pools.
3. The live P1 proof used the Java approved-suspension execution fallback because the Python worker returned no active awaiting approval frame. A later Worker-level E2E should verify the full Python frame resume path when the LangGraph runtime has a real suspended frame.

## Decision

Accepted. The Navigator-side ownership issue is fixed in code, covered by focused tests, and live-retested against TMS P1 approval-required invoke with a newly-created task. The remaining Python frame resume proof is a follow-up hardening item, not a blocker for the Java Gateway approval/resume plus REST adapter path.
