---
title: Navigator upstream CLI model grant commands
version: 1.3.0-SNAPSHOT
status: completed
created: 2026-05-14
updated: 2026-05-14
owner: Navigator upstream CLI
---

# Navigator Upstream CLI Model Grant Commands

## Background

Each upstream `ClientApp` needs an allowed model config grant before `ask` can use a `modelConfigId`. Before this change, upstream projects could verify and use `NAVI_MODEL_CONFIG_ID`, and could ensure the standard deterministic E2E model through `navi-e2e`, but they could not maintain their own normal business model grants from the project-local CLI.

## Scope

Add `navi upstream model` commands backed by the existing ClientApp-scoped control-plane credential:

```powershell
navi upstream model grants
navi upstream model grant --model-config-id <modelConfigId> [--set-default] [--write-profile]
navi upstream model set-default --grant-id <grantId> [--write-profile]
navi upstream model set-default --model-config-id <modelConfigId> [--write-profile]
```

## Contract

- Commands use `NAVI_CONTROL_API_KEY` and are scoped to `NAVI_CLIENT_APP_ID`.
- `model grants` lists grant id, model config id, model name, backend, status, default flag, and scope.
- `model grant` grants an existing model config to the current ClientApp; `--set-default` asks the server to make it default.
- `model set-default --model-config-id` first resolves the ClientApp grant id, then sets that grant as default.
- `--write-profile` writes the final `NAVI_MODEL_CONFIG_ID` to gitignored `.navigator/upstream.env`.
- These commands do not create tenant-wide model configs and do not modify tenant default model settings.

## Verification

```powershell
mvn -q -pl navigator-open-sdk -Dtest=UpstreamCliTest test
```

## Progress

| Item | Status | Notes |
| --- | --- | --- |
| CLI dispatch | completed | `model grants`, `model grant`, `model set-default` |
| SDK reuse | completed | Reuses existing `BusinessAgentApi` model grant APIs |
| Profile writeback | completed | `--write-profile` updates `NAVI_MODEL_CONFIG_ID` |
| Tests | completed | `UpstreamCliTest` covers list, grant, and set-default |
