---
name: navigator-ops
description: Route explicit Foggy Navigator operations for platform administration, scheduled AI tasks, Sharing Key lifecycle, and scheduled-task-only A2A calls. Use only when the user explicitly invokes $navigator-ops or clearly requests one of those Navigator operational workflows; do not use for ordinary coding, generic delegation, or local project analysis.
---

<!-- foggy-navigator-platform-skill:v1; name=navigator-ops -->

# Navigator Ops

Use this skill as a narrow router for Navigator operational workflows. Read only the reference that matches the confirmed request.

## Route the Request

- Platform users, Workers, working directories, LLM model configs, API credentials, or Git providers: read `references/platform-admin.md`.
- Create or maintain an AI cron/scheduled task, including Agent selection, prompt design, Sharing Key setup, context continuation, script generation, or report delivery: read `references/scheduled-task.md`.
- Create, inspect, update, disable, restore, or delete a Sharing Key outside the scheduled-task flow: read `references/sharing-key.md`.
- Execute a Navigator A2A call from an already prepared scheduled-task prompt: read `references/scheduled-a2a.md` only when the prompt contains `[NAVIGATOR_SCHEDULED_A2A]` and an exact `targetAgentId`.

If a request spans more than one branch, read them in dependency order. For example, a scheduled task may require `platform-admin.md`, then `sharing-key.md`, then `scheduled-task.md`.

## Global Safety Gate

- Confirm the requested external mutation before performing it when target, owner, scope, or destructive effect is ambiguous.
- Use Navigator APIs or supported CLIs; do not edit Navigator databases or credential files directly.
- Never invent or print tokens, passwords, API keys, Sharing Key plaintext, or full secret-bearing request headers.
- Prefer `$NAVIGATOR_API_BASE` and `$NAVIGATOR_TOKEN`; verify the environment before executing a workflow.
- Do not use the scheduled A2A branch for normal conversation, local code delegation, Codex/Claude native subagents, or fuzzy Agent-name routing.
- Record the target resource, action, and sanitized result. Do not claim success from an HTTP request that returned an error body.

## Stop Conditions

Stop and ask for direction when required ownership, target identifiers, environment, permissions, or destructive intent cannot be established safely. Do not widen the task into unrelated Navigator administration.
