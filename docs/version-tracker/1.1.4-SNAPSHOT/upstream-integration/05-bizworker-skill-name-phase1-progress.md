# BizWorker skill_name Phase 1 Progress

## 文档作用

- doc_type: progress | execution-checkin
- version: 1.1.4-SNAPSHOT
- status: implemented
- date: 2026-05-19
- source: [04-bizworker-skill-name-phase1-execution-plan.md](./04-bizworker-skill-name-phase1-execution-plan.md)
- intended_for: BizWorker implementer | Navigator reviewer
- purpose: 跟踪 BizWorker standalone Phase 1 的开发、测试、自检与后续交接状态

## Scope

Phase 1 只覆盖：

- `skill_name` identity normalization and alias validation.
- Standalone `SkillAgent` facade.
- Minimal `ToolProvider` protocol with mock/local implementations.
- Current request/context compatibility for `skill_name` while keeping internal `skill_id`.
- Targeted Python tests.

Phase 1 不覆盖：

- Java DB/entity/control-plane rename.
- Navigator/TMS OpenAPI `rootAgentId` behavior changes.
- Full Skill CRUD service, CLI, package publishing, HTTP provider.
- Full Python internal `skill_id` rename.

## Step Tracking

| Step | Status | Notes |
| --- | --- | --- |
| 1. Identity normalization | completed | `skill_name` canonical; aliases accepted only when same value |
| 2. Standalone `SkillAgent` facade | completed | Public import path added at `langgraph_biz_worker.SkillAgent` |
| 3. `ToolProvider` boundary | completed | Mock/local provider added and invoked through `SkillAgent.ask` -> `LlmSkillAgent` |
| 4. Query/runtime compatibility | completed | Request/context accept `skill_name`; internal frame keeps `skill_id` |
| 5. Targeted tests | completed | No external LLM APIs |
| 6. Progress writeback | completed | This file updated after coding and testing |

## Development Progress

| Area | Status | Notes |
| --- | --- | --- |
| Docs | completed | 02/04 constraints adjusted before coding; progress updated |
| Code | completed | Phase 1 Python runtime/facade implemented |
| Review | completed | Targeted and compatibility tests passed |

## Testing Progress

| Test | Status | Notes |
| --- | --- | --- |
| `python -m pytest tests/test_skill_identity.py tests/test_tool_provider.py tests/test_skill_agent_facade.py tests/test_root_graph.py tests/test_query.py tests/test_skill_registry_v2.py` | passed | 51 passed |
| `python -m pytest tests/test_llm_skill_agent.py` | passed | 39 passed |
| `python -m pytest tests/test_account_skill_routing.py` | passed | 10 passed |

## Experience Progress

N/A. Phase 1 is Python runtime/API compatibility work with no UI surface.

## Execution Check-in

- Completed work:
  - Added `skill_name` alias normalization and conflict validation for `skill_name`, `skillName`, `skill_id`, `skillId`.
  - Added standalone `SkillAgent` facade and mock/local Python tool provider boundary.
  - Added folder-name alias resolution in `SkillRegistry` while preserving existing manifest `id` lookups.
  - Added `message` request alias and forwarded top-level `skill_name` into root graph state.
  - Updated explicit root routing and child skill invocation to accept `skill_name` while keeping old `skill_id` compatibility.
  - Wired provider tool schemas/calls into `LlmSkillAgent`.
- Touched code paths:
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/models.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/routes/query.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_identity.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_agent.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/tool_provider.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_registry.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_tool_schemas.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_child_recovery.py`
- Tests run:
  - `python -m pytest tests/test_skill_identity.py tests/test_tool_provider.py tests/test_skill_agent_facade.py tests/test_root_graph.py tests/test_query.py tests/test_skill_registry_v2.py`
  - `python -m pytest tests/test_llm_skill_agent.py`
  - `python -m pytest tests/test_account_skill_routing.py`
- Remaining risks:
  - Internal runtime/event/report fields are still named `skill_id`; full internal rename remains out of Phase 1.
  - Skill resource tools still use `skill_id` parameters; they should be handled in a later broader rename pass if needed.
  - Standalone facade is intentionally minimal and has no package publishing/CLI/HTTP provider surface yet.
- Self-check conclusion:
  - Phase 1 implementation matches the documented compatibility boundary: Python callers can use folder-name `skill_name`, old aliases remain compatible, and provider tools are invoked through the normal LLM skill loop.
- Acceptance readiness:
  - Ready for Navigator-side review of the Python runtime change set.
