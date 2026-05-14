---
type: bug
bug_source: user-report
version: 1.3.0-SNAPSHOT
ticket: BUG-021
severity: minor
status: ready-for-verification
reproduction_status: confirmed
test_strategy: integration-test
automation_decision: required
owner: navigator-frontend
---

# BUG Work Item

## Background

用户在 Navigator 右侧「历史会话」面板反馈：此前已把历史会话分页调整为每页 120 条，但日常停留在默认筛选「待回复」+「处理中」时，记录数明显未到 120 就出现分页。

从截图和代码链路确认，问题发生在 Navigator 主前端历史侧栏，不属于 TMS 租户端业务页面。

## Reproduction

复现状态：`confirmed`

1. 打开 Navigator 主前端的 Worker 会话页。
2. 右侧历史会话保持默认筛选：「待回复」+「处理中」。
3. 当前可见会话数少于 120。
4. 观察底部仍可能出现分页，并且前端请求实际使用较小页大小。

## Expected vs Actual

Expected:

- Navigator 历史会话默认每页按 120 个会话分页。
- 全局历史和目录历史使用一致的默认页大小。
- 「待回复」+「处理中」筛选下，未超过 120 个匹配会话时不应出现下一页。

Actual:

- 全局历史会话的前端状态 `taskSize` 仍初始化为 `20`。
- 目录历史会话的前端状态 `dirTaskSize` 仍初始化为 `20`。
- 筛选切换和翻页会沿用该较小页大小重新查询。

## Impact Scope

- Navigator 前端历史会话面板
- 全局 Worker 历史分页
- 目录内历史分页

## Test Strategy

- `test_strategy`: `integration-test`
- `automation_decision`: `required`

覆盖方式：

- composable 单测验证默认 `listTasksPagedUnified` 请求使用 120。
- 视图集成测试验证目录历史请求使用 120，并保留默认状态筛选参数。

## Code Inventory

- `packages/navigator-frontend/src/composables/useClaudeWorker.ts`
- `packages/navigator-frontend/src/views/ClaudeWorkerView.vue`
- `packages/navigator-frontend/src/__tests__/useClaudeWorker.test.ts`
- `packages/navigator-frontend/src/views/__tests__/ClaudeWorkerView.integration.test.ts`

## Fix Checklist

- [x] 定义统一默认历史分页大小 `DEFAULT_TASK_PAGE_SIZE = 120`。
- [x] 全局历史 `taskSize` 使用该默认值。
- [x] 目录历史 `dirTaskSize` 使用该默认值。
- [x] 更新 composable 单测断言。
- [x] 补充目录历史集成测试断言。

## Verification

- `pnpm --dir D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend test -- src/__tests__/useClaudeWorker.test.ts`
- `pnpm --dir D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend test -- src/views/__tests__/ClaudeWorkerView.integration.test.ts`

当前状态：代码已修复并通过相关前端自动化测试，待在 Navigator 页面刷新后复验。
