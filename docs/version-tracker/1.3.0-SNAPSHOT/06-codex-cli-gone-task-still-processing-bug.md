---
type: bug
bug_source: user-report
version: 1.3.0-SNAPSHOT
ticket: BUG-020
severity: major
status: open
reproduction_status: partial
test_strategy: integration-test
automation_decision: required
owner: codex-worker-agent
---

# BUG Work Item

## Background

用户在 `Workers` 页面反馈一个 Codex 任务生命周期不一致问题：

1. 任务已经执行过“中止”操作。
2. Worker 侧对应的 Codex CLI 进程实际上已经不存在。
3. 但平台界面中，该任务仍长时间显示为 `处理中`，右侧历史会话卡片上的“中止”按钮也仍存在。

用户补充说明，该问题在“手动中止后的 Codex 任务”上更容易出现，说明它不是单次偶发，而是和 Codex 中止链路本身高度相关。

从截图可见，问题发生在 `1.3.0-SNAPSHOT` 的 Codex 会话场景中：中间主面板提示任务已中止，但右侧历史会话列表中的同一任务仍显示为 `处理中`，且仍提供“中止”操作入口。

## Reproduction

当前复现状态：`partial`

已确认现象：

1. 打开桌面端 `Workers` 页面。
2. 使用 Codex backend 创建一个任务。
3. 在任务执行过程中点击“中止”。
4. 一段时间后观察：
   - Codex CLI 进程已经退出或不再存在。
   - 主面板可能已经表现为“任务已中止”。
   - 右侧历史会话仍保留 `处理中` 标记，并继续展示“中止”按钮。

用户补充的高频触发条件：

1. 不是所有 Codex 任务都会中招。
2. 但“手动中止后的 Codex 任务”明显更容易进入这个异常状态。

当前缺口：

- 本轮尚未在本地重新构造一条稳定的端到端复现脚本。
- 还没有绑定到某个确定的后端日志样本或 DB 样本。
- 但基于截图和代码链路，已经可以确认这是“任务终态未稳定收敛到统一视图”的缺陷，而不是单纯样式问题。

## Expected vs Actual

Expected:

- 当用户手动中止 Codex 任务后，平台内的统一任务状态、会话聚合状态、历史会话交互态都应尽快收敛到终态。
- 如果 Worker 上已经没有对应 Codex CLI 进程，平台不应继续把该任务视为 `处理中`。
- 历史会话卡片不应继续显示“中止”按钮。

Actual:

- 某些手动中止后的 Codex 任务，虽然 CLI 已不存在，但历史会话仍保持 `处理中`。
- 主面板、任务详情、历史会话卡片之间的状态表现可能不一致。
- 用户会误以为任务仍在执行，或者系统没有真正完成中止收口。

## Impact Scope

- 桌面端 `Workers` 页面
- Codex Worker 任务生命周期可信度
- 右侧历史会话列表的交互态过滤
- 手动中止后的 Codex 会话继续操作路径
- 统一任务 API 的终态一致性

影响级别判断：`major`

原因：

- 问题直接发生在高频主路径 `创建任务 -> 手动中止 -> 查看历史会话`。
- 用户会对任务是否还在运行产生误判。
- 这属于任务状态收口缺陷，会影响后续重连、继续会话、重新同步、再次中止等操作判断。

## Current Assessment

当前更接近真实根因的判断是：Codex 的“任务真实状态”已经进入中止语义，但用于历史会话展示的聚合状态没有稳定同步，导致 UI 仍把会话视为 `PROCESSING`。

### 1. Codex 中止主链路本身会把任务写成 `ABORTED`

`CodexTaskService.abortTask()` 会在非终态任务上继续进入 `doAbortWorkerTask()`，后者会：

- 请求远端 Worker 中止
- 关闭本地流
- 将 `CodexTaskEntity.status` 置为 `ABORTED`
- 发布 `TaskStatusChangeEvent`

相关代码：

- [CodexTaskService.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java)
- [CodexStreamRelay.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexStreamRelay.java)

这说明“平台完全没有处理中止”并不是当前最可能的根因。

### 2. 历史会话的 `处理中` 不是直接看按钮点击结果，而是看聚合后的 `latestTask.status`

桌面端历史会话列表的“处理中”文案、状态点、是否展示“中止”按钮，都来自 `ClaudeWorkerView.vue` 中的 `ConversationGroup.latestTask.status`。

其中：

- `conv.latestTask.status === 'RUNNING'` 时会显示“中止”按钮。
- `deriveInteractionStateFromTaskStatus()` 把 `PENDING/RUNNING` 映射为 `PROCESSING`。
- `COMPLETED/FAILED/ABORTED` 会映射为 `AWAITING_REPLY`。

这意味着右侧仍显示 `处理中` 的前提，大概率不是前端单纯忘了改文案，而是它拿到的最新任务状态仍是 `RUNNING` 或旧值。

相关代码：

- [ClaudeWorkerView.vue](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/views/ClaudeWorkerView.vue)

### 3. 前端“手动中止”会先局部改 pane/task 状态，但历史会话依赖后端 reload 或 SSE 收敛

前端在 `handleAbortTask()` 和 `abortPane()` 中会立刻把打开中的 pane/task 改成 `ABORTED`，同时从 `activeTasks` 移除；但右侧历史会话列表仍依赖：

- `reloadWorkerTasks()`
- `loadDirectoryTasks()`
- 或 SSE 的 `task_status_change`

来把聚合数据真正刷新到 `workerState.tasks` / `directoryTasks`。

这会导致一个典型窗口期：

- 打开的面板已经显示“已中止”。
- 但历史会话列表仍沿用旧的 `latestTask.status`。

如果后端聚合查询返回的仍是旧状态，问题就会固化，而不是只出现瞬时闪烁。

相关代码：

- [useClaudeWorker.ts](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/composables/useClaudeWorker.ts)
- [ClaudeWorkerView.vue](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/views/ClaudeWorkerView.vue)

### 4. 后端历史会话分页是按 session 聚合 `session_tasks` / `sessions`，不是直接读取当前 pane 内存态

统一任务查询 `TaskDispatchFacade` 会按 `sessionId` 聚合 `SessionTaskEntity`，然后根据 `latestTask.status` 和 `SessionEntity.interactionState` 生成历史会话结果。

Codex 侧在 `persistTask()` 时会同步：

- `SessionTaskEntity.status`
- `SessionEntity.interactionState`

如果这里任何一环没有被及时写入、被旧记录覆盖、或排序选中了错误的 latest task，历史会话就会继续显示 `处理中`。

相关代码：

- [TaskDispatchFacade.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java)
- [CodexTaskService.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java)

### 5. 目前最值得优先怀疑的根因入口

基于现象和代码，优先级最高的排查方向有三个：

1. `abortTask()` 已把 `codex_tasks.status` 改成 `ABORTED`，但 `session_tasks` 或 `sessions.interactionState` 没有同步成功。
2. 历史会话聚合时选中的 `latestTask` 不是刚刚中止的那条任务，而是同 session 下另一条较旧的 `RUNNING` 记录。
3. 手动中止后，某条后续同步链路又把会话状态重新写回 `RUNNING/PROCESSING`，而此时 CLI 实际已不存在。

从用户反馈“手动中止后的任务更容易出现”看，第 1 和第 3 条的概率高于纯前端渲染问题。

## Test Strategy

本问题自动化要求：`required`

推荐主验证层级：`integration-test`

原因：

- 问题跨越 Codex task 表、统一 session 投影、SSE 推送和前端聚合展示。
- 只做前端单测无法证明后端终态收口真实正确。
- 需要覆盖“中止动作 -> DB 状态 -> 统一任务查询 -> 前端历史会话展示”的整条链路。

建议测试分层：

1. 后端集成测试
- 构造 Codex 任务处于 `RUNNING`。
- 调用统一 `cancel`。
- 断言 `codex_tasks.status = ABORTED`。
- 断言 `session_tasks.status = ABORTED`。
- 断言 `sessions.interactionState = AWAITING_REPLY`。
- 断言按 session 聚合后的历史会话结果不再返回 `PROCESSING`。

2. 前端集成测试
- 模拟用户在 `Workers` 页面中止 Codex 任务。
- 模拟后端返回 `ABORTED` 的刷新结果。
- 验证右侧历史会话卡片不再显示“处理中”与“中止”按钮。

3. 手工验证
- 创建 Codex 任务并手动中止。
- 确认 Codex CLI 进程消失后，历史会话状态同步收口为非处理中。

## Code Inventory

- [CodexTaskService.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java)
- [CodexStreamRelay.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexStreamRelay.java)
- [TaskDispatchFacade.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java)
- [TaskUpdateNotifier.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/session-module/src/main/java/com/foggy/navigator/session/sse/TaskUpdateNotifier.java)
- [useClaudeWorker.ts](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/composables/useClaudeWorker.ts)
- [ClaudeWorkerView.vue](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/views/ClaudeWorkerView.vue)
- [unifiedTask.ts](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/api/unifiedTask.ts)

## Fix Checklist

- 补一条可重复的“Codex 手动中止后状态收口”回归用例。
- 核对 `abortTask()` 后 `codex_tasks`、`session_tasks`、`sessions` 三处状态是否一致。
- 检查 session 聚合时 latest task 的排序与选取是否可能拿到旧任务。
- 检查手动中止后是否存在把会话重新写回 `RUNNING/PROCESSING` 的后续链路。
- 验证前端在 reload 后是否仍会保留旧的 `latestTask.status`。
- 修复后补自动化测试，防止 Codex 手动中止回归。

## Verification

修复后至少验证：

1. 手动中止 Codex 任务后，主面板与右侧历史会话状态一致。
2. CLI 进程已消失时，历史会话不再显示 `处理中`。
3. 历史会话卡片不再展示“中止”按钮。
4. 统一任务查询返回的 `latestStatus` 与 `interactionState` 已收敛到终态。
5. 同一 session 下连续多任务场景，不会因为聚合排序错误而拿到旧的 `RUNNING` 状态。

## References

- 用户提供截图：本轮对话中的 `Workers` 页面截图，显示主面板与右侧历史会话卡片状态不一致。
- 用户补充现象：手动中止后的 Codex 任务更容易出现该问题。
