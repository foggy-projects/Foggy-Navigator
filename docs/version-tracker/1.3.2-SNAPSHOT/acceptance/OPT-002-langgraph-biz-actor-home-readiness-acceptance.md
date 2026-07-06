---
acceptance_scope: feature
version: 1.3.2-SNAPSHOT
target: OPT-002-langgraph-biz-actor-home-readiness
doc_role: acceptance-record
doc_purpose: 说明本文件用于 LangGraph Biz Actor Home readiness 功能级验收记录
status: ready-for-signoff
decision: pending
reviewed_by: Codex
blocking_items: []
follow_up_required: no
evidence_count: 7
---

# Feature Acceptance

## Document Purpose

- doc_type: acceptance
- intended_for: signoff-owner | reviewer | owning-module
- purpose: 记录 `OPT-002: LangGraph Biz Actor Home Readiness` 的验收依据、证据和待签收结论。

## Acceptance Basis

- [workitem](../workitems/OPT-002-langgraph-biz-actor-home-readiness.md)
- [implementation quality gate](../quality/OPT-002-implementation-quality.md)
- [test coverage audit](../coverage/OPT-002-coverage-audit.md)

## Checklist

- [x] `LANGGRAPH_BIZ` Actor-owned task missing `directoryId` is rejected before side effects.
- [x] OpenAPI missing-directory failure uses `TASK_DIRECTORY_REQUIRED`.
- [x] A2A/direct/launcher carry `directoryId` and `cwd`.
- [x] `runtime_context` alias is accepted and sanitized.
- [x] readiness reports managed/delegated file tool root diagnostics.
- [x] delegated Python file writes land under Actor Home and report `storage_mode=delegated`.
- [x] Focused Java/Python tests passed.
- [x] Experience validation is N/A because no UI changed.

## Evidence

- `mvn test -pl business-agent-module -am "-Dtest=BusinessAgentTaskServiceTest" ...`: pass, 20 tests.
- `mvn test -pl addons/claude-worker-agent -am "-Dtest=OpenApiAgentReadinessServiceTest" ...`: pass, 18 tests.
- `mvn test -pl addons/claude-worker-agent -am "-Dtest=OpenApiControllerMessageMappingTest" ...`: pass, 33 tests.
- `mvn test -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphTaskServiceTest,LanggraphBusinessAgentWorkerTaskLauncherTest" ...`: pass, 31 tests.
- `mvn test -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphWorkerInnerA2aAgentTest" ...`: pass, 11 tests.
- `.venv\Scripts\python.exe -m pytest tests/test_account_file_tools.py tests/test_owner_aware_runtime_contract.py`: pass, 43 passed, 2 skipped.
- `git diff --check`: pass, CRLF normalization warnings only.

## Failed Items

- none

## Risks / Open Items

- blocking risks: none
- operational note:
  - Production Actor Home directory permissions and allowed_dirs policy remain deployment responsibilities.
  - Python focused tests require the project `.venv`; system Python missing `langchain_core` is an environment gap, not a product failure.

## Final Decision

`OPT-002: LangGraph Biz Actor Home Readiness` is ready for signoff.

No blocking implementation or coverage gaps remain. Final acceptance can be signed after reviewer confirms the scoped dirty diff should be committed as this OPT-002 change set.
