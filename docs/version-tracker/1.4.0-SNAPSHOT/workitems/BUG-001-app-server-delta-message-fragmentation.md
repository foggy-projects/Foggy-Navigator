---
type: bug
bug_source: acceptance-found
version: 1.4.0-SNAPSHOT
ticket: BUG-001
severity: major
status: closed-isolated
reproduction_status: confirmed
test_strategy: integration-test
automation_decision: required
owner: codex-app-server-worker | codex-worker-agent
---

# App-server 文本 Delta 被持久化为多条消息

## Background

最终真实 Worker -> Java -> unified SSE -> PC 验收中，父 Agent 的完整输出 `B_FULL_CHAIN_OK` 被展示为 `B`、`_FULL`、`_CHAIN`、`OK` 四张消息卡片。原生子任务条本身正常。

## Reproduction

1. 使用 app-server Worker 创建 Ultra 任务。
2. 让根 Agent 输出一个会被拆成多个 `item/agentMessage/delta` 的短文本。
3. 打开 PC Task Pane 或刷新后读取持久消息。

实际：每个 delta 被 Java 映射为 `TEXT_COMPLETE` 并持久化。期望：delta 是不持久化的 `TEXT_CHUNK`，`item/completed` 只产生一条完整 `TEXT_COMPLETE`。

独立复核还确认了同一链路上的 turn 关联竞态：`turn/start` response 与首批 item notification 处于同一 stdout batch 时，Promise continuation 尚未执行 `onTurnStarted`，早期 item 会因 root turnId 未设置而被丢弃。若只丢前缀 delta，Worker 最终 result 还可能只剩后缀。

## Impact Scope

- app-server 根 Agent 的流式文本和刷新后历史消息。
- 不影响旧 SDK Worker；不影响 native child payload 隐私边界。

## Test Strategy

- Worker integration：两个 delta 必须带 `subtype=text_delta`，完成事件必须再产生一条完整 assistant message，最终结果不得重复累加。
- Worker runtime：`turn/start` response、delta、completed、terminal 同批到达时，必须在 turn 关联完成后保序回放；stale turn 不得终止当前 turn。
- Java integration：`text_delta -> TEXT_CHUNK`，完成 assistant event -> `TEXT_COMPLETE`。
- Real E2E：PC 中完整响应只显示一张消息卡片，刷新后仍只有一条。

## Code Inventory

- `tools/codex-app-server-worker/src/app-server/event-bridge.ts`
- `tools/codex-app-server-worker/src/app-server/runtime.ts`
- `tools/codex-app-server-worker/tests/app-server-runtime.test.ts`
- `tools/codex-app-server-worker/tests/native-subtask.test.ts`
- `addons/codex-worker-agent/.../CodexStreamRelay.java`
- `addons/codex-worker-agent/.../CodexStreamRelayTest.java`

## Fix Checklist

- [x] 建立自动化失败复现。
- [x] delta 增加稳定 subtype 并保留实时 SSE。
- [x] item 完成时以 canonical `item.text` 生成完整消息并对重复 completed 去重；任务 result 取最后一条 canonical assistant message。
- [x] turn 关联完成前有界缓冲通知，完成 durable turnId 回调后保序回放。
- [x] Java 将 delta 映射为 `TEXT_CHUNK`。
- [x] focused runtime/native `18/18` 与 typecheck 通过。
- [x] Java Codex addon `259/259` 通过；raw full reactor 被 Windows Surefire fork/path 基础设施问题阻断，受影响定向测试通过。
- [x] 最终 Worker full regression `200 total / 193 passed / 7 platform-skipped / 0 failed`。
- [x] 重跑真实 PC desktop/320px 验收。

## Verification

- before: Worker focused test 缺少 delta subtype/最终消息；同批 response/notification 得到空 result；真实 PC 出现多张碎片卡片。
- after-static: focused runtime/native `18/18`、typecheck、Java Codex `259/259` 和 Worker `200 total / 193 passed / 7 platform-skipped / 0 failed` 回归通过；raw full reactor 仅受 Windows Surefire fork/path 基础设施问题阻断。
- final-live: 新任务 `20260711-8023` 经 Worker -> Java -> unified SSE -> PC 完成；`task.resultText` 精确为 `FINAL_RESULT_OK`。Playwright 验证最终 assistant 消息刷新前后均为 `1` 条，浏览器无 console、HTTP、应用或 UI 错误，desktop 与 320px 均通过。先前失败任务只保留为复现证据。

## References

- [OPT-001 progress](./OPT-001-independent-codex-app-server-worker-progress.md#acceptance-defect-closure)
- [BUG-007 final result aggregation](./BUG-007-app-server-final-result-aggregation.md)
