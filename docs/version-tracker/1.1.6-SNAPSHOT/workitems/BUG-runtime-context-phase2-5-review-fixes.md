---
type: bug
bug_source: acceptance-found
version: 1.1.6-SNAPSHOT
ticket: BUG-runtime-context-phase2-5-review-fixes
severity: major
status: ready-for-verification
reproduction_status: confirmed
test_strategy: unit-test
automation_decision: required
owner: langgraph-biz-worker
---

# BUG Work Item

## Background

Phase 2-5 runtime context governance review found three production risks:

1. Same `contextId` queued input can be accepted while the current root loop is executing the final submit tool, leaving the queued message unprocessed until a later turn.
2. Context compaction redaction covers query-string and simple `key=value` secrets, but misses JSON-style fields such as `{"token":"..."}`.
3. `ContextRuntimeMemory.commit_turn` returns `False` on invalid assistant projection but can leave a stale `pending_turn`.

## Expected vs Actual

Expected:

- BizWorker must not report `CONTEXT_RUNTIME_QUEUED` for messages that cannot be consumed by the running loop.
- Compaction summarizer input and fallback summaries must not leak JSON-style secrets.
- Failed commits must leave memory reusable for the next turn.

Actual:

- Terminal submit had a short window where a second message could be queued after the last checkpoint and before loop completion.
- JSON fields with quoted sensitive keys were not redacted.
- Invalid commit paths cleared running markers but not always the pending turn.

## Impact Scope

- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/context_memory.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`

## Test Strategy

Unit and focused integration tests:

- queued input arriving before terminal tool execution must force the LLM loop to reconsider instead of executing stale submit
- `FINALIZING` memory must reject new queue attempts with retryable busy
- JSON-style secrets must be redacted before summarizer/prompt projection
- invalid `commit_turn` must clear pending state and allow the next turn

## Fix Checklist

- [x] Add `FINALIZING` runtime memory state for terminal tool execution.
- [x] Skip stale terminal submit if a queued message arrives before the terminal tool runs.
- [x] Reject queue attempts while memory is `FINALIZING`.
- [x] Extend redaction to quoted JSON-style sensitive fields.
- [x] Clear pending turn on invalid commit projection.
- [x] Run target and full test suites.

## Verification

- Target runtime context suite: `98 passed`.
- Full BizWorker Python suite: `573 passed, 6 skipped, 11 warnings`.
- `git diff --check` passed.
