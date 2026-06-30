---
doc_role: feature-acceptance-record
doc_purpose: Formal signoff for OPT-003 Codex Biz upstream acceptance handoff.
acceptance_scope: feature
version: 1.3.2-SNAPSHOT
target: OPT-003-codex-biz-upstream-acceptance
status: signed-off
decision: accepted-with-risks
signed_off_by: Codex
signed_off_at: 2026-06-30
reviewed_by: Codex
blocking_items: []
follow_up_required: yes
evidence_count: 12
---

# Feature Acceptance

## Background

OPT-003 validates CodexBizWorker as an explicit gray/internal/developer route for upstream business systems. It does not make CodexBizWorker the default enterprise production route, and it does not force SIM and TMS to share the same `directoryId` source.

## Acceptance Basis

- Workitem: `docs/version-tracker/1.3.2-SNAPSHOT/workitems/OPT-003-codex-biz-upstream-acceptance.md`
- Quality gate: `docs/version-tracker/1.3.2-SNAPSHOT/quality/OPT-003-implementation-quality.md`
- Coverage audit: `docs/version-tracker/1.3.2-SNAPSHOT/coverage/OPT-003-coverage-audit.md`
- Live smoke task: `20260630-b499`

## Checklist

- [x] Self-owned smoke upstream is provisioned without relying on TMS existing profile.
- [x] Codex Worker health reports scoped home configured and ready.
- [x] `allowedTools` can be sent through OpenAPI/SDK ask as a top-level runtime option.
- [x] Explicit `providerType=codex-biz-worker` wins over logical upstream `agentId` for direct command-provider routing.
- [x] navigator_business MCP config is written to scoped Codex home without persisting task token.
- [x] Codex MCP `list_business_functions`, `get_business_function_schema` and `invoke_business_function` all complete.
- [x] `submit_skill_result` smoke returns marker `codex-biz-smoke-20260630-134920` and final JSON status `SUCCESS`.
- [x] WorkerGateway tool-message audit records `invoke_business_function`, `submit_skill_result`, `SUCCESS`.
- [x] `contextId` continuation positive and directory-conflict negative are covered.
- [x] SIM/TMS protocol differences are documented as consumer adapter differences, not global `directoryId` mandates.
- [x] Production boundary remains explicit: LangBizWorker stays default for formal enterprise business orchestration.

## Evidence

- Provisioning: ClientApp `capp_62137cc6-584a-42db-8ffc-35508a98aa80`, agent `codex-biz-smoke-agent`, modelConfig `48212e4e-fd63-4ec6-8fe5-47089a19824c`, directory `20260630-143b`, worker `3ad8bb7b`.
- Worker health: `http://127.0.0.1:3070/health` returned `status=ok`, `codex_biz_scoped_home_ready=true`.
- Live task: `20260630-b499`, contextId `bctx_20260630_36_36787f40b092468e8183687a84ea0d01`, workerTask/providerTask `3c9ef3e8-80c6-4061-8e6c-231c6ae0c95c`, providerType `codex-biz-worker`, workerBackend `OPENAI_CODEX`.
- MCP debug: `initialize`, `tools/list`, then `tools/call` for list/schema/invoke.
- WorkerGateway: GET `/business-functions` 200, GET `/business-functions/submit_skill_result/schema` 200, POST `/business-functions/submit_skill_result/invoke` 200, POST `/tool-messages` 200.
- Backend audit: `Tool message received: tool=invoke_business_function, functionId=submit_skill_result, status=SUCCESS`.
- Final marker: `{"marker":"codex-biz-smoke-20260630-134920","functionId":"submit_skill_result","status":"SUCCESS","structured_output.type":"OPEN_ARTIFACT"}`.
- Tests: Worker targeted test suite passed, Worker typecheck passed, Java targeted tests passed earlier for SDK/session route changes.

## Risks / Open Items

- SIM and TMS still need to run and record consumer-side smoke in their own repos.
- `TaskEvidence.structuredOutput` is not auto-populated from Codex MCP BusinessFunction output; current accepted evidence is final JSON plus WorkerGateway/tool-message audit.
- Installed `tools/navigator-upstream` wrapper is still `1.0.16`; consumers need the SDK/wrapper release containing `allowedTools` passthrough.

## Failed Items

- None blocking this signoff.
- Non-blocking gap: automatic structured output/artifact lifting is not implemented for Codex MCP invoke results.

## Final Decision

Accepted with risks. The self-owned Navigator acceptance scope is complete and can be delivered to SIM/TMS owners for their consumer validation. Remaining items are follow-ups and do not block gray/internal CodexBizWorker handoff.

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-30
- acceptance_record: docs/version-tracker/1.3.2-SNAPSHOT/acceptance/OPT-003-codex-biz-upstream-acceptance.md
- blocking_items: none
- follow_up_required: yes
