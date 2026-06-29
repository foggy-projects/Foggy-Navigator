---
audit_scope: feature
audit_mode: pre-acceptance-check
version: 1.3.2-SNAPSHOT
target: OPT-002-langgraph-biz-actor-home-readiness
status: reviewed
conclusion: ready-for-acceptance
reviewed_by: Codex
reviewed_at: 2026-06-29
follow_up_required: no
---

# Test Coverage Audit

## Document Purpose

- doc_type: coverage
- intended_for: reviewer | signoff-owner
- purpose: 记录 `OPT-002: LangGraph Biz Actor Home Readiness` 的测试覆盖审计结论。

## Coverage Matrix

| Item | Risk | Unit | Integration | E2E | Playwright | Manual | Evidence | Coverage |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| AC-1: `LANGGRAPH_BIZ` 缺 `directoryId` fail-fast | critical | yes | no | no | no | no | `BusinessAgentTaskServiceTest`; `LanggraphTaskServiceTest`; `OpenApiControllerMessageMappingTest` | covered |
| AC-2: OpenAPI 返回 `TASK_DIRECTORY_REQUIRED` marker | major | yes | no | no | no | no | `OpenApiControllerMessageMappingTest`: 33 tests pass | covered |
| AC-3: A2A/direct/launcher 透传 `directoryId` / `cwd` | major | yes | no | no | no | no | `LanggraphWorkerInnerA2aAgentTest`; `LanggraphBusinessAgentWorkerTaskLauncherTest`; `LanggraphTaskServiceTest` | covered |
| AC-4: `runtime_context` alias accepted and sanitized | major | yes | no | no | no | no | `LanggraphWorkerInnerA2aAgentTest`; `LanggraphTaskServiceTest` | covered |
| AC-5: readiness reports file tool root mode/alignment | major | yes | no | no | no | no | `OpenApiAgentReadinessServiceTest`: 18 tests pass | covered |
| AC-6: delegated Python writes land under Actor Home | critical | yes | no | no | no | no | `test_owner_aware_runtime_contract.py`: pass | covered |
| AC-7: delegated write result exposes `storage_mode=delegated` | minor | yes | no | no | no | no | `test_account_file_tools.py`: pass | covered |
| AC-8: no UI change | minor | no | no | no | no | yes | workitem `Experience Progress` | covered |

## Evidence Summary

- Java focused tests:
  - `BusinessAgentTaskServiceTest`: 20 passed.
  - `OpenApiAgentReadinessServiceTest`: 18 passed.
  - `OpenApiControllerMessageMappingTest`: 33 passed.
  - `LanggraphTaskServiceTest + LanggraphBusinessAgentWorkerTaskLauncherTest`: 31 passed.
  - `LanggraphWorkerInnerA2aAgentTest`: 11 passed.
- Python focused tests:
  - `.venv\Scripts\python.exe -m pytest tests/test_account_file_tools.py tests/test_owner_aware_runtime_contract.py`: 43 passed, 2 skipped.
- Workspace check:
  - `git diff --check`: pass with CRLF normalization warnings only.

## Gaps

- blocking gaps: none
- non-blocking gaps:
  - No browser/Playwright evidence because this has no UI surface.
  - No full Maven reactor run in this step; focused module tests cover changed Java behavior.

## Conclusion

- conclusion: ready-for-acceptance
- can_enter_acceptance: yes
- follow_up_required: no
