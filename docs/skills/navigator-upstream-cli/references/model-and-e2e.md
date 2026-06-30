# Model And Deterministic E2E

This reference was split from the navigator-upstream-cli skill main file. Read it only when the current task matches the section title or routing guidance in SKILL.md.

## Model Grant Management

Use `upstream model` when the upstream project needs to inspect or maintain the current ClientApp's normal business model grants. These commands require `NAVI_CLIENT_APP_ID` plus `NAVI_CONTROL_API_KEY` or an approved `NAVI_ADMIN_API_KEY` with `MODEL_CONFIG_MANAGE`; they do not create tenant-wide model configs or change tenant defaults.

```powershell
.\tools\navigator-upstream\navi.ps1 upstream model grants
.\tools\navigator-upstream\navi.ps1 upstream model grant --model-config-id <modelConfigId> --set-default --write-profile
.\tools\navigator-upstream\navi.ps1 upstream model set-default --model-config-id <modelConfigId> --write-profile
.\tools\navigator-upstream\navi.ps1 upstream model set-default --grant-id <grantId>
```

When the upstream project owns its LLM key, create a ClientApp-owned model config through an environment variable instead of putting the key in command history:

```powershell
$env:NAVI_LLM_API_KEY="<llm-api-key>"
.\tools\navigator-upstream\navi.ps1 upstream model create `
  --name "<displayName>" `
  --model-base-url "https://llm.example/v1" `
  --model-name "<modelName>" `
  --provider openai `
  --api-key-env NAVI_LLM_API_KEY `
  --set-default `
  --write-profile

.\tools\navigator-upstream\navi.ps1 upstream model update `
  --model-config-id <modelConfigId> `
  --model-base-url "https://llm.example/v1" `
  --model-name "<modelName>" `
  --runtime-budget-preset generic.128k

$env:NAVI_LLM_API_KEY="<new-llm-api-key>"
.\tools\navigator-upstream\navi.ps1 upstream model rotate-key `
  --model-config-id <modelConfigId> `
  --api-key-env NAVI_LLM_API_KEY
```

Use `--model-base-url` for the upstream LLM/OpenAI-compatible endpoint; `--base-url` is still the Navigator service URL. `model create` creates a `LANGGRAPH_BIZ` model and binds it to the current ClientApp with `grantScope=CLIENT_APP_OWNED`; `model update` and `model rotate-key` only work for those ClientApp-owned grants, not shared admin-provisioned grants.

Use `--write-profile` only when the project-local, gitignored `.navigator/upstream.env` should be updated with `NAVI_MODEL_CONFIG_ID`. Do not use these commands for the deterministic E2E model lane; use `navi-e2e model ensure ...` for that.

The current `model create/update` CLI surface configures LangGraph Biz prompt-budget preset keys through backend `LlmModelConfig`. Use `--runtime-budget-preset` normally and `--runtime-budget-override-json` only for small exceptions. Do not encode `runtimeBudgetPresetKey`, `contextWindowTokens`, `maxInputTokens`, `maxOutputTokens`, compaction thresholds, or tool-result budgets in `clientContext`, user messages, or ad hoc env vars.


## Deterministic E2E

For upstream automated E2E, do not use real LLM behavior as the primary regression gate. Use Navigator's standard E2E Test Model and scripted response protocol when available; keep real LLM calls as a separate smoke lane.

Follow the standalone design and usage guide instead of embedding the full E2E procedure here:
`docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/27-e2e-scripted-test-model-design.md`

Recommended scripted cursor format:

```text
next:${e2eTraceId}:${turnIndex}
```

Rules:

- Put `e2eTraceId` and `next:${e2eTraceId}:001` in the first user message.
- Put the next cursor in each scripted tool-call argument or assistant content.
- Use zero-padded turn indexes such as `001`, `002`, `003`.
- Treat the E2E Test Model default as a ClientApp model grant default, not a tenant default model.

Use the separate project-local E2E wrapper when it is installed:

```powershell
.\tools\navigator-upstream\navi-e2e.ps1 config check
.\tools\navigator-upstream\navi-e2e.ps1 model ensure --standard biz-worker --set-default --write-profile
.\tools\navigator-upstream\navi-e2e.ps1 script register --file .\.navigator\e2e-script.json
.\tools\navigator-upstream\navi-e2e.ps1 debug requests --trace-id <e2eTraceId>
.\tools\navigator-upstream\navi-e2e.ps1 script cleanup --trace-id <e2eTraceId>
```

The E2E wrapper reads the same gitignored `.navigator/upstream.env`; `NAVI_E2E_MOCK_LLM_URL` defaults to `http://localhost:8200` and may be overridden with `--mock-url`. `model ensure` requires `NAVI_CLIENT_APP_ID` and `NAVI_CONTROL_API_KEY`; `NAVI_ADMIN_TOKEN` or `NAVI_ADMIN_API_KEY` are internal fallback only. It writes only `NAVI_MODEL_CONFIG_ID` when `--write-profile` is used. Keep install/update instructions in the standalone install/update guide:
`docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/19-navigator-upstream-cli-install-update.md`
