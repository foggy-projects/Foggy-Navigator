---
acceptance_scope: feature
version: 1.3.0-SNAPSHOT
target: OPT-029-upstream-timeout-governance
doc_role: acceptance-record
doc_purpose: 说明本文件用于 OPT-029 功能级正式验收与签收结论记录
status: signed-off
decision: accepted-with-risks
signed_off_by: Codex
signed_off_at: 2026-05-18
reviewed_by: N/A
blocking_items: []
follow_up_required: yes
evidence_count: 10
---

# Feature Acceptance

## Background

- Version: 1.3.0-SNAPSHOT
- Target: OPT-029-upstream-timeout-governance
- Owner: upstream-integration + session-module + biz-worker-runtime + widget
- Goal: Close the TMS Navigator timeout incident by making UI wait timeout, client detach, user cancel, LLM retry/deadline failure, and recoverable Worker frame context converge under one cross-layer contract.

## Acceptance Basis

- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/OPT-029-upstream-timeout-governance.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/quality/OPT-029-upstream-timeout-governance-implementation-quality.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/coverage/OPT-029-upstream-timeout-governance-coverage-audit.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/BUG-027-biz-worker-llm-call-timeout-fuse-missing.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/BUG-028-tms-ticket-agent-attachment-not-delivered.md`

## Checklist

- [x] UI wait timeout defaults to detach/continue and does not call cancel.
- [x] Explicit user cancel is distinct from UI wait timeout and does not discard the conversation context for the next turn.
- [x] Java/session task core status remains compact, with timeout/cancel/recoverable details projected through reason/substatus fields.
- [x] Java LangGraph relay read timeout is classified and covered by targeted tests.
- [x] Biz Worker treats runtime frame journal/context as the only frame recovery fact source.
- [x] Worker selects the latest active recoverable focus when multiple interrupted frames exist.
- [x] LLM request timeout, retry progress, retry exhaustion, provider hang, and circuit behavior are covered by targeted tests.
- [x] Retry/progress events are emitted to the UI-consumable event stream and detail/debug modes can display them.
- [x] Client detach does not imply cancel; server-side work can finish and be reattached through task/context/message cursor.
- [x] TMS browser UI covers wait-timeout background prompt, explicit cancel, and reopened history-session continuation.
- [x] TMS real OpenAPI chain covers wait-timeout/detach recovery with stable `contextId` across turns.

## Evidence

- Workitem and progress: `docs/version-tracker/1.3.0-SNAPSHOT/workitems/OPT-029-upstream-timeout-governance.md`
- Implementation quality: `docs/version-tracker/1.3.0-SNAPSHOT/quality/OPT-029-upstream-timeout-governance-implementation-quality.md` -> `ready-for-coverage-audit`.
- Coverage audit: `docs/version-tracker/1.3.0-SNAPSHOT/coverage/OPT-029-upstream-timeout-governance-coverage-audit.md` -> `ready-with-gaps`.
- Worker OPT-029 capture: `docs/version-tracker/1.3.0-SNAPSHOT/test-records/opt-029-timeout-recovery/20260518-210232-e9042c/summary.json` -> 6 verdicts passed.
- TMS OpenAPI real-chain smoke: `docs/version-tracker/1.3.0-SNAPSHOT/test-records/opt-029-tms-timeout-recovery/20260518-remote-tms-openapi/summary.json` -> `passed=true`.
- Java targeted tests: `LanggraphWorkerClientTest`, `LanggraphStreamRelayTest`, and `LanggraphTaskServiceTest` -> 33 passed.
- Widget UX tests: `pnpm --filter @foggy/navigator-chat-widget test -- useNavigatorChat.ux.test.ts` -> 12 passed.
- Python Worker targeted and E2E tests: frame recovery, LLM guard, slow Worker, and detach/reattach sets listed in the coverage audit -> passed.
- Provider hang guard/soak: `tools/langgraph-biz-worker/tests/test_llm_call_guard.py` -> 4 passed.
- TMS browser E2E: `D:/workspace/tms-x6-dev/x3-web-tms/tests/playwright/navigator-chat-timeout-recovery.spec.ts` -> 3 passed.

## Failed Items

- none

## Risks / Open Items

- Non-blocking follow-up: run a live browser physical network-disconnect drill against the TMS BFF when an environment window is available. Current acceptance relies on the remote real OpenAPI smoke plus browser mock-BFF Playwright E2E for this slice.

## Final Decision

OPT-029 is accepted with risks. The core contract is implemented and evidenced across Worker, Java relay, widget, TMS browser E2E, and remote TMS OpenAPI smoke. The remaining live browser physical disconnect drill is not a blocker because the same routing and recovery contract is already covered by real OpenAPI and browser-level automated evidence.

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-05-18
- acceptance_record: `docs/version-tracker/1.3.0-SNAPSHOT/acceptance/OPT-029-upstream-timeout-governance-acceptance.md`
- blocking_items: none
- follow_up_required: yes
