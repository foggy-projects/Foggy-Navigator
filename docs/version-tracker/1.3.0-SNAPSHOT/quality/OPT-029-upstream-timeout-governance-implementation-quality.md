---
quality_scope: feature
quality_mode: pre-coverage-audit
version: 1.3.0-SNAPSHOT
target: OPT-029-upstream-timeout-governance
status: reviewed
decision: ready-for-coverage-audit
reviewed_by: Codex
reviewed_at: 2026-05-18
follow_up_required: no
---

# Implementation Quality Gate

## Background

OPT-029 closes the cross-layer timeout, detach, cancel, deadline, retry, and recoverable frame governance exposed by TMS Navigator live usage. The implementation spans the Navigator widget, Java LangGraph relay/session projection, Biz Worker frame journal/recovery, LLM call guard, TMS smoke scripts, and browser E2E coverage.

## Check Basis

- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/OPT-029-upstream-timeout-governance.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/BUG-027-biz-worker-llm-call-timeout-fuse-missing.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/BUG-028-tms-ticket-agent-attachment-not-delivered.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/test-records/opt-029-timeout-recovery/20260518-210232-e9042c/summary.json`
- `docs/version-tracker/1.3.0-SNAPSHOT/test-records/opt-029-tms-timeout-recovery/20260518-remote-tms-openapi/summary.json`
- TMS browser E2E commit `aa690154 test: add navigator timeout recovery e2e`

## Changed Surface

- `packages/navigator-chat-widget/src/composables/useNavigatorChat.ts`
- `packages/navigator-chat-widget/src/composables/useNavigatorChat.ux.test.ts`
- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/client/LanggraphWorkerClient.java`
- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphStreamRelay.java`
- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphWorkerService.java`
- `addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/client/LanggraphWorkerClientTest.java`
- `addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/service/LanggraphStreamRelayTest.java`
- `tools/langgraph-biz-worker/tests/test_llm_call_guard.py`
- `tools/langgraph-biz-worker/tests/test_llm_skill_agent.py`
- `tools/langgraph-biz-worker/tests/test_frame_lifecycle.py`
- `tools/langgraph-biz-worker/tests/test_e2e_scripted_tool_call_streaming.py`
- `tools/langgraph-biz-worker/tests/test_query.py`
- `tools/langgraph-biz-worker/scripts/opt029_timeout_recovery_capture.py`
- `deploy/dev-kvm-x3/scripts/54-smoke-tms-timeout-recovery.sh`
- `deploy/dev-kvm-x3/remote/smoke-tms-timeout-recovery.sh`
- `D:/workspace/tms-x6-dev/x3-web-tms/tests/playwright/navigator-chat-timeout-recovery.spec.ts`

## Quality Checklist

| Check | Result | Notes |
| --- | --- | --- |
| scope conformance | pass | Changes are limited to timeout governance, recoverable frame routing, Java relay timeout projection, Widget detach/cancel behavior, and targeted verification. |
| code hygiene | pass | No debug-only branch or temporary TODO was found in the reviewed OPT-029 change surface. |
| duplication and consolidation | pass | Worker recovery continues to use the frame journal/runtime context as the single recovery source; Java/TMS/widget only keep route anchors and state projections. |
| complexity and abstraction | pass | The cross-layer behavior is split by owner: Widget waits/detaches, Java projects relay/session status, Worker owns frame recovery, and LLM guard owns timeout/retry/fuse behavior. |
| error handling and edge cases | pass | Coverage includes UI wait timeout, explicit cancel, client detach, Java stream read timeout, LLM request timeout/retry exhaustion, provider hang, deadline/budget, and multiple recoverable focus selection. |
| readability and maintainability | pass | The workitem now contains the contract, state taxonomy, routing responsibility, default values, and acceptance evidence in one traceable place. |
| critical logic documentation | pass | The recoverable frame routing contract and the "UI timeout is not cancel" rule are documented with explicit owner boundaries. |
| contract and compatibility | pass | Existing OpenAPI `contextId`, task message cursor, cancel endpoint, and TMS BFF paths remain compatible; no UI or Java caller is asked to choose a Worker frame. |
| documentation and writeback | pass | Workitem progress and test evidence were updated; this quality gate, coverage audit, and acceptance record complete the signoff trail. |
| test alignment | pass | Tests map to the changed risk surface instead of broad unrelated coverage; TMS dirty REQ-R607 worktree changes were not touched. |
| release readiness | pass | No quality issue blocks coverage audit or formal acceptance. |

## Findings

- No blocking implementation quality findings.

## Risks / Follow-ups

- Browser physical network-disconnect reattach can still be exercised as a later hardening smoke. Existing browser E2E covers the user-visible timeout, explicit cancel, and reopened-session continuation via mock BFF; remote OpenAPI smoke covers the real TMS Navigator chain.

## Recommended Next Skills

- `foggy-test-coverage-audit` for the pre-acceptance evidence mapping.
- `foggy-acceptance-signoff` after coverage audit.

## Decision

`ready-for-coverage-audit`. The implementation is scoped to OPT-029, owner boundaries are explicit, and the remaining real-network browser drill is non-blocking hardening rather than a quality gate blocker.
