---
type: optimization
version: 1.3.1-SNAPSHOT
ticket: OPT-001
stage: 11
severity: medium
status: signed-off
owner: java-platform
created_at: 2026-06-26
---

# OPT-001 Stage 11: Gemini Narrow Port Bean Migration

## 文档作用

- doc_type: workitem
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 记录 Stage 11 Gemini task provider 从聚合 `TaskQueryProvider` 迁移到实际支持的窄端口 bean 的范围、计划、验证和进度。

## Background

Stage 8 已让 session 侧按 `TaskLookupProvider`、`TaskCommandProvider`、`TaskListingProvider`、`WorkerSessionQueryProvider` 四类窄端口注入 Provider。Stage 10 已完成 LangGraph task lifecycle service 退出聚合 `TaskQueryProvider`。

Gemini task service 当前只承担 task lookup 与 task command 能力，但仍直接实现聚合 `TaskQueryProvider`。由于聚合接口继承 listing 与 worker-session 端口，Gemini 会继续被 Spring 收集到它并不支持的 listing / worker-session 列表，削弱窄端口注册边界。

## Scope

本阶段只治理 Gemini task provider 的注册边界：

- `GeminiTaskService` 不再实现 `TaskQueryProvider`。
- `GeminiTaskService` 仅实现实际支持的 `TaskLookupProvider` 与 `TaskCommandProvider`。
- 保持 Gemini create/resume/cancel/delete、lookup、session projection 和 A2A abort wrapper 行为不变。
- 不改变 REST / OpenAPI / SDK payload。
- 补充单测确认 Gemini task service 不再作为 listing / worker-session / aggregate provider 暴露。

## Non-Goals

- 不拆分 Claude/Codex/Codex Biz 的 `TaskQueryProvider` 聚合实现。
- 不把 Gemini lookup 与 command 拆成两个物理 bean。
- 不改变 `TaskListingProvider` 的 `Object` 返回签名。
- 不引入 worker-session typed DTO / envelope。
- 不删除 `TaskQueryProvider` 兼容聚合接口。

## Implementation Plan

1. 将 `GeminiTaskService implements TaskQueryProvider` 改为 `implements TaskLookupProvider, TaskCommandProvider`。
2. 保留当前 providerType、capabilities、lookup 方法和 command 方法语义。
3. 补充类型边界单测：
   - task service 是 `TaskLookupProvider`。
   - task service 是 `TaskCommandProvider`。
   - task service 不是 `TaskQueryProvider`。
   - task service 不是 `TaskListingProvider`。
   - task service 不是 `WorkerSessionQueryProvider`。
4. 运行 Gemini focused regression 和 session focused regression。
5. 运行 `session-module,addons/gemini-worker-agent -am` 受影响 reactor 回归。
6. 回写 execution check-in、测试证据，并评估是否进入 Stage 12。

## Acceptance Criteria

- `GeminiTaskService` 不再实现 `TaskQueryProvider`。
- `GeminiTaskService` 只作为 lookup / command 窄端口被注入。
- Gemini task create/resume/cancel/delete 行为保持兼容。
- `GeminiWorkerAgentProvider` 继续通过 `TaskLookupProvider` 构造 abort wrapper。
- session facade 可继续通过窄端口完成 Gemini direct create 和 task operation routing。
- 聚焦测试和受影响 reactor 回归通过。

## Verification Plan

```powershell
mvn test -pl addons/gemini-worker-agent -am "-Dtest=GeminiTaskServiceAuthResolutionTest,GeminiStreamRelayTest,GeminiWorkerAgentProviderTest,GeminiWorkerClientTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn test -pl session-module,addons/gemini-worker-agent -am
```

## Progress Tracking

### Development Progress

- [x] Stage 10 输出复核：LangGraph narrow-port bean migration 已签收，Gemini 是下一最小风险切片。
- [x] `GeminiTaskService` 从聚合接口迁移到 lookup / command 窄端口。
- [x] 类型边界单测补充完成。
- [x] README / governance 回写完成。

### Testing Progress

- [x] Gemini focused regression。
- [x] session focused regression。
- [x] affected reactor regression。
- [x] `git diff --check`。

### Experience Progress

- N/A。该切片为 Java 后端 Provider 注册边界收敛，未新增或修改 UI 页面、表单、按钮、权限可见性或前端交互。

## Execution Check-in

- status: completed
- completed work summary:
  - `GeminiTaskService` 已从 `TaskQueryProvider` 迁移为 `TaskLookupProvider, TaskCommandProvider`。
  - 保留 providerType、capabilities、lookup、createTaskDirect、resumeTask、cancelTask、deleteTask 等现有语义。
  - `GeminiWorkerAgentProvider` 已在 Stage 8 通过 `TaskLookupProvider` 构造 abort wrapper，本阶段无需调整。
  - `GeminiTaskServiceAuthResolutionTest#exposesOnlySupportedTaskProviderPorts` 已覆盖类型边界，确认 Gemini task service 不再作为 aggregate/listing/worker-session provider 暴露。
  - README、root governance、quality、coverage 和 acceptance 记录已回写。
- touched code paths:
  - `addons/gemini-worker-agent/src/main/java/com/foggy/navigator/gemini/worker/service/GeminiTaskService.java`
  - `addons/gemini-worker-agent/src/test/java/com/foggy/navigator/gemini/worker/service/GeminiTaskServiceAuthResolutionTest.java`
  - `docs/version-tracker/1.3.1-SNAPSHOT/README.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage11-gemini-narrow-port-bean.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage11-implementation-quality.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage11-coverage-audit.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage11-gemini-narrow-port-bean-acceptance.md`
- self-check conclusion: `needs-formal-quality-gate`，本阶段作为正式阶段交付，已补质量门、覆盖审计和功能级验收。
- test status:
  - `mvn test -pl addons/gemini-worker-agent -am "-Dtest=GeminiTaskServiceAuthResolutionTest,GeminiStreamRelayTest,GeminiWorkerAgentProviderTest,GeminiWorkerClientTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：15 tests pass。
  - `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：64 tests pass。
  - `mvn test -pl session-module,addons/gemini-worker-agent -am`：affected reactor pass；Surefire XML 合计 578 tests，0 failures，0 errors，0 skipped。
  - `git diff --check`：无 whitespace error，仅 CRLF normalization warnings。
- remaining risks / blockers:
  - blocking_items: none
  - `TaskCommandProvider#cancelTask` deprecated fallback 仍保留，Gemini 仍需实现该兼容 command，待所有 Provider command 迁移后统一删除。
  - 未新增完整 Spring ApplicationContext 启动测试断言真实 provider bean list；本阶段以类型边界测试、session registry/facade 回归和 affected reactor 保护。
  - Claude/Codex/Codex Biz 仍实现聚合 `TaskQueryProvider`。
- acceptance readiness: ready-with-risks

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage11-gemini-narrow-port-bean-acceptance.md
- blocking_items: none
- follow_up_required: yes
