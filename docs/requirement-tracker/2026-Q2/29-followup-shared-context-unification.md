# 29 Follow-up - Shared Context Unification

## Date

- 2026-04-01

## Background

After landing `29-a2a-system-prompt-first-msg-semantics.md`, follow-up integration exposed several implementation-level gaps:

1. The shared ask flow wrote an extra pseudo context row into `agent_conversation_contexts` using the form `shared-nav:{sharingKeyId}:{contextId}` only to cache the Navigator session.
2. `firstMsg` could be injected repeatedly, causing duplicated `[Initial Message]` blocks in the final prompt.
3. Claude request dedup lived only inside the Claude inner A2A implementation, so provider behavior diverged and repeated prompts inside an existing conversation could be swallowed incorrectly.

This note records the follow-up adjustments that were made and the intended platform semantics.

## Decisions

### 1. `contextId` is the single source of truth

Shared ask no longer maintains any `shared-nav:*` pseudo context.

The unified rule is:

- The real `contextId` is the only conversation key.
- `contextAlias` is only a lookup mechanism that resolves to the real `contextId`.
- `navigatorSessionId` and `agentSessionRef` are both stored only on the real `AgentConversationContextEntity`.
- `agent_conversation_contexts` no longer serves as a shared-internal session cache.

### 2. Restoring by `contextId` must restore the full context

To support the rule above, recovery by `contextId` no longer reads only `agentSessionRef`.
It restores the full `AgentConversationContextEntity`, including at least:

- `agentSessionRef`
- `navigatorSessionId`
- `contextAlias`

This keeps shared ask, normal ask, and OpenAPI ask on the same recovery path.

### 3. `firstMsg` injection is idempotent

`prependFirstMsg(...)` now has idempotency protection:

- If the message text already starts with `[Initial Message]`, it is not prepended again.

### 4. First-turn detection uses `navigatorSessionId`

Whether a request is the first turn is determined by the presence of `navigatorSessionId`:

- No `navigatorSessionId`: treat as first turn.
- Existing `navigatorSessionId`: treat as a continued conversation.

`agentSessionRef` is no longer the source of truth for this decision.
This avoids repeated `firstMsg` injection when the provider session reference is temporarily absent but the Navigator session already exists.

### 5. Dedup no longer lives only in Claude

Request dedup is moved to the shared A2A decorator layer.

The new rule is:

- Dedup is only applied on first-turn requests.
- Once a request already belongs to an existing Navigator session, it is treated as a continuation and is not deduplicated.

This keeps platform behavior consistent across agents and prevents normal repeated prompts inside an existing conversation from being swallowed.

Claude still provides the implementation-specific hooks for:

- looking up a recent duplicate task
- recording the dedup key after task creation

But the decision of whether dedup should run is now made in the shared decorator.

### 6. Dedup hits must not erase existing context

In Claude dedup-hit scenarios, the returned task metadata may not contain a fresh `claudeSessionId` or `sessionId`.

The write-back rule is:

- If task metadata contains a new value, use it.
- If task metadata does not contain a new value, keep the existing `agentSessionRef` and `navigatorSessionId` from the restored context.

This prevents the stored context from being overwritten with null values and then being misclassified as first turn on the next request.

## Scope

Modules involved:

- `session-module`
- `navigator-spi`
- `addons/claude-worker-agent`
- `addons/codex-worker-agent`

Key classes involved:

- `SharedAskController`
- `ContextResolvingA2aAgent`
- `InnerA2aAgent`
- `AgentContextStore`
- `AgentContextStoreImpl`
- `ClaudeWorkerInnerA2aAgent`
- `ClaudeWorkerA2aAgentTest`
- `CodexWorkerA2aAgentTest`

## Expected Result

After these adjustments:

1. Shared ask no longer creates `shared-nav:*` rows.
2. One shared conversation keeps only one real `contextId` record in `agent_conversation_contexts`.
3. `contextId` can directly recover both `navigatorSessionId` and `agentSessionRef`.
4. `[Initial Message]` is not injected repeatedly.
5. Existing Navigator sessions are treated as continued conversations even if `agentSessionRef` is empty.
6. Dedup only protects first-turn idempotency and does not suppress normal repeated prompts during continuation.

## Follow-up

This document only records the implemented convergence and the latest dedup semantics.
Open items for later review:

- whether historical `shared-nav:*` rows should be cleaned up explicitly
- whether `firstMsg` should carry an explicit "already applied" marker in metadata
- whether dedup-hit tasks should always backfill more complete metadata
