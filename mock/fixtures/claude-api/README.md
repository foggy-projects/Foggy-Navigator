# Claude/Anthropic Messages API — Fixture 数据

## 采集记录

| 文件 | 类型 | 来源 | 采集时间 | 场景 |
|------|------|------|---------|------|
| simple-text | ✅ 真实采集 | DashScope Anthropic 透传 | 2026-03-05 17:12 | 纯文本回复 |
| tool-use | ✅ 真实采集 | DashScope Anthropic 透传 | 2026-03-05 17:14 | 单工具调用 |
| multi-turn | 🔧 基于验证格式构造 | — | 2026-03-05 | tool_result 回传后的最终响应 |
| parallel-tool-use | 🔧 基于验证格式构造 | — | 2026-03-05 | 3 个并行 tool_use 块 |
| max-tokens-truncated | 🔧 基于验证格式构造 | — | 2026-03-05 | stop_reason=max_tokens 截断 |
| with-system-prompt | 🔧 基于验证格式构造 | — | 2026-03-05 | 含 system 参数的请求+响应 |
| error-* (7 个) | 🔧 基于官方文档构造 | Anthropic API Docs | 2026-03-05 | HTTP 400/401/403/404/429/500/529 |
| error-mid-stream | 🔧 基于官方文档构造 | Anthropic API Docs | 2026-03-05 | 流式传输中途报错 |

### 来源说明

- **✅ 真实采集**：通过 `tools/claude-code-proxy` Passthrough 模式从真实 API 捕获
- **🔧 基于验证格式构造**：按照真实采集验证过的数据结构手动构造，格式可靠
- **🔧 基于官方文档构造**：按照 [Anthropic API Errors 文档](https://docs.anthropic.com/en/api/errors) 构造

## 采集方法

通过 `tools/claude-code-proxy` 的 Passthrough 模式，将 Claude Messages API 格式的请求透传到
DashScope 的 Anthropic-compatible 端点，自动捕获完整的请求/响应数据。

```
Claude Code CLI → claude-code-proxy (capture) → DashScope Anthropic API
                                                       ↓
                                              claude-stream-raw.txt
```

原始采集数据位于：`tools/claude-code-proxy/captured-fixtures/`（仅在 dev-run worktree 中）

## 目录结构

```
claude-api/
├── requests/                                # 请求 fixture
│   ├── simple-text.json                     # ✅ 纯文本请求（无 tools）
│   ├── tool-use.json                        # ✅ 含工具定义的请求
│   ├── multi-turn-with-tool-result.json     # 🔧 多轮对话（含 tool_result 回传）
│   ├── with-system-prompt.json              # 🔧 含 system 参数的请求
│   └── parallel-tool-use.json               # 🔧 触发并行工具调用的请求
│
├── non-streaming/                           # 重组后的完整响应（非流式格式）
│   ├── simple-text.json                     # ✅ 纯文本响应（thinking + text）
│   ├── tool-use.json                        # ✅ 工具调用响应（thinking + text + tool_use）
│   ├── multi-turn-final-response.json       # 🔧 多轮对话最终响应（end_turn）
│   ├── parallel-tool-use.json               # 🔧 并行工具调用（3 个 tool_use 块）
│   ├── max-tokens-truncated.json            # 🔧 max_tokens 截断响应
│   ├── with-system-prompt-response.json     # 🔧 含 system prompt 的响应
│   ├── error-400-invalid-request.json       # 🔧 400 参数错误
│   ├── error-401-authentication.json        # 🔧 401 认证失败
│   ├── error-403-permission.json            # 🔧 403 权限不足
│   ├── error-404-not-found.json             # 🔧 404 资源不存在
│   ├── error-429-rate-limit.json            # 🔧 429 速率限制
│   ├── error-500-api.json                   # 🔧 500 服务器内部错误
│   └── error-529-overloaded.json            # 🔧 529 服务过载
│
├── streaming/                               # SSE 流式事件序列（JSONL 格式）
│   ├── simple-text-events.jsonl             # ✅ 纯文本流事件序列
│   ├── tool-use-events.jsonl                # ✅ 工具调用流事件序列
│   ├── multi-turn-final-events.jsonl        # 🔧 多轮对话最终响应流
│   ├── parallel-tool-use-events.jsonl       # 🔧 并行工具调用流
│   ├── max-tokens-truncated-events.jsonl    # 🔧 max_tokens 截断流
│   └── error-mid-stream-events.jsonl        # 🔧 流式中途错误
│
└── README.md                                # 本文件
```

## Claude Messages API 格式参考

### 错误响应格式（已验证 — 来自官方文档）

```json
{
  "type": "error",
  "error": {
    "type": "authentication_error",
    "message": "invalid x-api-key"
  },
  "request_id": "req_xxx"
}
```

错误类型映射：

| HTTP 状态码 | error.type | 说明 |
|------------|-----------|------|
| 400 | `invalid_request_error` | 请求格式/内容错误 |
| 401 | `authentication_error` | API Key 问题 |
| 403 | `permission_error` | 权限不足 |
| 404 | `not_found_error` | 资源不存在 |
| 413 | `request_too_large` | 请求超过 32MB |
| 429 | `rate_limit_error` | 速率限制 |
| 500 | `api_error` | 服务器内部错误 |
| 529 | `overloaded_error` | 服务过载 |

### SSE 流式事件格式（已验证 — 真实采集）

#### 事件类型一览

| SSE event | data.type | 说明 | 出现次数 |
|-----------|-----------|------|---------|
| `message_start` | `message_start` | 消息开始，含 model/id | 1 |
| `ping` | `ping` | 心跳 | 1 |
| `content_block_start` | `content_block_start` | 内容块开始 | 每个块 1 次 |
| `content_block_delta` | `content_block_delta` | 内容块增量 | 每个块多次 |
| `content_block_stop` | `content_block_stop` | 内容块结束 | 每个块 1 次 |
| `message_delta` | `message_delta` | 消息结束信号，含 stop_reason + usage | 1 |
| `message_stop` | `message_stop` | 消息完成 | 1 |
| `error` | `error` | 流式中途错误 | 0-1 |

#### 内容块类型

| content_block.type | delta.type | 说明 |
|-------------------|-----------|------|
| `thinking` | `thinking_delta` | 思维链（扩展思维） |
| `thinking` | `signature_delta` | 思维签名（空字符串） |
| `text` | `text_delta` | 文本输出 |
| `tool_use` | `input_json_delta` | 工具调用参数（增量 JSON） |

#### 典型事件序列

**纯文本回复**：
```
message_start → ping →
  content_block_start(thinking) → thinking_delta... → signature_delta → content_block_stop →
  content_block_start(text) → text_delta... → content_block_stop →
message_delta(end_turn) → message_stop
```

**工具调用**：
```
message_start → ping →
  content_block_start(thinking) → thinking_delta... → signature_delta → content_block_stop →
  content_block_start(text) → text_delta... → content_block_stop →
  content_block_start(tool_use) → input_json_delta... → content_block_stop →
message_delta(tool_use) → message_stop
```

**并行工具调用**（多个 tool_use 块）：
```
message_start → ping →
  content_block_start(thinking) → ... → content_block_stop →
  content_block_start(text) → ... → content_block_stop →
  content_block_start(tool_use, index=2) → input_json_delta... → content_block_stop →
  content_block_start(tool_use, index=3) → input_json_delta... → content_block_stop →
  content_block_start(tool_use, index=4) → input_json_delta... → content_block_stop →
message_delta(tool_use) → message_stop
```

**流式中途错误**：
```
message_start → ping →
  content_block_start(text) → text_delta... →
event:error (连接中断，无 message_stop)
```

#### stop_reason 值

| stop_reason | 含义 |
|------------|------|
| `end_turn` | 正常结束，助手完成回复 |
| `tool_use` | 需要执行工具调用，等待 tool_result |
| `max_tokens` | 达到 max_tokens 限制 |
| `stop_sequence` | 遇到 stop_sequence |

### Anthropic 请求格式要点

1. **system prompt** 使用顶层 `system` 参数（不是 messages 数组中的 system role）
2. **tool_result** 放在 `role: "user"` 消息中，`content` 为包含 `type: "tool_result"` 的数组
3. **工具定义** 使用 `input_schema`（不是 OpenAI 的 `parameters`）
4. **并行工具调用** 的结果需要在同一个 user 消息中包含多个 `tool_result`

## JSONL 流式 Fixture 使用方法

Mock Server 可逐行读取 JSONL 文件，转为 SSE 事件发送：

```python
import json

def stream_fixture(filepath):
    """逐行读取 JSONL fixture，yield SSE 格式字符串"""
    with open(filepath) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            record = json.loads(line)
            event = record["event"]
            data = json.dumps(record["data"])
            yield f"event: {event}\ndata: {data}\n\n"
```

## 脱敏规则

- API Key → `sk-test-mock-key-xxx`
- 消息 ID → `msg_mock-{scenario}-{seq}`
- 工具调用 ID → `toolu_mock-{name}-{seq}`
- request_id → `req_mock-error-{code}-{seq}`
- 其余内容保留原始结构，仅去除真实用户信息

## 待采集场景

- [x] 纯文本回复（✅ 真实采集）
- [x] 单工具调用（✅ 真实采集）
- [x] 多轮对话 — tool_result 回传（🔧 构造）
- [x] 错误响应 — 400/401/403/404/429/500/529（🔧 构造）
- [x] 流式中途错误（🔧 构造）
- [x] max_tokens 截断响应（🔧 构造）
- [x] 带 system prompt 的请求+响应（🔧 构造）
- [x] 并行工具调用 — 多 tool_use 块（🔧 构造）
- [ ] 大量 token 的长响应（需真实采集）
- [ ] 多轮对话真实采集验证（需 proxy passthrough）
