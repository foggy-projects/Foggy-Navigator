# 10 Claude Worker Session Model Selection Sync Analysis

## Date

- 2026-04-04

## Type

- Bug
- Analysis
- Frontend State Sync

## Background

当前 Claude Worker 会话切换相关的模型同步存在两个相邻但不完全相同的问题：

1. 在历史会话列表中直接点击某个会话时，`configModelId` / 模型通常可以跟随恢复。
2. 但在 `Workers` 左侧通过切换工作目录时，右侧实际会切到另一个目录上下文中的会话，`configModelId` / 模型没有同步切换。
3. 当前进一步观察到：即使 `configModelId` 已经能自动切换，最终选中的 `model` 仍可能落回该 `configModelId` 下的默认模型，而不是该会话上一次实际执行使用的模型。

这两个现象都不是后端单点问题，而是前端 `ClaudeWorkerView.vue` 中“目录上下文切换 + 会话恢复 + 模型配置加载”三条状态流没有串成同一个原子流程。

## Scope

本项主要覆盖：

- `packages/navigator-frontend/src/views/ClaudeWorkerView.vue`
- `packages/navigator-frontend/src/composables/useWorkspaceContext.ts`
- `packages/navigator-frontend/src/composables/useClaudeWorker.ts`
- 统一任务分页接口返回的会话/任务模型字段使用方式

## Confirmed Findings

### 1. 目录切换会切换 workspace/pane，但不会恢复该 pane 对应会话的模型选择

这是当前“通过 Workers 切换工作目录，导致会话切换但模型没切”的直接原因。

关键代码路径：

- `useWorkspaceContext.ts` 明确把 pane 状态按 `workspaceKey` 持久化，目录切换后会恢复该目录原来的 pane 集合
- `ClaudeWorkerView.vue` 中 `activeWorkspaceKey = selectedDirectoryId ?? worker:${workerId}`
- `selectDirectory(...)` 只做目录切换、任务加载、SSE 恢复，不会读取新 workspace 当前 pane 对应的 session
- `restoreSessionModelSelection(...)` 只在 `viewTask(task)` 中调用

结果是：

1. 用户切换目录
2. 当前激活的 workspace 立刻切到另一个目录
3. 右侧 pane/session 实际上已经换了
4. 但模型选择栏仍保留上一目录/上一会话的状态

也就是说，当前前端把“会话切换”只定义成了 `viewTask(task)`，没有把“目录切换后恢复已有 pane/session”也视为会话切换事件。

## Root Cause 1

`selectDirectory(...)` 缺少“目录切换后按新 workspace 的当前会话恢复模型状态”的步骤。

这不是后端数据缺失，而是前端状态同步漏了一跳。

### 2. 跨 Worker / 跨目录跳转时，异步 `loadPlatformModelConfig()` 可能在会话恢复之后再次覆盖模型

这是当前“`configModelId` 已经切对了，但 `model` 变成默认值”的更直接原因。

关键代码路径：

- `selectWorker(...)` 会调用 `loadPlatformModelConfig()`
- `selectDirectory(...)` 在跨 Worker 时也会调用 `loadPlatformModelConfig()`
- `navigateToActiveSession(...)` / `navigateToProcessSession(...)` 会先 `selectDirectory(...)`，再马上 `viewTask(task)`
- `viewTask(task)` 里会调用 `restoreSessionModelSelection(...)`，尝试恢复会话自己的 `modelConfigId + model`
- 但 `loadPlatformModelConfig()` 是异步的，而且没有被 `await`

`loadPlatformModelConfig()` 结束后会继续做下面这些事：

1. 优先恢复 per-worker 缓存 `restoreWorkerLlmSelection(...)`
2. 否则应用 agent override / 默认模型配置
3. `watch(platformModelConfigId, ...)` 会在配置变化时把 `taskForm.model` 重置为该配置下的第一个可用模型

因此会出现下面的时序：

1. `viewTask(task)` 先把会话模型恢复成“上一次真实使用的模型”
2. 稍后，前面发出的 `loadPlatformModelConfig()` Promise 才返回
3. 它再次写入 `platformModelConfigId`
4. `watch(platformModelConfigId, ...)` 把 `taskForm.model` 改成默认模型

在这种覆盖下，最终 UI 上会表现为：

- `configModelId` 看起来是对的
- `model` 却退回到了该配置的默认值

## Root Cause 2

会话恢复和平台模型配置加载之间缺少优先级控制，导致“会话级恢复”会被“Worker 级默认/缓存恢复”异步覆盖。

本质上是竞态问题，不是简单的字段没传。

## Secondary Risk

`restoreSessionModelSelection(...)` 自身也包含一个显式回退分支：

- 当 `task.model` 为空
- 或不在当前 `claudeModelOptions` 中

它会直接回退到 `opts[0]`。

因此，即使未来修掉竞态，如果后端返回的 `task.model` 为空或不可匹配，前端仍会回退到默认模型。

当前代码层面尚不能证明这就是本次现象的主因，但这是一个明确存在的兜底路径，需要在修复时一起验证。

## Affected Code

### Frontend

- `packages/navigator-frontend/src/views/ClaudeWorkerView.vue`
  - `selectWorker(...)`
  - `selectDirectory(...)`
  - `loadPlatformModelConfig()`
  - `restoreWorkerLlmSelection(...)`
  - `restoreSessionModelSelection(...)`
  - `viewTask(task)`
  - `watch(platformModelConfigId, ...)`
- `packages/navigator-frontend/src/composables/useWorkspaceContext.ts`
  - workspace 级 pane 持久化与恢复

### Backend / Data Contract

- `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
  - 前端分页 / 搜索依赖的任务 `model` / `modelConfigId` 字段来源
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
  - Claude 任务 DTO 的 `model` / `modelConfigId` 输出

## Impact

该问题会直接影响：

- 会话切换后顶部模型栏显示错误
- 用户继续回复该会话时，请求携带错误的 `model`
- 新任务 / resume 任务可能把错误模型继续写回任务记录
- 后续再切回同一会话时，错误状态会被持续放大

因此这不是纯 UI 显示问题，而是会影响真实执行后端模型选择。

## Suggested Fix Direction

建议拆成两层修复：

1. 目录切换补同步
   - 在 `selectDirectory(...)` 切换完 workspace 后，读取新 workspace 当前聚焦 pane 或当前唯一 pane 的 `task.sessionId`
   - 以该 pane 对应 task 主动执行一次 `restoreSessionModelSelection(...)`

2. 给 `loadPlatformModelConfig()` 增加会话优先级保护
   - 目录/Worker 切换过程中，如果已经进入某个明确 session 的 `viewTask(...)` 恢复流程，后续返回的 `loadPlatformModelConfig()` 不应再覆盖会话级模型
   - 可以通过请求序号、session token、或“当前是否处于 session restore 中”的 guard 实现

3. 调整自动默认模型写回条件
   - `watch(platformModelConfigId, ...)` 不应在“会话恢复中”无条件把模型重置为第一个默认项
   - 只有在用户手动切换 `configModelId` 或没有会话上下文时，才应该做默认回填

4. 修复时顺带校验 `task.model` 数据完整性
   - 确认分页任务列表和会话搜索结果中的 `model` 确实是最新任务的实际模型，而不是空值或旧值

## Missing Tests

当前缺少至少两类回归测试：

1. 前端集成测试
   - 目录 A 打开会话 X
   - 切到目录 B，恢复会话 Y
   - 断言模型栏已切到 Y 的 `configModelId + model`

2. 前端竞态测试
   - 先触发 `loadPlatformModelConfig()`
   - 再立即 `viewTask(...)`
   - 让模型配置接口延迟返回
   - 断言延迟返回不会把会话模型改回默认值

## Status

本项作为 `1.0.0-SNAPSHOT` 缺陷分析记录，当前状态为：

- 原因已定位
- 影响链路已明确
- 对修复后新建会话的回归验证已完成，可进入版本签收

## Implementation Status

### 已完成的验收回写（2026-04-07）

结合 [11-playwright-revalidation-report.md](./11-playwright-revalidation-report.md) 的真实环境复测结论，本次进一步把前端集成回归测试补齐并修正到当前实现口径。

本次回写的测试修正全部位于：

- `packages/navigator-frontend/src/views/__tests__/ClaudeWorkerView.integration.test.ts`

已确认的测试修正包括：

1. 补全组件 `onMounted` 和目录切换链路实际依赖的 API mock
   - `@/api/codingAgent`
   - `@/api/platform`
   - `@/api/unifiedTask`
   - `@/api/ssh`
   - `@/api/fileBrowser`
2. 把断言从旧的 `claudeWorkerApi.*` 调整为当前统一任务实现实际调用的：
   - `unifiedTaskApi.resumeTaskUnified`
   - `unifiedTaskApi.listTasksByDirectoryPagedUnified`
3. 在 `mount()` 时全局注册 `ElementPlus`，避免 `el-table-column` 在 happy-dom 下因 slot 解构触发误报
4. 将 `afterEach` 从 `vi.restoreAllMocks()` 调整为 `vi.clearAllMocks()`，避免 factory mock 实现被还原后影响后续用例

### 验证结果

1. 真实环境复测证据
   - [11-playwright-revalidation-report.md](./11-playwright-revalidation-report.md) 已确认：对修复后新建会话，切走再点回后顶部模型栏能够恢复到该会话上一次实际使用的模型，继续发送第 2 轮后也未再出现真实执行模型漂移
2. 前端集成回归
   - 执行命令：`npm run test -- ClaudeWorkerView.integration.test.ts`
   - 结果：`3 passed`
3. 当前验收边界
   - 本项签收范围明确限定为“修复后新建会话”
   - 修复前已创建的旧会话不在本次验收范围内，这一边界与 `11` 中的复测说明保持一致

### 非阻断后续项

本次自动化回归虽然通过，但输出中仍存在几类未使测试失败的环境噪音，建议后续单独清理：

- `session` 历史消息接口仍会在测试环境中尝试访问 `/api/v1/sessions/...`
- `UnifiedSse` 订阅/退订仍会在 happy-dom 下打印网络失败日志
- `@/api/ssh` mock 当前未补 `listSshSessions` 导出，组件会打印一条已捕获错误日志

这些问题当前不影响 `10` 的修复结论，但说明测试隔离还可以继续收紧。

## 验收签收

- 签收状态：✅ 已签收
- 签收日期：2026-04-07
- 签收方式：版本文档审计签收
- 签收依据：真实环境复测已确认“修复后新建会话”链路恢复正常，且前端集成回归 `3/3` 通过。
- 验收边界：仅覆盖修复后新建会话；修复前旧会话不在本次签收范围内。
- 后续事项：保留测试环境噪音清理，不阻断本版本签收。
- 关联台账：[12-acceptance-signoff.md](./12-acceptance-signoff.md)
