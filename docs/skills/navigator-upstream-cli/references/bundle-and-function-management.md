# Bundle And Function Management

This reference was split from the navigator-upstream-cli skill main file. Read it only when the current task matches the section title or routing guidance in SKILL.md.

## Agent Bundle Sync

Use `agent sync` when the upstream project owns a Business Agent Bundle manifest and needs to register/update its callable Navi agent plus the matching public skill grant.

Agent bundle sync requires `NAVI_CLIENT_APP_ID` plus `NAVI_CONTROL_API_KEY` or an approved `NAVI_ADMIN_API_KEY` with `AGENT_BUNDLE_SYNC`:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream agent sync `
  --manifest .navigator/agent-bundle.json
```

The manifest may contain `agentId` or `agentCode`, `skillId`, `name`, `description`, `status`, `workerId`, `defaultModelConfigId`, `defaultModel`, `markdownBody`, `resources`, `functions`, and `materialize`. If `clientAppId` is omitted, the CLI fills it from `NAVI_CLIENT_APP_ID`; Navi still enforces that it matches the `NAVI_CONTROL_API_KEY` binding.

`functions` is an allowlist reference, not a full Function Manifest. Before `agent sync`, the upstream project must import its Business Function manifests and grant them to the current ClientApp with `upstream function import` and `upstream function grant`. Use the same ClientApp-scoped control credential; do not use tenant-level admin credentials.

If the agent skill `markdownBody` or Markdown resources contain `${@schema.<functionId>}`, that function must be included in `functions`. Navi resolves the placeholder during sync/materialize after verifying the function is imported and granted to the current ClientApp.

Keep the full design in the standalone agent bundle guide:
`docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/23-business-agent-bundle-registration-design.md`


## Skill Bundle Sync

Use `skill sync` when the upstream project owns a Skill Bundle manifest and needs to publish it through Navi.

ClientApp public skill sync requires `NAVI_CONTROL_API_KEY` plus `NAVI_CLIENT_APP_ID`; admin token/API key are internal fallback only:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream skill sync `
  --scope client-app-public `
  --manifest .navigator/skill-bundle.json
```

Account private skill sync uses runtime credential and is constrained to the current upstream user:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream skill sync `
  --scope account-private `
  --manifest .navigator/account-skill.json `
  --upstream-user-id <id>
```

The manifest may contain `skillId`, `name`, `description`, `markdownBody`, `resources`, `functions`, `status`, and `materialize`. Public manifests may also contain `clientAppId`; account-private manifests must not rely on a supplied `accountId` because Navi derives it from `X-Upstream-User-Id`.

### Schema Placeholders In Skill Markdown

Public skill and agent bundle `markdownBody` may include:

```md
${@schema.<functionId>}
```

Markdown resources under `references/**` or `assets/**` may use the same placeholder. Navi resolves it during sync/materialize into a sanitized public BusinessFunction contract.

- The function must be imported, granted to the current ClientApp, and listed in the bundle `functions` allowlist before sync/materialize.
- Put critical first-call contracts in `SKILL.md`, not only in `references/**`, because resources are read on demand by the LLM.
- Keep business routing, intent rules, and examples in normal Markdown. Use `${@schema.<functionId>}` only for the function contract block.
- Do not put placeholders in `description` or frontmatter description.
- Do not paste raw `manifestJson`, `adapterConfigJson`, internal URLs, auth headers, tokens, or private business data into skill Markdown, resources, prompts, issues, or logs.
- Example for TMS order opening: use `${@schema.tms.order.createOpeningDraft}` and keep `OPEN_EMPTY` versus `PREFILL_FROM_CLUES` rules as prose.

Keep installation and update instructions in the standalone install/update guide:
`docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/19-navigator-upstream-cli-install-update.md`


## Business Function Management

Use `upstream function` when an upstream project owns Business Function manifests and needs to prepare functions before `skill sync` or `agent sync`.

These commands require `NAVI_CONTROL_API_KEY` and `NAVI_CLIENT_APP_ID`; they do not require tenant-level admin credentials:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream function import `
  --manifest .navigator/function-manifest.json

.\tools\navigator-upstream\navi.ps1 upstream function grant `
  --function-id <functionId> `
  --version <version>

.\tools\navigator-upstream\navi.ps1 upstream function visible

.\tools\navigator-upstream\navi.ps1 upstream function grant-status `
  --grant-id <grantId> `
  --status DISABLED
```

`function import` reads an `ImportBusinessFunctionManifestForm` JSON file. Do not paste `adapterConfigJson`, `manifestJson`, internal URLs, tokens, or private business data into prompts, issues, or logs. `function grant` binds an already imported function to the current ClientApp. `function visible` lists the functions visible to the current ClientApp after grants.

Before handing off an upstream package that depends on Business Function commands, verify the OBS-installed CLI, not only the local source tree:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream --help
.\tools\navigator-upstream\navi.ps1 upstream function --help
```


## ClientApp Control Credential

Use `NAVI_CONTROL_API_KEY` for upstream-owned control-plane setup such as public skill sync, agent bundle sync, upstream user grants, model grant management, and E2E model ensure. This is scoped to one `NAVI_CLIENT_APP_ID`; Navi rejects attempts to operate another ClientApp.

`ensure-grant` only requires `NAVI_CONTROL_API_KEY` and the target upstream user id. `NAVI_UPSTREAM_USER_TOKEN` is optional and should be set only when the Worker needs to call back into the upstream system as that user. `TMS_STAFF_SESSION_TOKEN` is a legacy TMS sandbox alias for `NAVI_UPSTREAM_USER_TOKEN`.

Follow the standalone delivery guide instead of embedding all details here:
`docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/28-client-app-control-credential-delivery.md`

Do not ask upstream projects to use Navigator tenant admin credentials for normal delivery. Use `NAVI_CONTROL_API_KEY` for single-ClientApp control-plane work, and use `NAVI_ADMIN_API_KEY` only for the approved multi-tenant ClientApp bootstrap scope described above.
