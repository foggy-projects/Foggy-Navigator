# BUG: Child skill plain assistant response fails subagent protocol

## Status

- Date: 2026-05-22
- Status: fixed in BizWorker runtime
- Scope: `invoke_business_skill` / child frame completion protocol

## Symptom

Observed session:

`tools/langgraph-biz-worker/data/runtime/sessions/by-date/2026/05/22/cf/bctx_20260522_cf_cfa4ee30ed66457b8f35ef174a52c7fd`

Evidence:

- Root frame: `frm_7f4e2c6b3d27`
- Child frame: `frm_2fd5beb3e376`
- Task: `lgt_bfacb0e0fc634061`
- LLM submission: `logs/llm-submissions/000006_tms-ticket-agent_lgt_bfacb0e0fc634061_frm_2fd5beb3e376_iter01_attempt01.json`
- Child frame report: `reports/frm_2fd5beb3e376.md`

The root model correctly called `invoke_business_skill` for `tms-ticket-agent`.
The child model then returned a normal assistant clarification:

`请告诉我您想创建哪种类型的工单？`

BizWorker treated the absence of `submit_skill_result` or `handoff_to_parent` as a hard protocol failure and surfaced:

`Child skill returned a final assistant message without submit_skill_result or handoff_to_parent`

## Root Cause

The child frame protocol was stricter than Codex / Claude Code style subagent behavior.

Earlier design made `submit_skill_result` / `handoff_to_parent` mandatory for every child final response. That was useful for structured frame lifecycle control, but it rejected a common subagent pattern: the child agent can answer naturally, and the orchestrator/runtime normalizes the child response into either a final result or a wait-for-user state.

## Expected Behavior

Explicit runtime tools remain the preferred path when the child needs to return structured state, refs, active plan, or a controlled handoff.

If a child frame returns natural language without tool calls:

1. If the text asks the user for missing information, mark the child frame `AWAITING_USER` and route the next user message directly back to that child.
2. Otherwise, mark the child frame `COMPLETED` and promote the assistant text as the subagent result.
3. Only fail the frame if there is no tool call and no assistant content, or if the normalized output cannot satisfy the output contract.

## Fix

`LlmSkillAgent` now normalizes child plain assistant messages:

- clarification-like text becomes `WAITING_FOR_USER_INPUT` through `submit_user_input_request(...)`
- ordinary final text becomes `FINAL_FOR_USER` through `submit_result(...)`
- the emitted event uses `tool_name=assistant_message` so reports and UI can distinguish implicit subagent completion from an explicit runtime tool call

The child prompt was also softened:

- `submit_skill_result` / `handoff_to_parent` are still recommended for structured state
- direct natural language final messages are allowed and normalized by runtime

## Regression Coverage

Added unit coverage for both branches:

- child plain assistant final completes as a subagent result
- child plain assistant clarification pauses the child frame in `AWAITING_USER`

## Design Note

This keeps existing frame recovery semantics:

- `AWAITING_USER` continues to use active focus / deepest leaf routing
- root-visible protocol still sees the parent `invoke_business_skill` tool result
- child-private trace remains in child frame logs, reports, runtime message events and LLM submission logs
