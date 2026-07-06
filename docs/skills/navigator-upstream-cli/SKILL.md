---
name: navigator-upstream-cli
description: Guide upstream agents and developers to install, configure, use, smoke test, update, and troubleshoot the Navigator Upstream CLI for Foggy Navigator integrations. Use when an upstream project needs CLI-based setup, project-local .navigator/upstream.env configuration, upstream tenant ClientApp provisioning, runtime-token checks, upstream-user grant checks, agent ask/message polling checks, public/account skill bundle sync or clear, CLI self update, or sanitized GitHub issue reporting for CLI gaps or bugs.
---

# Navigator Upstream CLI

Use this skill when working inside an upstream project that needs to integrate with Foggy Navigator through the project-local Navigator Upstream CLI.

Keep this file as the task router and safety checklist. Read only the reference file that matches the current task before running detailed flows.

## First Actions

1. Work from the upstream project root.
2. Check whether the CLI is installed:

```powershell
Test-Path .\tools\navigator-upstream\navi.ps1
```

3. If it is missing or outdated, follow the standalone install/update guide instead of embedding install details here:
   `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/19-navigator-upstream-cli-install-update.md`
4. If the Navigator docs are not available locally, use the current OBS installer:

```powershell
irm https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/navigator-upstream-cli/install.ps1 | iex
```

5. Keep real credentials only in the upstream project's gitignored `.navigator/upstream.env`.
6. Prefer the published OBS installer scripts for local worker runtimes instead of manually downloading archives or copying workspace folders. Use the relevant installer before registering the worker in Navigator:

Claude Worker:

```bash
curl -sSL https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-worker/install.sh | bash
```

```powershell
irm https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-worker/install.ps1 | iex
```

Codex Worker:

```bash
curl -sSL https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/codex-worker/install.sh | bash
```

```powershell
irm https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/codex-worker/install.ps1 | iex
```

LangGraph Biz Worker:

```bash
curl -fsSL https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/langgraph-biz-worker/install.sh | bash
```

```powershell
irm https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/langgraph-biz-worker/install.ps1 | iex
```

Codex Biz Worker is a Navigator route over the Codex Worker runtime. There is no separate `codex-biz-worker` installer; install/update `codex-worker`, then use the `codex-biz-worker` provider route only when that explicit route is intended.

For local multi-worker bootstrap, `navi upstream worker-host install --file .navigator/worker-host.json` can orchestrate the same published installers for `claudeCode`, `codex`, and `biz` roles. Use `--dry-run` first to inspect the installer commands, then register/update Navigator resources with `worker-host apply` or `worker-host update`.

## Project Local Config

The installer creates:

```text
.navigator/upstream.env
tools/navigator-upstream/navi.ps1
tools/navigator-upstream/navi.cmd
```

Fill `.navigator/upstream.env` with the current upstream project's values:

```properties
NAVI_BASE_URL=http://localhost:8112
NAVI_TENANT_ID=<tenantId>
NAVI_CLIENT_APP_ID=<clientAppId>
NAVI_CLIENT_APP_KEY=<clientAppKey>
NAVI_CLIENT_APP_SECRET=<clientAppSecret>
NAVI_CLIENT_APP_ACCESS_TOKEN=
NAVI_CONTROL_API_KEY=<clientAppScopedControlKey>
NAVI_ADMIN_API_KEY=<upstreamSystemScopedClientAppAdminKey>
NAVI_ADMIN_TOKEN=<temporaryNavigatorLoginJwtForAdminKeyApproval>
NAVI_ADMIN_USER_ID=<optionalNavigatorAdminUserId>
NAVI_ADMIN_USERNAME=<optionalNavigatorAdminUsername>
NAVI_USER_API_KEY=<optionalTenantAdminUserApiKey>
NAVI_ADMIN_KEY_REQUEST_CODE=<requestCode>
NAVI_ADMIN_KEY_CLAIM_TOKEN=<claimToken>
NAVI_UPSTREAM_SYSTEM_ID=<upstreamSystemId>
NAVI_SOURCE_TENANT_ID=<sourceTenantId>
NAVI_UPSTREAM_MULTI_TENANT=true
NAVI_UPSTREAM_USER_ID=<upstreamUserId>
NAVI_UPSTREAM_USER_TOKEN=<optionalCurrentUpstreamUserToken>
NAVI_AGENT_CODE=<agentId>
NAVI_MODEL_CONFIG_ID=<modelConfigId>
NAVI_MODEL_VARIANT=<optionalModelVariant>
NAVI_SKILL_ID=<skillId>
NAVI_WORKER_ID=<workerId>
NAVI_DIRECTORY_ID=<directoryId>
NAVI_PROVIDER_TYPE=
NAVI_CODEX_HOME_KEY=
NAVI_PRIVATE_ACCOUNT_ID=
NAVI_CODEX_SANDBOX_MODE=workspace-write
NAVI_CODEX_APPROVAL_POLICY=never
NAVI_CODEX_NETWORK_ACCESS_ENABLED=false
NAVI_CODEX_WEB_SEARCH_MODE=disabled
NAVI_POLL_INTERVAL_SECONDS=4
```

Do not share one global profile across multiple upstream projects. Normal commands should not need `--profile`; the installed wrapper passes this project's `.navigator/upstream.env` automatically.

`NAVI_MODEL_CONFIG_ID` may be empty. When empty, Navigator resolves the Agent/default model config grant for the current ClientApp. A command-line `--model-config-id` overrides the profile/env value for a new task.

`NAVI_MODEL_VARIANT` is optional and only selects the concrete model name inside the same model config, such as `sonnet`, `opus`, or `codex-mini`. A command-line `--model-variant` overrides the profile/env value for a new task. It must be allowed by the model config `availableModels` and cannot change the backend. Do not use it to switch models while continuing an existing task/context; Navigator freezes the task's effective model name.

## Reference Routing

Read the smallest matching reference before using detailed flows:

- `references/runtime-contract.md`: BizWorker context/session rules, `clientContext`, hidden skill routing, command gates, diagnostics session-dir, model runtime budget presets, and owner-aware runtime diagnostics.
- `references/tms-db-binding.md`: TMS X3 DB-backed ClientApp binding, readiness smoke, and why `.navigator/upstream.env` is CLI/bootstrap only for TMS.
- `references/admin-key-bootstrap.md`: multi-tenant upstream admin-key request/claim/approval, ClientApp ensure, runtime/control credential issue, and aggregate tenant provisioning.
- `references/worker-setup-handoff.md`: external AI handoff package for installing Claude/Codex workers, verifying local runtimes, and configuring/updating them in a provided Navigator account.
- `references/programming-project-orchestration.md`: non-TMS coding-project bootstrap for worker, directory, ClientApp, model config, and A2A agent sync.
- `references/smoke-and-diagnostics.md`: owner-smoke, verify-agent-readiness, live ask/messages polling, Codex Biz Worker local sim lane, skill artifact reads, sessions, and session-messages.
- `references/model-and-e2e.md`: model grant management, ClientApp-owned model create/update/rotate-key, runtime budget preset options, deterministic E2E model, and `navi-e2e` wrapper.
- `references/bundle-and-function-management.md`: agent bundle sync, skill bundle sync, schema placeholders, Business Function import/grant/visibility, and ClientApp control credential delivery.
- `references/maintenance-and-troubleshooting.md`: stale skill clear commands, account-context files, self update semantics, package metadata checks, and troubleshooting entries.

## Common Smoke Flow

For a normal upstream project, run the release gate before any live `ask`:

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

Read `references/smoke-and-diagnostics.md` before changing the smoke shape, using `clientContext`, doing live actor smoke, polling messages, reading skill artifacts, or diagnosing Codex Biz Worker local sim.

## Critical Runtime Rules

- For new BizWorker sessions, omit `--context-id`; reuse a returned context id only for continuation in the same ClientApp and upstream user boundary.
- `clientContext` is session metadata only. Do not use it for system prompts, model budgets, workspace roots, skill/function private config, or LLM-visible execution settings.
- Do not send full transcript, `recentConversation`, or prompt history through `clientContext`.
- Do not pass hidden skill routing fields such as `businessSkillName` or `businessSkillId`; ask for a specific business skill in the user message and verify the result artifacts.
- Use `--model-config-id` and `--model-variant` as explicit runtime fields; do not put model selection or token budgets in `clientContext`.
- Prefer `upstream owner-smoke` before live `ask`; use `--no-directory-required` only when the target Agent is intentionally workspace-free.
- For `messages --task-id`, always pass `--agent-code <agentId>` or `--agent <agentId>` explicitly.
- For TMS X3, `.navigator/upstream.env` is not a deployed microservice runtime source. TMS readiness must prove DB-backed binding resolution.

## Safety Rules

- Do not print or paste token, secret, runtime access token, upstream user token, admin token, or staff session token.
- Do not print or paste `NAVI_ADMIN_API_KEY`, `NAVI_ADMIN_TOKEN`, `NAVI_ADMIN_KEY_CLAIM_TOKEN`, or `NAVI_OPERATOR_API_KEY`.
- Do not print or paste `NAVI_USER_API_KEY`.
- Do not print or paste `NAVI_CONTROL_API_KEY`.
- Do not include `adapterConfigJson`, `manifestJson`, private business data, or credentials in prompts, docs, logs, screenshots, or GitHub issues.
- Do not put full transcript, `recentConversation`, model token budgets, workspace roots, or system prompt overrides into `clientContext`.
- Do not call `/internal/worker-gateway/v1/**`.
- Do not send `NAVI_CLIENT_APP_SECRET` on `ask`; exchange it for a runtime access token first.
- Do not write runtime tokens to a profile unless `upstream config check` reports `profileGitIgnored=true`.
- Ensure `ask` uses the current upstream user id through `X-Upstream-User-Id`.
- Treat task status `COMPLETED`, `FAILED`, `CANCELED`, or `CANCELLED` as message polling stop conditions.

## When CLI Is Not Enough

If the CLI lacks a required capability, appears to have a bug, or the upstream project needs Navigator-side help:

1. Capture the CLI version, command, sanitized error summary, expected behavior, and environment type.
2. Create an issue at `https://github.com/foggy-projects/Foggy-Navigator/issues`.
3. Link the issue in the handoff notes.

Never include token, secret, runtime access token, upstream user token, `adapterConfigJson`, `manifestJson`, or private business data in the issue.
