---
type: bug
bug_source: acceptance-found
version: 1.4.0-SNAPSHOT
ticket: BUG-007
severity: major
status: closed-isolated
reproduction_status: confirmed
test_strategy: integration-test
automation_decision: required
owner: codex-app-server-worker | session-module
---

# App-server 最终结果混入过程消息

## Background

最终 Ultra 实链中，原生子任务、文件副作用、SSE 和隐私检查全部通过，但任务要求精确回复 `FINAL_STREAM_OK` 时，`task.resultText` 实际为过程消息与最后答复的拼接。Session 历史还额外持久化了一条 `isResult=true` 的 `SESSION_END` 汇总消息。

## Expected vs Actual

- Expected: 过程性 agent message 可独立展示，任务 result 只取最后一条 canonical agent message。
- Actual: bridge 累加每个 completed agent message，result 变成过程文本与最终文本拼接。
- Expected: result 事件只携带终态和 metrics，不重复写聊天历史。
- Actual: `SESSION_END` result 被 Session listener 持久化为额外 assistant message。

## Test Strategy

- Worker integration：先 completed 过程消息、再 completed 最终消息，事件均保留，但 `getResult().assistantText` 必须只等于最终消息。
- Session unit：`SESSION_END + isResult=true` 必须 SSE 推送但不持久化。
- Real E2E：新 Ultra 任务 `resultText=FINAL_STREAM_OK`，刷新后无 delta 碎片或重复 result 历史。

## Code Inventory

- `tools/codex-app-server-worker/src/app-server/event-bridge.ts`
- `tools/codex-app-server-worker/tests/native-subtask.test.ts`
- `session-module/src/main/java/com/foggy/navigator/session/event/SessionEventListener.java`
- `session-module/src/test/java/com/foggy/navigator/session/event/SessionEventListenerTest.java`

## Fix Checklist

- [x] 真实 Ultra 实链确认问题。
- [x] 建立 Worker 与 Session 自动化失败复现。
- [x] result 改为最后一条 canonical agent message。
- [x] `SESSION_END isResult` 不重复持久化。
- [x] 恢复路径使用最后 canonical message，并对 recovered `assistant_text` 去重。
- [x] Session focused `7/7`、reconciliation `10/10` 和独立复审通过。
- [x] 最终 Worker full regression `200 total / 193 passed / 7 platform-skipped / 0 failed`。
- [x] 独立复审完成，无 P0/P1。
- [x] 新 Ultra exact result、无重复历史和真实 PC 刷新验收通过。

## Verification

- before: task completed, native subtask completed, but result length 100 and exact-final assertion failed; Session history contained two canonical agent messages plus one concatenated result row.
- after-static: Worker last canonical、recovered `assistant_text` 去重、Session `SESSION_END isResult` 非持久化和 reconciliation 均通过；Session focused `7/7`、reconciliation `10/10`、Worker `200 total / 193 passed / 7 platform-skipped / 0 failed` 回归通过，独立复审无 P0/P1。
- final-live: 新 Ultra 任务 `20260711-8023` 的 `task.resultText` 精确为 `FINAL_RESULT_OK`，文件副作用精确为 `FINAL_NATIVE_RESULT_OK`，native snapshot 为 `1`、相关 native SSE 为 `5`。PC 最终 assistant 消息刷新前后均为 `1` 条，native bar 为 `1/1`；desktop/320px Playwright 通过。先前失败任务只作为复现证据，本缺陷在隔离 P0-P2 范围关闭。

## References

- [OPT-001 progress](./OPT-001-independent-codex-app-server-worker-progress.md#acceptance-defect-closure)
- [BUG-001 delta fragmentation](./BUG-001-app-server-delta-message-fragmentation.md)
