---
name: navigator-runtime-provisioning
description: Navigator upstream runtime provisioning and review workflow. Use when provisioning or auditing ClientApp runtime resources, worker-host apply/verify, Biz Worker identity visibility, modelConfig grants, Agent sync/bindings, working directories, readiness, owner-smoke, live smoke, credential lane separation, or SIM/TMS-style upstream isolation.
---

# Navigator Runtime Provisioning

Use this skill to complete or review Navigator upstream runtime provisioning without leaking credentials or crossing upstream boundaries.

## Safety Rules

- Never read `accounts/` for provisioning smoke.
- Never print, commit, or document real admin keys, control keys, runtime keys, LLM API keys, claim tokens, cookies, real accounts, or passwords.
- Keep real credentials only in gitignored profiles such as `.navigator/upstream.env`, `.navigator/*.env`, or `.navigator/tenants/*.env`, or in a platform secret store.
- Use tracked example files only for variable names and placeholders.
- Do not dispatch a first real business/UI inspection task until readiness, owner-smoke, and the intended live smoke pass.
- Do not access real TMS during local TMS runtime smoke unless the user explicitly changes the boundary.

## Credential Lanes

Use the narrowest lane that can perform the action:

| Lane | Profile field | Use for | Do not use for |
| --- | --- | --- | --- |
| Upstream admin | `NAVI_ADMIN_API_KEY` | ClientApp bootstrap, issue control/runtime keys, upstream-system modelConfig, worker-host apply/update, Biz Worker identity, worker pools, upstream shared directories | ask, owner-smoke, live smoke |
| ClientApp control | `NAVI_CONTROL_API_KEY` | ClientApp-owned modelConfig, grants/defaults, Agent sync, model/workspace/worker bindings, ClientApp directories, upstream user grants | cross-ClientApp or upstream-system repair |
| Runtime credential | ClientApp key-secret / access token | runtime-token exchange, readiness, owner-smoke, ask, messages, live smoke | modelConfig create, grant, binding, worker-host apply |
| Break-glass admin | operator/admin env only | cross-owner repair, migration, emergency revocation | normal provisioning |

## Standard Closure Order

1. Confirm target upstream system, tenant, ClientApp, agentCode, upstreamUserId, modelConfigId, directoryId, and boundary constraints.
2. Verify profile files are gitignored before writing credentials.
3. Run or review `worker-host verify` on the local manifest.
4. Run `worker-host apply --write-profile` only with an upstream-admin lane.
5. Create or update modelConfig through the correct lane; use an actually supported `modelName`.
6. Sync/register the Agent under the current ClientApp or upstream owner.
7. Bind default model, default workspace, and worker where required.
8. Ensure upstream user grant.
9. Run `verify-agent-readiness` with explicit `--agent-code`, `--upstream-user-id`, `--model-config-id`, and `--directory-id`.
10. Run `owner-smoke`.
11. Run the intended live smoke prompt.
12. Run cross-upstream negative smoke when credentials for more than one upstream are present.
13. Backfill runtime profiles, rehearsal records, version docs, and test records with IDs and outcomes only.
14. Scan changed tracked files for secret-like values before committing.

## Troubleshooting Checks

For readiness or owner-smoke failures, check these in order:

- `MODEL_CONFIG_GRANT`: modelConfig tenant / owner mismatch, missing grant, or Agent default model variant not allowed.
- `WORKER_HOST_ROLE_ROUTING`: Biz worker should resolve from `BIZ_WORKER_IDENTITY`, not a workspace fallback.
- `WORKSPACE_RESOURCE`: directory owner/tenant/ClientApp mismatch, disabled directory, or wrong workspace scope.
- `FILE_TOOL_ROOT_ALIGNMENT`: Biz worker file root must align with the effective directory.
- `BUSINESS_FUNCTION_UPSTREAM_ROUTE`: use a local mock route for local smoke when real upstream access is out of scope.
- ClientApp `upstream_system_id`: missing values can make upstream-owned Biz Worker identity invisible in local dev data.

## Documentation Targets

- Runtime profile: `docs/scopes/<scope>/runtime/actor-runtime-profiles.yml`
- Rehearsal record: `docs/scopes/<scope>/rehearsals/<actor-or-role>-navi-provisioning-run-<date>.md`
- Version workitem: `docs/version-tracker/<version>/workitems/`
- Test record: `docs/version-tracker/<version>/test-records/`
- SOP/runbook: `docs/version-tracker/<version>/runbooks/`
- Example profiles: `tools/navigator-upstream/fixtures/**.example.env`

## Local References

- Current provisioning workitem: `docs/version-tracker/1.3.3-SNAPSHOT/workitems/OPT-001-dev-operator-key-provisioning-boundary.md`
- Production/development SOP: `docs/version-tracker/1.3.3-SNAPSHOT/runbooks/navigator-runtime-provisioning-sop.md`
- Selftest script: `tools/navigator-upstream/scripts/navigator-provisioning-selftest.ps1`
- Example profiles: `tools/navigator-upstream/fixtures/provisioning-selftest/`
