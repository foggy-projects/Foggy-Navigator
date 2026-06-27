---
type: optimization
version: 1.3.1-SNAPSHOT
ticket: OPT-001
stage: 12
severity: medium
status: signed-off
owner: java-platform
created_at: 2026-06-26
---

# OPT-001 Stage 12: Codex / Codex Biz Narrow Port Bean Migration

## 文档作用

- doc_type: workitem
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 记录 Stage 12 Codex 与 Codex Biz task provider 从聚合 `TaskQueryProvider` 迁移到实际支持的窄端口 bean 的范围、计划、验证和进度。

## Background

Stage 8 已让 session 侧按 `TaskLookupProvider`、`TaskCommandProvider`、`TaskListingProvider`、`WorkerSessionQueryProvider` 四类窄端口注入 Provider。Stage 10 和 Stage 11 已分别完成 LangGraph、Gemini task lifecycle service 退出聚合 `TaskQueryProvider`。

Codex 与 Codex Biz 当前实际承担 task lookup、task command、task listing/search 三类能力，但仍直接实现聚合 `TaskQueryProvider`。由于聚合接口还继承 worker-session 端口，这两个 bean 会被 Spring 收集到 worker-session 候选集合，削弱窄端口注册边界。

## Scope

本阶段只治理 Codex / Codex Biz task provider 的注册边界：

- `CodexTaskService` 不再实现 `TaskQueryProvider`。
- `CodexTaskService` 仅实现实际支持的 `TaskLookupProvider`、`TaskCommandProvider`、`TaskListingProvider`。
- `CodexBizTaskProvider` 不再实现 `TaskQueryProvider`。
- `CodexBizTaskProvider` 仅实现实际支持的 `TaskLookupProvider`、`TaskCommandProvider`、`TaskListingProvider`。
- 保持 Codex / Codex Biz create/resume/cancel/delete/resync/rewind、lookup、listing/search、session projection、A2A abort wrapper 行为不变。
- 不改变 REST / OpenAPI / SDK payload。
- 补充单测确认 Codex / Codex Biz 不再作为 aggregate / worker-session provider 暴露。

## Non-Goals

- 不拆分 Claude 的 `TaskQueryProvider` 聚合实现。
- 不把 Codex lookup、command、listing 拆成多个物理 bean。
- 不改变 `TaskListingProvider` 的 `Object` 返回签名。
- 不删除 listing/search typed envelope 或 legacy fallback。
- 不引入 worker-session typed DTO / envelope。
- 不删除 `TaskQueryProvider` 兼容聚合接口。

## Implementation Plan

1. 将 `CodexTaskService implements TaskQueryProvider` 改为 `implements TaskLookupProvider, TaskCommandProvider, TaskListingProvider`。
2. 将 `CodexBizTaskProvider implements TaskQueryProvider` 改为 `implements TaskLookupProvider, TaskCommandProvider, TaskListingProvider`。
3. 保留 providerType、capabilities、lookup 方法、command 方法和 listing/search 方法语义。
4. 补充类型边界单测：
   - Codex task service 是 lookup / command / listing provider。
   - Codex task service 不是 aggregate / worker-session provider。
   - Codex Biz provider 是 lookup / command / listing provider。
   - Codex Biz provider 不是 aggregate / worker-session provider。
5. 运行 Codex focused regression 和 session focused regression。
6. 运行 `session-module,addons/codex-worker-agent -am` 受影响 reactor 回归。
7. 回写 execution check-in、测试证据，并评估是否进入 Stage 13。

## Acceptance Criteria

- `CodexTaskService` 不再实现 `TaskQueryProvider`。
- `CodexBizTaskProvider` 不再实现 `TaskQueryProvider`。
- 两个 Codex provider 只作为 lookup / command / listing 窄端口被注入。
- 两个 Codex provider 不再作为 worker-session provider 被注入。
- Codex / Codex Biz task create/resume/cancel/delete/resync/rewind 行为保持兼容。
- Codex / Codex Biz listing/search typed envelope 行为保持兼容。
- `CodexWorkerAgentProvider` 继续通过 `TaskLookupProvider` 构造 abort wrapper。
- session facade 可继续通过窄端口完成 Codex / Codex Biz direct create、task operation routing 和 listing/search 聚合。
- 聚焦测试和受影响 reactor 回归通过。

## Verification Plan

```powershell
mvn test -pl addons/codex-worker-agent -am "-Dtest=CodexTaskServiceTest,CodexBizTaskProviderTest,CodexStreamRelayTest,CodexWorkerAgentProviderTest,CodexWorkerA2aAgentTest,CodexWorkerFacadeImplTest,CodexWorkerClientTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn test -pl session-module,addons/codex-worker-agent -am
git diff --check
```

## Progress Tracking

### Development Progress

- [x] Stage 11 输出复核：Gemini narrow-port bean migration 已签收，Codex / Codex Biz 是下一最小风险切片。
- [x] `CodexTaskService` 从聚合接口迁移到 lookup / command / listing 窄端口。
- [x] `CodexBizTaskProvider` 从聚合接口迁移到 lookup / command / listing 窄端口。
- [x] 类型边界单测补充完成。
- [x] README / governance 回写完成。

### Testing Progress

- [x] Codex focused regression。
- [x] session focused regression。
- [x] affected reactor regression。
- [x] `git diff --check`。

### Experience Progress

- N/A。该切片为 Java 后端 Provider 注册边界收敛，未新增或修改 UI 页面、表单、按钮、权限可见性或前端交互。

## Execution Check-in

- status: completed
- completed work summary:
  - `CodexTaskService` 已从 `TaskQueryProvider` 迁移为 `TaskLookupProvider, TaskCommandProvider, TaskListingProvider`。
  - `CodexBizTaskProvider` 已从 `TaskQueryProvider` 迁移为 `TaskLookupProvider, TaskCommandProvider, TaskListingProvider`。
  - 保留 providerType、capabilities、lookup、createTaskDirect、resumeTask、cancelTask、deleteTask、resyncTask、rewindTask、listing/search 等现有语义。
  - `CodexWorkerAgentProvider` 已在 Stage 8 通过 `TaskLookupProvider` 构造 abort wrapper，本阶段无需调整。
  - `CodexTaskServiceTest#exposesOnlySupportedTaskProviderPorts` 与 `CodexBizTaskProviderTest#exposesOnlySupportedTaskProviderPorts` 已覆盖类型边界。
  - `rg "implements TaskQueryProvider"` 显示剩余生产实现仅为 Claude，session 测试 stub 保留兼容回归。
  - README、root governance、quality、coverage 和 acceptance 记录已回写。
- touched code paths:
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexBizTaskProvider.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexTaskServiceTest.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexBizTaskProviderTest.java`
  - `docs/version-tracker/1.3.1-SNAPSHOT/README.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage12-codex-narrow-port-bean.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage12-implementation-quality.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage12-coverage-audit.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage12-codex-narrow-port-bean-acceptance.md`
- self-check conclusion: `needs-formal-quality-gate`，本阶段作为正式阶段交付，已补质量门、覆盖审计和功能级验收。
- test status:
  - `mvn test -pl addons/codex-worker-agent -am "-Dtest=CodexTaskServiceTest,CodexBizTaskProviderTest,CodexStreamRelayTest,CodexWorkerAgentProviderTest,CodexWorkerA2aAgentTest,CodexWorkerFacadeImplTest,CodexWorkerClientTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：59 tests pass。
  - `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：64 tests pass。
  - `mvn test -pl session-module,addons/codex-worker-agent -am`：affected reactor pass；Surefire XML 合计 622 tests，0 failures，0 errors，0 skipped。
  - `git diff --check`：无 whitespace error，仅 CRLF normalization warnings。
- remaining risks / blockers:
  - blocking_items: none
  - `TaskCommandProvider#cancelTask` deprecated fallback 仍保留，Codex / Codex Biz 仍需实现该兼容 command，待所有 Provider command 迁移后统一删除。
  - 未新增完整 Spring ApplicationContext 启动测试断言真实 provider bean list；本阶段以类型边界测试、session registry/facade 回归和 affected reactor 保护。
  - Claude 仍实现聚合 `TaskQueryProvider`。
  - `TaskListingProvider` 仍保留 `Object` 返回签名，strictly typed method 迁移待后续阶段。
- acceptance readiness: ready-with-risks

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage12-codex-narrow-port-bean-acceptance.md
- blocking_items: none
- follow_up_required: yes
