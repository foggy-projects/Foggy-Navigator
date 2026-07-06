# Maintenance And Troubleshooting

This reference was split from the navigator-upstream-cli skill main file. Read it only when the current task matches the section title or routing guidance in SKILL.md.

## Skill Bundle Clear

Use `skill clear-public` and `skill clear-account` when an upstream project has removed or renamed skills and needs Navigator to clear stale persisted public bundles, account materialized bundles, legacy grants, allowlists, or worker materialized files.

Public skill clear requires `NAVI_CONTROL_API_KEY` plus `NAVI_CLIENT_APP_ID`; admin token/API key are internal fallback only:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream skill clear-public `
  --client-app-id <clientAppId> `
  --dry-run

.\tools\navigator-upstream\navi.ps1 upstream skill clear-public `
  --client-app-id <clientAppId> `
  --skill-id <oldSkillId> `
  --yes
```

Account skill clear targets one upstream account and requires the account id:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream skill clear-account `
  --client-app-id <clientAppId> `
  --account-id <accountId> `
  --dry-run

.\tools\navigator-upstream\navi.ps1 upstream skill clear-account `
  --client-app-id <clientAppId> `
  --account-id <accountId> `
  --skill-id <oldSkillId> `
  --yes
```

Always run `--dry-run` first and review `matchedSkillCount`, `skillBundleCount`, `legacySkillCount`, `clientAppSkillGrantCount`, `skillFunctionAllowlistCount`, `materializedBundleCount`, `cacheCount`, and `matchedSkillId` lines. Non dry-run clear requires `--yes`; zero-match is a valid result only when the output clearly shows `matchedSkillCount=0`.

Use this command for stale data cleanup after upstream skill domain refactors. Do not ask upstream projects to directly delete Navigator database rows or Worker skill directories.


## Account Context Files

Use `account-context` when the upstream BFF or integration agent needs to inspect the current account-level context files or update upstream-controlled policy:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream account-context list --upstream-user-id <id>
.\tools\navigator-upstream\navi.ps1 upstream account-context read --upstream-user-id <id> --file ACCOUNT_POLICY.md
.\tools\navigator-upstream\navi.ps1 upstream account-context read --upstream-user-id <id> --file AGENT.md
.\tools\navigator-upstream\navi.ps1 upstream account-context read --upstream-user-id <id> --file MEMORY.md
.\tools\navigator-upstream\navi.ps1 upstream account-context write-policy --upstream-user-id <id> --from .\ACCOUNT_POLICY.md --expected-sha256 <sha256>
```

The CLI uses `X-Client-App-Key`, a runtime access token, and `X-Upstream-User-Id`; Navigator derives the account from `accounts/me` after checking the upstream user grant. The first implementation only writes `ACCOUNT_POLICY.md`. `AGENT.md` and `MEMORY.md` are read-only through this CLI until Navigator adds explicit audited write APIs.

`ACCOUNT_POLICY.md` is an upstream-controlled account context file, not hidden built-in model memory. BizWorker injects existing account context files into the Worker system prompt in this order: `ACCOUNT_POLICY.md > AGENT.md > MEMORY.md`. If delegated workspace file tools show these files, treat the injected Account Context block as already loaded; read the physical file only when the user asks to inspect/update account context or exact file text is required.

Never put token, secret, `task_scoped_token`, `adapterConfigJson`, or `manifestJson` into account context files.


## Update

Use the project-local updater:

```powershell
.\tools\navigator-upstream\navi.ps1 self update
```

The updater reads `NAVI_UPSTREAM_CLI_URL` first, then `tools/navigator-upstream/RELEASE_URL`. It preserves `.navigator/upstream.env`.

`version` prints package metadata when available: `buildTimeUtc`, `gitCommit`, `gitDirty`, `released`, `packageSha256`, and `buildId`. Do not use the semantic version alone to prove an upstream project refreshed; compare `packageSha256` or `buildId` from `latest.json` / `RELEASE_MANIFEST.json`.

For current packages, `self update` compares the installed `RELEASE_MANIFEST.json` SHA256 with remote `latest.json.sha256.windows`; if the version is the same but SHA differs, it refreshes the install.


## Troubleshooting

- `java not found in PATH`: install JDK 17+.
- `Profile path is not git-ignored`: ensure `.gitignore` contains `.navigator/upstream.env`.
- `No release URL configured`: reinstall through the remote installer or set `NAVI_UPSTREAM_CLI_URL`.
- `client app credential expired`: refresh the current upstream project's ClientApp runtime credential.
- `verify-agent-readiness` still asks for `NAVI_CLIENT_APP_ACCESS_TOKEN`: update the project-local CLI; issue #104 requires a build with runtime-token profile writeback and automatic runtime token exchange.
- `Agent not found`: confirm the explicit `--agent-code` or profile `NAVI_AGENT_CODE` points to a registered Navigator agent in the current environment. For `messages --task-id`, pass `--agent-code` explicitly; do not rely on profile fallback.
- `owner-smoke resources FAIL missing=effectiveDirectoryId`: create or bind a Navigator directory for the Agent, unless this Agent is intentionally workspace-free and you rerun with `--no-directory-required`.
- `verify-agent-readiness FAIL`: read the failed check code first; fix the matching registration, skill grant, upstream user grant, route skill mismatch, or model config grant before running `ask`.
- `SKILL_ARTIFACT_PATH_INVALID`: use a path returned by `skill tree`; do not use absolute paths, `..`, or backslashes.
- `Task not found` during message polling: verify the target agent is worker-backed and persists task/session messages; simple echo/test agents may not be sufficient for full polling evidence.
