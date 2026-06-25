---
type: implementation-plan
version: 1.3.1-SNAPSHOT
ticket: OPT-001-stage6
severity: major
status: signed-off
owner: session-module/navigator-spi/java-platform
created_at: 2026-06-25
---

# OPT-001 Stage 6: TaskQueryProvider 窄端口治理

## 文档作用

- doc_type: workitem
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 记录 `TaskQueryProvider` 宽接口拆分为窄端口的执行计划、测试证据和验收状态。

## Background

Stage 1-5 已完成 Provider 路由、任务分发职责拆分、Provider 状态 schema、SSE 部署边界和运行配置硬化。剩余核心风险之一是 `TaskQueryProvider` 名义上是查询 SPI，但实际同时承载：

- task lookup：`getTaskById`、`listTasksBySession`、`listActiveDispatchTasks`
- task command：direct create、respond、reconnect、resync、rewind、resume、delete、scan checkpoints
- listing/search：paged list、search sessions、directory list
- worker session：list/count/messages/sync worker sessions

虽然 Stage 2.4 已引入 `TaskQueryCapability` 降低 fan-out 范围，但类型层面仍是宽接口，导致 session 侧调用点难以表达真实依赖，也容易让新增 Provider 误以为必须理解所有默认方法。

## Target Outcome

- 新增窄端口 SPI，按 lookup、command、listing/search、worker session 查询拆分职责。
- 保留 `TaskQueryProvider` 作为兼容聚合接口，避免一次性改动所有 Provider bean 和外部模块。
- `TaskQueryProviderRegistry` 提供窄端口视图，session 侧新调用优先依赖窄端口类型。
- 不改变 REST / OpenAPI / SDK payload，不改变 Provider 实现行为。
- 为 registry 窄端口选择和 session 任务操作路径补充回归测试。

## Scope / Ownership

| Area | Owner | Touchpoints |
| --- | --- | --- |
| SPI contract | `navigator-spi` | `TaskQueryProvider`、新增窄端口接口 |
| Provider routing | `session-module` | `TaskQueryProviderRegistry`、`TaskDispatchFacade`、`TaskOperationRouter` |
| Tests | `session-module` | `TaskQueryProviderRegistryTest`、`TaskDispatchFacadeTest` |
| Docs | `docs` | 本文档、OPT-001 主工作项 |

## Review Findings

- `TaskQueryProvider` 现有名称和职责不一致，command / worker session 操作都挂在 query 接口下。
- `TaskDispatchFacade` 的列表/search/worker session 查询和 `TaskOperationRouter` 的 command 操作都直接使用宽接口类型。
- capability metadata 已能表达 provider 支持范围，但 registry 返回类型仍是 `TaskQueryProvider`，无法在编译期限制调用面。
- 直接删除宽接口方法风险较高，会影响 Claude/Codex/Gemini/LangGraph provider 实现和现有 Spring bean 注入。

## Implementation Plan

### Stage 6.1 - Compatible Narrow Ports

- [x] 新增 `TaskProviderPort` 基础端口，集中 `getProviderType()` 与 capability helpers。
- [x] 新增 `TaskLookupProvider`、`TaskCommandProvider`、`TaskListingProvider`、`WorkerSessionQueryProvider`。
- [x] 将 `TaskQueryProvider` 改为兼容聚合接口，继承上述窄端口，保持 Provider 实现无行为变化。

### Stage 6.2 - Session Call-site Narrowing

- [x] `TaskQueryProviderRegistry` 增加 lookup / command / listing / worker-session 窄端口返回方法。
- [x] `TaskOperationRouter` 的 create/resume/respond/reconnect/resync/rewind/delete/scan/cancel 路径改为依赖 `TaskCommandProvider`。
- [x] `TaskDispatchFacade` 的 list/search/worker session fan-out 改为依赖 `TaskListingProvider` / `WorkerSessionQueryProvider`。
- [x] lookup 路径改为依赖 `TaskLookupProvider`。

### Stage 6.3 - Tests and Check-in

- [x] 补充 registry 窄端口返回类型与 capability fallback 测试。
- [x] 运行 session 定向回归。
- [x] 运行 provider 受影响 reactor 回归。
- [x] 回写本文档与 OPT-001 主工作项 execution check-in。

## Acceptance Criteria

| Criteria | Status | Evidence |
| --- | --- | --- |
| 窄端口 SPI 已定义且 `TaskQueryProvider` 兼容旧实现 | accepted | `TaskProviderPort` / `TaskLookupProvider` / `TaskCommandProvider` / `TaskListingProvider` / `WorkerSessionQueryProvider` 已新增；`TaskQueryProvider` 改为兼容聚合接口。 |
| session 侧调用点开始依赖窄端口类型 | accepted | `TaskOperationRouter` 使用 `TaskCommandProvider` / `TaskLookupProvider`；`TaskDispatchFacade` 使用 `TaskListingProvider` / `WorkerSessionQueryProvider` / lookup typed view。 |
| Provider 实现不需要在本阶段批量重写 | accepted | affected reactor 通过，Claude/Codex/Gemini/LangGraph provider 无需迁移 bean 实现。 |
| 现有任务 create/resume/list/search/worker session 行为不变 | accepted | `TaskDispatchFacadeTest` 54 tests pass；affected reactor 1520 tests pass。 |
| 自动化测试覆盖 registry 和 facade/router 关键路径 | accepted | `TaskQueryProviderRegistryTest` 8 tests pass；session focused regression 62 tests pass。 |

## Constraints / Non-Goals

- 不删除 `TaskQueryProvider`，不移除既有默认方法的兼容语义。
- 不改 REST / OpenAPI / SDK payload。
- 不引入新的 Provider 注册机制。
- 不在本阶段解决 typed provider envelope 和反射 envelope 读取问题。
- 不拆 LangGraph worker session endpoint 的具体 service 实现；本阶段只先收窄 SPI 类型边界。

## Progress Tracking

### Development Progress

| Item | Status | Notes |
| --- | --- | --- |
| Stage 6 子计划落档 | done | 本文档记录范围、review 发现、计划和验收标准。 |
| 窄端口 SPI | done | 新增四类窄端口和基础端口，`TaskQueryProvider` 保留兼容聚合接口。 |
| session 调用点收窄 | done | Registry 提供 typed views，Router/Facade/Resolver 侧按 lookup/command/listing/worker-session 端口表达依赖。 |
| 主 OPT-001 回写 | done | 主工作项已记录 Stage 6 结果、测试证据、质量门、覆盖审计和签收状态。 |

### Testing Progress

| Scope | Command summary | Result |
| --- | --- | --- |
| session focused regression | `mvn test -pl session-module -am "-Dtest=TaskQueryProviderRegistryTest,TaskDispatchFacadeTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"` | PASS：62 tests，0 failures，0 errors，0 skipped |
| affected reactor regression | `mvn test -pl navigator-spi,session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am` | PASS：Surefire XML 合计 220 reports / 1520 tests，0 failures，0 errors，0 skipped |

### Experience Progress

experience: N/A。该切片为 Java SPI 和后端路由类型边界治理；未新增或修改 UI 页面、表单、按钮、权限可见性或前端交互。

## Execution Check-in

| Item | Status | Notes |
| --- | --- | --- |
| Completed work summary | done | 完成兼容式窄端口 SPI、Registry typed views、session 调用点收窄和测试回归。 |
| Touched code paths listed | done | `navigator-spi` task provider SPI；`session-module` Registry / Router / Facade / CreateTargetResolver；`TaskQueryProviderRegistryTest`。 |
| Self-review completed | done | 已升级为正式实现质量门：`quality/OPT-001-stage6-implementation-quality.md`，decision=`ready-for-coverage-audit`。 |
| Test status recorded | done | session focused regression 和 affected reactor regression 均通过。 |
| Remaining risks recorded | done | legacy 聚合接口未删除、typed envelope 未治理、deprecated cancel 仍保留。 |
| Acceptance readiness | done | 覆盖审计 conclusion=`ready-with-gaps`，已完成签收，decision=`accepted-with-risks`。 |

## Quality / Coverage / Acceptance Status

| Item | Status | Record |
| --- | --- | --- |
| Implementation quality gate | ready-for-coverage-audit | `quality/OPT-001-stage6-implementation-quality.md` |
| Test coverage audit | ready-with-gaps | `coverage/OPT-001-stage6-coverage-audit.md` |
| Feature acceptance | signed-off / accepted-with-risks | `acceptance/OPT-001-stage6-task-query-provider-port-split-acceptance.md` |

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-25
- acceptance_record: `docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage6-task-query-provider-port-split-acceptance.md`
- blocking_items: none
- follow_up_required: yes
