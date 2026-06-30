# Smoke And Diagnostics

This reference was split from the navigator-upstream-cli skill main file. Read it only when the current task matches the section title or routing guidance in SKILL.md.

## Smoke Flow

Run these from the upstream project root:

```powershell
.\tools\navigator-upstream\navi.ps1 version
.\tools\navigator-upstream\navi.ps1 upstream config check
.\tools\navigator-upstream\navi.ps1 upstream client-app issue-runtime-key --client-app-id <clientAppId> --write-profile
.\tools\navigator-upstream\navi.ps1 upstream runtime-token --write-profile
.\tools\navigator-upstream\navi.ps1 upstream owner-smoke
.\tools\navigator-upstream\navi.ps1 upstream verify-agent-readiness --upstream-user-id <id>
.\tools\navigator-upstream\navi.ps1 upstream ensure-grant --upstream-user-id <id>
.\tools\navigator-upstream\navi.ps1 upstream ask --upstream-user-id <id> --message "..."
.\tools\navigator-upstream\navi.ps1 upstream messages --task-id <taskId> --agent-code <agentId> --poll --interval 4
```

When verifying session replay or upstream-owned session metadata, `ask` supports:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream ask `
  --upstream-user-id <id> `
  --message "..."

.\tools\navigator-upstream\navi.ps1 upstream ask `
  --upstream-user-id <id> `
  --context-id <returnedContextId> `
  --message "..." `
  --client-context-json '{"upstreamConversationId":"tms-ai-10001"}'

.\tools\navigator-upstream\navi.ps1 upstream ask `
  --upstream-user-id <id> `
  --message "..." `
  --model-variant <sonnet-or-opus-or-other-allowed-model> `
  --client-context-file .\navigator-client-context.json
```

The first `ask` should normally omit `--context-id`; capture the returned `contextId` and pass it only for continuation.

`clientContext` is a top-level `POST /ask` JSON object. Navigator stores it on the session summary only; it is not Worker metadata, not a model/runtime configuration surface, and must not be used for LLM-visible execution settings.

When a live actor smoke targets a specific Biz skill, make the skill request part of `--message` instead of `clientContext` or metadata:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream ask `
  --agent-code school-sim.actor.pm.m2.v1 `
  --upstream-user-id <id> `
  --message "请使用 school-sim.actor.pm.m2.v1 技能，完成 School Sim M2 PM live ask smoke。请写入指定 marker，并只回传 marker 路径和内容。"
```

BizWorker exposes the loaded account/private and public skill catalog (`id`, `name`, `description`) in the Root system prompt. The upstream CLI should not pass hidden skill-routing fields; optional `allowed_skills` context, when supported by the caller, is only a filter over the Worker-loaded catalog.

If the resulting task completes but follows an unrelated default diagnostic flow, treat it as a Biz prompt/skill handoff failure and capture `taskId`, `contextId`, `providerTaskId`, `workerTaskId`, final status, message count, marker path/content, and whether the default flow appeared. Do not ask the upstream to retry by adding hidden `businessSkillName` / `businessSkillId` fields.

### Codex Biz Worker Local Sim Lane

Use `providerType=codex-biz-worker` only for an explicit Codex execution route; do not present it as a transparent replacement for the LangGraph Biz root-skill route. Runtime knobs are top-level `ask` fields, not `clientContext` or hidden metadata:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream ask `
  --agent-code <agentId> `
  --upstream-user-id <scenarioId.actorId> `
  --provider-type codex-biz-worker `
  --directory-id <directoryId> `
  --private-account-id <scenarioId.actorId> `
  --sandbox-mode workspace-write `
  --approval-policy never `
  --network-access-enabled false `
  --web-search-mode disabled `
  --message "..."
```

For local world-sim smoke, run the existing `tools/codex-agent-worker` process in WSL and set `CODEX_BIZ_HOME_ROOT` to an absolute Linux path such as `/home/$USER/.foggy/codex-biz-homes`. `--directory-id` remains a Navigator WorkingDirectory ID and must be bound/visible to the current ClientApp, upstream user, and Agent; it is not a filesystem path. Use a stable actor key for `--private-account-id` or `--codex-home-key` so the Codex worker resolves a durable scoped `CODEX_HOME`.

After changing this route locally, restart both the Java Navigator service and the Codex worker process. Java restart loads the OpenAPI/provider route changes; worker restart applies `CODEX_BIZ_HOME_ROOT`. If Windows Java cannot reach WSL on `localhost`, register the Codex PhysicalWorker base URL with the WSL IP and port.

`runtime-token --write-profile` writes the full `NAVI_CLIENT_APP_ACCESS_TOKEN` only to the current gitignored profile and never prints the full token.

If `.navigator/upstream.env` contains `NAVI_UPSTREAM_USER_ID`, runtime commands that require an upstream user can omit `--upstream-user-id`. Keep using the explicit option when switching users during diagnostics.

If the profile has `NAVI_CLIENT_APP_KEY` and `NAVI_CLIENT_APP_SECRET`, runtime commands automatically exchange a fresh token in memory. This applies to `owner-smoke`, `verify-agent-readiness`, `ask`, `messages`, `sessions`, `session-messages`, `skill tree`, `skill read`, account-private `skill sync`, and `account-context list/read/write-policy`; do not manually copy masked token output back into prompts or docs.

For `messages --task-id`, always pass `--agent-code <agentId>` or `--agent <agentId>` explicitly. CLI task polling intentionally does not fall back to `NAVI_AGENT_CODE`, because project-local profiles can be reused across TMS, School Sim, and other upstreams; a stale profile value can poll the wrong Agent.

Run `owner-smoke` as the first owner-aware release gate. It does not create a task or call the Worker; it checks profile safety, runtime auth, readiness, and resolved Agent / Model / backend capability / PhysicalWorker / Workspace:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream owner-smoke
```

If a target Agent intentionally has no workspace, add `--no-directory-required`. Otherwise fix directory creation and Agent workspace binding before live smoke.

Run `verify-agent-readiness` before the first live actor smoke. It checks the current ClientApp runtime token, registered agent/skill, ClientApp skill grant, upstream user grant, route skill match, and model config grant without creating a Navigator task or calling the Worker.

For a specific skill/agent or model config:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream verify-agent-readiness `
  --agent-code <skill-or-agent-code> `
  --upstream-user-id <id> `
  --model-config-id <modelConfigId> `
  --model-variant <optionalModelVariant>
```

`verify-agent-grant` is accepted as a compatibility alias, but prefer `verify-agent-readiness` in new docs and scripts.


## Skill Artifact Reading

Use these commands when the upstream agent needs to inspect the currently authorized skill delivery files:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream skill tree --agent-code <skill-or-agent-code>
.\tools\navigator-upstream\navi.ps1 upstream skill read --agent-code <skill-or-agent-code> --path SKILL.md --start-line 1 --start-column 1 --max-chars 8000
```

`skill tree` returns only artifact-relative paths and slice URLs. `skill read` reads UTF-8 text by `start-line + start-column + max-chars`; if the response is truncated, continue with the printed `continueCommand`.

Use `sessions` and `session-messages` when verifying context/session replay:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream sessions
.\tools\navigator-upstream\navi.ps1 upstream session-messages --context-id <contextId>
```

`sessions` lists only sessions owned by the current ClientApp and upstream user. `session-messages` reads the same scoped session. Reusing `--context-id` in `ask` is allowed only when that context belongs to the same ClientApp + upstream user; Navigator rejects cross-user continuation before dispatching a task.
