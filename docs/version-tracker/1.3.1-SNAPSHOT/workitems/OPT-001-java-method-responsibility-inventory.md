---
type: architecture-inventory
version: 1.3.1-SNAPSHOT
ticket: OPT-001
status: done
owner: java-platform
created_at: 2026-06-25
---

# OPT-001: Java 方法级职责清单

## 文档作用

- doc_type: architecture-inventory
- intended_for: execution-agent | reviewer | refactor-owner
- purpose: 为 OPT-001 的后续实现提供方法级职责基线，明确哪些职责应先收敛，哪些职责应保留在当前边界。

## Scope / Evidence

本清单基于 2026-06-25 对 Java 核心链路的静态读取与 Stage 0 测试基线：

| File | Approx. lines | Primary role |
| --- | ---: | --- |
| `session-module/.../TaskDispatchFacade.java` | 911 | 统一任务入口、A2A DTO 编排、列表聚合、请求诊断状态回填 |
| `session-module/.../TaskCreateTargetResolver.java` | 126 | create 路径执行目标推导和 modelConfig/provider 兼容解析 |
| `session-module/.../TaskOperationRouter.java` | 343 | direct create、任务操作、resume 规范化和 Provider 操作路由 |
| `session-module/.../TaskDispatchRequestParams.java` | 54 | Direct / Resume / A2A metadata 共用请求参数转换 |
| `session-module/.../UnifiedSessionTaskProjectionService.java` | 711 | 统一 session-store 查询、provider page/search 兼容读取、DTO 投影 |
| `session-module/.../TaskQueryProviderRegistry.java` | 39 | Provider 列表、按 providerType/taskId 查找、capability-aware fan-out |
| `addons/claude-worker-agent/.../ClaudeTaskService.java` | 2869 | Claude 任务生命周期、认证、权限、回退、重连、同步、统一投影 |
| `addons/codex-worker-agent/.../CodexTaskService.java` | 1373 | Codex/Codex Biz 任务生命周期、会话恢复、查询分页、统一投影 |
| `addons/gemini-worker-agent/.../GeminiTaskService.java` | 660 | Gemini 任务生命周期、会话恢复、认证、统一投影 |
| `addons/langgraph-biz-worker/.../LanggraphTaskService.java` | 796 | LangGraph Biz 任务生命周期、业务审批、Worker Session 查询、统一投影 |

## Session Module

### `TaskDispatchFacade`

| Responsibility group | Representative methods | Current observation |
| --- | --- | --- |
| Front-door task creation | `createTask`、`submitTask`、`submitTaskDispatch`、`toTaskDispatchRequest`、`buildMessage`、`toA2aTask` | 同时承接 REST、OpenAPI/A2A submit 和 Direct Provider Route，入口兼容逻辑集中。 |
| Provider route resolution | `TaskCreateTargetResolver.resolveCreateExecutionTarget`、`resolveModelConfigIdFromDirectory`、`resolveBoundOrExplicitAgentId` | Stage 2.2 后 create 目标推导已迁入 `TaskCreateTargetResolver`；Stage 2.5 后 resume provider 推导已迁入 `TaskOperationRouter`。 |
| Provider/backend compatibility | `TaskOperationRouter.validateRequestedProviderTypeCompatibility`、`validateModelConfigProviderCompatibility`、`TaskCreateTargetResolver.resolveProviderTypeFromModelConfig` | Stage 1 后核心映射委托 `ProviderRouteRegistry`；create 推导由 `TaskCreateTargetResolver` 负责，Direct/Resume 操作兼容校验由 `TaskOperationRouter` 负责。 |
| Task operation dispatch | `cancelTask`、`respondToTask`、`reconnectTask`、`resyncTask`、`rewindTask`、`resumeTask`、`deleteTask`、`scanCheckpoints` | Stage 2.5 后 Facade 公开方法仅委托 `TaskOperationRouter`，Provider 定位、终态 no-op、resume normalize 和 delete cleanup 已迁出。 |
| Provider lookup | `TaskQueryProviderRegistry.findByType`、`findForTask`、`providersSupporting`、`TaskOperationRouter.findProviderForTask` | Stage 2.1 后 provider 列表、按 providerType 查找和按 taskId 查找归属已集中到 registry；Stage 2.5 后操作级 Provider 查找由 Router 封装。 |
| Unified query/search | `getTask`、`listTasksBySession`、`listActiveTasks`、`listTasksPaged`、`searchSessions`、`listTasksByDirectory*` | 优先走统一 `SessionTaskRepository`；`getTask` 已委托 Router，session-store 分页/搜索和投影委托 `UnifiedSessionTaskProjectionService`。 |
| Worker session passthrough | `listWorkerSessions`、`getWorkerSessionMessageCount`、`getWorkerSessionMessages`、`syncWorkerSessions` | Facade 仍作为 worker session 查询代理；Stage 2.4 后只优先遍历声明 worker session capability 的 Provider。 |
| DTO/projection adaptation | `toDispatchDTO`、`toDispatchTaskDTO*`、`buildTaskPageResponse`、`toCompactTaskItem` | A2A DTO 编排仍在 Facade；统一 session task、compact item、search result 和 provider envelope 兼容读取已委托 projection service。 |
| Session-store fallback search | `UnifiedSessionTaskProjectionService.listTasksPagedFromSessionStore`、`searchSessionsFromSessionStore`、`buildUnifiedSessionViews` | Stage 2.3 后统一会话视图聚合和筛选逻辑已迁出 Facade。 |
| JSON/reflection helpers | `parseJsonObject`、`writeJson`、`UnifiedSessionTaskProjectionService.readProperty`、`read*Property` | Facade 保留创建请求诊断状态 JSON 读写；provider page/search DTO 反射兼容读取迁入 projection service，类型安全风险仍存在。 |
| Diagnostic state persistence | `persistTaskRequestFields`、`hasDiagnosticMetadata`、`copyDiagnosticMetadata`、`diagnosticMetadataKeys` | 创建请求元数据回填到统一任务状态，和 provider state schema 没有显式契约。 |

Refactor direction:

- Stage 1 已用 `ProviderRouteRegistry` 统一 providerType、workerBackend、modelConfig 兼容规则。
- Stage 2.1 已抽 `TaskQueryProviderRegistry`，Stage 2.4 已增加 capability descriptor，Stage 2.5 已抽 `TaskOperationRouter`，集中处理操作语义、resume normalize 和 unsupported fallback。
- Stage 2.2 已抽 `TaskCreateTargetResolver`；后续如继续收敛，可把 provider compatibility 校验抽成更通用的 compatibility helper。
- Stage 2.3 已抽 `UnifiedSessionTaskProjectionService`；后续可减少反射 DTO 兼容，推动 provider 返回 typed envelope。
- 保留 `TaskDispatchFacade` 作为 Controller 入口门面，只编排请求、上下文和结果。

### `UnifiedAgentResolver`

| Responsibility group | Representative methods | Current observation |
| --- | --- | --- |
| Agent card aggregation | `listAgents`、`listByProviderType` | 聚合所有 `A2aAgentProvider`，按 providerType 可过滤。 |
| Agent resolution | `resolveAgent`、`getProviderType` | 支持优先 Provider，再 fallback 遍历 provider。 |
| Preferred provider inference | `resolvePreferredProvider`、`resolveProviderTypeFromModelConfig` | Stage 1 后 modelConfig 到 providerType 映射已复用 `ProviderRouteRegistry`。 |

Refactor direction: Preferred provider 推导已接入 `ProviderRouteRegistry`；后续重点转向 Provider 可用性、能力声明和 fallback 行为的显式化。

### `SessionBindingService`

| Responsibility group | Representative methods | Current observation |
| --- | --- | --- |
| Session-agent binding | `getOrBind` | 处理新绑定、旧会话补 providerType、跨 agent 漂移拒绝。 |
| Read-side validation | `validateBinding` | 只校验 agentId 一致性，不处理 providerType 兼容。 |

Refactor direction: 当前职责边界相对清晰，优先保持稳定；后续只补充 providerType 绑定迁移和审计日志测试。

## SPI Contract

### `TaskQueryProvider`

`TaskQueryProvider` 名称仍偏“查询”，但实际已承担复合 Provider 端口：

- 基础查询：`getTaskById`、`getTaskByIdAndUser`、`listTasksBySession`、`listActiveDispatchTasks`
- 创建/恢复：`createTaskDirect`、`resumeTask`
- 操作：`respondToTask`、`reconnectTask`、`resyncTask`、`rewindTask`、`cancelTask`、`deleteTask`、`scanCheckpoints`
- 列表/搜索：`listTasksPaged`、`searchSessions`、`listTasksByDirectory*`
- Worker Session：`listWorkerSessions`、`getWorkerSessionMessageCount`、`getWorkerSessionMessages`、`syncWorkerSessions`
- 能力描述：`getCapabilities`、`supports`

Risk:

- 许多方法仍为 default throw；Stage 2.4 已通过 capability descriptor 降低 provider fan-out 查询的运行期猜测，Stage 2.5 已把任务操作路由集中到 `TaskOperationRouter`，但 SPI 端口尚未正式拆分。
- 新增 Provider 时容易只实现查询能力，却在统一入口暴露 create/resume/worker session 操作。
- `cancelTask` 标记 deprecated，但 Facade 和部分 provider 仍保留兼容路径。

Refactor direction: capability descriptor 和 `TaskOperationRouter` 已作为兼容性中间层落地；后续建议拆为 `TaskQueryPort`、`TaskLifecyclePort`、`TaskRecoveryPort`、`WorkerSessionPort`。

## Provider TaskService

### Claude

| Responsibility group | Representative methods | Current observation |
| --- | --- | --- |
| Native create/resume | `createTask(CreateTaskForm)`、`resumeTask(ResumeTaskForm)` | 处理 worker 归属、目录解析、session 创建/复用、用户 prompt 持久化、模型与认证解析、事件发布。 |
| Worker lifecycle callbacks | `createTrackedSyncTask`、`recordWorkerProgress`、`completeTask`、`failTask`、`abortTask`、`doAbortWorkerTask`、`doPostAbort` | 同一 service 处理 worker 回调、状态机转换、远端中止和本地补偿。 |
| Permission flow | `setAwaitingPermission`、`resumeFromPermission`、`publishConfirmationResponse`、`respondToTask` | 权限确认、前端响应、worker permission API、session message 发布混在任务服务内。 |
| Checkpoint / rewind | `scanAndPopulateCheckpoints`、`addCheckpoint`、`rewindTask`、`truncateSessionMessages`、`buildRewindResult` | 文件回退、conversation fork、首轮回退清理 `claudeSessionId` 等复杂逻辑集中。 |
| Resync / reconnect | `reconnectTask`、`resyncTask`、`resync`、`detectCliStatus`、`syncMessagesFromWorker`、`computeMissing`、`importMessages` | 同时检测 CLI 状态、补消息、完成任务、发布错误。 |
| Auth/model/provider config | `resolveAuth`、`resolveEffectiveModelConfigId`、`resolveEffectiveModel`、`resolveEnvVars`、`buildWorkerProviderConfig`、`bindAuthToSession` | 会话绑定认证、模型配置 fallback、Agent Teams 锁定都在任务服务中。 |
| Projection/state | `persistTask`、`syncSessionTask`、`syncSessionProjection`、`buildClaudeTaskStateJson`、`updateSessionInteractionState`、`clearClaudeSessionId` | 私有 task 表与统一 session/task 投影同步仍强耦合；provider/task state JSON 写入已切到 `ProviderStateCodec`。 |
| TaskQueryProvider adapter | `createTaskDirect`、`resumeTask(Map)`、`getTaskById*`、`listTasks*`、`searchSessions`、`listWorkerSessions*` | SPI 适配层位于大 service 尾部，直接复用内部方法。 |

First split candidates:

- `ClaudeAuthResolver`
- `ClaudeTaskLifecycleService`
- `ClaudeSessionProjectionService`
- `ClaudePermissionService`
- `ClaudeRewindService`
- `ClaudeResyncService`
- `ClaudeTaskProviderAdapter`

### Codex / Codex Biz

| Responsibility group | Representative methods | Current observation |
| --- | --- | --- |
| Create/resume | `createTask`、`createTaskDirect`、`resumeTask`、`createAndStartTask` | Direct 参数转 Form、平台 session 复用、thread 恢复与新 thread fallback 集中。 |
| Worker lifecycle | `createTrackedSyncTask`、`recordWorkerProgress`、`completeTask`、`failTask`、`abortTask`、`doAbortWorkerTask`、`updateCodexThreadId` | Worker 回调与统一投影同步在同一 service 中完成。 |
| Provider variants | `getProviderType`、`normalizeProviderType`、`isCodexBizProvider`、`matchesProvider`、`*ForProvider` | 一个 service 同时服务 `codex-worker` 与 `codex-biz-worker`，旁路还有 `CodexBizTaskProvider` 参数规范化。 |
| Query/search/page | `listTasksPaged*`、`listTasksByDirectoryPaged*`、`searchSessions*`、`buildSessionPage`、`toSearchResult` | Provider 私有查询与统一会话搜索格式绑定。 |
| Projection/state | `persistTask`、`syncSessionTask`、`syncSessionProjection`、`buildCodexTaskStateJson`、`resolveTaskContextId` | `codexThreadId`、`contextId`、providerType 等状态已通过 `ProviderStateCodec` 写入/读取，服务边界仍同时承担投影同步。 |
| Recovery | `resyncTask`、`rewindTask` | 恢复能力较 Claude 简化，但仍通过同一 SPI 暴露。 |

First split candidates:

- `CodexProviderVariantResolver`
- `CodexTaskLifecycleService`
- `CodexSessionProjectionService`
- `CodexTaskQueryService`
- `CodexRecoveryService`

### Gemini

| Responsibility group | Representative methods | Current observation |
| --- | --- | --- |
| Create/resume | `createTask`、`createTaskDirect`、`resumeTask`、`createAndStartTask` | 结构比 Claude/Codex 更小，但同样包含 Direct 参数适配、session 复用和 worker 事件发布。 |
| Platform session | `resolveSessionId`、`createPlatformSession` | 会话创建、用户 prompt 持久化和 logical agent 解析在 task service 内。 |
| Worker lifecycle | `createTrackedSyncTask`、`recordWorkerProgress`、`completeTask`、`failTask`、`abortTask`、`doAbortWorkerTask` | Worker 回调、远端任务 ID、Gemini session ID 同步。 |
| Query/provider port | `getProviderType`、`getTaskById*`、`listTasksBySession`、`listActiveDispatchTasks`、`cancelTask`、`deleteTask` | TaskQueryProvider 端口直接落在 service 上。 |
| Projection/state/auth | `syncSessionProjection`、`syncSessionEntityProjection`、`buildGeminiTaskStateJson`、`resolveGeminiAuth`、`resolveEffectiveModel*` | provider/task state JSON 已迁移到 `ProviderStateCodec`；auth/model 解析仍在 task service 内。 |

First split candidates:

- `GeminiTaskLifecycleService`
- `GeminiAuthResolver`
- `GeminiSessionProjectionService`

### LangGraph Biz

| Responsibility group | Representative methods | Current observation |
| --- | --- | --- |
| Provider query/direct route | `getTaskById*`、`listTasksBySession`、`listActiveDispatchTasks`、`createTaskDirect` | 作为 TaskQueryProvider 直接处理统一入口。 |
| Worker session endpoints | `listWorkerSessions`、`getWorkerSessionMessageCount`、`getWorkerSessionMessages`、`syncWorkerSessions` | Worker session 查询能力比其他 provider 更业务化。 |
| Business task creation | `createTask`、`resolveCompatibleWorkerId`、`buildProviderContext`、`resolveSkillName`、`recentConversation` | 创建任务时融合 skill/function、对话历史和业务上下文。 |
| Lifecycle/interruption | `startTask`、`completeTask`、`failTask`、`cancelTask`、`recordTaskInterruption*` | 业务任务状态、recoverable interruption、统一投影状态一起维护。 |
| Approval flow | `createApprovalRecord`、`approveTask` | 业务审批与 worker resume 调用位于同一 service。 |
| Projection/state | `syncSessionTask`、`syncSessionProjection`、`buildTaskStateJson`、`resolveAgentId` | context/structured output 等统一 task JSON 写入已迁移到 `ProviderStateCodec`；worker session endpoints 仍在 service 内。 |

First split candidates:

- `LanggraphTaskLifecycleService`
- `LanggraphApprovalService`
- `LanggraphWorkerSessionService`
- `LanggraphSessionProjectionService`
- `LanggraphProviderContextBuilder`

## Provider AgentProvider Duplication

Claude、Codex、Gemini、LangGraph 的 `*WorkerAgentProvider` 都重复实现了以下逻辑：

- `listAgentCards` / `resolveAgent`
- `isManagedAgent`
- `resolveProviderType`
- `mapWorkerBackendToProviderType`
- `toA2aAgent` / `toAgentCard`
- user / tenant context fallback

Current status / risk:

- providerType 与 worker backend 的核心映射已收敛到 `ProviderRouteRegistry`，并被 `TaskDispatchFacade`、`UnifiedAgentResolver`、四个 provider adapter、session、配置服务、业务接入和 OpenAPI 诊断链路复用。
- Agent Card 构造、tenant context fallback、provider-specific 默认 cwd/context 参数仍在各 adapter 中保留局部重复。
- Claude/Codex/Gemini/LangGraph 对 tenant context 的支持方式不完全一致，新增 provider 时容易复制旧分支。

Refactor direction:

- `ProviderRouteRegistry` 已集中 workerBackend 映射；后续如继续减少 adapter 重复，可提供 `CodingAgentProviderSupport` 集中处理 CodingAgentEntity 过滤和 card 构造公共字段。
- Provider adapter 只保留 provider-specific agent 构造和默认 cwd/context 参数。

## Stage 1 / Stage 2 / Stage 3 Execution Inputs

建议按以下顺序进入实现：

1. [x] 新增 provider route/capability 单一事实来源，先覆盖 `OPENAI_CODEX`、`CLAUDE_CODE`、`GEMINI_CLI`、`LANGGRAPH_BIZ`。
2. [x] 将 `TaskDispatchFacade` 中的 provider route/compat 方法迁出，保持公开方法签名不变。
3. [x] 先抽 `TaskQueryProviderRegistry`，降低 Facade 中 Provider 查找职责的重复。
4. [x] 为 `TaskQueryProvider` 增加能力描述，先让 Facade 的 provider fan-out 查询不再完全依赖 default throw 判断能力；端口拆分仍作为后续方向。
5. [x] 抽 `TaskOperationRouter`，集中 Facade 中任务操作和 resume 规范化路由。
6. [ ] 选择 Claude 作为最大 service 的拆分样板，只做内部类/服务拆分，不改变 REST/SPI payload。
7. [x] 对 `providerStateJson` / `taskStateJson` 定义 provider codec，先覆盖 `claudeSessionId`、`codexThreadId`、`geminiSessionId`、LangGraph task context/state。Stage 3.1 已新增共享 `ProviderStateCodec`；Stage 3.2 已完成 Claude/Codex/Gemini provider session state 读写迁移；Stage 3.3 已完成 Claude/Codex/Gemini/LangGraph `taskStateJson` 写入迁移。LangGraph worker session endpoints 拆分仍是后续项，不阻塞 codec 基线。

## Non-Code Status

本清单仅记录职责边界与实现切入点，未移动 Java 代码。后续任一拆分都必须先保持 Stage 0 测试基线通过，再按 OPT-001 执行 progress/test/evidence 回写。
