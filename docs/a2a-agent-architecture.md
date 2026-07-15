# A2A Agent 统一发现、路由与任务分发架构

> 当前 Foggy Navigator 中 Agent 发现、Provider 解析、Direct Provider Route 与统一任务分发的实现口径。

## 1. 文档定位

本文说明 Java 后端如何把不同来源的 Agent / Worker Provider 纳入统一会话和任务链路。它是实现架构文档，不定义产品功能范围。

产品功能边界优先参考：

- [系统架构概览](./00-system-overview.md)
- [功能架构说明](./02-modules/functional-architecture.md)

本次同步源自 `1.3.1-SNAPSHOT` Java 侧架构风险治理工作项：

- [OPT-001: Java 侧架构风险治理与核心链路优化](./version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md)

## 2. 当前作用

A2A / Provider 架构当前承担四类职责：

1. Agent 发现  
   把多个 `A2aAgentProvider` 暴露的 Agent Card 聚合成统一列表。
2. Agent 解析  
   根据 `agentId`、`providerType`、`modelConfigId` 和用户/租户上下文定位真实执行后端。
3. 统一任务分发
   支持显式 Agent 的 A2A Route，也支持目录、Worker、Provider、modelConfig 驱动的 Direct Provider Route。
4. 任务查询与操作聚合
   通过 `TaskQueryProvider` 聚合各 Provider 的查询、恢复、取消、回退、重连和会话同步能力。

## 3. 当前运行拓扑

```text
Frontend / OpenAPI / SDK
  -> TaskController / AgentDiscoveryController
  -> AgentSubmitPipeline
  -> TaskDispatchFacade
  -> UnifiedAgentResolver
  -> A2aAgentProvider / A2aAgent / TaskQueryProvider
  -> Provider TaskService
  -> WorkerStreamRelay
  -> AgentMessage / TaskStatusChangeEvent / TaskCompletionEvent
  -> SessionEventListener / TaskUpdateNotifier
  -> UnifiedSseEmitter
  -> Frontend / SDK SSE consumer
```

## 4. 核心术语

| 术语 | 含义 |
| --- | --- |
| `logicalAgentId` / `agentId` | 平台侧可发现、可绑定的逻辑 Agent。 |
| `providerType` | 执行后端类型，例如 `claude-worker`、`codex-worker`、`codex-app-server-worker`、`codex-biz-worker`、`gemini-worker`、`langgraph-biz-worker`。 |
| `modelConfigId` | 平台 LLM 模型配置，通常决定模型、凭证、baseUrl 和 worker backend 兼容性。 |
| `workerBackend` | 模型配置中的物理执行后端标识，例如 `CLAUDE_CODE`、`OPENAI_CODEX`、`GEMINI_CLI`、`LANGGRAPH_BIZ`。 |
| `A2aAgentProvider` | 某类 Agent 来源的 Spring Bean，负责列出和解析 Agent。 |
| `A2aAgent` | 已解析的可执行 Agent，提供发送任务、查任务、取消任务的最小接口。 |
| `TaskQueryProvider` | Provider 级任务查询和操作 SPI，不依赖先解析出某个 Agent 实例。 |
| `TaskQueryCapability` | `TaskQueryProvider` 的可选能力描述，用于统一入口 fan-out 前缩小 Provider 候选集合。 |
| `TaskQueryProviderRegistry` | `session-module` 内部 Provider 查找辅助，集中按 providerType、taskId 或 capability 定位 `TaskQueryProvider`。 |
| `TaskCreateTargetResolver` | `session-module` 内部 create 路径目标推导组件，处理显式 Agent、`directory#`、session 绑定和 modelConfig fallback。 |
| `TaskOperationRouter` | `session-module` 内部 Provider 操作路由组件，处理 direct create、cancel、respond、reconnect、resync、rewind、resume、delete 和 checkpoint scan。 |
| `UnifiedSessionTaskProjectionService` | `session-module` 内部统一 session-store 查询与 DTO 投影组件。 |
| Direct Provider Route | 不通过显式 `agentId`，由目录、worker、providerType 或 modelConfig 直接定位 Provider 的任务创建路径。 |
| A2A Route | 由显式 `agentId` 解析出 `A2aAgent` 后调用 `sendTask()` 的路径。 |

## 5. 核心接口

### 5.1 `A2aAgent`

统一执行接口，表达“一个已解析、可调用的 Agent”。

当前动作：

- 返回 Agent Card
- 发送任务
- 查询任务
- 取消任务

### 5.2 `A2aAgentProvider`

统一提供者接口，表达“某类 Agent 来源”。

每个 Provider 负责：

- 列出自己管理的 Agent Card
- 按用户/租户上下文解析指定 `agentId`
- 返回自己的 `providerType`

### 5.3 `TaskQueryProvider`

Provider 级任务查询和操作 SPI，供 `TaskDispatchFacade` / `TaskOperationRouter` 聚合使用。

它覆盖：

- 按 taskId / sessionId / directory / worker 查询任务
- 创建 Direct Provider Route 任务
- cancel / resume / reconnect / resync / rewind
- worker session 列表与消息同步

Stage 2.4 后，`TaskQueryProvider` 增加 `getCapabilities()` / `supports(...)` 默认方法。空 capability 集合表示 legacy provider，`TaskDispatchFacade` 的聚合查询会在没有明确支持者时保留旧的遍历 fallback。

## 6. 当前主要实现

### 6.1 `UnifiedAgentResolver`

职责：

- 聚合所有 `A2aAgentProvider`
- 按 `providerType`、`modelConfigId` 和 `agentId` 推断目标 Provider
- 给 `TaskDispatchFacade` 提供统一解析结果

### 6.2 `TaskDispatchFacade`

职责：

- 统一承接前端、OpenAPI、A2A 装饰层和任务端点的任务请求
- 解析执行目标：显式 Agent、目录、worker、providerType、modelConfig
- 维护 Session 与 Agent / Provider 绑定
- 调用 A2A Route 或 Direct Provider Route
- 聚合 Provider 的任务查询和 worker session 同步能力，并委托 `TaskOperationRouter` 执行任务操作路由

Stage 2.1 后，Provider 列表与按 `providerType` / `taskId` 的查找已委托给 `TaskQueryProviderRegistry`。Facade 仍作为统一入口，但内部 Provider 查找、创建目标推导、统一查询/投影和任务操作已逐步拆分，后续治理继续在 `OPT-001` 跟踪。

Stage 2.2~2.5 后：

- create 路径执行目标推导已委托 `TaskCreateTargetResolver`
- 统一 session-store 查询、provider page/search envelope 读取和 `DispatchTaskDTO` 投影已委托 `UnifiedSessionTaskProjectionService`
- Provider fan-out 查询会优先使用 `TaskQueryCapability` 缩小候选集合
- direct create、cancel、respond、reconnect、resync、rewind、resume、delete 和 scan checkpoints 已委托 `TaskOperationRouter`
- Facade 仍保留 Controller 入口、create A2A 编排、列表聚合和创建请求诊断状态回填

### 6.3 `TaskOperationRouter`

职责：

- 按统一任务投影或 Provider task 查询结果定位 `TaskQueryProvider`
- 执行 direct create、cancel、respond、reconnect、resync、rewind、resume、delete 和 checkpoint scan
- 在 resume 时优先复用 session 已绑定的 providerType，并规范化冲突的 providerType / modelConfigId
- 在 cancel/delete 路径保留终态 no-op、Provider 已缺失清理和 A2A fallback 等兼容语义

该组件不暴露 REST API，不改变 `TaskDispatchFacade` 的公开方法；它只是把 Provider 操作路由从 Facade 中移出。

### 6.4 `SessionBindingService`

职责：

- 新会话首个任务建立 Agent / Provider 绑定
- 已绑定会话禁止漂移到另一个 Agent / Provider
- 对历史会话补齐 providerType 或恢复绑定来源

### 6.5 `UnifiedSseEmitter`

职责：

- 维护用户 SSE 连接和 session 订阅关系
- 推送 `session_event`、`assistant_notification`、`task_update`、`heartbeat`
- 当前实现为单 JVM 内存态，`userEmitters`、`userSubscriptions` 和 `sessionToUsers` 不跨应用实例共享
- 当前版本支持单实例，或部署层按用户/会话对 `/api/v1/sse/**` 做粘性路由，并保证订阅请求与任务事件生产链路同实例亲和
- 浏览器断线重连后需重新建立 SSE 并重新订阅活跃 session；错过的状态以消息历史、任务详情查询或 provider resync 补偿，不依赖 SSE replay
- 非粘性多实例需要外部事件总线或集中通知服务后再支持，演进计划见 `OPT-001 Stage 4`

### 6.6 `ProviderRouteRegistry`

职责：

- 作为 `workerBackend` 到 `providerType` 的统一映射入口
- 规范化 `workerBackend` 输入，兼容大小写、短横线和下划线形式
- 将 `providerType`、短别名和诊断字段中的 route token 反向映射到 canonical `workerBackend`
- 维护 modelConfig 目标 Provider 与实际执行 Provider 的兼容规则
- `OPENAI_CODEX` 固定映射 `codex-worker`；`OPENAI_CODEX_APP_SERVER` 固定映射 `codex-app-server-worker`
- `codex-biz-worker` 仍可复用 `OPENAI_CODEX` modelConfig，但不会隐式切入 App Server
- 被 `TaskDispatchFacade`、`UnifiedAgentResolver`、`JpaSessionManager`、配置服务、业务接入、OpenAPI 诊断链路和 Claude / Codex / Gemini / LangGraph Provider adapter 复用

该组件只负责静态路由语义，不判断当前运行期是否存在可用的 `TaskQueryProvider`。运行期可用性由 `TaskQueryProviderRegistry` 和 `TaskQueryCapability` 声明辅助判断，最终仍以 Provider Bean 是否存在及具体调用结果为准。

## 7. 当前 Provider

| Provider | providerType | 主要模块 | 说明 |
| --- | --- | --- | --- |
| Claude Worker | `claude-worker` | `addons/claude-worker-agent` | Claude Worker、目录、文件、跨项目、OpenAPI 任务主通道。 |
| Codex Worker | `codex-worker` | `addons/codex-worker-agent` | Codex CLI Worker 任务通道，复用工作目录治理。 |
| Codex App Server Worker | `codex-app-server-worker` | `addons/codex-worker-agent` | Codex app-server 独立任务通道；Session/Task 只能续接 App Server Thread/Turn。 |
| Codex Biz Route | `codex-biz-worker` | `addons/codex-worker-agent` | OpenAPI / 业务侧 Codex 直连路由，无独立可发现 Agent；可复用 `OPENAI_CODEX` modelConfig。 |
| Gemini Worker | `gemini-worker` | `addons/gemini-worker-agent` | Gemini CLI Worker 任务通道。 |
| LangGraph Biz Worker | `langgraph-biz-worker` | `addons/langgraph-biz-worker` | 业务 Agent / Skill / Function 执行通道。 |

旧 `echo-agent` 已在 1.4.2 dev 阶段退出源码、根 reactor 和默认 `launcher`，因此不再是当前 Provider。`UnifiedAgentResolverTest` 中的 test-only 内存 fixture 独立覆盖 discovery、resolve、send、query 和 cancel，不会向默认运行时注册合成 Agent。

## 8. 任务路由语义

### 8.1 A2A Route

适用场景：

- 请求明确携带 `agentId`
- 需要先通过 Provider 解析出一个 `A2aAgent`
- 会话绑定的是逻辑 Agent 与 providerType

基本流程：

```text
TaskDispatchFacade
  -> UnifiedAgentResolver.resolveAgent(agentId, context)
  -> SessionBindingService.bind(...)
  -> A2aAgent.sendTask(message)
  -> provider task projection
```

### 8.2 Direct Provider Route

适用场景：

- 请求从目录、worker、providerType 或 modelConfig 进入
- 前端任务创建需要完整触发 Provider `TaskService`，包括 Session 创建、Provider task 持久化、WorkerTaskStartEvent 和 StreamRelay
- 不需要先暴露成某个显式 Agent

基本流程：

```text
TaskDispatchFacade
  -> TaskCreateTargetResolver.resolveCreateExecutionTarget(...)
  -> TaskOperationRouter.createTaskDirect(...)
  -> TaskQueryProvider.createTaskDirect(...)
  -> Provider TaskService
  -> WorkerTaskStartEvent
  -> StreamRelay
```

## 9. 与会话和事件的关系

- `SessionEntity` 是统一会话投影，记录用户、租户、agentId、providerType、当前 worker / directory、latestTaskId 和 provider 私有状态。
- Session 首个任务绑定真实 providerType；SDK 与 App Server 之间切换必须创建新 Session，不携带原生 Thread ID，也不存在 Provider fallback。
- `SessionTaskEntity` 是统一任务流水，记录 taskId、providerType、providerTaskId、workerId、directoryId、status、modelConfigId 和 task 私有状态。
- `SessionMessageEntity` 存储可回放消息历史。
- `SessionEventListener` 监听 `AgentMessage`，负责消息落库和 SSE 推送。
- `TaskUpdateNotifier` 监听任务状态变化，推送 `task_update`。

## 10. 当前边界与风险

1. A2A 架构不是通用工作流引擎；跨项目阶段编排仍由专门模块承担。
2. `TaskDispatchFacade` 当前仍是统一分发入口；Stage 2.1~2.5 已抽出 Provider 查找、创建目标推导、统一投影、capability fan-out 和任务操作路由，但 Provider 状态 schema 与 SPI 端口拆分仍需继续治理。
3. Provider route / backend 的核心映射已收敛到 `ProviderRouteRegistry`；后续新增 Provider 应优先扩展该注册表和对应回归，而不是在业务模块复制常量。
4. `providerStateJson` 与 `taskStateJson` 需要 schema 化，避免恢复、回退和跨版本兼容依赖隐式字段。
5. `UnifiedSseEmitter` 当前为内存态实现；1.3.1-SNAPSHOT 支持单实例或具备同实例亲和的粘性会话部署，非粘性多实例需要外部事件总线或集中通知服务后再支持。

上述治理项统一跟踪在 [OPT-001](./version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md)。

## 11. 阅读建议

建议结合以下文档一起阅读：

- [系统架构概览](./00-system-overview.md)
- [任务治理中心](./02-modules/task-governance.md)
- [会话协作中心](./02-modules/session-collaboration.md)
- [平台设置与资源治理](./02-modules/platform-governance.md)

---

**更新日期**: 2026-06-25
**基准**: 当前 Java 代码结构、`session-module` 统一任务分发、`navigator-spi` Provider SPI 与各 Worker addon 实现
