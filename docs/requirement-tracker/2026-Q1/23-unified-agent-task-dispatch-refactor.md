# 统一 Agent 任务分发、统一事件、统一消息流重构方案

> **实施状态：后端全部完成 + 前端 createTask 已迁移（2026-03-24）**
>
> 已完成：
> - ✅ SessionEntity 会话绑定（providerType / bindingSource）
> - ✅ AgentResolveContext + A2aAgentProvider SPI 扩展
> - ✅ UnifiedAgentResolver + SessionBindingService
> - ✅ TaskDispatchFacade + TaskQueryProvider SPI（含 modelConfigId→agentId 自动推导）
> - ✅ 统一 TaskController @ `/api/v1/tasks`
> - ✅ OpenApiController 迁移到 AgentResolveContext
> - ✅ Codex SessionEntity 创建 bug 修复
> - ✅ AgentMessageBuilder 消息标准化工具
> - ✅ WorkerStreamRelay + CodexStreamRelay 全面改用 AgentMessageBuilder
> - ✅ 旧路由逻辑 `@Deprecated` 标记
> - ✅ 前端 `unifiedTask.ts` API 模块 + `useClaudeWorker.createTask()` 迁移到 `/api/v1/tasks`
>
> 待完成：
> - ⬜ 前端其余操作（resume/abort/respond/rewind）逐步迁移
> - ⬜ Codex A2A 异步化（需确认 Worker SSE 支持）
> - ⬜ 删除旧 `isCodexBackend()` 路由

## 需求概述

当前平台已经同时接入 `claude-worker-agent` 和 `codex-worker-agent`，后续还会继续增加更多 Agent 类型。现阶段普通前端任务、OpenAPI 调用、A2A 调用走的是不同入口，路由决策也分散在不同 Controller 和 Provider 中，已经不适合继续横向扩展。

本次重构的目标是建立一套统一的任务分发层：

- 外部入口统一走任务分发 Facade
- 内部统一通过 `A2aAgentProvider` 解析 `A2aAgent`
- 会话级固定 Agent 路由，不再按每次请求的模型配置临时切后端
- 前端统一接收任务状态和会话消息流，不感知 Claude/Codex/未来 Agent 的实现差异

---

## 背景与现状

### 1. 普通前端任务当前是 Controller 层临时分流

当前普通前端创建编程任务的入口仍然在：

- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/ClaudeTaskController.java`

其中 `createTask()` 会在 Controller 层根据 `modelConfigId -> workerBackend` 判断是否转发到 Codex：

- `createTask()`：line 52
- `isCodexBackend()`：line 67

这意味着：

- 路由是“按本次请求选中的模型配置”决定的
- 会话一旦建立后，前端如果切换配置，有机会把后续消息发到另一个后端
- Claude/Codex 的选择逻辑没有沉淀到统一会话路由层

### 2. A2A 路径与普通前端任务路径是两套体系

当前 A2A 统一发现与调用已经存在：

- `session-module/src/main/java/com/foggy/navigator/session/registry/DefaultA2aAgentRegistry.java`
- `session-module/src/main/java/com/foggy/navigator/session/controller/AgentDiscoveryController.java`
- `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/A2aAgentProvider.java`
- `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/A2aAgent.java`

但普通前端的 Claude/Codex 编程任务并没有走这条链路，而是直接走各自的 TaskService / WorkerFacade。

### 3. OpenAPI 仍然直接依赖 Claude Provider

当前 OpenAPI Agent 相关入口在：

- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java`

它直接注入并调用：

- `ClaudeWorkerAgentProvider`

代码位置可见：

- `agentProvider` 字段：line 48
- `listAgents()`：line 370
- `askAgent()`：line 396

这意味着 OpenAPI 并没有通过统一 Registry/Resolver 解析 Agent，后续新增非 Claude Agent 时还会继续复制这类入口。

### 4. 事件推送已经部分统一，但“任务创建和任务查询”还没有统一

当前 Worker 事件进入前端的主链路其实已经比较统一：

- Claude/Codex Worker 侧 SSE 由各自 Relay 消费
- Relay 发布 `AgentMessage`
- `session-module/src/main/java/com/foggy/navigator/session/event/SessionEventListener.java` 持久化消息并转发
- `session-module/src/main/java/com/foggy/navigator/session/sse/UnifiedSseEmitter.java` 通过 `/api/v1/sse/unified` 推给前端
- `session-module/src/main/java/com/foggy/navigator/session/sse/TaskUpdateNotifier.java` 监听 `TaskStatusChangeEvent` / `TaskCompletionEvent` 推送 `task_update`

也就是说：

- 消息下行链路已经基本统一
- 但任务创建、任务路由、任务查询、任务取消仍然是多套入口

### 5. 现有 `agent_tasks` 更偏“委派子任务”，不适合作为这次统一分发表的直接替代

平台已有通用任务基础设施：

- `navigator-common/src/main/java/com/foggy/navigator/common/entity/AgentTaskEntity.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/AgentTaskService.java`
- `session-module/src/main/java/com/foggy/navigator/session/controller/AgentTaskController.java`

但这套表结构和语义当前主要服务于：

- Tutor/Delegate 产生的跨 Agent 子任务
- `TASK_COMPLETED` 型通知
- `/api/v1/agent-tasks` 看板

它的字段是：

- `parentSessionId`
- `sourceAgentId`
- `targetAgentId`
- `taskType`
- `externalTaskId`

这与“用户直接发起的 Claude/Codex 编程任务”并不完全等价。如果直接把所有用户任务硬塞进 `agent_tasks`，会把现有“委派子任务”语义和“用户主任务”语义混在一起。

### 6. A2A 实现目前也不一致，不能直接当统一前台任务入口

当前两个 A2A 适配器行为并不一致：

- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/adapter/ClaudeWorkerA2aAgent.java`
  - `sendTask()` 是异步 tracked task，返回平台任务 ID，比较接近可统一的方向
- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/adapter/CodexWorkerA2aAgent.java`
  - `sendTask()` 仍然走 `syncQuery()`，并返回随机 UUID，不是稳定任务 ID

这说明：

- A2A SPI 是对的
- 但各个 Agent 适配器还没有收敛到统一的“异步任务句柄 + 标准事件流”语义

---

## 当前问题清单

### 1. 会话后端不稳定

当前普通前端的路由依据是 `modelConfigId`，而不是“session 已绑定的 agent”。这不满足“同一会话固定由同一个 Agent 继续处理”的需求。

### 2. 新增 Agent 的扩展成本偏高

如果继续沿用现在的做法，后续每新增一个 Agent，至少要考虑：

- 普通前端入口怎么分流
- OpenAPI 怎么单独接
- 任务控制接口怎么分支
- SSE/状态/查询怎么接入

这会继续把路由逻辑散落在多个模块。

### 3. 外部接口语义不统一

当前至少存在三类入口：

- 普通前端编程任务入口
- A2A `/api/v1/agents/{agentId}/ask`
- OpenAPI Agent 入口

它们对于任务 ID、任务状态、会话续接、查询取消的定义都不完全一致。

### 4. Controller 暴露了不该暴露的后端细节

当前 Controller 能感知：

- Claude / Codex 的具体分流规则
- Provider 的具体实现类
- 某些 Worker 的专属调用路径

这与“外部只知道发任务给某个 Agent，不关心内部 A2A 或 Worker 实现”的目标相违背。

### 5. 统一消息流没有统一“标准化入口”

虽然最终都是走 `UnifiedSseEmitter`，但不同 Worker Relay 仍然可以构造不同风格的 payload。后续 Agent 越多，这里的消息歧义会越明显。

---

## 目标

### 一、目标

1. 建立统一任务分发入口，普通前端、OpenAPI、A2A 最终都经过同一套分发服务。
2. 分发服务内部统一通过 `A2aAgentProvider -> A2aAgent` 解析和调用目标 Agent。
3. 会话在首次发起任务时绑定 Agent，后续同会话不得跨 Agent 漂移。
4. 前端统一消费：
   - 统一任务模型
   - 统一任务状态流
   - 统一会话消息流
5. 新增一个 Agent 时，不再修改现有 Controller 的分流逻辑。
6. 不对外暴露 `A2aAgent` 内部处理细节，Controller 只依赖 Facade。

### 二、非目标

1. 一期不重写 `claude-agent-worker` / `codex-agent-worker` 的上游协议。
2. 一期不强行删除现有 `/api/v1/claude-tasks/**`、`/api/v1/open/agents/**` 等旧接口，先做兼容适配。
3. 一期不要求所有 Agent 都提供完全相同的高级能力，例如 rewind / resync / permission flow；高级能力先通过 capability 扩展。
4. 一期不直接把 `agent_tasks` 改造成用户主任务表，避免破坏现有委派任务语义。

---

## 目标架构

```text
┌────────────────────────────────────────────────────────────┐
│ External Entrypoints                                      │
│  - Frontend Task API                                      │
│  - OpenAPI Agent API                                      │
│  - A2A Ask API                                            │
└──────────────────────┬─────────────────────────────────────┘
                       │
                       ▼
┌────────────────────────────────────────────────────────────┐
│ TaskDispatchFacade                                         │
│  - createTask                                              │
│  - getTask / listTasks                                     │
│  - cancel / reply / resync                                 │
└───────────────┬───────────────────────┬────────────────────┘
                │                       │
                ▼                       ▼
┌───────────────────────────┐   ┌───────────────────────────┐
│ SessionAgentRouteService  │   │ UnifiedAgentResolver      │
│  - getOrBindRoute         │   │ 通过 A2aAgentProvider     │
│  - validateBoundAgent     │   │ 解析 A2aAgent             │
└───────────────┬───────────┘   └───────────────┬───────────┘
                │                               │
                ▼                               ▼
┌────────────────────────────────────────────────────────────┐
│ A2aAgent                                                   │
│  - sendTask (返回平台 task handle)                         │
│  - getTask                                                 │
│  - cancelTask                                              │
└──────────────────────┬─────────────────────────────────────┘
                       │
                       ▼
┌────────────────────────────────────────────────────────────┐
│ Backend-specific Task Service / Worker Relay              │
│  ClaudeTaskService / WorkerStreamRelay                    │
│  CodexTaskService  / CodexStreamRelay                     │
│  Future Agent ...                                         │
└──────────────────────┬─────────────────────────────────────┘
                       │
                       ▼
┌────────────────────────────────────────────────────────────┐
│ DispatchEventPublisher                                     │
│  - AgentMessage                                            │
│  - TaskStatusChangeEvent / TaskCompletionEvent             │
└──────────────────────┬─────────────────────────────────────┘
                       │
                       ▼
┌────────────────────────────────────────────────────────────┐
│ SessionEventListener / TaskUpdateNotifier / Unified SSE    │
└────────────────────────────────────────────────────────────┘
```

核心原则只有一句话：

> 用户选择的是 Agent，会话绑定的是 Agent；模型配置只是该 Agent 的执行参数，不再是后端路由器。

---

## 核心设计

### 1. 统一 Agent 解析层

新增：

- `UnifiedAgentResolver`

职责：

- 对普通前端按用户维度解析 Agent
- 对 OpenAPI 按租户维度解析 Agent
- 统一封装 Registry / Provider 的调用细节

### 建议引入 `AgentResolveContext`

建议不要再继续增加 `resolveAgentByTenant()` 这类平铺方法，而是引入上下文对象：

```java
public class AgentResolveContext {
    private String userId;
    private String tenantId;
    private String sessionId;
    private String requestSource; // UI / OPEN_API / A2A / SYSTEM
}
```

然后扩展 `A2aAgentProvider`：

```java
public interface A2aAgentProvider {

    List<A2aAgentCard> listAgentCards(String userId);

    Optional<A2aAgent> resolveAgent(String agentId, String userId);

    default List<A2aAgentCard> listAgentCards(AgentResolveContext context) { ... }

    default Optional<A2aAgent> resolveAgent(String agentId, AgentResolveContext context) { ... }

    String getProviderType();
}
```

这样做的原因：

- 对现有 Provider 改动可控，保留兼容
- 后续如果出现系统任务、租户任务、后台恢复任务，不会继续膨胀出更多重载方法
- `UnifiedAgentResolver` 可以成为唯一对外解析入口

### 对外约束

Controller 不再直接依赖：

- `ClaudeWorkerAgentProvider`
- `CodexWorkerAgentProvider`
- `ClaudeWorkerFacade`
- `CodexWorkerFacade`

Controller 只依赖：

- `TaskDispatchFacade`
- `UnifiedAgentResolver`

---

### 2. 会话路由绑定层

新增表：

- `session_agent_routes`

建议字段：

| 字段 | 说明 |
|------|------|
| `sessionId` | 平台会话 ID，唯一 |
| `userId` | 用户 ID |
| `tenantId` | 租户 ID，可空 |
| `agentId` | 绑定的 Agent ID |
| `providerType` | `claude-worker` / `codex-worker` / future |
| `backendType` | 更细粒度后端分类，可选 |
| `workerId` | 关联 worker 实例，可空 |
| `directoryId` | 默认目录，可空 |
| `bindingSource` | `EXPLICIT_AGENT` / `LEGACY_MODEL_CONFIG` / `RESTORED` |
| `routeStatus` | `ACTIVE` / `ARCHIVED` / `BROKEN` |
| `lockedAt` | 绑定时间 |
| `createdAt` / `updatedAt` | 审计字段 |

### 绑定规则

1. 新会话首次发任务时确定 Agent。
2. 如果请求显式传了 `agentId`，优先使用该 Agent 并写入 route。
3. 如果是旧前端仍只传 `modelConfigId`，允许通过兼容映射推导默认 Agent，再写入 route。
4. 同一 `sessionId` 已存在 route 时，后续请求必须命中同一个 Agent。
5. 若新请求会把会话切到另一个 Agent，直接拒绝并返回 `SESSION_AGENT_BOUND_MISMATCH`。

### 对前端语义的影响

这意味着：

- 用户在一个会话里从 Claude 切到 Codex，不再是“改一个下拉框就继续”
- 正确行为应该是：
  - 新建会话
  - 或 fork 当前会话

这正是此次重构需要显式建立的产品语义。

---

### 3. 统一任务外壳

建议新增统一主任务表，而不是直接复用 `agent_tasks`：

- `dispatch_tasks`

原因：

- `agent_tasks` 当前是“委派子任务”表
- `dispatch_tasks` 是“用户主任务 / OpenAPI 主任务 / A2A 主任务”的统一外壳
- 两者未来可以再讨论是否合并，但本次重构不建议强行混表

### 建议字段

| 字段 | 说明 |
|------|------|
| `taskId` | 平台统一任务 ID |
| `sessionId` | 所属会话 |
| `userId` | 用户 ID |
| `tenantId` | 租户 ID |
| `agentId` | 目标 Agent |
| `providerType` | Agent Provider 类型 |
| `backendType` | 后端类型 |
| `status` | 统一状态 |
| `interactionState` | 统一交互状态 |
| `taskKind` | `CODING` / `ASK` / `DELEGATION` / future |
| `prompt` | 任务输入摘要 |
| `resultText` | 最终结果摘要或正文 |
| `errorMessage` | 错误信息 |
| `workerId` | 关联 Worker，可空 |
| `directoryId` | 目录，可空 |
| `modelConfigId` | 触发任务时的模型配置 |
| `upstreamTaskId` | 上游任务 ID |
| `upstreamSessionId` | 上游会话 ID |
| `inputTokens` / `outputTokens` | token 统计 |
| `costUsd` | 成本 |
| `durationMs` | 耗时 |
| `numTurns` | turn 数 |
| `capabilities` | 当前任务可执行动作 |
| `createdAt` / `updatedAt` / `completedAt` | 审计字段 |

### ID 策略

建议平台层预先生成 `taskId`，并要求 Claude/Codex 等后端任务创建接口接收这个 `taskId`，后端明细表继续使用相同主键。

也就是说：

- 前端永远只认平台 `taskId`
- Claude/Codex 明细表不再额外生成一套“平台不可见 taskId”
- 真正的上游 Worker/CLI 任务号继续放在 `workerTaskId` / `upstreamTaskId`

这样能避免：

- 平台 taskId 与后端 taskId 双向映射
- Controller/Facade 还要知道“先找 dispatch 再找 claude/codex 详情”

### 统一状态建议

平台统一状态建议收敛为：

- `PENDING`
- `RUNNING`
- `AWAITING_INPUT`
- `COMPLETED`
- `FAILED`
- `ABORTED`
- `STALLED`

后端内部状态仍可保留，但对外必须映射到这套状态。

---

### 4. 统一任务分发 Facade

新增：

- `TaskDispatchFacade`

建议方法：

```java
DispatchTaskDTO createTask(TaskDispatchRequest request, AgentResolveContext context);

DispatchTaskDTO getTask(String taskId, AgentResolveContext context);

List<DispatchTaskDTO> listTasks(String sessionId, AgentResolveContext context);

DispatchTaskDTO cancelTask(String taskId, AgentResolveContext context);

DispatchTaskDTO replyTask(String taskId, TaskReplyRequest request, AgentResolveContext context);

DispatchTaskDTO resyncTask(String taskId, AgentResolveContext context);
```

### createTask 主流程

1. 根据 `sessionId` 查询 `session_agent_routes`
2. 如不存在 route，则根据 `agentId` 或兼容 `modelConfigId` 绑定 route
3. 用 `UnifiedAgentResolver` 获取 `A2aAgent`
4. 预创建 `dispatch_tasks` 记录，生成平台 `taskId`
5. 构造标准 `A2aMessage`，把 `taskId`、`sessionId`、`modelConfigId`、`cwd` 等放入 metadata
6. 调用 `A2aAgent.sendTask()`
7. 返回统一 `DispatchTaskDTO`

### 关键约束

Facade 内部可以拿到 `A2aAgent`，但 Controller 不允许拿到。

也就是说：

- Controller 不知道具体是 Claude 还是 Codex
- Controller 不知道 Worker 是同步、异步还是 SSE
- Controller 只拿统一任务 DTO

---

### 5. A2A 适配器统一改造

这是本次设计里非常关键的一点。

如果要让普通前端、OpenAPI、A2A 最终收敛到同一套任务分发，那么 `A2aAgent` 的真实语义必须统一为：

> `sendTask()` 返回“平台任务句柄”，真实执行在后台继续进行，事件和状态通过统一事件总线往外发。

### 需要统一的行为

所有用户可见的 Agent 适配器都应满足：

1. `sendTask()` 返回稳定的、可查询的任务 ID
2. `getTask(taskId)` 能查询该任务当前状态
3. `cancelTask(taskId)` 取消的是平台任务，不是临时随机 UUID
4. 流式消息通过现有 `AgentMessage` 链路进入 `UnifiedSseEmitter`
5. 最终状态通过 `TaskStatusChangeEvent` / `TaskCompletionEvent` 进入 `task_update`

### 对现有实现的影响

#### Claude A2A

`ClaudeWorkerA2aAgent` 已经比较接近目标方向，但仍建议进一步收敛：

- 不再额外维持一套“仅 A2A 使用”的执行路径
- 尽量复用普通前端同一套 `createTask / relay / event publish` 逻辑

#### Codex A2A

`CodexWorkerA2aAgent` 当前仍然是 `syncQuery()`，需要改成：

- 创建 tracked task
- 返回平台 `taskId`
- 由后台流式执行
- 由 Codex relay 发布统一消息和状态事件

否则它无法真正成为统一分发层的底层执行器。

---

### 6. 统一事件与统一消息流

现有基础设施整体不需要推倒重来，但需要增加一个标准化层：

- `DispatchEventPublisher`

职责：

- 屏蔽不同 Worker/Agent 对 `AgentMessage` payload 的差异
- 强制所有 Agent 按统一规则发布消息和状态

### 建议沿用现有 `MessageType`

当前已有的 `agent-framework/.../MessageType.java` 已经基本够用，建议继续沿用：

- `SESSION_START`
- `TEXT_CHUNK`
- `TEXT_COMPLETE`
- `TOOL_CALL_START`
- `TOOL_CALL_RESULT`
- `CONFIRMATION_REQUEST`
- `CONFIRMATION_RESPONSE`
- `STATE_SYNC`
- `ERROR`
- `CHECKPOINT`

### 统一消息 Envelope

建议所有 `AgentMessage.payload` 至少带以下基础字段：

| 字段 | 说明 |
|------|------|
| `taskId` | 平台任务 ID |
| `sessionId` | 平台会话 ID |
| `agentId` | 目标 Agent |
| `providerType` | Provider 类型 |
| `backendType` | 后端类型 |
| `timestamp` | 事件时间 |
| `seq` | 可选，流式事件序号 |

### 标准链路

统一后的链路应为：

1. Agent/Worker 产生原始事件
2. Relay/Service 调用 `DispatchEventPublisher` 标准化
3. 发布 `AgentMessage`
4. `SessionEventListener` 落库到 session message
5. `UnifiedSseEmitter.sendSessionEvent()` 推 `session_event`
6. 同步发布 `TaskStatusChangeEvent` / `TaskCompletionEvent`
7. `TaskUpdateNotifier` 推 `task_update`

这条链路里，前端不再需要知道事件来自 Claude 还是 Codex。

---

## 7. 对外 API 统一方案

### 一、统一任务 API

建议新增统一任务入口：

- `POST /api/v1/tasks`
- `GET /api/v1/tasks/{taskId}`
- `GET /api/v1/tasks?sessionId=...`
- `POST /api/v1/tasks/{taskId}/cancel`
- `POST /api/v1/tasks/{taskId}/reply`
- `POST /api/v1/tasks/{taskId}/resync`

说明：

- `cancel` / `reply` / `resync` 是一期建议收敛的公共动作
- 其余高级动作先通过 capability 扩展，不在一期强推统一

### 二、统一发现 API

继续保留：

- `GET /api/v1/agents`
- `GET /api/v1/agents/{agentId}/card`

但 `POST /api/v1/agents/{agentId}/ask` 应逐步降级为统一任务 API 的兼容别名。

也就是说：

- 发现类接口保留 Agent 视角
- 执行类接口逐步统一到 Task 视角

### 三、统一 SSE

继续保留现有：

- `GET /api/v1/sse/unified`

前端只消费两类事件：

- `session_event`
- `task_update`

任务面板看 `task_update`，会话内容看 `session_event`。

---

## 8. 与模型配置的关系

这个部分是此次评审必须明确的产品语义。

### 推荐规则

1. 新会话首次创建任务时，允许用 `modelConfigId` 推导默认 Agent。
2. 一旦 `session_agent_routes` 已绑定，后续请求的 `modelConfigId` 只能影响该 Agent 内部的模型/鉴权参数。
3. 如果新传入的 `modelConfigId` 会把会话切到另一个 Provider 或另一个 Agent，直接拒绝。
4. 用户如果想从 Claude 会话切到 Codex，会话层面必须：
   - 新建会话
   - 或 fork 会话

### 原因

如果不建立这个规则：

- 会话历史和后端执行者会脱钩
- 断线重连、任务恢复、权限回复、任务取消都可能命中错误后端
- 后续多 Agent 扩展会持续恶化

---

## 9. 迁移方案

建议按四个阶段推进，避免一次性爆炸式重构。

### 阶段 1：建立统一分发骨架

目标：

- 引入 `TaskDispatchFacade`
- 引入 `UnifiedAgentResolver`
- 引入 `session_agent_routes`
- 旧 Controller 内部改为调用 Facade

涉及：

- `ClaudeTaskController`
- `OpenApiController`
- `AgentDiscoveryController`

此阶段前端可以不改，只要旧接口内部已经开始走统一分发。

### 阶段 2：建立统一主任务表

目标：

- 引入 `dispatch_tasks`
- 平台预生成统一 `taskId`
- Claude/Codex 创建任务时接受平台 `taskId`

此阶段完成后，前端可以开始改为读取统一任务 DTO，而不再分别读 Claude/Codex 任务 DTO。

### 阶段 3：统一 A2A 执行语义和事件标准化

目标：

- Claude/Codex A2A 适配器都返回稳定平台任务句柄
- 引入 `DispatchEventPublisher`
- 各 Relay / Service 改为通过标准化层发布事件

此阶段完成后：

- 普通前端任务
- OpenAPI 任务
- `/api/v1/agents/{agentId}/ask`

在执行语义上会真正收敛为一条主链。

### 阶段 4：收口旧接口和旧分流逻辑

目标：

- 去掉 `ClaudeTaskController` 内部的 `isCodexBackend()` 路由
- OpenAPI 不再直接依赖 `ClaudeWorkerAgentProvider`
- 逐步废弃按 `modelConfigId` 做后端分流的旧逻辑

---

## 10. 测试与验收建议

### 单元测试

1. `SessionAgentRouteService`
   - 新会话绑定成功
   - 已绑定会话拒绝跨 Agent 漂移
   - 旧 `modelConfigId` 兼容映射正确

2. `UnifiedAgentResolver`
   - user 维度解析
   - tenant 维度解析
   - Provider 不支持对应上下文时的 fallback

3. `TaskDispatchFacade`
   - createTask 路径正确创建 route + task
   - get/cancel/reply/resync 正确落到目标 Agent

### 集成测试

1. 前端普通任务创建 Claude 会话，再尝试切 Codex，应返回 route mismatch
2. 同一任务的 `session_event` 和 `task_update` 都能收到
3. OpenAPI 和普通前端创建的任务都进入 `dispatch_tasks`
4. `A2aAgent.sendTask()` 返回的任务 ID 可以通过统一任务 API 查询

### 回归测试

1. 旧 `/api/v1/claude-tasks/**`、`/api/v1/open/agents/**` 在兼容阶段仍可用
2. 现有 Tutor/Delegate 的 `agent_tasks` 看板不受影响

---

## 11. 风险与待确认项

### 1. 是否允许同会话切换模型配置

建议：

- 允许切换同一 Agent 内部兼容模型
- 不允许切换到另一个 Agent / Provider

这个规则需要产品层和前端层同步确认。

### 2. `dispatch_tasks` 是否与 `agent_tasks` 合并

本方案建议先分开。

原因：

- `agent_tasks` 现有语义是委派子任务
- `dispatch_tasks` 语义是用户主任务

如果后续确认两者的业务边界可以统一，再做第二阶段表收敛。

### 3. A2A 是否需要扩展更多能力接口

一期建议只要求：

- `sendTask`
- `getTask`
- `cancelTask`

`reply/resync/rewind` 先由 `TaskDispatchFacade` 配合 capability 适配，不立即把 SPI 扩到很大。

### 4. 历史会话如何补 route

上线后会存在大量旧会话没有 `session_agent_routes`。

建议补齐策略：

- 优先从最近一次任务详情恢复
- 找不到时按旧 `modelConfigId` 兼容推导
- 仍失败时标记为 `BROKEN`，要求用户新建会话

---

## 12. 评审建议关注点

这份方案评审时建议重点确认以下四个决策：

1. 是否接受“会话绑定 Agent，切换 Claude/Codex 必须新建或 fork 会话”的产品语义。
2. 是否同意新增 `dispatch_tasks`，而不是直接复用 `agent_tasks`。
3. 是否同意把执行入口统一收敛到 `TaskDispatchFacade`，Controller 不再感知具体 Provider/Worker。
4. 是否同意把 `A2aAgent.sendTask()` 统一为“返回平台任务句柄，后台异步执行，事件从统一总线输出”的语义。

如果这四点确认，后续实现路径就是清晰的。
