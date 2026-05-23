---
type: bug
bug_source: user-report
version: 1.1.6-SNAPSHOT
ticket: BUG-root-overdelegates-business-skill-to-agent-frame
severity: major
status: ready-for-verification
reproduction_status: confirmed
test_strategy: unit-test
automation_decision: required
owner: langgraph-biz-worker
---

# BUG: Root Overdelegates Ordinary Business Skill To Agent Frame

## Background

In the TMS smoke conversation, an ordinary user request to create or test a ticket was routed by the root LLM through `invoke_business_agent(tms-ticket-agent)`. This opened a child Agent frame and changed the LLM system prompt to a delegated Agent prompt.

This is not aligned with the current runtime-context design. Ordinary business Skill usage should behave like Codex/Claude Code style Skill loading: load Skill material through a tool and keep the material, tool call, and tool result in the current root conversation context. Agent frames are reserved for explicit sub-agent delegation or work that truly needs an isolated lifecycle.

## Reproduction

Evidence session:

- `contextId`: `bctx_20260522_70_70e42656de864a18a6dd565a0aa49aca`
- root LLM submission: `logs/llm-submissions/000004_conversation.root_lgt_38ddbbed3c074ff9_frm_ffc264c04e07_iter01_attempt01.json`
- child Agent LLM submission: `logs/llm-submissions/000005_tms-ticket-agent_lgt_38ddbbed3c074ff9_frm_f81fdd49e0c0_iter01_attempt01.json`

Observed root tool call:

```json
{
  "name": "invoke_business_agent",
  "args": {
    "agent_id": "tms-ticket-agent",
    "instruction": "用户正在做测试，需要提交一个测试工单。请引导用户完成工单创建流程，或者如果技能支持直接创建，请创建一个测试用的工单。"
  }
}
```

## Expected vs Actual

Expected:

- Ordinary business Skill requests call `invoke_business_skill`.
- Skill material is loaded into the current root frame context.
- The root system prompt remains the normal root orchestration prompt.
- No child Agent frame is opened merely because the Skill bundle name contains `agent`.

Actual:

- Root called `invoke_business_agent`.
- A child frame `frm_f81fdd49e0c0` was opened.
- The next LLM submission used a delegated `tms-ticket-agent` system prompt.

## Impact Scope

- Root conversation runtime context diverges from Codex/Claude Code style Skill loading.
- User-visible flow can enter child-frame lifecycle unnecessarily.
- Prompt behavior becomes harder to reason about because ordinary Skill usage changes the system prompt and available completion contract.

## Test Strategy

Required automated coverage:

- Root manifest must state that ordinary business Skill requests default to `invoke_business_skill`.
- Tool schema must discourage `invoke_business_agent` for ordinary business Skill routing or for bundle names ending with `-agent`.
- Scripted prompt-contract tests must keep user messages clean and preserve Skill metadata in system context.

Manual verification:

- Real LLM smoke should show ordinary TMS Skill requests using `invoke_business_skill` in the root frame and `child_frame_count=0`.

## Code Inventory

- `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_agent_prompts.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_tool_schemas.py`
- `tools/langgraph-biz-worker/tests/test_root_graph.py`
- `tools/langgraph-biz-worker/tests/test_e2e_scripted_tool_call_streaming.py`
- `tools/langgraph-biz-worker/tests/test_llm_tool_schemas.py`

## Fix Checklist

- [x] Tighten root manifest wording: Skill loading is default for ordinary business requests.
- [x] Tighten shared LLM prompt wording: do not open Agent frame merely because a bundle name contains `agent`.
- [x] Tighten `invoke_business_skill` and `invoke_business_agent` tool descriptions.
- [x] Update design docs to preserve the Skill vs Agent boundary.
- [x] Add regression assertions in prompt/schema tests.

## Verification

- `ruff check` on touched BizWorker source and tests.
- Targeted pytest for root prompt contract, scripted prompt contract, and tool schema wording.
- Real LLM smoke confirms the model no longer calls `invoke_business_agent` for the ordinary Skill-loading prompt; the direct smoke needs a valid ClientApp context to fully load TMS public Skill materials.
