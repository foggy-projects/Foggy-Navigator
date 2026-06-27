---
type: optimization
version: 1.3.1-SNAPSHOT
ticket: OPT-001
severity: major
status: in-progress
owner: java-platform
created_at: 2026-06-25
---

# OPT-001: Java 侧架构风险治理与核心链路优化

## 文档作用

- doc_type: workitem
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 将 Java 侧全面 review 发现的架构风险纳入 `1.3.1-SNAPSHOT` 版本跟踪，并定义后续优化的范围、验收标准和进度记录要求。

## Background

2026-06-24 至 2026-06-25 对 Java 侧模块进行了静态架构 review。当前后端是 Spring Boot 多模块平台，主链路已经从单一 LLM Agent 调用演进为：

```text
Controller / OpenAPI
  -> AgentSubmitPipeline
  -> TaskDispatchFacade
  -> UnifiedAgentResolver / TaskQueryProvider
  -> Provider TaskService
  -> WorkerStreamRelay
  -> AgentMessage / TaskStatusEvent
  -> SessionEventListener / TaskUpdateNotifier
  -> UnifiedSseEmitter
  -> Frontend / SDK
```

系统能力已经覆盖 Claude、Codex、Gemini、LangGraph Biz、Echo 等 Provider，但核心分发、Provider 状态、事件推送和运行配置仍存在维护风险，需要作为独立优化项治理。

## Problem Statement

当前主要风险点：

1. `TaskDispatchFacade` 与 Provider 侧 `*TaskService` 过大，混合了路由、校验、状态机、查询、恢复、重连、回退和统一投影逻辑。
2. Provider 类型、worker backend、modelConfig 兼容关系分散在 `TaskDispatchFacade`、`UnifiedAgentResolver`、各 `A2aAgentProvider` 和配置服务中，存在漂移风险。
3. `SessionEntity.providerStateJson` 与 `SessionTaskEntity.taskStateJson` 承载 Provider 私有状态，但缺少显式 schema、版本和迁移约束。
4. `UnifiedSseEmitter` 使用 JVM 内存态管理 emitter、订阅关系和心跳，当前部署边界偏单实例，多实例策略未显式记录。
5. 运行配置中存在开发便利项，例如 `allow-bean-definition-overriding: true`、JPA `ddl-auto: update`，需要明确生产 profile 的安全边界。
6. 架构文档对当前 A2A / Direct Provider Route / TaskQueryProvider 的说明不完整，容易误导后续实现。

## Target Outcome

- 统一任务分发主链路职责更清晰，后续修改能按路由、Provider 操作、Session 投影、状态恢复分别 review。
- Provider 类型和 backend 能力映射有单一事实来源或显式校验入口，避免新增 Provider 时复制条件判断。
- Provider 私有状态有版本化 schema 或 typed codec，恢复、回退、续接和跨版本兼容可测试。
- SSE 的单实例边界、多实例演进方案和验收条件明确。
- 开发配置与生产配置风险被分离，生产 profile 不依赖隐式自动建表和 bean 覆盖。
- 架构文档同步到当前实现，后续开发以 `docs/a2a-agent-architecture.md` 和本工作项为入口。

## Scope / Ownership

| Area | Owner module | Current touchpoints |
| --- | --- | --- |
| 统一任务分发 | `session-module` | `TaskDispatchFacade`、`AgentSubmitPipeline`、`UnifiedAgentResolver`、`SessionBindingService` |
| Provider SPI | `navigator-spi` | `A2aAgent`、`A2aAgentProvider`、`TaskQueryProvider` |
| 统一持久化投影 | `navigator-common` | `SessionEntity`、`SessionTaskEntity`、`SessionMessageEntity` |
| Worker Provider | `addons/*-worker-agent` | `ClaudeTaskService`、`CodexTaskService`、`GeminiTaskService`、`LanggraphTaskService`、各 `*WorkerAgentProvider`、各 StreamRelay |
| 平台配置 | `metadata-config-module`、`launcher` | LLM model config、worker backend、profile 配置 |
| 业务接入 | `business-agent-module` | `BusinessAgentTaskService`、`WorkerGatewayService`、Skill / Function / ClientApp grant |
| 架构文档 | `docs` | `a2a-agent-architecture.md`、`00-system-overview.md`、`02-modules/functional-architecture.md` |

## Phased Plan

### Stage 0 - 文档与基线

- [x] 记录 Java 架构 review 结论和风险点。
- [x] 同步 A2A 架构文档到当前实现口径。
- [x] 运行并记录当前关键测试基线。
- [x] 梳理 `TaskDispatchFacade` 和各 Provider `TaskService` 的方法级职责清单：见 [OPT-001-java-method-responsibility-inventory.md](./OPT-001-java-method-responsibility-inventory.md)。

### Stage 1 - Provider 路由与能力映射治理

- [x] 建立 Provider 类型、worker backend、modelConfig 兼容规则的统一校验入口或配置模型。
- [x] 减少 `TaskDispatchFacade`、Provider 实现和配置服务中的重复映射判断。
- [x] 为 Claude / Codex / Gemini / LangGraph Biz 路由添加对等回归测试。

当前完成范围：

- 已新增 `navigator-common` 的 `ProviderRouteRegistry`，统一维护 `OPENAI_CODEX`、`CLAUDE_CODE`、`GEMINI_CLI`、`LANGGRAPH_BIZ` 到 Provider route 的映射。
- 已将 `TaskDispatchFacade`、`UnifiedAgentResolver`、`JpaSessionManager`、Claude / Codex / Gemini / LangGraph `*WorkerAgentProvider` 切换为复用公共注册表。
- 已将 `metadata-config-module`、`business-agent-module`、OpenAPI readiness / diagnostics 中的 backend 支持列表、常量和别名推导迁移到公共注册表。
- `ProviderRouteRegistry` 已补齐 backend canonical normalization、route token 反向映射和 supported backend 判断，支持大小写、短横线/下划线和 providerType 别名输入。
- 已保留 `OPENAI_CODEX` modelConfig 可兼容 `codex-biz-worker` direct route 的规则。
- `navigator-open-sdk` 等外部 SDK 边界如需复用该注册表，应另行评估是否引入 `navigator-common` 依赖；本阶段不强制跨 SDK 迁移。

### Stage 2 - 统一任务分发职责收敛

- [x] 将任务创建、任务操作、Provider 查询、Session 投影、恢复/续接等职责拆出明确边界。
- [x] 保持现有 REST / OpenAPI / SDK payload 和 `DispatchTaskDTO` 兼容。
- [x] 对 cancel、resume、rewind、reconnect、resync 等高风险路径补充或更新测试。

当前完成范围：

- Stage 2.1 已新增 `TaskQueryProviderRegistry`，集中管理 `TaskQueryProvider` 列表、按 providerType 查找和按 taskId 归属查找。
- `TaskDispatchFacade` 构造签名、公开方法和异常语义保持不变，Provider 遍历入口统一委托 registry。
- Stage 2.2 已新增 `TaskCreateTargetResolver`，迁出 create 路径的显式 Agent、`directory#` 隐式 Agent、session 绑定和 modelConfig fallback 推导。
- Stage 2.3 已新增 `UnifiedSessionTaskProjectionService`，迁出统一 session-store 分页/搜索、compact item、provider page/search envelope 读取和 `SessionTaskEntity` 到 `DispatchTaskDTO` 投影。
- Stage 2.4 已新增 `TaskQueryCapability` 与 `TaskQueryProvider#getCapabilities()`，并让 Claude、Codex、Codex Biz、Gemini、LangGraph Provider 声明当前支持的统一端点能力；`TaskQueryProviderRegistry` 在 provider fan-out 查询前按 capability 缩小候选集合。
- Stage 2.5 已新增 `TaskOperationRouter` 与 `TaskDispatchRequestParams`，迁出 direct create、cancel、respond、reconnect、resync、rewind、resume、delete、scan checkpoints 的 Provider 操作路由和 resume 规范化。
- `TaskDispatchFacade` 仍保留 Controller 入口、create A2A 编排、列表聚合和创建请求诊断状态回填；Provider 状态 schema 和 typed envelope 仍放入后续阶段治理。
- Stage 2 实现质量门已完成，结论为 `ready-with-risks`；见 `quality/OPT-001-implementation-quality.md`。
- Stage 2 测试覆盖审计已完成，结论为 Stage 1/2 slice 可进入验收；见 `coverage/OPT-001-stage1-stage2-coverage-audit.md`。
- Stage 1/2 功能级验收签收已完成，结论为 `accepted`；见 `acceptance/OPT-001-stage1-stage2-acceptance.md`。

### Stage 3 - Provider 状态 schema 化

- [x] 为 `providerStateJson` 和 `taskStateJson` 定义 Provider 级 schema、版本字段和 codec。
- [x] 为 Claude `claudeSessionId`、Codex `codexThreadId`、Gemini `geminiSessionId` 的 provider session state 恢复字段建立测试。
- [x] 为 LangGraph `taskStateJson` 中的 context/state 恢复字段建立测试；worker session endpoints 拆分覆盖保留为后续项。
- [x] 明确并覆盖 Claude/Codex/Gemini provider session state 的未知字段、旧版本字段、坏 JSON 和空状态兼容策略。
- [x] 明确并覆盖 Claude/Codex/Gemini/LangGraph task state 的未知字段、旧版本字段和空状态兼容策略。
- [x] 补齐 `DispatchTaskDTO` 对 schema v1 `taskStateJson` 的直接投影回归。
- [x] 完成 Stage 3 实现质量门与测试覆盖审计。

当前推进范围：

- Stage 3 子计划已落档：`workitems/OPT-001-stage3-provider-state-schema.md`。
- Stage 3.1 已新增 `ProviderStateCodec`，定义 `schemaVersion=1`、`providerType` 和核心字段常量。
- `TaskDispatchFacade` 的 context/diagnostic metadata 写入路径已开始通过共享 codec 合并 `taskStateJson`，为新写入状态补 schema version 与 providerType。
- Stage 3.1 定向回归与 session 全量回归已通过，详见 Stage 3 子计划 execution-checkin。
- Stage 3.2 已完成 Claude/Codex/Gemini 的 `providerStateJson` 读写迁移，恢复读取兼容 legacy/schema v1，写入会保留未知字段并补 schema version 与 providerType。
- Stage 3.3 已完成 Claude/Codex/Gemini/LangGraph Provider 内部 `taskStateJson` 写入迁移，并通过受影响 Provider reactor 回归。
- Stage 3.4 已补 `DispatchTaskDTO` 对 schema v1 task state 的直接投影回归，并完成实现质量门与测试覆盖审计。
- Stage 3 质量门结论为 `ready-with-risks`；见 `quality/OPT-001-stage3-implementation-quality.md`。
- Stage 3 覆盖审计结论为 `ready-with-gaps`，可进入验收；见 `coverage/OPT-001-stage3-coverage-audit.md`。
- Stage 3 功能级验收已签收，结论为 `accepted-with-risks`；见 `acceptance/OPT-001-stage3-provider-state-schema-acceptance.md`。

### Stage 4 - SSE 与部署边界治理

- [x] 明确当前 `UnifiedSseEmitter` 为单实例内存态实现。
- [x] 设计多实例策略：粘性会话、外部事件总线或集中通知服务，至少选定一条演进路线。
- [x] 覆盖 emitter 清理、心跳、订阅恢复、断线重连和任务状态补偿测试。

当前完成范围：

- Stage 4 子计划已落档：`workitems/OPT-001-stage4-sse-deployment-boundary.md`。
- 当前版本支持单实例，或部署层按用户/会话对 `/api/v1/sse/**` 做粘性路由，并保证订阅请求与任务事件生产链路同实例亲和。
- 非粘性多实例不在本阶段实现范围内；后续需引入外部事件总线或集中通知服务后再承诺跨 JVM 实时投递。
- `UnifiedSseEmitter` 已将普通业务事件发送失败、heartbeat 失败和 callback 断连统一收敛到空连接用户清理逻辑。
- `TaskUpdateNotifier` 已补 `task_status_change`、`task_completion` 和缺失 userId/session 跳过行为单测。

### Stage 5 - 运行配置硬化

- [x] 区分 dev / local / production profile 对 `ddl-auto`、bean overriding、密钥和 actuator 暴露的要求。
- [x] 补充生产启动前置检查或文档化部署检查表。
- [x] 避免生产环境依赖隐式 schema update。

当前完成范围：

- Stage 5 子计划已落档：`workitems/OPT-001-stage5-runtime-config-hardening.md`。
- `launcher` base `application.yml` 保留开发便利默认值，但已支持通过环境变量覆盖关键项。
- 新增 `application-prod.yml`，生产 profile 默认 `ddl-auto=validate`、禁止 bean overriding、收敛 actuator 暴露，并要求显式提供 datasource、JWT、ROOT password、credential key/salt 和 external URL。
- 新增 `ProductionConfigurationGuard` 并接入 `FogyNavigatorApplication`，仅在 `prod` / `production` profile 下 fail-fast 拒绝危险配置。
- guard 已覆盖 dev 跳过、prod 拒绝危险默认值、prod 接受安全配置和 `production` alias 单测。

### Stage 6 - TaskQueryProvider 窄端口治理

- [x] 新增 lookup / command / listing / worker-session 窄端口 SPI。
- [x] 保留 `TaskQueryProvider` 兼容聚合接口，避免批量重写 Provider 实现。
- [x] session 侧 Registry / Router / Facade 调用点开始依赖窄端口类型。
- [x] 完成实现质量门、测试覆盖审计和功能级验收签收。

当前完成范围：

- Stage 6 子计划已落档：`workitems/OPT-001-stage6-task-query-provider-port-split.md`。
- `navigator-spi` 新增 `TaskProviderPort`、`TaskLookupProvider`、`TaskCommandProvider`、`TaskListingProvider`、`WorkerSessionQueryProvider`。
- `TaskQueryProvider` 改为兼容聚合接口，继承上述窄端口，现有 Claude/Codex/Gemini/LangGraph provider 实现无需本阶段批量迁移。
- `TaskQueryProviderRegistry` 已提供 lookup / command / listing / worker-session typed views。
- `TaskOperationRouter` 的命令路径改为依赖 `TaskCommandProvider`，查询路径使用 `TaskLookupProvider`。
- `TaskDispatchFacade` 的 list/search/worker session fan-out 改为依赖 `TaskListingProvider` / `WorkerSessionQueryProvider`。
- Stage 6 实现质量门结论为 `ready-for-coverage-audit`；见 `quality/OPT-001-stage6-implementation-quality.md`。
- Stage 6 覆盖审计结论为 `ready-with-gaps`，可进入验收；见 `coverage/OPT-001-stage6-coverage-audit.md`。
- Stage 6 功能级验收已签收，结论为 `accepted-with-risks`；见 `acceptance/OPT-001-stage6-task-query-provider-port-split-acceptance.md`。

### Stage 7 - Provider listing/search typed envelope 治理

- [x] 新增 Provider listing/search typed envelope。
- [x] `UnifiedSessionTaskProjectionService` listing/search envelope 解析改为 typed-first。
- [x] Claude/Codex/Codex Biz 的 SPI listing/search 返回迁移到 typed envelope。
- [x] 保留 legacy Map / JavaBean getter fallback，避免旧 Provider 立即破坏。
- [x] 完成实现质量门、测试覆盖审计和功能级验收签收。

当前完成范围：

- Stage 7 子计划已落档：`workitems/OPT-001-stage7-provider-listing-envelope.md`。
- `navigator-spi` 新增 `TaskPageResult` 与 `TaskSearchResult`，用于统一表达 listing page 和 search page 结果。
- `UnifiedSessionTaskProjectionService` 优先识别 typed envelope；旧 Map / JavaBean getter 读取保留为 compatibility fallback。
- Claude SPI 返回 typed envelope，历史 controller/service DTO 返回保持不变。
- Codex / Codex Biz listing/search SPI 返回从 `Map.of(...)` 迁移为 typed envelope。
- Stage 7 实现质量门结论为 `ready-with-risks`；见 `quality/OPT-001-stage7-implementation-quality.md`。
- Stage 7 覆盖审计结论为 `ready-with-gaps`，可进入验收；见 `coverage/OPT-001-stage7-coverage-audit.md`。
- Stage 7 功能级验收已签收，结论为 `accepted-with-risks`；见 `acceptance/OPT-001-stage7-provider-listing-envelope-acceptance.md`。

### Stage 8 - Provider port 注入收窄

- [x] `TaskDispatchFacade` 构造期接收 lookup / command / listing / worker-session 四类窄端口列表。
- [x] `TaskQueryProviderRegistry` 内部按窄端口分别维护集合。
- [x] capability filtering 在具体端口集合内执行，并保持 legacy empty capability fallback。
- [x] `findCommandProviderForTask` 支持 lookup bean 与 command bean 分离但 providerType 相同的形态。
- [x] 完成实现质量门、测试覆盖审计和功能级验收签收。

当前完成范围：

- Stage 8 子计划已落档：`workitems/OPT-001-stage8-provider-port-injection.md`。
- `TaskDispatchFacade` 生产构造边界不再以 `List<TaskQueryProvider>` 作为唯一 Provider 集合。
- `TaskQueryProviderRegistry` 已按 `TaskLookupProvider`、`TaskCommandProvider`、`TaskListingProvider`、`WorkerSessionQueryProvider` 分别维护集合。
- `findCommandProviderForTask` 已改为 lookup 端口识别任务归属，再按 providerType 查找 command 端口。
- `AbortCoordinatingA2aAgent` 主构造已依赖 `TaskLookupProvider`，并保留 deprecated `TaskQueryProvider` 兼容构造器；Claude/Codex/Gemini worker adapter 已迁移到 lookup-port 构造。
- Stage 8 实现质量门结论为 `ready-with-risks`；见 `quality/OPT-001-stage8-implementation-quality.md`。
- Stage 8 覆盖审计结论为 `ready-with-gaps`，可进入验收；见 `coverage/OPT-001-stage8-coverage-audit.md`。
- Stage 8 功能级验收已签收，结论为 `accepted-with-risks`；见 `acceptance/OPT-001-stage8-provider-port-injection-acceptance.md`。

### Stage 9 - LangGraph worker-session 端口拆分

- [x] 新增 `LanggraphWorkerSessionQueryService implements WorkerSessionQueryProvider`。
- [x] `LanggraphTaskService` 不再声明 worker-session capabilities。
- [x] worker-session list/count/messages/sync 逻辑迁移到独立服务，并保持 Map payload 兼容。
- [x] session facade 补充独立 worker-session provider 接入回归。
- [x] 完成实现质量门、测试覆盖审计和功能级验收签收。

当前完成范围：

- Stage 9 子计划已落档：`workitems/OPT-001-stage9-langgraph-worker-session-split.md`。
- `LanggraphWorkerSessionQueryService` 已独立承接 `LIST_WORKER_SESSIONS`、`GET_WORKER_SESSION_MESSAGE_COUNT`、`GET_WORKER_SESSION_MESSAGES`、`SYNC_WORKER_SESSIONS`。
- `LanggraphTaskService` capability 已收窄到 create/cancel/delete 等任务生命周期能力。
- worker ownership、session ownership、session message pagination 和 sync total 语义保持兼容。
- `TaskDispatchFacadeTest` 已覆盖只实现 `WorkerSessionQueryProvider` 的 provider 可被 session facade 命中。
- Stage 9 实现质量门结论为 `ready-with-risks`；见 `quality/OPT-001-stage9-implementation-quality.md`。
- Stage 9 覆盖审计结论为 `ready-with-gaps`，可进入验收；见 `coverage/OPT-001-stage9-coverage-audit.md`。
- Stage 9 功能级验收已签收，结论为 `accepted-with-risks`；见 `acceptance/OPT-001-stage9-langgraph-worker-session-split-acceptance.md`。

### Stage 10 - LangGraph narrow port bean 迁移

- [x] `LanggraphTaskService` 从聚合 `TaskQueryProvider` 迁移为实际支持的窄端口 bean。
- [x] `LanggraphTaskService` 仅暴露 task lookup 与 task command 能力，不再作为 listing / worker-session / aggregate provider 注册。
- [x] `LanggraphWorkerSessionQueryService` 继续独立承接 worker-session 查询能力。
- [x] 补充 LangGraph Provider 类型边界回归测试。
- [x] 完成实现质量门、测试覆盖审计和功能级验收签收。

当前完成范围：

- Stage 10 子计划已落档：`workitems/OPT-001-stage10-langgraph-narrow-port-bean.md`。
- `LanggraphTaskService` 已仅实现 `TaskLookupProvider` 与 `TaskCommandProvider`。
- task lookup / create / cancel / delete 语义保持不变；worker-session 仍由 `LanggraphWorkerSessionQueryService` 承接。
- `LanggraphTaskServiceTest#exposes_only_supported_task_provider_ports` 覆盖类型边界，防止 task service 回退为聚合 `TaskQueryProvider`。
- Stage 10 实现质量门结论为 `ready-with-risks`；见 `quality/OPT-001-stage10-implementation-quality.md`。
- Stage 10 覆盖审计结论为 `ready-with-gaps`，可进入验收；见 `coverage/OPT-001-stage10-coverage-audit.md`。
- Stage 10 功能级验收已签收，结论为 `accepted-with-risks`；见 `acceptance/OPT-001-stage10-langgraph-narrow-port-bean-acceptance.md`。

### Stage 11 - Gemini narrow port bean 迁移

- [x] `GeminiTaskService` 从聚合 `TaskQueryProvider` 迁移为实际支持的窄端口 bean。
- [x] `GeminiTaskService` 仅暴露 task lookup 与 task command 能力，不再作为 listing / worker-session / aggregate provider 注册。
- [x] 保持 Gemini create/resume/cancel/delete、lookup、session projection 和 A2A abort wrapper 行为兼容。
- [x] 补充 Gemini Provider 类型边界回归测试。
- [x] 完成实现质量门、测试覆盖审计和功能级验收签收。

当前完成范围：

- Stage 11 子计划已落档：`workitems/OPT-001-stage11-gemini-narrow-port-bean.md`。
- `GeminiTaskService` 已仅实现 `TaskLookupProvider` 与 `TaskCommandProvider`。
- task lookup / create / resume / cancel / delete 语义保持不变。
- `GeminiTaskServiceAuthResolutionTest#exposesOnlySupportedTaskProviderPorts` 覆盖类型边界，防止 task service 回退为聚合 `TaskQueryProvider`。
- Stage 11 实现质量门结论为 `ready-with-risks`；见 `quality/OPT-001-stage11-implementation-quality.md`。
- Stage 11 覆盖审计结论为 `ready-with-gaps`，可进入验收；见 `coverage/OPT-001-stage11-coverage-audit.md`。
- Stage 11 功能级验收已签收，结论为 `accepted-with-risks`；见 `acceptance/OPT-001-stage11-gemini-narrow-port-bean-acceptance.md`。

### Stage 12 - Codex / Codex Biz narrow port bean 迁移

- [x] `CodexTaskService` 从聚合 `TaskQueryProvider` 迁移为实际支持的窄端口 bean。
- [x] `CodexBizTaskProvider` 从聚合 `TaskQueryProvider` 迁移为实际支持的窄端口 bean。
- [x] 两个 Codex provider 仅暴露 task lookup、task command 与 task listing/search 能力，不再作为 worker-session / aggregate provider 注册。
- [x] 保持 Codex / Codex Biz create/resume/cancel/delete/resync/rewind、listing/search、session projection 和 A2A abort wrapper 行为兼容。
- [x] 补充 Codex / Codex Biz Provider 类型边界回归测试。
- [x] 完成实现质量门、测试覆盖审计和功能级验收签收。

当前完成范围：

- Stage 12 子计划已落档：`workitems/OPT-001-stage12-codex-narrow-port-bean.md`。
- `CodexTaskService` 已仅实现 `TaskLookupProvider`、`TaskCommandProvider` 与 `TaskListingProvider`。
- `CodexBizTaskProvider` 已仅实现 `TaskLookupProvider`、`TaskCommandProvider` 与 `TaskListingProvider`。
- task lookup / command / listing-search 语义保持不变。
- `CodexTaskServiceTest#exposesOnlySupportedTaskProviderPorts` 与 `CodexBizTaskProviderTest#exposesOnlySupportedTaskProviderPorts` 覆盖类型边界，防止两个 provider 回退为聚合 `TaskQueryProvider` 或 worker-session 端口。
- Stage 12 实现质量门结论为 `ready-with-risks`；见 `quality/OPT-001-stage12-implementation-quality.md`。
- Stage 12 覆盖审计结论为 `ready-with-gaps`，可进入验收；见 `coverage/OPT-001-stage12-coverage-audit.md`。
- Stage 12 功能级验收已签收，结论为 `accepted-with-risks`；见 `acceptance/OPT-001-stage12-codex-narrow-port-bean-acceptance.md`。

### Stage 13 - Claude narrow port bean 迁移

- [x] `ClaudeTaskService` 从聚合 `TaskQueryProvider` 迁移为实际支持的窄端口 bean。
- [x] `ClaudeTaskService` 显式暴露 task lookup、task command、task listing/search 与 worker-session 查询能力，不再作为 aggregate provider 注册。
- [x] 保持 Claude create/resume/cancel/delete/respond/reconnect/resync/rewind、listing/search、worker-session 查询、session projection 和 A2A abort wrapper 行为兼容。
- [x] 补充 Claude Provider 类型边界回归测试。
- [x] 完成实现质量门、测试覆盖审计和功能级验收签收。

当前完成范围：

- Stage 13 子计划已落档：`workitems/OPT-001-stage13-claude-narrow-port-bean.md`。
- `ClaudeTaskService` 已显式实现 `TaskLookupProvider`、`TaskCommandProvider`、`TaskListingProvider` 与 `WorkerSessionQueryProvider`。
- task lookup / command / listing-search / worker-session 语义保持不变。
- `ClaudeTaskServiceAuthTest#exposesOnlySupportedTaskProviderPorts` 覆盖类型边界，防止 provider 回退为聚合 `TaskQueryProvider`。
- `rg "implements TaskQueryProvider"` 显示生产代码已无聚合实现，仅 session 测试 stub 保留兼容回归。
- Stage 13 实现质量门结论为 `ready-with-risks`；见 `quality/OPT-001-stage13-implementation-quality.md`。
- Stage 13 覆盖审计结论为 `ready-with-gaps`，可进入验收；见 `coverage/OPT-001-stage13-coverage-audit.md`。
- Stage 13 功能级验收已签收，结论为 `accepted-with-risks`；见 `acceptance/OPT-001-stage13-claude-narrow-port-bean-acceptance.md`。

### Stage 14 - TaskListingProvider typed method contract

- [x] `TaskListingProvider` 新增 typed listing/search 主方法。
- [x] `TaskPageResult` / `TaskSearchResult` 增加 legacy Map / JavaBean envelope adapter。
- [x] `TaskDispatchFacade` provider fan-out 改为调用 typed methods。
- [x] Claude / Codex / Codex Biz Provider 实现 typed override，legacy `Object` 方法保留委派兼容。
- [x] 补充 legacy envelope compatibility 与 typed facade path 回归。
- [x] 完成实现质量门、测试覆盖审计和功能级验收签收。

当前完成范围：

- Stage 14 子计划已落档：`workitems/OPT-001-stage14-task-listing-typed-method.md`。
- `TaskListingProvider` 已新增 `listTaskPage`、`searchSessionPage`、`listDirectoryTaskPage`。
- `TaskDispatchFacade` 的 list/search fan-out 不再直接调用 `listTasksPaged`、`searchSessions`、`listTasksByDirectoryPaged` legacy 方法。
- Claude / Codex / Codex Biz listing/search 实现已迁移为 typed override，legacy `Object` 方法继续保留为委派 wrapper。
- `UnifiedSessionTaskProjectionServiceTest#taskListingProviderTypedMethodsAdaptLegacyEnvelopes` 覆盖旧 Map / JavaBean envelope 兼容。
- Stage 14 实现质量门结论为 `ready-with-risks`；见 `quality/OPT-001-stage14-implementation-quality.md`。
- Stage 14 覆盖审计结论为 `ready-with-gaps`，可进入验收；见 `coverage/OPT-001-stage14-coverage-audit.md`。
- Stage 14 功能级验收已签收，结论为 `accepted-with-risks`；见 `acceptance/OPT-001-stage14-task-listing-typed-method-acceptance.md`。

### Stage 15 - WorkerSession typed DTO / envelope

- [x] `WorkerSessionQueryProvider` 新增 typed worker-session summary、message、message count 和 sync result 主方法。
- [x] 新增 `WorkerSessionSummary`、`WorkerSessionMessage`、`WorkerSessionMessageCount`、`WorkerSessionSyncResult` typed records。
- [x] `TaskDispatchFacade` worker-session provider fan-out 改为调用 typed methods，再转回 legacy REST payload。
- [x] Claude / LangGraph worker-session provider 实现 typed override，legacy Map 方法保留委派兼容。
- [x] 补充 session facade typed path、legacy Map default adapter 和 LangGraph typed provider 回归。
- [x] 完成实现质量门、测试覆盖审计和功能级验收签收。

当前完成范围：

- Stage 15 子计划已落档：`workitems/OPT-001-stage15-worker-session-typed-envelope.md`。
- `WorkerSessionQueryProvider` 已新增 `listWorkerSessionSummaries`、`getWorkerSessionMessageCountResult`、`listWorkerSessionMessages`、`syncWorkerSessionState`。
- `TaskDispatchFacade` worker-session fan-out 不再直接调用 `listWorkerSessions`、`getWorkerSessionMessageCount`、`getWorkerSessionMessages`、`syncWorkerSessions` legacy 方法。
- Claude / LangGraph worker-session 实现已迁移为 typed override，legacy Map 方法继续保留为委派 wrapper。
- `TaskDispatchFacadeTest#workerSessionTypedDefaultsAdaptLegacyMaps` 覆盖旧 Map provider 兼容。
- Stage 15 实现质量门结论为 `ready-with-risks`；见 `quality/OPT-001-stage15-implementation-quality.md`。
- Stage 15 覆盖审计结论为 `ready-with-gaps`，可进入验收；见 `coverage/OPT-001-stage15-coverage-audit.md`。
- Stage 15 功能级验收已签收，结论为 `accepted-with-risks`；见 `acceptance/OPT-001-stage15-worker-session-typed-envelope-acceptance.md`。

### Stage 16 - Claude worker-session provider bean split

- [x] 新增 `ClaudeWorkerSessionQueryService implements WorkerSessionQueryProvider`。
- [x] `ClaudeTaskService` 不再实现 `WorkerSessionQueryProvider`。
- [x] `ClaudeTaskService` 不再声明 worker-session capabilities。
- [x] Claude worker-session list/count/messages/sync typed 与 legacy SPI 方法迁移到新 service。
- [x] `ClaudeTaskService.syncLocalSessions(...)` 暂时保留为 sync 本地任务投影复用点。
- [x] 补充 Claude worker-session provider 和 task service 类型边界回归。
- [x] 完成实现质量门、测试覆盖审计和功能级验收签收。

当前完成范围：

- Stage 16 子计划已落档：`workitems/OPT-001-stage16-claude-worker-session-bean.md`。
- `ClaudeWorkerSessionQueryService` 已独立承接 `LIST_WORKER_SESSIONS`、`GET_WORKER_SESSION_MESSAGE_COUNT`、`GET_WORKER_SESSION_MESSAGES`、`SYNC_WORKER_SESSIONS`。
- `ClaudeTaskService` capability 已收窄到 task lookup / command / listing 相关能力，不再作为 worker-session provider 注册。
- worker ownership、Worker API client 调用、typed DTO / envelope 与 legacy Map wrapper 兼容语义保持不变。
- `ClaudeWorkerSessionQueryServiceTest` 覆盖 capabilities、list/count/messages/sync 和跨用户 worker 拒绝。
- Stage 16 实现质量门结论为 `ready-with-risks`；见 `quality/OPT-001-stage16-implementation-quality.md`。
- Stage 16 覆盖审计结论为 `ready-with-gaps`，可进入验收；见 `coverage/OPT-001-stage16-coverage-audit.md`。
- Stage 16 功能级验收已签收，结论为 `accepted-with-risks`；见 `acceptance/OPT-001-stage16-claude-worker-session-bean-acceptance.md`。

### Stage 17 - Legacy provider method deprecation gate

- [x] 盘点 legacy listing / worker-session provider 方法调用面和外部兼容边界。
- [x] `TaskListingProvider` legacy `Object` 方法标记 `@Deprecated(since = "1.3.1", forRemoval = false)`。
- [x] `WorkerSessionQueryProvider` legacy Map/List 方法标记 `@Deprecated(since = "1.3.1", forRemoval = false)`。
- [x] Claude / Codex / Codex Biz listing legacy wrappers 与 Claude / LangGraph worker-session legacy wrappers 同步标记 deprecation。
- [x] 新增反射回归锁定 legacy SPI methods 的 deprecation 契约。
- [x] 明确 removal gate：本阶段不删除方法，不设置 `forRemoval=true`，后续 removal 必须另起 workitem。
- [x] 完成实现质量门、测试覆盖审计和功能级验收签收。

当前完成范围：

- Stage 17 子计划已落档：`workitems/OPT-001-stage17-legacy-provider-method-deprecation.md`。
- session-module 生产 provider fan-out 已确认不直接调用 legacy listing / worker-session provider methods。
- `TaskListingProvider` 与 `WorkerSessionQueryProvider` legacy methods 已进入 deprecated 迁移窗口，typed default adapter 保留兼容调用。
- Claude / Codex / Codex Biz / LangGraph 内置 provider wrapper deprecation 已同步，直接依赖具体 service 的调用方也能收到迁移信号。
- `TaskProviderLegacyContractTest` 覆盖 SPI legacy methods 的 `since=1.3.1` 与 `forRemoval=false`。
- Stage 17 实现质量门结论为 `ready-with-risks`；见 `quality/OPT-001-stage17-implementation-quality.md`。
- Stage 17 覆盖审计结论为 `ready-with-gaps`，可进入验收；见 `coverage/OPT-001-stage17-coverage-audit.md`。
- Stage 17 功能级验收已签收，结论为 `accepted-with-risks`；见 `acceptance/OPT-001-stage17-legacy-provider-method-deprecation-acceptance.md`。

### Stage 18 - TaskCommandProvider cancel direct method

- [x] 盘点 command provider cancel legacy fallback 调用面和 A2A cancel 边界。
- [x] `TaskCommandProvider` 新增非 deprecated `cancelTaskDirect(String, String)` 主方法。
- [x] `TaskCommandProvider#cancelTask(String, String)` 保留兼容入口，并从 `forRemoval=true` 收敛为 `forRemoval=false`。
- [x] session provider cancel route 迁移到 `cancelTaskDirect`。
- [x] Claude / Codex / Codex Biz / Gemini / LangGraph provider 真实取消逻辑迁移到 direct method，legacy wrapper 仅委托。
- [x] 补充反射回归、provider-route 回归和 direct service call 回归。
- [x] 完成实现质量门、测试覆盖审计和功能级验收签收。

当前完成范围：

- Stage 18 子计划已落档：`workitems/OPT-001-stage18-task-command-cancel-direct-method.md`。
- `TaskCommandProvider#cancelTaskDirect` 已成为 provider command cancel 主调用契约；default implementation 兼容外部只 override legacy `cancelTask` 的 provider。
- `TaskOperationRouter` provider cancel route 不再直接调用 deprecated legacy cancel。
- Claude / Codex / Codex Biz / Gemini / LangGraph 内置 provider 真实取消逻辑已迁移到 `cancelTaskDirect`，legacy `cancelTask` wrapper 保留兼容。
- `TaskProviderLegacyContractTest` 覆盖 direct cancel 不 deprecated、legacy cancel deprecated 且 `forRemoval=false`。
- `TaskDispatchFacadeTest` 覆盖 session provider-route 调用 `cancelTaskDirect`，A2A cancel 分流保持 `agent.cancelTask`。
- Stage 18 实现质量门结论为 `ready-with-risks`；见 `quality/OPT-001-stage18-implementation-quality.md`。
- Stage 18 覆盖审计结论为 `ready-with-gaps`，可进入验收；见 `coverage/OPT-001-stage18-coverage-audit.md`。
- Stage 18 功能级验收已签收，结论为 `accepted-with-risks`；见 `acceptance/OPT-001-stage18-task-command-cancel-direct-method-acceptance.md`。

### Stage 19 - Migration support foundation

- [x] 审计现有 production schema migration 资产和启动时 migration 实现。
- [x] 新增 `DatabaseMigrationSupport`，集中承接 MySQL detection、INFORMATION_SCHEMA table / column / index 查询和 identifier quote。
- [x] `CodingAgentTenantScopeMigration` 迁移到共享 helper，移除本地 DataSource / Connection / INFORMATION_SCHEMA 重复逻辑。
- [x] `GeminiFlashRuntimeBudgetMigration` 迁移到共享 helper，保持启动时 MySQL-only、idempotent、warn-not-fail 语义。
- [x] 明确历史 `docs/migration/*.sql` 不自动执行，避免把一次性或人工修复脚本误纳入启动流程。
- [x] 补充 migration helper 与两个启动 migration 的行为单测。
- [x] 完成实现质量门、测试覆盖审计和功能级验收签收。

当前完成范围：

- Stage 19 子计划已落档：`workitems/OPT-001-stage19-migration-support-foundation.md`。
- 现有两个生产启动 migration 已共享 `DatabaseMigrationSupport`，减少后续 schema migration 继续复制 JDBC metadata / INFORMATION_SCHEMA 查询的风险。
- `docs/migration/*.sql` 保持人工运维脚本定位，本阶段未引入 Flyway/Liquibase，也未做历史 SQL 自动执行。
- `DatabaseMigrationSupportTest` 覆盖 MySQL detection、table / column / index 查询、single-column unique index 查询和 identifier quote。
- `CodingAgentTenantScopeMigrationTest` 与 `GeminiFlashRuntimeBudgetMigrationTest` 覆盖 MySQL/table guard、DDL/DML 执行条件和核心 SQL 参数。
- Stage 19 实现质量门结论为 `ready-with-risks`；见 `quality/OPT-001-stage19-implementation-quality.md`。
- Stage 19 覆盖审计结论为 `ready-with-gaps`，可进入验收；见 `coverage/OPT-001-stage19-coverage-audit.md`。
- Stage 19 功能级验收已签收，结论为 `accepted-with-risks`；见 `acceptance/OPT-001-stage19-migration-support-foundation-acceptance.md`。

### Stage 20 - Startup migration runner / manifest

- [x] 新增 Java startup migration 契约，要求 migration 显式声明稳定 `id` 与 `description`。
- [x] 新增统一 `DatabaseStartupMigrationRunner`，集中监听 `ApplicationReadyEvent` 并按 manifest 顺序执行。
- [x] 新增 `navigator.database.startup-migrations.enabled` 与 `dry-run` 配置，生产 profile 支持环境变量覆盖。
- [x] `CodingAgentTenantScopeMigration`、`GeminiFlashRuntimeBudgetMigration` 移除各自启动事件监听，改由 runner 编排。
- [x] runner 统一处理 disabled、non-MySQL、dry-run、单项失败继续和 manifest 日志。
- [x] 明确历史 `docs/migration/*.sql` 仍不自动执行，避免把人工脚本误纳入启动流程。
- [x] 完成实现质量门、测试覆盖审计和功能级验收签收。

当前完成范围：

- Stage 20 子计划已落档：`workitems/OPT-001-stage20-startup-migration-runner.md`。
- `DatabaseStartupMigrationRunner` 已成为 startup migration 的唯一启动事件监听入口；具体 migration class 只保留幂等迁移动作。
- migration manifest 通过 `DatabaseStartupMigrationDescriptor` 输出稳定 id / description，并按 id 排序。
- 默认行为保持兼容：默认 enabled、非 dry-run、MySQL 环境下继续运行现有两项 startup migrations。
- `DatabaseStartupMigrationRunnerTest` 覆盖 disabled、non-MySQL、dry-run、排序、失败继续和 manifest descriptor。
- Stage 20 实现质量门结论为 `ready-with-risks`；见 `quality/OPT-001-stage20-implementation-quality.md`。
- Stage 20 覆盖审计结论为 `ready-with-gaps`，可进入验收；见 `coverage/OPT-001-stage20-coverage-audit.md`。
- Stage 20 功能级验收已签收，结论为 `accepted-with-risks`；见 `acceptance/OPT-001-stage20-startup-migration-runner-acceptance.md`。

## Acceptance Criteria

1. `TaskDispatchFacade` 不再作为所有 Provider 操作的唯一超大聚合点，关键职责有明确边界和测试。
2. Provider route / backend / modelConfig 兼容规则有统一入口，新增 Provider 不需要复制多处分支。
3. `providerStateJson` / `taskStateJson` 至少对当前 Provider 的核心字段有 schema、版本和 codec 说明。
4. Claude、Codex、Gemini、LangGraph Biz 的 create / resume / cancel / reconnect 或等价核心路径有回归测试覆盖。
5. `UnifiedSseEmitter` 的部署边界和多实例策略已落文档；如实现改造，则有断线、心跳、订阅清理测试。
6. 生产 profile 配置风险完成收敛或形成明确部署检查表。
7. 架构文档与实际主链路一致，不再只描述 Claude 单 Provider 或旧 A2A 调用路径。
8. `TaskQueryProvider` 的查询、命令、列表和 worker-session 职责有窄端口边界，session 调用点不再统一依赖宽接口表达所有操作。
9. Provider listing/search 聚合结果有 typed envelope 主路径，统一投影层不再主要依赖 Map key / JavaBean getter 反射读取。
10. session Provider 注册与发现边界按窄端口列表表达，不再以宽 `List<TaskQueryProvider>` 作为唯一注入集合。
11. LangGraph worker-session 查询由独立 `WorkerSessionQueryProvider` bean 承接，任务生命周期服务不再声明 worker-session capability。
12. LangGraph task lifecycle service 不再实现聚合 `TaskQueryProvider`，只作为实际支持的 lookup / command 窄端口注册。
13. Gemini task lifecycle service 不再实现聚合 `TaskQueryProvider`，只作为实际支持的 lookup / command 窄端口注册。
14. Codex / Codex Biz provider 不再实现聚合 `TaskQueryProvider`，只作为实际支持的 lookup / command / listing 窄端口注册。
15. Claude provider 不再实现聚合 `TaskQueryProvider`，只作为实际支持的 lookup / command / listing / worker-session 窄端口注册。
16. `TaskListingProvider` listing/search 主调用契约有 typed methods，session provider fan-out 不再直接依赖 legacy `Object` 返回方法。
17. `WorkerSessionQueryProvider` worker-session 主调用契约有 typed DTO / envelope methods，session provider fan-out 不再直接依赖 legacy Map 返回方法。
18. Claude worker-session 查询由独立 `WorkerSessionQueryProvider` bean 承接，`ClaudeTaskService` 不再声明 worker-session capability。
19. Legacy listing / worker-session provider methods 明确标记 `@Deprecated(since = "1.3.1", forRemoval = false)`，并以 removal gate 管控后续删除。
20. `TaskCommandProvider` provider direct cancel 主调用契约不再依赖 legacy `cancelTask(String, String)` 方法，session provider-route 与内置 provider 均以 `cancelTaskDirect(String, String)` 为主路径。
21. 生产启动 migration 的共性 MySQL metadata / INFORMATION_SCHEMA 判断有共享 helper，现有启动 migration 不再各自复制底层 schema 检查逻辑，历史一次性 SQL 不被误自动执行。
22. Java startup migrations 由统一 manifest runner 编排，具备稳定 id / description、确定性排序、enabled / dry-run 运维开关和单项失败 warn-not-fail 语义，历史一次性 SQL 仍不被误自动执行。

## Constraints / Non-Goals

- 不在本优化中改动前端交互形态，除非后端契约变化必须同步。
- 不改变当前 REST / OpenAPI / SDK 的对外语义，除非另起兼容迁移项。
- 不一次性重写所有 Provider；优先从职责边界和回归测试开始。
- 不把 `agent-framework` 进程内 LLM Agent 路径与 Worker/A2A 任务路径强行合并。
- 不在未建立测试基线前移动高风险状态机代码。

## Required Review / Audit Workflow

- 进入实现前：补齐方法级职责清单和测试基线。
- 每个阶段完成后：执行 `execution-checkin` 写回进度。
- 大规模拆分或状态 schema 变更后：执行 `foggy-implementation-quality-gate`。
- 测试证据齐备后：执行 `foggy-test-coverage-audit`。
- 版本收口前：执行 `foggy-acceptance-signoff`。

## Verification Plan

建议基线命令：

```powershell
mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am
```

重点测试集合：

- `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
- `session-module/src/test/java/com/foggy/navigator/session/registry/UnifiedAgentResolverTest.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/SessionBindingServiceTest.java`
- `session-module/src/test/java/com/foggy/navigator/session/sse/UnifiedSseEmitterTest.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/WorkerStreamRelayTest.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService*.java`
- `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexTaskServiceTest.java`
- `addons/gemini-worker-agent/src/test/java/com/foggy/navigator/gemini/worker/**/*Test.java`
- `addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/**/*Test.java`

## Stage 0 Baseline Results

执行日期：2026-06-25。

| Scope | Command summary | Result |
| --- | --- | --- |
| `session-module` | `mvn test -pl session-module -am '-Dtest=TaskDispatchFacadeTest,UnifiedAgentResolverTest,SessionBindingServiceTest,UnifiedSseEmitterTest' '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false'` | PASS：81 tests，0 failures，0 errors，0 skipped |
| `addons/claude-worker-agent` | `mvn test -pl addons/claude-worker-agent -am '-Dtest=ClaudeTaskService*Test,WorkerStreamRelayTest' '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false'` | PASS：46 tests，0 failures，0 errors，0 skipped |
| `addons/codex-worker-agent` | `mvn test -pl addons/codex-worker-agent -am '-Dtest=CodexTaskServiceTest,CodexStreamRelayTest' '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false'` | PASS：23 tests，0 failures，0 errors，0 skipped |
| `addons/gemini-worker-agent` | `mvn test -pl addons/gemini-worker-agent -am '-Dtest=GeminiTaskServiceAuthResolutionTest,GeminiStreamRelayTest,GeminiWorkerAgentProviderTest,GeminiWorkerClientTest' '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false'` | PASS：12 tests，0 failures，0 errors，0 skipped |
| `addons/langgraph-biz-worker` | `mvn test -pl addons/langgraph-biz-worker -am '-Dtest=LanggraphTaskServiceTest,LanggraphTaskServiceApprovalTest,LanggraphStreamRelayTest,LanggraphWorkerAgentProviderTest,LanggraphWorkerInnerA2aAgentTest,LanggraphBusinessAgentWorkerTaskLauncherTest,LanggraphWorkerResumeEventListenerTest' '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false'` | PASS：74 tests，0 failures，0 errors，0 skipped |

合计：236 tests，0 failures，0 errors，0 skipped。

PowerShell 备注：`-Dtest=...`、`-DfailIfNoTests=false`、`-Dsurefire.failIfNoSpecifiedTests=false` 已使用单引号包裹作为基线命令。未加引号的两次早期尝试在测试执行前被 PowerShell / Maven 参数解析拦截，不计入测试失败。

## Stage 1 Route Registry Results

执行日期：2026-06-25。

| Scope | Command summary | Result |
| --- | --- | --- |
| `navigator-common` | `mvn test -pl navigator-common -am '-Dtest=ProviderRouteRegistryTest' '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false'` | PASS：6 tests，0 failures，0 errors，0 skipped |
| `session-module` | `mvn test -pl session-module -am '-Dtest=TaskDispatchFacadeTest,UnifiedAgentResolverTest' '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false'` | PASS：60 tests，0 failures，0 errors，0 skipped |
| Worker provider adapters | `mvn test -pl addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am '-Dtest=ClaudeWorkerAgentProviderTest,CodexWorkerAgentProviderTest,GeminiWorkerAgentProviderTest,LanggraphWorkerAgentProviderTest' '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false'` | PASS：21 tests，0 failures，0 errors，0 skipped |
| Affected Java reactor | `mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am` | PASS：1475 tests，0 failures，0 errors，0 skipped |

定向回归合计：87 tests，0 failures，0 errors，0 skipped。受影响 Java reactor 完整回归：1475 tests，0 failures，0 errors，0 skipped。

## Stage 1 Follow-up / Stage 2.1 Results

执行日期：2026-06-25。

| Scope | Command summary | Result |
| --- | --- | --- |
| `session-module` provider inference | `mvn test -pl session-module -am '-Dtest=ProviderRouteRegistryTest,JpaSessionManagerTest' '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false'` | PASS：28 tests，0 failures，0 errors，0 skipped |
| backend capability migration | `mvn test -pl navigator-common,metadata-config-module,business-agent-module,addons/claude-worker-agent -am '-Dtest=ProviderRouteRegistryTest,LlmModelManagerImplTest,ClientAppModelConfigGrantServiceTest,BusinessAgentTaskServiceTest,OpenApiAgentReadinessServiceTest,OpenApiControllerMessageMappingTest' '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false'` | PASS：118 tests，0 failures，0 errors，0 skipped |
| Stage 2.1 facade lookup extraction | `mvn test -pl session-module -am '-Dtest=TaskDispatchFacadeTest,JpaSessionManagerTest,ProviderRouteRegistryTest' '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false'` | PASS：78 tests，0 failures，0 errors，0 skipped |
| affected Java reactor | `mvn test -pl navigator-common,metadata-config-module,business-agent-module,session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am` | PASS：1522 tests，0 failures，0 errors，0 skipped |

说明：最后一项为本轮改动涉及模块及其 `-am` 依赖的完整回归，覆盖公共注册表、metadata config、business agent、session、Claude/Codex/Gemini/LangGraph worker addon。

## Progress Tracking

### Development Progress

| Item | Status | Notes |
| --- | --- | --- |
| Java 架构 review 风险落档 | done | 本文档记录风险、目标、阶段和验收标准。 |
| A2A 架构文档同步 | done | `docs/a2a-agent-architecture.md` 已同步到当前多 Provider / TaskQueryProvider 口径。 |
| 方法级职责清单 | done | 见 `workitems/OPT-001-java-method-responsibility-inventory.md`。 |
| Stage 1 路由注册表 | done | 新增 `ProviderRouteRegistry`，统一 session 与四个 worker adapter 的 backend/provider 规则。 |
| Stage 1 后续映射收口 | done | `JpaSessionManager`、配置服务、业务接入和 OpenAPI readiness / diagnostics 已复用公共注册表。 |
| Stage 2.1 Provider 查找拆分 | done | 新增 `TaskQueryProviderRegistry`，Facade 的 provider 遍历与查找入口已集中。 |
| Stage 2.2 创建目标推导拆分 | done | 新增 `TaskCreateTargetResolver`，Facade create 路由推导已迁出。 |
| Stage 2.3 统一投影拆分 | done | 新增 `UnifiedSessionTaskProjectionService`，统一 session-store 查询和 DTO 投影已迁出。 |
| Stage 2.4 Provider capability 描述 | done | 新增 `TaskQueryCapability`，Provider fan-out 查询已可按能力声明缩小候选。 |
| Stage 2.5 任务操作路由拆分 | done | 新增 `TaskOperationRouter`，Facade 的任务操作入口已改为委托。 |
| Stage 2 实现质量门 | done | 见 `quality/OPT-001-implementation-quality.md`；decision=`ready-with-risks`，可进入测试覆盖审计。 |
| Stage 2 测试覆盖审计 | done | 见 `coverage/OPT-001-stage1-stage2-coverage-audit.md`；conclusion=`ready-for-acceptance`，覆盖范围为 Stage 1/2。 |
| Stage 1/2 功能级验收签收 | done | 见 `acceptance/OPT-001-stage1-stage2-acceptance.md`；decision=`accepted`，后续继续推进 Stage 3。 |
| Stage 3.1 Provider 状态 codec 基线 | done | 新增 `ProviderStateCodec` 并接入 `TaskDispatchFacade` context/diagnostic metadata 写入路径，新状态开始写入 `schemaVersion=1` 与 `providerType`。 |
| Stage 3.2 provider session state 迁移 | done | Claude/Codex/Gemini 的 `providerStateJson` 读写已迁移到 `ProviderStateCodec`；legacy/schema v1、坏 JSON、未知字段保留和清空 session id 已覆盖。 |
| Stage 3.3 provider task state 迁移 | done | Claude/Codex/Gemini/LangGraph 的 `taskStateJson` 写入已迁移到 `ProviderStateCodec`；既有诊断字段、context 和 provider 私有字段保留。 |
| Stage 3.4 quality / coverage closure | done | 已补 schema v1 `DispatchTaskDTO` 直接投影回归；Stage 3 质量门见 `quality/OPT-001-stage3-implementation-quality.md`，覆盖审计见 `coverage/OPT-001-stage3-coverage-audit.md`。 |
| Stage 3 功能级验收签收 | done | 见 `acceptance/OPT-001-stage3-provider-state-schema-acceptance.md`；decision=`accepted-with-risks`，无阻断项。 |
| Stage 4 SSE 部署边界治理 | done | 见 `workitems/OPT-001-stage4-sse-deployment-boundary.md`；已完成单实例边界、粘性会话策略、发送失败清理硬化和状态推送补测。 |
| Stage 4 功能级验收签收 | done | 见 `acceptance/OPT-001-stage4-sse-deployment-boundary-acceptance.md`；decision=`accepted-with-risks`，无阻断项。 |
| Stage 5 运行配置硬化 | done | 见 `workitems/OPT-001-stage5-runtime-config-hardening.md`；已完成 launcher dev/prod profile 拆分、生产启动 guard、部署检查表和回归测试。 |
| Stage 5 功能级验收签收 | done | 见 `acceptance/OPT-001-stage5-runtime-config-hardening-acceptance.md`；decision=`accepted-with-risks`，无阻断项。 |
| Stage 6 TaskQueryProvider 窄端口治理 | done | 见 `workitems/OPT-001-stage6-task-query-provider-port-split.md`；已完成兼容式窄端口 SPI、Registry typed views、session 调用点收窄、质量门、覆盖审计和签收。 |
| Stage 7 Provider listing/search typed envelope | done | 见 `workitems/OPT-001-stage7-provider-listing-envelope.md`；已完成 typed envelope、projection typed-first 解析、Provider SPI 迁移、质量门、覆盖审计和签收。 |
| Stage 8 Provider port 注入收窄 | done | 见 `workitems/OPT-001-stage8-provider-port-injection.md`；已完成 facade 四类端口列表注入、registry 分集合维护、lookup/command 分离路由、兼容构造和签收。 |
| Stage 9 LangGraph worker-session 端口拆分 | done | 见 `workitems/OPT-001-stage9-langgraph-worker-session-split.md`；已完成独立 `WorkerSessionQueryProvider`、capability 迁移、session fan-out 回归、质量门、覆盖审计和签收。 |
| Stage 10 LangGraph narrow port bean 迁移 | done | 见 `workitems/OPT-001-stage10-langgraph-narrow-port-bean.md`；已完成 LangGraph task service 退出聚合 `TaskQueryProvider`、lookup/command 窄端口注册、类型边界回归、质量门、覆盖审计和签收。 |
| Stage 11 Gemini narrow port bean 迁移 | done | 见 `workitems/OPT-001-stage11-gemini-narrow-port-bean.md`；已完成 Gemini task service 退出聚合 `TaskQueryProvider`、lookup/command 窄端口注册、类型边界回归、质量门、覆盖审计和签收。 |
| Stage 12 Codex / Codex Biz narrow port bean 迁移 | done | 见 `workitems/OPT-001-stage12-codex-narrow-port-bean.md`；已完成 Codex / Codex Biz 退出聚合 `TaskQueryProvider`、lookup/command/listing 窄端口注册、类型边界回归、质量门、覆盖审计和签收。 |
| Stage 13 Claude narrow port bean 迁移 | done | 见 `workitems/OPT-001-stage13-claude-narrow-port-bean.md`；已完成 Claude 退出聚合 `TaskQueryProvider`、lookup/command/listing/worker-session 窄端口注册、类型边界回归、质量门、覆盖审计和签收。 |
| Stage 14 TaskListingProvider typed method contract | done | 见 `workitems/OPT-001-stage14-task-listing-typed-method.md`；已完成 typed listing/search 主方法、legacy envelope adapter、session fan-out typed 迁移、Claude/Codex typed override、质量门、覆盖审计和签收。 |
| Stage 15 WorkerSession typed DTO / envelope | done | 见 `workitems/OPT-001-stage15-worker-session-typed-envelope.md`；已完成 worker-session typed records、SPI typed methods、session fan-out typed 迁移、Claude/LangGraph typed override、质量门、覆盖审计和签收。 |
| Stage 16 Claude worker-session provider bean split | done | 见 `workitems/OPT-001-stage16-claude-worker-session-bean.md`；已完成独立 `ClaudeWorkerSessionQueryService`、Claude task service worker-session 端口移除、类型边界回归、质量门、覆盖审计和签收。 |
| Stage 17 Legacy provider method deprecation gate | done | 见 `workitems/OPT-001-stage17-legacy-provider-method-deprecation.md`；已完成 legacy listing/worker-session 方法调用面审计、SPI/provider wrapper deprecation、反射回归、质量门、覆盖审计和签收。 |
| Stage 18 TaskCommandProvider cancel direct method | done | 见 `workitems/OPT-001-stage18-task-command-cancel-direct-method.md`；已完成 provider command cancel direct method、session provider-route 迁移、内置 provider direct cancel 迁移、反射回归、质量门、覆盖审计和签收。 |
| Stage 19 Migration support foundation | done | 见 `workitems/OPT-001-stage19-migration-support-foundation.md`；已完成共享 migration helper、现有启动 migration 去重、历史 SQL 自动执行边界确认、质量门、覆盖审计和签收。 |
| Stage 20 Startup migration runner / manifest | done | 见 `workitems/OPT-001-stage20-startup-migration-runner.md`；已完成统一 startup migration runner、manifest、enabled/dry-run 配置、既有启动 migration 迁移、质量门、覆盖审计和签收。 |
| 代码实现 | in-progress | Stage 1/2、Stage 3、Stage 4、Stage 5、Stage 6、Stage 7、Stage 8、Stage 9、Stage 10、Stage 11、Stage 12、Stage 13、Stage 14、Stage 15、Stage 16、Stage 17、Stage 18、Stage 19、Stage 20 均已签收；剩余后续重点是 migration version table / execution record、真实 MySQL smoke、可选的 Claude sync 本地投影 service 化，以及 legacy listing/worker-session/command cancel 方法在至少一个版本周期后的 removal 评估。 |

### Testing Progress

| Scope | Status | Notes |
| --- | --- | --- |
| 静态文档校验 | done | 已检查版本目录、工作项和架构文档路径。 |
| Java 单元/集成测试 | done | Stage 0 核心基线通过：Session 81、Claude 46、Codex 23、Gemini 12、LangGraph 74，合计 236 tests。 |
| 回归基线 | done | 已记录核心命令、范围和结果，后续实现阶段以本节为回归参照。 |
| Stage 1 路由回归 | done | `ProviderRouteRegistryTest`、`TaskDispatchFacadeTest`、`UnifiedAgentResolverTest`、四个 `*WorkerAgentProviderTest` 通过，合计 87 tests。 |
| Stage 1 受影响 reactor 完整回归 | done | `session-module` + Claude/Codex/Gemini/LangGraph worker addons + `-am` 依赖完整通过，合计 1475 tests。 |
| Stage 1 后续映射回归 | done | session provider inference 28 tests；backend capability migration 118 tests。 |
| Stage 2.1 回归 | done | `TaskDispatchFacadeTest`、`JpaSessionManagerTest`、`ProviderRouteRegistryTest` 合计 78 tests。 |
| Stage 2.2~2.4 定向回归 | done | `TaskDispatchFacadeTest`、`TaskQueryProviderRegistryTest`、Codex/Gemini/LangGraph Provider 核心测试通过，合计 111 tests；Claude addon 纳入编译。 |
| Stage 2.2~2.4 受影响 reactor 完整回归 | done | `navigator-common`、metadata、business、session、四个 worker addon 与 `-am` 依赖完整通过，合计 1525 tests。 |
| Stage 2.5 定向回归 | done | `TaskDispatchFacadeTest`、`TaskQueryProviderRegistryTest` 通过，合计 53 tests。 |
| Stage 2.5 session 全量回归 | done | `mvn test -pl session-module -am` 通过，合计 239 tests。 |
| Stage 2 质量门回归 | done | 清理不可达 cancel fallback 后，定向回归 53 tests、session 全量 239 tests 均通过。 |
| Stage 2 覆盖补测与审计 | done | 新增 `reconnect`、`resync`、`scan checkpoints` 路由断言，并补 direct route `attachments` 透传断言；定向回归 56 tests、session 全量 242 tests 均通过。 |
| Stage 3.1 Provider 状态 codec 回归 | done | `ProviderStateCodecTest`、`ProviderRouteRegistryTest`、`TaskDispatchFacadeTest` 定向回归通过：common 14 tests、session 53 tests；`mvn test -pl session-module -am` 通过，合计 242 tests。 |
| Stage 3.2 Codex/Gemini provider session state 回归 | done | 定向回归：ProviderStateCodec 5、CodexTaskService 20、GeminiTaskServiceAuthResolution 8 tests；Codex/Gemini addon full reactor 通过：common 15、session 242、Codex 57、Gemini 14 tests。 |
| Stage 3.2 Claude provider session state 回归 | done | 定向回归：ProviderStateCodec 5、ClaudeTaskServiceAuth 20、ClaudeTaskServiceRewind 5、ClaudeTaskServiceSync 7、ConversationConfigService 13 tests；Claude addon full reactor `mvn test -pl addons/claude-worker-agent -am` 通过，合计 312 tests。 |
| Stage 3.3 provider task state 回归 | done | 定向回归：ClaudeTaskServiceAuth 21、CodexTaskService 20、GeminiTaskServiceAuthResolution 8、LanggraphTaskService 24 tests；受影响 Provider reactor `mvn test -pl addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am` 通过，合计 1503 tests。 |
| Stage 3.4 投影与覆盖审计回归 | done | `TaskDispatchFacadeTest` 54 tests pass；`mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am` 通过，Surefire XML 合计 219 suites / 1504 tests。 |
| Stage 4 SSE 定向回归 | done | `UnifiedSseEmitterTest`、`UnifiedSseControllerTest`、`SessionEventListenerTest`、`TaskUpdateNotifierTest` 通过，合计 26 tests。 |
| Stage 4 session 全量回归 | done | `mvn test -pl session-module -am` 通过，合计 250 tests。 |
| Stage 5 launcher guard 定向回归 | done | `ProductionConfigurationGuardTest` 通过，合计 4 tests。 |
| Stage 5 launcher 受影响 reactor 回归 | done | `mvn test -pl launcher -am` 通过，Surefire XML 合计 250 reports / 1756 tests，0 failures，0 errors，0 skipped。 |
| Stage 6 session focused regression | done | `TaskQueryProviderRegistryTest`、`TaskDispatchFacadeTest` 通过，合计 62 tests。 |
| Stage 6 affected reactor regression | done | `mvn test -pl navigator-spi,session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am` 通过，Surefire XML 合计 220 reports / 1520 tests，0 failures，0 errors，0 skipped。 |
| Stage 8 session focused regression | done | `TaskQueryProviderRegistryTest`、`TaskDispatchFacadeTest` 通过，合计 63 tests。 |
| Stage 8 affected reactor regression | done | `mvn test -pl navigator-spi,session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am` 通过，Surefire XML 合计 221 reports / 1525 tests，0 failures，0 errors，0 skipped。 |
| Stage 9 LangGraph focused regression | done | `LanggraphWorkerSessionQueryServiceTest`、`LanggraphTaskServiceTest` 通过，合计 26 tests。 |
| Stage 9 session focused regression | done | `TaskDispatchFacadeTest`、`TaskQueryProviderRegistryTest` 通过，合计 64 tests。 |
| Stage 9 affected reactor regression | done | `mvn test -pl session-module,addons/langgraph-biz-worker -am` 通过，Surefire XML 合计 162 reports / 1148 tests，0 failures，0 errors，0 skipped。 |
| Stage 10 LangGraph focused regression | done | `LanggraphTaskServiceTest`、`LanggraphWorkerSessionQueryServiceTest` 通过，合计 27 tests。 |
| Stage 10 session focused regression | done | `TaskDispatchFacadeTest`、`TaskQueryProviderRegistryTest` 通过，合计 64 tests。 |
| Stage 10 affected reactor regression | done | `mvn test -pl session-module,addons/langgraph-biz-worker -am` 通过，Surefire XML 合计 162 reports / 1149 tests，0 failures，0 errors，0 skipped。 |
| Stage 11 Gemini focused regression | done | `GeminiTaskServiceAuthResolutionTest`、`GeminiStreamRelayTest`、`GeminiWorkerAgentProviderTest`、`GeminiWorkerClientTest` 通过，合计 15 tests。 |
| Stage 11 session focused regression | done | `TaskDispatchFacadeTest`、`TaskQueryProviderRegistryTest` 通过，合计 64 tests。 |
| Stage 11 affected reactor regression | done | `mvn test -pl session-module,addons/gemini-worker-agent -am` 通过，Surefire XML 合计 578 tests，0 failures，0 errors，0 skipped。 |
| Stage 12 Codex focused regression | done | `CodexTaskServiceTest`、`CodexBizTaskProviderTest`、`CodexStreamRelayTest`、`CodexWorkerAgentProviderTest`、`CodexWorkerA2aAgentTest`、`CodexWorkerFacadeImplTest`、`CodexWorkerClientTest` 通过，合计 59 tests。 |
| Stage 12 session focused regression | done | `TaskDispatchFacadeTest`、`TaskQueryProviderRegistryTest` 通过，合计 64 tests。 |
| Stage 12 affected reactor regression | done | `mvn test -pl session-module,addons/codex-worker-agent -am` 通过，Surefire XML 合计 622 tests，0 failures，0 errors，0 skipped。 |
| Stage 13 Claude focused regression | done | `ClaudeTaskService*Test`、`WorkerStreamRelayTest`、`ClaudeWorkerAgentProviderTest`、`ClaudeWorkerA2aAgentTest`、`ClaudeWorkerClientTest` 通过，合计 90 tests。 |
| Stage 13 session focused regression | done | `TaskDispatchFacadeTest`、`TaskQueryProviderRegistryTest` 通过，合计 64 tests。 |
| Stage 13 affected reactor regression | done | `mvn test -pl session-module,addons/claude-worker-agent -am` 通过，Surefire XML 合计 1320 tests，0 failures，0 errors，0 skipped。 |
| Stage 13 static scan / diff check | done | `rg "implements TaskQueryProvider"` 仅剩 session 测试 stub；`git diff --check` 无 whitespace error，仅 CRLF normalization warnings。 |
| Stage 14 targeted regression | done | `TaskDispatchFacadeTest`、`UnifiedSessionTaskProjectionServiceTest`、`TaskQueryProviderRegistryTest`、`CodexTaskServiceTest`、`CodexBizTaskProviderTest`、`ClaudeTaskServiceAuthTest` 通过，合计 121 tests。 |
| Stage 14 affected reactor regression | done | `mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent -am` 通过，Surefire XML 合计 191 suites / 1380 tests，0 failures，0 errors，0 skipped。 |
| Stage 14 static scan / diff check | done | `rg "provider\\.(listTasksPaged|searchSessions|listTasksByDirectoryPaged)\\("` 无生产 fan-out legacy 调用；`git diff --check` 无 whitespace error，仅 CRLF normalization warnings。 |
| Stage 15 targeted regression | done | `TaskDispatchFacadeTest`、`TaskQueryProviderRegistryTest`、`ClaudeTaskServiceAuthTest`、`LanggraphWorkerSessionQueryServiceTest`、`LanggraphTaskServiceTest` 通过，合计 114 tests。 |
| Stage 15 affected direct reactor regression | done | `mvn test -pl session-module,addons/claude-worker-agent,addons/langgraph-biz-worker -am` 通过，Surefire XML 合计 208 reports / 1461 tests，0 failures，0 errors，0 skipped。 |
| Stage 15 broader Java worker reactor regression | done | `mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am` 通过，Surefire XML 合计 221 reports / 1535 tests，0 failures，0 errors，0 skipped。 |
| Stage 15 static scan / diff check | done | `rg "provider\\.(listWorkerSessions|getWorkerSessionMessageCount|getWorkerSessionMessages|syncWorkerSessions)\\("` 无生产 fan-out legacy 调用；`git diff --check` 无 whitespace error，仅 CRLF normalization warnings。 |
| Stage 16 targeted regression | done | `TaskDispatchFacadeTest`、`TaskQueryProviderRegistryTest`、`ClaudeTaskServiceAuthTest`、`ClaudeWorkerSessionQueryServiceTest`、`ClaudeTaskServiceSyncTest` 通过，合计 100 tests。 |
| Stage 16 affected reactor regression | done | `mvn test -pl session-module,addons/claude-worker-agent -am` 通过，Surefire XML 合计 183 reports / 1328 tests，0 failures，0 errors，0 skipped。 |
| Stage 16 static scan / diff check | done | `ClaudeTaskService.java` worker-session provider 关键词无匹配；`ClaudeWorkerSessionQueryService.java` 独立实现 worker-session provider；`git diff --check` 无 whitespace error，仅 CRLF normalization warnings。 |
| Stage 17 targeted regression | done | `TaskProviderLegacyContractTest`、`TaskDispatchFacadeTest`、`TaskQueryProviderRegistryTest`、`ClaudeTaskServiceAuthTest`、`ClaudeWorkerSessionQueryServiceTest`、`CodexTaskServiceTest`、`CodexBizTaskProviderTest`、`LanggraphWorkerSessionQueryServiceTest` 通过，合计 131 tests。 |
| Stage 17 affected reactor regression | done | `mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/langgraph-biz-worker -am` 通过，Surefire XML 合计 219 reports / 1528 tests，0 failures，0 errors，0 skipped。 |
| Stage 17 static scan / diff check | done | `rg "provider\\.(listTasksPaged|searchSessions|listTasksByDirectoryPaged|listWorkerSessions|getWorkerSessionMessageCount|getWorkerSessionMessages|syncWorkerSessions)\\("` 无生产 fan-out legacy 调用；deprecated annotation fixed-string scan 命中 24 处 expected annotations；`git diff --check` 无 whitespace error，仅 CRLF normalization warnings。 |
| Stage 18 targeted regression | done | `TaskProviderLegacyContractTest`、`TaskDispatchFacadeTest`、`TaskQueryProviderRegistryTest`、`ClaudeTaskServiceAuthTest`、`CodexTaskServiceTest`、`CodexBizTaskProviderTest`、`GeminiTaskServiceAuthResolutionTest`、`LanggraphTaskServiceTest`、`LanggraphWorkerInnerA2aAgentTest` 通过，Surefire XML 合计 11 reports / 159 tests，0 failures，0 errors，0 skipped。 |
| Stage 18 affected reactor regression | done | `mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am` 通过，Surefire XML 合计 223 reports / 1545 tests，0 failures，0 errors，0 skipped。 |
| Stage 18 static scan / diff check | done | `rg "provider\\.cancelTask\\(" session-module/src/main/java` 无匹配；`cancelTaskDirect` direct usage scan 覆盖 SPI、session route 与内置 provider；`TaskCommandProvider` legacy `forRemoval=true` scan 无匹配；`git diff --check` 无 whitespace error，仅 CRLF normalization warnings。 |
| Stage 19 targeted regression | done | `DatabaseMigrationSupportTest`、`CodingAgentTenantScopeMigrationTest`、`GeminiFlashRuntimeBudgetMigrationTest`、`CommonAutoConfigurationTest` 通过，合计 12 tests，0 failures，0 errors，0 skipped。 |
| Stage 19 affected reactor regression | done | `mvn test -pl launcher -am` 通过，Surefire XML 合计 250 reports / 1669 tests，0 failures，0 errors，0 skipped。 |
| Stage 19 static scan / diff check | done | main code 未发现 `docs/migration` / `ResourceDatabasePopulator` / `ScriptUtils` 自动执行历史 SQL；`DatabaseMigrationSupport` usage scan 仅命中预期 migration 与测试；`git diff --check` 无 whitespace error，仅 CRLF normalization warnings。 |
| Stage 20 targeted regression | done | `DatabaseStartupMigrationRunnerTest`、`DatabaseMigrationSupportTest`、`CodingAgentTenantScopeMigrationTest`、`GeminiFlashRuntimeBudgetMigrationTest`、`CommonAutoConfigurationTest` 通过，合计 17 tests，0 failures，0 errors，0 skipped。 |
| Stage 20 affected reactor regression | done | `mvn test -pl launcher -am` 通过，Surefire XML 合计 251 reports / 1674 tests，0 failures，0 errors，0 skipped。 |
| Stage 20 static scan / diff check | done | startup migration event listener 只剩统一 runner；main code 未发现 `docs/migration` / `ResourceDatabasePopulator` / `ScriptUtils` 自动执行历史 SQL；`git diff --check` 无 whitespace error，仅 CRLF normalization warnings。 |

### Experience Progress

experience: N/A

原因：本工作项当前为 Java 后端架构治理与文档同步，不涉及新增或修改 UI 页面、表单、列表、弹窗、按钮交互或权限可见性。

## Execution Checklist

- [x] 记录事项类型、版本、优先级、状态和 owner。
- [x] 记录背景、问题陈述、目标结果和非目标。
- [x] 记录模块 ownership 和代码触点。
- [x] 记录验收标准、验证计划和后续评审流程。
- [x] 记录 development / testing / experience progress。
- [x] Stage 0 测试基线完成后回写本文档。
- [x] Stage 0 方法级职责清单已落档。
- [x] Stage 1 路由注册表实现完成后补充 execution-checkin。
- [x] Stage 1 后续 provider/backend 映射收口完成后补充 execution-checkin。
- [x] Stage 2.1 Provider 查找拆分完成后补充 execution-checkin。
- [x] Stage 2.2 创建目标推导拆分完成后补充 execution-checkin。
- [x] Stage 2.3 统一 session-store 查询/投影拆分完成后补充 execution-checkin。
- [x] Stage 2.4 Provider capability 描述完成后补充 execution-checkin。
- [x] Stage 2.5 任务操作路由拆分完成后补充 execution-checkin。
- [x] Stage 2 实现质量门完成后补充质量记录与进度回写。
- [x] Stage 2 测试覆盖审计完成后补充覆盖记录与进度回写。
- [x] Stage 1/2 功能级验收签收完成后补充 acceptance 记录与进度回写。
- [x] Stage 3.1 Provider 状态 codec 基线完成后补充 execution-checkin。
- [x] Stage 3.2 Codex/Gemini provider session state 迁移完成后补充 execution-checkin。
- [x] Stage 3.2 Claude provider session state 迁移完成后补充 execution-checkin。
- [x] Stage 3.3 Provider task state 迁移完成后补充 execution-checkin。
- [x] Stage 3.4 投影回归、质量门和覆盖审计完成后补充记录。
- [x] Stage 3 功能级验收签收完成后补充 acceptance 记录。
- [x] Stage 4 SSE 部署边界治理完成后补充 execution-checkin。
- [x] Stage 4 功能级验收签收完成后补充 acceptance 记录。
- [x] Stage 5 运行配置硬化完成后补充 execution-checkin。
- [x] Stage 5 功能级验收签收完成后补充 acceptance 记录。
- [x] Stage 6 TaskQueryProvider 窄端口治理完成后补充 execution-checkin。
- [x] Stage 6 实现质量门、覆盖审计和功能级验收签收完成后补充记录。
- [x] Stage 7 Provider listing/search typed envelope 完成后补充 execution-checkin、质量门、覆盖审计和签收记录。
- [x] Stage 8 Provider port 注入收窄完成后补充 execution-checkin、质量门、覆盖审计和签收记录。
- [x] Stage 9 LangGraph worker-session 端口拆分完成后补充 execution-checkin、质量门、覆盖审计和签收记录。
- [x] Stage 10 LangGraph narrow port bean 迁移完成后补充 execution-checkin、质量门、覆盖审计和签收记录。
- [x] Stage 11 Gemini narrow port bean 迁移完成后补充 execution-checkin、质量门、覆盖审计和签收记录。
- [x] Stage 12 Codex / Codex Biz narrow port bean 迁移完成后补充 execution-checkin、质量门、覆盖审计和签收记录。
- [x] Stage 13 Claude narrow port bean 迁移完成后补充 execution-checkin、质量门、覆盖审计和签收记录。
- [x] Stage 14 TaskListingProvider typed method contract 完成后补充 execution-checkin、质量门、覆盖审计和签收记录。
- [x] Stage 15 WorkerSession typed DTO / envelope 完成后补充 execution-checkin、质量门、覆盖审计和签收记录。
- [x] Stage 16 Claude worker-session provider bean split 完成后补充 execution-checkin、质量门、覆盖审计和签收记录。
- [x] Stage 17 Legacy provider method deprecation gate 完成后补充 execution-checkin、质量门、覆盖审计和签收记录。
- [x] Stage 18 TaskCommandProvider cancel direct method 完成后补充 execution-checkin、质量门、覆盖审计和签收记录。
- [x] Stage 19 Migration support foundation 完成后补充 execution-checkin、质量门、覆盖审计和签收记录。
- [x] Stage 20 Startup migration runner / manifest 完成后补充 execution-checkin、质量门、覆盖审计和签收记录。
- [ ] 每个后续实现阶段完成后补充 execution-checkin。

## Acceptance Status

### Stage 1/2

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: Codex
- signed_off_at: 2026-06-25
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage1-stage2-acceptance.md
- acceptance_scope: OPT-001 Stage 1/2 only
- blocking_items: none
- follow_up_required: no

### Stage 3

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-25
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage3-provider-state-schema-acceptance.md
- acceptance_scope: OPT-001 Stage 3 provider state schema only
- blocking_items: none
- follow_up_required: yes

### Stage 4

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-25
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage4-sse-deployment-boundary-acceptance.md
- acceptance_scope: OPT-001 Stage 4 SSE deployment boundary only
- blocking_items: none
- follow_up_required: yes

### Stage 5

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-25
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage5-runtime-config-hardening-acceptance.md
- acceptance_scope: OPT-001 Stage 5 runtime config hardening only
- blocking_items: none
- follow_up_required: yes

### Stage 6

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-25
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage6-task-query-provider-port-split-acceptance.md
- acceptance_scope: OPT-001 Stage 6 TaskQueryProvider port split only
- blocking_items: none
- follow_up_required: yes

### Stage 7

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-25
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage7-provider-listing-envelope-acceptance.md
- acceptance_scope: OPT-001 Stage 7 Provider listing/search typed envelope only
- blocking_items: none
- follow_up_required: yes

### Stage 8

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-25
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage8-provider-port-injection-acceptance.md
- acceptance_scope: OPT-001 Stage 8 Provider port injection narrowing only
- blocking_items: none
- follow_up_required: yes

### Stage 9

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-25
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage9-langgraph-worker-session-split-acceptance.md
- acceptance_scope: OPT-001 Stage 9 LangGraph worker-session port split only
- blocking_items: none
- follow_up_required: yes

### Stage 10

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage10-langgraph-narrow-port-bean-acceptance.md
- acceptance_scope: OPT-001 Stage 10 LangGraph narrow port bean migration only
- blocking_items: none
- follow_up_required: yes

### Stage 11

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage11-gemini-narrow-port-bean-acceptance.md
- acceptance_scope: OPT-001 Stage 11 Gemini narrow port bean migration only
- blocking_items: none
- follow_up_required: yes

### Stage 12

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage12-codex-narrow-port-bean-acceptance.md
- acceptance_scope: OPT-001 Stage 12 Codex / Codex Biz narrow port bean migration only
- blocking_items: none
- follow_up_required: yes

### Stage 13

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage13-claude-narrow-port-bean-acceptance.md
- acceptance_scope: OPT-001 Stage 13 Claude narrow port bean migration only
- blocking_items: none
- follow_up_required: yes

### Stage 14

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage14-task-listing-typed-method-acceptance.md
- acceptance_scope: OPT-001 Stage 14 TaskListingProvider typed method contract only
- blocking_items: none
- follow_up_required: yes

### Stage 15

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage15-worker-session-typed-envelope-acceptance.md
- acceptance_scope: OPT-001 Stage 15 WorkerSession typed DTO / envelope only
- blocking_items: none
- follow_up_required: yes

### Stage 16

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage16-claude-worker-session-bean-acceptance.md
- acceptance_scope: OPT-001 Stage 16 Claude worker-session provider bean split only
- blocking_items: none
- follow_up_required: yes

### Stage 17

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage17-legacy-provider-method-deprecation-acceptance.md
- acceptance_scope: OPT-001 Stage 17 legacy provider method deprecation gate only
- blocking_items: none
- follow_up_required: yes

### Stage 18

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage18-task-command-cancel-direct-method-acceptance.md
- acceptance_scope: OPT-001 Stage 18 TaskCommandProvider cancel direct method only
- blocking_items: none
- follow_up_required: yes

### Stage 19

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage19-migration-support-foundation-acceptance.md
- acceptance_scope: OPT-001 Stage 19 migration support foundation only
- blocking_items: none
- follow_up_required: yes

### Stage 20

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage20-startup-migration-runner-acceptance.md
- acceptance_scope: OPT-001 Stage 20 startup migration runner / manifest only
- blocking_items: none
- follow_up_required: yes

## Execution Check-in

### 2026-06-25 - Stage 1 Route Registry

已完成：

- 新增 `ProviderRouteRegistry`，集中提供 backend 到 providerType 的映射、已知 Provider 集合和 modelConfig/执行 Provider 兼容判断。
- `TaskDispatchFacade` 仍保留原有分发语义，但 modelConfig 推导和 Codex Biz 兼容判断已委托公共注册表。
- `UnifiedAgentResolver` 与四个 worker adapter 删除各自私有 backend switch，避免路由漂移。
- `docs/a2a-agent-architecture.md` 已补充 `ProviderRouteRegistry`、`workerBackend` 与 `codex-biz-worker` direct route 说明。

测试证据：

- `ProviderRouteRegistryTest`：6 tests pass。
- `TaskDispatchFacadeTest` + `UnifiedAgentResolverTest`：60 tests pass。
- `ClaudeWorkerAgentProviderTest`、`CodexWorkerAgentProviderTest`、`GeminiWorkerAgentProviderTest`、`LanggraphWorkerAgentProviderTest`：21 tests pass。
- `mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am`：1475 tests pass。

剩余风险：

- `JpaSessionManager` 仍保留 `KNOWN_PROVIDER_TYPES` 私有集合，当前只包含 Claude、Codex、Codex Biz，缺少 Gemini 与 LangGraph Biz；下一步应改为复用 `ProviderRouteRegistry.isKnownProviderType` 并补充 session create 回归。
- `metadata-config-module`、`business-agent-module`、OpenAPI readiness / diagnostics 里仍存在 backend 支持列表或 provider/backend 反向推导，后续需要按边界继续收口。
- `TaskDispatchFacade` 的职责体积未在本阶段拆分；后续 Stage 2 已拆出 Provider 查找、创建目标推导、统一投影和任务操作路由，剩余状态恢复/schema 边界继续跟进。

质量闸门判断：

- 本阶段为小范围路由映射收口，未改 REST / OpenAPI / SDK payload，未移动 Provider 状态机和持久化 schema；执行轻量 self-check，暂不触发正式 `foggy-implementation-quality-gate`。

### 2026-06-25 - Stage 1 Follow-up Provider / Backend Mapping

已完成：

- `JpaSessionManager` 删除私有 `KNOWN_PROVIDER_TYPES` 判断，改为 `ProviderRouteRegistry.isKnownProviderType`，新增 session create 回归覆盖 Claude、Codex、Codex Biz、Gemini、LangGraph Biz。
- `ProviderRouteRegistry` 扩展 canonical backend normalization、known backend 判断和 route token 到 backend 的反向映射，统一处理 `openai-codex`、`codex-biz-worker`、`gemini` 等输入形态。
- `metadata-config-module` 的订阅型 backend 判断、`business-agent-module` 的 supported backend 常量/规范化、OpenAPI readiness / diagnostics 的 backend 判断均迁移到公共注册表。
- `codex-biz-worker` 到 `OPENAI_CODEX` 的兼容诊断语义保留；未知 provider fallback 仍保持原有 OpenAPI 字段兼容。

测试证据：

- `ProviderRouteRegistryTest` + `JpaSessionManagerTest`：28 tests pass。
- `ProviderRouteRegistryTest`、`LlmModelManagerImplTest`、`ClientAppModelConfigGrantServiceTest`、`BusinessAgentTaskServiceTest`、`OpenApiAgentReadinessServiceTest`、`OpenApiControllerMessageMappingTest`：118 tests pass。

剩余风险：

- 本阶段注册表仍是静态路由语义，尚未声明 Provider 运行期能力；该风险已在 Stage 2.4 通过 `TaskQueryCapability` 与 Provider `getCapabilities()` 完成第一步收敛。
- `navigator-open-sdk` 如需共享 backend/provider 常量，需要另行评估依赖边界，避免 SDK 被平台内部模块反向污染。

### 2026-06-25 - Stage 2.1 TaskQueryProvider Lookup Registry

已完成：

- 新增 `TaskQueryProviderRegistry`，集中封装 provider 列表、按 providerType 查找和按 taskId 查找归属 Provider。
- `TaskDispatchFacade` 改为显式构造函数，保持原构造参数不变；Spring 注入和现有单测手工构造语义不变。
- Facade 内部 Provider 遍历入口统一走 registry，为后续拆 `TaskOperationRouter`、`UnifiedSessionTaskProjectionService` 提供更小的边界；该拆分已在 Stage 2.2~2.5 执行。

测试证据：

- `TaskDispatchFacadeTest`、`JpaSessionManagerTest`、`ProviderRouteRegistryTest`：78 tests pass。
- 本轮受影响 Java reactor：`mvn test -pl navigator-common,metadata-config-module,business-agent-module,session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am`，1522 tests pass。

剩余风险：

- Stage 2.1 仅完成 Provider 查找职责抽离，未改变 REST / OpenAPI / SDK payload，未移动任务状态机。
- `TaskDispatchFacade` 中创建目标推导、统一 session-store 查询/投影和恢复/续接操作已在后续 Stage 2.2~2.5 拆出；反射 DTO 适配和 Provider 状态 schema 仍是后续复杂度来源。

下一步规划已执行，见后续 Stage 2.2~2.5 记录。

### 2026-06-25 - Stage 2.2~2.4 Facade Boundary Split and Provider Capabilities

已完成：

- 新增 `TaskCreateTargetResolver`，集中 create 路径目标推导：显式 Agent、`directory#` 隐式 Agent、session 绑定、显式 providerType 和 modelConfig fallback。
- 新增 `UnifiedSessionTaskProjectionService`，迁出统一 session-store 分页/搜索、provider page/search envelope 读取、compact item 和 `DispatchTaskDTO` 投影逻辑。
- 新增 `TaskQueryCapability` 与 `TaskQueryProvider#getCapabilities()`，保留空集合 legacy fallback 语义，避免未迁移 Provider 直接被排除。
- `TaskQueryProviderRegistry#providersSupporting(...)` 已用于 `listTasksPaged`、`searchSessions`、目录分页和 worker session 查询 fan-out；当有 Provider 声明 capability 时优先只遍历支持者。
- Claude、Codex、Codex Biz、Gemini、LangGraph Provider 已按实际 override 方法声明 capability，避免把分页、搜索或 worker session 请求发给明显不支持的 Provider。

测试证据：

- 定向命令：`mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest,ClaudeTaskServiceTest,CodexTaskServiceTest,CodexBizTaskProviderTest,GeminiTaskServiceAuthResolutionTest,LanggraphTaskServiceTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`，111 tests pass。
- 完整受影响 reactor：`mvn test -pl navigator-common,metadata-config-module,business-agent-module,session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am`，1525 tests pass。

剩余风险：

- `respondToTask`、`reconnectTask`、`resyncTask`、`rewindTask`、`resumeTask` 等任务操作在本阶段尚未拆出；该风险已在 Stage 2.5 通过 `TaskOperationRouter` 收敛。
- `UnifiedSessionTaskProjectionService` 仍需要兼容 Provider 私有 page/search DTO，因此保留反射读取；后续可逐步引入 typed envelope 或统一 response contract。
- `providerStateJson` / `taskStateJson` schema 化尚未启动，恢复、回退、续接字段仍依赖隐式 JSON key。

质量闸门判断：

- 本阶段未改变 REST / OpenAPI / SDK payload，未移动 Provider 状态机和持久化 schema；已执行定向与受影响 reactor 完整回归。正式 `foggy-implementation-quality-gate` 建议放在 Stage 2 操作路由拆分或 Stage 3 状态 schema 变更后执行。

### 2026-06-25 - Stage 2.5 TaskOperationRouter Extraction

已完成：

- 新增 `TaskOperationRouter`，集中 `getTask`、direct create、cancel、respond、reconnect、resync、rewind、resume、delete、scan checkpoints 的 Provider 操作路由。
- 新增 `TaskDispatchRequestParams`，统一 Direct / Resume / A2A metadata 的请求参数转换，并保留 `attachments`、`images`、`context` 等原有透传字段。
- `TaskDispatchFacade` 保持构造签名、公开方法和 REST / OpenAPI / SDK payload 不变；公共方法内部改为委托 Router，调用后仍由 Facade 统一补写 `session_tasks` 的 model/modelConfig/context/diagnostics。
- resume 的 session-bound provider 优先、冲突 providerType 修正、冲突 modelConfig 清空、legacy agent/modelConfig fallback 等规则已迁入 Router。
- cancel 的终态 no-op、provider route 优先、A2A fallback 和 delete 的 provider 已缺失清理统一迁入 Router。

测试证据：

- 定向命令：`mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`，53 tests pass。
- session 全量命令：`mvn test -pl session-module -am`，239 tests pass。

剩余风险：

- `TaskOperationRouter` 仍复用当前 `TaskQueryProvider` 的 default throw 兼容语义，尚未拆成更明确的 task query / lifecycle / recovery / worker-session 端口。
- `UnifiedSessionTaskProjectionService` 仍需要兼容 Provider 私有 page/search DTO，因此保留反射读取；后续可逐步引入 typed envelope 或统一 response contract。
- `providerStateJson` / `taskStateJson` schema 化尚未启动，恢复、回退、续接字段仍依赖隐式 JSON key。

质量闸门判断：

- 本阶段是 Facade 内部职责拆分，未改变外部 payload、Provider 状态机或持久化 schema；已执行定向与 session 全量回归。建议在 Stage 2 收口后执行一次正式 `foggy-implementation-quality-gate`，再进入 Stage 3 状态 schema。

### 2026-06-25 - Stage 2 Implementation Quality Gate

已完成：

- 执行正式 `foggy-implementation-quality-gate`，质量门文档见 `quality/OPT-001-implementation-quality.md`。
- Review `TaskOperationRouter`、`TaskDispatchRequestParams`、`TaskDispatchFacade`、`TaskQueryProviderRegistry`、`UnifiedSessionTaskProjectionService` 等 Stage 2 核心实现。
- 删除 `TaskOperationRouter.cancelTask` 中已不可达的 provider fallback 分支，避免后续维护误判取消链路。
- 确认 `TaskDispatchRequestParams` 保留 `attachments`、`images`、`context` 等请求透传字段，未改变外部 payload。

测试证据：

- `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：53 tests pass。
- `mvn test -pl session-module -am`：239 tests pass。

质量门结论：

- decision=`ready-with-risks`。
- Stage 2 可进入 `foggy-test-coverage-audit`。
- 不阻断但必须跟进的风险：`TaskQueryProvider` 端口偏宽且 `cancelTask` 仍有 deprecated 警告、统一查询 envelope 仍为反射兼容、`providerStateJson` / `taskStateJson` 尚未 schema 化、`reconnect/resync/scan checkpoints` 覆盖需要审计确认。

### 2026-06-25 - Stage 2 Test Coverage Audit

已完成：

- 执行 `foggy-test-coverage-audit`，覆盖审计文档见 `coverage/OPT-001-stage1-stage2-coverage-audit.md`。
- 针对质量门指出的 pass-through 覆盖弱点，新增 `reconnectTask`、`resyncTask`、`scanCheckpoints` 的归属 Provider 路由断言。
- 强化 LangGraph Biz direct route create 测试，新增 `attachments` 透传断言，与 `images`、`context`、modelConfig 兼容验证对齐。
- 将 Stage 1/2 requirement、acceptance item 与测试证据映射到 coverage matrix。

测试证据：

- `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：56 tests pass。
- `mvn test -pl session-module -am`：242 tests pass。

覆盖审计结论：

- conclusion=`ready-for-acceptance`，适用于 `OPT-001` Stage 1/2 Java dispatch governance 切片。
- Stage 1/2 范围内未发现阻断覆盖缺口。
- Stage 3 Provider 状态 schema、`TaskQueryProvider` 窄端口拆分和 typed provider envelope 仍是后续规划项，不纳入本次 Stage 1/2 验收范围。

### 2026-06-25 - Stage 1/2 Feature Acceptance Signoff

已完成：

- 执行 `foggy-acceptance-signoff`，验收记录见 `acceptance/OPT-001-stage1-stage2-acceptance.md`。
- 验收范围限定为 Stage 1 Provider 路由治理与 Stage 2 `TaskDispatchFacade` 职责边界拆分。
- 确认 Stage 3 Provider 状态 schema、Stage 4 SSE 部署边界和 Stage 5 运行配置硬化不纳入本次签收范围。

签收结论：

- acceptance_status=`signed-off`
- acceptance_decision=`accepted`
- blocking_items=`none`
- follow_up_required=`no` for Stage 1/2 acceptance

### 2026-06-25 - Stage 3.1 Shared Provider State Codec

已完成：

- 新增 `ProviderStateCodec`，集中定义 Provider 状态 schema version、providerType 和 `claudeSessionId`、`codexThreadId`、`geminiSessionId`、`contextId`、`agentTeamsConfigId`、`checkpoints` 等核心字段常量。
- codec 支持 legacy JSON、坏 JSON 降级、未知字段保留、空值移除和嵌套 checkpoint payload 保留。
- `TaskDispatchFacade` 的 context/diagnostic metadata 写入路径已通过共享 codec 合并 `taskStateJson`，新写入状态带 `schemaVersion=1` 与 `providerType`。
- Stage 3 子计划与测试证据已回写到 `workitems/OPT-001-stage3-provider-state-schema.md`。

测试证据：

- `mvn test -pl navigator-common,session-module -am "-Dtest=ProviderStateCodecTest,ProviderRouteRegistryTest,TaskDispatchFacadeTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：`navigator-common` 14 tests pass，`session-module` 53 tests pass。
- `mvn test -pl session-module -am`：242 tests pass。

剩余风险：

- Claude/Codex/Gemini/LangGraph Provider 内部 `providerStateJson` / `taskStateJson` 私有 helper 尚未迁移，恢复、回退、续接字段仍需 Stage 3.2/3.3 继续治理。
- 本阶段新增 `navigator-common` 对 Jackson databind 的直接依赖；后续如收紧 common 依赖边界，需要同步复核。
- `TaskQueryProvider` 窄端口拆分与 typed provider envelope 不纳入 Stage 3.1，继续作为后续架构项。

下一步：

- Stage 3.2 优先迁移 Codex/Gemini 的 `providerStateJson` 读写，再迁移 Claude 的 `agentTeamsConfigId`、checkpoint、rewind 相关状态。

### 2026-06-25 - Stage 3.2 Codex/Gemini Provider Session State Migration

已完成：

- Codex `resumeTask`、`syncSessionProjection`、rewind 清空 `codexThreadId` 的 provider session state 读写切到 `ProviderStateCodec`。
- Gemini `resumeTask` 与 session entity projection 的 `geminiSessionId` provider session state 读写切到 `ProviderStateCodec`。
- Codex/Gemini 写入路径会保留未知字段，并在有 payload 时补 `schemaVersion=1` 与 `providerType`。
- 测试覆盖 legacy JSON 读取、schema v1 JSON 读取、未知字段保留和清空 session id。

测试证据：

- `mvn test -pl navigator-common,addons/codex-worker-agent,addons/gemini-worker-agent -am "-Dtest=ProviderStateCodecTest,CodexTaskServiceTest,GeminiTaskServiceAuthResolutionTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：ProviderStateCodec 5 tests、CodexTaskService 20 tests、GeminiTaskServiceAuthResolution 8 tests pass。
- `mvn test -pl addons/codex-worker-agent,addons/gemini-worker-agent -am`：`navigator-common` 15 tests、`session-module` 242 tests、Codex 57 tests、Gemini 14 tests pass。

剩余风险：

- Claude provider session state 已在后续切片迁移完成；见下一节。
- Codex/Gemini/Claude/LangGraph 的 `taskStateJson` 迁移仍待 Stage 3.3。
- 本阶段未触碰对外 REST / OpenAPI / SDK payload，也未拆分 `TaskQueryProvider` 端口。

下一步：

- 执行 Claude provider session state 迁移并优先补充 legacy/schema/unknown/clear 状态单测；完成情况见下一节。

### 2026-06-25 - Stage 3.2 Claude Provider Session State Migration

已完成：

- Claude `syncLocalSessions` 删除会话过滤、`resumeTask(Map)` 恢复、`syncSessionProjection`、Agent Teams 锁定和 rewind 首轮清理 `claudeSessionId` 的 provider session state 读写切到 `ProviderStateCodec`。
- `ConversationConfigService` 读取/写入 `agentTeamsConfigId` 改为复用共享 codec，写入时保留未知字段并补 `schemaVersion=1` 与 `providerType`。
- 测试覆盖 legacy JSON、schema v1 JSON、坏 JSON 降级、未知字段保留、Agent Teams 配置读取/写入和清空 `claudeSessionId`。

代码触点：

- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ConversationConfigService.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/ClaudeTaskServiceAuthTest.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/ClaudeTaskServiceRewindTest.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/ClaudeTaskServiceSyncTest.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/ConversationConfigServiceTest.java`

测试证据：

- `mvn test -pl navigator-common,addons/claude-worker-agent -am "-Dtest=ProviderStateCodecTest,ClaudeTaskServiceAuthTest,ClaudeTaskServiceRewindTest,ClaudeTaskServiceSyncTest,ConversationConfigServiceTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：ProviderStateCodec 5、ClaudeTaskServiceAuth 20、ClaudeTaskServiceRewind 5、ClaudeTaskServiceSync 7、ConversationConfigService 13 tests pass。
- `mvn test -pl addons/claude-worker-agent -am`：312 tests pass。

剩余风险：

- Claude/Codex/Gemini/LangGraph 的 `taskStateJson` 迁移仍待 Stage 3.3；Claude checkpoint/task projection 状态仍在私有 builder 中。
- LangGraph worker session/context 状态兼容覆盖仍待后续切片。
- 本阶段未触碰对外 REST / OpenAPI / SDK payload，也未拆分 `TaskQueryProvider` 端口。

下一步：

- 进入 Stage 3.3：优先迁移 Provider 内部 `taskStateJson` 生成/读取，并补充 `DispatchTaskDTO` 投影对 legacy/schema v1 的兼容回归。

### 2026-06-25 - Stage 3.3 Provider Task State Migration

Review 发现：

- Claude/Gemini `taskStateJson` 写入每次重建 JSON，缺少 schema/provider 标记，并可能覆盖调度层追加的诊断字段。
- Codex/LangGraph task state helper 与共享 codec 的坏 JSON 降级、空字段删除和 schema 标记规则不一致。
- `UnifiedSessionTaskProjectionService` 当前按普通 Map 读取业务 key；schema v1 保持同名业务 key，兼容性可接受，但 typed constants / typed envelope 仍是后续质量项。

已完成：

- Claude/Codex/Gemini/LangGraph 的 `taskStateJson` 写入统一迁移到 `ProviderStateCodec`。
- Codex `resolveTaskContextId` 改为通过 codec 读取 `contextId`。
- LangGraph `structuredOutput` 继续保持字符串形式写入，避免改变前端/API 读取语义。
- 各 Provider 写入时保留既有 `originalTaskId`、恢复字段、诊断字段和其他未知字段，并补 `schemaVersion=1` 与 `providerType`。

测试证据：

- `mvn test -pl addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am "-Dtest=ClaudeTaskServiceAuthTest,CodexTaskServiceTest,GeminiTaskServiceAuthResolutionTest,LanggraphTaskServiceTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：73 tests pass。
- `mvn test -pl addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am`：Surefire XML 合计 1503 tests，0 failures，0 errors，0 skipped。

剩余风险：

- `UnifiedSessionTaskProjectionService` 仍保留普通 Map/string key 投影读取，需补 schema v1 直接投影回归后再考虑 typed constants / envelope。
- LangGraph worker session endpoint 仍位于 `LanggraphTaskService`，本阶段只完成统一 task state 的 context/state schema 化。
- `TaskQueryProvider` 窄端口拆分、Provider typed envelope 和 Claude 大 service 拆分仍是后续架构项。

下一步：

- 补 schema v1 task state 的 `DispatchTaskDTO` 直接投影回归，并执行 Stage 3 `foggy-implementation-quality-gate` 与 `foggy-test-coverage-audit`。

### 2026-06-25 - Stage 3.4 Projection Regression / Quality / Coverage Closure

已完成：

- 补齐 `DispatchTaskDTO` 对 schema v1 `taskStateJson` 的直接投影回归，覆盖 `codexThreadId`、`contextId`、`checkpoints`、`fileCheckpointingEnabled` 和目录名称投影。
- 执行 Stage 3 实现质量门，质量门文档见 `quality/OPT-001-stage3-implementation-quality.md`。
- 执行 Stage 3 测试覆盖审计，覆盖审计文档见 `coverage/OPT-001-stage3-coverage-audit.md`。
- 同步回写 Stage 3 子计划和本文档的 progress / checklist / execution-checkin。

测试证据：

- `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：54 tests pass。
- `mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am`：Surefire XML 合计 219 suites / 1504 tests，0 failures，0 errors，0 skipped。

质量门结论：

- decision=`ready-with-risks`。
- 未发现阻断覆盖审计的实现缺陷。
- 非阻断风险：typed envelope / typed constants、LangGraph worker session endpoint 拆分、Gemini JSON helper cleanup、`TaskQueryProvider` 窄端口拆分。

覆盖审计结论：

- conclusion=`ready-with-gaps`。
- can_enter_acceptance=`yes`。
- Stage 3 Provider 状态 schema 化切片内未发现阻断验收的测试覆盖缺口。

下一步：

- 建议先对 Stage 3 执行功能级验收签收；验收后再选择下一架构切片推进 Stage 4 SSE 部署边界、LangGraph endpoint 拆分或 `TaskQueryProvider` 窄端口收敛。

### 2026-06-25 - Stage 3 Acceptance Signoff

已完成：

- 执行 Stage 3 Provider 状态 schema 化功能级验收。
- 验收记录见 `acceptance/OPT-001-stage3-provider-state-schema-acceptance.md`。
- 签收结论为 `accepted-with-risks`，无阻断项。

签收结论：

- Stage 3 已满足当前验收标准：Provider 状态 schema/codec 已落地，legacy/bad JSON/unknown field/null removal 策略已覆盖，`DispatchTaskDTO` schema v1 直接投影回归已补齐，质量门和覆盖审计均完成。
- 风险项限定为后续架构收敛：typed provider envelope、LangGraph worker session endpoint 拆分、Gemini JSON helper cleanup、`TaskQueryProvider` 窄端口拆分。

下一步：

- 建议规划并推进 Stage 4 SSE 部署边界治理；若要先降低 Provider service 复杂度，可转为 LangGraph endpoint 拆分或 `TaskQueryProvider` 窄端口切片。

### 2026-06-25 - Stage 4 SSE Deployment Boundary

Review 发现：

- `UnifiedSseEmitter` 的 `userEmitters`、`userSubscriptions`、`sessionToUsers` 都是 JVM 内存态，当前不能承诺非粘性多实例下的实时投递一致性。
- completion、timeout、error 和 heartbeat 失败路径已有订阅清理，但普通业务事件发送失败后未统一清理空连接用户的订阅索引。
- `createEmitter` 的 `computeIfAbsent(...).add(...)` 连接加入路径存在重连/清理竞争窗口。
- `TaskUpdateNotifier` 缺少任务状态变更和任务完成补偿推送的单测。

已完成：

- 新增 Stage 4 子计划：`workitems/OPT-001-stage4-sse-deployment-boundary.md`。
- `docs/a2a-agent-architecture.md` 已明确 `UnifiedSseEmitter` 为单 JVM 内存态；当前版本支持单实例或具备同实例亲和的粘性会话部署。
- 非粘性多实例标记为后续演进项，需要外部事件总线或集中通知服务，不在本阶段实现。
- `UnifiedSseEmitter` 普通发送失败、heartbeat 失败和 callback 断连统一复用空连接用户清理逻辑。
- `createEmitter` 改为在 `userEmitters.compute(...)` 内完成列表创建和 emitter 添加，降低重连/清理并发竞争。
- 新增/强化 `UnifiedSseEmitterTest`，覆盖发送失败清理、heartbeat 清理、断连后重连并重新订阅。
- 新增 `TaskUpdateNotifierTest`，覆盖 `task_status_change`、`task_completion` 和缺失 userId/session 跳过行为。

测试证据：

- `mvn test -pl session-module -am '-Dtest=UnifiedSseEmitterTest,UnifiedSseControllerTest,SessionEventListenerTest,TaskUpdateNotifierTest' '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false'`：26 tests pass。
- `mvn test -pl session-module -am`：250 tests pass。

自检结论：

- self-check-only；本切片没有改变 SSE API path、事件名、payload 语义或前端交互形态。
- 不需要升级正式 `foggy-implementation-quality-gate`；剩余风险已经限定为非粘性多实例实时投递，需要后续事件总线或集中通知服务切片处理。

下一步：

- 对 Stage 4 执行功能级验收签收；之后建议进入 Stage 5 运行配置硬化，或继续收敛 `TaskQueryProvider` 窄端口 / LangGraph worker session endpoint。

### 2026-06-25 - Stage 5 Runtime Config Hardening

Review 发现：

- `launcher` base 配置以开发便利为默认：`allow-bean-definition-overriding=true`、JPA `ddl-auto=update`、JWT/ROOT/credential 存在开发默认值。
- actuator 默认暴露 `health,beans,metrics` 且 `health.show-details=always`，生产环境容易暴露过多运行细节。
- 原有 `ExternalUrlStartupValidator` 仅对 localhost external URL 输出 warning，不能阻止生产 profile 带危险配置启动。

已完成：

- `launcher/src/main/resources/application.yml` 的关键开发默认值改为可通过环境变量覆盖，保持 dev/test 兼容。
- 新增 `launcher/src/main/resources/application-prod.yml`，生产 profile 默认 `ddl-auto=validate`、禁止 bean overriding、收敛 actuator 暴露，并将 datasource、JWT、ROOT password、credential key/salt、external URL 作为显式生产配置项。
- 新增 `ProductionConfigurationGuard`，在 `prod` / `production` profile 下启动前统一校验危险配置并 fail-fast。
- `FogyNavigatorApplication` 已注册 production guard，确保 launcher 启动入口执行前置检查。
- `launcher` 补充 `spring-boot-starter-test` 测试依赖和 `ProductionConfigurationGuardTest`。
- Stage 5 子计划、部署检查表、测试证据和 ready-for-acceptance 状态已回写。

测试证据：

- `mvn test -pl launcher -am '-Dtest=ProductionConfigurationGuardTest' '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false'`：4 tests pass。
- `mvn test -pl launcher -am`：Surefire XML 合计 250 reports / 1756 tests，0 failures，0 errors，0 skipped。

自检结论：

- self-check-only；本切片没有改变 REST / OpenAPI / SDK payload、数据库表结构、业务认证流程或前端交互形态。
- Stage 5 可进入功能级验收签收。

剩余风险：

- 未引入 Flyway/Liquibase；生产 schema 仍需通过 migration 或人工流程预先准备，`prod` profile 只负责禁止隐式 `ddl-auto=update`。
- `user-auth-module` 等非 launcher 独立启动配置未纳入本切片；如存在独立生产启动方式，应补充对应 prod profile 或统一入口约束。

下一步：

- 对 Stage 5 执行功能级验收签收；随后建议进入 `TaskQueryProvider` 窄端口治理，或优先拆分 LangGraph worker session endpoint。

### 2026-06-25 - Stage 5 Acceptance Signoff

已完成：

- 执行 Stage 5 运行配置硬化功能级验收。
- 验收记录见 `acceptance/OPT-001-stage5-runtime-config-hardening-acceptance.md`。
- 签收结论为 `accepted-with-risks`，无阻断项。

签收结论：

- Stage 5 已满足当前验收标准：launcher dev/prod profile 已拆分，生产 profile 禁止隐式 schema update 和 bean override，生产启动 guard 已覆盖危险默认值 fail-fast。
- 风险项限定为部署侧 follow-up：未引入 Flyway/Liquibase，生产 schema 仍需预先准备；非 launcher 独立启动配置未纳入本阶段。

下一步：

- 进入 Stage 6 `TaskQueryProvider` 窄端口治理。

### 2026-06-25 - Stage 6 TaskQueryProvider Port Split

Review 发现：

- `TaskQueryProvider` 名义上是查询 SPI，但实际同时承载 task lookup、direct create、respond/reconnect/resync/rewind/resume/delete/scan、paged list/search 和 worker session 操作。
- Stage 2.4 的 `TaskQueryCapability` 已能缩小 fan-out 候选，但 registry 返回类型仍是 `TaskQueryProvider`，无法在编译期表达调用面。
- 直接删除宽接口方法风险较高，会影响 Claude/Codex/Gemini/LangGraph provider bean 注入和现有实现。

已完成：

- 新增 `TaskProviderPort`，集中 `getProviderType()`、`getCapabilities()` 和 `supports(...)` 默认语义。
- 新增 `TaskLookupProvider`、`TaskCommandProvider`、`TaskListingProvider`、`WorkerSessionQueryProvider` 四类窄端口。
- `TaskQueryProvider` 改为兼容聚合接口，继承上述窄端口，保持现有 Provider 实现无行为变化。
- `TaskQueryProviderRegistry` 新增 lookup / command / listing / worker-session typed views，并保留原有 `providersSupporting(...)` 兼容入口。
- `TaskOperationRouter` 的 direct create、resume、respond、reconnect、resync、rewind、delete、scan、provider cancel 路径改为依赖 `TaskCommandProvider`，lookup 路径依赖 `TaskLookupProvider`。
- `TaskDispatchFacade` 的 list/search/directory list 使用 `TaskListingProvider`，worker session fan-out 使用 `WorkerSessionQueryProvider`。
- `TaskCreateTargetResolver` 的 direct provider availability 判断改为检查 command port。

测试证据：

- `mvn test -pl session-module -am "-Dtest=TaskQueryProviderRegistryTest,TaskDispatchFacadeTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：62 tests pass。
- `mvn test -pl navigator-spi,session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am`：affected reactor 通过，Surefire XML 合计 220 reports / 1520 tests，0 failures，0 errors，0 skipped。

质量与覆盖：

- 实现质量门见 `quality/OPT-001-stage6-implementation-quality.md`，decision=`ready-for-coverage-audit`。
- 测试覆盖审计见 `coverage/OPT-001-stage6-coverage-audit.md`，conclusion=`ready-with-gaps`，can_enter_acceptance=`yes`。

签收结论：

- 验收记录见 `acceptance/OPT-001-stage6-task-query-provider-port-split-acceptance.md`。
- acceptance_decision=`accepted-with-risks`，无阻断项。

剩余风险：

- `TaskQueryProvider` 仍保留为兼容聚合接口；Provider bean 注入尚未迁移到独立窄端口集合。
- `TaskCommandProvider#cancelTask` 仍保留 deprecated 兼容方法；彻底移除需等统一 A2A abort 链路完全覆盖后另起迁移项。
- Provider page/search typed envelope 仍未治理，继续作为后续架构项。

下一步：

- 建议优先推进 Provider typed envelope / `UnifiedSessionTaskProjectionService` 反射读取治理，或拆分 LangGraph worker session endpoint。

### 2026-06-25 - Stage 7 Provider Listing/Search Typed Envelope

Review 发现：

- `UnifiedSessionTaskProjectionService` listing/search envelope 原先通过 Map key 或 JavaBean getter 反射读取 Provider 返回，字段漂移时缺少编译期保护。
- Claude SPI 路径返回模块内 DTO，Codex / Codex Biz 返回 `Map.of(...)`，统一 SPI contract 与模块 DTO/Map 结构耦合。
- 旧 Provider 仍可能依赖 legacy 返回结构，直接删除 fallback 会扩大兼容风险。

已完成：

- 新增 `TaskPageResult` 与 `TaskSearchResult` typed envelope。
- `TaskListingProvider` Javadocs 明确新实现应返回 typed envelope。
- `UnifiedSessionTaskProjectionService` 改为 typed-first 解析 listing/search envelope，并保留 legacy Map / JavaBean getter fallback。
- Claude SPI `listTasksPaged`、`listTasksByDirectoryPaged`、`searchSessions` 返回 typed envelope；历史 controller DTO 返回保持不变。
- Codex / Codex Biz listing/search SPI 返回迁移为 typed envelope。
- 补充 `UnifiedSessionTaskProjectionServiceTest`，覆盖 typed page/search 与 legacy fallback。

测试证据：

- `mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent -am "-Dtest=UnifiedSessionTaskProjectionServiceTest,TaskDispatchFacadeTest,ClaudeTaskServiceTest,CodexTaskServiceTest,CodexBizTaskProviderTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：session-module 58 tests pass；codex-worker-agent 28 tests pass。
- `mvn test -pl navigator-spi,session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am`：affected reactor 通过，Surefire XML 合计 221 reports / 1524 tests，0 failures，0 errors，0 skipped。
- `git diff --check`：无 whitespace error，仅 CRLF normalization warnings。

质量与覆盖：

- 实现质量门见 `quality/OPT-001-stage7-implementation-quality.md`，decision=`ready-with-risks`。
- 测试覆盖审计见 `coverage/OPT-001-stage7-coverage-audit.md`，conclusion=`ready-with-gaps`，can_enter_acceptance=`yes`。

签收结论：

- 验收记录见 `acceptance/OPT-001-stage7-provider-listing-envelope-acceptance.md`。
- acceptance_decision=`accepted-with-risks`，无阻断项。

剩余风险：

- `TaskListingProvider` 仍保留 `Object` 返回类型；彻底收紧签名需要后续兼容迁移。
- legacy Map / JavaBean getter fallback 仍保留；需等外部 Provider 迁移完成后再规划删除或 deprecated。
- Claude / Codex 大型 TaskService 拆分仍未处理。

下一步：

- 建议进入 Stage 8：优先收敛 Provider bean 注入到独立窄端口集合，或拆分 LangGraph worker session endpoint；若目标是继续压低 listing/search contract 风险，可先规划 `TaskListingProvider` strictly typed method 迁移。

### 2026-06-25 - Stage 8 Provider Port Injection Narrowing

Review 发现：

- `TaskDispatchFacade` 构造期仍以 `List<TaskQueryProvider>` 接收所有 Provider，Stage 6 的窄端口治理尚未落到生产注入边界。
- `TaskQueryProviderRegistry` 内部仍维护宽聚合 Provider 集合，再按调用场景转换为窄端口，无法自然支持未来只实现 lookup 或 command 的独立 bean。
- `findCommandProviderForTask` 原先要求同一个宽 Provider 同时完成 lookup 与 command，lookup/command 分离形态缺少回归保护。

已完成：

- `TaskDispatchFacade` 构造函数改为接收 `TaskLookupProvider`、`TaskCommandProvider`、`TaskListingProvider`、`WorkerSessionQueryProvider` 四类列表。
- `TaskQueryProviderRegistry` 内部按四类窄端口分别维护集合，capability filtering 在具体端口集合内执行。
- `findCommandProviderForTask` 改为 lookup 端口识别任务归属，再按 providerType 查找 command 端口。
- `TaskQueryProviderRegistryTest` 新增 lookup provider 与 command provider 分离但 providerType 相同的回归。
- `AbortCoordinatingA2aAgent` 主构造改为依赖 `TaskLookupProvider`，并保留 deprecated `TaskQueryProvider` 兼容构造器。
- Claude/Codex/Gemini worker adapter 创建 abort wrapper 时改用 lookup-port 构造。

测试证据：

- `mvn test -pl session-module -am "-Dtest=TaskQueryProviderRegistryTest,TaskDispatchFacadeTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：63 tests pass。
- 初次 affected reactor 暴露 Claude adapter 测试仍链接旧构造签名；已通过兼容构造器和 adapter 迁移修复。
- `mvn test -pl addons/claude-worker-agent -am "-Dtest=ClaudeWorkerAgentProviderTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：10 tests pass。
- `mvn test -pl navigator-spi,session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am`：affected reactor 通过，Surefire XML 合计 221 reports / 1525 tests，0 failures，0 errors，0 skipped。
- `git diff --check`：无 whitespace error，仅 CRLF normalization warnings。

质量与覆盖：

- 实现质量门见 `quality/OPT-001-stage8-implementation-quality.md`，decision=`ready-with-risks`。
- 测试覆盖审计见 `coverage/OPT-001-stage8-coverage-audit.md`，conclusion=`ready-with-gaps`，can_enter_acceptance=`yes`。

签收结论：

- 验收记录见 `acceptance/OPT-001-stage8-provider-port-injection-acceptance.md`。
- acceptance_decision=`accepted-with-risks`，无阻断项。

剩余风险：

- `TaskQueryProvider` 聚合接口仍保留，现有 Provider 仍一次性实现四类端口；独立 bean 拆分留给后续阶段。
- 当前缺少专门的 Spring ApplicationContext 启动测试验证四类泛型列表注入；受影响 reactor 已覆盖编译和模块回归。
- `TaskListingProvider` strictly typed method 迁移仍未处理。

下一步：

- 建议进入 Stage 9：优先拆分 LangGraph worker session endpoint，或推进 Provider 独立窄端口 bean 迁移；若继续压低 listing/search contract 风险，可规划 `TaskListingProvider` strictly typed method 兼容迁移。

### 2026-06-25 - Stage 9 LangGraph Worker Session Port Split

Review 发现：

- `LanggraphTaskService` 同时承载 task lifecycle 与 worker-session 查询端点，服务职责继续膨胀。
- worker-session capability 仍由 task service 声明，Stage 8 的 worker-session 窄端口没有真实独立 Provider 样例。
- worker-session ownership 校验和 Map projection helper 私有在 task service 中，测试也嵌在 `LanggraphTaskServiceTest.WorkerSessions`。

已完成：

- 新增 `LanggraphWorkerSessionQueryService implements WorkerSessionQueryProvider`，注入 `LanggraphWorkerService`、`SessionTaskRepository`、`SessionMessageRepository`。
- `LanggraphWorkerSessionQueryService` 声明 worker-session list/count/messages/sync 四类 capability。
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

- 建议进入 Stage 10：优先推进 Provider 独立窄端口 bean 迁移；备选是 `TaskListingProvider` strictly typed method 兼容迁移，或 worker-session typed DTO / envelope。

### 2026-06-26 - Stage 10 LangGraph Narrow Port Bean Migration

Review 发现：

- Stage 9 后 `LanggraphWorkerSessionQueryService` 已独立承接 worker-session 端口，但 `LanggraphTaskService` 仍实现聚合 `TaskQueryProvider`。
- 聚合接口会让 LangGraph task service 在 Spring 注入边界上继续被视为 listing / worker-session 候选，削弱 Stage 8 的窄端口集合治理收益。
- 直接迁移所有 Provider 风险较高，LangGraph 是当前已具备独立 worker-session bean 的最小可验证切片。

已完成：

- `LanggraphTaskService` 从 `TaskQueryProvider` 迁移为 `TaskLookupProvider, TaskCommandProvider`。
- `LanggraphTaskService` 保留 providerType、capability、lookup、create、cancel、delete 语义，不再作为 listing / worker-session / aggregate provider 暴露。
- `LanggraphWorkerSessionQueryService` 保持独立 worker-session provider 角色，继续承接 list/count/messages/sync。
- `LanggraphTaskServiceTest#exposes_only_supported_task_provider_ports` 覆盖类型边界，防止 task service 回退实现聚合接口或 listing/worker-session 端口。
- Stage 10 子计划、README 索引、质量门、覆盖审计和验收签收记录已回写。

测试证据：

- `mvn test -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphTaskServiceTest,LanggraphWorkerSessionQueryServiceTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：27 tests pass。
- `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：64 tests pass。
- `mvn test -pl session-module,addons/langgraph-biz-worker -am`：affected reactor 通过，Surefire XML 合计 162 reports / 1149 tests，0 failures，0 errors，0 skipped。

质量与覆盖：

- 实现质量门见 `quality/OPT-001-stage10-implementation-quality.md`，decision=`ready-with-risks`。
- 测试覆盖审计见 `coverage/OPT-001-stage10-coverage-audit.md`，conclusion=`ready-with-gaps`，can_enter_acceptance=`yes`。

签收结论：

- 验收记录见 `acceptance/OPT-001-stage10-langgraph-narrow-port-bean-acceptance.md`。
- acceptance_decision=`accepted-with-risks`，无阻断项。

剩余风险：

- 当前仅 LangGraph task lifecycle service 退出聚合 `TaskQueryProvider`；Claude/Codex/Gemini 仍主要通过聚合接口兼容四类端口。
- `TaskCommandProvider#cancelTask` 仍保留 deprecated fallback；彻底移除需等 A2A abort 链路和 Provider command 迁移全部收口。
- `TaskListingProvider` 仍使用 `Object` 返回类型；strictly typed method 迁移仍需后续阶段处理。

下一步：

- 建议进入 Stage 11：优先迁移 Gemini 或 Codex task service 退出聚合 `TaskQueryProvider`，沿用 LangGraph 的类型边界测试模式。
- 备选路线是先推进 `TaskListingProvider` strictly typed method 兼容迁移，继续压低 listing/search contract 风险。

### 2026-06-26 - Stage 11 Gemini Narrow Port Bean Migration

Review 发现：

- Stage 10 已验证 LangGraph task lifecycle service 可退出聚合 `TaskQueryProvider`，但 Gemini 仍直接实现聚合接口。
- Gemini task service 实际只需要 lookup / command 能力，继续实现聚合接口会让它被 Spring 收集到 listing / worker-session 候选列表。
- Gemini 能力面小于 Codex/Claude，适合作为第二个 Provider narrow-port bean 迁移切片。

已完成：

- `GeminiTaskService` 从 `TaskQueryProvider` 迁移为 `TaskLookupProvider, TaskCommandProvider`。
- `GeminiTaskService` 保留 providerType、capability、lookup、createTaskDirect、resumeTask、cancelTask、deleteTask 语义，不再作为 listing / worker-session / aggregate provider 暴露。
- `GeminiTaskServiceAuthResolutionTest#exposesOnlySupportedTaskProviderPorts` 覆盖类型边界，防止 task service 回退实现聚合接口或 listing/worker-session 端口。
- Stage 11 子计划、README 索引、质量门、覆盖审计和验收签收记录已回写。

测试证据：

- `mvn test -pl addons/gemini-worker-agent -am "-Dtest=GeminiTaskServiceAuthResolutionTest,GeminiStreamRelayTest,GeminiWorkerAgentProviderTest,GeminiWorkerClientTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：15 tests pass。
- `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：64 tests pass。
- `mvn test -pl session-module,addons/gemini-worker-agent -am`：affected reactor 通过，Surefire XML 合计 578 tests，0 failures，0 errors，0 skipped。
- `git diff --check`：无 whitespace error，仅 CRLF normalization warnings。

质量与覆盖：

- 实现质量门见 `quality/OPT-001-stage11-implementation-quality.md`，decision=`ready-with-risks`。
- 测试覆盖审计见 `coverage/OPT-001-stage11-coverage-audit.md`，conclusion=`ready-with-gaps`，can_enter_acceptance=`yes`。

签收结论：

- 验收记录见 `acceptance/OPT-001-stage11-gemini-narrow-port-bean-acceptance.md`。
- acceptance_decision=`accepted-with-risks`，无阻断项。

剩余风险：

- Claude/Codex/Codex Biz 仍直接实现 `TaskQueryProvider` 聚合接口。
- `TaskCommandProvider#cancelTask` 仍保留 deprecated fallback；彻底移除需等 A2A abort 链路和 Provider command 迁移全部收口。
- `TaskListingProvider` 仍使用 `Object` 返回类型；strictly typed method 迁移仍需后续阶段处理。
- worker-session payload 仍是 Map，typed DTO / envelope 不在本阶段范围。

下一步：

- 建议进入 Stage 12：优先迁移 Codex / Codex Biz 退出聚合 `TaskQueryProvider`。该阶段比 Gemini 更复杂，因为 Codex 同时承载 listing/search 能力，需先明确是否拆物理 adapter，还是先改为精确实现 lookup / command / listing 三类窄端口。
- 备选路线是先推进 `TaskListingProvider` strictly typed method 兼容迁移，为 Codex listing/search 迁移降低契约风险。

### 2026-06-26 - Stage 12 Codex / Codex Biz Narrow Port Bean Migration

Review 发现：

- Stage 11 后 Gemini 已退出聚合 `TaskQueryProvider`，但 Codex 与 Codex Biz 仍直接实现聚合接口。
- Codex / Codex Biz 实际需要 lookup / command / listing 三类能力，不需要 worker-session 查询端口。
- 继续实现聚合接口会让两个 Codex provider 被 Spring 收集到 worker-session 候选集合，削弱 Stage 8 的窄端口集合治理收益。

已完成：

- `CodexTaskService` 从 `TaskQueryProvider` 迁移为 `TaskLookupProvider, TaskCommandProvider, TaskListingProvider`。
- `CodexBizTaskProvider` 从 `TaskQueryProvider` 迁移为 `TaskLookupProvider, TaskCommandProvider, TaskListingProvider`。
- 保留 providerType、capability、lookup、create/resume/cancel/delete/resync/rewind、listing/search typed envelope 和 session projection 语义。
- `CodexTaskServiceTest#exposesOnlySupportedTaskProviderPorts` 覆盖 Codex task service 类型边界。
- `CodexBizTaskProviderTest#exposesOnlySupportedTaskProviderPorts` 覆盖 Codex Biz provider 类型边界。
- `rg "implements TaskQueryProvider"` 显示剩余生产实现仅为 Claude，session 测试 stub 保留兼容回归。
- Stage 12 子计划、README 索引、质量门、覆盖审计和验收签收记录已回写。

测试证据：

- `mvn test -pl addons/codex-worker-agent -am "-Dtest=CodexTaskServiceTest,CodexBizTaskProviderTest,CodexStreamRelayTest,CodexWorkerAgentProviderTest,CodexWorkerA2aAgentTest,CodexWorkerFacadeImplTest,CodexWorkerClientTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：59 tests pass。
- `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：64 tests pass。
- `mvn test -pl session-module,addons/codex-worker-agent -am`：affected reactor 通过，Surefire XML 合计 622 tests，0 failures，0 errors，0 skipped。
- `git diff --check`：无 whitespace error，仅 CRLF normalization warnings。

质量与覆盖：

- 实现质量门见 `quality/OPT-001-stage12-implementation-quality.md`，decision=`ready-with-risks`。
- 测试覆盖审计见 `coverage/OPT-001-stage12-coverage-audit.md`，conclusion=`ready-with-gaps`，can_enter_acceptance=`yes`。

签收结论：

- 验收记录见 `acceptance/OPT-001-stage12-codex-narrow-port-bean-acceptance.md`。
- acceptance_decision=`accepted-with-risks`，无阻断项。

剩余风险：

- Claude 仍直接实现 `TaskQueryProvider` 聚合接口。
- `TaskCommandProvider#cancelTask` 仍保留 deprecated fallback；彻底移除需等 A2A abort 链路和 Provider command 迁移全部收口。
- `TaskListingProvider` 仍使用 `Object` 返回类型；strictly typed method 迁移仍需后续阶段处理。
- worker-session payload 仍是 Map，typed DTO / envelope 不在本阶段范围。
- 当前缺少专门的 Spring ApplicationContext 启动测试验证真实 provider bean list；受影响 reactor 已覆盖编译和模块回归。

下一步：

- 建议进入 Stage 13：迁移 Claude task service 退出聚合 `TaskQueryProvider`，使生产 provider 中不再存在宽聚合实现。
- 备选路线是先推进 `TaskListingProvider` strictly typed method 兼容迁移，收紧 Codex / Claude listing/search contract。

### 2026-06-26 - Stage 13 Claude Narrow Port Bean Migration

Review 发现：

- Stage 12 后 Claude 是生产代码中最后一个直接实现聚合 `TaskQueryProvider` 的 provider。
- Claude 与 Gemini / Codex 的差异是：它仍实际承接 worker-session list/count/messages/sync 查询能力。
- 本阶段应先消除聚合接口暴露，但保留 Claude 的 worker-session 窄端口能力；物理拆分为独立 worker-session bean 可后续单独治理。

已完成：

- `ClaudeTaskService` 从 `TaskQueryProvider` 迁移为 `TaskLookupProvider, TaskCommandProvider, TaskListingProvider, WorkerSessionQueryProvider`。
- 保留 providerType、capability、lookup、create/resume/cancel/delete/respond/reconnect/resync/rewind、listing/search typed envelope、worker-session 查询和 session projection 语义。
- `ClaudeWorkerAgentProvider` 已在 Stage 8 通过 `TaskLookupProvider` 构造 abort wrapper，本阶段无需调整。
- `ClaudeTaskServiceAuthTest#exposesOnlySupportedTaskProviderPorts` 覆盖 Claude task service 类型边界。
- `rg "implements TaskQueryProvider"` 显示生产代码已无聚合实现，仅 session 测试 stub 保留兼容回归。
- Stage 13 子计划、README 索引、质量门、覆盖审计和验收签收记录已回写。

测试证据：

- `mvn test -pl addons/claude-worker-agent -am "-Dtest=ClaudeTaskService*Test,WorkerStreamRelayTest,ClaudeWorkerAgentProviderTest,ClaudeWorkerA2aAgentTest,ClaudeWorkerClientTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：90 tests pass。
- `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：64 tests pass。
- `mvn test -pl session-module,addons/claude-worker-agent -am`：affected reactor 通过，Surefire XML 合计 1320 tests，0 failures，0 errors，0 skipped。
- `git diff --check`：无 whitespace error，仅 CRLF normalization warnings。
- `rg -n "implements TaskQueryProvider" addons navigator-spi session-module`：生产代码已无聚合实现，仅 `TaskQueryProviderRegistryTest` 保留兼容 stub。

质量与覆盖：

- 实现质量门见 `quality/OPT-001-stage13-implementation-quality.md`，decision=`ready-with-risks`。
- 测试覆盖审计见 `coverage/OPT-001-stage13-coverage-audit.md`，conclusion=`ready-with-gaps`，can_enter_acceptance=`yes`。

签收结论：

- 验收记录见 `acceptance/OPT-001-stage13-claude-narrow-port-bean-acceptance.md`。
- acceptance_decision=`accepted-with-risks`，无阻断项。

剩余风险：

- Claude worker-session 查询仍在 `ClaudeTaskService` 物理 bean 内；如需更强职责隔离，后续拆为独立 `WorkerSessionQueryProvider` bean。
- `TaskCommandProvider#cancelTask` 仍保留 deprecated fallback；彻底移除需等 A2A abort 链路和 Provider command 迁移全部收口。
- `TaskListingProvider` 仍使用 `Object` 返回类型；strictly typed method 迁移仍需后续阶段处理。
- worker-session payload 仍是 Map，typed DTO / envelope 不在本阶段范围。
- 当前缺少专门的 Spring ApplicationContext 启动测试验证真实 provider bean list；受影响 reactor、类型边界测试和静态扫描已覆盖本阶段核心风险。

下一步：

- 建议进入 Stage 14：优先治理 `TaskListingProvider` strictly typed method，让 Claude / Codex listing/search contract 从 `Object` 过渡到明确 typed envelope。
- 备选路线是先拆 Claude worker-session 查询为独立 `WorkerSessionQueryProvider` bean，与 LangGraph 的职责边界保持一致。

### 2026-06-26 - Stage 14 TaskListingProvider Typed Method Contract

Review 发现：

- Stage 7 虽已引入 typed envelope，但 `TaskListingProvider` 主方法仍返回 `Object`。
- `TaskDispatchFacade` list/search fan-out 仍直接调用 legacy 方法，弱类型边界没有完全收敛。
- Claude / Codex / Codex Biz 已是 narrow listing providers，适合迁移到 typed method override。

已完成：

- `TaskListingProvider` 新增 `listTaskPage`、`searchSessionPage`、`listDirectoryTaskPage` typed methods。
- `TaskPageResult` / `TaskSearchResult` 新增 legacy Map / public JavaBean getter envelope adapter。
- `TaskDispatchFacade` 三处 provider fan-out 已迁移到 typed methods。
- Claude / Codex / Codex Biz listing/search 实现迁移为 typed override，legacy `Object` 方法保留委派兼容。
- `UnifiedSessionTaskProjectionServiceTest` 补充 legacy envelope compatibility 回归；`TaskDispatchFacadeTest` 和 `CodexTaskServiceTest` 更新为 typed 主路径断言。
- Stage 14 子计划、README 索引、质量门、覆盖审计和验收签收记录已回写。

测试证据：

- `mvn test -pl session-module,addons/codex-worker-agent,addons/claude-worker-agent -am "-Dtest=TaskDispatchFacadeTest,UnifiedSessionTaskProjectionServiceTest,TaskQueryProviderRegistryTest,CodexTaskServiceTest,CodexBizTaskProviderTest,ClaudeTaskServiceAuthTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：121 tests pass。
- `mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent -am`：affected reactor 通过，Surefire XML 合计 191 suites / 1380 tests，0 failures，0 errors，0 skipped。
- `rg -n "provider\.(listTasksPaged|searchSessions|listTasksByDirectoryPaged)\(" session-module/src/main/java addons/claude-worker-agent/src/main/java addons/codex-worker-agent/src/main/java navigator-spi/src/main/java`：无匹配。
- `git diff --check`：无 whitespace error，仅 CRLF normalization warnings。

质量与覆盖：

- 实现质量门见 `quality/OPT-001-stage14-implementation-quality.md`，decision=`ready-with-risks`。
- 测试覆盖审计见 `coverage/OPT-001-stage14-coverage-audit.md`，conclusion=`ready-with-gaps`，can_enter_acceptance=`yes`。

签收结论：

- 验收记录见 `acceptance/OPT-001-stage14-task-listing-typed-method-acceptance.md`。
- acceptance_decision=`accepted-with-risks`，无阻断项。

剩余风险：

- legacy `Object` listing/search 方法仍保留，后续可在外部调用方确认后 deprecate/remove。
- worker-session payload 仍为 Map，typed DTO / envelope 未处理。
- Claude worker-session 查询仍在 `ClaudeTaskService` 物理 bean 内。
- 未运行仓库级全量 `mvn test`，本阶段以 affected reactor 覆盖直接依赖链。

下一步：

- Stage 15 已按优先级推进并签收，详见下方记录。
- 后续建议优先拆 Claude worker-session 查询为独立 `WorkerSessionQueryProvider` bean，再评估 legacy listing / worker-session 方法 deprecation/removal 条件。

### 2026-06-26 - Stage 15 WorkerSession Typed DTO / Envelope

Review 发现：

- `WorkerSessionQueryProvider` 主方法仍返回 `Map` / `List<Map>`，弱类型边界保留在 SPI 主链路。
- `TaskDispatchFacade` worker-session fan-out 仍直接调用 legacy Map 方法，和 Stage 14 listing/search typed 主链路不一致。
- Claude / LangGraph 已具备 worker-session provider 入口，适合先通过 typed override 完成契约收敛，同时保持 REST payload 不变。

已完成：

- 新增 `WorkerSessionSummary`、`WorkerSessionMessage`、`WorkerSessionMessageCount`、`WorkerSessionSyncResult` typed records。
- `WorkerSessionQueryProvider` 新增 `listWorkerSessionSummaries`、`getWorkerSessionMessageCountResult`、`listWorkerSessionMessages`、`syncWorkerSessionState` typed methods。
- `TaskDispatchFacade` 四处 worker-session provider fan-out 已迁移到 typed methods，再通过 `toMap()` 保持 REST payload 兼容。
- Claude / LangGraph worker-session 实现迁移为 typed override，legacy Map 方法保留委派兼容。
- `TaskDispatchFacadeTest` 补充 typed provider verify 与 legacy Map default adapter 回归；`LanggraphWorkerSessionQueryServiceTest` 直接断言 typed DTO / envelope。
- Stage 15 子计划、README 索引、质量门、覆盖审计和验收签收记录已回写。

测试证据：

- `mvn test -pl session-module,addons/claude-worker-agent,addons/langgraph-biz-worker -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest,ClaudeTaskServiceAuthTest,LanggraphWorkerSessionQueryServiceTest,LanggraphTaskServiceTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：114 tests pass。
- `mvn test -pl session-module,addons/claude-worker-agent,addons/langgraph-biz-worker -am`：affected direct reactor 通过，Surefire XML 合计 208 reports / 1461 tests，0 failures，0 errors，0 skipped。
- `mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am`：broader Java worker reactor 通过，Surefire XML 合计 221 reports / 1535 tests，0 failures，0 errors，0 skipped。
- `rg -n "provider\.(listWorkerSessions|getWorkerSessionMessageCount|getWorkerSessionMessages|syncWorkerSessions)\(" session-module/src/main/java addons/claude-worker-agent/src/main/java addons/langgraph-biz-worker/src/main/java navigator-spi/src/main/java`：无匹配。
- `git diff --check`：无 whitespace error，仅 CRLF normalization warnings。

质量与覆盖：

- 实现质量门见 `quality/OPT-001-stage15-implementation-quality.md`，decision=`ready-with-risks`。
- 测试覆盖审计见 `coverage/OPT-001-stage15-coverage-audit.md`，conclusion=`ready-with-gaps`，can_enter_acceptance=`yes`。

签收结论：

- 验收记录见 `acceptance/OPT-001-stage15-worker-session-typed-envelope-acceptance.md`。
- acceptance_decision=`accepted-with-risks`，无阻断项。

剩余风险：

- legacy Map worker-session 方法仍保留，后续可在外部调用方确认后 deprecate/remove。
- REST worker-session payload 仍为 Map，本阶段只收敛 Java SPI 主路径。
- Claude worker-session 查询仍在 `ClaudeTaskService` 物理 bean 内。
- 未运行仓库级全量 `mvn test`，本阶段以 affected reactor 和 broader Java worker reactor 覆盖直接依赖链。

下一步：

- Stage 16 已按建议推进并签收，详见下方记录。
- 后续建议规划 legacy listing / worker-session 方法 deprecation/removal 条件；不建议早于外部插件兼容性确认直接删除。

### 2026-06-26 - Stage 16 Claude WorkerSession Provider Bean Split

Review 发现：

- Stage 15 后 worker-session 主链路已迁移到 typed DTO / envelope，但 Claude worker-session 查询仍在 `ClaudeTaskService` 物理 bean 内。
- `ClaudeTaskService` 同时承担 task lookup、command、listing 和 worker-session provider 注册，会削弱 Stage 8 窄端口集合与 Stage 9 LangGraph 拆分带来的职责边界收益。
- Claude 已具备 typed worker-session methods，适合作为低风险物理 bean 拆分切片。

已完成：

- 新增 `ClaudeWorkerSessionQueryService implements WorkerSessionQueryProvider`，providerType 保持 `claude-worker`。
- `ClaudeWorkerSessionQueryService` 独立声明 `LIST_WORKER_SESSIONS`、`GET_WORKER_SESSION_MESSAGE_COUNT`、`GET_WORKER_SESSION_MESSAGES`、`SYNC_WORKER_SESSIONS`。
- `ClaudeTaskService` 不再实现 `WorkerSessionQueryProvider`，也不再声明 worker-session capabilities。
- worker-session list/count/messages/sync typed 与 legacy Map wrapper 已迁移到新 service，REST payload 兼容语义不变。
- sync path 暂时复用 `ClaudeTaskService.syncLocalSessions(...)` 作为本地任务投影入口，避免重复或重写落库规则。
- `ClaudeWorkerSessionQueryServiceTest` 覆盖 capabilities、list/count/messages/sync 和跨用户 worker 拒绝；`ClaudeTaskServiceAuthTest` 覆盖 task service 类型边界。
- Stage 16 子计划、README 索引、质量门、覆盖审计和验收签收记录已回写。

测试证据：

- `mvn test -pl session-module,addons/claude-worker-agent -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest,ClaudeTaskServiceAuthTest,ClaudeWorkerSessionQueryServiceTest,ClaudeTaskServiceSyncTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：100 tests pass。
- `mvn test -pl session-module,addons/claude-worker-agent -am`：affected reactor 通过，Surefire XML 合计 183 reports / 1328 tests，0 failures，0 errors，0 skipped。
- `rg -n "WorkerSessionQueryProvider|LIST_WORKER_SESSIONS|GET_WORKER_SESSION_MESSAGE_COUNT|GET_WORKER_SESSION_MESSAGES|SYNC_WORKER_SESSIONS" addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`：无匹配。
- `rg -n "class ClaudeWorkerSessionQueryService|implements WorkerSessionQueryProvider|LIST_WORKER_SESSIONS|getProviderType|getCapabilities|syncWorkerSessionState" addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeWorkerSessionQueryService.java`：确认新 service 独立实现 worker-session provider。
- `git diff --check`：无 whitespace error，仅 CRLF normalization warnings。

质量与覆盖：

- 实现质量门见 `quality/OPT-001-stage16-implementation-quality.md`，decision=`ready-with-risks`。
- 测试覆盖审计见 `coverage/OPT-001-stage16-coverage-audit.md`，conclusion=`ready-with-gaps`，can_enter_acceptance=`yes`。

签收结论：

- 验收记录见 `acceptance/OPT-001-stage16-claude-worker-session-bean-acceptance.md`。
- acceptance_decision=`accepted-with-risks`，无阻断项。

剩余风险：

- `ClaudeWorkerSessionQueryService` sync path 仍依赖 `ClaudeTaskService.syncLocalSessions(...)`；后续如继续压低 service 间耦合，可单独抽 `ClaudeSessionProjectionService`。
- legacy listing / worker-session 方法仍保留，后续需结合外部插件/调用方兼容性再规划 deprecation / removal。
- `TaskCommandProvider#cancelTask` deprecated fallback、migration execution record / version table 与真实 MySQL smoke 仍属于后续治理项。
- 未运行仓库级根目录全量 `mvn test`，本阶段以 affected reactor 覆盖直接依赖链。

下一步：

- Stage 17 已按建议推进并签收，详见下方记录。
- 后续仍可单独抽 `ClaudeSessionProjectionService`，进一步降低 `ClaudeWorkerSessionQueryService` 对 `ClaudeTaskService` 的 sync 投影依赖。

### 2026-06-26 - Stage 17 Legacy Provider Method Deprecation Gate

Review 发现：

- Stage 14/15 后 session-module 生产 provider fan-out 已迁移到 typed methods，但 legacy listing / worker-session SPI 方法仍保留为兼容入口。
- 直接删除 legacy 方法会破坏外部插件、SDK、测试 stub 或未纳入本仓的 provider 实现；不适合作为本阶段动作。
- 如果不显式 deprecate，后续实现仍可能继续误用旧 `Object` / Map 契约，导致 typed 主链路治理效果被稀释。

已完成：

- `TaskListingProvider` legacy `listTasksPaged`、`searchSessions`、`listTasksByDirectoryPaged` 标记 `@Deprecated(since = "1.3.1", forRemoval = false)`。
- `WorkerSessionQueryProvider` legacy `listWorkerSessions`、`getWorkerSessionMessageCount`、`getWorkerSessionMessages`、`syncWorkerSessions` 标记 `@Deprecated(since = "1.3.1", forRemoval = false)`。
- Claude / Codex / Codex Biz listing legacy wrappers 和 Claude / LangGraph worker-session legacy wrappers 同步标记 deprecation。
- typed default adapter 保留兼容调用并 suppress 内部 deprecation warning，REST payload 兼容语义不变。
- 新增 `TaskProviderLegacyContractTest`，反射断言 SPI legacy methods 均为 `since=1.3.1` 且 `forRemoval=false`。
- Stage 17 子计划、README 索引、质量门、覆盖审计和验收签收记录已回写。

测试证据：

- `mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/langgraph-biz-worker -am "-Dtest=TaskProviderLegacyContractTest,TaskDispatchFacadeTest,TaskQueryProviderRegistryTest,ClaudeTaskServiceAuthTest,ClaudeWorkerSessionQueryServiceTest,CodexTaskServiceTest,CodexBizTaskProviderTest,LanggraphWorkerSessionQueryServiceTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：131 tests pass。
- `mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/langgraph-biz-worker -am`：affected reactor 通过，Surefire XML 合计 219 reports / 1528 tests，0 failures，0 errors，0 skipped。
- `rg -n "provider\.(listTasksPaged|searchSessions|listTasksByDirectoryPaged|listWorkerSessions|getWorkerSessionMessageCount|getWorkerSessionMessages|syncWorkerSessions)\(" session-module/src/main/java addons/claude-worker-agent/src/main/java addons/codex-worker-agent/src/main/java addons/langgraph-biz-worker/src/main/java navigator-spi/src/main/java`：无匹配。
- deprecated annotation fixed-string scan：命中 24 处 expected `@Deprecated(since = "1.3.1", forRemoval = false)`。
- `git diff --check`：无 whitespace error，仅 CRLF normalization warnings。

质量与覆盖：

- 实现质量门见 `quality/OPT-001-stage17-implementation-quality.md`，decision=`ready-with-risks`。
- 测试覆盖审计见 `coverage/OPT-001-stage17-coverage-audit.md`，conclusion=`ready-with-gaps`，can_enter_acceptance=`yes`。

签收结论：

- 验收记录见 `acceptance/OPT-001-stage17-legacy-provider-method-deprecation-acceptance.md`。
- acceptance_decision=`accepted-with-risks`，无阻断项。

剩余风险：

- 外部插件、SDK 或未纳入本仓测试的调用方可能仍使用 legacy 方法；removal 前必须提供迁移窗口与 release note。
- 本阶段没有删除 legacy 方法，也没有设置 `forRemoval=true`；后续 removal 必须另起 workitem 并至少覆盖 broader Java worker reactor。
- `TaskCommandProvider#cancelTask` deprecated fallback、migration execution record / version table、真实 MySQL smoke、可选的 `ClaudeSessionProjectionService` 仍属于后续治理项。
- 未运行根目录仓库级全量 `mvn test`，本阶段以 affected reactor 覆盖直接依赖链。

下一步：

- Stage 18 已按建议推进并签收，详见下方记录。
- Stage 19 已推进 migration support foundation 并签收，详见下方记录。
- Stage 20 已推进 startup migration runner / manifest；后续建议优先规划 migration execution record / version table 与真实 MySQL smoke，或单独抽 `ClaudeSessionProjectionService`，进一步降低 Claude worker-session sync 对 task service 的内部依赖。

### 2026-06-26 - Stage 18 TaskCommandProvider Cancel Direct Method

Review 发现：

- `TaskCommandProvider#cancelTask(String, String)` 已是 deprecated fallback，但 session provider-route 仍直接调用该方法。
- 内置 provider 的真实取消逻辑仍实现于 legacy override，导致 command provider 主链路与 deprecated contract 绑定。
- A2A abort 的 `A2aAgent#cancelTask(String)` 与 provider command cancel 名称相近，需要在 SPI 与测试中明确边界，避免误删或误改 A2A 取消链路。

已完成：

- `TaskCommandProvider` 新增非 deprecated `cancelTaskDirect(String, String)` 主方法；default implementation 委派 legacy `cancelTask`，兼容外部只 override legacy 方法的 provider。
- `TaskCommandProvider#cancelTask(String, String)` 保留兼容入口，并从 `forRemoval=true` 收敛为 `forRemoval=false`。
- `TaskOperationRouter` provider cancel route 已迁移到 `cancelTaskDirect`；A2A route 仍保持 `A2aAgent#cancelTask(String)` 语义不变。
- Claude / Codex / Codex Biz / Gemini / LangGraph 内置 provider 真实取消逻辑已迁移到 `cancelTaskDirect`，legacy `cancelTask` wrapper 保留委派兼容。
- `CodexBizTaskProvider` 与 `LanggraphWorkerInnerA2aAgent` 内部 service 调用同步迁移到 direct method。
- `TaskProviderLegacyContractTest` 补充 direct method 非 deprecated、legacy method `forRemoval=false` 反射回归。
- Stage 18 子计划、README 索引、质量门、覆盖审计和验收签收记录已回写。

测试证据：

- `mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am "-Dtest=TaskProviderLegacyContractTest,TaskDispatchFacadeTest,TaskQueryProviderRegistryTest,ClaudeTaskServiceAuthTest,CodexTaskServiceTest,CodexBizTaskProviderTest,GeminiTaskServiceAuthResolutionTest,LanggraphTaskServiceTest,LanggraphWorkerInnerA2aAgentTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：Surefire XML 合计 11 reports / 159 tests，0 failures，0 errors，0 skipped。
- `mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am`：affected reactor 通过，Surefire XML 合计 223 reports / 1545 tests，0 failures，0 errors，0 skipped。
- `rg -n "provider\.cancelTask\(" session-module/src/main/java`：无匹配，session provider-route 不再调用 legacy provider cancel。
- `cancelTaskDirect(` static scan：覆盖 SPI、session route、内置 provider 与相关测试。
- `TaskCommandProvider` legacy `forRemoval=true` scan 无匹配，expected `forRemoval=false` annotation 存在。
- `git diff --check`：无 whitespace error，仅 CRLF normalization warnings。

质量与覆盖：

- 实现质量门见 `quality/OPT-001-stage18-implementation-quality.md`，decision=`ready-with-risks`。
- 测试覆盖审计见 `coverage/OPT-001-stage18-coverage-audit.md`，conclusion=`ready-with-gaps`，can_enter_acceptance=`yes`。

签收结论：

- 验收记录见 `acceptance/OPT-001-stage18-task-command-cancel-direct-method-acceptance.md`。
- acceptance_decision=`accepted-with-risks`，无阻断项。

剩余风险：

- 外部插件、SDK 或未纳入本仓测试的 provider 可能仍 override / call legacy `cancelTask(String, String)`；removal 前必须提供迁移窗口与 release note。
- 本阶段只建立 direct method 主路径，不删除 legacy cancel；彻底 removal 必须另起 workitem 并覆盖外部兼容性确认。
- migration execution record / version table、真实 MySQL smoke、可选的 `ClaudeSessionProjectionService` 仍属于后续治理项。
- 未运行根目录仓库级全量 `mvn test`，本阶段以 direct affected reactor 覆盖涉及模块及其 `-am` 依赖。

下一步：

- Stage 19 已推进 migration support foundation 并签收，已把现有启动 migration 的重复 schema helper 收敛到共享 helper，并明确历史 SQL 不自动执行。
- Stage 20 已推进 startup migration runner / manifest，并完成 enabled / dry-run 开关、统一启动入口和签收；真实 MySQL smoke 与 migration execution record 保留为后续 Stage 21 候选。
- 备选路线是抽 `ClaudeSessionProjectionService`，降低 `ClaudeWorkerSessionQueryService` 对 `ClaudeTaskService.syncLocalSessions(...)` 的内部依赖。
- legacy listing / worker-session / command cancel 方法 removal 不建议立即推进，至少等一个版本周期、外部插件迁移说明与 release note 完成后再评估。

### 2026-06-26 - Stage 19 Migration Support Foundation

Review 发现：

- Stage 5 将生产 `ddl-auto` 收敛为 `validate` 后，schema preparation 仍依赖人工流程或既有启动 migration。
- `CodingAgentTenantScopeMigration` 与 `GeminiFlashRuntimeBudgetMigration` 均有 MySQL-only guard、table/column/index 检查和 warn-not-fail 语义，但底层 JDBC metadata / INFORMATION_SCHEMA 逻辑重复。
- `docs/migration/*.sql` 包含一次性改表、数据迁移和 dev tenant 修复脚本，不适合作为启动时自动执行脚本直接纳入。

已完成：

- 新增 `DatabaseMigrationSupport`，集中提供 MySQL detection、table / column / index 检查、single-column unique index 查询和 identifier quote。
- `CodingAgentTenantScopeMigration` 迁移到共享 helper，保留 `agent_profile` 补列、legacy single-column unique index drop、tenant+agent composite unique index create 语义。
- `GeminiFlashRuntimeBudgetMigration` 迁移到共享 helper，保留 MySQL-only、table guard、idempotent update 和 warn-not-fail 语义。
- 历史 `docs/migration/*.sql` 保持人工运维脚本定位，本阶段未自动执行、未引入 Flyway/Liquibase。
- `DatabaseMigrationSupport` 使用 `Locale.ROOT` 进行数据库产品名判断，避免区域化大小写边界。
- Stage 19 子计划、README 索引、质量门、覆盖审计和验收签收记录已回写。

测试证据：

- `mvn test -pl navigator-common -am "-Dtest=DatabaseMigrationSupportTest,CodingAgentTenantScopeMigrationTest,GeminiFlashRuntimeBudgetMigrationTest,CommonAutoConfigurationTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：12 tests pass。
- `mvn test -pl launcher -am`：affected reactor 通过，Surefire XML 合计 250 reports / 1669 tests，0 failures，0 errors，0 skipped。
- `rg -n "docs/migration|migration/.*\.sql|ClassPathResource|ResourceDatabasePopulator|ScriptUtils" navigator-common/src/main/java launcher/src/main/java -S`：无匹配，main code 未自动执行历史 SQL。
- `DatabaseMigrationSupport` usage scan：仅命中预期 migration 与测试。
- `git diff --check`：无 whitespace error，仅 CRLF normalization warnings。

质量与覆盖：

- 实现质量门见 `quality/OPT-001-stage19-implementation-quality.md`，decision=`ready-with-risks`。
- 测试覆盖审计见 `coverage/OPT-001-stage19-coverage-audit.md`，conclusion=`ready-with-gaps`，can_enter_acceptance=`yes`。

签收结论：

- 验收记录见 `acceptance/OPT-001-stage19-migration-support-foundation-acceptance.md`。
- acceptance_decision=`accepted-with-risks`，无阻断项。

剩余风险：

- 本阶段只建立 migration support foundation，不提供完整 migration runner、manifest、版本表、dry-run/apply 或 rollback 机制。
- 未连接真实 MySQL 实例执行 smoke，当前通过 mocked JDBC metadata / JdbcTemplate 行为回归覆盖。
- 启动 migration 继续保持 warn-not-fail，避免启动中断，但部署侧仍需显式检查 schema 结果。
- 未运行仓库根目录全量 `mvn test`，本阶段以 `launcher -am` affected reactor 覆盖生产启动链路。

下一步：

- Stage 20 已推进 startup migration runner / manifest，把现有 Java startup migrations 纳入统一 runner、manifest、enabled / dry-run 开关和签收流程。
- 后续如继续处理生产 schema 风险，建议 Stage 21 优先设计 migration execution record / version table 与真实 MySQL smoke，再评估是否扩展到历史 SQL runner。
- 备选路线仍是抽 `ClaudeSessionProjectionService`，降低 `ClaudeWorkerSessionQueryService` 对 `ClaudeTaskService.syncLocalSessions(...)` 的内部依赖。
- legacy listing / worker-session / command cancel 方法 removal 不建议立即推进，至少等一个版本周期、外部插件迁移说明与 release note 完成后再评估。

### 2026-06-26 - Stage 20 Startup Migration Runner / Manifest

Review 发现：

- Stage 19 后既有 startup migrations 已复用 `DatabaseMigrationSupport`，但仍由各自 `@EventListener(ApplicationReadyEvent.class)` 分散触发。
- 生产启动时缺少统一 manifest、确定性排序、enabled / dry-run 运维开关和单项失败处理口径。
- 历史 `docs/migration/*.sql` 仍包含一次性人工脚本，不适合在本阶段自动接入启动执行。

已完成：

- 新增 `DatabaseStartupMigration` 契约，要求 startup migration 暴露稳定 `id`、`description` 和 `migrate()`。
- 新增 `DatabaseStartupMigrationDescriptor`、`DatabaseStartupMigrationProperties` 与 `DatabaseStartupMigrationRunner`。
- runner 统一监听 `ApplicationReadyEvent`，按 migration id 排序输出 manifest 并执行。
- runner 统一处理 disabled、non-MySQL、dry-run 和单 migration 失败继续，失败策略保持 warn-not-fail。
- `CodingAgentTenantScopeMigration` 与 `GeminiFlashRuntimeBudgetMigration` 已移除各自 event listener，改为 `DatabaseStartupMigration` bean。
- `application-prod.yml` 增加 `NAVIGATOR_DATABASE_STARTUP_MIGRATIONS_ENABLED` 与 `NAVIGATOR_DATABASE_STARTUP_MIGRATIONS_DRY_RUN` 环境变量映射。
- 历史 `docs/migration/*.sql` 继续保持人工运维脚本定位，本阶段未自动执行、未引入 Flyway/Liquibase。
- Stage 20 子计划、README 索引、质量门、覆盖审计和验收签收记录已回写。

测试证据：

- `mvn test -pl navigator-common -am "-Dtest=DatabaseStartupMigrationRunnerTest,DatabaseMigrationSupportTest,CodingAgentTenantScopeMigrationTest,GeminiFlashRuntimeBudgetMigrationTest,CommonAutoConfigurationTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：17 tests pass。
- `mvn test -pl launcher -am`：affected reactor 通过，Surefire XML 合计 251 reports / 1674 tests，0 failures，0 errors，0 skipped。
- `rg -n "ApplicationReadyEvent|@EventListener|implements DatabaseStartupMigration|DatabaseStartupMigrationRunner|startup-migrations" navigator-common/src/main/java launcher/src/main/resources/application-prod.yml navigator-common/src/test/java -S`：确认 startup migration event listener 只剩统一 runner，两个既有 migration 均实现 `DatabaseStartupMigration`。
- `rg -n "docs/migration|migration/.*\.sql|ClassPathResource|ResourceDatabasePopulator|ScriptUtils" navigator-common/src/main/java launcher/src/main/java -S`：无匹配，main code 未自动执行历史 SQL。
- `git diff --check`：无 whitespace error，仅 CRLF normalization warnings。

质量与覆盖：

- 实现质量门见 `quality/OPT-001-stage20-implementation-quality.md`，decision=`ready-with-risks`。
- 测试覆盖审计见 `coverage/OPT-001-stage20-coverage-audit.md`，conclusion=`ready-with-gaps`，can_enter_acceptance=`yes`。

签收结论：

- 验收记录见 `acceptance/OPT-001-stage20-startup-migration-runner-acceptance.md`。
- acceptance_decision=`accepted-with-risks`，无阻断项。

剩余风险：

- 本阶段没有实现 migration version table / execution record / rollback；新增复杂 migration 前仍需单独设计。
- 未连接真实 MySQL 实例执行 smoke，当前通过 unit/mock 与 launcher affected reactor 覆盖。
- 历史 `docs/migration/*.sql` 仍未纳入自动化 runner，后续需要脚本分类、幂等校验和运维流程。
- runner 保持 warn-not-fail，部署侧仍需显式监控启动日志并核对 schema 结果。
- 未运行仓库根目录全量 `mvn test`，本阶段以 `launcher -am` affected reactor 覆盖生产启动链路。

下一步：

- 建议 Stage 21 优先补 migration execution record / version table 和真实 MySQL smoke，把 startup migration 从“幂等动作集合”推进到“可审计执行记录”。
- 备选路线仍是抽 `ClaudeSessionProjectionService`，降低 `ClaudeWorkerSessionQueryService` 对 `ClaudeTaskService.syncLocalSessions(...)` 的内部依赖。
- legacy listing / worker-session / command cancel 方法 removal 不建议立即推进，至少等一个版本周期、外部插件迁移说明与 release note 完成后再评估。
