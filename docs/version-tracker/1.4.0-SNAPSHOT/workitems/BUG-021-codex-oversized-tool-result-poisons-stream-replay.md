---
type: bug
bug_source: user-report
version: 1.4.0-SNAPSHOT
ticket: BUG-021
severity: major
status: ready-for-verification
reproduction_status: confirmed
test_strategy: unit-test
automation_decision: required
owner: codex-worker-agent
---

# Codex oversized tool result poisons durable stream replay

## Background

Long-running Codex SDK tasks can produce a single `tool_result` whose output is larger than the
MySQL `TEXT` capacity used by `session_messages.metadata`. The Worker and Codex CLI keep running,
but the Java relay requires durable persistence before advancing the event acknowledgement cursor.

## Reproduction

1. Run a Codex SDK task whose shell command emits a large result, such as Maven output exceeding
   65 KiB after JSON serialization.
2. Let the Worker persist and stream the `tool_result` event.
3. Observe Java fail to persist the session message and close the SSE subscription.
4. Observe reconnect replay the same unacknowledged sequence and fail again while the Worker task
   and Codex CLI process remain active.

Confirmed production examples included tool-result outputs of 79,813 bytes and approximately
1,048,535 bytes. Direct Worker replay and 15-second keepalive were healthy.

## Expected vs Actual

- Expected: the complete Worker event remains available in the Worker event log; the platform
  persists a bounded display copy, acknowledges the sequence, and continues processing later events.
- Actual: the oversized event cannot fit in `session_messages.metadata`, is never acknowledged, and
  becomes a poison event on every reconnect. The UI eventually reports
  `CODEX_WORKER_STREAM_DISCONNECTED` even though execution continues.

## Impact Scope

- Codex SDK and App Server streams routed through `CodexStreamRelay`.
- Tasks with large command or tool output.
- Final replies and terminal state behind the poison event are not delivered to the UI.

## Test Strategy

- Add a Java relay unit test using escaping and multi-byte output well above the database limit.
- Simulate the durable persistence size boundary.
- Verify the persisted tool result is explicitly marked as truncated and remains below a conservative
  metadata byte budget.
- Verify the event sequence is acknowledged only after durable persistence succeeds.
- Keep the existing persistence-failure regression proving genuine database failures still stop ACK.

## Code Inventory

- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexStreamRelay.java`
- `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexStreamRelayTest.java`
- `session-module/src/main/java/com/foggy/navigator/session/event/SessionEventListener.java`
- `navigator-common/src/main/java/com/foggy/navigator/common/entity/SessionMessageEntity.java`

## Fix Checklist

- [x] Confirm Worker and CLI remain running after the frontend disconnect.
- [x] Confirm direct Worker replay and heartbeat are healthy.
- [x] Identify oversized unacknowledged `tool_result` events in both affected task logs.
- [x] Add the failing size-bound regression test.
- [x] Bound the durable/display copy while preserving the full Worker event log.
- [x] Persist truncation metadata and advance ACK after successful persistence.
- [x] Run targeted relay tests and repository checks.
- [x] Commit and push the Java backend fix.

## Verification

- Before the fix, the 3.68 MB regression event raised the simulated metadata overflow and terminated
  event processing before ACK.
- After the fix, the same event persisted a 49,152-byte metadata document containing both the head
  and tail of the output, `dataTruncated=true`, the original UTF-8 byte count, and an explicit
  truncation reason. The sequence was acknowledged after persistence.
- `CodexStreamRelayTest`: 35 tests passed, including the existing assertion that a genuine durable
  persistence failure terminates the stream without advancing higher sequences.
- Targeted oversized-result test passed again after final code cleanup.
- `git diff --check` passed.
- Current live evidence at 16:37 on July 12, 2026: one affected Worker task had already completed at
  event sequence 121 while its Java/UI stream remained behind; the other Worker task was still
  running and had advanced to sequence 386. This confirms execution data remains recoverable.
- Live verification after Java deployment: affected tasks must replay beyond their previous poison
  sequence and converge to the Worker terminal state without updating or restarting the Worker.
- Release scope: Java platform only. No Codex SDK Worker or App Server Worker package change is
  required for this fix.

## References

- [BUG-017 Codex SDK terminal message and stream lifecycle](./BUG-017-codex-sdk-terminal-message-and-stream-lifecycle.md)
- [BUG-020 Codex final result missing and commentary rendered as system](./BUG-020-codex-final-result-missing-and-commentary-rendered-as-system.md)
