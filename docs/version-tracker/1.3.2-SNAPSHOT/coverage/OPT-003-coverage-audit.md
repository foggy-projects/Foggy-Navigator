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
| Logical upstream `agentId` plus explicit `providerType=codex-biz-worker` routes direct | critical | yes | no | no | no | yes | `TaskDispatchFacadeTest`; live task providerType `codex-biz-worker` | covered |
| Worker MCP config persists no token and includes cwd/env var names/approval whitelist | critical | yes | no | yes | no | yes | `sdk-wrapper.test.ts`; scoped `config.toml` managed block | covered |
| MCP stdio works with Codex Content-Length framing | critical | yes | no | yes | no | yes | `navigator-business-mcp.test.ts`; live MCP debug log includes `tools/call` | covered |
| BusinessFunction list/schema/invoke and tool-message audit | critical | yes | no | yes | no | yes | Worker log and MCP debug log for task `20260630-b499`; backend tool-message log | covered |
| Task-scoped token hygiene | critical | yes | no | yes | no | yes | No token in scoped config, worker log or MCP debug log; backend logs only task/session ids | covered |
| Context continuation positive and directory conflict negative | major | yes | no | yes | no | yes | Prior live tasks and `TaskDispatchFacadeTest` route guard | covered |
| `TaskEvidence.structuredOutput` auto-lifts OPEN_ARTIFACT | major | no | no | no | no | yes | Final JSON carries `structured_output.type=OPEN_ARTIFACT`; evidence structuredOutput is not populated | partially-covered |
| SIM/TMS consumer-specific smoke | major | no | no | no | no | no | Protocol handed off; consumer repos must record their own evidence | partially-covered |

## Evidence Summary

- Java targeted tests previously passed: `UpstreamCliTest`, `TaskDispatchFacadeTest`.
- Worker targeted tests passed on 2026-06-30: `npm test -- --runInBand tests/sdk-wrapper.test.ts tests/navigator-business-mcp.test.ts`, 95 tests passed.
- Worker typecheck passed: `npm run typecheck`.
- Live submit smoke passed: task `20260630-b499` completed with marker `codex-biz-smoke-20260630-134920`.
- MCP/WorkerGateway evidence passed: list/schema/invoke/tool-message all completed with HTTP 200.

## Gaps

- `TaskEvidence.structuredOutput` automatic artifact extraction remains a follow-up; current acceptance uses final JSON and tool-message audit.
- SIM and TMS consumer-side smoke evidence is not present in this repo and must be completed by each owner.
- Full final regression suite still needs to be rerun immediately before commit.

## Recommended Next Skills

- `foggy-acceptance-signoff`

## Conclusion

Coverage is sufficient for self-owned Navigator acceptance with gaps. The remaining gaps are explicit follow-ups and do not block handing the protocol and evidence package to SIM/TMS owners for their consumer validation.
