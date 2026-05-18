---
audit_scope: feature
audit_mode: pre-acceptance-check
version: 1.3.0-SNAPSHOT
target: OPT-029-upstream-timeout-governance
status: reviewed
conclusion: ready-with-gaps
reviewed_by: Codex
reviewed_at: 2026-05-18
follow_up_required: yes
---

# Test Coverage Audit

## Background

OPT-029 covers interruption recovery and timeout governance across Navigator upstream callers, the TMS browser host, Java relay/session task projection, Python Biz Worker frame recovery, and LLM/provider execution. The main acceptance risks are conflating UI wait timeout with cancel, losing frame context after task interruption, retrying silently until the UI disconnects, and leaking resources during provider hangs.

## Audit Basis

- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/OPT-029-upstream-timeout-governance.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/quality/OPT-029-upstream-timeout-governance-implementation-quality.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/test-records/opt-029-timeout-recovery/20260518-210232-e9042c/summary.json`
- `docs/version-tracker/1.3.0-SNAPSHOT/test-records/opt-029-tms-timeout-recovery/20260518-remote-tms-openapi/summary.json`
- `packages/navigator-chat-widget/src/composables/useNavigatorChat.ux.test.ts`
- `tools/langgraph-biz-worker/tests/test_llm_call_guard.py`
- `tools/langgraph-biz-worker/tests/test_llm_skill_agent.py`
- `tools/langgraph-biz-worker/tests/test_frame_lifecycle.py`
- `tools/langgraph-biz-worker/tests/test_e2e_scripted_tool_call_streaming.py`
- `tools/langgraph-biz-worker/tests/test_query.py`
- `addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/client/LanggraphWorkerClientTest.java`
- `addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/service/LanggraphStreamRelayTest.java`
- `D:/workspace/tms-x6-dev/x3-web-tms/tests/playwright/navigator-chat-timeout-recovery.spec.ts`

## Coverage Matrix

| Requirement / Acceptance Item | Risk | Evidence Layer | Evidence | Conclusion |
| --- | --- | --- | --- | --- |
| UI wait timeout is detach/continue and must not call cancel | critical | unit-test / playwright-test / real-chain smoke | Widget UX test `treats UI wait timeout as detach`; TMS Playwright timeout case; remote TMS OpenAPI smoke `cancelApiCalled=false` | covered |
| Stable `contextId` allows next turn to continue after detach or timeout | critical | unit-test / e2e-test / playwright-test / real-chain smoke | Widget UX context reuse tests; Worker capture next-turn recovery; TMS OpenAPI second task reused `20260518-73b3`; TMS Playwright context reuse assertions | covered |
| Worker owns frame recovery using runtime context journal, not Java or upstream frame selection | critical | unit-test / e2e-test / evidence artifact | `test_frame_lifecycle.py` latest recoverable focus; `test_llm_skill_agent.py` recoverable child/root cases; OPT-029 capture artifacts under `frames-by-conversation` | covered |
| Server-side task interruption remains recoverable after LLM timeout/retry exhaustion | critical | unit-test / e2e-test | `test_llm_call_guard.py`; `test_llm_skill_agent.py`; OPT-029 capture step `01-llm-timeout-retry` and `02-continue-after-timeout` | covered |
| Retry/backoff emits progress visible to UI modes | major | unit-test / e2e evidence | `test_llm_call_guard.py::test_retry_progress_event_is_emitted_before_retry`; widget progress UX tests; OPT-029 capture `progress_type=llm_retrying` | covered |
| Java relay read timeout is classified and projected separately from user cancel | major | integration-test | `LanggraphWorkerClientTest`, `LanggraphStreamRelayTest`, `LanggraphTaskServiceTest` targeted Maven run reported 33 passed | covered |
| Explicit user cancel stops current polling but preserves conversation context for next user message | major | unit-test / playwright-test / Worker tests | TMS Playwright explicit cancel case; Worker frame interruption tests for `user_cancelled`; Java cancel interruption tests | covered |
| Client detach after first progress does not kill the Worker task and artifacts converge | major | e2e-test / capture evidence | `test_e2e_scripted_tool_call_streaming.py::test_client_detach_then_next_turn_reuses_persistent_frame`; OPT-029 capture detach verdicts | covered |
| Provider hang does not leak LLM concurrency slots or worker threads beyond guard limits | major | unit/soak targeted test | `tests/test_llm_call_guard.py -q` provider hang guard/soak, 4 passed | covered |
| TMS browser UX shows background processing, supports cancel, and restores from history after reopen | major | playwright-test | `pnpm exec playwright test tests/playwright/navigator-chat-timeout-recovery.spec.ts --project=chromium`, 3 passed | covered |
| Physical browser network disconnect and reattach through real TMS BFF | minor | manual/real-chain evidence | Remote OpenAPI smoke covers real Navigator chain; browser-level physical network loss remains a follow-up hardening drill | partially-covered |

## Evidence Summary

- Python Worker targeted tests: `test_llm_tool_dispatcher.py`, `test_file_frame_journal.py`, `test_frame_lifecycle.py::...latest_recoverable...`, and `test_llm_call_guard.py` -> 25 passed.
- Python agent targeted tests: LLM transient retry, child timeout recoverable interruption, continue reopened failed child, and persistent root model error recoverable -> 4 passed.
- Java relay/session timeout tests: `mvn -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphWorkerClientTest,LanggraphStreamRelayTest,LanggraphTaskServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` -> 33 passed.
- Widget timeout/progress behavior tests: `pnpm --filter @foggy/navigator-chat-widget test -- useNavigatorChat.ux.test.ts` -> 12 passed.
- Client detach and reattach recovery: `pytest tests/test_e2e_scripted_tool_call_streaming.py::test_client_detach_then_next_turn_reuses_persistent_frame -q` -> passed.
- Worker OPT-029 capture against local 3061 Worker: `scripts/opt029_timeout_recovery_capture.py --base-url http://localhost:3061`, latest record `20260518-210232-e9042c`, 6 verdicts passed.
- Worker slow targeted tests: `tests/test_query.py -q` -> 13 passed.
- Provider hang guard/soak targeted tests: `tests/test_llm_call_guard.py -q` -> 4 passed.
- TMS OpenAPI end-to-end timeout/detach recovery smoke: `deploy/dev-kvm-x3/scripts/54-smoke-tms-timeout-recovery.sh`, remote dev-kvm-x3 real chain passed; `contextId=20260518-73b3` reused across turns.
- TMS browser E2E timeout/cancel/reopen recovery: `pnpm exec playwright test tests/playwright/navigator-chat-timeout-recovery.spec.ts --project=chromium`, 3 passed.

## Gaps

- Non-blocking: real browser physical network-disconnect reattach through the live TMS BFF was not separately exercised. The behavior is covered by the real OpenAPI chain and by browser mock-BFF history/reopen E2E, so this is suitable as a later hardening smoke rather than an acceptance blocker.

## Recommended Next Skills

- `foggy-acceptance-signoff` for formal feature signoff with the non-blocking browser physical disconnect gap acknowledged.

## Conclusion

`ready-with-gaps`. Critical OPT-029 behavior is mapped to unit, integration, Worker E2E, TMS browser Playwright, and real TMS OpenAPI smoke evidence. The only remaining gap is a non-blocking live browser network-loss drill.
