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
  "model": "codex-latest"
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
  "model": "codex-latest",
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
| `base_url` | `string` | 否 | 本次请求覆盖 OpenAI compatible base URL |
| `env_vars` | `object` | 否 | 传给 Codex 子进程的额外环境变量 |
| `images` | `array` | 否 | Base64 图片附件，历史字段名，也可承载非图片附件 |
| `attachments` | `array` | 否 | 上游已上传附件元数据和 URL |
| `codex_home_key` | `string` | 否 | 逻辑账号/actor key；worker 会在 `CODEX_BIZ_HOME_ROOT` 下解析独立 `CODEX_HOME` |
| `developer_instructions` | `string` | 否 | Codex SDK developer instructions |
| `output_schema` | `object` | 否 | Codex SDK turn output schema |
| `codex_config` | `object` | 否 | 额外 Codex SDK config override |
| `sandbox_mode` | `string` | 否 | 覆盖 Codex SDK sandbox mode；默认 `danger-full-access` |
| `approval_policy` | `string` | 否 | 覆盖 Codex SDK approval policy |
| `network_access_enabled` | `boolean` | 否 | 是否允许网络访问 |
| `web_search_mode` | `string` | 否 | 覆盖 Codex web search mode |
| `additional_directories` | `array` | 否 | Codex SDK additional directories，仍受 `CODEX_ALLOWED_CWDS` 约束 |
| `business_runtime_context` | `object` | 否 | Navigator 服务端运行时上下文；可携带 `task_scoped_token`、业务 task/session/context 标识和工具 allowlist，不写入 Codex prompt |

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
  "model": "codex-latest",
  "max_turns": 1
}
```

### 3.1.1 Codex Biz scoped CODEX_HOME

当 Navigator 通过 `providerType=codex-biz-worker` 调用本 worker 时，通常会传入 `codex_home_key`，让不同 actor 使用独立 durable Codex home。worker 侧必须配置绝对路径 `CODEX_BIZ_HOME_ROOT`，否则带 `codex_home_key` 的请求会被拒绝。

稳定错误：

```json
{
  "error": "CODEX_BIZ_HOME_ROOT is required when codex_home_key is provided"
}
```

本机 WSL sim 推荐配置：

```bash
CODEX_WORKER_HOST=0.0.0.0
CODEX_WORKER_PORT=3051
CODEX_BIZ_HOME_ROOT=/home/$USER/.foggy/codex-biz-homes
CODEX_ALLOWED_CWDS=/home/sa/workspace
```

请求示例：

```json
{
  "prompt": "在 actor workspace 内完成本轮任务",
  "cwd": "/mnt/d/world-sim/scenario-1/actor-1",
  "codex_home_key": "scenario-1.actor-1",
  "approval_policy": "never",
  "network_access_enabled": false,
  "web_search_mode": "disabled",
  "developer_instructions": "Return only valid JSON.",
  "output_schema": {
    "type": "object",
    "properties": {
      "decision": { "type": "string" }
    },
    "required": ["decision"]
  },
  "codex_config": {
    "tool_output_token_limit": 4096
  },
  "additional_directories": ["/mnt/d/world-sim/shared"]
}
```

`codex_home_key` 是 worker 本地 scoped home key；上游业务系统不应直接拼接 `CODEX_HOME` 路径。修改 `CODEX_BIZ_HOME_ROOT` 后需要重启 worker 进程。`GET /health` 只返回是否配置了 root，不返回真实路径。

当请求没有显式 `api_key`，且 worker 也没有有效 `OPENAI_API_KEY` 时，scoped home 会使用 worker 默认 Codex home 中的 `auth.json` 作为登录态种子。worker 只复制 `auth.json`，不会在日志、health 或 SSE 事件中输出该文件内容或真实 scoped home 路径。

为避免过期环境变量污染 Codex CLI，worker 会在启动 Codex 子进程前同步处理 `OPENAI_API_KEY` 和 `CODEX_API_KEY`：存在有效 key 时写入有效值；不存在有效 key 时从子进程环境中移除这两个变量，让 Codex login/auth.json 路径生效。

Codex Biz route 在 Navigator 侧使用 `providerType=codex-biz-worker`。它可以复用 `workerBackend=OPENAI_CODEX` 的 `modelConfigId`，但不会暴露为独立可发现 Agent，也不会走 LangGraph BizWorker root-skill 路由。

路线定位上，`LANGGRAPH_BIZ` / LangBizWorker 与 `codex-biz-worker` / CodexBizWorker 是互补关系。企业应用、正式业务编排、审批/挂起、业务审计和依赖 root skill 的上游链路应默认继续使用 LangBizWorker；CodexBizWorker 只作为显式 opt-in 的内部调试、开发者自用和 Codex-native 执行/诊断通道。不要在未完成端到端 parity smoke 前把企业应用默认路由从 LangBizWorker 切到 CodexBizWorker。

`business_runtime_context` 只作为 Java -> Worker 的结构化运行时字段接收。Worker 不会把其中的 `task_scoped_token` 拼进 `developer_instructions`、prompt、日志或 health response。

当 `business_runtime_context.task_scoped_token` 存在，且 `allowed_tools` 为空或包含 `business.functions.invoke` / `business.functions.*` / `business.*` 等业务函数授权时，Worker 会给 Codex 动态注入内置 `navigator_business` MCP server。该 server 通过 `CODEX_NAVIGATOR_WORKER_GATEWAY_BASE_URL` 指向 Navigator WorkerGateway，默认 `http://localhost:8080`，并使用运行时 token 调用 `/internal/worker-gateway/v1`。

当前内置工具：

- `list_business_functions`
- `get_business_function_schema`
- `invoke_business_function`

`get_business_function_schema` 和 `invoke_business_function` 使用 Navigator 返回的完整 `function_id`；不要删除 `.v1` 等函数 id 后缀。`version` 可省略，也可重复传入返回的同一版本。`invoke_business_function` 还要求传 `input`，对象输入会转发为 `input`，字符串输入会转发为 `inputJson`。`report_tool_message` 不是模型可见工具；Worker 会在 `invoke_business_function` 后对 Navigator WorkerGateway 做 best-effort 内部审计上报。

上游不需要、也不应该把 `task_scoped_token` 写入 prompt、developer instructions、`codex_config` 或模型可见参数。业务函数的真实授权仍由 Navigator WorkerGateway 根据 task-scoped token、skill grants 和 client-app visibility 校验。若未来要扩大到正式业务链路，仍需针对原 BizWorker 依赖的 `submit_skill_result`、BusinessFunction side effect、tool result/message 形态做端到端 smoke；在此之前，本能力只证明 Codex Worker 具备第一段 MCP 桥接能力，不改变 LangBizWorker 的企业应用默认定位。

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
data: {"type":"result","task_id":"...","session_id":"019d...","content":"PONG","result":"PONG","duration_ms":9159,"input_tokens":10238,"output_tokens":22,"num_turns":1,"model":"gpt-5.6-sol","seq":2}
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
{ "model": "codex-latest" }
```

```json
{ "model": "gpt-5.6-sol:high" }
```

```json
{ "model": "codex-ultra" }
```

### 4.2 支持的思考等级

当前 worker 会把 `model` 里的后缀透传为 Codex 的 `model_reasoning_effort`，支持：

- `minimal`
- `low`
- `medium`
- `high`
- `xhigh`
- `max`
- `ultra`

同时兼容一个前端别名：

- `extra-high` 会被自动映射为 `xhigh`

例如：

```json
{
  "prompt": "分析这个目录的主要风险",
  "cwd": "D:\\projects\\demo-repo",
  "model": "gpt-5.6-sol:extra-high"
}
```

等价于：

```json
{
  "model": "gpt-5.6-sol:xhigh"
}
```

GPT-5.6-Sol 的稳定 alias：

| Alias | 实际模型 |
|---|---|
| `codex-latest` | `gpt-5.6-sol` |
| `codex-fast` | `gpt-5.6-sol:low` |
| `codex-deep` | `gpt-5.6-sol:high` |
| `codex-xhigh` | `gpt-5.6-sol:xhigh` |
| `codex-max` | `gpt-5.6-sol:max` |
| `codex-ultra` | `gpt-5.6-sol:ultra` |
| `codex-mini` | `gpt-5.4-mini` |

`ultra` 会允许 Codex 自动委派子任务。Worker 会继续返回父任务的正常文本和最终结果，并对协作工具事件写入脱敏诊断日志；当前 SSE 协议不承诺完整的子 Agent 拓扑或逐 Agent 进度。

### 4.3 默认值

如果不传 `model`，当前默认是：

```text
codex-latest -> gpt-5.6-sol
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
- scoped `CODEX_HOME` (`codex_home_key` + `CODEX_BIZ_HOME_ROOT`)
- developer instructions
- output schema
- Codex config override
- sandbox mode / approval policy / network access / web search mode
- additional directories
- 图片输入和 URL 附件元数据透传

### 9.2 默认行为

当请求未显式传入 `sandbox_mode` 时，worker 与 Navigator 的 `codex-biz-worker` route 均默认使用 `danger-full-access`，即不启用 Codex 文件系统和命令网络沙箱限制。该模式不会突破 Worker 进程自身的操作系统用户、容器或服务权限边界。

直接调用 worker 时，其他策略字段未显式传入则由 Codex SDK 配置决定。通过 Navigator 的 `codex-biz-worker` route 调用时，默认策略为 `approval_policy=never`、`network_access_enabled=false`、`web_search_mode=disabled`。其中 `network_access_enabled` 只配置 `workspace-write` 沙箱内的命令网络；使用 `danger-full-access` 时不会重新建立命令网络边界。

调用方需要改用受限模式或覆盖策略时，可以显式带上：

- `sandbox_mode`
- `approval_policy`
- `network_access_enabled`
- `web_search_mode`
- `additional_directories`

### 9.3 SDK 有但当前 worker 未承诺稳定契约

虽然 Codex SDK 本身还有更多能力，但不在上表中的 SDK 实验字段不属于当前 HTTP 稳定契约。上游不要依赖 worker 私有实现细节或 SDK 内部字段名。

如果上游后续需要新的 SDK 字段，应先扩展 `QueryRequest`、校验逻辑和回归测试，再纳入本文档。

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
- `codex_biz_home_root_configured`
- `codex_biz_scoped_home_ready`

其中 `codex_auth_mode` 可能为：

- `api_key`
- `codex_login`
- `none`

`codex_biz_home_root_configured` 和 `codex_biz_scoped_home_ready` 只表示 `CODEX_BIZ_HOME_ROOT` 是否已配置，不返回真实路径。

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
  -d "{\"prompt\":\"请总结当前仓库结构\",\"cwd\":\"D:\\\\projects\\\\demo-repo\",\"model\":\"codex-latest\"}"
```

### 12.2 继续会话

```bash
curl -N -X POST http://localhost:3051/api/v1/query \
  -H "Content-Type: application/json" \
  -d "{\"prompt\":\"继续，重点看测试和部署\",\"session_id\":\"019d1b11-f816-7e21-8ff6-2f9958abaf0d\",\"cwd\":\"D:\\\\projects\\\\demo-repo\",\"model\":\"codex-max\"}"
```

### 12.3 指定 API Key

```bash
curl -N -X POST http://localhost:3051/api/v1/query \
  -H "Content-Type: application/json" \
  -d "{\"prompt\":\"检查这个目录的风险\",\"cwd\":\"D:\\\\projects\\\\demo-repo\",\"model\":\"codex-latest\",\"api_key\":\"sk-...\"}"
```

### 12.4 Ultra runtime 边界

SDK Worker 不再接受新的 Ultra 会话。没有 `session_id` 的 `codex-ultra` 请求会在执行前返回 HTTP `409` 和稳定错误码 `CODEX_ULTRA_APP_SERVER_REQUIRED`，上游应把新会话路由到独立 App Server Worker。

已有 SDK Ultra thread 为保持 runtime affinity，可以携带原 `session_id` 继续 drain：

```bash
curl -N -X POST http://localhost:3051/api/v1/query \
  -H "Content-Type: application/json" \
  -d "{\"prompt\":\"继续并汇总结论\",\"session_id\":\"019d1b11-f816-7e21-8ff6-2f9958abaf0d\",\"cwd\":\"D:\\\\projects\\\\demo-repo\",\"model\":\"codex-ultra\"}"
```

### 12.5 重连任务流

```bash
curl -N "http://localhost:3051/api/v1/tasks/44beb057-c03b-4aa1-aabf-fffa479114c8/subscribe?ack_seq=2"
```

### 12.6 Codex Biz readiness

```bash
curl http://localhost:3051/health
```

期望看到：

```json
{
  "status": "ok",
  "codex_biz_home_root_configured": true,
  "codex_biz_scoped_home_ready": true
}
```

本地 smoke helper：

```powershell
powershell -ExecutionPolicy Bypass -File tools/codex-agent-worker/scripts/codex-biz-smoke.ps1 -BaseUrl http://127.0.0.1:3051
```

执行真实 Codex actor A/B 隔离与 resume 验证：

```powershell
powershell -ExecutionPolicy Bypass -File tools/codex-agent-worker/scripts/codex-biz-smoke.ps1 -BaseUrl http://127.0.0.1:3051 -Cwd /home/sa/workspace/Foggy-Navigator -RunLiveQueries
```

预期结果：

- actor A 和 actor B 返回不同 `session_id`
- actor A resume 返回与 actor A 首次请求相同的 `session_id`
- 输出只包含 task/session/model/turn/event 计数，不包含 token、auth 文件内容或 scoped home 真实路径

该 smoke 只验证 CodexBizWorker 的 scoped home、会话隔离和 Codex 执行前置条件；它不替代 LangBizWorker 的企业业务 smoke，也不构成把企业应用默认路线切到 CodexBizWorker 的验收证据。
