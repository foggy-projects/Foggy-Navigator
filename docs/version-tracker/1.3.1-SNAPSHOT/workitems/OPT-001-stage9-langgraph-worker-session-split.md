---
type: optimization
version: 1.3.1-SNAPSHOT
ticket: OPT-001
stage: 9
severity: medium
status: signed-off
owner: java-platform
created_at: 2026-06-25
---

# OPT-001 Stage 9: LangGraph Worker Session Port Split

## 文档作用

- doc_type: workitem
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 记录 Stage 9 LangGraph worker-session 查询端口拆分的范围、计划、验收标准、测试证据和签收状态。

## Background

Stage 8 已让 session 侧 `TaskDispatchFacade` 和 `TaskQueryProviderRegistry` 按 lookup / command / listing / worker-session 四类窄端口接收 Provider 集合，但 LangGraph 侧仍由 `LanggraphTaskService` 这个任务生命周期服务直接实现 worker-session 查询端点：

- `listWorkerSessions`
- `getWorkerSessionMessageCount`
- `getWorkerSessionMessages`
- `syncWorkerSessions`

这使 LangGraph task create/resume/cancel/state projection 与 worker-session 查询、session ownership 校验、message projection 混在同一个服务中。后续如果继续拆独立 Provider bean，LangGraph 是最适合先落地的试点。

## Scope

本阶段只治理 LangGraph worker-session 查询端口：

- 新增独立 `LanggraphWorkerSessionQueryService implements WorkerSessionQueryProvider`。
- 将 worker-session list/count/messages/sync 逻辑从 `LanggraphTaskService` 迁出。
- 将 worker-session capability 声明从 `LanggraphTaskService` 移到新服务。
- 保持外部返回 Map 字段和异常语义兼容。
- 保持 LangGraph task create/resume/cancel、task state schema、SSE 和 controller 路径不变。
- 补充或迁移现有 worker-session 单测，确认新服务行为不变。
- 补 session fan-out 回归，确认独立 worker-session provider bean 可被 `TaskDispatchFacade` 使用。

## Non-Goals

- 不拆分 LangGraph task lifecycle service 的 create/resume/cancel 主链路。
- 不改变 `WorkerSessionQueryProvider` SPI 方法签名。
- 不将 worker-session Map payload 改为 typed DTO。
- 不改变前端、OpenAPI 或 SDK 对外字段。
- 不处理 Claude/Codex/Gemini 的独立窄端口 bean 迁移。
- 不删除 `TaskQueryProvider` 兼容聚合接口。

## Review Findings

| Finding | Risk | Planned action |
| --- | --- | --- |
| `LanggraphTaskService` 同时承担 task lifecycle 与 worker-session 查询 | 服务职责继续膨胀，Stage 8 的独立 worker-session port 没有真实落地样例 | 新增 `LanggraphWorkerSessionQueryService` 承接 worker-session 端点 |
| worker-session capability 仍由 task service 声明 | session fan-out 仍会把 worker-session 请求路由到宽 task service | capability 移到新 `WorkerSessionQueryProvider` bean |
| ownership 校验和 Map projection 是私有 helper | 后续复用、测试和拆分困难 | 将相关 helper 与单测一起迁移到新 service |
| 现有测试在 `LanggraphTaskServiceTest.WorkerSessions` 内 | 测试边界跟服务职责不一致 | 迁移为 `LanggraphWorkerSessionQueryServiceTest`，保留 behavior assertions |

## Implementation Plan

1. 新增 `LanggraphWorkerSessionQueryService`，注入 `LanggraphWorkerService`、`SessionTaskRepository`、`SessionMessageRepository`。
2. 从 `LanggraphTaskService` 移除 worker-session SPI 方法和相关 helper；保留仍被 recent conversation / prompt 持久化主链路使用的 `SessionMessageRepository` 依赖。
3. 调整 LangGraph capability：
   - `LanggraphTaskService` 只声明 create/cancel/delete 等任务操作 capability。
   - 新 service 声明 worker-session 四类 capability。
4. 将 `LanggraphTaskServiceTest.WorkerSessions` 行为迁移为 `LanggraphWorkerSessionQueryServiceTest`。
5. 补 session fan-out 测试，确认独立 `WorkerSessionQueryProvider` 可被 `TaskDispatchFacade` 命中。
6. 执行定向和受影响 reactor 回归。
7. 回写 execution check-in、实现质量门、覆盖审计和功能级验收。

## Acceptance Criteria

- `LanggraphTaskService` 不再实现 worker-session 查询端点，也不再声明 worker-session capabilities。
- `LanggraphWorkerSessionQueryService` 作为独立 `WorkerSessionQueryProvider` bean 提供 list/count/messages/sync 能力。
- worker-session 返回字段保持兼容：`session_id/sessionId`、`worker_id/workerId`、`latest_task_id/taskId`、message `role/content/timestamp/taskId` 等不变。
- worker ownership 和 session ownership 校验语义保持不变。
- `TaskDispatchFacade` 可通过独立 worker-session port 调用 LangGraph worker-session 能力。
- LangGraph focused tests、session fan-out regression 和受影响 Java reactor 回归通过。

## Verification Plan

```powershell
mvn test -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphWorkerSessionQueryServiceTest,LanggraphTaskServiceTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn test -pl session-module,addons/langgraph-biz-worker -am
```

如上述修改触及 SPI / provider injection 兼容边界，再补跑：

```powershell
mvn test -pl navigator-spi,session-module,addons/langgraph-biz-worker -am
```

## Progress

- 2026-06-25: 子计划创建，范围限定为 LangGraph worker-session 查询端口拆分。
- 2026-06-25: 新增 `LanggraphWorkerSessionQueryService implements WorkerSessionQueryProvider`，承接 worker-session list/count/messages/sync。
- 2026-06-25: `LanggraphTaskService` 移除 worker-session SPI 方法和 worker-session capability 声明，保留 task lifecycle capability。
- 2026-06-25: `LanggraphTaskServiceTest.WorkerSessions` 迁移为 `LanggraphWorkerSessionQueryServiceTest`，新增 provider capability 和 worker ownership 回归。
- 2026-06-25: `TaskDispatchFacadeTest` 新增独立 `WorkerSessionQueryProvider` list 接入回归。
- 2026-06-25: LangGraph focused regression、session focused regression、affected reactor、质量门、覆盖审计和功能级验收签收已完成。

## Execution Check-in

Review 发现：

- `LanggraphTaskService` 原先同时承载 task lifecycle 与 worker-session 查询端点，服务职责继续膨胀。
- worker-session capability 仍由 task service 声明，Stage 8 的 worker-session 窄端口没有真实独立 Provider 样例。
- worker-session ownership 校验和 Map projection helper 私有在 task service 中，测试也嵌在 `LanggraphTaskServiceTest.WorkerSessions`。

已完成：

- 新增 `LanggraphWorkerSessionQueryService`，注入 `LanggraphWorkerService`、`SessionTaskRepository`、`SessionMessageRepository`，独立实现 `WorkerSessionQueryProvider`。
- `LanggraphWorkerSessionQueryService` 声明 `LIST_WORKER_SESSIONS`、`GET_WORKER_SESSION_MESSAGE_COUNT`、`GET_WORKER_SESSION_MESSAGES`、`SYNC_WORKER_SESSIONS` 四类 capability。
- `LanggraphTaskService` 删除 worker-session list/count/messages/sync 方法和相关 helper，capability 收窄为 create/cancel/delete。
- 保持 worker-session Map payload 字段兼容，包括 `session_id/sessionId`、`worker_id/workerId`、`latest_task_id/taskId`、message `role/content/timestamp/taskId`。
- `LanggraphTaskServiceTest.WorkerSessions` 迁移为 `LanggraphWorkerSessionQueryServiceTest`，并补充独立 provider capability 与跨用户 worker 拒绝测试。
- `TaskDispatchFacadeTest` 新增只实现 `WorkerSessionQueryProvider` 的 provider 回归，确认 facade 可脱离 `TaskQueryProvider` 聚合接口调用 worker-session list。

测试证据：

- `mvn test -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphWorkerSessionQueryServiceTest,LanggraphTaskServiceTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：26 tests pass。
- `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：64 tests pass。
- `mvn test -pl session-module,addons/langgraph-biz-worker -am`：affected reactor 通过，Surefire XML 合计 162 reports / 1148 tests，0 failures，0 errors，0 skipped。
- `git diff --check`：无 whitespace error，仅 CRLF normalization warnings。

质量与覆盖：

- 实现质量门见 `quality/OPT-001-stage9-implementation-quality.md`，decision=`ready-with-risks`。
- 测试覆盖审计见 `coverage/OPT-001-stage9-coverage-audit.md`，conclusion=`ready-with-gaps`，can_enter_acceptance=`yes`。

签收结论：

- 验收记录见 `acceptance/OPT-001-stage9-langgraph-worker-session-split-acceptance.md`。
- acceptance_decision=`accepted-with-risks`，无阻断项。

剩余风险：

- 当前仅 LangGraph worker-session 已拆成独立 provider；其他 Provider 仍主要通过聚合 `TaskQueryProvider` 兼容四类端口。
- 当前缺少专门的 Spring ApplicationContext 启动测试验证两个 LangGraph provider bean 的真实注入列表；受影响 reactor 已覆盖编译和模块回归。
- worker-session payload 仍是 Map，typed DTO / envelope 不在本阶段范围。

下一步：

- 建议进入 Stage 10：优先推进 Provider 独立窄端口 bean 迁移，或规划 `TaskListingProvider` strictly typed method 兼容迁移；如继续收敛 worker-session contract，可另起 typed worker-session DTO / envelope。

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-25
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage9-langgraph-worker-session-split-acceptance.md
- blocking_items: none
- follow_up_required: yes
