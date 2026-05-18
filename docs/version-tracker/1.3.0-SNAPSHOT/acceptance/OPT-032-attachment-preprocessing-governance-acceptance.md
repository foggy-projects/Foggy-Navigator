---
acceptance_scope: feature
version: 1.3.0-SNAPSHOT
target: OPT-032-attachment-preprocessing-governance-follow-up
doc_role: acceptance-record
doc_purpose: 说明本文件用于 OPT-032 功能级正式验收与签收结论记录
status: signed-off
decision: accepted
signed_off_by: Codex
signed_off_at: 2026-05-18
reviewed_by: N/A
blocking_items: []
follow_up_required: no
evidence_count: 7
---

# Feature Acceptance

## Background

- Version: 1.3.0-SNAPSHOT
- Target: OPT-032-attachment-preprocessing-governance-follow-up
- Owner: biz-worker-runtime + navigator-business-agent + upstream-integration
- Goal: Close BUG-028 follow-up governance by preserving direct attachment handoff, adding explicit preprocessing evidence, and normalizing OpenAPI attachment compatibility behavior.

## Acceptance Basis

- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/OPT-032-attachment-preprocessing-governance-follow-up.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/quality/OPT-032-attachment-preprocessing-governance-implementation-quality.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/coverage/OPT-032-attachment-preprocessing-governance-coverage-audit.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/BUG-028-tms-ticket-agent-attachment-not-delivered.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/REQ-030-biz-worker-on-demand-attachment-analysis-and-vision-model-config.md`

## Checklist

- [x] Direct handoff remains the default for create/submit workflows with attachments.
- [x] Explicit preprocessing is limited to user intent, skill policy, or required business fields that need derived attachment content.
- [x] OpenAPI accepts legacy `metadata.attachments` but emits one normalized attachment list for Worker dispatch.
- [x] Duplicate refs are deduped and top-level `attachments` remains canonical on conflict.
- [x] Worker-side analysis output includes safe original-attachment evidence.
- [x] Direct handoff is backed by BUG-028 real LLM evidence.
- [x] No raw signed URL, token, API key, or image bytes are introduced into normal prompts or docs.

## Evidence

- Workitem and progress: `docs/version-tracker/1.3.0-SNAPSHOT/workitems/OPT-032-attachment-preprocessing-governance-follow-up.md`
- Implementation quality: `docs/version-tracker/1.3.0-SNAPSHOT/quality/OPT-032-attachment-preprocessing-governance-implementation-quality.md` -> `ready-for-coverage-audit`.
- Coverage audit: `docs/version-tracker/1.3.0-SNAPSHOT/coverage/OPT-032-attachment-preprocessing-governance-coverage-audit.md` -> `ready-for-acceptance`.
- Java targeted tests: OpenAPI normalizer/controller mapping tests -> 16 passed.
- Python targeted tests: attachment context and analysis tests -> 6 passed.
- Worker scripted E2E: explicit preprocessing ticket flow -> 1 selected test passed.
- Real LLM direct-handoff evidence: `docs/version-tracker/1.3.0-SNAPSHOT/test-records/real-llm-attachment-ticket/20260518-162515-65bf00/summary.json` -> `PASS`.

## Failed Items

- none

## Risks / Open Items

- none blocking. Future behavior that automatically analyzes all attachments should be handled as a separate requirement because it would intentionally change the OPT-032 policy boundary.

## Final Decision

OPT-032 is accepted. The compatibility, explicit preprocessing, sanitized evidence, and direct-handoff regression requirements are complete and have sufficient test/evidence coverage for this release scope.

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: Codex
- signed_off_at: 2026-05-18
- acceptance_record: `docs/version-tracker/1.3.0-SNAPSHOT/acceptance/OPT-032-attachment-preprocessing-governance-acceptance.md`
- blocking_items: none
- follow_up_required: no
