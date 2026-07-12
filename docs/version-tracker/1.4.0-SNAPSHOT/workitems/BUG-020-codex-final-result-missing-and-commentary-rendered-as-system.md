---
type: bug
bug_source: user-report
version: 1.4.0-SNAPSHOT
ticket: BUG-020
severity: major
status: ready-for-verification
reproduction_status: confirmed
test_strategy: unit-test
automation_decision: required
owner: codex-worker-session-frontend
---

# Codex final result missing and commentary rendered as system

## Background

After the GPT-5.6 SDK lifecycle update, progress `agent_message` events are emitted as
`assistant_text/commentary`, while only the last message is carried by the terminal `result` event.

## Reproduction

1. Run a Codex SDK task that emits commentary, tool calls, and a final result.
2. Let the task complete successfully.
3. Observe that commentary is rendered as yellow system output and the final answer is absent.

## Expected vs Actual

- Expected: commentary is shown as Agent progress and the terminal result is shown as the final Agent reply.
- Actual: commentary is classified as system output; `SESSION_END/isResult` is discarded because the platform assumes an earlier final `assistant_text` exists.

## Impact Scope

- Codex SDK Worker conversations using the new final-only result lifecycle.
- Live SSE rendering and persisted/reloaded session history.

## Test Strategy

- Session listener unit tests for terminal-result persistence and duplicate suppression.
- Foggy Chat state tests for commentary role, final-only result rendering, and duplicate suppression.

## Code Inventory

- `session-module/.../SessionEventListener.java`
- `packages/foggy-chat-core/src/store/chatState.ts`
- Associated Java and TypeScript unit tests.

## Fix Checklist

- [x] Persist non-empty terminal results when no identical final assistant message exists.
- [x] Handle `SESSION_END` in chat state.
- [x] Render final-only result as an assistant reply.
- [x] Keep duplicate suppression when final assistant text was already emitted.
- [x] Render Codex commentary as assistant progress instead of system output.
- [x] Recover final output from completed task records for already affected sessions.
- [x] Run targeted Java and frontend tests.

## Verification

- `SessionEventListenerTest`: 8 passed.
- `chatState.test.ts`: 48 passed.
- `useTaskPaneNativeSubtasks.test.ts`: 17 passed, including legacy result recovery on initial load and task refresh.
- Navigator frontend TypeScript check passed.
- Production Vite build is environment-blocked because this host has Node.js 18.19.1 while Vite 7 requires Node.js 20.19+ or 22.12+.
