# BUG-023: Frame Execution Report approval resume propagation

## Source

TMS X6 upstream re-verified the Frame Execution Report runtime exposure on 2026-05-17.

Observed path:

- `root skill -> tms-fulfillment-agent -> tms.vehicle.create v1`
- `approvalRequired=true`
- business suspension, business execution, LangGraph task, and session task all reached terminal state
- `read_frame_execution_report` can read the generated function report

## Symptoms

Only the `invoke_business_function` `TOOL_RESULT` exposed:

- `execution_report_ref`
- `execution_report_digest`

The following visible events did not expose report metadata:

- `approval_required`
- `post_approval_message`
- `business_function_result_message`
- promoted child result from `invoke_business_skill`
- root terminal result
- `skill_frame_close`

The message stream also did not contain `skill_frame_close` for the approval resume path.

Report state convergence was incomplete:

- function frame report reached `COMPLETED`
- child skill report stayed `RUNNING`
- root report stayed `AWAITING_APPROVAL` or stale

## Impact

The frontend can render report digest/ref when present, but most approval resume events still require raw frame JSON or backend inspection to review child agent and business function execution.

## Expected Behavior

1. `approval_required` exposes report ref/digest.
2. `post_approval_message` exposes report ref/digest.
3. `business_function_result_message` exposes report ref/digest.
4. promoted child result and `skill_frame_close` expose report ref/digest.
5. business function result completion/failure regenerates function, child, and root reports.
6. final report digest status matches the terminal business outcome where the approval resume path ends the task.

## Initial Analysis

Two gaps are likely involved:

1. Java SSE relay copies skill frame fields but does not uniformly copy `execution_report_ref/digest` into BFF payloads.
2. Approval resume business execution is completed deterministically by Java after the Python skill loop has suspended. Java currently completes/fails the visible task but does not notify the Python frame journal to regenerate child/root reports with the final business result.

## Regression Coverage Plan

- Java relay unit tests for report field propagation on:
  - `approval_required`
  - `tool_result`
  - `skill_frame_close`
  - `result`
- Python runtime tests for approval resume business result finalization:
  - nested child skill success path
  - failure path
  - `read_frame_execution_report` sees final statuses
- Python resume endpoint test for `post_approval_message.execution_report_ref/digest`.
- Java business suspension test for `business_function_result_message.execution_report_ref/digest`.

## Implementation

- Added Worker report reconciliation API: `POST /api/v1/frames/business-function-result`.
- Added deterministic runtime finalization by `taskId + suspendId`:
  - updates the function-call frame with the final adapter result
  - regenerates function report digest
  - finalizes and regenerates affected child/root skill reports
  - returns root/child/function report refs and compact digests
- Enriched `POST /api/v1/resume` `resume_message` with function report ref/digest for `post_approval_message`.
- Added Java `BusinessFunctionExecutionReportBridge` extension point in `business-agent-module`.
- Added LangGraph implementation of that bridge:
  - calls Worker report reconciliation API
  - emits deterministic `skill_frame_close` for finalized child frames
  - returns report metadata to the business result message builder
- Added report metadata propagation in `LanggraphStreamRelay` for:
  - `approval_required`
  - `tool_use`
  - `tool_result`
  - `skill_frame_close`
  - `result`
- Enriched deterministic `business_function_result_message` with:
  - `execution_report_ref/digest`
  - `function_execution_report_ref/digest`
  - `child_execution_report_ref/digest`
  - `root_execution_report_ref/digest`

## Verification

- Python Worker full test suite:
  - `410 passed, 6 skipped`
- Java targeted regression:
  - `BusinessFunctionSuspensionServiceTest`: 16 passed
  - `LanggraphBusinessFunctionExecutionReportBridgeTest`: 1 passed
  - `LanggraphStreamRelayTest`: 7 passed
  - `LanggraphWorkerResumeEventListenerTest`: 11 passed

## Follow-up Revalidation: 2026-05-17

TMS reported BUG-023 still failed in local E2E after BUG-021 follow-up passed:

- deterministic `post_approval_message` and `business_function_result_message` were written back to visible `lgt_*`
- terminal `langgraph_tasks` and `session_tasks` statuses converged correctly
- visible messages still missed report refs/digests
- child/root reports remained stale
- no deterministic `skill_frame_close` was observed

Local inspection found a packaging/runtime mismatch. Source and `target/classes` contained
`LanggraphBusinessFunctionExecutionReportBridge`, but the running packaged `launcher-1.0.0-SNAPSHOT.jar`
and nested `langgraph-biz-worker-1.0.0-SNAPSHOT.jar` were older and did not contain the bridge class.
In that state Java cannot call the Worker reconciliation API, so the observed E2E failure is expected even
though source-level tests pass.

Additional hardening:

- log bridge bean installation at startup
- warn when report reconciliation is skipped or returns empty
- add Worker resume regression for post-approval report enrichment after in-memory frame store is cleared

Artifact verification before upstream handoff must include:

```powershell
mvn clean package -pl launcher -am -DskipTests
jar tf addons\langgraph-biz-worker\target\langgraph-biz-worker-1.0.0-SNAPSHOT.jar |
  Select-String -Pattern "LanggraphBusinessFunctionExecutionReportBridge|BusinessFunctionExecutionReport"
```

The running backend logs should include:

```text
Business function execution report bridge configured: com.foggy.navigator.langgraph.worker.service.LanggraphBusinessFunctionExecutionReportBridge
```

## Follow-up Revalidation 2: 2026-05-17

TMS revalidated with the rebuilt Java artifact and confirmed the nested jar contains
`LanggraphBusinessFunctionExecutionReportBridge`. The latest failure changed shape:

- `invoke_business_function` `TOOL_RESULT` has report ref/digest
- `approval_required` has report ref/digest
- `post_approval_message`, `business_function_result_message`, promoted child result, and `skill_frame_close` are still missing report metadata
- child/root report digests remain stale

Local runtime inspection found the Java bridge was loaded, but Worker reconciliation calls returned HTTP 404:

```text
POST /api/v1/frames/business-function-result -> 404
```

The frame journal already contained the expected function frames and suspend IDs, so the stale report state was
not caused by missing frame data. The running Python Worker process had loaded an older package/runtime that did
not register the new frame report route. Before restarting the Worker:

```text
OpenAPI paths under /api/v1/frames:
- /api/v1/frames/interruption
```

After restarting through `tools/langgraph-biz-worker/start.ps1`, the route was registered and empty-body calls
returned validation errors instead of 404:

```text
OpenAPI paths under /api/v1/frames:
- /api/v1/frames/business-function-result
- /api/v1/frames/interruption

POST /api/v1/frames/business-function-result {} -> 422
```

Direct reconciliation against TMS-provided local samples then succeeded:

- success sample `lgt_4c4c5af5c6a348f0 / sus_caeb9608e4c9488fbf7d19dae4685db5`
  - function/child/root digests all converged to `COMPLETED`
  - response included `function_execution_report_*`, `child_execution_report_*`, `root_execution_report_*`
  - response included `closed_skill_frames`
- failure sample `lgt_0f297c68edf84d89 / sus_64b4ed7bf8374b9db35ffe3a992fe8f2`
  - function/child/root digests all converged to `FAILED`
  - response included the same report metadata and `closed_skill_frames`

Additional regression added:

- `tools/langgraph-biz-worker/tests/test_frame_report_route.py`
  - asserts `/api/v1/frames/business-function-result` is registered in OpenAPI
  - verifies HTTP endpoint restores frames from the journal after in-memory store is cleared
  - verifies success and failure paths converge function/child/root report digests

Latest local verification:

- `PYTHONPATH=src .\.venv\Scripts\python.exe -m pytest tests/test_frame_report_route.py tests/test_resume.py tests/test_frame_execution_report.py`
  - `32 passed`
- `mvn -pl addons/langgraph-biz-worker,business-agent-module -am "-Dtest=LanggraphBusinessFunctionExecutionReportBridgeTest,LanggraphStreamRelayTest,LanggraphWorkerResumeEventListenerTest,BusinessFunctionSuspensionServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
  - `19 passed`
- runtime health:
  - backend `/actuator/health`: `UP`
  - Worker `/health`: `UP`
  - Worker OpenAPI includes `/api/v1/frames/business-function-result`

## Follow-up Revalidation 3: 2026-05-17

TMS revalidated after backend and Python Worker restart and confirmed the core BUG-023 chain is restored:

- success and adapter-failure paths both reconcile through Worker `/api/v1/frames/business-function-result`
- deterministic `post_approval_message` is written to visible `lgt_*` with report ref/digest
- deterministic `business_function_result_message` is written to visible `lgt_*` with root/function/child report refs/digests
- deterministic `skill_frame_close` is emitted with child skill frame report ref/digest
- function, child skill, and root report digests converge to `COMPLETED` or `FAILED`
- `langgraph_tasks` and `session_tasks` converge to terminal status

One residual propagation gap remained:

- pre-approval `invoke_business_skill` tool result returned only
  `{"ok": true, "approval_wait": true, "child_frame_id": "..."}`
- that event did not include `execution_report_ref/digest`

Decision: treat this as a required but narrow propagation follow-up. The event represents the parent/root frame
receiving the child skill's approval wait state, so it should expose the child frame's current
`AWAITING_APPROVAL` report.

Implementation:

- enrich `invoke_business_skill` approval-wait results with the child frame report payload
- include both top-level `execution_report_ref/digest` and `child_execution_report_ref/digest`
- apply the same enrichment to resumed recoverable child skill approval-wait results

Additional regression:

- `tests/test_llm_skill_agent.py::test_llm_agent_bubbles_child_business_function_approval_to_root`
  - now runs with a journal-backed runtime so report generation matches the real Worker
  - asserts the pre-approval `invoke_business_skill` tool result carries the child frame
    `AWAITING_APPROVAL` report ref/digest

Latest local verification:

- `PYTHONPATH=src .\.venv\Scripts\python.exe -m pytest tests/test_llm_skill_agent.py::test_llm_agent_bubbles_child_business_function_approval_to_root`
  - `1 passed`
- `PYTHONPATH=src .\.venv\Scripts\python.exe -m pytest tests/test_llm_skill_agent.py tests/test_frame_report_route.py tests/test_resume.py tests/test_frame_execution_report.py`
  - `62 passed`

Upstream spot revalidation passed on 2026-05-17:

- sample task `lgt_6345c0c0469f4062`, context `20260517-9f18`, suspend `sus_1a1a41296c564e71887809c36b64d911`
- pre-approval `invoke_business_skill` promoted child result includes:
  - `ok=true`
  - `approval_wait=true`
  - `child_frame_id=frm_24834867381d`
  - `execution_report_ref=frame-report://lgt_6345c0c0469f4062/frm_24834867381d`
  - `child_execution_report_ref=frame-report://lgt_6345c0c0469f4062/frm_24834867381d`
  - `execution_report_digest.status=AWAITING_APPROVAL`
  - `child_execution_report_digest.status=AWAITING_APPROVAL`
- event metadata also includes top-level `execution_report_ref/digest`
- post-approval regression remained green:
  - `post_approval_message` includes report ref/digest
  - `business_function_result_message` includes root/function/child report refs/digests
  - `skill_frame_close` includes child report ref/digest
  - suspension, business execution, `langgraph_tasks`, and `session_tasks` all converged to `COMPLETED`
  - function, child, and root reports all converged to `COMPLETED`
  - no post/result message fell back to `obt_*`
  - no legacy approval resume errors recurred

## Fix Plan

1. Add a Worker API that records final business function result against the suspended frame by `taskId + suspendId`.
2. Let the Worker update function frame output/status, close affected child/root reports for this terminal task path, and return report refs/digests.
3. Add a Java-side report bridge that calls the Worker API before publishing deterministic business function result messages.
4. Add report metadata to deterministic `business_function_result_message` payloads.
5. Copy report metadata through `LanggraphStreamRelay` for all relevant SSE event types.
6. Ensure `post_approval_message` carries the Worker-provided report metadata.

## Status

- Status: closed; core E2E and residual pre-approval `invoke_business_skill` report propagation both passed upstream revalidation
- Owner: Navi
