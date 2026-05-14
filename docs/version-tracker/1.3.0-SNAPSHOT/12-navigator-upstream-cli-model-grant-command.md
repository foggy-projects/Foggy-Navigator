---
title: Navigator upstream CLI model commands
version: 1.3.0-SNAPSHOT
status: completed
created: 2026-05-14
updated: 2026-05-14
owner: Navigator upstream CLI
---

# Navigator Upstream CLI Model Commands

## Background

Each upstream `ClientApp` needs an allowed model config grant before `ask` can use a `modelConfigId`. Some upstream projects also own their LLM key and need a self-service path to create and rotate a model config without receiving tenant-wide admin credentials.

## Scope

Add `navi upstream model` commands backed by the existing ClientApp-scoped control-plane credential:

```powershell
navi upstream model grants
navi upstream model grant --model-config-id <modelConfigId> [--set-default] [--write-profile]
navi upstream model set-default --grant-id <grantId> [--write-profile]
navi upstream model set-default --model-config-id <modelConfigId> [--write-profile]
navi upstream model create --name <name> --model-base-url <url> --model-name <model> --api-key-env <envName> [--provider openai] [--set-default] [--write-profile]
navi upstream model update --model-config-id <modelConfigId> [--name <name>] [--model-base-url <url>] [--model-name <model>] [--provider openai] [--set-default] [--write-profile]
navi upstream model rotate-key --model-config-id <modelConfigId> --api-key-env <envName>
```

## Contract

- Commands use `NAVI_CONTROL_API_KEY` and are scoped to `NAVI_CLIENT_APP_ID`.
- `model grants` lists grant id, model config id, model name, backend, status, default flag, and scope.
- `model grant` grants an existing model config to the current ClientApp; `--set-default` asks the server to make it default.
- `model set-default --model-config-id` first resolves the ClientApp grant id, then sets that grant as default.
- `model create` creates a `LANGGRAPH_BIZ` model config in the current tenant, immediately grants it to the current ClientApp with `grantScope=CLIENT_APP_OWNED`, and never changes the tenant default model.
- `model update` and `model rotate-key` only work on models granted to the current ClientApp with `grantScope=CLIENT_APP_OWNED`; shared/admin-provisioned grants cannot be modified by the upstream project.
- `--api-key-env` reads the LLM key from an environment variable and prevents the key from appearing in the command line or CLI output.
- `--model-base-url` is the upstream LLM/OpenAI-compatible base URL; `--base-url` remains the Navigator service URL.
- `--write-profile` writes the final `NAVI_MODEL_CONFIG_ID` to gitignored `.navigator/upstream.env`.
- These commands do not grant tenant-wide model administration.

## Verification

```powershell
mvn -q -pl navigator-open-sdk -Dtest=UpstreamCliTest test
```

## Progress

| Item | Status | Notes |
| --- | --- | --- |
| CLI dispatch | completed | `model grants`, `model grant`, `model set-default`, `model create`, `model update`, `model rotate-key` |
| SDK reuse | completed | `BusinessAgentApi` model grant + ClientApp-owned model APIs |
| Backend control API | completed | `/api/v1/client-apps/{clientAppId}/model-configs` |
| Profile writeback | completed | `--write-profile` updates `NAVI_MODEL_CONFIG_ID` |
| Tests | completed | CLI + service boundary tests |
