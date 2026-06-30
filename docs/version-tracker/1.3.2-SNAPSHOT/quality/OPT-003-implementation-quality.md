---
quality_scope: feature
quality_mode: pre-coverage-audit
version: 1.3.2-SNAPSHOT
target: OPT-003-codex-biz-upstream-acceptance
status: reviewed
decision: ready-with-risks
reviewed_by: Codex
reviewed_at: 2026-06-30
follow_up_required: yes
---

# Implementation Quality Gate

## Background

OPT-003 将 CodexBizWorker 从 route readiness 推进到可交付上游验收的 self-owned smoke 闭环，覆盖 SDK ask runtime option、session 显式 provider route、Worker navigator_business MCP bridge、Windows/Codex MCP 配置和签收文档。

## Check Basis

- Workitem: `docs/version-tracker/1.3.2-SNAPSHOT/workitems/OPT-003-codex-biz-upstream-acceptance.md`
- Prior readiness: `OPT-001-codex-biz-route-readiness.md`, `OPT-002-langgraph-biz-actor-home-readiness.md`
- Live evidence: task `20260630-b499`, context `bctx_20260630_36_36787f40b092468e8183687a84ea0d01`, workerTask `3c9ef3e8-80c6-4061-8e6c-231c6ae0c95c`

## Changed Surface

- `navigator-open-sdk`: OpenAPI ask top-level `allowedTools` passthrough and CLI/env parsing.
- `session-module`: explicit `providerType=codex-biz-worker` direct route when logical upstream `agentId` is present.
- `tools/codex-agent-worker`: no-secret scoped MCP config, `cwd`, inherited env vars, Codex MCP approval whitelist, Content-Length stdio framing, MCP debug logs, MCP tool-granular allowlist.
- `addons/claude-worker-agent`: `TaskEvidence.structuredOutput` fallback for Codex final JSON `structured_output.*` content.
- `tools/navigator-upstream`: project-local wrapper metadata and SDK jar updated to 1.0.18 while preserving smoke scripts.
- Docs: OPT-003 workitem, version index, quality, coverage and acceptance records.

## Quality Checklist

- scope conformance: pass. Changes are scoped to CodexBiz upstream acceptance and do not switch default enterprise business route away from LangBizWorker.
- code hygiene: pass. No temporary branches or token-bearing config are persisted. Debug logging records method/path/status only.
- duplication and consolidation: pass. MCP env key and tool-name lists are centralized; SDK config rendering reuses common TOML helpers.
- complexity and abstraction: pass with minor risk. The scoped config upsert is intentionally local to Worker SDK wrapper because it is Codex-specific.
- error handling and edge cases: pass. Missing token disables MCP config; stdio supports both Content-Length and JSONL; unknown tools remain errors.
- readability and maintainability: pass. Routing helper `isKnownCommandProvider` removes repeated registry checks.
- critical logic documentation: pass. Workitem records why `directoryId`, `codexHomeKey`, MCP approval and structured output handling differ from SIM-only assumptions.
- contract and compatibility: pass with risk. `allowedTools` remains additive at the OpenAPI/SDK layer and is enforced at MCP tool granularity inside Codex Worker. Codex MCP approval fields rely on Codex CLI 0.142.3 documented config behavior.
- documentation and writeback: pass. Workitem and index are updated; formal quality/coverage/acceptance docs are added.
- test alignment: pass. Tests cover SDK serialization, route priority, MCP config/token hygiene and stdio framing; live smoke covers WorkerGateway path.
- release readiness: ready with risks. Consumer-specific SIM/TMS smoke remains outside this repo handoff.

## Findings

No blocking implementation issue found.

## Risks / Follow-ups

- Consumer-visible `navigator-upstream-cli` publication must be verified by packageSha/buildId; this project-local wrapper is already on 1.0.18.
- SIM and TMS must run their own consumer smoke and record route/profile, effective directory and Codex home sources.

## Recommended Next Skills

- `foggy-test-coverage-audit`
- `foggy-acceptance-signoff`

## Decision

Implementation is ready to enter coverage audit with the risks above. None block the self-owned Navigator acceptance scope.
