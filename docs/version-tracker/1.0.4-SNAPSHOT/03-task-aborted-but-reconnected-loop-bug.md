---
type: bug
bug_source: user-report
version: 1.0.4-SNAPSHOT
ticket: BUG-019
severity: major
status: open
reproduction_status: partial
test_strategy: integration-test
automation_decision: required
owner: claude-worker-agent
---

# BUG Work Item

## Background

用户在 `Workers` 页面反馈：某个任务已经中止，或者至少已经进入异常终止语义，但会话消息区仍持续追加 `Task stream reconnected` 系统消息。

从用户提供的界面截图看，同一任务在较短时间内连续出现多条 `Task stream reconnected`，说明系统没有把该任务稳定收敛到终态，而是仍在重复执行 SSE 重连恢复逻辑。

这不是单纯的提示样式问题。现有实现中，`reconnected` 提示来自后端真实的重连动作，因此消息持续刷出意味着后端仍在把该任务视为可恢复的活跃任务处理。

## Reproduction

当前复现状态：`partial`

已确认现象：

1. 打开桌面端 `Workers` 页面。
2. 进入某个 Claude Worker 任务会话。
3. 任务发生异常中止，或用户视角上已经“停掉/中止”。
4. 会话消息区后续仍不断出现 `Task stream reconnected`。

当前缺口：

- 本轮尚未在本地重新构造一条稳定复现链。
- 但用户截图已明确表明同一任务被连续推送多条 `reconnected` 消息。
- 结合代码链路，已能确认该现象对应真实的后端自动重连逻辑，而不是纯前端假渲染。

建议后续补充稳定复现步骤：

1. 构造一个 Worker SSE 中断或执行异常场景。
2. 观察任务状态是否仍停留在 `RUNNING` / `AWAITING_PERMISSION`。
3. 观察后端是否持续进入 `attemptReconnect()` 或被 `TaskStateReconciler` 反复触发 `reconnectTask()`。
4. 验证前端是否对每次 `reconnected` 都追加一条系统消息。

## Expected vs Actual

Expected:

- 当任务已经进入 `FAILED` / `ABORTED` / `COMPLETED` 等终态后，不应再自动重连 Worker SSE 流。
- 即便底层连接发生恢复，同一任务也不应在消息区无限堆积 `Task stream reconnected` 提示。
- 用户视角上的“任务已终止”与系统内部的自动恢复语义应保持一致。

Actual:

- 任务在用户视角上已经异常中止或已终止后，系统仍持续产生 `Task stream reconnected`。
- 说明后端仍在把该任务当作活跃任务重连。
- 前端对每次 `reconnected` 事件都会直接渲染为一条可见系统消息，导致刷屏。

## Impact Scope

- 桌面端 `Workers` 页面会话体验
- Claude Worker 任务生命周期可信度
- 任务失败 / 中止后的状态一致性
- SSE 自动恢复逻辑
- `TaskStateReconciler` 自动修复逻辑

影响级别判断：`major`

原因：

- 会误导用户判断任务真实状态。
- 会让失败/中止任务看起来像仍在反复恢复执行。
- 表明后端任务终态收口存在缺陷，属于生命周期管理问题，不是单纯展示瑕疵。

## Current Assessment

当前代码链路显示，该问题由“后端自动重连未正确收口”与“前端对重连提示不做去重/终态拦截”共同导致。

### 1. 后端会在重连成功时主动推送 `reconnected`

`WorkerStreamRelay.reconnectTask()` 在成功重新订阅 Worker SSE 后，会直接发布：

- `Task stream reconnected`
- `subtype = reconnected`

相关代码：

- [WorkerStreamRelay.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/WorkerStreamRelay.java#L179)

`attemptReconnect()` 也会在自动重连成功后再次发布同类消息：

- [WorkerStreamRelay.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/WorkerStreamRelay.java#L427)

### 2. Worker 流完成或报错后，只要 DB 状态仍是活跃态，就会继续重连

在 `subscribeSseFlux()` 中：

- `doOnComplete()` 会检查当前任务是否已经是 `COMPLETED/FAILED/ABORTED`
- 如果不是，就进入 `attemptReconnect()`
- `doOnError()` 对非 4xx 错误同样会进入 `attemptReconnect()`

相关代码：

- [WorkerStreamRelay.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/WorkerStreamRelay.java#L347)

这意味着只要任务没有被及时落成终态，而仍停留在 `RUNNING` 或 `AWAITING_PERMISSION`，系统就会持续尝试恢复 SSE。

### 3. Reconciler 还会追加第二条自动重连路径

`TaskStateReconciler` 中有两类逻辑会再次触发 `streamRelay.reconnectTask()`：

1. CLI 仍存活，但 Java 侧没有活跃 stream，且检测到 seq gap
2. CLI 已退出，但 Worker 仍有未同步事件

相关代码：

- [TaskStateReconciler.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/TaskStateReconciler.java#L174)
- [TaskStateReconciler.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/TaskStateReconciler.java#L219)

因此问题并非只有单次 reconnect，而是存在“流回调自动重连 + Reconciler 补重连”的双路径。

### 4. 前端会把每次 `reconnected` 都渲染成可见消息

`chatState.ts` 对 `subtype === 'reconnected'` 的处理是直接 push 一条系统消息，没有去重，也没有按任务终态过滤：

- [chatState.ts](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/foggy-chat-core/src/store/chatState.ts#L222)

同时，`useTaskPane.ts` 在收到 `ERROR` 时虽然会把 pane task 状态设为 `FAILED`，但后续如果又收到了新的 `reconnected` 事件，消息区仍会继续追加提示：

- [useTaskPane.ts](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/composables/useTaskPane.ts#L173)

### 5. 初步根因判断

更接近真实根因的描述应为：

- 某些异常/断流场景下，任务没有及时、稳定地从活跃态收敛到终态。
- 后端仍把它视为“可恢复任务”，持续自动重连。
- 每次重连又被前端渲染成新的 `Task stream reconnected`，于是形成刷屏。

因此该问题本质上是“任务终态收口缺陷”，前端刷屏只是外显症状。

## Test Strategy

本问题自动化要求：`required`

推荐主验证层级：`integration-test`

原因：

- 根因主要在后端任务生命周期与自动重连策略。
- 仅做前端单测不足以证明问题真正被修复。
- 需要验证 DB 状态、Worker 状态、SSE 重连、Reconciler 触发之间的联动。

建议测试分层：

1. 后端集成测试
- 模拟任务流异常结束但任务状态尚未落终态的场景。
- 验证自动重连次数、终态收口逻辑、Reconciler 补重连逻辑是否符合预期。
- 验证终态后不再触发 `reconnectTask()`。

2. 前端集成测试
- 模拟连续收到多个 `reconnected` 事件。
- 验证消息区不会无限堆积相同提示，或终态后不再展示该提示。

3. 必要时补手工验证
- 在真实 Worker 环境下构造一次断流/异常中止。
- 确认会话区不再出现持续刷新的 `Task stream reconnected`。

## Code Inventory

- [WorkerStreamRelay.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/WorkerStreamRelay.java)
- [TaskStateReconciler.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/TaskStateReconciler.java)
- [ClaudeTaskService.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java)
- [chatState.ts](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/foggy-chat-core/src/store/chatState.ts)
- [useTaskPane.ts](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/composables/useTaskPane.ts)
- [ClaudeWorkerView.vue](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/views/ClaudeWorkerView.vue)

## Fix Checklist

- 明确哪些异常场景下任务仍会残留在 `RUNNING` / `AWAITING_PERMISSION`。
- 收紧 `WorkerStreamRelay` 自动重连条件，避免已终止语义任务继续自动 reconnect。
- 审核 `TaskStateReconciler` 的补重连条件，防止与终态语义冲突。
- 明确“可恢复断流”与“已终态失败/中止”是否需要新的状态或判定分支。
- 前端对 `reconnected` 提示做去重、折叠或终态过滤，避免消息刷屏。
- 补自动化测试覆盖异常断流、自动重连、终态收口、前端提示去重。

## Verification

修复后至少验证：

1. 构造异常中止或断流场景后，任务最终能稳定进入正确终态。
2. 终态任务不会继续被 `WorkerStreamRelay` 自动重连。
3. `TaskStateReconciler` 不会对已终态任务重复发起补重连。
4. 会话消息区不会持续堆积 `Task stream reconnected`。
5. 如果保留重连提示，同一任务的提示应有限、可解释，且不应在终态后继续增长。

## References

- 用户提供截图：本轮对话中的 `Workers` 页面截图，显示同一任务出现大量 `Task stream reconnected`。
- 相关实现文档：
  - [06-task-message-alignment.md](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/docs/requirement-tracker/2026-Q1/06-task-message-alignment.md)
