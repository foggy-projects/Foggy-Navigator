---
audit_scope: feature
audit_mode: pre-acceptance-check
version: 1.3.0-SNAPSHOT
target: REQ-041-world-sim-task-diagnostics-contract
status: reviewed
conclusion: ready-for-acceptance
reviewed_by: codex
reviewed_at: 2026-05-27
follow_up_required: no
---

# Test Coverage Audit

## Background

REQ-041 exposes recovery diagnostics and completion evidence for upstream world-sim. The risk areas are external API contract correctness, tenant/agent ownership checks, secret masking, message event semantics, recovery correlation, and SDK/CLI consumption.

## Audit Basis

- Requirement/workitem: `docs/version-tracker/1.3.0-SNAPSHOT/workitems/REQ-041-world-sim-task-diagnostics-contract.md`
- Quality gate: `docs/version-tracker/1.3.0-SNAPSHOT/quality/req-041-world-sim-task-diagnostics-implementation-quality.md`
- Test commands:
  - `mvn -pl addons/claude-worker-agent -am -Dtest=OpenApiControllerMessageMappingTest "-Dsurefire.failIfNoSpecifiedTests=false" test`
  - `mvn -pl session-module -am "-Dtest=TaskDispatchFacadeTest,OpenApiSessionQueryServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
  - `mvn -pl addons/langgraph-biz-worker -am -Dtest=LanggraphTaskServiceTest "-Dsurefire.failIfNoSpecifiedTests=false" test`
  - `mvn -pl navigator-open-sdk -Dtest=UpstreamCliTest test`
  - `npm run typecheck` in `business-agent-module/integration-tests`
  - `$env:BIZ_AGENT_E2E_OPENAPI_DIAGNOSTICS_SMOKE='true'; npm test -- tests/04-openapi-task-diagnostics-evidence-contract.test.ts`
  - `mvn -pl session-module,addons/claude-worker-agent,navigator-open-sdk -am -DskipTests compile`
  - `powershell -ExecutionPolicy Bypass -File tools\navigator-upstream-cli\dist\package.ps1`

## Coverage Matrix

| Requirement / acceptance item | Risk | Evidence layer | Evidence | Coverage |
| --- | --- | --- | --- | --- |
| Safe diagnostics snapshot | critical | unit-test | `OpenApiControllerMessageMappingTest`, diagnostics DTO assertions | covered |
| Not-picked-up facts | critical | unit-test | `SUBMITTED`, `workerStartedAt=null`, `providerTaskId=null`, `messagesCount=0` controller test | covered |
| Completion evidence refs | critical | unit-test, e2e-smoke | Controller evidence test covers final answer, structured output, report refs, artifact refs, query stripping; E2E smoke validates final answer and `frame_report` ref from BizWorker | covered |
| Message/event distinction | major | unit-test, sdk-cli-test | Controller message event contract test and CLI message event/ref output test | covered |
| Recovery correlation | major | unit-test, e2e-smoke | `TaskDispatchFacadeTest` persists metadata; LangGraph sync preserves existing taskState metadata; diagnostics test and E2E smoke return sanitized correlation | covered |
| Cancel capability matrix | major | unit-test | Diagnostics test asserts unsupported runtime cancel and `admin_only` mode | covered |
| Scoped access checks | critical | unit-test, e2e-smoke | Diagnostics ownership rejection test plus runtime-token route checks; E2E smoke uses ClientApp runtime token | covered |
| Secret masking | critical | unit-test, sdk-cli-test, e2e-smoke | Backend evidence test and CLI tests assert masking for token/api_key/Bearer-like values; E2E smoke asserts runtime secret/access token are absent from public payloads | covered |
| SDK/CLI consumption | major | sdk-cli-test, package-evidence | `UpstreamCliTest`; local 1.0.16 package build with feature metadata | covered |
| No Navigator adjudication verdict | major | code-review | Quality gate confirms no world-sim state classifier added | covered |

## Evidence Summary

- OpenAPI controller test: 28 tests passed.
- Session read model/correlation tests: 64 tests passed.
- LangGraph task state preservation tests: 24 tests passed.
- SDK/CLI tests: 80 tests passed.
- Integration test typecheck: passed.
- Navigator OpenAPI diagnostics/evidence smoke: 1 test passed in 87.92s.
- Smoke evidence record: `docs/version-tracker/1.3.0-SNAPSHOT/test-records/req-041-openapi-diagnostics-evidence-smoke-20260527.md`.
- Affected module compile: passed.
- Local upstream CLI package: `navigator-upstream-cli-1.0.16-windows.zip`.
- Package SHA256: `a5a639da836a1be83e984cfff5a338ae9239f542d73d8d3dd823f4588fa14ea2`.
- Package metadata includes `task-evidence` and `message-event-contract`.

## Gaps

No blocking coverage gaps for the first facts-layer slice.

Non-blocking gaps:

- External world-sim route recovery smoke was not run; Navigator-owned OpenAPI/BizWorker/mock-LLM E2E smoke passed.
- No remote CLI upload smoke was run.
- Business frame report body access was not retested because this slice only returns refs and leaves the existing frame report endpoint unchanged.

## Recommended Next Skills

- `foggy-acceptance-signoff`

## Conclusion

Conclusion: `ready-for-acceptance`.

The evidence covers the external contract, security boundary, SDK/CLI consumption, package metadata, and Navigator-owned E2E route needed for this slice.
