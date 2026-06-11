# REQ-041 OpenAPI Diagnostics / Evidence Smoke

## Result

- date: 2026-05-27
- status: pass
- scope: local Navigator E2E with real OpenAPI route, real LangGraph BizWorker bridge, mock LLM backend
- regression_test: `business-agent-module/integration-tests/tests/04-openapi-task-diagnostics-evidence-contract.test.ts`
- opt_in_flag: `BIZ_AGENT_E2E_OPENAPI_DIAGNOSTICS_SMOKE=true`

## Environment

- Navigator API: `http://localhost:8112`
- LangGraph BizWorker health: `http://127.0.0.1:3065/health`
- Mock LLM admin health: `http://127.0.0.1:3066/admin/health`
- Mock LLM API base: `http://127.0.0.1:3066/v1`

Health checks passed before the smoke run.

## Commands

```powershell
cd business-agent-module/integration-tests
npm run typecheck
$env:BIZ_AGENT_E2E_OPENAPI_DIAGNOSTICS_SMOKE='true'
Remove-Item Env:BIZ_AGENT_E2E_MOCK_BASE_URL -ErrorAction SilentlyContinue
npm test -- tests/04-openapi-task-diagnostics-evidence-contract.test.ts
```

## Evidence

- `npm run typecheck`: pass.
- `npm test -- tests/04-openapi-task-diagnostics-evidence-contract.test.ts`: pass, 1 test, duration 87.92s.
- Java preservation regression:

```powershell
mvn -pl addons/langgraph-biz-worker -am -Dtest=LanggraphTaskServiceTest "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Result: pass, 24 tests.

## Covered Flow

The smoke creates a fresh tenant/admin, registers a LangGraph worker and BizWorker identity, creates a ClientApp, grants an upstream user, ensures a mock model config, issues runtime/control credentials, syncs the Agent bundle, exchanges a runtime token, and calls:

```text
POST /api/v1/open/agents/{agentId}/ask
GET  /api/v1/open/agents/{agentId}/tasks/{taskId}
GET  /api/v1/open/agents/{agentId}/tasks/{taskId}/messages
GET  /api/v1/open/agents/{agentId}/tasks/{taskId}/diagnostics
GET  /api/v1/open/agents/{agentId}/tasks/{taskId}/evidence
```

Key assertions:

- upstream does not provide first-turn `contextId`; Navigator generates `bctx_yyyyMMdd_<hash>_<id>`.
- task reaches `COMPLETED` through the real BizWorker path.
- diagnostics returns status, worker/backend facts, provider/model facts, cancel capability, and recovery correlation facts.
- messages expose `eventKind=final_marker` and terminal facts.
- evidence exposes final answer summary and `frame_report` refs.
- response payload does not leak runtime credential secret or runtime access token.
- mock LLM debug records include the expected trace and no tool calls.

## Defects Caught Before Pass

1. The integration-test default mock LLM URL pointed at `http://localhost:18080/v1`, which in the current E2E environment is not the mock LLM service. The default is now `http://127.0.0.1:3066/v1`, and the health check verifies JSON `status=ok`.
2. `LanggraphTaskService.syncSessionTask` overwrote existing `SessionTask.taskStateJson`, losing submit-time diagnostics/correlation metadata before OpenAPI diagnostics could read it. The sync now merges existing state and overlays worker facts.
3. The smoke test data used `task-<random>` IDs, which can contain an `sk-...` substring and trigger secret masking. Test correlation IDs now avoid secret-like substrings.
4. Real evidence includes a safe `frame_report` ref, so the smoke now validates the ref shape instead of assuming an empty report list.

## Follow-up Boundary

This is the Navigator-owned mock E2E regression for the REQ-041 facts contract. It proves the platform route and BizWorker bridge. A separate external world-sim live route smoke can still be run by world-sim to validate its own adjudication logic, but Navigator does not need to embed that adjudication.
