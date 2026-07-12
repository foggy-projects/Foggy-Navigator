---
type: bug
bug_source: user-report
version: 1.4.0-SNAPSHOT
ticket: BUG-017
severity: major
status: fixed-isolated
reproduction_status: confirmed
test_strategy: unit-and-integration
automation_decision: required
owner: codex-agent-worker | codex-worker-agent | navigator-frontend
---

# Codex SDK 过程消息误作最终答复与 SSE 生命周期异常

## Background

升级 GPT-5.6 支持后，Codex SDK 单轮执行可能产生多条 `agent_message`：工具调用前的进度说明和真正的最终答复。SDK Worker 当前把所有消息拼接为 result，Java Relay 又把每条普通 `assistant_text` 映射为 `TEXT_COMPLETE`，导致前端把过程说明显示成最终答复。

同时，SDK Worker 的任务重连订阅只监听事件，不监听广播关闭；任务完成后订阅可能继续悬挂。长时间没有 SSE 字节时，中间网络设备也可能切断连接，Java 最终显示 `CODEX_WORKER_STREAM_DISCONNECTED`，即使 Codex CLI 子进程已经正常退出或远端任务已经完成。

## Reproduction

1. 使用 Codex SDK Worker 执行包含工具调用的 GPT-5.6 任务。
2. SDK 依次返回进度 `agent_message`、工具事件、最终 `agent_message`、`turn.completed`。
3. 观察事件日志中 result 是两条消息拼接，前端提前出现过程消息气泡。
4. 通过 `/api/v1/tasks/{taskId}/subscribe` 重连运行中任务，随后让任务完成；观察订阅未因 `EventBroadcast.close()` 主动结束。

## Expected vs Actual

- Expected: 工具调用前的消息属于 commentary；最终 result 只取最后一条 canonical `agent_message`。
- Actual: 所有 `agent_message` 被拼接，过程说明和最终答复混为一个 result。
- Expected: 任务广播关闭后，初始和重连 SSE 都立即结束；运行期间定期发送注释心跳。
- Actual: 重连 SSE 没有 close 通知，也没有心跳，容易形成悬挂或被空闲链路切断。
- Expected: 既有响应超时提示、用户/系统主动中止和中止后的远端完成态判定保持不变。
- Actual: 本缺陷不应通过取消超时机制或无限等待来规避。

## Test Strategy

- Worker unit：进度消息 + 工具调用 + 最终消息时，只发一条 `commentary` 过程事件，result 精确等于最终消息。
- Worker unit：`EventBroadcast.close()` 通知活跃订阅且只通知一次；关闭后的订阅立即收到关闭通知。
- Java unit：`assistant_text/subtype=commentary` 映射为非终态 `STATE_SYNC`，不映射为 `TEXT_COMPLETE`。
- Existing regression：Codex task abort/reconcile、response timeout projection、SDK Worker typecheck/build/tests 保持通过。

## Code Inventory

- `tools/codex-agent-worker/src/codex/sdk-wrapper.ts`
- `tools/codex-agent-worker/src/persistence/event-store.ts`
- `tools/codex-agent-worker/src/routes/query.ts`
- `tools/codex-agent-worker/src/routes/tasks.ts`
- `tools/codex-agent-worker/tests/sdk-wrapper.test.ts`
- `tools/codex-agent-worker/tests/event-store.test.ts`
- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexStreamRelay.java`
- `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexStreamRelayTest.java`

## Fix Checklist

- [x] 通过用户现场截图和 Worker JSONL 确认复现。
- [x] 建立 Worker 事件聚合与广播关闭回归测试。
- [x] 最后一条候选消息只进入 result，已被后续执行证明为过程的消息标记为 commentary。
- [x] SSE 增加心跳并在广播关闭时结束所有订阅。
- [x] Java Relay 将 commentary 映射为非终态过程状态。
- [x] 保持既有超时显示、主动中止和远端终态 reconcile 语义。
- [x] 完成定向测试和构建；部署后的现场复验列为后续发布检查项。

## Verification

- before: 现场事件文件中多条 `assistant_text` 被拼接到 result；任务重连订阅不接收广播关闭通知。
- after: Worker 仅把最终候选消息写入 result，过程消息以 `commentary` 发出；Java Relay 不再将
  commentary 投影为 `TEXT_COMPLETE`。初始订阅与重连订阅均使用 replay/subscription/gap replay
  交接和序号去重，广播关闭会结束 SSE，并每 15 秒发送注释心跳。
- Worker verification: `npm test` 通过（143 项，142 通过、1 项 Windows 条件跳过），
  `npm run typecheck`、`npm run build` 通过。
- Release target: Codex SDK Worker `1.0.14`；App Server Worker 无运行时代码变化，不重复发布。
- Release candidate: `package:release --platform all --smoke auto` 判定并通过 `full` smoke，覆盖三平台
  归档结构、SHA-256、禁带文件扫描、候选包 `npm ci` 和 Linux 候选 `/health` 启动检查；候选健康
  状态为 `ok`、版本为 `1.0.14`。
- Java verification: `CodexStreamRelayTest`、`CodexRuntimeRegistryServiceTest`、
  `CodexRuntimeControllerTest` 共 134 项通过；`CodexTaskServiceTest` 与
  `TaskResponseTimeoutSupportTest` 共 80 项通过，确认原有超时显示、主动中止与完成态 reconcile
  行为未被替换。
- Remaining live check: 发布并重启平台与 SDK Worker 后，用真实 GPT-5.6 工具任务确认前端只把
  canonical final 显示为最终答复，并观察长任务不再产生空闲断流。

## References

- [BUG-007 App Server final result aggregation](./BUG-007-app-server-final-result-aggregation.md)
- [BUG-011 terminal broadcast and task-store bounds](./BUG-011-terminal-broadcast-and-task-store-bounds.md)
