# Worker Setup Handoff

Use this reference when the handoff goal is: give an AI the Navigator Upstream CLI skill link, a Navigator login target, and scoped credentials so it can install Claude/Codex workers, verify them, and configure or update worker resources in Navigator.

## Handoff Package

The user-facing handoff should contain these fields:

```text
Navigator Upstream CLI skill: <reachable SKILL.md or skill package URL>
Navigator login URL: <https://navigator.example.com>
Navigator username: <username>
Navigator password: <deliver out of band>
Target tenant id: <tenantId>
Target upstream system id: <upstreamSystemId or namespace>
Target worker host id: <stableHostId>
Worker host URL: <http://host-or-ip-without-port>
Install shell: <powershell|bash|wsl>
Claude worker port: <port, default 3031>
Codex worker port: <port, default 3051>
Action: <apply|update>
Existing Claude worker id: <optional, required for update when profile has no NAVI_WORKER_ID>
```

Do not put real passwords, API keys, worker auth tokens, or claim tokens in tracked files, prompts, screenshots, or issue reports. Store secrets only in the gitignored `.navigator/upstream.env` profile or in process environment variables.

## Credential Path

`worker-host apply` and `worker-host update` use the upstream-admin credential lane. The effective profile must contain `NAVI_BASE_URL`, `NAVI_ADMIN_API_KEY`, and the target tenant/upstream identifiers.

If the handoff only provides a Navigator username/password, use `auth login` after installing Navigator Upstream CLI `1.0.19+`. It reads the password from an environment variable, calls Navigator login, and writes `NAVI_ADMIN_TOKEN` to `.navigator/upstream.env` without printing it:

```powershell
$env:NAVI_LOGIN_PASSWORD = "<provided out of band>"
.\tools\navigator-upstream\navi.ps1 upstream auth login `
  --base-url <navigatorBaseUrl> `
  --username <username> `
  --password-env NAVI_LOGIN_PASSWORD `
  --write-profile
Remove-Item Env:\NAVI_LOGIN_PASSWORD
```

Then use the login token to approve and claim a scoped upstream admin credential. `NAVI_ADMIN_TOKEN` is only for the bootstrap approval path; `worker-host apply` and `worker-host update` still require the claimed `NAVI_ADMIN_API_KEY`.

```powershell
.\tools\navigator-upstream\navi.ps1 upstream admin-key request `
  --base-url <navigatorBaseUrl> `
  --upstream-system-id <upstreamSystemId> `
  --requested-tenant-id <tenantId> `
  --multi-tenant `
  --reason "install and configure Claude/Codex workers" `
  --write-profile

.\tools\navigator-upstream\navi.ps1 upstream admin-key status
.\tools\navigator-upstream\navi.ps1 upstream admin-key approve `
  --request-code <requestCode> `
  --authorized-tenant-ids <tenantId> `
  --namespace <upstreamSystemId> `
  --scopes CLIENT_APP_MANAGE,CLIENT_APP_RUNTIME_KEY_ISSUE,CLIENT_APP_CONTROL_KEY_ISSUE,WORKER_MANAGE,WORKING_DIRECTORY_MANAGE,WORKER_POOL_MANAGE,MODEL_CONFIG_MANAGE,AGENT_BUNDLE_SYNC `
  --claim-ttl-minutes 60
.\tools\navigator-upstream\navi.ps1 upstream admin-key claim --write-profile
```

If the operator has already delivered `NAVI_ADMIN_API_KEY`, write it directly to `.navigator/upstream.env` and run `upstream config check` before provisioning. Do not print the value.

## Install CLI

From the upstream project root, install or update the Navigator Upstream CLI with the current published installer:

```powershell
irm https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/navigator-upstream-cli/install.ps1 | iex
```

Verify the project profile is gitignored before writing secrets:

```powershell
.\tools\navigator-upstream\navi.ps1 version
.\tools\navigator-upstream\navi.ps1 upstream config check
```

## Worker Manifest

Create `.navigator/worker-host.json` locally. Keep the file gitignored when it contains private hostnames, worker tokens, or environment variable names that should not be published.

```json
{
  "workerHostId": "<stableHostId>",
  "hostUrl": "http://127.0.0.1",
  "workers": {
    "claudeCode": {
      "enabled": true,
      "port": 3031,
      "name": "<stableHostId> Claude Code Worker",
      "authTokenEnv": "NAVI_CLAUDE_WORKER_TOKEN"
    },
    "codex": {
      "enabled": true,
      "port": 3051,
      "name": "<stableHostId> Codex Worker",
      "authTokenEnv": "NAVI_CLAUDE_WORKER_TOKEN",
      "model": "<optionalCodexModel>"
    }
  }
}
```

For Navi-routed Codex, do not set `workers.codex.workerId`. Codex is stored under the Claude worker resource as `claudeCode.codexConfig`.

## Install And Verify Runtimes

Prefer `worker-host install` because it uses the published OBS installers and applies the ports from the manifest.

```powershell
.\tools\navigator-upstream\navi.ps1 upstream worker-host install --file .navigator/worker-host.json --dry-run
.\tools\navigator-upstream\navi.ps1 upstream worker-host install --file .navigator/worker-host.json --install-shell powershell
```

For WSL:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream worker-host install --file .navigator/worker-host.json --install-shell wsl --wsl-distro Ubuntu
```

Manual installer fallback:

```bash
curl -sSL https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-worker/install.sh | bash
curl -sSL https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/codex-worker/install.sh | bash
```

```powershell
irm https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-worker/install.ps1 | iex
irm https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/codex-worker/install.ps1 | iex
```

Verify manifest normalization and local routing before writing Navigator resources:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream worker-host verify --file .navigator/worker-host.json
```

Also check each worker health endpoint from the machine that Navigator will call:

```powershell
Invoke-RestMethod http://127.0.0.1:3031/health
Invoke-RestMethod http://127.0.0.1:3051/health
```

## Configure Navigator

Create the worker resource when no existing worker id is known:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream worker-host apply `
  --file .navigator/worker-host.json `
  --target-tenant-id <tenantId> `
  --write-profile
```

Update the existing worker resource when a worker id is already known:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream worker-host update `
  --file .navigator/worker-host.json `
  --worker-id <existingClaudeWorkerId> `
  --write-profile
```

After configuration, run:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream worker health
.\tools\navigator-upstream\navi.ps1 upstream worker processes
```

Then continue with the normal readiness chain for the target ClientApp or Agent: issue runtime/control keys if needed, sync or update the Agent, bind default model/workspace/worker, run `verify-agent-readiness`, run `owner-smoke`, and finally run the intended live smoke.

## Handoff Result

Return only non-secret evidence:

```text
CLI version: <version>
Claude worker health: OK at <redactedBaseUrl>
Codex worker health: OK at <redactedBaseUrl>
Navigator action: <apply|update>
Worker host id: <workerHostId>
Claude worker id: <workerId>
Codex route: configured under claudeCode.codexConfig
Profile updated: .navigator/upstream.env
Readiness/owner-smoke: <OK or blocking reason>
```
