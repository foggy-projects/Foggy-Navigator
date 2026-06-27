# 测试速查参考

## 后端测试文件清单（按模块）

### agent-framework（15 个测试文件）
```
src/test/java/com/foggy/navigator/agent/framework/
├── service/
│   ├── DefaultAgentInvokerTest.java
│   ├── skill/
│   │   ├── KeywordSkillMatcherTest.java
│   │   ├── LlmSkillMatcherTest.java
│   │   └── SkillMatcherTest.java
│   └── ...
├── llm/
│   └── ...
└── routing/
    └── ...
```

### session-module（5 个测试文件）
```
src/test/java/com/foggy/navigator/session/
├── service/
│   ├── JpaSessionManagerTest.java
│   └── ...
└── controller/
    └── ...
```

### addons/coding-agent（9 个测试文件）
```
src/test/java/com/foggy/navigator/coding/agent/
├── service/
│   ├── ConversationServiceTest.java
│   └── ...
├── controller/
│   └── ...
└── integration/
    └── ...
```

### addons/claude-worker-agent（6 个测试文件）
```
src/test/java/com/foggy/navigator/claude/worker/
├── service/
│   └── ...
└── ...
```

### metadata-config-module（3 个测试文件）
```
src/test/java/com/foggy/navigator/metadata/query/config/
├── service/
│   ├── SkillConfigManagerTest.java
│   ├── AgentModelServiceTest.java
│   └── ...
└── TestConfig.java
```

### metadata-query-module（2 个测试文件）
```
src/test/java/com/foggy/navigator/metadata/query/
├── service/
│   └── ...
└── controller/
    └── MetadataQueryControllerTest.java
```

### user-auth-module（1 个测试文件）
```
src/test/java/com/foggy/navigator/auth/
└── service/
    └── JwtTokenServiceTest.java
```

### tutor-agent（1 个测试文件）
```
src/test/java/com/foggy/navigator/tutor/
└── ...
```

### addons/task-assistant（3 个测试文件）
```
src/test/java/com/foggy/navigator/task/assistant/
└── ...
```

## 前端测试文件清单

### packages/foggy-chat
```
src/__tests__/
├── chatState.test.ts              # 聊天状态管理（479行，全面）
└── interactionCards.test.ts       # 交互卡片组件（532行，全面）
```

### packages/navigator-frontend
```
src/__tests__/
├── TutorAgentAdapter.test.ts      # Tutor Agent 消息适配
├── useClaudeWorker.test.ts        # Claude Worker composable
├── useSession.test.ts             # 会话管理 composable
└── useUnifiedSse.test.ts          # 统一 SSE composable

src/views/__tests__/
└── ClaudeWorkerView.integration.test.ts  # 集成测试

tooltip-test.spec.ts               # Playwright E2E（根目录）
```

### L3 集成测试项目
```
addons/coding-agent/integration-tests/tests/
├── 01-basic-flow.test.ts
├── 02-sse-events.test.ts
├── 03-cleanup-recovery.test.ts
├── 04-error-handling.test.ts
└── 05-e2e-openhands.test.ts

session-module/integration-tests/tests/
├── 01-session-crud.test.ts
├── 02-message-flow.test.ts
├── 03-sse-events.test.ts
├── 04-guide-cards.test.ts
└── 05-error-handling.test.ts
```

## 版本信息

| 依赖 | 版本 | 说明 |
|------|------|------|
| Spring Boot | 3.4.2 | 父 POM 版本 |
| JUnit 5 | (via starter) | Jupiter |
| Mockito | (via starter) | + MockitoExtension |
| H2 | (managed) | 内存数据库 |
| Vitest | ^4.0.18 | 前端测试运行器 |
| @vue/test-utils | ^2.4.6 | Vue 组件测试 |
| happy-dom | ^20.6.1 | DOM 模拟 |
| Playwright | ^1.58.0 | E2E 测试 |

## 快速新建测试检查清单

### 新建后端 L1 单元测试

- [ ] 在 `src/test/java/` 下镜像生产代码包路径
- [ ] 类名 `{被测类}Test.java`
- [ ] `@ExtendWith(MockitoExtension.class)`
- [ ] `@Mock` 声明依赖、`@InjectMocks` 声明被测类
- [ ] `@DisplayName` 提供中文描述
- [ ] 每个测试方法有明确的 `assertThat` 断言

### 新建后端 L2 集成测试

- [ ] `src/test/resources/application.yml` 配置 H2
- [ ] 如需 `TestConfig.java`，配置 `@EntityScan` + `@EnableJpaRepositories`
- [ ] `@SpringBootTest(classes = TestConfig.class)`
- [ ] `@ActiveProfiles("test")` + `@Transactional`
- [ ] 验证数据库交互（INSERT/UPDATE/SELECT）

### 新建前端单元测试

- [ ] 在 `src/__tests__/` 或组件旁的 `__tests__/` 目录
- [ ] 文件名 `{模块}.test.ts`
- [ ] `vi.mock()` 模拟外部依赖
- [ ] `beforeEach` 中 `vi.clearAllMocks()`
- [ ] 工厂函数用 `make` 前缀

### 新建 L3 集成测试

- [ ] 独立 `integration-tests/` 目录
- [ ] `package.json` + `vitest.config.ts` + `tsconfig.json`
- [ ] `src/api-client.ts` 封装 HTTP 调用
- [ ] `tests/setup.ts` 全局健康检查 + 登录
- [ ] 文件命名 `{序号}-{功能}.test.ts`
- [ ] `afterEach` 清理测试资源
- [ ] **`mock/` 目录**：为所有外部依赖实现 Mock Service
- [ ] **`mock/fixtures/`**：采集并存放真实 API 响应数据
- [ ] **`mock/README.md`**：记录数据采集来源和版本
- [ ] **`tests/setup.ts`** 中启动/关闭所有 Mock Services
- [ ] **`.env`** 中配置 Mock 端口（如 `LLM_MOCK_PORT=18080`）

## Mock Service 速查

### 外部服务 → Mock 端口约定

| 外部服务 | 默认 Mock 端口 | 环境变量 |
|----------|--------------|---------|
| LLM API（OpenAI-compatible） | 18080 | `LLM_MOCK_PORT` |
| Claude Worker（Python 服务） | 18031 | `WORKER_MOCK_PORT` |
| OpenHands API | 18090 | `OPENHANDS_MOCK_PORT` |
| GitHub API | 18091 | `GITHUB_MOCK_PORT` |
| GitLab API | 18092 | `GITLAB_MOCK_PORT` |

### 外部服务关键 API 和数据结构

> **数据来源**：Claude/Anthropic API 数据结构已通过 `tools/claude-code-proxy` Passthrough 模式
> 从真实 API 采集验证（2026-03-05）。Fixture 文件位于 `mock/fixtures/claude-api/`。

#### Claude/Anthropic Messages API（`POST /v1/messages`）✅ 已验证

本项目 Claude Worker Agent 底层通过 Claude Code CLI 与 LLM 通信，使用 **Anthropic 原生 Messages API 格式**。

**请求**：
```json
{
  "model": "claude-sonnet-4-20250514",
  "max_tokens": 4096,
  "stream": true,
  "messages": [{"role": "user", "content": "问题内容"}],
  "tools": [{
    "name": "get_weather",
    "description": "Get current weather for a location",
    "input_schema": {
      "type": "object",
      "properties": {"location": {"type": "string", "description": "City name"}},
      "required": ["location"]
    }
  }]
}
```

**响应（非流式 - 纯文本）**：
```json
{
  "id": "msg_58227d15-b989-4603-9056-1af645644a4f",
  "type": "message",
  "role": "assistant",
  "model": "claude-sonnet-4-20250514",
  "content": [
    {"type": "thinking", "thinking": "思维链内容..."},
    {"type": "text", "text": "回复文本"}
  ],
  "stop_reason": "end_turn",
  "usage": {"input_tokens": 12, "output_tokens": 171, "cache_creation_input_tokens": 0, "cache_read_input_tokens": 0}
}
```

**响应（非流式 - 工具调用）**：
```json
{
  "content": [
    {"type": "thinking", "thinking": "思维链..."},
    {"type": "text", "text": "I'll check the weather."},
    {"type": "tool_use", "id": "toolu_xxx", "name": "get_weather", "input": {"location": "Beijing"}}
  ],
  "stop_reason": "tool_use",
  "usage": {"input_tokens": 192, "output_tokens": 130}
}
```

**SSE 流式事件序列**（完整验证后的典型流）：

```
event:message_start
data:{"message":{"model":"...","id":"msg_xxx","role":"assistant","type":"message","content":[],"usage":{...}},"type":"message_start"}

event:ping
data:{"type":"ping"}

event:content_block_start               ← thinking 块
data:{"type":"content_block_start","content_block":{"type":"thinking","thinking":""},"index":0}

event:content_block_delta               ← thinking 增量
data:{"delta":{"type":"thinking_delta","thinking":"..."},"type":"content_block_delta","index":0}

event:content_block_delta               ← thinking 签名
data:{"delta":{"type":"signature_delta","signature":""},"type":"content_block_delta","index":0}

event:content_block_stop
data:{"type":"content_block_stop","index":0}

event:content_block_start               ← text 块
data:{"type":"content_block_start","content_block":{"type":"text","text":""},"index":1}

event:content_block_delta               ← text 增量
data:{"delta":{"type":"text_delta","text":"Hello World"},"type":"content_block_delta","index":1}

event:content_block_stop
data:{"type":"content_block_stop","index":1}

event:content_block_start               ← tool_use 块（仅工具调用时）
data:{"type":"content_block_start","content_block":{"name":"get_weather","input":{},"id":"toolu_xxx","type":"tool_use"},"index":2}

event:content_block_delta               ← 工具参数 JSON 增量
data:{"delta":{"partial_json":"{\"location\": \"Beijing\"}","type":"input_json_delta"},"type":"content_block_delta","index":2}

event:content_block_stop
data:{"type":"content_block_stop","index":2}

event:message_delta                     ← 结束信号 + usage
data:{"delta":{"stop_reason":"end_turn"},"type":"message_delta","usage":{"output_tokens":171}}

event:message_stop
data:{"type":"message_stop"}
```

**SSE 事件类型速查**：

| SSE event | delta.type | 说明 |
|-----------|-----------|------|
| `message_start` | — | 消息开始，含 model/id/usage |
| `ping` | — | 心跳 |
| `content_block_start` | — | 块开始（thinking/text/tool_use） |
| `content_block_delta` | `thinking_delta` | 思维链增量 |
| `content_block_delta` | `signature_delta` | 思维签名（空） |
| `content_block_delta` | `text_delta` | 文本增量 |
| `content_block_delta` | `input_json_delta` | 工具参数 JSON 增量 |
| `content_block_stop` | — | 块结束 |
| `message_delta` | — | 消息结束（含 stop_reason + usage） |
| `message_stop` | — | 流完成 |

**stop_reason 值**：`end_turn` | `tool_use` | `max_tokens` | `stop_sequence`

#### LLM API — OpenAI-compatible 格式（LangChain4j 路径）

当后端通过 LangChain4j 调用 LLM 时（非 Claude Worker 路径），使用 OpenAI-compatible 格式：

**请求** `POST /chat/completions`：
```json
{
  "model": "claude-sonnet-4-20250514",
  "messages": [
    {"role": "system", "content": "..."},
    {"role": "user", "content": "..."}
  ],
  "tools": [{"type": "function", "function": {"name": "xxx", "parameters": {...}}}],
  "temperature": 0.7,
  "stream": false
}
```

**响应（非流式）**：
```json
{
  "id": "chatcmpl-xxx",
  "object": "chat.completion",
  "choices": [{
    "index": 0,
    "message": {"role": "assistant", "content": "回复文本"},
    "finish_reason": "stop"
  }],
  "usage": {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150}
}
```

#### Claude Worker SSE（`POST /api/v1/query`）

**请求**：
```json
{
  "prompt": "用户任务描述",
  "workingDirectory": "/path/to/project",
  "sessionId": "optional-session-id",
  "model": "claude-sonnet-4-20250514",
  "apiKey": "sk-xxx",
  "baseUrl": "https://api.openai.com/v1"
}
```

**SSE 事件流**（事件类型通过 `event:` 字段区分）：
```
event: status
data: {"taskId":"task-xxx","status":"running"}

event: message
data: {"taskId":"task-xxx","type":"assistant","message":"正在分析..."}

event: tool_use
data: {"taskId":"task-xxx","tool":"Read","input":{"file_path":"/src/main.ts"}}

event: tool_result
data: {"taskId":"task-xxx","tool":"Read","output":"file content..."}

event: permission_request
data: {"taskId":"task-xxx","tool":"Edit","input":{...},"requestId":"req-xxx"}

event: result
data: {"taskId":"task-xxx","result":"任务完成","cost":{"input_tokens":1000,"output_tokens":500}}
```

#### Git Provider API

**GitHub `GET /user/repos`**：
```json
[{
  "full_name": "owner/repo",
  "name": "repo",
  "description": "desc",
  "clone_url": "https://github.com/owner/repo.git",
  "default_branch": "main",
  "html_url": "https://github.com/owner/repo"
}]
```

**GitLab `GET /api/v4/projects`**：
```json
[{
  "id": 1,
  "name": "repo",
  "path_with_namespace": "group/repo",
  "http_url_to_repo": "https://gitlab.com/group/repo.git",
  "default_branch": "main",
  "web_url": "https://gitlab.com/group/repo"
}]
```

### Fixture 数据管理

#### 共享 Fixture 目录

```
mock/fixtures/
├── claude-api/                  # ★ Claude/Anthropic Messages API（全面覆盖）
│   ├── requests/                # 请求 fixture（5 个场景）
│   ├── non-streaming/           # 非流式完整响应（9 个场景 + 7 个错误）
│   ├── streaming/               # SSE 流式事件序列 JSONL（6 个场景）
│   └── README.md                # 完整采集记录 + 格式文档
├── worker-events/               # Claude Worker SSE 事件（待采集）
└── git-provider/                # Git Provider API（待采集）
```

#### 采集进度

| Fixture | 采集方式 | 状态 |
|---------|---------|------|
| claude-api/simple-text | ✅ proxy passthrough 真实采集 | ✅ 已完成 |
| claude-api/tool-use | ✅ proxy passthrough 真实采集 | ✅ 已完成 |
| claude-api/multi-turn | 🔧 基于验证格式构造 | ✅ 已完成 |
| claude-api/parallel-tool-use | 🔧 基于验证格式构造 | ✅ 已完成 |
| claude-api/max-tokens-truncated | 🔧 基于验证格式构造 | ✅ 已完成 |
| claude-api/with-system-prompt | 🔧 基于验证格式构造 | ✅ 已完成 |
| claude-api/error-* (7 个) | 🔧 基于官方文档构造 | ✅ 已完成 |
| claude-api/error-mid-stream | 🔧 基于官方文档构造 | ✅ 已完成 |
| worker-events/* | — | ⬜ 待采集 |
| git-provider/* | — | ⬜ 待采集 |

#### 脱敏规则

- API Key → `sk-test-mock-key-xxx`
- 消息 ID → `msg_mock-{scenario}-{seq}`
- 工具调用 ID → `toolu_mock-{name}-{seq}`
- 用户名 → `test-user`
- Token → `mock-token-xxx`
