---
type: optimization
version: 1.3.1-SNAPSHOT
ticket: OPT-001
stage: 10
severity: medium
status: signed-off
owner: java-platform
created_at: 2026-06-26
---

# OPT-001 Stage 10: LangGraph Narrow Port Bean Migration

## 文档作用

- doc_type: workitem
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 记录 Stage 10 LangGraph task provider 从聚合 `TaskQueryProvider` 迁移到窄端口注册的范围、计划、验证和进度。

## Background

Stage 8 已让 session 侧按 `TaskLookupProvider`、`TaskCommandProvider`、`TaskListingProvider`、`WorkerSessionQueryProvider` 四类窄端口注入 Provider。Stage 9 已将 LangGraph worker-session 查询拆为独立 `WorkerSessionQueryProvider` bean。

但 LangGraph task lifecycle 服务仍直接实现宽 `TaskQueryProvider` 聚合接口。由于 `TaskQueryProvider` 继承所有窄端口，它会被 Spring 同时注入 lookup / command / listing / worker-session 列表，即使 LangGraph task service 实际只支持 lookup 和 command。这会继续放大 Provider 职责边界，也会让后续删除 legacy 聚合接口变困难。

## Scope

本阶段只治理 LangGraph task provider 的注册边界：

- `LanggraphTaskService` 不再实现 `TaskQueryProvider`。
- `LanggraphTaskService` 仅实现实际支持的 `TaskLookupProvider` 与 `TaskCommandProvider`。
- 保持 `LanggraphWorkerSessionQueryService` 继续独立承接 worker-session 查询。
- 保持 task create/cancel/delete、lookup、session projection 和 controller/A2A 调用路径不变。
- 不改变 REST / OpenAPI / SDK payload。
- 补充单测确认 LangGraph task service 不再作为 listing / worker-session / aggregate provider 注册。

## Non-Goals

- 不拆分 Claude/Codex/Gemini 的 `TaskQueryProvider` 聚合实现。
- 不把 LangGraph lookup 与 command 再拆成两个物理 bean。
- 不改变 `TaskListingProvider` 的 `Object` 返回签名。
- 不引入 worker-session typed DTO / envelope。
- 不删除 `TaskQueryProvider` 兼容聚合接口。

## Implementation Plan

1. 将 `LanggraphTaskService implements TaskQueryProvider` 改为 `implements TaskLookupProvider, TaskCommandProvider`。
2. 保留当前 providerType、capabilities、lookup 方法和 command 方法语义。
3. 补充类型边界单测：
   - task service 是 `TaskLookupProvider`。
   - task service 是 `TaskCommandProvider`。
   - task service 不是 `TaskQueryProvider`。
   - task service 不是 `TaskListingProvider`。
   - task service 不是 `WorkerSessionQueryProvider`。
4. 运行 LangGraph focused regression 和 session focused regression。
5. 根据影响范围决定是否运行 `session-module,addons/langgraph-biz-worker -am`。
6. 回写 execution check-in、测试证据，并评估是否进入正式质量门和覆盖审计。

## Acceptance Criteria

- `LanggraphTaskService` 不再实现 `TaskQueryProvider`。
- `LanggraphTaskService` 只作为 lookup / command 窄端口被注入。
- `LanggraphWorkerSessionQueryService` 仍是 LangGraph worker-session 查询的唯一 SPI bean。
- LangGraph task create/lookup/cancel/delete 行为保持兼容。
- session facade 可继续通过窄端口完成 LangGraph direct create 和 task operation routing。
- 聚焦测试和受影响 reactor 回归通过。

## Verification Plan

```powershell
mvn test -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphTaskServiceTest,LanggraphWorkerSessionQueryServiceTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn test -pl session-module,addons/langgraph-biz-worker -am
```

## Progress

- 2026-06-26: 子计划创建，范围限定为 LangGraph task provider 从聚合接口迁移到实际支持的 lookup / command 窄端口。
- 2026-06-26: `LanggraphTaskService` 从 `TaskQueryProvider` 迁移为 `TaskLookupProvider, TaskCommandProvider`。
- 2026-06-26: `LanggraphTaskServiceTest` 新增类型边界回归，确认 task service 不再作为 aggregate/listing/worker-session provider 暴露。
- 2026-06-26: LangGraph focused regression、session focused regression、affected reactor、质量门、覆盖审计和功能级验收签收已完成。

## Execution Check-in

Review 发现：

- Stage 8 已支持四类窄端口列表注入，Stage 9 已拆出 LangGraph worker-session provider，但 `LanggraphTaskService` 仍直接实现 `TaskQueryProvider` 聚合接口。
- 由于 `TaskQueryProvider` 继承 lookup / command / listing / worker-session，LangGraph task service 会继续被 Spring 收集到它并不支持的 listing / worker-session 列表。
- LangGraph 是当前最小风险切片：worker-session 刚独立，task service 实际只需要 lookup / command 两类端口。

已完成：

- `LanggraphTaskService` implements 列表从 `TaskQueryProvider` 改为 `TaskLookupProvider, TaskCommandProvider`。
- 保留 providerType、capability、lookup、createTaskDirect、cancelTask、deleteTask 等现有语义。
- `LanggraphWorkerSessionQueryService` 继续独立承接 worker-session list/count/messages/sync。
- `LanggraphTaskServiceTest` 新增 `exposes_only_supported_task_provider_ports`，断言 service 是 lookup/command，不是 aggregate/listing/worker-session。
- `README.md` 已加入 Stage 10 workitem 索引。

测试证据：

- `mvn test -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphTaskServiceTest,LanggraphWorkerSessionQueryServiceTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：27 tests pass。
- `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：64 tests pass。
- `mvn test -pl session-module,addons/langgraph-biz-worker -am`：affected reactor 通过，Surefire XML 合计 162 reports / 1149 tests，0 failures，0 errors，0 skipped。
- `git diff --check`：无 whitespace error，仅 CRLF normalization warnings。

质量与覆盖：

- 实现质量门见 `quality/OPT-001-stage10-implementation-quality.md`，decision=`ready-with-risks`。
- 测试覆盖审计见 `coverage/OPT-001-stage10-coverage-audit.md`，conclusion=`ready-with-gaps`，can_enter_acceptance=`yes`。

签收结论：

- 验收记录见 `acceptance/OPT-001-stage10-langgraph-narrow-port-bean-acceptance.md`。
- acceptance_decision=`accepted-with-risks`，无阻断项。

剩余风险：

- Claude/Codex/Gemini 仍直接实现 `TaskQueryProvider` 聚合接口。
- LangGraph lookup 与 command 仍由同一个 task service bean 承接；进一步拆物理 bean 留给后续阶段。
- 当前缺少专门 Spring ApplicationContext 启动测试断言真实 provider bean list；受影响 reactor 已覆盖编译和模块回归。
- `TaskListingProvider` strictly typed method 与 worker-session typed DTO/envelope 仍未处理。

下一步：

- 建议进入 Stage 11：优先迁移下一个 Provider 的聚合接口实现，推荐从 Gemini 开始，因为其 task service 体积和能力面小于 Claude/Codex；备选是规划 `TaskListingProvider` strictly typed method 兼容迁移。

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage10-langgraph-narrow-port-bean-acceptance.md
- blocking_items: none
- follow_up_required: yes
