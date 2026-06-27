---
type: optimization
version: 1.3.1-SNAPSHOT
ticket: OPT-001
stage: 16
severity: medium
status: signed-off
owner: java-platform
created_at: 2026-06-26
---

# OPT-001 Stage 16: Claude WorkerSession Provider Bean Split

## 文档作用

- doc_type: workitem
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 记录 Stage 16 将 Claude worker-session 查询能力从 `ClaudeTaskService` 物理 bean 拆到独立 `WorkerSessionQueryProvider` bean 的范围、计划、验证和进度。

## Background

Stage 13 已让 `ClaudeTaskService` 退出聚合 `TaskQueryProvider`，但它仍同时作为 task lookup、task command、task listing 和 worker-session provider 注册。Stage 15 已将 worker-session 主方法切到 typed DTO / envelope，下一步可以安全收敛 Claude 的物理 bean 职责边界。

## Scope

本阶段只治理 Claude worker-session 查询的物理 bean 边界：

- 新增独立 `ClaudeWorkerSessionQueryService implements WorkerSessionQueryProvider`。
- `ClaudeTaskService` 不再实现 `WorkerSessionQueryProvider`，也不再声明 worker-session capabilities。
- worker-session 查询、消息、计数、sync SPI 方法迁移到新 service。
- `ClaudeTaskService.syncLocalSessions(...)` 暂时保留为 sync 落库复用点，避免在本阶段重写任务投影逻辑。
- 补充类型边界和 worker-session provider 回归测试。

## Non-Goals

- 不改变 `/api/v1/tasks/workers/{workerId}/sessions*` REST API 响应形状。
- 不改变 Python Worker `/api/v1/sessions*` payload。
- 不重写 `syncLocalSessions` 的本地任务投影逻辑。
- 不删除 legacy worker-session Map 方法。
- 不治理 legacy listing / worker-session 方法 deprecation/removal。
- 不治理 deprecated task command fallback。

## Implementation Plan

1. 新增 `ClaudeWorkerSessionQueryService`，实现 Claude worker-session typed/legacy methods 和 worker-session capabilities。
2. 从 `ClaudeTaskService` 移除 `WorkerSessionQueryProvider` 实现、worker-session capabilities 和 SPI 方法。
3. 保持 `ClaudeTaskService.syncLocalSessions(...)` public 兼容，供新 worker-session service sync path 复用。
4. 更新 `ClaudeTaskServiceAuthTest` 类型边界断言。
5. 新增 `ClaudeWorkerSessionQueryServiceTest` 覆盖 capabilities、list/count/messages/sync 和 user ownership。
6. 运行 targeted regression、affected reactor、静态扫描与 diff check。

## Acceptance Criteria

- `ClaudeTaskService` 不再是 `WorkerSessionQueryProvider` bean。
- `ClaudeTaskService#getCapabilities()` 不再包含 worker-session capabilities。
- 新 `ClaudeWorkerSessionQueryService` 独立声明 `LIST_WORKER_SESSIONS`、`GET_WORKER_SESSION_MESSAGE_COUNT`、`GET_WORKER_SESSION_MESSAGES`、`SYNC_WORKER_SESSIONS`。
- Claude worker-session REST 兼容路径仍可通过 provider registry 找到 `claude-worker` worker-session provider。
- worker-session typed DTO / envelope 返回保持 Stage 15 兼容行为。
- targeted regression 与 affected reactor 回归通过。
- 静态扫描确认 Claude task lifecycle service 不再声明 worker-session provider 端口。

## Verification Plan

```powershell
mvn test -pl session-module,addons/claude-worker-agent -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest,ClaudeTaskServiceAuthTest,ClaudeWorkerSessionQueryServiceTest,ClaudeTaskServiceSyncTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn test -pl session-module,addons/claude-worker-agent -am
rg -n "WorkerSessionQueryProvider|LIST_WORKER_SESSIONS|GET_WORKER_SESSION_MESSAGE_COUNT|GET_WORKER_SESSION_MESSAGES|SYNC_WORKER_SESSIONS" addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java
git diff --check
```

## Progress Tracking

### Development Progress

- [x] `ClaudeWorkerSessionQueryService` 新增完成。
- [x] `ClaudeTaskService` worker-session 端口和 capabilities 移除完成。
- [x] Claude worker-session provider 测试新增完成。
- [x] Claude task service 类型边界测试更新完成。
- [x] README / governance 回写完成。

### Testing Progress

- [x] Stage 16 targeted regression。
- [x] Stage 16 affected reactor regression。
- [x] Stage 16 static scan。
- [x] `git diff --check`。

### Experience Progress

- N/A。该切片为 Java 后端 SPI bean 职责拆分，未新增或修改 UI 页面、表单、按钮、权限可见性或前端交互。

## Execution Check-in

- status: completed
- completed work summary: 已新增独立 `ClaudeWorkerSessionQueryService implements WorkerSessionQueryProvider`，迁移 Claude worker-session list/count/messages/sync typed 与 legacy SPI 方法；`ClaudeTaskService` 不再实现 worker-session 端口，也不再声明 worker-session capabilities，保留 `syncLocalSessions(...)` 作为本地任务投影复用点。
- touched code paths:
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeWorkerSessionQueryService.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
  - `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/ClaudeWorkerSessionQueryServiceTest.java`
  - `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/ClaudeTaskServiceAuthTest.java`
  - `docs/version-tracker/1.3.1-SNAPSHOT/README.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
- test status:
  - targeted regression: 100 tests pass.
  - affected reactor: `mvn test -pl session-module,addons/claude-worker-agent -am` pass；Surefire XML 合计 183 reports / 1328 tests，0 failures，0 errors，0 skipped。
  - static scan: `ClaudeTaskService.java` 内 worker-session provider 关键词无匹配。
  - `git diff --check`: pass，仅 CRLF normalization warnings。
- remaining risks / blockers: 无阻断项；`syncLocalSessions(...)` 仍保留在 `ClaudeTaskService` 内作为 sync 投影复用点，legacy worker-session Map 方法仍为兼容保留，仓库级根目录全量 `mvn test` 未运行。
- acceptance readiness: ready-with-risks

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage16-claude-worker-session-bean-acceptance.md
- blocking_items: none
- follow_up_required: yes
