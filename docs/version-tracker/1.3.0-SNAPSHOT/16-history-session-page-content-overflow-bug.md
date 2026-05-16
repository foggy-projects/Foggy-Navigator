---
type: bug
bug_source: user-report
version: 1.3.0-SNAPSHOT
ticket: BUG-022
severity: major
status: ready-for-verification
reproduction_status: confirmed
test_strategy: unit-test
automation_decision: required
owner: session-module,navigator-frontend
---

# BUG Work Item

## Background

用户在 Navigator 历史会话面板发现两个问题：

- 点击历史会话时，`/api/v1/tasks/page` 可能出现相同参数的重复请求。
- 请求 `size=100` 时，后端响应 `content` 远超过 100 条，例如 `totalSessions=174` 但 `content` 返回 1700+ 条。

## Reproduction

复现状态：`confirmed`

1. 打开 Navigator Worker 页面。
2. 历史会话筛选为 `AWAITING_REPLY` 或默认历史筛选。
3. 观察网络请求 `/api/v1/tasks/page?page=0&size=100&state=AWAITING_REPLY`。
4. 响应中 `content.length` 明显大于请求的 `size`。

## Expected vs Actual

Expected:

- 分页接口按会话分页时，`content` 也应保持每个会话一条摘要记录，长度不超过 `size`。
- 历史列表仍能显示轮数、首条提示、总成本、token 摘要。
- 相同参数的并发分页请求应复用同一个 in-flight 请求。

Actual:

- 后端先按 session 分页，再将每个 session 下所有 task 全量展开到 `content`。
- 长会话导致历史面板一次拉取大量 task，网络响应过大。
- 前端多个刷新入口并发时可能重复请求同一页。

## Impact Scope

- `session-module` 统一任务分页接口
- Navigator 主前端历史会话面板
- 待回复任务批量加载链路

## Test Strategy

- 后端单测覆盖：同一 session 多个 task 时，分页接口只返回一个摘要 DTO，且摘要字段正确。
- 前端通过类型与现有历史面板测试覆盖摘要字段兼容。

## Code Inventory

- `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
- `navigator-common/src/main/java/com/foggy/navigator/common/dto/DispatchTaskDTO.java`
- `packages/navigator-frontend/src/api/unifiedTask.ts`
- `packages/navigator-frontend/src/views/ClaudeWorkerView.vue`
- `packages/navigator-frontend/src/types/index.ts`

## Fix Checklist

- [x] 后端分页 content 改为每个会话一条摘要记录。
- [x] DTO 增加会话摘要字段。
- [x] 前端历史分组读取摘要字段，保持轮数/成本/首条提示显示。
- [x] 前端统一分页 API 增加并发请求去重。
- [x] 运行后端与前端相关测试。

## Verification

- `mvn -pl session-module -am -Dtest=TaskDispatchFacadeTest '-Dsurefire.failIfNoSpecifiedTests=false' test` 通过。
- `pnpm --dir packages/navigator-frontend test -- src/__tests__/useClaudeWorker.test.ts src/views/__tests__/ClaudeWorkerView.integration.test.ts` 通过。
- `pnpm --dir packages/navigator-frontend run type-check` 通过。
