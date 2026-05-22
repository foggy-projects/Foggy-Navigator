---
type: bug
bug_source: user-report
version: 1.1.6-SNAPSHOT
ticket: BUG-child-agent-inherits-root-runtime-history
severity: major
status: ready-for-verification
reproduction_status: confirmed
test_strategy: unit-test
automation_decision: required
owner: langgraph-biz-worker
---

# BUG: Child Agent Inherits Root Runtime History

## Background

After the Skill / Agent / Frame boundary refactor, plain Skill loading stays in the current frame, while explicit Agent delegation opens a non-root Agent frame. The Agent frame is intended to be an isolated lifecycle container: it receives handoff input and necessary execution environment, then promotes a controlled result back to the parent.

In the TMS smoke session, a child Agent LLM submission still included Root conversation history such as the user's initial `hi` and the Root assistant greeting. This happened because the child runtime context was built with a shallow copy of the parent `runtime_context`.

## Reproduction

Evidence session:

- `contextId`: `bctx_20260522_70_70e42656de864a18a6dd565a0aa49aca`
- child Agent submission: `logs/llm-submissions/000005_tms-ticket-agent_lgt_38ddbbed3c074ff9_frm_f81fdd49e0c0_iter01_attempt01.json`

Observed child submission contained prior Root messages before the current handoff:

```text
human: hi
assistant: 你好！有什么我可以帮你的吗？
human: 随便调个工具，我做测试
```

## Expected vs Actual

Expected:

- Child Agent first submission contains its own system prompt plus the parent handoff instruction/input.
- Child Agent recovery restores only that child frame's own runtime-message-events.
- Root-visible protocol, Root business Skill catalog, and Root memory callbacks are not inherited by default.

Actual:

- `_runtime_context_for_child_agent()` copied the whole parent runtime context.
- `_runtime_visible_conversation` and `_model_visible_business_context` leaked into child prompt assembly.
- Child Agent submissions could receive Root history and Root allowed Skill catalog as if they were local child context.

## Impact Scope

- Agent frame isolation and context governance.
- LLM submission logs for delegated Agent frames.
- Future `AWAITING_USER` / TIMEOUT / ERROR recovery, because it should restore the leaf frame's own protocol rather than root history.

## Test Strategy

Required automated coverage:

- Invoking a child Agent from Root with `_runtime_visible_conversation` present must not inject Root user/assistant history into the child messages.
- Root `_model_visible_business_context.allowed_skills` must not appear in child Agent system prompt by default.
- Existing summary visibility coverage must continue to allow controlled root summary injection only when `context_visibility=summary`.

Manual verification:

- A real TMS smoke that explicitly opens an Agent frame should show child `llm-submissions/*.json` with only child system + handoff / child recovery messages, not the full Root conversation.

## Code Inventory

- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_child_recovery.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_message_builder.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`
- `tools/langgraph-biz-worker/tests/test_llm_skill_agent.py`
- `docs/version-tracker/1.1.6-SNAPSHOT/09-llm-submission-message-contract.md`
- `docs/version-tracker/1.1.6-SNAPSHOT/12-agent-frame-and-skill-tool-boundary.md`

## Fix Checklist

- [x] Replace parent runtime context shallow-copy with child-frame allowlist copying.
- [x] Keep execution-scoped inputs such as `contextId`, `clientAppId`, `task_scoped_token`, `llm_config`, `execution_policy`, attachments and date context.
- [x] Strip Root-visible protocol, visible recent conversation, Root business Skill catalog and Root memory callbacks from child Agent context.
- [x] Preserve controlled root summary injection for `context_visibility=summary`.
- [x] Add regression coverage for child Agent prompt isolation.
- [x] Update design docs.

## Verification

- `tools/langgraph-biz-worker`: `.\.venv\Scripts\python.exe -m pytest tests/test_llm_skill_agent.py -q` passed, including the new child Agent isolation regression and existing summary / attachment / timeout recovery coverage.
- `tools/langgraph-biz-worker`: `.\.venv\Scripts\python.exe -m pytest tests/test_root_graph.py::test_system_root_manifest_allows_plain_final_and_discourages_submit_for_simple_replies tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_root_prompt_contract_ignores_skill_alias_and_keeps_user_message_clean tests/test_llm_tool_schemas.py -q` passed.
- `tools/langgraph-biz-worker`: `.\.venv\Scripts\python.exe -m ruff check src/langgraph_biz_worker/runtime/llm_child_recovery.py src/langgraph_biz_worker/graphs/root_graph.py src/langgraph_biz_worker/runtime/llm_agent_prompts.py src/langgraph_biz_worker/runtime/llm_tool_schemas.py tests/test_llm_skill_agent.py tests/test_root_graph.py tests/test_e2e_scripted_tool_call_streaming.py tests/test_llm_tool_schemas.py` passed.

## References

- `docs/version-tracker/1.1.6-SNAPSHOT/09-llm-submission-message-contract.md`
- `docs/version-tracker/1.1.6-SNAPSHOT/12-agent-frame-and-skill-tool-boundary.md`
