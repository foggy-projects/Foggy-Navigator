---
type: optimization
version: 1.3.1-SNAPSHOT
ticket: OPT-001
stage: 15
severity: medium
status: signed-off
owner: java-platform
created_at: 2026-06-26
---

# OPT-001 Stage 15: WorkerSession Typed DTO / Envelope

## 文档作用

- doc_type: workitem
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 记录 Stage 15 将 worker-session 查询端口从弱类型 `Map` 主路径迁移到 typed DTO / envelope 方法的范围、计划、验证和进度。

## Background

Stage 6 到 Stage 14 已完成 task provider 窄端口拆分、provider bean 注入收窄、生产 provider 退出聚合 `TaskQueryProvider`、以及 listing/search typed method contract。

剩余高频弱类型边界集中在 `WorkerSessionQueryProvider`：

- `listWorkerSessions` 返回 `List<Map<String, Object>>`
- `getWorkerSessionMessageCount` 返回 `Map<String, Object>`
- `getWorkerSessionMessages` 返回 `List<Map<String, Object>>`
- `syncWorkerSessions` 返回 `Map<String, Object>`

这些结构目前由 `TaskDispatchFacade` 直接 fan-out 并透传给 REST controller。Stage 15 的目标是建立 typed Java SPI 主路径，同时保持 REST payload 与 legacy provider 兼容。

## Scope

本阶段只治理 worker-session 查询端口的 Java 主调用契约：

- 在 `navigator-spi` 新增 worker-session typed DTO / envelope：
  - worker session summary
  - worker session message
  - worker session message count
  - worker session sync result
- `WorkerSessionQueryProvider` 新增 typed methods，并默认适配 legacy `Map` 方法。
- `TaskDispatchFacade` worker-session provider fan-out 改为调用 typed methods。
- Claude / LangGraph worker-session provider 实现 typed override，legacy `Map` 方法保留委派兼容。
- 旧 REST controller 仍返回 `Map` / `List<Map>`，由 facade 将 typed DTO 转回原 payload 形状。
- 补充兼容测试，确认 legacy Map provider 仍可被 typed default 方法读取。

## Non-Goals

- 不改变 `/api/v1/tasks/workers/{workerId}/sessions` 等 REST API 的响应形状。
- 不修改 Python Worker 的 `/api/v1/sessions*` payload。
- 不删除 `WorkerSessionQueryProvider` 的 legacy `Map` 方法。
- 不把 legacy worker-session 方法标记 `forRemoval`，避免引入额外编译告警噪音。
- 不拆 Claude worker-session 查询的物理 bean；该项保留为后续职责隔离阶段。
- 不治理 task command `rewindTask` / `cancelTask` deprecated fallback。

## Implementation Plan

1. 新增 worker-session typed DTO / envelope records，并提供 `from(Map/Object)` 与 `toMap()` 兼容转换。
2. 在 `WorkerSessionQueryProvider` 中新增 typed methods，默认适配 legacy `Map` 方法。
3. 将 `TaskDispatchFacade` 的四处 worker-session fan-out 调用迁移到 typed methods，再转回 legacy REST payload。
4. 将 Claude / LangGraph worker-session provider 的实现改为 typed override，legacy `Map` 方法委派 typed 方法。
5. 补充/调整回归测试：
   - session facade worker-session 委派走 typed methods。
   - legacy Map provider 通过 typed default adapter。
   - LangGraph worker-session service 直接断言 typed DTO / envelope。
   - Claude provider 类型边界继续保持。
6. 运行 targeted regression、affected reactor、静态扫描与 diff check。

## Acceptance Criteria

- `WorkerSessionQueryProvider` 提供 typed worker-session 查询主方法。
- `TaskDispatchFacade` provider fan-out 不再直接调用 legacy `Map` worker-session 方法。
- Claude / LangGraph worker-session provider 暴露 typed override。
- legacy `Map` 方法仍可用于旧调用方，并委派到 typed 方法。
- 旧 Provider 返回 Map 时，typed default adapter 仍能读取 session、message、count 和 sync result。
- REST / OpenAPI / SDK worker-session 响应保持兼容。
- targeted regression 与 affected reactor 回归通过。
- 静态扫描确认生产 fan-out 不再直接调用旧 worker-session `Map` 方法。

## Verification Plan

```powershell
mvn test -pl session-module,addons/claude-worker-agent,addons/langgraph-biz-worker -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest,ClaudeTaskServiceAuthTest,LanggraphWorkerSessionQueryServiceTest,LanggraphTaskServiceTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn test -pl session-module,addons/claude-worker-agent,addons/langgraph-biz-worker -am
rg -n "provider\.(listWorkerSessions|getWorkerSessionMessageCount|getWorkerSessionMessages|syncWorkerSessions)\(" session-module/src/main/java addons/claude-worker-agent/src/main/java addons/langgraph-biz-worker/src/main/java navigator-spi/src/main/java
git diff --check
```

## Progress Tracking

### Development Progress

- [x] worker-session typed DTO / envelope records 新增完成。
- [x] `WorkerSessionQueryProvider` typed methods 新增完成。
- [x] `TaskDispatchFacade` worker-session fan-out 迁移到 typed methods。
- [x] Claude / LangGraph worker-session provider typed override 完成。
- [x] session facade、legacy compatibility、LangGraph worker-session 测试更新完成。
- [x] README / governance 回写完成。

### Testing Progress

- [x] Stage 15 targeted regression。
- [x] Stage 15 affected reactor regression。
- [x] Stage 15 static scan。
- [x] `git diff --check`。

### Experience Progress

- N/A。该切片为 Java 后端 SPI 契约收敛，未新增或修改 UI 页面、表单、按钮、权限可见性或前端交互。

## Execution Check-in

- status: completed
- completed work summary: 新增 WorkerSessionSummary、WorkerSessionMessage、WorkerSessionMessageCount、WorkerSessionSyncResult typed records；`WorkerSessionQueryProvider` 增加 typed default methods；`TaskDispatchFacade` worker-session fan-out 迁移到 typed methods；Claude / LangGraph provider 实现 typed override，legacy Map 方法保留委派兼容；补充 facade typed path、legacy default adapter 与 LangGraph typed provider 回归。
- touched code paths: `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/*WorkerSession*.java`、`session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`、`addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`、`addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphWorkerSessionQueryService.java` 及对应测试。
- test status: targeted regression 114 tests pass；affected direct reactor 208 reports / 1461 tests pass；broader Java worker reactor 221 reports / 1535 tests pass；static scan 无生产 fan-out legacy worker-session provider 调用；`git diff --check` 无 whitespace error。
- remaining risks / blockers: 无阻断项；legacy Map 方法和 REST payload 仍保留；Claude worker-session 查询仍在 `ClaudeTaskService` 物理 bean 内；未运行仓库级全量 `mvn test`。
- acceptance readiness: ready；质量门、覆盖审计和功能级验收均已签收，结论为 accepted-with-risks。

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage15-worker-session-typed-envelope-acceptance.md
- blocking_items: none
- follow_up_required: yes
