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

## Fix Plan

1. Add a Worker API that records final business function result against the suspended frame by `taskId + suspendId`.
2. Let the Worker update function frame output/status, close affected child/root reports for this terminal task path, and return report refs/digests.
3. Add a Java-side report bridge that calls the Worker API before publishing deterministic business function result messages.
4. Add report metadata to deterministic `business_function_result_message` payloads.
5. Copy report metadata through `LanggraphStreamRelay` for all relevant SSE event types.
6. Ensure `post_approval_message` carries the Worker-provided report metadata.

## Status

- Status: implemented, pending upstream E2E verification
- Owner: Navi
