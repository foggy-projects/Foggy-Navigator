---
quality_scope: feature
quality_mode: pre-coverage-audit
version: v1.0
tracked_version: 1.3.1-SNAPSHOT
target: OPT-001-java-architecture-risk-governance
status: reviewed
decision: ready-with-risks
reviewed_by: Codex
reviewed_at: 2026-06-25
follow_up_required: yes
---

# OPT-001 Implementation Quality Gate

## Background

本质量门覆盖 `OPT-001` Stage 1 与 Stage 2.1~2.5 的 Java 侧架构治理实现，重点检查：

- Provider route / backend / modelConfig 映射是否已集中。
- `TaskDispatchFacade` 内部职责是否从单一大类拆出更明确边界。
- 外部 REST / OpenAPI / SDK payload 与 `DispatchTaskDTO` 兼容性是否保持。
- 当前实现是否可以进入测试覆盖审计，以及进入 Stage 3 Provider 状态 schema 化前还剩哪些风险。

## Check Basis

参考文档：

- `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
- `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-method-responsibility-inventory.md`
- `docs/a2a-agent-architecture.md`

代码检查范围：

- `navigator-common/src/main/java/com/foggy/navigator/common/util/ProviderRouteRegistry.java`
- `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskQueryCapability.java`
- `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskQueryProvider.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskQueryProviderRegistry.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskCreateTargetResolver.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/UnifiedSessionTaskProjectionService.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskOperationRouter.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchRequestParams.java`
- Claude / Codex / Gemini / LangGraph worker Provider capability 声明与 route 适配。

验证命令：

```powershell
mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn test -pl session-module -am
```

结果：

- 定向回归：53 tests，0 failures，0 errors，0 skipped。
- session 全量回归：239 tests，0 failures，0 errors，0 skipped。

## Changed Surface

Stage 1 已将 Provider route 规则集中到 `ProviderRouteRegistry`，并把 session、metadata-config、business-agent、OpenAPI readiness / diagnostics 和四个 worker adapter 的重复映射收口到公共入口。

Stage 2 已完成 Facade 边界拆分：

- `TaskQueryProviderRegistry`：Provider 列表、按 providerType 查找、按 taskId 查找、按 capability 过滤 fan-out。
- `TaskCreateTargetResolver`：create 路径目标推导、session 绑定、directory 隐式 Agent、modelConfig fallback。
- `UnifiedSessionTaskProjectionService`：统一 session-store 分页/搜索、compact item、Provider page/search envelope 读取和 DTO 投影。
- `TaskOperationRouter`：direct create、cancel、respond、reconnect、resync、rewind、resume、delete、scan checkpoints 操作路由。
- `TaskDispatchRequestParams`：Direct / Resume / A2A metadata 的共享参数转换。

`TaskDispatchFacade` 仍保留 Controller 统一入口、A2A create 编排、列表聚合和创建请求诊断字段回填，未改变公开方法、构造注入语义和外部 payload。

## Quality Checklist

| Dimension | Result | Evidence / Risk |
| --- | --- | --- |
| Scope conformance | pass | 实现范围聚焦 Java 侧架构治理，未扩散到前端或外部 SDK 契约变更。 |
| Code hygiene | pass | 质量门期间删除了 `TaskOperationRouter.cancelTask` 中不可达的 provider fallback 分支；未发现 TODO/FIXME/debug 输出。 |
| Duplication / consolidation | pass | Provider route/backend 规则、Provider 查找、create 目标推导、统一投影和任务操作路由已从 Facade 或散落分支集中。 |
| Complexity / abstraction | pass-with-risk | Facade 体积和职责已明显下降；`TaskQueryProvider` 端口仍偏宽，生命周期操作与查询能力尚未拆成更窄 SPI。 |
| Error handling | pass-with-risk | cancel 终态 no-op、provider route 优先、delete provider missing cleanup、resume normalize 语义保持；部分 unsupported 行为仍依赖 `TaskQueryProvider` default throw。 |
| Readability | pass | 新增类命名与职责边界清晰，方法级职责清单已同步。 |
| Critical logic docs | pass | `OPT-001` 主文档、方法清单和 A2A 架构文档已记录 Stage 1/2 现状与剩余风险。 |
| Contract compatibility | pass | 未改变 REST / OpenAPI / SDK payload；`DispatchTaskDTO` 与请求透传字段保持兼容，`attachments`、`images`、`context` 已纳入共享参数转换。 |
| Docs / writeback | pass | 版本 README、工作项、架构文档和本质量门均已回写。 |
| Test alignment | pass-with-risk | 定向与 session 全量回归通过；覆盖审计仍需确认 `reconnect`、`resync`、`scan checkpoints` 等 pass-through 路径是否需要补独立断言。 |
| Release readiness | ready-with-risks | Stage 2 可进入测试覆盖审计；Stage 3 前需继续处理 Provider 状态 schema 与 SPI 端口风险。 |

## Findings

### Blocking Findings

未发现阻断进入测试覆盖审计的实现缺陷。

### Non-blocking Findings

1. `TaskQueryProvider` 仍承担查询、任务生命周期、恢复、worker session 等多类职责，且 `cancelTask` 当前仍触发 deprecated 编译警告。该问题不改变现有行为，但建议后续拆分为更窄端口或引入 lifecycle/recovery 专用接口。
2. `UnifiedSessionTaskProjectionService` 为兼容 Provider 私有 page/search DTO 仍使用反射读取 envelope 字段。当前是兼容性选择，后续建议推动 typed envelope 或统一 response contract。
3. `providerStateJson` / `taskStateJson` 仍缺少版本化 schema、typed codec 和迁移策略。该风险属于 Stage 3 主目标，当前 Stage 2 不应继续扩大隐式 JSON key 的使用。
4. `reconnectTask`、`resyncTask`、`scanCheckpoints` 已完成操作路由迁出，但从测试名称与断言看覆盖弱于 cancel/resume/rewind/delete，应在覆盖审计中决定是否补测。

## Risks / Follow-ups

- Stage 3 优先定义 Provider 状态 schema：Claude `claudeSessionId`、Codex `codexThreadId`、Gemini session、LangGraph worker session、checkpoint / rewind / resume 相关字段。
- 评估 `TaskQueryProvider` 的端口拆分顺序：query、task lifecycle、recovery、worker session passthrough 可拆成独立能力接口，逐步降低 default throw。
- 为 Provider page/search 返回引入 typed envelope 前，需要保留当前反射兼容层并用测试锁住字段兼容。
- 覆盖审计需复核 Stage 2 acceptance criteria：cancel/resume/rewind/respond/delete 已有明确回归；reconnect/resync/scan checkpoints 是否补测由覆盖审计裁定。

## Recommended Next Skills

- `foggy-test-coverage-audit`：下一步建议执行，审计 Stage 1/2 requirement、acceptance item 与测试证据映射。
- `plan-evaluator`：可选，用于比较先做 SPI 端口拆分还是先做 Provider 状态 schema。
- `foggy-bug-regression-workflow`：当前不需要；本质量门未发现需要按 BUG 流程处理的回归缺陷。

## Decision

decision: `ready-with-risks`

Stage 2 的核心实现质量可以进入测试覆盖审计。风险不阻断覆盖审计，但必须进入后续规划：`TaskQueryProvider` 端口偏宽、统一查询 envelope 仍为兼容反射、Provider 状态 JSON 尚未 schema 化，以及少数 pass-through 操作测试覆盖需审计确认。
