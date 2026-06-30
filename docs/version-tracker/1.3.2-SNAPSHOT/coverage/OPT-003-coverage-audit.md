---
audit_scope: feature
audit_mode: pre-acceptance-check
version: 1.3.2-SNAPSHOT
target: OPT-003-codex-biz-upstream-acceptance
status: reviewed
conclusion: ready-with-gaps
reviewed_by: Codex
reviewed_at: 2026-06-30
follow_up_required: yes
---

# Test Coverage Audit

## Background

This audit maps OPT-003 acceptance requirements to automated tests and live smoke evidence before final signoff.

## Audit Basis

- Requirement/progress: `docs/version-tracker/1.3.2-SNAPSHOT/workitems/OPT-003-codex-biz-upstream-acceptance.md`
- Quality gate: `docs/version-tracker/1.3.2-SNAPSHOT/quality/OPT-003-implementation-quality.md`
- Live smoke: task `20260630-b499`, workerTask `3c9ef3e8-80c6-4061-8e6c-231c6ae0c95c`

## Coverage Matrix

| Item | Risk | unit-test | integration-test | e2e-test | playwright-test | manual-evidence | Evidence | Conclusion |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| SDK ask preserves `allowedTools` as top-level runtime option | major | yes | no | no | no | no | `UpstreamCliTest` CLI/env assertions | covered |
| Worker MCP enforces `allowedTools` at MCP tool granularity | critical | yes | no | yes | no | no | `navigator-business-mcp.test.ts`; `sdk-wrapper.test.ts` | covered |
| Logical upstream `agentId` plus explicit `providerType=codex-biz-worker` routes direct | critical | yes | no | no | no | yes | `TaskDispatchFacadeTest`; live task providerType `codex-biz-worker` | covered |
| Worker MCP config persists no token and includes cwd/env var names/approval whitelist | critical | yes | no | yes | no | yes | `sdk-wrapper.test.ts`; scoped `config.toml` managed block | covered |
| MCP stdio works with Codex Content-Length framing | critical | yes | no | yes | no | yes | `navigator-business-mcp.test.ts`; live MCP debug log includes `tools/call` | covered |
| BusinessFunction list/schema/invoke and tool-message audit | critical | yes | no | yes | no | yes | Worker log and MCP debug log for task `20260630-b499`; backend tool-message log | covered |
| Task-scoped token hygiene | critical | yes | no | yes | no | yes | No token in scoped config, worker log or MCP debug log; backend logs only task/session ids | covered |
| Context continuation positive and directory conflict negative | major | yes | no | yes | no | yes | Prior live tasks and `TaskDispatchFacadeTest` route guard | covered |
| `TaskEvidence.structuredOutput` auto-lifts OPEN_ARTIFACT | major | yes | no | no | no | yes | `OpenApiControllerMessageMappingTest`; live task `20260630-af4c` evidence reports `structuredOutput.available=true`, `source=message_content` | covered |
| SIM/TMS consumer-specific smoke | major | no | no | no | no | no | Protocol handed off; consumer repos must record their own evidence | partially-covered |

## Evidence Summary

- Java targeted tests previously passed: `UpstreamCliTest`, `TaskDispatchFacadeTest`.
- Java OpenAPI evidence regression passed on 2026-06-30: `mvn -q -am -pl addons/claude-worker-agent "-Dtest=OpenApiControllerMessageMappingTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`.
- Worker tests passed on 2026-06-30: `npm test -- tests/navigator-business-mcp.test.ts tests/sdk-wrapper.test.ts`; the script ran the full Worker suite, 97 tests passed.
- Worker typecheck passed: `npm run typecheck`.
- Live submit smoke passed: task `20260630-b499` completed with marker `codex-biz-smoke-20260630-134920`.
- Live structured output lift passed: task `20260630-af4c` completed with marker `codex-biz-smoke-20260630-continue-143306`; evidence re-read after service restart reported `structuredOutput.available=true`, `source=message_content`, `value.type=OPEN_ARTIFACT`.
- MCP/WorkerGateway evidence passed: list/schema/invoke/tool-message all completed with HTTP 200.

## Gaps

- SIM and TMS consumer-side smoke evidence is not present in this repo and must be completed by each owner.
- Consumer-visible `navigator-upstream-cli` publication should be verified by packageSha/buildId before consumer self-update.

## Recommended Next Skills

- `foggy-acceptance-signoff`

## Conclusion

Coverage is sufficient for self-owned Navigator acceptance with consumer gaps. The remaining gaps are SIM/TMS-owned smoke evidence and consumer-visible wrapper publication verification; they do not block handing the protocol and evidence package to SIM/TMS owners.
