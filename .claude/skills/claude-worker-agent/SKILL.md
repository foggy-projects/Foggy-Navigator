---
name: claude-worker-agent
description: Claude Worker Agent 全栈开发指导（Java 后端 + Python Worker + SDK 集成）。当用户需要开发 claude-worker-agent 模块的功能、修改任务流程、调整 Worker 通信、配置 Agent Teams、处理 Auth 绑定时使用。触发词：/cw, /claude-worker, 提及"Claude Worker"、"Agent Worker"、"SDK集成"、"任务流程"。
---

# Claude Worker Agent 全栈开发指导

为 `addons/claude-worker-agent`（Java 后端）和 `tools/claude-agent-worker`（Python Worker）提供全栈开发指导。

## 使用场景

当用户需要以下操作时激活：
- 修改任务创建/恢复/中止流程
- 调整 Worker SSE 流转发与事件映射
- 配置 Agent Teams（子 Agent 分工）
- 修改 per-conversation Auth 绑定逻辑
- 添加新的 Worker 端点或 Java 端 API
- 升级 Claude Agent SDK 或修改 SDK 包装层
- 调试任务执行失败、Auth 不匹配、SSE 断流等问题

## 架构概览

```
前端 (ClaudeWorkerView.vue)
  │  POST /api/v1/claude-tasks { prompt, workerId, directoryId, agentTeamsJson }
  ▼
Java 后端 (addons/claude-worker-agent)
  │  ClaudeTaskController → ClaudeTaskService → ApplicationEventPublisher
  │  发布 ClaudeTaskStartEvent
  ▼
WorkerStreamRelay (@Async @EventListener)
  │  ClaudeWorkerClient.streamQuery() → WebClient SSE
  ▼
Python Worker (tools/claude-agent-worker)
  │  FastAPI POST /api/v1/query → SdkWrapper.run_query()
  │  event_mapper → SSE 事件流
  ▼
Claude Agent SDK (claude-agent-sdk)
  │  query(prompt, ClaudeAgentOptions)
  │  支持 agents (AgentDefinition) / setting_sources / env
  ▼
Claude Code CLI (SDK 内置)
  │  多个 Agent 子进程并行
  ▼
SSE 事件流回传: Worker → Java → 前端
```

## 模块结构

### Java 后端

```
addons/claude-worker-agent/
├── src/main/java/com/foggy/navigator/claude/worker/
│   ├── controller/
│   │   ├── ClaudeWorkerController.java     # Worker CRUD API
│   │   ├── ClaudeTaskController.java       # 任务 + 会话配置 API
│   │   └── WorkingDirectoryController.java # 工作目录 API
│   ├── service/
│   │   ├── ClaudeWorkerService.java        # Worker 管理
│   │   ├── ClaudeTaskService.java          # 任务生命周期（核心）
│   │   ├── WorkerStreamRelay.java          # SSE 桥接（核心）
│   │   ├── ConversationConfigService.java  # 会话配置（置顶/标题/Auth）
│   │   ├── WorkingDirectoryService.java    # 工作目录管理
│   │   └── WorkerHealthChecker.java        # 定时健康检查
│   ├── client/
│   │   ├── ClaudeWorkerClient.java         # Worker HTTP/SSE 客户端
│   │   └── ClaudeWorkerClientFactory.java  # 按 Worker 管理客户端实例
│   ├── model/
│   │   ├── entity/
│   │   │   ├── ClaudeWorkerEntity.java
│   │   │   ├── ClaudeTaskEntity.java
│   │   │   ├── WorkingDirectoryEntity.java
│   │   │   └── ConversationConfigEntity.java
│   │   ├── dto/
│   │   │   ├── WorkerDTO.java
│   │   │   ├── TaskDTO.java
│   │   │   ├── WorkingDirectoryDTO.java
│   │   │   └── ConversationConfigDTO.java
│   │   ├── form/
│   │   │   ├── CreateTaskForm.java
│   │   │   ├── ResumeTaskForm.java
│   │   │   ├── UpdatePinForm.java
│   │   │   ├── UpdateTitleForm.java
│   │   │   ├── BindAuthForm.java
│   │   │   └── BatchBindAuthForm.java
│   │   └── event/
│   │       ├── ClaudeTaskStartEvent.java   # Spring 内部事件
│   │       └── WorkerEvent.java            # Worker SSE 事件 POJO
│   ├── repository/
│   │   ├── ClaudeWorkerRepository.java
│   │   ├── ClaudeTaskRepository.java
│   │   ├── WorkingDirectoryRepository.java
│   │   └── ConversationConfigRepository.java
│   ├── spi/
│   │   └── ClaudeWorkerFacadeImpl.java     # SPI 实现（跨 Agent 任务）
│   └── config/
│       └── ClaudeWorkerAutoConfiguration.java
```

### Python Worker

```
tools/claude-agent-worker/
├── src/agent_worker/
│   ├── main.py                  # FastAPI 入口
│   ├── config.py                # Settings (pydantic-settings)
│   ├── models.py                # Pydantic 模型
│   ├── auth.py                  # Bearer token 验证
│   ├── claude/
│   │   ├── sdk_wrapper.py       # SDK 封装（核心）
│   │   ├── event_mapper.py      # SDK 消息 → SSE 事件映射
│   │   └── session_scanner.py   # JSONL 会话扫描
│   └── routes/
│       ├── query.py             # POST /api/v1/query（SSE 流）
│       ├── sessions.py          # 会话列表 / 消息读取
│       ├── auth.py              # GET /api/v1/auth-config
│       ├── git_info.py          # GET /api/v1/git-info
│       ├── skills.py            # GET /api/v1/skills
│       └── health.py            # GET /health
├── pyproject.toml               # 依赖：claude-agent-sdk>=0.1.37
└── start.ps1 / stop.ps1         # 启停脚本
```

## 核心流程

### 任务创建流程

```
1. ClaudeTaskController.createTask()
2. ClaudeTaskService.createTask()
   a. 验证 Worker 归属和状态
   b. 如果有 directoryId → 解析 cwd 和 agentTeamsJson
   c. 创建 Foggy Session (sessionManager.createSession)
   d. 添加 USER 消息
   e. 持久化 ClaudeTaskEntity (status=RUNNING)
   f. resolveAuth() → 解析 per-conversation auth
   g. 发布 ClaudeTaskStartEvent
3. WorkerStreamRelay.onTaskStart() (@Async)
   a. 发送 SESSION_START AgentMessage
   b. 创建 ClaudeWorkerClient
   c. client.streamQuery() → SSE 流订阅
   d. 每个 SSE 事件 → relayEvent() → AgentMessage → eventPublisher
   e. result 事件 → taskService.completeTask()
   f. error 事件 → taskService.failTask()
```

### Auth 解析流程

```
resolveAuth(sessionId, workerId, userId):
  1. getOrCreate ConversationConfigEntity
  2. if authBoundAt != null:
       → 解密 authToken → 返回 [apiKey, authToken, baseUrl]
  3. else:
       → client.getAuthConfig() → 自动绑定
       → 返回 Worker 的原始 auth 值
```

### SSE 事件映射

Java `WorkerEvent.type` → `AgentMessage.MessageType`:

| Worker 事件 | AgentMessage 类型 | 说明 |
|-------------|-------------------|------|
| `system` | `SESSION_START` | 任务启动，携带 claudeSessionId |
| `assistant_text` | `TEXT_CHUNK` | Claude 回复文本片段 |
| `tool_use` | `TOOL_CALL_START` | 工具调用（工具名 + 输入） |
| `tool_result` | `TOOL_CALL_RESULT` | 工具执行结果 |
| `result` | `TEXT_COMPLETE` | 任务完成，携带 cost/tokens/model |
| `error` | `ERROR` | 任务失败 |

## 关键组件详解

### SdkWrapper (Python)

SDK 封装层，同时支持 `claude-agent-sdk`（推荐）和旧的 `claude-code-sdk`。

**SDK 检测优先级**：
```python
# 1. 优先加载 claude-agent-sdk (bundles own CLI)
from claude_agent_sdk import query, ClaudeAgentOptions, AgentDefinition
_use_agent_sdk = True

# 2. 回退到 claude-code-sdk (deprecated, uses system CLI)
from claude_code_sdk import query, ClaudeCodeOptions
```

**环境变量注入** (`_build_env`):
```python
env = {}
# 防止嵌套会话检测（Worker 在 Claude Code 终端内运行时）
if os.environ.get("CLAUDECODE"):
    env["CLAUDECODE"] = ""
# Per-request auth 覆盖（优先）→ 全局配置（回退）
key = api_key or settings.anthropic_api_key
token = auth_token or settings.anthropic_auth_token
url = base_url or settings.anthropic_base_url
```

**Agent Teams 配置** (`_apply_agents_config`):
```python
# 新 SDK: JSON string → AgentDefinition objects → options.agents
agents_raw = json.loads(agents_value)
agents = {
    name: AgentDefinition(**{k: v for k, v in defn.items() if k in valid_fields})
    for name, defn in agents_raw.items()
}
options_kwargs["agents"] = agents

# 旧 SDK: 保持在 extra_args 中
options_kwargs["extra_args"] = {"agents": agents_json_string}
```

**SDK 调用** (`run_query`):
```python
options_kwargs = {"cwd": cwd}
if _use_agent_sdk:
    options_kwargs["setting_sources"] = ["user", "project", "local"]  # 加载 CLAUDE.md
    self._apply_agents_config(extra_args, options_kwargs)

options = _options_cls(**options_kwargs)
async for message in _query_fn(prompt=prompt, options=options):
    # AssistantMessage → TextBlock / ToolUseBlock / ToolResultBlock
    # ResultMessage → cost, tokens, session_id
    # SystemMessage → init (session_id), system events
```

### ClaudeWorkerClient (Java)

WebClient-based HTTP/SSE 客户端，与 Python Worker 通信。

**关键方法**：

| 方法 | HTTP | 说明 |
|------|------|------|
| `streamQuery()` | POST /api/v1/query | SSE 流式查询（含 per-request auth） |
| `abortTask()` | POST /api/v1/query/{id}/abort | 中止任务 |
| `healthCheck()` | GET /health | 健康检查 |
| `listSessions()` | GET /api/v1/sessions | 列出本地会话 |
| `getSessionMessages()` | GET /api/v1/sessions/{id}/messages | 读取会话历史 |
| `syncSessions()` | POST /api/v1/sessions/sync | 重新扫描 JSONL |
| `getAuthConfig()` | GET /api/v1/auth-config | 获取 Worker auth 配置 |
| `getGitInfo()` | GET /api/v1/git-info | 查询 Git 信息 |
| `listSkills()` | GET /api/v1/skills | 获取项目技能列表 |

**streamQuery 请求体**：
```json
{
  "prompt": "用户提示词",
  "cwd": "/path/to/project",
  "session_id": "resume-session-id",
  "model": "claude-opus-4-6",
  "max_turns": 10,
  "extra_args": { "agents": "{...json...}" },
  "api_key": "sk-...",
  "auth_token": "...",
  "base_url": "https://..."
}
```

### WorkerStreamRelay (Java)

SSE 桥接组件，监听 `ClaudeTaskStartEvent`，消费 Worker SSE 流，转为 `AgentMessage` 发布。

**关键行为**：
- `@Async("sessionEventExecutor")` — 异步执行，不阻塞主线程
- `activeStreams` (`ConcurrentHashMap`) — 跟踪活跃流，支持 abort
- `detectedModel` / `detectedClaudeSessionId` — 从 SSE 事件中动态提取
- `result` 事件 → `taskService.completeTask()` + `TaskCompletionEvent`
- `error` 事件 → `taskService.failTask()` + `TaskCompletionEvent`

### ConversationConfigService (Java)

会话级配置管理（置顶、自定义标题、Auth 绑定）。

**Auth 绑定规则**：
- `authBoundAt != null` → 已绑定，**不可修改**
- 手动绑定：`bindAuth()` — 前端用户填写
- 自动绑定：`bindAuthFromWorker()` — 从 Worker 全局配置写入
- 同步绑定：`syncLocalSessions()` 时自动绑定 Worker auth
- Token 加密：`CredentialEncryptor.encrypt/decrypt`
- DTO 返回脱敏：`maskToken()` 保留前6后4位

## Agent Teams 配置

### 数据流

```
前端: WorkingDirectory.agentTeamsConfig (JSON string)
  → CreateTaskForm.agentTeamsJson
  → ClaudeTaskStartEvent.agentTeamsJson
  → ClaudeWorkerClient.streamQuery(agentTeamsJson)
    body.extra_args = { "agents": agentTeamsJson }
  → Python QueryRequest.extra_args.agents
  → SdkWrapper._apply_agents_config()
    → ClaudeAgentOptions(agents={name: AgentDefinition(...)})
  → Claude Code SDK 启动子 Agent 进程
```

### 配置格式

```json
{
  "reviewer": {
    "description": "Code reviewer for quality assurance",
    "prompt": "You are a code reviewer. Review code changes..."
  },
  "tester": {
    "description": "Test writer",
    "prompt": "You write comprehensive unit tests..."
  }
}
```

### 前端 useTeams 开关

`ClaudeWorkerView.vue` 中的 `taskForm.useTeams` 控制是否传递 Agent Teams 配置：
```typescript
if (taskForm.value.useTeams && selectedDirectory.value?.agentTeamsConfig) {
  agentTeamsJson = selectedDirectory.value.agentTeamsConfig
}
```

## Worker 配置

### 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `AGENT_WORKER_PORT` | 3031 | 监听端口 |
| `AGENT_WORKER_HOST` | 0.0.0.0 | 监听地址 |
| `AGENT_WORKER_WORKER_TOKEN` | "" | Bearer token 认证 |
| `AGENT_WORKER_WORKER_NAME` | "" | Worker 名称 |
| `AGENT_WORKER_ALLOWED_CWDS` | [] | 允许的工作目录白名单 |
| `AGENT_WORKER_MAX_CONCURRENT_TASKS` | 3 | 最大并发任务数 |
| `AGENT_WORKER_ANTHROPIC_API_KEY` | "" | Anthropic API Key |
| `AGENT_WORKER_ANTHROPIC_AUTH_TOKEN` | "" | Anthropic Auth Token |
| `AGENT_WORKER_ANTHROPIC_BASE_URL` | "" | 自定义 API 端点 |

### Auth 模式

| 模式 | 环境变量 | 说明 |
|------|----------|------|
| `SUBSCRIPTION` | `ANTHROPIC_AUTH_TOKEN` | Claude Pro/Max 订阅 |
| `API_KEY` | `ANTHROPIC_API_KEY` | API Key 直接付费 |
| `CUSTOM_ENDPOINT` | `ANTHROPIC_API_KEY` + `ANTHROPIC_BASE_URL` | 自定义端点 |

## API 端点参考

### Java 后端 API

#### 任务管理

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/v1/claude-tasks` | 创建任务 |
| POST | `/api/v1/claude-tasks/resume` | 恢复任务 |
| GET | `/api/v1/claude-tasks/{taskId}` | 获取任务 |
| GET | `/api/v1/claude-tasks` | 列出所有任务 |
| GET | `/api/v1/claude-tasks/page` | 分页列出任务 |
| POST | `/api/v1/claude-tasks/{taskId}/abort` | 中止任务 |
| DELETE | `/api/v1/claude-tasks/{taskId}` | 删除任务 |
| GET | `/api/v1/claude-tasks/directory/{directoryId}` | 按目录列出任务 |

#### Worker 会话

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/claude-tasks/worker/{workerId}/sessions` | 列出 Worker 本地会话 |
| GET | `/api/v1/claude-tasks/worker/{workerId}/sessions/{sessionId}/messages` | 读取会话历史 |
| POST | `/api/v1/claude-tasks/worker/{workerId}/sessions/sync` | 同步本地会话 |

#### 会话配置

| 方法 | 端点 | 说明 |
|------|------|------|
| PATCH | `/api/v1/claude-tasks/conversations/{sessionId}/pin` | 置顶/取消置顶 |
| PATCH | `/api/v1/claude-tasks/conversations/{sessionId}/title` | 设置标题 |
| POST | `/api/v1/claude-tasks/conversations/{sessionId}/bind-auth` | 绑定 Auth |
| POST | `/api/v1/claude-tasks/conversations/batch-bind-auth` | 批量绑定 Auth |
| GET | `/api/v1/claude-tasks/conversation-configs?sessionIds=` | 批量获取配置 |

### Python Worker API

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/v1/query` | 流式查询（SSE） |
| POST | `/api/v1/query/{task_id}/abort` | 中止查询 |
| GET | `/api/v1/sessions` | 列出已知会话 |
| GET | `/api/v1/sessions/{session_id}/messages` | 读取会话历史 |
| POST | `/api/v1/sessions/sync` | 重新扫描 JSONL |
| GET | `/api/v1/auth-config` | 获取当前 auth 配置 |
| GET | `/api/v1/git-info?path=` | 查询 Git 仓库信息 |
| GET | `/api/v1/skills?cwd=` | 获取项目技能列表 |
| GET | `/health` | 健康检查 |

## 常见问题与踩坑

### "Claude Code cannot be launched inside another Claude Code session"

**原因**：Worker 在 Claude Code 终端内启动，继承了 `CLAUDECODE=1` 环境变量。
**修复**：`_build_env()` 中清空该变量：
```python
if os.environ.get("CLAUDECODE"):
    env["CLAUDECODE"] = ""
```

### claude-code-sdk vs claude-agent-sdk

| | claude-code-sdk (deprecated) | claude-agent-sdk (推荐) |
|--|---|---|
| 包名 | `claude-code-sdk` | `claude-agent-sdk` |
| Options | `ClaudeCodeOptions` | `ClaudeAgentOptions` |
| CLI | 依赖系统安装的 `claude` | 内置 CLI（~73MB） |
| Agent Teams | 通过 `extra_args` JSON | 一等公民 `agents` 参数 |
| Setting Sources | 不支持 | `setting_sources` 加载 CLAUDE.md |

### per-request auth 覆盖优先级

```
Per-request (from ConversationConfig)
  ↓ 如果为空
Worker 全局配置 (settings.anthropic_*)
  ↓ 如果为空
Claude Code CLI 默认行为
```

### SSE 事件中的 session_id

- `system` 事件（subtype=init）首先返回 `session_id`
- 后续 `assistant_text`、`tool_use` 等事件不一定带 session_id
- `result` 事件通过 `ResultMessage.session_id` 确认最终 session_id
- Java 端 `detectedClaudeSessionId` (AtomicReference) 逐步积累

### 任务超时

- `ClaudeTaskService.checkTimeoutTasks()` 每 5 分钟检查
- RUNNING 超过 2 小时 → 标记 FAILED
- Worker 端 `max_concurrent_tasks` 默认 3，超限返回 429

## 开发模式

### 创建新 Entity

```java
@Entity
@Table(name = "claude_xxx", indexes = {
    @Index(name = "idx_xxx_user", columnList = "userId")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class XxxEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(unique = true, nullable = false)
    private String xxxId;
    private String userId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        createdAt = updatedAt = LocalDateTime.now();
    }
    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
```

### 添加新 Worker 端点

1. 在 `models.py` 添加 Request/Response 模型
2. 在 `routes/` 新建或扩展路由模块
3. 在 `main.py` 注册路由 `app.include_router(xxx.router)`
4. 在 `ClaudeWorkerClient.java` 添加对应方法
5. 在 Controller 中调用 client 方法

### 添加新事件类型

1. Python: `event_mapper.py` 添加 `map_xxx()` 函数
2. Python: `sdk_wrapper.py` 的 `run_query()` 添加消息处理分支
3. Java: `WorkerEvent.java` 确认字段映射
4. Java: `WorkerStreamRelay.relayEvent()` 添加 case 分支
5. Java: 选择合适的 `MessageType`

## 常用命令

```bash
# Java 后端编译
mvn compile -pl addons/claude-worker-agent -am -DskipTests

# 后端全量编译+启动
powershell -ExecutionPolicy Bypass -File start-launcher.ps1

# Python Worker 启动
powershell -ExecutionPolicy Bypass -File tools/claude-agent-worker/start.ps1

# Python Worker 停止
powershell -ExecutionPolicy Bypass -File tools/claude-agent-worker/stop.ps1

# 安装/升级 SDK
pip install --upgrade claude-agent-sdk

# 前端编译（验证类型）
cd packages/navigator-frontend && pnpm exec vite build
```

## 参考文件

详细的技术参考请查看：
- [reference.md](./reference.md) - Pydantic 模型、Java Entity/DTO、SSE 事件格式
- [Agent Teams 使用指南](../../../docs/02-modules/claude-agent-teams-guide.md) - 用户配置指南
