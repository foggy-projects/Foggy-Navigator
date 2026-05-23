# BUG: ClientApp public skill resource visible but invoke manifest missing

## Status

- Date: 2026-05-22
- Status: fixed in BizWorker runtime
- Scope: `invoke_business_skill` / ClientApp public skill manifest resolution

## Symptom

Observed session:

`tools/langgraph-biz-worker/data/runtime/sessions/by-date/2026/05/22/29/bctx_20260522_29_29ca8b235b2f43b79494d6c542f37e9e`

Evidence:

- `000003_conversation.root_...iter02...json` called `list_skill_resources` and returned the current ClientApp public skill list, including `tms-ticket-agent`.
- `000004_conversation.root_...iter01...json` did not replay the previous Root `list_skill_resources` tool protocol into the next ordinary root turn. This exposed a separate runtime-context design gap: Root-visible tool protocol should be retained in bounded context until compaction/cropping, while only child-private trace stays isolated.
- `000005_conversation.root_...iter02...json` called `invoke_business_skill({"skill_name":"tms-ticket-agent", ...})`.
- The tool result was `{"ok": false, "error": "Skill manifest not found: tms-ticket-agent"}`.
- The LLM then recovered by calling `list_skill_resources` and `read_skill_resource(SKILL.md)` and answered the user directly, so the UI appeared mostly normal even though the child skill frame was not opened.

## Root Cause

BizWorker had two different visibility paths:

1. Public skill resources were readable through `PublicSkillResourceTools` using the current `client_app_id`.
2. `invoke_business_skill` resolved executable child skills through the process-wide `SkillRegistry`.

When the registry did not have the current ClientApp app-public layer installed at the moment of tool execution, a skill could be advertised/read as a public resource but still fail as an executable manifest.

This is especially risky because the registry is process-wide and request-scoped ClientApp/account layers can be reloaded by other turns.

## Fix

Before resolving an executable skill manifest, BizWorker now installs the current request's skill layers from runtime context:

- `client_app_id` / `clientAppId` -> `load_client_app_public_skills(...)`
- `account_id` -> `load_account_skills(...)`

The same resolution is used for:

- initial child skill invocation
- recoverable child resume
- LLM child frame execution
- active focus / nested parent resume paths in root graph

## Regression Coverage

Added a unit regression test that starts with only `system.root` registered, places `tms-ticket-agent/SKILL.md` under a temp ClientApp public skill directory, and verifies that `invoke_business_skill` can lazily resolve and run the child frame from `runtime_context.client_app_id`.

## Design Note

This does not change the runtime-context message contract:

- completed root turns still promote user-facing assistant results into `ContextRuntimeMemory`
- Root frame tool call / tool result protocol must be retained in the Root visible protocol window until compaction/cropping
- child skill private details are not replayed into ordinary root turns unless the current turn is actively inside that frame or a recovery path requires protocol restoration
- child-private raw trace remains in `llm-submissions`, `runtime-message-events`, frame private messages, reports and audit logs
