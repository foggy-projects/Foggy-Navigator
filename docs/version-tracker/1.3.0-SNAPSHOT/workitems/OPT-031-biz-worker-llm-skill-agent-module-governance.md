---
type: optimization
version: 1.3.0-SNAPSHOT
ticket: OPT-031
severity: major
status: open
owner: biz-worker-runtime
---

# OPT-031: BizWorker LlmSkillAgent Module Governance

## Background

`tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py` has grown into the central runtime file for LLM-driven skill execution. As of 2026-05-18, the file is about 2,019 lines and 90 KB.

Recent work for BUG-027 and REQ-030 added more responsibilities:

- guarded LLM invocation and timeout error handling;
- persistent root interruption and user "继续" recovery semantics;
- child skill invocation, failed child recovery, and `resume_recoverable_child_skill`;
- on-demand attachment analysis tool integration;
- business function dispatch and approval/suspension result handling;
- tool schema definitions;
- prompt construction for root planning, time context, recoverable interruptions, visible context, attachments, and active plans;
- output guardrails, tool-call normalization, and summary sanitization.

This file is now still functional, but it is becoming a high-risk modification point. Small changes for timeout, tool routing, prompt policy, and frame lifecycle can easily interact in ways that are hard to review.

## Problem Statement

The current module has several maintainability risks:

1. Multiple ownership boundaries are mixed in one file.
   - Agent loop orchestration, tool execution, prompt policy, tool registry/schema, frame recovery, business function result handling, and content safety all live together.
2. The class-level responsibilities are too broad.
   - `LlmSkillAgent` owns both control flow and most adapter/helper logic.
3. Regression blast radius is high.
   - BUG-027 showed that a timeout fix can affect child frame lifecycle and user "继续" semantics.
4. Testing is present but hard to target by responsibility.
   - `test_llm_skill_agent.py` is also growing because many unrelated behaviors share one implementation surface.
5. Future feature work will continue to add pressure.
   - More business tools, multimodal tools, recovery states, and user-facing progress events will likely extend the same file unless boundaries are created.

## Target Outcome

Refactor `llm_skill_agent.py` into smaller runtime modules without changing behavior.

The target state should make each change easy to review by answering:

- Is this a prompt policy change?
- Is this a tool schema or tool dispatch change?
- Is this a frame lifecycle/recovery change?
- Is this a business function integration change?
- Is this an LLM agent loop change?

## Non-Goals

- Do not redesign Frame lifecycle semantics in this optimization.
- Do not change public API payloads or SSE event contracts.
- Do not change the BUG-027 behavior: LLM timeout must remain recoverable and failed child frames must still support user "继续".
- Do not replace LangChain or the existing tool-call loop.
- Do not combine this refactor with frontend display work.

## Current Code Inventory

Primary file:

- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py`

Closely coupled modules:

- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_runtime.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_call_guard.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/tools/attachment_analysis.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/tools/business_function_tools.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/account_file_tools.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/public_skill_resource_tools.py`

Safety tests to preserve:

- `tools/langgraph-biz-worker/tests/test_llm_skill_agent.py`
- `tools/langgraph-biz-worker/tests/test_llm_call_guard.py`
- `tools/langgraph-biz-worker/tests/test_frame_interruption.py`
- `tools/langgraph-biz-worker/tests/test_frame_lifecycle.py`
- `tools/langgraph-biz-worker/tests/test_e2e_scripted_tool_call_streaming.py`

## Proposed Module Boundaries

Suggested target modules:

| Module | Responsibility |
| --- | --- |
| `runtime/llm_skill_agent.py` | Keep only the high-level agent loop and public `LlmSkillAgent` entry point. |
| `runtime/llm_tool_dispatcher.py` | Dispatch tool calls to business functions, child skills, attachment analysis, artifacts, file tools, public resources, and control tools. |
| `runtime/llm_tool_schemas.py` | Own `_KNOWN_TOOL_SCHEMAS`, schema dedupe, and manifest-to-tool-spec mapping. |
| `runtime/llm_agent_prompts.py` | Build system/user prompts, recoverable interruption prompts, active plan prompts, time context prompts, and visible context prompts. |
| `runtime/llm_child_recovery.py` | Own child skill invocation, resume, recoverable failed child handling, and parent/child recovery event shaping. |
| `runtime/llm_business_function_adapter.py` | Own business function result/suspension/approval helpers and safe summary logic. |
| `runtime/llm_tool_call_codec.py` | Normalize LangChain/OpenAI tool-call shapes, scrub tool args, and sanitize tool call audit payloads. |

The first pass should prefer moving code with minimal edits. Rename or redesign APIs only after tests prove parity.

## Stage A Responsibility Map

Stage A characterization was performed against the current local worktree on 2026-05-18. The file is now 2,211 lines in this workspace, so the original 2,019-line size should be treated as the initial governance trigger rather than the exact current baseline. No runtime code should be moved in Stage A.

Extraction priority:

- `P1`: pure extraction candidate for Stage B; should move with compatibility imports and no behavior rewrite.
- `P2`: extract after dispatcher/adapter seams exist; preserve event payloads and frame mutations exactly.
- `P3`: keep in `llm_skill_agent.py` until the high-risk recovery loop is covered by baseline evidence.

| Lines | Symbol | Current responsibility | Target module | Priority / risk note |
| --- | --- | --- | --- | --- |
| 47-89 | content/safety constants and regexes | Artifact scrub template, progress sink key, business/runtime id detection for summary guardrails. | `llm_tool_call_codec.py` for scrub/progress constants; `llm_business_function_adapter.py` for summary guard constants | P1 for constants only if moved with dependent functions; avoid splitting regexes from guard helpers. |
| 95-105 | `LlmSkillAgent.__init__` | Stores model, runtime, iteration limit, data root. | keep in `llm_skill_agent.py` | P3; public constructor remains stable. |
| 107-250 | `LlmSkillAgent.run` | Main agent loop, manifest lookup, runtime tool context setup, guarded LLM invoke, max-iteration failure handling, persistent interruption recording. | keep in `llm_skill_agent.py` | P3; BUG-027 timeout/retry/deadline/fuse path enters through `invoke_chat_model` here. |
| 252-346 | `LlmSkillAgent._execute_tool_call` | Tool-use event creation, safe arg capture, audit request/response, dispatch invocation, result events, progress sink, suspension/turn completion flags, artifact scrub hook. | `llm_tool_dispatcher.py` | P2; event payload order and `_suspended` semantics must remain byte-compatible where tests assert them. |
| 348-797 | `LlmSkillAgent._call_tool` | Central dispatch for mock tools, attachment analysis, business functions, execution reports, artifacts, files, public resources, child skill invoke/resume, shelving, submit. | `llm_tool_dispatcher.py` with child paths delegated to `llm_child_recovery.py` and function paths to `llm_business_function_adapter.py` | P2/P3; biggest risk area because BUG-027 child failure recovery lives here. |
| 799-872 | `LlmSkillAgent._finalize_business_function_call` | Completes function frames or suspends caller awaiting approval, emits `approval_required`. | `llm_business_function_adapter.py` | P2; approval payload and timeout fields must stay unchanged. |
| 874-879 | `LlmSkillAgent._append_private_message` | Persists private frame messages. | keep in `llm_skill_agent.py` initially | P3; dispatcher can receive a callback later. |
| 881-885 | `LlmSkillAgent._append_tool_call_message` | Records tool call in private messages. | `llm_tool_dispatcher.py` eventually | P2; move with dispatcher or keep callback. |
| 887-917 | `LlmSkillAgent._append_tool_audit` | Writes JSONL tool audit with sanitized result. | `llm_tool_dispatcher.py` | P2; filesystem side effect should move only with tests around redaction/log shape. |
| 920-927 | `LlmSkillAgent._bind_tools` | Binds model tools from manifest-derived specs. | `llm_tool_schemas.py` | P1; pure schema wrapper. |
| 930-964 | `LlmSkillAgent._build_system_prompt` | Builds system prompt, account context, skill instructions, runtime id guardrails, public resource guidance, submit rule. | `llm_agent_prompts.py` | P1; snapshot tests should assert key prompt fragments. |
| 967-984 | `LlmSkillAgent._build_user_prompt` | Builds user prompt from time context, recoverable interruption, active plan, planning policy, request/input, attachments, visible root context. | `llm_agent_prompts.py` | P1; preserve exact ordering because prompt tests depend on visibility and time placement. |
| 991-997 | `_FILE_TOOL_NAMES`, `_PUBLIC_RESOURCE_TOOL_NAMES` | Tool name sets for file/public resource dispatch. | `llm_tool_dispatcher.py` or `llm_tool_schemas.py` | P1/P2; prefer schemas if shared by both specs and dispatch. |
| 1000-1018 | `_runtime_task_scoped_token`, `_runtime_client_app_id`, `_runtime_attachments` | Pulls typed values from runtime context. | token/client app to `llm_tool_dispatcher.py`; attachments to `llm_agent_prompts.py` | P1 if copied with callers; keep compatibility exports. |
| 1021-1032 | `_runtime_context_for_child_skill` | Produces child runtime context and root summary visibility. | `llm_child_recovery.py` | P2; context isolation tests are the guardrail. |
| 1035-1065 | `_resume_parent_if_waiting`, `_record_parent_child_recoverable_interruption` | Parent resume and recoverable child failure recording. | `llm_child_recovery.py` | P3; this is a BUG-027 invariant. |
| 1068-1081 | `_context_visibility_for_child_manifest`, `_normalize_context_visibility` | Child context visibility policy. | `llm_child_recovery.py` | P2; covered by child summary/isolated/passthrough prompt tests. |
| 1084-1111 | `_recoverable_interruption_context`, `_active_plan_context` | Reads frame working state into prompt runtime context. | `llm_agent_prompts.py` or `llm_child_recovery.py` | P1/P2; prompt extraction is fine, but pending-child fields must stay intact. |
| 1114-1232 | prompt builders | Recoverable interruption, active plan, root planning policy, visible context, time context prompts. | `llm_agent_prompts.py` | P1; pure extraction target. |
| 1235-1301 | time helpers | Runtime time resolution, local timezone fallback, ISO parsing, month range. | `llm_agent_prompts.py` | P1; no runtime mutation. |
| 1304-1313 | `_emit_progress_event` | Emits tool events to optional runtime sink. | `llm_tool_dispatcher.py` | P2; maintain best-effort swallow behavior. |
| 1316-1324 | business function name helpers | Detect and split direct function tool names such as `function_id@version`. | `llm_business_function_adapter.py` | P1; pure helpers. |
| 1327-1400 | `_dispatch_file_tool`, `_dispatch_public_resource_tool` | Delegates file/public resource tool calls and translates `FileToolError`. | `llm_tool_dispatcher.py` | P2; low state risk but belongs with dispatch split. |
| 1408-1445 | `_tool_specs`, `_dedupe_tool_specs`, global/hidden tool sets | Builds bound tool schema list and deduplicates schemas. | `llm_tool_schemas.py` | P1; pure extraction target. |
| 1448-1861 | `_KNOWN_TOOL_SCHEMAS` | Function tool schema registry. | `llm_tool_schemas.py` | P1; move without changing schema payloads. |
| 1869-1906 | `_scrub_create_artifact_content` | Removes large `create_artifact.content` from active LLM context. | `llm_tool_call_codec.py` | P1; pure helper but must preserve LangChain and OpenAI shapes. |
| 1914-1949 | `_extract_tool_calls`, `_normalize_tool_call`, `_normalize_openai_tool_call` | Normalizes LangChain/OpenAI tool-call representations. | `llm_tool_call_codec.py` | P1; pure extraction target. |
| 1952-2017 | `_safe_content`, execution-report payload helpers, `_safe_tool_call_args` | JSON-safe payload copy, report ref/digest extraction, secret arg redaction. | `llm_tool_call_codec.py`; child approval payload may later live in `llm_child_recovery.py` | P1/P2; keep shared helpers importable to avoid circulars. |
| 2020-2149 | final summary guard helpers | Prevents runtime ids/page actions being presented as business order identifiers; derives safe fallback summary. | `llm_business_function_adapter.py` | P1/P2; extraction is pure, but tests must pin final summary behavior. |
| 2152-2164 | `_tool_function_id` | Resolves function id for tool event metadata. | `llm_tool_dispatcher.py` or `llm_business_function_adapter.py` | P2; event metadata compatibility risk. |
| 2167-2200 | business suspension helpers | Detects suspension/approval, extracts suspend id/timeout, builds approval summary. | `llm_business_function_adapter.py` | P1; move with `_finalize_business_function_call` or keep compatibility imports. |
| 2203-2211 | `_manifest_for_frame` | Uses frame-frozen manifest snapshot before registry fallback. | keep in `llm_skill_agent.py` | P3; protects active execution from registry reload. |

## Stage A Safety Coverage Map

Existing focused tests that should be treated as the Stage A characterization baseline:

| Invariant | Current coverage |
| --- | --- |
| Regular skill completion | `tests/test_llm_skill_agent.py::test_llm_agent_completes_skill_via_submit_tool` |
| LLM timeout/retry/deadline/fuse semantics | `test_llm_agent_times_out_hung_model_and_fails_frame`, `test_llm_agent_retries_transient_timeout_and_completes_frame`, `tests/test_llm_call_guard.py` |
| Persistent root turn completion | `test_llm_agent_persistent_frame_submit_keeps_frame_running`, `test_llm_agent_persistent_frame_submit_persists_active_plan` |
| Root "continue" prompt context | `test_llm_agent_persistent_frame_prompt_includes_recoverable_interruption_context`, `test_llm_agent_persistent_frame_prompt_includes_pending_recoverable_child` |
| Failed child frame reopened by user "继续" | `test_persistent_root_child_timeout_records_recoverable_interruption`, `test_persistent_root_continue_reopens_failed_child_frame_after_timeout`, `test_llm_agent_root_resumes_pending_recoverable_child_frame` |
| Interrupted child/user cancel lifecycle | `tests/test_frame_interruption.py::test_user_cancelled_waiting_child_records_child_and_reuses_root`, nested child interruption tests |
| Approval wait and resume | `test_llm_agent_bubbles_child_business_function_approval_to_root`, `test_resume_approval_completes_pending_function_frame` |
| Business function suspension | `test_llm_agent_suspends_business_function_call` |
| Attachment analysis and attachment propagation | `test_llm_agent_analyzes_attachment_with_vision_config`, fallback/required-config tests, `test_llm_agent_child_skill_receives_sanitized_attachment_context` |
| Output summary guard | `test_llm_agent_does_not_turn_tms_draft_or_frame_id_into_order_number`, `test_llm_agent_allows_explicit_order_number_field_in_final_summary`, `test_llm_agent_page_action_without_business_id_does_not_generate_order_number` |
| Tool schema visibility | `test_llm_agent_exposes_only_invoke_business_function_tool_without_skill_allowlist`, persistent root shelve exposure tests |

## Phased Plan

### Stage A - Characterization

- [x] Generate a local responsibility map for `llm_skill_agent.py` by function/range.
- [x] Add or identify focused tests for these invariants:
  - regular skill completion;
  - persistent root turn completion;
  - interrupted root "继续";
  - interrupted child "继续";
  - failed child frame reopened by user "继续";
  - approval wait and resume;
  - business function suspension;
  - attachment analysis;
  - output summary guard.
- [ ] Capture a targeted baseline test run before the first extraction.
- [ ] Confirm full worker tests are green before moving code.

### Stage B - Low-Risk Extraction

- [x] Extract prompt builders to `llm_agent_prompts.py`.
- [x] Extract tool schema definitions and `_tool_specs` to `llm_tool_schemas.py`.
- [x] Extract tool-call normalization and safe audit helpers to `llm_tool_call_codec.py`.
- [x] Keep compatibility imports in `llm_skill_agent.py` until all tests pass.
- [x] Risk control: do not edit `LlmSkillAgent.run`, `_execute_tool_call`, `_call_tool`, or child recovery branches in this stage.

### Stage C - Tool Dispatch Split

- [x] Introduce `LlmToolDispatcher` or equivalent functional dispatcher.
- [x] Move artifact, file, public resource, attachment analysis, business function, and child skill dispatch behind explicit methods.
- [x] Preserve existing event payloads and tool result JSON exactly where tests depend on them.
- [x] Risk control: move one dispatch group at a time; keep `_suspended`, `_events`, execution-report refs, progress sink behavior, and tool audit payloads stable.

### Stage D - Recovery Boundary

- [x] Move child invocation/resume/recoverable failed child logic into `llm_child_recovery.py`.
- [x] Keep BUG-027 semantics explicit:
  - child LLM timeout may mark child frame `FAILED`;
  - parent root remains `RUNNING` with `continuation_state=INTERRUPTED`;
  - root retains `pending_recoverable_child_frame_id`;
  - user "继续" reopens the same child frame and continues it.
- [x] Add focused tests if any behavior is currently only covered by broad integration tests.
- [x] Risk control: no semantic rewrite of `SkillRuntime.record_recoverable_child_interruption`, `prepare_recoverable_child_resume`, or `record_recoverable_interruption`.

### Stage E - Cleanup

- [x] Remove compatibility shims after call sites are stable.
- [x] Reduce `llm_skill_agent.py` to the public class, constructor, `run()`, and narrow orchestration helpers.
- [x] Update code inventory docs if module ownership changes.

## Acceptance Criteria

1. `llm_skill_agent.py` is reduced to a focused orchestration file, ideally under 600 lines.
2. Extracted modules each have one clear ownership area and no circular imports.
3. All existing behavior remains compatible:
   - persistent root frames remain reusable;
   - failed child frames can still be continued;
   - approval and business function suspension semantics remain unchanged;
   - attachment analysis remains opt-in and uses configured vision/reasoning model config.
4. Python worker full tests pass.
5. At least one targeted test file exists or is updated for each extracted responsibility with meaningful coverage.
6. The refactor can be reviewed in small commits or stages, with no mixed feature changes.

## Risk Controls

- Start with pure extraction, not redesign.
- Keep names and payload shapes stable during Stage B and Stage C.
- Run focused tests after each extraction stage, then full `tools/langgraph-biz-worker/tests`.
- Avoid editing `skill_runtime.py` unless recovery behavior requires it and a regression test is added first.
- Keep BUG-027 and REQ-030 tests in the safety set for every stage.

## Verification Plan

Minimum commands after each stage:

```powershell
cd tools/langgraph-biz-worker
$env:PYTHONPATH='src'
.\.venv\Scripts\python -m pytest tests/test_llm_skill_agent.py tests/test_llm_call_guard.py tests/test_frame_interruption.py tests/test_frame_lifecycle.py -q
```

Stage-specific add-ons:

```powershell
# Stage B prompt/schema/codec extraction
.\.venv\Scripts\python -m pytest tests/test_llm_skill_agent.py -q

# Stage C dispatcher and business function adapter extraction
.\.venv\Scripts\python -m pytest tests/test_llm_skill_agent.py tests/test_e2e_scripted_tool_call_streaming.py -q

# Stage D child recovery extraction
.\.venv\Scripts\python -m pytest tests/test_llm_skill_agent.py tests/test_frame_interruption.py tests/test_frame_lifecycle.py tests/test_resume.py -q
```

Before closing the optimization:

```powershell
cd tools/langgraph-biz-worker
$env:PYTHONPATH='src'
.\.venv\Scripts\python -m pytest tests -q
```

## Progress Tracking

### Development Progress

- [x] Identified `llm_skill_agent.py` as a governance target after BUG-027 timeout/recovery work.
- [x] Recorded current size and responsibility spread.
- [x] Stage A responsibility map completed.
- [x] Stage A extraction target classification completed.
- [x] Stage A focused safety test inventory identified.
- [x] Stage B/P1 low-risk extraction completed.
  - Extracted prompt construction and runtime time/recoverable context prompt helpers to `runtime/llm_agent_prompts.py`.
  - Extracted `_KNOWN_TOOL_SCHEMAS`, tool spec assembly, global/hidden tool sets, and schema dedupe to `runtime/llm_tool_schemas.py`.
  - Extracted tool-call normalization, artifact-content scrub, safe content copy, execution-report payload helpers, and safe tool args to `runtime/llm_tool_call_codec.py`.
  - Kept compatibility imports and `LlmSkillAgent._build_system_prompt/_build_user_prompt` aliases in `llm_skill_agent.py`.
  - Current local line counts after P1: `llm_skill_agent.py` 1,368 lines; prompt module 295 lines; schema module 463 lines; codec module 160 lines.
- [x] Stage C/P2 low-risk helper extraction completed.
  - Extracted business function id parsing, suspension/approval summary helpers, and final-summary guardrail helpers to `runtime/llm_business_function_adapter.py`.
  - Extracted progress event sink emission, file/public resource dispatch helpers, and tool `function_id` metadata resolution to `runtime/llm_tool_dispatcher.py`.
  - Kept `_execute_tool_call`, `_call_tool`, `_finalize_business_function_call`, child recovery branches, and BUG-027 recovery control flow in `llm_skill_agent.py`.
  - Current local line counts after P2: `llm_skill_agent.py` 1,058 lines; prompt module 295 lines; schema module 463 lines; codec module 160 lines; business adapter 231 lines; dispatcher helper module 117 lines.
- [x] Stage C/P3-P4 tool dispatch split completed for non-control tool groups.
  - Introduced `LlmToolDispatcher` and `LlmToolDispatchContext` in `runtime/llm_tool_dispatcher.py`.
  - Moved mock tools, attachment analysis, execution report reads, artifact tools, file tools, public resource tools, business function discovery, explicit `invoke_business_function`, and direct function-id dispatch behind dispatcher methods.
  - Kept `_execute_tool_call` in `llm_skill_agent.py` so tool-use/tool-result event creation, audit logging, `_suspended`, persistent turn completion, and artifact scrub placeholders remain in the original control path.
  - Kept `_finalize_business_function_call` in `llm_skill_agent.py` and invoked it through a dispatcher callback so approval/suspension frame mutations and payload fields stay unchanged.
- [x] Stage D/P5 recovery boundary extracted.
  - Added `runtime/llm_child_recovery.py`.
  - Moved child skill invocation, recoverable child resume, parent resume-if-waiting, child runtime context visibility, and recoverable child interruption recording helpers.
  - Preserved BUG-027 semantics: failed child frames remain recoverable on user "continue", parent root interruption state is recorded, and approval bubble behavior is unchanged.
  - Current local line counts after P5: `llm_skill_agent.py` 638 lines; dispatcher 387 lines; child recovery 297 lines; prompt module 295 lines; schema module 463 lines; codec module 160 lines; business adapter 231 lines.
- [x] Stage E cleanup completed.
  - Moved tool audit JSONL writing and runtime client app id lookup into `runtime/llm_tool_dispatcher.py`.
  - Moved model tool binding into `runtime/llm_tool_schemas.py`.
  - Kept `LlmSkillAgent.run`, `_execute_tool_call`, `_call_tool`, and `_finalize_business_function_call` as the remaining orchestration/control boundary; no timeout, retry, deadline, fuse, child recovery, or business suspension semantics were rewritten.
  - Current local line counts after Stage E: `llm_skill_agent.py` 599 lines; dispatcher 429 lines; child recovery 297 lines; prompt module 295 lines; schema module 475 lines; codec module 160 lines; business adapter 231 lines.

### Test Progress

- [x] Focused safety tests identified for Stage A characterization.
- [ ] Baseline targeted tests captured before first extraction. Not captured before P1 started; use the post-P1 targeted run below as the first recorded extraction safety evidence.
- [x] Post-P1 targeted tests passed on 2026-05-18:
  - `cd tools/langgraph-biz-worker; $env:PYTHONPATH='src'; .\.venv\Scripts\python -m pytest tests/test_llm_skill_agent.py tests/test_artifact_context_governance.py -q`
  - Result: 42 passed in 1.67s.
- [x] Post-P1 stage safety set passed on 2026-05-18:
  - `cd tools/langgraph-biz-worker; $env:PYTHONPATH='src'; .\.venv\Scripts\python -m pytest tests/test_llm_skill_agent.py tests/test_llm_call_guard.py tests/test_frame_interruption.py tests/test_frame_lifecycle.py tests/test_artifact_context_governance.py -q`
  - Result: 73 passed in 2.46s.
- [x] Pre-P2 full Python worker baseline passed on 2026-05-18:
  - `cd tools/langgraph-biz-worker; $env:PYTHONPATH='src'; .\.venv\Scripts\python -m pytest tests -q`
  - Result: 436 passed, 6 skipped, 10 warnings in 20.62s.
  - Note: this was captured before P2 extraction, not before the first P1 extraction.
- [x] Post-P2 business adapter focused test passed on 2026-05-18:
  - `cd tools/langgraph-biz-worker; $env:PYTHONPATH='src'; .\.venv\Scripts\python -m pytest tests/test_llm_skill_agent.py -q`
  - Result: 38 passed in 1.62s.
- [x] Post-P2 stage safety set passed on 2026-05-18:
  - `cd tools/langgraph-biz-worker; $env:PYTHONPATH='src'; .\.venv\Scripts\python -m pytest tests/test_llm_skill_agent.py tests/test_llm_call_guard.py tests/test_frame_interruption.py tests/test_frame_lifecycle.py tests/test_artifact_context_governance.py -q`
  - Result: 73 passed in 2.43s.
- [x] Post-P2 Stage C add-on passed on 2026-05-18:
  - `cd tools/langgraph-biz-worker; $env:PYTHONPATH='src'; .\.venv\Scripts\python -m pytest tests/test_llm_skill_agent.py tests/test_e2e_scripted_tool_call_streaming.py -q`
  - Result: 49 passed, 3 warnings in 13.09s.
- [x] Post-P2 full Python worker test suite passed on 2026-05-18:
  - `cd tools/langgraph-biz-worker; $env:PYTHONPATH='src'; .\.venv\Scripts\python -m pytest tests -q`
  - Result: 436 passed, 6 skipped, 10 warnings in 19.54s.
- [x] Pre-P3 stage safety baseline passed on 2026-05-18:
  - `cd tools/langgraph-biz-worker; $env:PYTHONPATH='src'; .\.venv\Scripts\python -m pytest tests/test_llm_skill_agent.py tests/test_llm_call_guard.py tests/test_frame_interruption.py tests/test_frame_lifecycle.py tests/test_artifact_context_governance.py -q`
  - Result: 73 passed in 2.46s.
- [x] Post-P3 low-risk dispatcher focused test passed on 2026-05-18:
  - `cd tools/langgraph-biz-worker; $env:PYTHONPATH='src'; .\.venv\Scripts\python -m pytest tests/test_llm_skill_agent.py -q`
  - Result: 38 passed in 1.64s.
- [x] Post-P4 business dispatcher focused test passed on 2026-05-18:
  - `cd tools/langgraph-biz-worker; $env:PYTHONPATH='src'; .\.venv\Scripts\python -m pytest tests/test_llm_skill_agent.py -q`
  - Result: 38 passed in 1.67s after preserving the existing `llm_skill_agent.invoke_business_function` monkeypatch compatibility point.
- [x] Post-P5 child recovery focused test passed on 2026-05-18:
  - `cd tools/langgraph-biz-worker; $env:PYTHONPATH='src'; .\.venv\Scripts\python -m pytest tests/test_llm_skill_agent.py -q`
  - Result: 38 passed in 1.69s.
- [x] Post-P5 stage safety set passed on 2026-05-18:
  - `cd tools/langgraph-biz-worker; $env:PYTHONPATH='src'; .\.venv\Scripts\python -m pytest tests/test_llm_skill_agent.py tests/test_llm_call_guard.py tests/test_frame_interruption.py tests/test_frame_lifecycle.py tests/test_artifact_context_governance.py -q`
  - Result: 73 passed in 2.43s.
- [x] Post-P5 Stage C add-on passed on 2026-05-18:
  - `cd tools/langgraph-biz-worker; $env:PYTHONPATH='src'; .\.venv\Scripts\python -m pytest tests/test_llm_skill_agent.py tests/test_e2e_scripted_tool_call_streaming.py -q`
  - Result: 49 passed, 3 warnings in 13.40s.
- [x] Post-P5 full Python worker test suite passed on 2026-05-18:
  - `cd tools/langgraph-biz-worker; $env:PYTHONPATH='src'; .\.venv\Scripts\python -m pytest tests -q`
  - Result: 436 passed, 6 skipped, 10 warnings in 19.86s.
- [x] Post-Stage-E focused test passed on 2026-05-18:
  - `cd tools/langgraph-biz-worker; $env:PYTHONPATH='src'; .\.venv\Scripts\python -m pytest tests/test_llm_skill_agent.py -q`
  - Result: 38 passed in 1.65s.
- [x] Post-Stage-E stage safety set passed on 2026-05-18:
  - `cd tools/langgraph-biz-worker; $env:PYTHONPATH='src'; .\.venv\Scripts\python -m pytest tests/test_llm_skill_agent.py tests/test_llm_call_guard.py tests/test_frame_interruption.py tests/test_frame_lifecycle.py tests/test_artifact_context_governance.py -q`
  - Result: 73 passed in 2.55s.
- [x] Full Python worker test suite passes after final extraction.
  - `cd tools/langgraph-biz-worker; $env:PYTHONPATH='src'; .\.venv\Scripts\python -m pytest tests -q`
  - Result: 436 passed, 6 skipped, 10 warnings in 22.30s.

### Experience Progress

- [x] N/A for Stage A. This work item is backend Python runtime/module governance only and has no UI workflow change in the characterization stage.
- [x] N/A for P1. The change is pure Python runtime module extraction with no UI workflow or display change.
- [x] N/A for P2. The change is Python runtime helper extraction only and has no UI workflow or display change.
- [x] N/A for P3-P5. The change is Python runtime module extraction only and has no UI workflow or display change.
- [x] N/A for Stage E. The change is Python runtime helper cleanup only and has no UI workflow or display change.

### Execution Check-in - 2026-05-18 P1

- Completed work: P1 pure extraction only; no dispatcher split, child recovery rewrite, business function behavior change, or prompt content rewrite.
- Touched code paths:
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_agent_prompts.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_tool_schemas.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_tool_call_codec.py`
- Self-check:
  - [x] BUG-027 timeout/retry/deadline/fuse entry point remains in `LlmSkillAgent.run` through `invoke_chat_model`.
  - [x] `invoke_business_skill`, `resume_recoverable_child_skill`, `shelve_interrupted_frame`, and business function suspension branches were not moved.
  - [x] Existing private helper names remain importable from `llm_skill_agent.py` during the compatibility period.
  - [x] No public API payload or SSE event contract was intentionally changed.
- Test status: pass for the post-P1 targeted and stage safety commands listed above.
- Remaining risks: Stage C/D still carry the real behavior risk because tool dispatch and child recovery are still inside `llm_skill_agent.py`.
- Acceptance readiness: P1 is ready for review as a scoped extraction; the overall OPT-031 item remains open.

### Execution Check-in - 2026-05-18 P2

- Completed work: P2 helper extraction only; no dispatcher class rewrite, no child recovery extraction, and no business function lifecycle behavior change.
- Touched code paths:
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_business_function_adapter.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_tool_dispatcher.py`
- Self-check:
  - [x] BUG-027 timeout/retry/deadline/fuse entry point remains in `LlmSkillAgent.run` through `invoke_chat_model`.
  - [x] `_execute_tool_call`, `_call_tool`, `_finalize_business_function_call`, `invoke_business_skill`, `resume_recoverable_child_skill`, and recoverable child interruption recording were not moved.
  - [x] User "继续" recovery remains on the existing failed child frame path; no frame lifecycle mutation logic was rewritten.
  - [x] Business function approval/suspension payload construction remains called from `_finalize_business_function_call` with unchanged field names.
  - [x] Existing private helper names remain importable from `llm_skill_agent.py` through compatibility imports.
  - [x] No public API payload or SSE event contract was intentionally changed.
- Test status: pass for the pre-P2 baseline, post-P2 focused/stage/add-on commands, and post-P2 full suite listed above.
- Remaining risks: full `LlmToolDispatcher` extraction and `llm_child_recovery.py` boundary are still pending; these are the high-risk Stage C/D parts.
- Acceptance readiness: P2 is ready for review as a scoped helper extraction; the overall OPT-031 item remains open.

### Execution Check-in - 2026-05-18 P3-P5

- Completed work: P3-P5 extraction across dispatcher and child recovery boundaries; no feature behavior rewrite was intended.
- Touched code paths:
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_tool_dispatcher.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_child_recovery.py`
- Self-check:
  - [x] BUG-027 timeout/retry/deadline/fuse entry point remains in `LlmSkillAgent.run` through `invoke_chat_model`.
  - [x] `_execute_tool_call` remains in `llm_skill_agent.py`; event creation, progress sink emission, audit logging, `_suspended`, and artifact scrub handling were not reimplemented.
  - [x] `_finalize_business_function_call` remains in `llm_skill_agent.py`; dispatcher calls it as a callback for unchanged approval/suspension behavior.
  - [x] Existing test monkeypatch path `llm_skill_agent.invoke_business_function` remains supported by passing the module-level callable into the dispatcher.
  - [x] Child skill invoke/resume logic moved to `llm_child_recovery.py` with the same event shapes, `child_frame_id`, `_events`, `_suspended`, approval bubble, and failed-child recoverability semantics.
  - [x] User "continue" recovery remains covered by focused tests for reopening the failed child frame.
  - [x] No public API payload or SSE event contract was intentionally changed.
- Test status: pass for the pre-P3 baseline, post-P3/P4/P5 focused tests, post-P5 stage safety set, Stage C add-on, and full suite listed above.
- Remaining risks: Stage E cleanup is still open; `llm_skill_agent.py` is now close to the target at 638 lines but still contains submit/shelve control handling, audit helpers, and business finalize logic.
- Acceptance readiness: P3-P5 are ready for review as scoped extraction work; the overall OPT-031 item remains open until Stage E cleanup/closure.

### Execution Check-in - 2026-05-18 Stage E

- Completed work: final cleanup-only extraction for tool audit, runtime client app id lookup, and model tool binding; `llm_skill_agent.py` is now 599 lines.
- Touched code paths:
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_tool_dispatcher.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_tool_schemas.py`
- Self-check:
  - [x] BUG-027 timeout/retry/deadline/fuse entry point remains in `LlmSkillAgent.run` through `invoke_chat_model`.
  - [x] `_execute_tool_call` still owns tool-use/tool-result event creation, progress sink emission, `_suspended`, persistent turn completion, and artifact scrub handling.
  - [x] Tool audit JSONL output keeps the same path, timestamp source, payload fields, result scrubbing, and exception-swallow behavior.
  - [x] Public resource client app id lookup still accepts both `client_app_id` and `clientAppId`.
  - [x] `_call_tool`, `submit_skill_result`, `shelve_interrupted_frame`, and `_finalize_business_function_call` behavior was not rewritten.
  - [x] Child recovery remains delegated to `llm_child_recovery.py`; user "continue" recovery on the failed child frame remains covered by the focused safety tests.
  - [x] No public API payload or SSE event contract was intentionally changed.
- Test status: pass for the post-Stage-E focused test, stage safety set, and full Python worker suite listed above.
- Remaining risks: no known Stage E behavior risk; remaining compatibility imports in `llm_skill_agent.py` are private helper re-exports kept for conservative reviewability.
- Acceptance readiness: OPT-031 implementation is ready for review as staged extraction work.

### Implementation Quality Gate - 2026-05-18

- Quality record: `docs/version-tracker/1.3.0-SNAPSHOT/quality/OPT-031-llm-skill-agent-module-governance-implementation-quality.md`
- Decision: `ready-for-coverage-audit`.
- Findings: no blocking implementation issues found.
- Follow-up: run `foggy-test-coverage-audit` before formal acceptance; if future work continues splitting `_call_tool` or `_finalize_business_function_call`, add module-level dispatcher/recovery tests first.

### Test Coverage Audit - 2026-05-18

- Coverage record: `docs/version-tracker/1.3.0-SNAPSHOT/coverage/OPT-031-llm-skill-agent-module-governance-coverage-audit.md`
- Conclusion: `ready-with-gaps`.
- Covered: Stage B through Stage E focused, stage safety, Stage C add-on, and full worker pytest evidence; BUG-027 timeout/retry/deadline/fuse; failed child frame user "continue" recovery; business function approval/suspension; attachment/artifact scrub paths.
- Non-blocking gaps: no dedicated direct module-level tests for `LlmToolDispatcher` and `llm_child_recovery`; no direct audit JSONL field assertion after Stage E; no pre-P1 targeted baseline captured before the first extraction.
- Acceptance readiness: ready for `foggy-acceptance-signoff` with these gaps acknowledged.

## Related Work

- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/BUG-027-biz-worker-llm-call-timeout-fuse-missing.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/BUG-028-tms-ticket-agent-attachment-not-delivered.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/REQ-030-biz-worker-on-demand-attachment-analysis-and-vision-model-config.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/OPT-029-upstream-timeout-governance.md`
