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
evidence_count: 20
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
- [x] Codex Worker enforces `allowedTools` at MCP tool granularity.
- [x] Explicit `providerType=codex-biz-worker` wins over logical upstream `agentId` for direct command-provider routing.
- [x] navigator_business MCP config is written to scoped Codex home without persisting task token.
- [x] Codex MCP `list_business_functions`, `get_business_function_schema` and `invoke_business_function` all complete.
- [x] `submit_skill_result` smoke returns marker `codex-biz-smoke-20260630-134920` and final JSON status `SUCCESS`.
- [x] `TaskEvidence.structuredOutput` can lift Codex final JSON `structured_output.*` OPEN_ARTIFACT content; live task `20260630-af4c` confirms `source=message_content`.
- [x] WorkerGateway tool-message audit records `invoke_business_function`, `submit_skill_result`, `SUCCESS`.
- [x] `contextId` continuation positive and conflict negatives are covered; once the context is bound, continuation can omit provider/worker/directory/scoped-home overrides. The first direct CodexBizWorker request still needs a scoped-home source.
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
- Live structured output lift: task `20260630-af4c`, contextId `bctx_20260630_b0_b0b8054afdbc43a0bf1401b2bd66e9dc`, marker `codex-biz-smoke-20260630-continue-143306`; after `.\start-launcher.ps1` reload, evidence reports `structuredOutput.available=true`, `source=message_content`, `value.type=OPEN_ARTIFACT`.
- 2026-06-30 rerun: task `20260630-0a61`, contextId `bctx_20260630_1b_1b347d05b3da48e9a7101738940c620d`, workerTask/providerTask `fcceaf4e-193d-4381-895a-5f178793ad29`; final marker `codex-biz-smoke-rerun-20260630-174033`, `functionId=submit_skill_result`, `status=SUCCESS`, `structuredOutput.source=message_content`, `structured_output.type=OPEN_ARTIFACT`.
- 2026-06-30 continuation rerun: task `20260630-4f73` reused contextId `bctx_20260630_1b_1b347d05b3da48e9a7101738940c620d`, omitted provider/directory/model overrides, retained `privateAccountId=codex-biz-smoke-user`, and resolved to providerType `codex-biz-worker`, workerBackend `OPENAI_CODEX`, worker `3ad8bb7b`; marker `codex-biz-context-bound-rerun-20260630-174304`.
- 2026-06-30 context-bound scoped-home replay: first task `20260630-d39b` created context `bctx_20260630_96_960f31e76ab94c2f94c453d9b9fa6be8` with `privateAccountId=tenant/world-sim/scenario-1/actor-context-only-20260630211256`; continuation task `20260630-00d8` sent only `contextId` plus auth/message fields, with no provider/directory/model/scoped-home env or CLI values, and resolved to providerType `codex-biz-worker`, workerBackend `OPENAI_CODEX`, final content `OK2`.
- 2026-06-30 conflict checks: same context plus conflicting `--provider-type langgraph-biz-worker` failed fast with HTTP 400 `CONTEXT_WORKER_MISMATCH`; same context plus conflicting `--private-account-id tenant/world-sim/scenario-1/actor-conflict` failed fast with HTTP 400 `CONTEXT_WORKER_MISMATCH`.
- Tests: Worker suite passed with 97 tests, Worker typecheck passed, Java OpenAPI evidence regression passed, Java targeted tests passed earlier for SDK/session route changes.
- Local wrapper: repaired `navigator-upstream-cli 1.0.18`, packageSha `b94556726789124837cbf683f4acb69f75df89dfa5c0d7144dcd3dfc3a5084a2`, buildId `1.0.18+6d1eb7431155.dirty`.

## Risks / Open Items

- SIM and TMS still need to run and record consumer-side smoke in their own repos.
- Consumer projects need to verify the published `navigator-upstream-cli` package by packageSha/buildId before relying on wrapper self-update; this project-local wrapper is already on 1.0.18.
- Legacy CodexBiz contexts created before the 2026-06-30 scoped-home replay fix may not have `providerState` scoped-home fields. Recreate the context, or continue once with the same scoped-home source to refresh the binding before switching to context-only continuation.

## Failed Items

- None blocking this signoff.
- Non-blocking gap: consumer-side SIM/TMS smoke evidence is not present in this repo.

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
