---
type: optimization
version: 1.3.1-SNAPSHOT
ticket: OPT-001
stage: 13
severity: medium
status: signed-off
owner: java-platform
created_at: 2026-06-26
---

# OPT-001 Stage 13: Claude Narrow Port Bean Migration

## 文档作用

- doc_type: workitem
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 记录 Stage 13 Claude task provider 从聚合 `TaskQueryProvider` 迁移到实际支持的窄端口 bean 的范围、计划、验证和进度。

## Background

Stage 8 已让 session 侧按 `TaskLookupProvider`、`TaskCommandProvider`、`TaskListingProvider`、`WorkerSessionQueryProvider` 四类窄端口注入 Provider。Stage 10、Stage 11、Stage 12 已分别完成 LangGraph、Gemini、Codex / Codex Biz task lifecycle service 退出聚合 `TaskQueryProvider`。

Claude 当前是生产代码中最后一个直接实现聚合 `TaskQueryProvider` 的 provider。与 Gemini、Codex 不同，Claude task service 当前仍实际承接 worker-session 查询能力，因此本阶段只消除聚合接口暴露，不拆分 worker-session 物理 bean。

## Scope

本阶段只治理 Claude task provider 的注册边界：

- `ClaudeTaskService` 不再实现 `TaskQueryProvider`。
- `ClaudeTaskService` 显式实现实际支持的 `TaskLookupProvider`、`TaskCommandProvider`、`TaskListingProvider`、`WorkerSessionQueryProvider`。
- 保持 Claude create/resume/cancel/delete/respond/reconnect/resync/rewind、lookup、listing/search、worker-session 查询、session projection、A2A abort wrapper 行为不变。
- 不改变 REST / OpenAPI / SDK payload。
- 补充单测确认 Claude 不再作为 aggregate provider 暴露，同时继续作为 worker-session provider 暴露。

## Non-Goals

- 不把 Claude worker-session 查询拆成独立物理 bean。
- 不改变 `TaskListingProvider` 的 `Object` 返回签名。
- 不删除 listing/search typed envelope 或 legacy fallback。
- 不引入 worker-session typed DTO / envelope。
- 不删除 `TaskQueryProvider` 兼容聚合接口。
- 不删除 `TaskCommandProvider#cancelTask` deprecated fallback。

## Implementation Plan

1. 将 `ClaudeTaskService implements TaskQueryProvider` 改为 `implements TaskLookupProvider, TaskCommandProvider, TaskListingProvider, WorkerSessionQueryProvider`。
2. 保留 providerType、capabilities、lookup 方法、command 方法、listing/search 方法和 worker-session 查询方法语义。
3. 补充类型边界单测：
   - Claude task service 是 lookup / command / listing / worker-session provider。
   - Claude task service 不是 aggregate `TaskQueryProvider`。
4. 运行 Claude focused regression 和 session focused regression。
5. 运行 `session-module,addons/claude-worker-agent -am` 受影响 reactor 回归。
6. 回写 execution check-in、测试证据，并评估下一阶段。

## Acceptance Criteria

- `ClaudeTaskService` 不再实现 `TaskQueryProvider`。
- `ClaudeTaskService` 作为 lookup / command / listing / worker-session 窄端口被注入。
- Claude task create/resume/cancel/delete/respond/reconnect/resync/rewind 行为保持兼容。
- Claude listing/search typed envelope 行为保持兼容。
- Claude worker-session list/count/messages/sync 行为保持兼容。
- `ClaudeWorkerAgentProvider` 继续通过 `TaskLookupProvider` 构造 abort wrapper。
- session facade 可继续通过窄端口完成 Claude direct create、task operation routing、listing/search 聚合和 worker-session 查询。
- 聚焦测试和受影响 reactor 回归通过。
- 生产代码中不再有 `implements TaskQueryProvider`。

## Verification Plan

```powershell
mvn test -pl addons/claude-worker-agent -am "-Dtest=ClaudeTaskService*Test,WorkerStreamRelayTest,ClaudeWorkerAgentProviderTest,ClaudeWorkerA2aAgentTest,ClaudeWorkerClientTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn test -pl session-module,addons/claude-worker-agent -am
git diff --check
rg -n "implements TaskQueryProvider" addons navigator-spi session-module
```

## Progress Tracking

### Development Progress

- [x] Stage 12 输出复核：Codex / Codex Biz narrow-port bean migration 已签收，Claude 是最后一个生产聚合实现。
- [x] `ClaudeTaskService` 从聚合接口迁移到 lookup / command / listing / worker-session 窄端口。
- [x] 类型边界单测补充完成。
- [x] README / governance 回写完成。

### Testing Progress

- [x] Claude focused regression。
- [x] session focused regression。
- [x] affected reactor regression。
- [x] `git diff --check`。
- [x] `implements TaskQueryProvider` 静态扫描。

### Experience Progress

- N/A。该切片为 Java 后端 Provider 注册边界收敛，未新增或修改 UI 页面、表单、按钮、权限可见性或前端交互。

## Execution Check-in

- status: completed
- completed work summary:
  - `ClaudeTaskService` 已从 `TaskQueryProvider` 迁移为 `TaskLookupProvider, TaskCommandProvider, TaskListingProvider, WorkerSessionQueryProvider`。
  - 保留 providerType、capability、lookup、create/resume/cancel/delete/respond/reconnect/resync/rewind、listing/search typed envelope、worker-session 查询和 session projection 语义。
  - `ClaudeWorkerAgentProvider` 已在 Stage 8 通过 `TaskLookupProvider` 构造 abort wrapper，本阶段无需调整。
  - `ClaudeTaskServiceAuthTest#exposesOnlySupportedTaskProviderPorts` 已覆盖类型边界。
  - `rg "implements TaskQueryProvider"` 显示生产代码已无聚合实现，仅 session 测试 stub 保留兼容回归。
  - README、root governance、quality、coverage 和 acceptance 记录已回写。
- touched code paths:
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
  - `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/ClaudeTaskServiceAuthTest.java`
  - `docs/version-tracker/1.3.1-SNAPSHOT/README.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage13-claude-narrow-port-bean.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage13-implementation-quality.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage13-coverage-audit.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage13-claude-narrow-port-bean-acceptance.md`
- test status:
  - `mvn test -pl addons/claude-worker-agent -am "-Dtest=ClaudeTaskService*Test,WorkerStreamRelayTest,ClaudeWorkerAgentProviderTest,ClaudeWorkerA2aAgentTest,ClaudeWorkerClientTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：90 tests pass。
  - `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：64 tests pass。
  - `mvn test -pl session-module,addons/claude-worker-agent -am`：affected reactor pass；Surefire XML 合计 1320 tests，0 failures，0 errors，0 skipped。
  - `git diff --check`：无 whitespace error，仅 CRLF normalization warnings。
  - `rg -n "implements TaskQueryProvider" addons navigator-spi session-module`：生产代码已无聚合实现，仅 `TaskQueryProviderRegistryTest` 保留兼容 stub。
- remaining risks / blockers:
  - blocking_items: none
  - Claude worker-session 查询仍在 task service 物理 bean 内，后续如需更强职责隔离，应另起阶段拆分。
  - `TaskListingProvider` 仍保留 `Object` 返回签名，strictly typed method 迁移待后续阶段。
  - `TaskCommandProvider#cancelTask` deprecated fallback 仍保留，待所有 Provider command 迁移后统一删除。
  - worker-session payload 仍为 Map，typed DTO / envelope 待后续阶段。
  - 未新增完整 Spring ApplicationContext 启动测试断言真实 provider bean list；本阶段以类型边界测试、session registry/facade 回归、静态扫描和 affected reactor 保护。
- acceptance readiness: ready-with-risks

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage13-claude-narrow-port-bean-acceptance.md
- blocking_items: none
- follow_up_required: yes
