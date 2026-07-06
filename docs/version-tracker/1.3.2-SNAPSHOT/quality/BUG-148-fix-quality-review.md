---
quality_scope: bug
quality_mode: post-fix-quality-review
version: 1.3.2-SNAPSHOT
target: BUG-148-openapi-ask-context-continuation
status: reviewed
decision: ready-for-external-verification
reviewed_by: codex
reviewed_at: 2026-07-05
follow_up_required: yes
---

# Implementation Quality Gate

## Background

- Check target: GitHub issue #148, OpenAPI `ask` first turn succeeds but immediate continuation with the returned `contextId` returns HTTP 500.
- Current stage: initial fix plus issue-comment follow-up implemented, focused unit regressions passed, delivery review requested.
- Fix objective: the OpenAPI-returned business `contextId` must be reusable immediately by the same tenant, client app, upstream user, and agent; invalid continuation requests must fail with a structured RX error before task dispatch.

## Check Basis

- requirement: https://github.com/foggy-projects/Foggy-Navigator/issues/148
- follow-up requirement: https://github.com/foggy-projects/Foggy-Navigator/issues/148#issuecomment-4885983136
- bug work item: `docs/version-tracker/1.3.2-SNAPSHOT/workitems/BUG-148-openapi-ask-context-continuation.md`
- implementation plan: bind first-turn business session from returned task metadata, add a strict recovery path for delayed `business_agent_session` materialization, and keep normal context continuation on the create-task path unless `resume=true` is explicit.
- progress: bug work item status is `ready-for-external-verification`.
- execution check-in: focused code review of the OpenAPI ask continuation path, business token predicate, and regression tests.
- test result summary: `mvn -pl addons/claude-worker-agent -am "-Dtest=OpenApiControllerMessageMappingTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` passed on 2026-07-05; 38 tests, 0 failures, 0 errors. `mvn -pl session-module -am "-Dtest=TaskDispatchFacadeTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` passed on 2026-07-05; 66 tests, 0 failures, 0 errors. Live two-turn OpenAPI smoke passed on 2026-07-05 21:20:43 Asia/Shanghai with `navigator-provisioning-selftest-agent-a`: first task `lgt_58327848d90d4380`, second task `lgt_cb9e255122754867`, same `contextId=bctx_20260705_21_212fbdbeae654176b1c6f95af46c4ceb`, `workerBackend=LANGGRAPH_BIZ`, `providerType=langgraph-biz-worker`.
- static check summary: `git diff --check` reported no whitespace errors; only Git LF/CRLF working-copy warnings were printed.

## Changed Surface

- changed files:
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java`
  - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskService.java`
  - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/repository/BusinessTaskScopedTokenRepository.java`
  - `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiControllerMessageMappingTest.java`
  - `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
  - `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
  - `docs/version-tracker/1.3.2-SNAPSHOT/workitems/BUG-148-openapi-ask-context-continuation.md`
  - `docs/version-tracker/1.3.2-SNAPSHOT/quality/BUG-148-fix-quality-review.md`
- changed modules: Claude Worker OpenAPI controller, Business Agent task-scoped token service, Session task dispatch facade, focused OpenAPI and task-dispatch unit tests.
- declared completed scope: OpenAPI ask continuation no longer falls through to default HTTP 500 when the first-turn business session row is delayed; strict same-tenant/client/upstream/context/agent recovery is available only for the missing-row case; normal LangGraph Biz context continuation now reuses the bound Navigator session and calls `createTaskDirect(...)` instead of unsupported provider `resumeTask(...)`.
- excluded from this review: unrelated working-tree changes under `docs/version-tracker/1.3.3-SNAPSHOT/`, `navigator-open-sdk/`, and `tools/navigator-upstream/`.

## Quality Checklist

- scope conformance: pass. The implementation is limited to issue #148 continuation handling, the minimum token lookup needed to keep the recovery boundary strict, and the dispatch branch that the follow-up smoke identified.
- code hygiene: pass with minor follow-up. No debug branch or temporary TODO was found; two test stubs still reference the older `resolveSessionId(...)` path and can be cleaned during the next test touch.
- duplication and consolidation: pass with minor follow-up. `resolveTaskSessionId(...)` and `resolveTaskNavigatorSessionId(...)` both read `metadata.sessionId`; the fallback behavior differs, so this is acceptable for the fix, but a small shared helper would improve maintainability.
- complexity and abstraction: pass. The controller gained recovery logic, but it remains localized and guarded by helper methods. The `TaskDispatchFacade` follow-up is a single branch guard using the existing `resume` request flag. If more delayed-binding cases appear, controller recovery should move into a small domain service rather than keep growing in `OpenApiController`.
- error handling and edge cases: pass. Missing upstream user, missing business session, cross-upstream access, same-upstream recovery, and agent-owner projection are covered. Rejected continuations return `RX.failB(...)` and do not call the agent.
- readability and maintainability: pass. The recovery rule is understandable from the helper names and test names. The warning log for a recovered missing business row is useful operational evidence.
- critical logic documentation: pass. The bug work item documents why task metadata `sessionId` is preferred and why recovery must require same target agent plus same-upstream active OpenAPI task-scoped token.
- contract and compatibility: pass. No OpenAPI success response shape change is introduced. Invalid continuation behavior changes from default HTTP 500 to structured RX failure, which matches the issue expectation.
- documentation and writeback: pass. BUG work item and this formal quality review are present under `docs/version-tracker/1.3.2-SNAPSHOT/`.
- test alignment: pass. Regression tests exercise first-turn bind from task metadata, missing-row recovery, cross-upstream rejection, no-send invalid continuation behavior, and the LangGraph Biz ordinary continuation route that must not call provider resume.
- release readiness: ready for external verification. Code-level review is clear and the equivalent OpenAPI + `LANGGRAPH_BIZ` live continuation path has passed. Final external closure should repeat the same two-turn smoke with the reported TMS tenant/client app credentials.

## Findings

- finding 1: No blocking implementation issue remains after the issue-comment follow-up. The live smoke failure in comment `4885983136` exposed an additional dispatch-layer bug; this review includes the targeted fix and regression test for that path.
- finding 2: The recovery boundary is appropriately strict. A missing `business_agent_session` row is recoverable only when the Navigator context belongs to the resolved agent owner and target agent, and an active OpenAPI task-scoped token exists for the same tenant, client app, upstream user, and context.
- finding 3: The primary fix path is consistent with the Claude Worker A2A adapter contract: submitted tasks include `metadata.sessionId`, so first-turn business session binding no longer depends only on the delayed context query mapping.
- finding 4: The dispatch-layer fix preserves explicit resume semantics: `createTask(..., resume=true)` can still call provider `resumeTask(...)`, while ordinary `createTask(...)` with a bound `contextId` continues by creating a new task in the same Navigator session.
- finding 5: Minor maintainability cleanup remains around duplicate metadata `sessionId` extraction and stale unit-test stubs. This does not affect the current behavior or delivery decision.

## Risks / Follow-ups

- risk 1: The equivalent live OpenAPI path has passed, but the exact reported TMS replay could not be run in this workspace because local `.navigator/upstream.env` points to legacy `capp_2852124a-...` / disabled `tms-agent-v305`, while `tms-tenant-88800-root-agent` is registered under `capp_c958991d-...` and the matching runtime credential profile is not present.
- risk 2: The recovery path depends on an active OpenAPI task-scoped token that initially stores the business `contextId`. If an old or manually crafted context lacks that token, continuation should still be rejected; that is intentional but should be understood by the integrator.
- follow-up 1: Rerun and record one live OpenAPI two-turn smoke in the reported TMS environment after supplying the matching `capp_c958991d-...` runtime credential profile: first ask without `contextId`, then immediate second ask with the returned `contextId`, same tenant, same client app, same upstream user, and same agent.
- follow-up 2: During the next test-maintenance pass, remove stale `resolveSessionId(...)` stubs from controller unit tests and optionally extract a shared `metadata.sessionId` helper.

## Recommended Next Skills

- `foggy-test-coverage-audit`: recommended before formal signoff, mainly to record the unit and live-smoke evidence mapping.
- `foggy-bug-regression-workflow`: already applied through the BUG work item and regression test decision.
- `plan-evaluator`: not required; current approach is scoped and does not need architecture replanning.
- back to implementation: not required for code-level delivery; only minor cleanup remains.

## Decision

- decision: ready-for-external-verification
- can_enter_coverage_audit: yes
- follow_up_required: yes
- delivery conclusion: The implementation is suitable for delivery review. No blocking code issue remains in the reviewed paths; focused unit tests and an equivalent live OpenAPI two-turn smoke passed. The remaining closure requirement is a replay with the reported TMS client app credential profile.

## Lightweight Self-Check Note

- self_check_summary: The fix closes the direct HTTP 500 causes found so far, preserves same-upstream ownership checks, adds strict delayed-row recovery, prevents ordinary LangGraph Biz continuation from calling unsupported provider resume, includes focused regression tests, and passed an equivalent live OpenAPI continuation smoke.
- self_check_decision: needs-formal-quality-gate
- self_check_follow_up: Complete exact reported TMS credential replay before external acceptance signoff.
