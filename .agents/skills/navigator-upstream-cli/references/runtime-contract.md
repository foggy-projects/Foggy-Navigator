# Runtime Contract

This reference was split from the navigator-upstream-cli skill main file. Read it only when the current task matches the section title or routing guidance in SKILL.md.

## Current BizWorker Runtime Contract

For new BizWorker sessions, do not ask the upstream project to generate `contextId`. Omit `--context-id` on the first `ask`; Navigator / BizWorker returns a standard `bctx_yyyyMMdd_<hash>_<id>` context id. Store that returned value only for continuation, UI correlation, and diagnostics.

Reuse `--context-id` only when continuing an existing session that belongs to the same ClientApp and upstream user. Do not prefill a fixed context id in `.navigator/upstream.env`.

If a BizWorker task reaches terminal `FAILED` with a recoverable runtime interruption such as `failureStage=RUNTIME` and `LLM skill agent reached max iterations without valid submit`, continue by creating a new `ask` with the same `contextId` and a short message such as `继续` or `continue`. Do not reuse the old `taskId`, do not resend the full UI transcript, and do not add hidden skill routing metadata.

Example continuation command:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream ask `
  --agent-code <agentId> `
  --context-id <sameContextId> `
  --message "继续"
```

The upstream application owns the complete UI transcript. BizWorker owns only the bounded LLM runtime context: Root-visible user / assistant / tool protocol, compacted summary, retained tail, and frame focus / interruption state. Do not send full transcript, `recentConversation`, or prompt history through `clientContext`.

`clientContext` is session metadata only. It can carry upstream conversation ids, business object ids, trace ids, and similar correlation fields. It must not be used for system prompt overrides, model token budgets, workspace paths, Skill/Function private config, or LLM-visible execution settings.

For model selection, use explicit runtime fields instead of `clientContext`: `--model-config-id` selects the allowed `LlmConfigModel`, and `--model-variant` selects the concrete model name within that config. The normal recommendation is to encode stable choices in the Agent and call `ask` with only `agentId` and the user message.

For direct A2A `ask` against a Biz Agent, do not ask the upstream project to set hidden skill routing fields such as `businessSkillName` / `businessSkillId` in `clientContext`, metadata, or profile files. BizWorker exposes the loaded skill catalog (`id`, `name`, `description`) in the Root system prompt, and optional non-empty `allowed_skills` is only a filter over that catalog. If the smoke needs a specific actor/business skill, put that instruction in the user message itself, for example: `请使用 school-sim.actor.pm.m2.v1 技能，完成 School Sim M2 PM live ask smoke ...`. Then verify from messages/result artifacts that the requested actor skill was actually followed.

Skill and Agent are separate runtime concepts:

- Skill is current-frame material and tool capability. Calling a skill does not by itself open a child frame.
- Agent is the boundary that opens a child frame and has its own system prompt, message events, and recovery boundary.
- Agent results should be promoted back to the parent as digest / refs instead of expanding all child raw traces into the parent prompt.

Account workspace and account memory are resolved by BizWorker from the upstream user/account binding and injected into the Root system context when enabled. If an upstream system needs a delegated workspace root, configure it through the upstream-user/account binding API, not per-request `clientContext`.

When `BIZ_WORKER_LLM_SUBMISSION_LOG_ENABLED=true`, real ChatModel request bodies are saved under the session directory `logs/llm-submissions/`. Treat these files as Worker diagnostics, not as the public CLI replay contract.

For development-only BizWorker diagnostics, use the CLI to resolve the local session directory from the returned BizWorker `contextId` instead of recursively searching `runtime/sessions`:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream diagnostics session-dir --context-id <contextId>
.\tools\navigator-upstream\navi.ps1 upstream diagnostics session-dir --context-id <contextId> --task-id <taskId>
```

If the upstream project uses a non-standard BizWorker data root, pass `--data-root <bizWorkerDataRoot>` or set `NAVI_BIZ_WORKER_DATA_ROOT`. The command returns `sessionDirectory`, `logsDirectory`, `skillToolCallsDirectory`, `skillToolCallsFile` when `--task-id` is supplied, `runtimeMessageEventsDirectory`, `runtimeMessageEventsFile` when `--task-id` is supplied, `llmSubmissionsDirectory`, worker identity hints, `accessHint`, and `notFoundReason`. It may return local absolute paths for dev/test diagnosis, but it must not print tokens, HTTP headers, adapter config, runtime credentials, or log contents.

For TMS real-Agent E2E, call this command after BFF `ask` returns `contextId` and `taskId`, then read `skillToolCallsFile` to assert the actual BizWorker tool calls and DSL payloads. Treat `exists=false` with `context-not-found`, `session-dir-not-found`, `worker-unavailable`, or `cleaned` as explicit diagnosis rather than falling back to blind recursive search. If `accessHint=ssh-required`, use the returned worker host and path for remote inspection.

Navigator model config now exposes first-class LangGraph Biz runtime budget preset fields. Use CLI `upstream model create/update --runtime-budget-preset <key>` for normal cases, and `--runtime-budget-override-json <json>` only for small exceptions. Do not put token budgets in `clientContext` or user messages.

Prefer preset-based configuration over manual token numbers. The expected model is: auto-match by `workerBackend` / provider / `modelName`, allow an optional `runtimeBudgetPresetKey` such as `generic.128k`, and use a small override JSON only for exceptions. Codex / Claude Code / Gemini native workers normally keep their own context management; Navigator budget presets mainly govern LangGraph Biz or third-party model proxy cases.

LangGraph BizWorker `command` is a worker-side Linux-only capability. `BIZ_WORKER_ENABLE_COMMAND=true` means the worker may expose the real command tool when all runtime gates also pass: Linux host, non-read-only delegated workspace, valid `workdir`, and `workdir` inside `allowed_dirs`. Set `BIZ_WORKER_ENABLE_COMMAND=false` in the BizWorker process environment or its `.env` file to disable command globally for that worker.

`BIZ_WORKER_ENABLE_COMMAND` is not a per-task Java/Navigator switch and should not be placed in `clientContext`, model config env vars, or user messages. Upstream systems control task-level command access by choosing whether to delegate a writable workspace: omit `workdir`, mark the workspace read-only, or restrict `allowed_dirs` to prevent command exposure. `allowed_tools` does not need to include `command`; business-side users that only need file tools may still set `allowed_tools` to file IO entries such as `read_file`, while a strongly controlled upstream can enable a writable delegated workspace and rely on the worker kill switch plus workspace gates.

Before asking an upstream project to run a live `ask`, prefer `upstream owner-smoke`. It is the current owner-aware release gate: it checks the gitignored profile, auto-exchanges the ClientApp runtime token, runs Agent readiness, and verifies that the runtime resolves an Agent, Model, backend capability, PhysicalWorker, and Workspace. Only use `--no-directory-required` when the target Agent is intentionally workspace-free.

Navigator Upstream CLI `1.0.8+` prints non-secret PhysicalWorker diagnostics in `owner-smoke` / `verify-agent-readiness`: `physicalWorkerId`, `workerName`, `workerBackend`, scrubbed `baseUrl`, `status`, `healthStatus`, `version`, `hostname`, `lastHeartbeat`, `source`, and `usedAs=execution,directory`. Use this output to map the resolved runtime to WSL Codex, WSL Biz, Windows worker, or another registered worker without requiring `NAVI_ADMIN_API_KEY`. Never ask upstream users to paste tokens, auth headers, API keys, provider credentials, or raw worker auth config while troubleshooting this line.
