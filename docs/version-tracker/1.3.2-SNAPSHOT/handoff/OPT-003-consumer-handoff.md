---
doc_role: consumer-handoff
version: 1.3.2-SNAPSHOT
target: OPT-003-codex-biz-upstream-acceptance
status: ready-for-consumer-smoke
updated_at: 2026-06-30
---

# OPT-003 Consumer Handoff

## Contract

- `directoryId` means the resolved Navigator WorkingDirectory/effective directory. It is not SIM-specific: SIM may derive it from actor-owned mapping; TMS may derive it from profile, route, or client-app binding.
- `privateAccountId` / `codexHomeKey` selects the Codex scoped home. It does not replace `directoryId`.
- `providerType=codex-biz-worker` is an explicit opt-in route. LangBizWorker remains the default formal enterprise business route.
- Continuation on an existing `contextId` should omit `providerType` / worker type and `directoryId`; Navigator restores provider, worker and directory from the context binding. Current direct CodexBizWorker calls still need the same actor/account scoped-home source from adapter/profile/upstream-user mapping, or an explicit `privateAccountId` / `codexHomeKey`, until context-bound scoped-home replay is implemented.
- `allowedTools` is a top-level ask option and is now MCP-tool granular: `business.functions.list` exposes `list_business_functions`; `business.functions.schema` exposes `get_business_function_schema`; `business.functions.invoke` exposes `invoke_business_function`; `business.functions.*` or `business.*` exposes all three.
- Business function ids such as `submit_skill_result` are not MCP tool grants. Grant the function through Navigator WorkerGateway permissions, and grant the MCP bridge with `business.functions.*` or the specific MCP tool aliases above.
- OPEN_ARTIFACT payloads should use `type`, `label`, `artifact.uri`, and optional `context`. Legacy `previewUrl` / `url` may be accepted by adapters, but consumer evidence should record the normalized artifact URI.
- `TaskEvidence.structuredOutput` resolves in this order: task state, message metadata, then visible final JSON message content. Codex final JSON with `structured_output.*` fields is lifted with source `message_content`.

## SIM Smoke

Use SIM's actor-owned directory mapping as the source of the effective Navigator directory. Do not force SIM to adopt the TMS profile model.

```powershell
.\tools\navigator-upstream\navi.ps1 upstream owner-smoke
.\tools\navigator-upstream\navi.ps1 upstream ask `
  --provider-type codex-biz-worker `
  --directory-id <resolvedNavigatorDirectoryId> `
  --private-account-id <tenant/scenario/actor> `
  --allowed-tools business.functions.schema,business.functions.invoke `
  --message "<ask the agent to produce submit_skill_result OPEN_ARTIFACT evidence>"
.\tools\navigator-upstream\navi.ps1 upstream evidence --task-id <taskId>
.\tools\navigator-upstream\navi.ps1 upstream diagnostics --task-id <taskId>
.\tools\navigator-upstream\navi.ps1 upstream ask `
  --context-id <returnedContextId> `
  --private-account-id <same-tenant/scenario/actor-derived-key> `
  --message "<short continuation smoke; do not pass providerType or directoryId>"
```

Record: route/profile, actor mapping input, resolved `directoryId`, scoped home key source, task id, context id, continuation task id, WorkerGateway invoke status, tool-message audit status, and `TaskEvidence.structuredOutput.source`.

## TMS Smoke

Use TMS profile / route / client-app binding as the source of the effective Navigator directory. Do not require TMS to persist SIM actor ids.

```powershell
.\tools\navigator-upstream\navi.ps1 upstream owner-smoke
.\tools\navigator-upstream\navi.ps1 upstream ask `
  --provider-type codex-biz-worker `
  --directory-id <resolvedNavigatorDirectoryId-if-not-injected-by-adapter> `
  --codex-home-key <tenant/clientApp/upstreamUser> `
  --allowed-tools business.functions.schema,business.functions.invoke `
  --message "<ask the agent to produce submit_skill_result OPEN_ARTIFACT evidence>"
.\tools\navigator-upstream\navi.ps1 upstream evidence --task-id <taskId>
.\tools\navigator-upstream\navi.ps1 upstream diagnostics --task-id <taskId>
.\tools\navigator-upstream\navi.ps1 upstream ask `
  --context-id <returnedContextId> `
  --codex-home-key <same-tenant/clientApp/upstreamUser-derived-key> `
  --message "<short continuation smoke; do not pass providerType or directoryId>"
```

Record: TMS route/profile id, effective directory source, runtime credential source, scoped home key source, task id, context id, continuation task id, WorkerGateway invoke status, tool-message audit status, and `TaskEvidence.structuredOutput.source`.

## Wrapper Gate

Current Navi project-local wrapper was updated to `navigator-upstream-cli 1.0.18` with package SHA `c7dbfbf364bd584e6c2f9414bdcabaa44b53bb688ce9c24f1b1bacc891abec70` and buildId `1.0.18+970590c3f2d7`. The local package metadata reports `gitDirty=True`; consumer projects should compare `packageSha256` or `buildId`, not only semantic version.

Before consumer smoke, run:

```powershell
.\tools\navigator-upstream\navi.ps1 version
.\tools\navigator-upstream\navi.ps1 upstream --help
```

If the consumer project still has an older wrapper, update from the released `latest.json` after the package is published, or use the rebuilt SDK jar for smoke only and record that deviation.

## Acceptance Evidence

Consumer signoff must include:

- self-owned or consumer-owned provisioning summary without raw keys.
- Worker health/readiness summary.
- `submit_skill_result` task evidence: taskId, contextId, providerType, workerBackend, effective directory marker, structured output source.
- BusinessFunction evidence: function id/version, schema summary, invoke result summary, WorkerGateway/tool-message audit marker.
- Context continuation positive case that omits provider/worker/directory overrides, plus a conflict negative case. Record whether scoped-home identity came from adapter/profile or explicit `privateAccountId` / `codexHomeKey`.
- SIM/TMS-specific notes: directory source, Codex home source, route/profile, fallback route.
- Test command summary and result.
- Explicit production boundary statement: CodexBizWorker remains opt-in gray/internal/developer route.
