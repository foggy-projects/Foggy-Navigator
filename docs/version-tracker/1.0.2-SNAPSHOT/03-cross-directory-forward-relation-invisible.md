---
type: bug
bug_source: user-report
version: 1.0.2-SNAPSHOT
ticket: BUG-003
severity: minor
status: fixed
reproduction_status: confirmed
test_strategy: manual-then-e2e
automation_decision: recommended
owner: navigator-frontend
---

# BUG Work Item

## Background

BUG-002 修复后，同目录内转发的父子/上下游关系已能正常展示。但用户在验证过程中发现：当**转发到其他工作目录**时，源会话所在目录与目标会话所在目录都看不到关联标签（"上游" / "子会话 N"），视觉上像是两个独立会话。

此问题是 BUG-002 遗留的关联子问题，单独登记便于跟踪修复。

## Reproduction

1. 进入 Workers 页面，选中工作目录 A。
2. 新建会话，等待 assistant 回复。
3. 点击转发，选择"转发到新会话"，将 `directoryId` 切换到工作目录 B，提交。
4. 转发成功后，分别查看目录 A 与目录 B 的历史会话列表。

实际现象：

- 目录 A 中的源会话没有"子会话 N"标签。
- 目录 B 中的目标会话没有"上游"标签。
- 切换到 Worker 级视图（不选目录）时，关系也不一定可见（取决于分页是否覆盖）。

## Expected vs Actual

Expected:

- 即使父子会话位于同一 Worker 下的不同工作目录，关联标签也应正常显示。
- 点击"上游"或"子会话"应能跨目录跳转到对应会话。

Actual:

- 当前 `childConversationMap` / `conversationBySessionId` 仅基于当前目录的会话池计算，跨目录的会话不会出现在同一个映射里，关联标签无法显示。

## Current Assessment

### 根因

前端 [`ClaudeWorkerView.vue`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/views/ClaudeWorkerView.vue) 的关系映射来自 `allConversations`，而 `allConversations` 在选中目录时只包含 `directoryConversations`：

```ts
const allConversations = computed(() =>
  selectedDirectoryId.value ? directoryConversations.value : workerConversations.value,
)

const conversationBySessionId = computed(() => {
  const map = new Map<string, ConversationGroup>()
  for (const conv of allConversations.value) { ... } // 仅当前目录
})

const childConversationMap = computed(() => {
  for (const conv of allConversations.value) { ... } // 仅当前目录
  ...
})
```

因此跨目录会话不会出现在关系查找池中。

## Test Strategy

- manual-then-e2e：先手动验证修复生效，后续补 Playwright 回归，防止再次退化。
- 关键场景：同 Worker 跨目录转发、目录 A 与目录 B 视角下的关系标签、关系标签点击跨目录跳转。

## Code Inventory

- [ClaudeWorkerView.vue](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/views/ClaudeWorkerView.vue) — `conversationBySessionId` / `childConversationMap` 计算逻辑
- [useForwardSession.ts](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/composables/useForwardSession.ts) — 已有 `forwardConversationPool` 的合并思路可参考

## Fix Plan（方案 A）

将关系映射的数据源从"目录过滤后的会话池"改为"Worker 级会话池 + 当前目录会话池的去重合集"，确保同一 Worker 下跨目录的父子关系都能被解析。

**核心改动（`ClaudeWorkerView.vue`）**：

1. 新增 `relationConversationPool` computed：合并 `workerConversations` 与 `directoryConversations`，按 `sessionId` 去重。
2. `conversationBySessionId` 与 `childConversationMap` 改为基于 `relationConversationPool`，而不是 `allConversations`。
3. `allConversations`（列表渲染用）保持不变，仍按当前目录过滤。

这样：
- 列表渲染范围：仍然是"当前目录"（不会突然把其他目录的会话混进列表）。
- 关系解析范围：扩大到"整个 Worker"，跨目录父子关系可见。

## Out of Scope（后续再评估）

- 跨目录跳转的 UX 优化（点击"上游"时自动切换目录并高亮子会话）
- 跨 Worker 转发的关系展示（当前转发本身就受限于 Worker）
- 在关系标签上展示"位于目录 X"等上下文信息

## Verification

手工验证应覆盖：

1. 同目录转发：关系显示正常（BUG-002 回归）。
2. 同 Worker 跨目录转发：
   - 源目录下源会话显示"子会话 N"标签。
   - 目标目录下目标会话显示"上游"标签。
3. 点击关系标签可跳转到对应会话（即使跨目录）。
4. Worker 级视图下，跨目录关系同样可见。

## References

- 前置 BUG：[02-session-forward-relation-missing-bug.md](./02-session-forward-relation-missing-bug.md)
- 用户截图：本轮对话中的 Workers 页面截图，显示跨目录转发后父会话缺少"子会话"标签。

## Fix Record

- fix_date: 2026-04-17
- fixed_by: Claude
- approach: 方案 A — 关系映射池从目录级扩展到 Worker 级 + 关联标签点击跨目录跳转

### 第一轮：关联标签可见性

文件：`packages/navigator-frontend/src/views/ClaudeWorkerView.vue`

1. 新增 `relationConversationPool` computed：以 `sessionId` 去重合并 `workerConversations` 和 `directoryConversations`。
2. `conversationBySessionId` 与 `childConversationMap` 改为基于 `relationConversationPool` 计算，不再受 `selectedDirectoryId` 过滤影响。
3. `allConversations`（列表渲染数据源）保持原样，确保列表仍按当前目录展示。

### 第二轮：关联标签点击跨目录跳转

用户反馈：仅能看到"上游 / 子会话 N"标签，但点击后历史列表没有切到对应目录，子会话依然看不到。

补充修复：

1. 新增 `viewRelatedTask(task)` 函数：目标 task 的 `directoryId / workerId` 若与当前不同，先调用 `selectDirectory` 切换上下文再 `viewTask`。
2. 两处模板（里程碑分组视图 + 平铺视图）中的"上游" tag 与子会话 popover 条目，点击绑定从 `viewTask(...)` 替换为 `viewRelatedTask(...)`。

### 效果

- 同 Worker 下跨目录转发的父子关系标签在任一目录视角下都能渲染。
- 点击"上游"或子会话条目时，历史列表自动切到对应目录，子会话不再被目录过滤遮蔽。
- 列表内容范围未扩大，不会把其他目录的会话混入当前列表。

### 验证

- 前端构建通过（`scripts/build-frontend.sh`，含 TS 类型检查）。
- 手工验证待执行：
  1. 同 Worker 跨目录转发，源目录显示"子会话 N"、目标目录显示"上游"。
  2. 点击"子会话"条目 → 列表切到目标目录，可看到子会话本身。
  3. 点击"上游" → 列表切回源目录，可看到源会话。
