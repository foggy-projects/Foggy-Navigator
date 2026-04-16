# 上游 Agent 接入时序图与流程图

## 文档作用

- doc_type: sequence-and-flow
- intended_for: external-integration-owner | execution-agent | reviewer
- purpose: 用 Mermaid 图说明 1.0.3-SNAPSHOT 上游接入首版的调用时序、增量消息轮询流程和平台内部 taskId 串联链路

## Version

- `1.0.3-SNAPSHOT`

## Status

- Draft
- 2026-04-15

## 上游输入文档

1. [01-upstream-agent-integration-requirement.md](./01-upstream-agent-integration-requirement.md)
2. [05-upstream-agent-integration-implementation-plan.md](./05-upstream-agent-integration-implementation-plan.md)
3. [06-upstream-agent-integration-api-contract-draft.md](./06-upstream-agent-integration-api-contract-draft.md)

## 1. 上游完整调用时序

该图说明上游系统如何完成首版完整接入：

1. 发起任务
2. 获取 `taskId/contextId`
3. 轮询任务状态
4. 轮询任务进行中的新增消息
5. 任务完成后回放会话消息

```mermaid
sequenceDiagram
    autonumber
    participant Upstream as 上游系统
    participant OpenAPI as Navigator Open API
    participant Task as 任务服务
    participant Session as 会话/消息服务
    participant Worker as Agent Worker

    Upstream->>OpenAPI: POST /api/v1/open/agents/{agentId}/ask
    OpenAPI->>Task: 创建平台任务
    Task->>Session: 创建或复用内部 sessionId
    Task->>Worker: 发送任务请求
    Task-->>OpenAPI: taskId + contextId + SUBMITTED
    OpenAPI-->>Upstream: OpenTask

    loop 任务执行中
        Upstream->>OpenAPI: GET /agents/{agentId}/tasks/{taskId}
        OpenAPI->>Task: 查询任务状态
        Task-->>OpenAPI: RUNNING/AWAITING_INPUT/COMPLETED
        OpenAPI-->>Upstream: OpenTask

        Upstream->>OpenAPI: GET /agents/{agentId}/tasks/{taskId}/messages?cursor={cursor}
        OpenAPI->>Session: 查询该 taskId 新增消息
        Session-->>OpenAPI: messages + nextCursor + hasMore
        OpenAPI-->>Upstream: IncrementalMessages
    end

    Upstream->>OpenAPI: GET /agents/{agentId}/sessions/{contextId}/messages
    OpenAPI->>Session: 查询会话完整消息
    Session-->>OpenAPI: SessionMessages
    OpenAPI-->>Upstream: 会话消息列表
```

## 2. 增量消息轮询流程

该图说明上游如何使用 `cursor` 轮询进行中的消息。

首版语义建议：

- 第一次调用不传 `cursor`
- 服务端返回 `nextCursor`
- 后续调用携带上一次的 `nextCursor`
- 即使本轮没有新消息，也应返回当前任务标识和可继续使用的游标
- 任务终态后，上游可做最后一次消息轮询，再停止

```mermaid
flowchart TD
    A[开始轮询 task messages] --> B{是否已有 cursor}
    B -- 否 --> C[请求 /tasks/taskId/messages<br/>limit=50]
    B -- 是 --> D[请求 /tasks/taskId/messages<br/>cursor=xxx, limit=50]

    C --> E[服务端按 taskId 查询新增消息]
    D --> E

    E --> F[返回 messages + nextCursor + hasMore]
    F --> G{messages 是否为空}

    G -- 否 --> H[上游追加渲染新增消息]
    G -- 是 --> I[上游保持当前消息列表不变]

    H --> J[保存 nextCursor]
    I --> J

    J --> K{hasMore 是否为 true}
    K -- 是 --> D
    K -- 否 --> L[查询任务状态]

    L --> M{任务是否终态}
    M -- 否 --> N[等待轮询间隔]
    N --> D
    M -- 是 --> O[最后一次使用 nextCursor 拉取遗漏消息]
    O --> P[停止轮询]
```

## 3. 平台内部 taskId 串联时序

该图说明平台内部如何把同一任务内持续产生的多条消息串到同一个平台 `taskId` 下。

关键规则：

- 对上游暴露的平台主标识是 `taskId`
- Worker 内部任务 ID 只作为内部追踪或调试字段
- Worker 事件进入平台后，统一转换为带 `taskId` 的 AgentMessage
- 持久化消息时，必须能在对外 DTO 中显式返回 `taskId`

```mermaid
sequenceDiagram
    autonumber
    participant OpenAPI as Open API
    participant TaskService as ClaudeTaskService/TaskDispatch
    participant Worker as Worker
    participant Relay as WorkerStreamRelay
    participant EventBus as Spring Event
    participant Listener as SessionEventListener
    participant MessageStore as SessionMessage Store
    participant Upstream as 上游轮询方

    OpenAPI->>TaskService: createTask()
    TaskService-->>OpenAPI: 平台 taskId + contextId
    TaskService->>Worker: 发起 Worker 任务
    Worker-->>Relay: workerTaskId + assistant_text/tool/result events

    Relay->>Relay: 建立 taskId -> workerTaskId 映射
    Relay->>EventBus: 发布 AgentMessage(internalSessionId, taskId, type, payload)
    EventBus->>Listener: onAgentMessage()
    Listener->>MessageStore: 持久化消息 content + metadata
    MessageStore-->>Listener: messageId

    Upstream->>OpenAPI: GET /tasks/{taskId}/messages?cursor=xxx
    OpenAPI->>MessageStore: 查询 taskId 对应新增消息
    MessageStore-->>OpenAPI: messageId + contextId + taskId + content
    OpenAPI-->>Upstream: messages + nextCursor
```

## 4. 会话回放流程

该图说明任务完成后，上游如何使用 `contextId` 回放会话消息。

```mermaid
flowchart TD
    A[上游持有 contextId] --> B[GET /agents/agentId/sessions/contextId/messages]
    B --> C[Open API 校验 agentId 与访问权限]
    C --> D[按 contextId 定位内部 sessionId 后查询消息]
    D --> E[按时间升序转换为 SessionMessage DTO]
    E --> F[显式填充 messageId/contextId/taskId/role/type/content/metadata/createdAt]
    F --> G[返回 messages + nextCursor + hasMore]
    G --> H{hasMore 是否为 true}
    H -- 是 --> I[上游携带 nextCursor 继续拉取]
    I --> B
    H -- 否 --> J[会话回放完成]
```

## 5. Demo 推荐主流程

Demo 建议覆盖以下主流程：

```mermaid
flowchart LR
    A[选择 Agent] --> B[创建任务 ask]
    B --> C[展示 taskId 和 contextId]
    C --> D[轮询任务状态]
    C --> E[轮询任务新增消息]
    D --> F{任务完成?}
    E --> G[实时追加消息]
    F -- 否 --> D
    F -- 是 --> H[停止状态轮询]
    H --> I[加载会话消息]
    I --> J[展示完整会话回放]
```

## 6. 后续落地注意事项

实现阶段需要特别注意：

1. 图中的 `cursor` 语义需要在接口实现和 SDK 中保持一致
2. 图中的 `taskId` 必须是平台侧主标识
3. 图中的 `contextId` 必须是对外唯一会话主标识
4. 对外 DTO 不应要求上游理解 Worker 内部事件结构
5. 如果最终实现选择 `sinceMessageId` 而不是 `cursor`，需要同步更新本图和 API 合同草案
