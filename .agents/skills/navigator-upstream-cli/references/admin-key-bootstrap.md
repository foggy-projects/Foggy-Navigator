# Admin Key Bootstrap

This reference was split from the navigator-upstream-cli skill main file. Read it only when the current task matches the section title or routing guidance in SKILL.md.

## Upstream Admin Key Bootstrap

For multi-tenant upstream systems, use `upstream admin-key` to request and claim a system-scoped ClientApp admin credential. These commands require Navigator Upstream CLI `1.0.4+`.

Upstream-side flow, from the upstream project root:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream admin-key request `
  --base-url <navigatorBaseUrl> `
  --upstream-system-id <systemId> `
  --requested-tenant-id <navigatorTenantId> `
  --multi-tenant `
  --reason "bootstrap upstream tenant ClientApps" `
  --write-profile

.\tools\navigator-upstream\navi.ps1 upstream admin-key status

.\tools\navigator-upstream\navi.ps1 upstream admin-key claim --write-profile
```

`request` writes the one-time claim token to the gitignored project profile and does not print the final key. `claim --write-profile` writes `NAVI_ADMIN_API_KEY` to the same profile only after a Navigator operator/admin has approved the request.

Navigator-side approval is separate and must not run from the upstream project. It requires `NAVI_OPERATOR_API_KEY` in a Navigator admin/ops environment:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream admin-key list --status PENDING
.\tools\navigator-upstream\navi.ps1 upstream admin-key approve `
  --request-code <requestCode> `
  --authorized-tenant-ids <tenantId> `
  --namespace <systemId> `
  --scopes CLIENT_APP_MANAGE,CLIENT_APP_RUNTIME_KEY_ISSUE,CLIENT_APP_CONTROL_KEY_ISSUE,WORKER_MANAGE,WORKING_DIRECTORY_MANAGE,WORKER_POOL_MANAGE,MODEL_CONFIG_MANAGE,AGENT_BUNDLE_SYNC `
  --claim-ttl-minutes 60
```

When the scope list is omitted on current Navigator builds, the default approval includes the programming-project orchestration scopes above. Passing the explicit list is still clearer in handoffs.

`--claim-ttl-minutes` is primarily the approved request claim window. Current Navigator builds also accept `--claim-ttl-minutes 0` or `--claim-ttl-minutes -1` as an explicit operator/admin approval for a no-expiry `NAVI_ADMIN_API_KEY`; the claim token still uses the default claim window and must be claimed promptly. Do not tell upstream projects they can self-approve this: no-expiry admin keys still require Navigator-side `NAVI_OPERATOR_API_KEY` or `NAVI_ADMIN_TOKEN`.

After claim, a multi-tenant upstream can create or reuse tenant ClientApps and write each tenant's runtime credential and `NAVI_CONTROL_API_KEY` to its own gitignored profile. `issue-runtime-key` / `issue-runtime-credential` requires `navigator-upstream-cli` / `navigator-open-sdk` `1.0.6` or newer:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream client-app ensure `
  --target-tenant-id <navigatorTenantId> `
  --upstream-ref <upstreamTenantCode> `
  --name "<displayName>" `
  --tenant-profile .navigator/tenants/<upstreamTenantCode>.env `
  --write-profile

.\tools\navigator-upstream\navi.ps1 upstream client-app issue-runtime-key `
  --client-app-id <clientAppId> `
  --tenant-profile .navigator/tenants/<upstreamTenantCode>.env `
  --write-profile

.\tools\navigator-upstream\navi.ps1 upstream client-app issue-control-key `
  --client-app-id <clientAppId> `
  --tenant-profile .navigator/tenants/<upstreamTenantCode>.env `
  --write-profile
```

Use `issue-runtime-key` before `runtime-token --write-profile`. It uses `NAVI_ADMIN_API_KEY`, writes full `NAVI_CLIENT_APP_KEY` / `NAVI_CLIENT_APP_SECRET` only to the gitignored tenant profile, clears stale `NAVI_CLIENT_APP_ACCESS_TOKEN`, and prints only masked app key plus sha256 digests. Use `--rotate-runtime-credential` or rerun the command when a smoke rebuild needs a fresh ClientApp runtime credential.

Compatibility note: Navigator 1.0.6+ accepts legacy upstream admin credentials that have `CLIENT_APP_MANAGE` but were issued before `CLIENT_APP_RUNTIME_KEY_ISSUE` existed. New admin key approvals should still explicitly include `CLIENT_APP_RUNTIME_KEY_ISSUE`.

Upgrade note: project-local reinstall/update should not require manually deleting old SDK jars. Current installers clean stale `navigator-open-sdk-*.jar`, and the wrapper chooses one SDK jar matching `VERSION` instead of loading every SDK jar in `lib`.

When Navigator itself exposes the idempotent upstream tenant provisioning API, prefer the aggregate command for TMS-style onboarding because it creates/reuses the ClientApp and writes the runtime credential, control key, root agent, model, skill, and workspace/backend policy in one gitignored tenant profile:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream client-app ensure-tenant `
  --source-system TMS `
  --source-tenant-id 3 `
  --name "TMS tenant 3" `
  --capability-domain tms.ops `
  --model-config-id <modelConfigId> `
  --skill-id <skillId> `
  --tenant-profile .navigator/tenants/tms-3.env `
  --write-profile
```

`ensure-tenant` uses the upstream system-scoped `NAVI_ADMIN_API_KEY`. It calls `POST /api/v1/admin/upstream-tenants/client-apps/ensure` and refuses to run unless the target profile is gitignored, so one-time `NAVI_CLIENT_APP_SECRET` and `NAVI_CONTROL_API_KEY` are not lost or printed. Use `--rotate-credentials` only when intentionally reissuing tenant credentials.

For non-multi-tenant upstream systems, Navigator may skip `NAVI_ADMIN_API_KEY` and directly deliver a single ClientApp profile containing `NAVI_CONTROL_API_KEY`.
