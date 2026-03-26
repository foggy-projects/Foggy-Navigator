# Codex Agent Worker 上游接入说明

本文面向调用 `codex-agent-worker` 的上游服务或前端，说明如何传参、如何选择鉴权模式、如何续接会话、如何订阅任务，以及当前 worker 实际暴露了哪些 Codex SDK 能力。

## 1. 服务概览

当前 worker 对外暴露以下接口：

- `GET /health`
- `POST /api/v1/query`
- `GET /api/v1/tasks/:taskId/subscribe`
- `GET /api/v1/tasks/:taskId/status`
- `POST /api/v1/tasks/:taskId/abort`
- `GET /api/v1/sessions`

`POST /api/v1/query` 是主入口。它会启动一个 Codex 任务，并通过 SSE 持续返回事件。

## 2. 鉴权方式

本 worker 有两层鉴权，含义不同：

- Worker 自身的 HTTP 鉴权
- Codex/OpenAI 的模型调用鉴权

### 2.1 Worker 自身的 HTTP 鉴权

如果服务端配置了 `CODEX_WORKER_TOKEN`，上游调用所有非 `/health` 接口时都需要传：

```http
Authorization: Bearer <CODEX_WORKER_TOKEN>
```

如果服务端未配置 `CODEX_WORKER_TOKEN`，则不需要这个 Header。

### 2.2 Codex/OpenAI 调用鉴权

支持两种模式：

1. 订阅模式
2. API Key 模式

#### 订阅模式

适用于机器已经执行过 Codex 登录，本机存在 `~/.codex/auth.json`。

上游调用时：

- 不要在请求体里传 `api_key`
- 服务端 `.env` 里的 `OPENAI_API_KEY` 也应为空，或至少不能是占位值

请求示例：

```json
{
  "prompt": "请分析当前目录下最近失败的测试并给出修复建议",
  "cwd": "D:\\projects\\demo-repo",
  "model": "gpt-5.4-mini"
}
```

说明：

- 这时 worker 会走本机 Codex 登录态
- 当前项目已对 `sk-xxx` 这类占位值做过滤，不会再错误覆盖订阅登录

#### API Key 模式

适用于由上游显式提供 OpenAI API Key。

有两种传法：

1. 每次请求单独传 `api_key`
2. 服务端通过 `.env` 提供 `OPENAI_API_KEY`

单次请求传入示例：

```json
{
  "prompt": "总结这个仓库的构建方式",
  "cwd": "D:\\projects\\demo-repo",
  "model": "gpt-5.4-mini",
  "api_key": "sk-..."
}
```

优先级如下：

1. 请求体里的 `api_key`
2. 服务端环境变量 `OPENAI_API_KEY`
3. 本机 `~/.codex/auth.json`

建议：

- 如果上游要严格控制租户级鉴权，用每次请求传 `api_key`
- 如果是单租户部署，用服务端 `.env`
- 如果是本机开发联调，优先用订阅模式

## 3. 主接口: POST /api/v1/query

### 3.1 请求体

当前支持的字段如下：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `prompt` | `string` | 是 | 发送给 Codex 的提示词 |
| `cwd` | `string` | 否 | 工作目录 |
| `session_id` | `string` | 否 | 续接已有 Codex thread |
| `model` | `string` | 否 | 模型名，支持附带思考等级后缀 |
| `max_turns` | `number` | 否 | 限制最多完成多少个 turn，必须是正整数 |
| `api_key` | `string` | 否 | 本次请求覆盖默认鉴权 |

最小请求：

```json
{
  "prompt": "Reply with exactly PONG."
}
```

带工作目录和模型：

```json
{
  "prompt": "请阅读当前项目并总结启动方式",
  "cwd": "D:\\projects\\demo-repo",
  "model": "gpt-5.4-mini",
  "max_turns": 1
}
```

### 3.2 返回方式

返回不是普通 JSON，而是 `text/event-stream`。

事件统一格式：

```text
event: message
data: {...json...}
```

常见事件类型：

- `assistant_text`
- `tool_use`
- `tool_result`
- `result`
- `error`

### 3.3 SSE 返回示例

```text
event: message
data: {"type":"assistant_text","task_id":"...","session_id":"019d...","content":"PONG","seq":1}

event: message
data: {"type":"result","task_id":"...","session_id":"019d...","content":"PONG","result":"PONG","duration_ms":9159,"input_tokens":10238,"output_tokens":22,"num_turns":1,"model":"gpt-5.4-mini","seq":2}
```

关键字段说明：

- `task_id`: 当前任务 ID，用于任务状态查询、重连订阅、取消任务
- `session_id`: Codex thread ID，用于后续续接会话
- `seq`: 事件序号，用于断线重放

## 4. 模型与思考等级

### 4.1 传法

`model` 支持两种形式：

1. 只传模型名
2. 传 `模型名:思考等级`

示例：

```json
{ "model": "gpt-5.4-mini" }
```

```json
{ "model": "gpt-5.4:high" }
```

```json
{ "model": "gpt-5.4-mini:medium" }
```

### 4.2 支持的思考等级

当前 worker 会把 `model` 里的后缀映射到 Codex SDK 的 `modelReasoningEffort`，支持：

- `minimal`
- `low`
- `medium`
- `high`
- `xhigh`

同时兼容一个前端别名：

- `extra-high` 会被自动映射为 `xhigh`

例如：

```json
{
  "prompt": "分析这个目录的主要风险",
  "cwd": "D:\\projects\\demo-repo",
  "model": "gpt-5.4:extra-high"
}
```

等价于：

```json
{
  "model": "gpt-5.4:xhigh"
}
```

### 4.3 默认值

如果不传 `model`，当前默认是：

```text
gpt-5.4-mini
```

## 5. 续接会话

### 5.1 续接方式

Codex 的会话续接依赖 `session_id`，它本质上就是 SDK 的 `thread_id`。

第一次请求时不传：

```json
{
  "prompt": "先阅读仓库并告诉我结构"
}
```

SSE 返回里拿到：

- `session_id`

下一次继续同一会话时传回去：

```json
{
  "prompt": "继续，重点看 CI 和发布脚本",
  "session_id": "019d1b11-f816-7e21-8ff6-2f9958abaf0d"
}
```

这会调用 SDK 的 `resumeThread(session_id, ...)`，而不是新开线程。

### 5.2 /api/v1/sessions 的作用

`GET /api/v1/sessions` 会返回当前 worker 进程已知的会话列表。

示例返回：

```json
[
  {
    "session_id": "019d1b11-f816-7e21-8ff6-2f9958abaf0d",
    "thread_id": "019d1b11-f816-7e21-8ff6-2f9958abaf0d",
    "created_at": "2026-03-23T14:27:19.000Z",
    "last_active": "2026-03-23T14:27:28.000Z"
  }
]
```

注意：

- 这是从当前进程内存里的任务注册表重建出来的
- 它不是一个完整、永久的会话数据库
- 如果 worker 重启，这个列表可能为空

### 5.3 真正的“回退会话”是否支持

当前 worker 不支持“把会话回退到某个历史轮次后再继续”。

当前仅支持：

- 新建会话
- 按 `session_id` 续接已有会话
- 对任务事件做重放

当前不支持：

- 指定某个历史 turn 作为新分叉点
- 删除会话中的某几轮历史消息
- 会话级快照回滚

如果上游产品里有“回退/撤销到某轮”的需求，需要在上游自行维护分叉点和会话映射，当前 worker 没有暴露这层能力。

## 6. 任务订阅、断线重连与回放

`POST /api/v1/query` 本身就会返回实时 SSE。

如果上游断线，或者想在另一个连接里继续接收结果，可使用：

```text
GET /api/v1/tasks/:taskId/subscribe
```

### 6.1 重连订阅

示例：

```http
GET /api/v1/tasks/44beb057-c03b-4aa1-aabf-fffa479114c8/subscribe
```

如果要从某个事件序号之后继续拉：

```http
GET /api/v1/tasks/44beb057-c03b-4aa1-aabf-fffa479114c8/subscribe?ack_seq=5
```

说明：

- `ack_seq` 表示客户端已经确认收到的最后一个事件序号
- 服务端会从 `seq > ack_seq` 的事件开始补发
- 当前版本已修正 `seq` 为严格单调递增，可安全用于断线续传

### 6.2 订阅返回行为

连接建立后，服务端会先发一个 `sync_checkpoint`：

```json
{
  "type": "assistant_text",
  "task_id": "44beb057-c03b-4aa1-aabf-fffa479114c8",
  "subtype": "sync_checkpoint",
  "content": "",
  "seq": 0,
  "latest_seq": 12,
  "event_count": 12
}
```

然后：

1. 回放未确认事件
2. 继续推送后续新事件

如果任务已经结束，且内存广播已关闭：

- 服务端会回放完已有事件后直接结束连接

### 6.3 基于磁盘日志回放

worker 会把任务事件写到 `logs/events/<taskId>.jsonl`。

因此即使任务广播对象已不存在，`/subscribe` 仍可能从磁盘回放历史事件。这个能力适合：

- 前端断线后重新取回完整结果
- 上游服务做失败补偿

## 7. 任务状态与取消

### 7.1 查询任务状态

```text
GET /api/v1/tasks/:taskId/status
```

示例返回：

```json
{
  "task_id": "44beb057-c03b-4aa1-aabf-fffa479114c8",
  "status": "completed",
  "thread_id": "019d1b11-f816-7e21-8ff6-2f9958abaf0d",
  "started_at": "2026-03-23T14:27:19.000Z",
  "completed_at": "2026-03-23T14:27:28.000Z",
  "duration_ms": 9159
}
```

`status` 可能取值：

- `running`
- `completed`
- `failed`
- `aborted`

### 7.2 取消任务

```text
POST /api/v1/tasks/:taskId/abort
```

示例返回：

```json
{
  "task_id": "44beb057-c03b-4aa1-aabf-fffa479114c8",
  "status": "aborted"
}
```

取消的是当前运行中的任务，不是删除整个会话历史。

取消成功后，SSE 流通常会再收到一个终态错误事件：

```json
{
  "type": "error",
  "task_id": "29cadef9-088d-4dfb-8bbb-2b8d0563c461",
  "session_id": "019d1b29-2d17-7043-b2dd-e3b89853cda7",
  "error": "Task aborted",
  "seq": 2
}
```

## 8. 工作目录 cwd

`cwd` 会作为 Codex 的工作目录传入 SDK。

示例：

```json
{
  "prompt": "检查这个仓库的 package.json",
  "cwd": "D:\\projects\\demo-repo"
}
```

如果服务端配置了 `CODEX_ALLOWED_CWDS`，那么 `cwd` 必须命中允许列表，否则会返回 `403`。

建议：

- 上游总是显式传 `cwd`
- 服务端配置白名单，避免 agent 访问错误目录

## 9. 当前 worker 实际暴露的 Codex 能力

### 9.1 已暴露

- 新建会话
- 续接会话
- 流式文本输出
- 命令执行事件透传
- MCP 工具调用事件透传
- 文件修改事件透传
- 推理摘要事件透传
- 任务取消
- 任务断线重连和事件重放
- 模型选择
- 思考等级设置
- API Key 或本机订阅登录

### 9.2 已固定写死的行为

当前 worker 内部固定传给 SDK：

- `skipGitRepoCheck: true`
- `sandboxMode: danger-full-access`

也就是说，上游当前不能按请求动态切换：

- sandbox 模式
- approval policy
- web search
- network access
- additional directories

### 9.3 SDK 有但当前 worker 未暴露

虽然 Codex SDK 本身还有更多能力，但当前 HTTP API 未开放这些字段：

- `approvalPolicy`
- `networkAccessEnabled`
- `webSearchMode`
- `webSearchEnabled`
- `additionalDirectories`
- `outputSchema`
- 图片输入

如果上游后续需要这些能力，需要扩展 `QueryRequest` 和 `runQuery()` 的透传参数。

## 10. 当前限制与注意事项

### 10.1 max_turns 是 worker 侧限制

Codex SDK 当前没有直接暴露 `max_turns` HTTP 参数，因此这里的限制由 worker 自己执行。

行为是：

- 每完成一个 `turn.completed` 计一次
- 当已完成轮数达到上限后，在下一轮开始时主动中止任务

因此它能约束多轮代理行为，但不是底层模型原生参数。

### 10.2 /health 不是完整鉴权可用性检查

`GET /health` 只能说明服务进程起来了，不代表：

- OpenAI API Key 一定有效
- 本机订阅态一定有效
- 模型调用一定成功

真正是否能调用 Codex，仍以实际 `POST /api/v1/query` 结果为准。

当前 `/health` 额外会返回：

- `codex_auth_configured`
- `codex_auth_mode`

其中 `codex_auth_mode` 可能为：

- `api_key`
- `codex_login`
- `none`

### 10.3 会话列表不是持久化数据库

`GET /api/v1/sessions` 只反映当前进程见过的 thread，不适合作为权威会话存储。

如果上游要长期追踪会话，应自行落库：

- `session_id`
- 发起人
- 业务对象 ID
- 最近任务 ID
- 最近活动时间

## 11. 推荐接入方式

### 11.1 本地开发

- worker 不传 `api_key`
- `.env` 的 `OPENAI_API_KEY` 留空
- 本机先完成 Codex 登录
- 上游保存 `task_id` 和 `session_id`

### 11.2 服务化部署

- 上游每次请求显式传 `cwd`
- 上游保存 `task_id`、`session_id`、最后收到的 `seq`
- 断线后使用 `/api/v1/tasks/:taskId/subscribe?ack_seq=N` 重连
- 如果是多租户，优先每次请求传独立 `api_key`

## 12. 调用示例

### 12.1 新开会话

```bash
curl -N -X POST http://localhost:3051/api/v1/query \
  -H "Content-Type: application/json" \
  -d "{\"prompt\":\"请总结当前仓库结构\",\"cwd\":\"D:\\\\projects\\\\demo-repo\",\"model\":\"gpt-5.4-mini\"}"
```

### 12.2 继续会话

```bash
curl -N -X POST http://localhost:3051/api/v1/query \
  -H "Content-Type: application/json" \
  -d "{\"prompt\":\"继续，重点看测试和部署\",\"session_id\":\"019d1b11-f816-7e21-8ff6-2f9958abaf0d\",\"cwd\":\"D:\\\\projects\\\\demo-repo\",\"model\":\"gpt-5.4:high\"}"
```

### 12.3 指定 API Key

```bash
curl -N -X POST http://localhost:3051/api/v1/query \
  -H "Content-Type: application/json" \
  -d "{\"prompt\":\"检查这个目录的风险\",\"cwd\":\"D:\\\\projects\\\\demo-repo\",\"model\":\"gpt-5.4-mini\",\"api_key\":\"sk-...\"}"
```

### 12.4 重连任务流

```bash
curl -N "http://localhost:3051/api/v1/tasks/44beb057-c03b-4aa1-aabf-fffa479114c8/subscribe?ack_seq=2"
```
