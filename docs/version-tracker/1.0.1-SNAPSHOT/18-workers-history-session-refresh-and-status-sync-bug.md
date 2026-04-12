---
type: bug
bug_source: user-report
version: 1.0.1-SNAPSHOT
ticket: BUG-018
severity: major
status: open
reproduction_status: confirmed
test_strategy: e2e-test
automation_decision: required
owner: navigator-frontend
---

# BUG Work Item

## Background

在桌面端 `Workers` 页面人工验收时，用户反馈右侧“历史会话”区域存在两个明显的一致性问题：

1. 中间会话区顶部操作条/状态与右侧历史会话卡片状态不匹配。
2. 新建会话后，右侧历史会话列表不会立即出现新卡片，通常要等任务完成后才刷新。

这两个问题都发生在 `ClaudeWorkerView` 的核心操作路径中，直接影响用户对当前会话状态的判断，也会影响继续会话、转发、归档等后续动作。

## Reproduction

### BUG A: 当前会话状态与历史会话卡片状态不匹配

1. 打开桌面端 `Workers` 页面。
2. 选择任一可用目录。
3. 新建一个普通任务，让会话进入运行态。
4. 观察中间主会话区顶部状态文案/按钮。
5. 同时观察右侧“历史会话”中对应卡片的状态文案。

实际现象：

- 主会话区显示会话已进入运行态或已可继续操作。
- 右侧历史会话卡片仍显示旧状态，例如仍停留在 `处理中`，或与当前主区域不一致。

### BUG B: 新建会话后历史会话列表不立即刷新

1. 打开桌面端 `Workers` 页面。
2. 选中一个目录，确认右侧已有历史会话列表。
3. 在主输入框输入新 prompt 并发送，创建一个全新会话。
4. 观察右侧“历史会话”列表。

实际现象：

- 新会话不会在创建成功后立刻出现在右侧列表。
- 往往需要等任务完成、状态轮询命中或手工刷新后，右侧才出现该会话卡片。

## Expected vs Actual

Expected:

- 当前会话的状态在主会话区、右侧历史会话卡片、历史会话操作入口之间应保持一致。
- 新建会话成功后，右侧历史会话列表应立即出现对应卡片，并进入正确的初始状态，例如 `处理中`。

Actual:

- 主会话区与右侧历史会话卡片存在状态不同步。
- 新建会话后右侧历史会话列表缺少即时插入/刷新，要等任务完成后才补刷新。

## Impact Scope

- 桌面端 `Workers` 页面主链路
- 历史会话列表的当前状态判断
- 新建会话后的可见性与可继续操作性
- 可能波及转发、里程碑、继续会话、回退、归档等依赖当前会话上下文的入口

这是高频主流程缺陷。虽然不会直接导致数据丢失，但会让用户误判当前会话状态，并降低页面可操作性。

## Current Assessment

从当前前端实现看，问题大概率集中在 `ClaudeWorkerView.vue` 的以下状态流：

- 创建新任务后，仅建立了中间 pane 和 SSE 连接，但未在创建成功后立即把新会话同步进右侧历史会话数据源。
- SSE `task_status_change` 虽然会尝试同步 `activeTasks` 与 `tasks`，但历史会话卡片依赖的会话聚合数据可能没有在所有路径上即时重算。
- `reloadWorkerTasks()` 与 `loadDirectoryTasks()` 的触发时机在“新建会话成功后”与“任务完成后”之间可能不一致，导致右侧列表刷新滞后。

相关代码位置：

- [ClaudeWorkerView.vue](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/views/ClaudeWorkerView.vue)

已观察到的相关实现片段：

- SSE 状态同步处理：`handleTaskUpdateEvent(...)`
- 创建新任务入口：`handleCreateTask()`
- 历史会话重载：`reloadWorkerTasks()` / `loadDirectoryTasks()`

## Test Strategy

本问题默认要求补自动化验证：

- 主要是桌面端前端交互与状态同步问题，最合适的首选层级是 `e2e-test`
- 如能稳定抽取局部状态流，也建议补充 `ClaudeWorkerView` 级别的前端集成测试

建议测试分层：

- Playwright / 浏览器 E2E：
  - 新建会话后右侧历史会话立即出现
  - 新建会话运行期间，中间主会话区与右侧卡片状态一致
- 前端集成测试：
  - 模拟 createTask 成功后，历史会话列表数据源即时更新
  - 模拟 SSE 状态变更后，历史会话卡片状态与 pane task 状态保持一致

## Code Inventory

- [ClaudeWorkerView.vue](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/views/ClaudeWorkerView.vue)
- [useClaudeWorker.ts](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/composables/useClaudeWorker.ts)
- [unifiedTask.ts](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/api/unifiedTask.ts)

## Fix Checklist

- 复现并明确“主会话状态”和“右侧卡片状态”分别取自哪一套状态源。
- 确认新建会话成功后，右侧历史会话列表是否少了一次即时插入或 reload。
- 确认 SSE `task_status_change`、`task_completion` 到达时，历史会话卡片依赖的数据是否全部同步。
- 修复新建会话后的右侧即时刷新。
- 修复会话状态在 pane、activeTasks、history conversations 之间的同步闭环。
- 补充至少一条自动化验证覆盖“创建即出现”和“运行态状态一致”。

## Verification

手工验证应覆盖：

1. 在目录下新建会话后，右侧历史会话立即出现新卡片。
2. 任务处于 `处理中` 时，中间区域与右侧卡片状态一致。
3. 任务完成后，中间区域与右侧卡片同步变更为完成/待回复态。
4. 点击右侧新卡片重新打开会话，不应出现旧状态残留。

自动化验证应覆盖：

1. 登录并进入 `Workers` 页面。
2. 创建新会话。
3. 在任务未完成前断言右侧历史会话已出现。
4. 断言右侧卡片状态与中间主会话顶部状态一致。

## References

- 用户截图证据：本轮对话中的 `Workers` 页面截图，显示主会话与右侧历史卡片状态存在不一致。
- 相关体验清单：
  - [pc-workers-experience-checklist.md](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/docs/test-cases/pc-workers-experience-checklist.md)
- 相关执行报告：
  - [pc-workers-playwright-report-2026-04-12.md](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/docs/test-reports/pc-workers-playwright-report-2026-04-12.md)
