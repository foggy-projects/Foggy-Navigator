---
acceptance_scope: feature
version: 1.3.0-SNAPSHOT
target: REQ-041-world-sim-task-diagnostics-contract
status: signed-off
decision: accepted-with-risks
signed_off_by: codex
signed_off_at: 2026-05-27
reviewed_by: codex
blocking_items: []
follow_up_required: yes
evidence_count: 11
doc_role: feature-acceptance
doc_purpose: Sign off the facts-layer task diagnostics and completion evidence contract for world-sim recovery.
---

# Feature Acceptance

## Background

foggy-world-sim issue #134 requested Navigator-side diagnostics and completion evidence so world-sim can decide whether to wait, retry, pause, or accept task results. Navigator accepted the request with a strict boundary: Navigator returns facts and capability metadata; world-sim owns adjudication.

## Acceptance Basis

- Requirement/workitem: `docs/version-tracker/1.3.0-SNAPSHOT/workitems/REQ-041-world-sim-task-diagnostics-contract.md`
- Quality gate: `docs/version-tracker/1.3.0-SNAPSHOT/quality/req-041-world-sim-task-diagnostics-implementation-quality.md`
- Coverage audit: `docs/version-tracker/1.3.0-SNAPSHOT/coverage/req-041-world-sim-task-diagnostics-coverage-audit.md`
- Source issue: `foggy-projects/Foggy-Navigator#134`

## Checklist

| Item | Result | Notes |
| --- | --- | --- |
| Diagnostics endpoint available | pass | `GET /api/v1/open/agents/{agentId}/tasks/{taskId}/diagnostics` implemented. |
| Evidence endpoint available | pass | `GET /api/v1/open/agents/{agentId}/tasks/{taskId}/evidence` implemented. |
| Not-picked-up facts distinguishable | pass | `SUBMITTED` with no worker start, no provider task id, and zero messages is covered. |
| Message event contract | pass | Messages expose `eventKind`, `progressType`, terminal marker, report refs, and artifact refs. |
| Recovery correlation | pass | Upstream metadata is persisted and returned as sanitized facts. |
| Cancel/cleanup capability | pass | Unsupported runtime cancel is explicit and not advertised as supported. |
| Security boundary | pass | Runtime token route, tenant/agent task ownership, and masking tests pass. |
| SDK/CLI consumption | pass | SDK models, API methods, CLI commands, and message event output are covered. |
| Package metadata | pass | Local 1.0.16 package contains `task-evidence` and `message-event-contract` feature metadata. |
| Navigator OpenAPI E2E smoke | pass | Opt-in smoke submits through ClientApp runtime token, reaches BizWorker, and validates diagnostics/messages/evidence. |
| Navigator adjudication boundary | pass | No world-sim recovery verdict was added to Navigator core. |

## Evidence

1. `mvn -pl addons/claude-worker-agent -am -Dtest=OpenApiControllerMessageMappingTest "-Dsurefire.failIfNoSpecifiedTests=false" test` passed, 28 tests.
2. `mvn -pl session-module -am "-Dtest=TaskDispatchFacadeTest,OpenApiSessionQueryServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` passed, 64 tests.
3. `mvn -pl addons/langgraph-biz-worker -am -Dtest=LanggraphTaskServiceTest "-Dsurefire.failIfNoSpecifiedTests=false" test` passed, 24 tests.
4. `mvn -pl navigator-open-sdk -Dtest=UpstreamCliTest test` passed, 80 tests.
5. `npm run typecheck` in `business-agent-module/integration-tests` passed.
6. `$env:BIZ_AGENT_E2E_OPENAPI_DIAGNOSTICS_SMOKE='true'; npm test -- tests/04-openapi-task-diagnostics-evidence-contract.test.ts` passed, 1 test, 87.92s.
7. Smoke evidence record: `docs/version-tracker/1.3.0-SNAPSHOT/test-records/req-041-openapi-diagnostics-evidence-smoke-20260527.md`.
8. `mvn -pl session-module,addons/claude-worker-agent,navigator-open-sdk -am -DskipTests compile` passed.
9. `powershell -ExecutionPolicy Bypass -File tools\navigator-upstream-cli\dist\package.ps1` built `navigator-upstream-cli-1.0.16-windows.zip`.
10. Package SHA256: `a5a639da836a1be83e984cfff5a338ae9239f542d73d8d3dd823f4588fa14ea2`.
11. Workitem progress and acceptance mapping are updated in `REQ-041-world-sim-task-diagnostics-contract.md`.

## Failed Items

None.

## Risks / Open Items

- Remote CLI publication/upload was not performed. Release owner should publish the generated 1.0.16 package when ready.
- External world-sim route smoke was not run in this local pass. Navigator-owned OpenAPI/BizWorker/mock-LLM E2E smoke passed and is retained as opt-in regression coverage.
- Evidence endpoint returns refs and summaries only; full frame report content remains behind the existing frame report API.

## Final Decision

Decision: `accepted-with-risks`.

The first facts-layer slice satisfies the agreed contract and is acceptable for integration. Navigator-owned E2E smoke passed; remaining risks are release/external-upstream follow-ups, not blockers for the implemented Navigator contract.

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: codex
- signed_off_at: 2026-05-27
- acceptance_record: `docs/version-tracker/1.3.0-SNAPSHOT/acceptance/req-041-world-sim-task-diagnostics-acceptance.md`
- blocking_items: none
- follow_up_required: yes
