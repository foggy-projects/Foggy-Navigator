---
type: bug
bug_source: user-report
version: 1.1.6-SNAPSHOT
ticket: BUG-root-system-prompt-submit-tool-overuse
severity: major
status: ready-for-verification
reproduction_status: confirmed
test_strategy: integration-test
automation_decision: required
owner: langgraph-biz-worker
---

# Root System Prompt Overuses `submit_skill_result`

## Background

After the Skill / Agent / Frame boundary refactor, `conversation.root` is a persistent root frame. Ordinary root replies can end a user turn by returning natural language directly. `submit_skill_result` is retained for root only when the model needs to preserve structured state such as `active_plan`, `artifact_refs`, `evidence_refs`, or `structured_output`.

## Reproduction

In a real TMS smoke session, sending a simple `hi` caused the root LLM to call:

```text
submit_skill_result
```

The generated user-visible summary was a generic acknowledgement instead of a direct assistant response.

## Expected vs Actual

Expected:

- Simple greetings and direct Q&A return a natural-language assistant message.
- Root only calls `submit_skill_result` when it needs to preserve structured state.

Actual:

- The root system prompt still said the root agent must use `submit_skill_result` and must not directly output natural language.
- The tool schema did not discourage root from using `submit_skill_result` for ordinary answers.

## Impact Scope

- Root conversation prompt contract.
- Real LLM behavior for greetings and simple answers.
- LLM submission logs, because normal replies were represented as tool calls.

## Test Strategy

- Add prompt contract unit coverage for `_system_root_manifest`.
- Extend the scripted E2E root prompt contract test to reject the old forced-submit wording.
- Run a real LLM smoke after restarting BizWorker to confirm `hi` no longer induces `submit_skill_result`.

## Code Inventory

- `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_agent_prompts.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_tool_schemas.py`
- `tools/langgraph-biz-worker/tests/test_root_graph.py`
- `tools/langgraph-biz-worker/tests/test_e2e_scripted_tool_call_streaming.py`

## Fix Checklist

- [x] Remove root manifest wording that requires `submit_skill_result` for every answer.
- [x] Explicitly tell root to avoid `submit_skill_result` for greetings and simple Q&A.
- [x] Clarify root completion contract in the shared system prompt helper.
- [x] Clarify tool schema guidance for persistent root.
- [x] Run automated regression tests.
- [x] Run real LLM smoke and inspect LLM submission / runtime message logs.

## Verification

- `tools/langgraph-biz-worker`: `.\.venv\Scripts\python.exe -m ruff check src/langgraph_biz_worker/graphs/root_graph.py src/langgraph_biz_worker/runtime/llm_agent_prompts.py src/langgraph_biz_worker/runtime/llm_tool_schemas.py tests/test_root_graph.py tests/test_e2e_scripted_tool_call_streaming.py` passed.
- `tools/langgraph-biz-worker`: `.\.venv\Scripts\python.exe -m pytest tests/test_root_graph.py::test_system_root_manifest_allows_plain_final_and_discourages_submit_for_simple_replies tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_root_prompt_contract_ignores_skill_alias_and_keeps_user_message_clean -q` passed.
- `tools/langgraph-biz-worker`: `.\.venv\Scripts\python.exe -m pytest tests/test_llm_skill_agent.py::test_llm_agent_persistent_frame_prompt_includes_active_plan tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_root_skill_active_plan_survives_across_tasks -q` passed.
- Real LLM smoke session `bctx_20260522_df_dfe66ec07621` with task `lgt_root_plain_dfe66ec07621` returned natural language directly: `你好！有什么我可以帮你的吗？`
- Submission log `000001_conversation.root_lgt_root_plain_dfe66ec07621_frm_1bd2a2392098_iter01_attempt01.json` had role sequence `system,human`, contained the new "普通寒暄、简单问答...不要调用 submit_skill_result" guidance, did not contain the old forced-submit wording, and had no tool calls.
- Runtime event log `lgt_root_plain_dfe66ec07621_frm_1bd2a2392098.jsonl` had zero assistant tool-call events.

## References

- `docs/version-tracker/1.1.6-SNAPSHOT/09-llm-submission-message-contract.md`
- `docs/version-tracker/1.1.6-SNAPSHOT/12-agent-frame-and-skill-tool-boundary.md`
