# 上游 Agent 接入 API 合同草案

## 文档作用

- doc_type: api-contract-draft
- intended_for: execution-agent | reviewer | external-integration-owner
- purpose: 为 1.0.3-SNAPSHOT 上游接入首版提供一版可执行的 API 合同草案，收口接口范围、对象结构、轮询语义和 taskId 串联规则

## Version

- `1.0.3-SNAPSHOT`

## Status

- Draft
- 2026-04-15

## 上游输入文档

1. [01-upstream-agent-integration-requirement.md](./01-upstream-agent-integration-requirement.md)
2. [02-upstream-agent-integration-current-state-analysis.md](./02-upstream-agent-integration-current-state-analysis.md)
3. [05-upstream-agent-integration-implementation-plan.md](./05-upstream-agent-integration-implementation-plan.md)

## 1. 草案结论

建议首版继续沿用现有对外 Open API 根路径：

- `/api/v1/open`

并在其下补齐会话域与任务增量消息能力。

本草案新增一条强约束：

- 对外只保留 `contextId` 和 `taskId` 两个主标识
- `contextId` 表示一条持续会话上下文
- `taskId` 表示某一次具体任务执行
- 内部 `sessionId` 不进入上游正式合同，也不作为外部查询主键

建议首版正式开放以下接口：

1. `POST /agents/{agentId}/ask`
2. `GET /agents/{agentId}/tasks/{taskId}`
3. `POST /agents/{agentId}/tasks/{taskId}/cancel`
4. `GET /agents/{agentId}/tasks/{taskId}/messages`
5. `GET /agents/{agentId}/sessions`
6. `GET /agents/{agentId}/sessions/{contextId}/messages`

说明：

- 以上路径均相对 `/api/v1/open`
- 会话接口挂在 `agentId` 下，首版优先保证“某上游接入某个 Agent”语义清晰
- 对外会话主键统一使用 `contextId`
- 内部 `sessionId` 不进入上游正式合同
- 首版不引入独立上游 SSE 合同

## 2. 接口列表

### 2.1 发起任务

`POST /agents/{agentId}/ask`

用途：

- 发起一个新的异步任务

请求体建议：

```json
{
  "message": "请帮我分析最近三天的销售异常",
  "contextId": "ctx_xxx_optional",
  "metadata": {
    "bizKey": "order-001"
  }
}
```

响应体建议：

```json
{
  "taskId": "task_xxx",
  "agentId": "claude-worker",
  "contextId": "ctx_xxx",
  "status": "SUBMITTED",
  "createdAt": "2026-04-15T10:00:00"
}
```

### 2.2 查询任务状态

`GET /agents/{agentId}/tasks/{taskId}`

用途：

- 获取任务当前状态和终态结果

响应体建议：

```json
{
  "taskId": "task_xxx",
  "agentId": "claude-worker",
  "contextId": "ctx_xxx",
  "status": "RUNNING",
  "result": null,
  "errorMessage": null,
  "createdAt": "2026-04-15T10:00:00",
  "updatedAt": "2026-04-15T10:00:10"
}
```

状态建议首版统一为：

- `SUBMITTED`
- `RUNNING`
- `AWAITING_INPUT`
- `COMPLETED`
- `FAILED`
- `CANCELLED`

### 2.3 取消任务

`POST /agents/{agentId}/tasks/{taskId}/cancel`

用途：

- 取消正在执行中的任务

响应体建议：

```json
{
  "taskId": "task_xxx",
  "status": "CANCELLED"
}
```

### 2.4 轮询任务进行中的新增消息

`GET /agents/{agentId}/tasks/{taskId}/messages`

用途：

- 拉取某个任务在执行过程中新产生的消息

建议查询参数：

- `cursor`: 可选，服务端返回的增量游标
- `limit`: 可选，默认 `50`

首版不建议同时支持多种增量协议；建议二选一：

1. `cursor`
2. `sinceMessageId`

当前更建议 `cursor`，因为更便于后续服务端演进。

响应体建议：

```json
{
  "taskId": "task_xxx",
  "contextId": "ctx_xxx",
  "messages": [
    {
      "messageId": "msg_001",
      "contextId": "ctx_xxx",
      "taskId": "task_xxx",
      "role": "assistant",
      "type": "TEXT",
      "content": "我先检查一下数据范围。",
      "metadata": {
        "agentId": "claude-worker"
      },
      "createdAt": "2026-04-15T10:00:02"
    },
    {
      "messageId": "msg_002",
      "contextId": "ctx_xxx",
      "taskId": "task_xxx",
      "role": "assistant",
      "type": "TEXT",
      "content": "我发现昨天订单退款率异常升高。",
      "metadata": {
        "agentId": "claude-worker"
      },
      "createdAt": "2026-04-15T10:00:06"
    }
  ],
  "nextCursor": "cur_xxx",
  "hasMore": false
}
```

首版语义建议：

1. 只返回该 `taskId` 对应的消息
2. 返回顺序按消息产生时间升序
3. 同一批次可能返回 0 条、1 条或多条消息
4. 服务端保证同一个 `cursor` 不重复返回已消费消息

### 2.5 会话列表

`GET /agents/{agentId}/sessions`

用途：

- 获取某个 Agent 下、当前调用主体可访问的会话列表

建议查询参数：

- `limit`
- `cursor`

响应体建议：

```json
{
  "sessions": [
    {
      "contextId": "ctx_xxx",
      "agentId": "claude-worker",
      "title": "销售异常分析",
      "status": "ACTIVE",
      "latestTaskId": "task_xxx",
      "createdAt": "2026-04-15T09:59:00",
      "updatedAt": "2026-04-15T10:00:10"
    }
  ],
  "nextCursor": null,
  "hasMore": false
}
```

### 2.6 会话消息列表

`GET /agents/{agentId}/sessions/{contextId}/messages`

用途：

- 获取指定会话上下文下的消息列表

建议查询参数：

- `limit`
- `cursor`

响应体建议：

```json
{
  "contextId": "ctx_xxx",
  "messages": [
    {
      "messageId": "msg_001",
      "contextId": "ctx_xxx",
      "taskId": "task_xxx",
      "role": "user",
      "type": "USER",
      "content": "请帮我分析最近三天的销售异常",
      "metadata": {},
      "createdAt": "2026-04-15T10:00:00"
    },
    {
      "messageId": "msg_002",
      "contextId": "ctx_xxx",
      "taskId": "task_xxx",
      "role": "assistant",
      "type": "TEXT",
      "content": "我先检查一下数据范围。",
      "metadata": {
        "agentId": "claude-worker"
      },
      "createdAt": "2026-04-15T10:00:02"
    }
  ],
  "nextCursor": "cur_hist_xxx",
  "hasMore": true
}
```

## 3. 对象结构草案

### 3.1 SessionSummary

```json
{
  "contextId": "string",
  "agentId": "string",
  "title": "string",
  "status": "string",
  "latestTaskId": "string|null",
  "createdAt": "datetime",
  "updatedAt": "datetime"
}
```

### 3.2 SessionMessage

```json
{
  "messageId": "string",
  "contextId": "string",
  "taskId": "string|null",
  "role": "user|assistant|tool|system",
  "type": "USER|TEXT|TOOL_CALL|TOOL_RESULT|STATE|ERROR",
  "content": "string|null",
  "metadata": {},
  "createdAt": "datetime"
}
```

说明：

- `taskId` 必须在对外 DTO 中显式返回
- `contextId` 是对外唯一会话主键
- `metadata` 首版允许保留扩展字段，但不应承载核心必填字段

### 3.3 OpenTask

```json
{
  "taskId": "string",
  "agentId": "string",
  "contextId": "string",
  "status": "SUBMITTED|RUNNING|AWAITING_INPUT|COMPLETED|FAILED|CANCELLED",
  "result": "string|null",
  "errorMessage": "string|null",
  "createdAt": "datetime",
  "updatedAt": "datetime"
}
```

## 4. taskId 串联规则草案

### 4.1 平台主标识

对上游来说，平台主标识统一使用：

- `taskId`

上游不应依赖 Worker 内部任务 ID 作为主调用键。

### 4.2 消息归属规则

首版规则：

1. 任务创建后，平台返回 `taskId`
2. 该任务过程中产生的所有正式消息都应可归属于该 `taskId`
3. 上游读取会话消息时，也应能看到每条消息对应的 `taskId`
4. 上游只需要使用 `contextId` 定位会话，不需要理解内部 `sessionId`
5. 平台内部必须保证 `contextId -> sessionId` 为一对一稳定映射

### 4.3 Worker 内部任务 ID

Worker 内部任务 ID：

- 仅作为调试字段或平台内部追踪字段

首版建议：

1. 默认不作为公开必填字段
2. 如需返回，可仅放入调试字段或 metadata

## 5. 分页与增量语义草案

### 5.1 首版统一建议

建议会话列表、会话消息列表、任务增量消息统一采用：

- `limit + cursor`

原因：

1. 接口语义更统一
2. 后续更容易切换底层实现
3. 避免同时维护 offset 与 sinceMessageId 多套协议

### 5.2 顺序规则

建议统一：

- 返回结果按时间升序

### 5.3 幂等要求

要求：

1. 调用相同 `cursor` 时，返回结果应保持稳定
2. `nextCursor` 应可继续向后拉取
3. 空结果也必须返回当前 `taskId/contextId`

## 6. 错误语义草案

建议首版明确以下错误类型：

1. `TASK_NOT_FOUND`
2. `CONTEXT_NOT_FOUND`
3. `AGENT_NOT_FOUND`
4. `INVALID_CURSOR`
5. `FORBIDDEN`
6. `BAD_REQUEST`

## 7. 首版仍未锁定的点

当前仍需在实现前确认：

1. `cursor` 的具体编码方式
2. 会话列表是否按 `agentId` 强绑定，还是允许更宽泛查询
3. 消息类型枚举是否进一步收敛
4. 是否返回调试字段 `providerTaskId`
5. 调试信息中是否保留内部 `sessionId`，以及保留在哪一层

## 8. 下一步建议

在本合同草案基础上，下一步建议补两类图示文档：

1. 调用时序图
2. 轮询流程图

原因：

1. 接口字段已经收口后，再补图能避免反复返工
2. 时序图最适合表达“ask -> task poll -> message poll -> session replay”
3. 流程图最适合表达 `cursor` 轮询和完成态判断
