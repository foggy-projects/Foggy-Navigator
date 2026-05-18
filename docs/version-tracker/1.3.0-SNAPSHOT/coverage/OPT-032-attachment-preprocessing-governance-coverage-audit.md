---
audit_scope: feature
audit_mode: pre-acceptance-check
version: 1.3.0-SNAPSHOT
target: OPT-032-attachment-preprocessing-governance-follow-up
status: reviewed
conclusion: ready-for-acceptance
reviewed_by: Codex
reviewed_at: 2026-05-18
follow_up_required: no
---

# Test Coverage Audit

## Background

OPT-032 covers attachment preprocessing governance after BUG-028. The main acceptance risks are preserving direct handoff, making explicit preprocessing observable without leaking secrets, and normalizing canonical and legacy OpenAPI attachment inputs.

## Audit Basis

- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/OPT-032-attachment-preprocessing-governance-follow-up.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/quality/OPT-032-attachment-preprocessing-governance-implementation-quality.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/BUG-028-tms-ticket-agent-attachment-not-delivered.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/REQ-030-biz-worker-on-demand-attachment-analysis-and-vision-model-config.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/test-records/real-llm-attachment-ticket/20260518-162515-65bf00/`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiAttachmentNormalizerTest.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiControllerMessageMappingTest.java`
- `tools/langgraph-biz-worker/tests/test_attachment_context.py`
- `tools/langgraph-biz-worker/tests/test_attachment_analysis.py`
- `tools/langgraph-biz-worker/tests/test_e2e_scripted_tool_call_streaming.py`

## Coverage Matrix

| Requirement / Acceptance Item | Risk | Evidence Layer | Evidence | Conclusion |
| --- | --- | --- | --- | --- |
| Direct ticket creation with an attachment avoids image analysis and preserves attachment refs | critical | e2e-test / real-llm-evidence | BUG-028 real LLM direct-handoff smoke under `test-records/real-llm-attachment-ticket/20260518-162515-65bf00/`; existing scripted ticket attachment E2E | covered |
| Explicit preprocessing is opt-in and links analysis output back to the original attachment | major | unit-test / e2e-test | `test_attachment_analysis.py`; `test_e2e_scripted_tool_call_streaming.py -k "ticket_from_image_content"` asserts `attachment_evidence.attachment_ids == ["att-vision"]` | covered |
| Sanitized per-hop evidence proves propagation without leaking signed URLs or tokens | major | unit-test / real-llm-evidence | `test_attachment_context.py`; sensitive-pattern scan over real LLM events, SSE, frame artifacts, and tool-audit logs found no hits | covered |
| OpenAPI top-level `attachments` remains canonical and `metadata.attachments` is normalized for compatibility | major | unit-test | `OpenApiAttachmentNormalizerTest`; `OpenApiControllerMessageMappingTest`; Maven targeted test run passed with 16 tests | covered |
| Duplicate or conflicting attachment refs are deduped deterministically | major | unit-test | OpenAPI normalizer tests cover id/ref, URL/href/downloadUrl, and name+mime identity behavior | covered |
| Policy boundary says when direct handoff vs preprocessing applies | minor | documentation | OPT-032 phased plan and acceptance criteria reference BUG-028 direct handoff and REQ-030 explicit `analyze_attachment` policy | covered |

## Evidence Summary

- Java targeted tests: `mvn -pl .\addons\claude-worker-agent -am "-Dtest=OpenApiAttachmentNormalizerTest,OpenApiControllerMessageMappingTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` -> 16 tests passed.
- Python targeted tests: `$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests/test_attachment_context.py tests/test_attachment_analysis.py` -> 6 tests passed.
- Worker scripted E2E: `$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests/test_e2e_scripted_tool_call_streaming.py -k "ticket_from_image_content"` -> 1 selected test passed.
- Real LLM BUG-028 direct-handoff smoke: `summary.json` status `PASS`; root invoked `tms-ticket-agent`; child result returned attachment id/name/provider/safe URL path; raw evidence logs were scanned with no sensitive pattern hits.

## Gaps

- None blocking. This audit accepts the real LLM direct-handoff smoke as evidence rather than requiring it to run in every local unit-test cycle.

## Recommended Next Skills

- `foggy-acceptance-signoff` for formal feature signoff.

## Conclusion

`ready-for-acceptance`. Critical direct-handoff behavior, explicit preprocessing evidence, safe observability, and OpenAPI compatibility normalization are all mapped to automated or real-chain evidence.
