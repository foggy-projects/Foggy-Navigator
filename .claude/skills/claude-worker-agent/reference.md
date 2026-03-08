# Claude Worker Agent 技术参考

## Python Worker 模型

### QueryRequest

```python
class QueryRequest(BaseModel):
    prompt: str                          # 用户提示词
    cwd: str | None = None              # 工作目录
    session_id: str | None = None       # 恢复会话 ID
    max_turns: int | None = None        # 最大 agentic 轮次
    model: str | None = None            # 模型名称
    extra_args: dict | None = None      # 额外 CLI 参数（含 agents JSON）
    api_key: str | None = None          # Per-request API Key 覆盖
    auth_token: str | None = None       # Per-request Auth Token 覆盖
    base_url: str | None = None         # Per-request Base URL 覆盖
```

### QueryEvent (SSE)

```python
class QueryEvent(BaseModel):
    type: str               # assistant_text | tool_use | tool_result | result | error | system
    content: str | None     # 文本内容
    tool: str | None        # 工具名称
    input: dict | None      # 工具输入
    output: str | None      # 工具输出
    task_id: str            # 任务 ID
    session_id: str | None  # Claude Code 会话 ID
    cost_usd: float | None  # 费用（result 事件）
    duration_ms: int | None # 耗时（result 事件）
    input_tokens: int | None
    output_tokens: int | None
    num_turns: int | None
    model: str | None       # 模型名称
    error: str | None       # 错误信息
```

### Settings

```python
class Settings(BaseSettings):
    port: int = 3031
    host: str = "0.0.0.0"
    worker_token: str = ""
    worker_name: str = ""
    allowed_cwds: list[str] = []
    max_concurrent_tasks: int = 3
    anthropic_api_key: str = ""
    anthropic_auth_token: str = ""
    anthropic_base_url: str = ""

    model_config = SettingsConfigDict(env_prefix="AGENT_WORKER_")
```

### HealthResponse

```python
class HealthResponse(BaseModel):
    hostname: str
    version: str
    active_tasks: int
    claude_cli_available: bool
    worker_name: str
```

### SessionInfo

```python
class SessionInfo(BaseModel):
    session_id: str
    cwd: str
    created_at: datetime
    updated_at: datetime
    slug: str | None = None
    git_branch: str | None = None
```

### GitInfoResponse

```python
class GitInfoResponse(BaseModel):
    path: str
    is_git_repo: bool
    branch: str | None = None
    remote_url: str | None = None
    status: str = "unknown"      # clean | dirty | unknown
    provider: str = "OTHER"      # GITHUB | GITLAB | GITEE | OTHER
```

### CreateWorktreeRequest

```python
class CreateWorktreeRequest(BaseModel):
    repo_path: str                    # 主仓库路径
    branch: str                       # 要检出的分支
    worktree_path: str | None = None  # 可选自定义路径，默认自动生成
```

### WorktreeInfo

```python
class WorktreeInfo(BaseModel):
    path: str
    branch: str
    is_main: bool
```

### CreateWorktreeResponse

```python
class CreateWorktreeResponse(BaseModel):
    path: str
    branch: str
```

### RemoveWorktreeRequest

```python
class RemoveWorktreeRequest(BaseModel):
    worktree_path: str
```

---

## Java Entity

### ClaudeTaskEntity

```java
@Entity
@Table(name = "claude_tasks")
public class ClaudeTaskEntity {
    Long id;
    String taskId;           // unique, 12 chars UUID prefix
    String sessionId;        // Foggy Navigator session ID
    String workerId;
    String userId;
    String prompt;
    String cwd;
    String directoryId;      // nullable
    String status;           // PENDING | RUNNING | COMPLETED | FAILED | ABORTED
    String claudeSessionId;  // Claude Code local session ID
    BigDecimal costUsd;
    Long inputTokens;
    Long outputTokens;
    Long durationMs;
    Integer numTurns;
    String model;
    String errorMessage;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}
```

### ConversationConfigEntity

```java
@Entity
@Table(name = "claude_conversation_configs")
public class ConversationConfigEntity {
    Long id;
    String sessionId;        // unique, Foggy Navigator session ID
    String workerId;
    String userId;
    Boolean pinned;          // default false
    LocalDateTime pinnedAt;  // 置顶时间（用于排序）
    String customTitle;      // nullable, VARCHAR(255)
    String authMode;         // SUBSCRIPTION | API_KEY | CUSTOM_ENDPOINT
    String authToken;        // encrypted via CredentialEncryptor
    String baseUrl;          // nullable, VARCHAR(512)
    LocalDateTime authBoundAt; // non-null = immutable
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}
```

### WorkingDirectoryEntity

```java
@Entity
@Table(name = "claude_working_directories")
public class WorkingDirectoryEntity {
    Long id;
    String directoryId;      // unique
    String workerId;
    String userId;
    String projectName;
    String path;
    String gitBranch;
    String gitRemoteUrl;
    String gitProvider;      // GITHUB | GITLAB | GITEE | OTHER
    String gitStatus;        // clean | dirty | unknown
    String agentTeamsConfig; // JSON string
    // --- PROJECT & Worktree 字段 ---
    String directoryType = "STANDARD";  // STANDARD | PROJECT
    String parentProjectId;  // nullable, 指向 PROJECT 的 directoryId
    String projectTaskPrompt; // nullable, TEXT, PROJECT 专用任务分配 prompt
    Boolean worktree = false; // 是否 git worktree 创建的临时目录
    String sourceDirectoryId; // nullable, worktree 来源目录
    LocalDateTime lastSyncedAt;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}
```

### ClaudeWorkerEntity

```java
@Entity
@Table(name = "claude_workers")
public class ClaudeWorkerEntity {
    Long id;
    String workerId;         // unique
    String userId;
    String name;
    String baseUrl;
    String authToken;        // encrypted, for Worker API auth
    String authMode;         // SUBSCRIPTION | API_KEY | CUSTOM_ENDPOINT
    String status;           // ONLINE | OFFLINE | UNKNOWN
    String hostname;
    String workerVersion;
    LocalDateTime lastHeartbeat;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}
```

---

## Java DTO

### TaskDTO

```java
@Builder
public class TaskDTO {
    String taskId;
    String sessionId;
    String workerId;
    String prompt;
    String cwd;
    String directoryId;
    String status;
    String claudeSessionId;
    BigDecimal costUsd;
    Long inputTokens;
    Long outputTokens;
    Long durationMs;
    Integer numTurns;
    String model;
    String errorMessage;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}
```

### ConversationConfigDTO

```java
@Builder
public class ConversationConfigDTO {
    String sessionId;
    boolean pinned;
    LocalDateTime pinnedAt;
    String customTitle;
    String authMode;
    boolean authBound;        // authBoundAt != null
    String baseUrl;
    String maskedAuthToken;   // 脱敏: sk-ant****xxxx
}
```

---

## Java Form

### CreateTaskForm

```java
public class CreateTaskForm {
    String workerId;       // required
    String prompt;         // required
    String cwd;            // optional (从 directoryId 自动解析)
    String directoryId;    // optional
    String model;          // optional
    Integer maxTurns;      // optional
    String agentTeamsJson; // optional
}
```

### ResumeTaskForm

```java
public class ResumeTaskForm {
    String workerId;          // required
    String claudeSessionId;   // required
    String prompt;            // required
    String cwd;
    String directoryId;
    String sessionId;         // optional, 复用已有 Foggy session
    String model;
    Integer maxTurns;
    String agentTeamsJson;
}
```

### BindAuthForm

```java
public class BindAuthForm {
    String authMode;   // SUBSCRIPTION | API_KEY | CUSTOM_ENDPOINT
    String authToken;  // raw token (will be encrypted)
    String baseUrl;    // optional
}
```

---

## Spring 内部事件

### ClaudeTaskStartEvent

```java
public class ClaudeTaskStartEvent extends ApplicationEvent {
    String taskId;
    String sessionId;       // Foggy Navigator session ID
    String workerId;
    String userId;
    String prompt;
    String cwd;
    String claudeSessionId; // nullable (new task = null, resume = existing)
    String model;
    Integer maxTurns;
    String agentTeamsJson;
    String apiKey;          // decrypted per-conversation auth
    String authToken;       // decrypted per-conversation auth
    String baseUrl;
}
```

### WorkerEvent (SSE 反序列化)

```java
public class WorkerEvent {
    String type;            // system | assistant_text | tool_use | tool_result | result | error
    String content;
    String tool;
    Map<String, Object> input;
    String output;
    @JsonProperty("task_id")  String taskId;
    @JsonProperty("session_id") String sessionId;
    String result;
    @JsonProperty("cost_usd") BigDecimal costUsd;
    @JsonProperty("duration_ms") Long durationMs;
    @JsonProperty("input_tokens") Long inputTokens;
    @JsonProperty("output_tokens") Long outputTokens;
    @JsonProperty("num_turns") Integer numTurns;
    String model;
    String error;
}
```

---

## SDK 消息类型映射

### event_mapper.py 函数

| 函数 | 输入 | 输出 type |
|------|------|-----------|
| `map_assistant_text()` | TextBlock from AssistantMessage | `assistant_text` |
| `map_tool_use()` | ToolUseBlock | `tool_use` |
| `map_tool_result()` | ToolResultBlock | `tool_result` |
| `map_result()` | ResultMessage | `result` |
| `map_system()` | SystemMessage | `system` |
| `map_error()` | Exception / cancel | `error` |

### SDK 消息类型 → event_mapper

```
claude-agent-sdk 消息          → event_mapper 函数
───────────────────────────────────────────────
AssistantMessage
  ├── TextBlock                → map_assistant_text()
  ├── ToolUseBlock             → map_tool_use()
  └── ToolResultBlock          → map_tool_result()
ResultMessage                  → map_result()
SystemMessage                  → map_system()
Exception                      → map_error()
asyncio.CancelledError         → map_error()
```

---

## 前端类型（TypeScript）

```typescript
// packages/navigator-frontend/src/types/index.ts

interface ClaudeWorker {
  workerId: string
  name: string
  baseUrl: string
  authMode: 'SUBSCRIPTION' | 'API_KEY' | 'CUSTOM_ENDPOINT'
  status: 'ONLINE' | 'OFFLINE' | 'UNKNOWN'
  hostname?: string
  workerVersion?: string
  lastHeartbeat?: string
  createdAt: string
}

interface ClaudeTask {
  taskId: string
  sessionId: string
  workerId: string
  prompt: string
  cwd?: string
  directoryId?: string
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'ABORTED'
  claudeSessionId?: string
  costUsd?: number
  inputTokens?: number
  outputTokens?: number
  durationMs?: number
  numTurns?: number
  model?: string
  errorMessage?: string
  createdAt: string
  updatedAt: string
}

interface WorkingDirectory {
  directoryId: string
  workerId: string
  projectName: string
  path: string
  gitBranch?: string
  gitRemoteUrl?: string
  gitProvider?: 'GITHUB' | 'GITLAB' | 'GITEE' | 'OTHER'
  gitStatus?: 'clean' | 'dirty' | 'unknown'
  agentTeamsConfig?: string
  // --- PROJECT & Worktree 字段 ---
  directoryType?: 'STANDARD' | 'PROJECT'
  parentProjectId?: string
  projectTaskPrompt?: string
  worktree?: boolean
  sourceDirectoryId?: string
  lastSyncedAt?: string
  createdAt: string
  updatedAt: string
}

interface ConversationConfig {
  sessionId: string
  pinned: boolean
  pinnedAt?: string
  customTitle?: string
  authMode?: string
  authBound: boolean
  baseUrl?: string
  maskedAuthToken?: string
}

interface WorkerSession {
  session_id: string
  cwd: string
  created_at: string
  updated_at: string
  slug?: string
  git_branch?: string
}
```

---

## 前端 API 函数

```typescript
// packages/navigator-frontend/src/api/claudeWorker.ts

// Worker
listWorkers(): Promise<ClaudeWorker[]>
getWorker(workerId): Promise<ClaudeWorker>
registerWorker(form): Promise<ClaudeWorker>
updateWorker(workerId, form): Promise<ClaudeWorker>
deleteWorker(workerId): Promise<void>
triggerHealthCheck(workerId): Promise<ClaudeWorker>

// Directory
listDirectoriesByWorker(workerId): Promise<WorkingDirectory[]>
createDirectory(form): Promise<WorkingDirectory>        // form 含 directoryType?, parentProjectId?
updateDirectory(directoryId, form): Promise<WorkingDirectory>  // form 含 projectTaskPrompt?, parentProjectId?
deleteDirectory(directoryId): Promise<void>
syncDirectoryGitInfo(directoryId): Promise<WorkingDirectory>
listChildDirectories(directoryId): Promise<WorkingDirectory[]>  // PROJECT 子目录
listSkills(directoryId): Promise<SkillInfo[]>

// Worktree
createWorktree(directoryId, branch): Promise<WorkingDirectory>
removeWorktree(directoryId): Promise<void>

// Task
createTask(form): Promise<ClaudeTask>
resumeTask(form): Promise<ClaudeTask>
getTask(taskId): Promise<ClaudeTask>
listTasks(): Promise<ClaudeTask[]>
listTasksPaged(page, size): Promise<Page<ClaudeTask>>
listTasksByDirectory(directoryId): Promise<ClaudeTask[]>
abortTask(taskId): Promise<{taskId, status}>
deleteTask(taskId): Promise<{taskId, deleted}>

// Worker Sessions
listWorkerSessions(workerId): Promise<WorkerSession[]>
getWorkerSessionMessages(workerId, sessionId): Promise<Message[]>
syncWorkerSessions(workerId): Promise<{synced, total}>

// Conversation Config
updateConversationPin(sessionId, pinned): Promise<ConversationConfig>
updateConversationTitle(sessionId, title): Promise<ConversationConfig>
bindConversationAuth(sessionId, form): Promise<ConversationConfig>
listConversationConfigs(sessionIds): Promise<ConversationConfig[]>
batchBindConversationAuth(form): Promise<{bound, total}>
```

---

## useClaudeWorker Composable

```typescript
// packages/navigator-frontend/src/composables/useClaudeWorker.ts

const {
  // 状态
  workers,              // ref<ClaudeWorker[]>
  tasks,                // ref<ClaudeTask[]>
  directories,          // ref<WorkingDirectory[]>
  loading,              // ref<boolean>
  taskPage, taskSize, taskTotal,
  onlineWorkers,        // computed: status === 'ONLINE'
  conversationConfigs,  // ref<Map<string, ConversationConfig>>

  // Worker 操作
  loadWorkers, registerWorker, updateWorker, deleteWorker, refreshWorkerStatus,

  // Task 操作
  loadTasks, loadTasksPage, createTask, resumeTask, abortTask, deleteTask,

  // Directory 操作 (createDirectory form 含 directoryType?, parentProjectId?)
  loadDirectories, createDirectory, deleteDirectory, syncGitInfo, syncSessions,

  // Conversation Config 操作
  loadConversationConfigs, togglePin, setTitle, bindAuth, batchBindAuth,
} = useClaudeWorker()
```
