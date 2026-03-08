# 任务重新同步功能（Task Resync）

## 需求概述

在任务卡片中新增 **「重新同步」** 按钮。点击后，后端智能判断 Worker 侧 Claude Code CLI 的存活状态，采取不同恢复策略：

- **CLI 还活着** → 重新连接 SSE 事件流（复用现有 reconnect 机制）
- **CLI 已退出** → 从 Worker 的 JSONL 会话记录拉取完整对话，同步回平台

两种策略合并为一个端点，前端一键操作。

---

## 问题背景

### 会话不同步场景

| # | 场景 | 原因 | 频率 |
|---|------|------|------|
| 1 | **Task Stalled** | Java SSE 中断超 10min（heartbeat timeout），标记 stalled 但 Worker CLI 可能仍在执行或已完成 | 高 |
| 2 | **Java 后端重启** | `WorkerStreamRelay.lastAckedSeq` 在内存中，重启后以 ack_seq=0 重连成功但部分消息可能漏持久化 | 中 |
| 3 | **网络抖动** | SSE 流中断，Worker 的 EventBroadcast 保存了完整事件但 Java 未收到 | 中 |
| 4 | **SYNCED 会话** | 通过"同步会话"导入的历史任务，Java `session_messages` 表完全没有消息内容 | 100% |
| 5 | **Reconciler 误判 FAILED** | `DEAD_CLI_MISS_THRESHOLD=3` 约 3min 后将任务标记 FAILED，但 CLI 实际只是暂时检测不到（macOS process.title 覆盖问题） | 低 |
| 6 | **终端直接 resume** | 用户通过 SSH 终端直接 `claude --resume`，Worker JSONL 有记录但 Java 无感知 | 低 |

### 现有机制的局限性

**现有 reconnect（`POST /{taskId}/reconnect`）**：
- 前置条件：仅允许 `status=FAILED` 的任务（Controller line 92）
- 流程：`resetToRunning()` → `streamRelay.reconnectTask()` → 通过 `subscribeToTask(taskId, ackSeq)` 重连 SSE
- **问题**：如果 Worker 端的 task 已经不在 `task_registry` 中（CLI 已退出且事件已被清理），reconnect 会失败且不会同步 JSONL 中的历史消息

**现有 TaskStateReconciler**：
- 每 60s 自动检测 seq 缺口并触发 reconnect
- 3 次检测 CLI 已死后标记 FAILED
- **问题**：标记 FAILED 后不会主动从 JSONL 补齐消息

---

## 解决方案

### 一、合并端点设计

```
POST /api/v1/claude-tasks/{taskId}/resync
```

后端自动探测状态，返回统一结果：

```
┌─────────────────────────┐
│ 用户点击「重新同步」       │
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│ 1. Worker 是否可达？      │──── 否 ────▶ 返回 WORKER_UNREACHABLE
└───────────┬─────────────┘
            │ 是
            ▼
┌─────────────────────────┐
│ 2. Worker task_registry  │
│    中有该 task？          │──── 是 ────▶ [策略A] 重新连接 SSE
└───────────┬─────────────┘              (resetToRunning + reconnect)
            │ 否
            ▼
┌─────────────────────────┐
│ 3. CLI 进程还活着？       │──── 是 ────▶ [策略A] 重新连接 SSE
│  (进程列表匹配 foggy_     │              (可能需要新建 task entry)
│   task_id)               │
└───────────┬─────────────┘
            │ 否
            ▼
┌─────────────────────────┐
│ 4. JSONL 有会话记录？     │──── 否 ────▶ 返回 NO_SESSION_DATA
│  (claudeSessionId)       │
└───────────┬─────────────┘
            │ 是
            ▼
┌─────────────────────────┐
│ [策略B] 消息同步          │
│ 从 Worker JSONL 拉取消息  │
│ 与平台侧对比，补齐缺失    │
└─────────────────────────┘
```

### 二、API 规格

#### 请求

```
POST /api/v1/claude-tasks/{taskId}/resync
Content-Type: application/json
Authorization: Bearer <jwt>
```

无请求体。

#### 响应

```json
{
  "code": 0,
  "data": {
    "action": "RECONNECTED | MESSAGES_SYNCED | ALREADY_ALIGNED | NO_SESSION_DATA | WORKER_UNREACHABLE",

    "cliStatus": {
      "alive": false,
      "workerReachable": true,
      "taskInRegistry": false,
      "source": "process_list"
    },

    "messageSync": {
      "platformBefore": { "user": 3, "assistant": 2, "total": 5 },
      "workerTotal":    { "user": 5, "assistant": 5, "total": 10 },
      "imported": 5,
      "platformAfter":  { "user": 5, "assistant": 5, "total": 10 },
      "missingPreview": [
        { "role": "user", "content": "请帮我修改...", "timestamp": "2026-03-06T11:20:00" },
        { "role": "assistant", "content": "好的，我来...", "timestamp": "2026-03-06T11:20:15" }
      ]
    },

    "taskStatusAfter": "RUNNING | COMPLETED | FAILED"
  }
}
```

**action 枚举说明**：

| action | 含义 | 触发条件 |
|--------|------|----------|
| `RECONNECTED` | CLI 仍活着，已重新连接 SSE | 策略 A |
| `MESSAGES_SYNCED` | CLI 已退出，从 JSONL 补齐了消息 | 策略 B，有缺失消息 |
| `ALREADY_ALIGNED` | CLI 已退出，但消息已完全一致 | 策略 B，无缺失 |
| `NO_SESSION_DATA` | Worker 没有该会话的 JSONL 记录 | claudeSessionId 为空或 Worker 找不到文件 |
| `WORKER_UNREACHABLE` | Worker 服务不可达 | health check 失败 |

### 三、UI 交互设计

#### 入口 1：TaskPane Header 按钮（打开任务时）

在 TaskPane 的 `#header-extra` slot 中，现有的"搁置"/"归档"按钮之前新增：

```
Header 区域：
┌──────────────────────────────────────────────────────────────────────┐
│ [A] ● sonnet  目前运行测试进度  待回复 [重新同步] 搁置 归档  $0.12  关闭 │
└──────────────────────────────────────────────────────────────────────┘
```

**显示条件**：`task.status` 为 `FAILED` 且 `task.claudeSessionId` 存在

#### 入口 2：历史会话列表 `...` 菜单（侧边栏）

在会话列表每条记录的 `el-dropdown` 菜单中（现有的 "扫描" 和 "删除" 之间），新增菜单项：

```
┌────────────────────────────┐
│  置顶                       │
│  编辑标题                    │
│  Auth 配置                  │
│  标签                       │
│  详情                       │
│  搁置                       │
│  归档                       │
│  回退                       │
│  扫描                       │
│  重新同步        ← 新增      │
│  ─────────────────────      │
│  删除                       │
└────────────────────────────┘
```

**显示条件**：`conv.latestTask.status === 'FAILED'`
> 注意：不要求 `claudeSessionId` 存在。即使 SYNCED 任务没有 claudeSessionId，
> 后端 resync 也会返回 `NO_SESSION_DATA` 并友好提示，不会报错。

**两个入口共享同一个处理函数 `handleResync()`，调用同一个后端端点 `POST /resync`。**

#### 交互流程

```
点击「重新同步」
    │
    ▼
按钮变为 Loading 状态（"同步中..."）
    │
    ▼ POST /resync
    │
    ├── action = RECONNECTED
    │   → Toast: "CLI 仍在运行，已重新连接"
    │   → 任务状态变为 RUNNING
    │   → 聊天面板追加 STATE_SYNC 消息 "已重新连接到 Claude Code"
    │   → SSE 自动恢复推送
    │
    ├── action = MESSAGES_SYNCED
    │   → Toast: "已从 Worker 同步 {n} 条消息"
    │   → 任务状态变为 COMPLETED
    │   → 刷新聊天面板消息列表（重新加载 session messages）
    │   → interactionState → AWAITING_REPLY
    │
    ├── action = ALREADY_ALIGNED
    │   → Toast: "消息已同步，无需更新"
    │   → 任务状态保持 FAILED → 改为 COMPLETED
    │
    ├── action = NO_SESSION_DATA
    │   → Toast.warning: "Worker 中未找到该会话记录"
    │
    └── action = WORKER_UNREACHABLE
        → Toast.error: "Worker 不可达，请检查 Worker 状态"
```

---

## 技术实现

### 1. 后端改动清单

| 文件 | 改动类型 | 说明 |
|------|----------|------|
| `ClaudeTaskController.java` | 新增端点 | `POST /{taskId}/resync` |
| `ClaudeTaskService.java` | 新增方法 | `resync()` — 核心逻辑 |
| `ClaudeTaskService.java` | 新增方法 | `syncMessagesFromWorker()` — 策略 B |
| `model/dto/ResyncResult.java` | 新增 DTO | 响应结构 |
| `ClaudeWorkerClient.java` | **无需改动** | 已有所需 API |
| `WorkerStreamRelay.java` | **无需改动** | 已有 `reconnectTask()` |
| `SecurityConfig.java` | **无需改动** | `/api/v1/claude-tasks/**` 已 permitAll |

### 2. 核心实现（Java）

#### 2.1 Controller

```java
// ClaudeTaskController.java

@PostMapping("/{taskId}/resync")
@RequireAuth
public RX<ResyncResult> resyncTask(@PathVariable String taskId) {
    String userId = UserContext.getCurrentUserId();
    return RX.ok(taskService.resync(taskId, userId));
}
```

#### 2.2 Service — 主流程

```java
// ClaudeTaskService.java

public ResyncResult resync(String taskId, String userId) {
    // 1. 加载任务
    ClaudeTaskEntity task = taskRepository.findByTaskId(taskId)
            .orElseThrow(() -> RX.throwB("Task not found"));
    if (!task.getUserId().equals(userId)) throw RX.throwB("Not owner");

    String claudeSessionId = task.getClaudeSessionId();
    String workerId = task.getWorkerId();

    ResyncResult result = new ResyncResult();
    result.setTaskId(taskId);

    // 2. 检查 Worker 可达性
    ClaudeWorkerEntity worker;
    ClaudeWorkerClient client;
    try {
        worker = workerService.getWorkerEntity(workerId);
        client = workerService.createClient(worker);
        client.healthCheck().block(Duration.ofSeconds(5));
    } catch (Exception e) {
        result.setAction("WORKER_UNREACHABLE");
        result.setCliStatus(CliStatus.unreachable(e.getMessage()));
        return result;
    }

    // 3. 探测 CLI 状态 — 先查 task_registry，再查进程列表
    CliStatus cliStatus = detectCliStatus(client, task);
    result.setCliStatus(cliStatus);

    // 4. 策略分派
    if (cliStatus.isAlive() || cliStatus.isTaskInRegistry()) {
        // ——— 策略 A：重新连接 ———
        return executeReconnect(task, result);
    } else {
        // ——— 策略 B：消息同步 ———
        return executeMsgSync(task, client, result);
    }
}
```

#### 2.3 策略 A — 重新连接

```java
private ResyncResult executeReconnect(ClaudeTaskEntity task, ResyncResult result) {
    // 复用现有 reconnect 逻辑
    resetToRunning(task.getTaskId());
    streamRelay.reconnectTask(task.getTaskId(), task.getSessionId(), task.getWorkerId());

    result.setAction("RECONNECTED");
    result.setTaskStatusAfter("RUNNING");
    return result;
}
```

**说明**：复用 `ClaudeTaskController.reconnectTask()` 的核心逻辑（lines 86-100），区别是 resync 不限制 status=FAILED（stalled 的 RUNNING 任务也可触发）。

#### 2.4 策略 B — 消息同步

```java
private ResyncResult executeMsgSync(ClaudeTaskEntity task, ClaudeWorkerClient client,
                                     ResyncResult result) {
    String claudeSessionId = task.getClaudeSessionId();
    String sessionId = task.getSessionId();

    // 1. 前置检查
    if (claudeSessionId == null || claudeSessionId.isEmpty()) {
        result.setAction("NO_SESSION_DATA");
        return result;
    }

    // 2. 获取 Worker 侧消息
    List<Map<String, Object>> workerMessages;
    try {
        workerMessages = client.getSessionMessages(claudeSessionId)
                .block(Duration.ofSeconds(15));
    } catch (Exception e) {
        result.setAction("NO_SESSION_DATA");
        result.setCliStatus(CliStatus.builder()
                .alive(false).workerReachable(true)
                .source("session_api").detail(e.getMessage()).build());
        return result;
    }
    if (workerMessages == null || workerMessages.isEmpty()) {
        result.setAction("NO_SESSION_DATA");
        return result;
    }

    // 3. 获取平台侧消息
    List<Message> platformMessages = (sessionId != null)
            ? sessionManager.getAllMessages(sessionId)
            : List.of();

    // 4. 计算差异
    MessageCount platformBefore = countByRole(platformMessages);
    MessageCount workerTotal = countWorkerMessages(workerMessages);
    List<Map<String, Object>> missing = computeMissing(platformMessages, workerMessages);

    // 5. 构建消息同步报告
    MessageSyncReport syncReport = new MessageSyncReport();
    syncReport.setPlatformBefore(platformBefore);
    syncReport.setWorkerTotal(workerTotal);

    if (missing.isEmpty()) {
        result.setAction("ALREADY_ALIGNED");
        syncReport.setImported(0);
        syncReport.setPlatformAfter(platformBefore);
        result.setMessageSync(syncReport);
        // 如果还是 FAILED 状态且消息已对齐，标记为 COMPLETED
        if ("FAILED".equals(task.getStatus())) {
            markAsCompletedFromSync(task);
            result.setTaskStatusAfter("COMPLETED");
        }
        return result;
    }

    // 6. 为 SYNCED 任务补建 Session（如果没有 sessionId）
    if (sessionId == null) {
        sessionId = createSessionForTask(task);
    }

    // 7. 导入缺失消息
    int imported = importMessages(sessionId, missing);
    syncReport.setImported(imported);
    syncReport.setMissingPreview(truncatePreview(missing, 10)); // 最多预览 10 条

    List<Message> afterMessages = sessionManager.getAllMessages(sessionId);
    syncReport.setPlatformAfter(countByRole(afterMessages));

    result.setAction("MESSAGES_SYNCED");
    result.setMessageSync(syncReport);

    // 8. 更新任务状态为 COMPLETED
    markAsCompletedFromSync(task);
    result.setTaskStatusAfter("COMPLETED");

    return result;
}
```

#### 2.5 消息差异计算

```java
/**
 * 计算 Worker 侧有但平台侧没有的消息。
 *
 * 匹配策略：有序位置匹配。
 * Worker 消息列表是从 JSONL 按顺序读取的（排除 sidechain），平台消息按 createdAt 排序。
 * 以 Worker 消息列表为基准，逐条尝试匹配平台消息，未匹配到的即为缺失。
 *
 * 匹配标准：role 相同 且 content 前 200 字符（strip后）相同。
 */
private List<Map<String, Object>> computeMissing(
        List<Message> platformMessages,
        List<Map<String, Object>> workerMessages) {

    // 构建平台侧有序指纹列表（用于顺序匹配）
    List<String> platformFps = platformMessages.stream()
            .map(m -> fingerprint(m.getRole().name().toLowerCase(), m.getContent()))
            .collect(Collectors.toList());

    List<Map<String, Object>> missing = new ArrayList<>();
    int platformIdx = 0;

    for (Map<String, Object> wm : workerMessages) {
        String fp = fingerprint((String) wm.get("role"), (String) wm.get("content"));
        if (platformIdx < platformFps.size() && platformFps.get(platformIdx).equals(fp)) {
            platformIdx++; // 匹配成功，推进平台指针
        } else {
            missing.add(wm); // 平台没有这条消息
        }
    }
    return missing;
}

private String fingerprint(String role, String content) {
    if (content == null) content = "";
    String trimmed = content.strip();
    String prefix = trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    return role + ":" + prefix;
}
```

**为什么用有序位置匹配而非集合差集**：
- 同一会话中可能出现内容完全相同的消息（如用户多次输入"继续"）
- 集合差集会误认为平台已有该消息
- 有序匹配更精确，Worker JSONL 的顺序就是原始对话顺序

#### 2.6 消息导入

```java
private int importMessages(String sessionId, List<Map<String, Object>> messages) {
    int imported = 0;
    for (Map<String, Object> msg : messages) {
        String role = (String) msg.get("role");
        String content = (String) msg.get("content");
        String timestamp = (String) msg.get("timestamp");

        MessageRole messageRole = "user".equals(role)
                ? MessageRole.USER : MessageRole.ASSISTANT;

        LocalDateTime createdAt = parseTimestamp(timestamp);

        sessionManager.addMessage(sessionId, Message.builder()
                .sessionId(sessionId)
                .role(messageRole)
                .content(content)
                .createdAt(createdAt)
                .build());
        imported++;
    }
    return imported;
}

private LocalDateTime parseTimestamp(String ts) {
    if (ts == null || ts.isEmpty()) return LocalDateTime.now();
    try {
        return LocalDateTime.parse(ts);
    } catch (Exception e) {
        try {
            return LocalDateTime.parse(ts, DateTimeFormatter.ISO_DATE_TIME);
        } catch (Exception e2) {
            return LocalDateTime.now();
        }
    }
}
```

#### 2.7 状态更新

```java
/**
 * 从消息同步恢复的任务标记为 COMPLETED。
 * CLI 已退出 → 任务实际已经结束（无论成功失败），同步消息后恢复为 COMPLETED。
 */
@Transactional
private void markAsCompletedFromSync(ClaudeTaskEntity task) {
    task.setStatus("COMPLETED");
    task.setErrorMessage(null);
    taskRepository.save(task);
    conversationConfigService.updateInteractionState(task.getSessionId(), "AWAITING_REPLY");
}

/**
 * 为 SYNCED 任务（无 sessionId）补建 Navigator Session。
 */
@Transactional
private String createSessionForTask(ClaudeTaskEntity task) {
    String sessionId = sessionManager.createSession(
            task.getUserId(), "claude-worker", task.getPrompt());
    task.setSessionId(sessionId);
    taskRepository.save(task);
    return sessionId;
}
```

#### 2.8 CLI 状态探测

```java
/**
 * 三层探测 CLI 存活状态：
 * 1. 查 Worker task_registry（通过 getTaskStatus API）
 * 2. 查进程列表匹配 foggy_task_id
 * 3. 查进程列表匹配 claudeSessionId（兜底）
 */
private CliStatus detectCliStatus(ClaudeWorkerClient client, ClaudeTaskEntity task) {
    // 层1: 查 task_registry（最可靠）
    try {
        // 注意：Worker 的 task_id 可能与 Java 的 taskId 不同
        // 但 Worker subscribe 端点支持 foggy_task_id 解析
        Map<String, Object> status = client.getTaskStatus(task.getTaskId())
                .block(Duration.ofSeconds(5));
        if (status != null) {
            boolean cliAlive = Boolean.TRUE.equals(status.get("cli_alive"));
            boolean closed = Boolean.TRUE.equals(status.get("closed"));
            String source = (String) status.getOrDefault("source", "registry");
            return CliStatus.builder()
                    .alive(cliAlive)
                    .workerReachable(true)
                    .taskInRegistry("registry".equals(source))
                    .source("task_status")
                    .detail(closed ? "stream closed" : (cliAlive ? "cli running" : "cli exited"))
                    .build();
        }
    } catch (Exception e) {
        log.debug("Task status check failed: {}", e.getMessage());
    }

    // 层2: 查进程列表（CLI 活着但 task_registry 已清理时）
    try {
        Map<String, Object> processResult = client.listCliProcesses()
                .block(Duration.ofSeconds(10));
        if (processResult != null) {
            List<Map<String, Object>> processes =
                    (List<Map<String, Object>>) processResult.get("processes");
            if (processes != null) {
                boolean found = processes.stream().anyMatch(p -> {
                    String foggyTaskId = (String) p.get("foggy_task_id");
                    return task.getTaskId().equals(foggyTaskId);
                });
                if (found) {
                    return CliStatus.builder()
                            .alive(true).workerReachable(true)
                            .taskInRegistry(false).source("process_list").build();
                }
            }
        }
    } catch (Exception e) {
        log.debug("Process list check failed: {}", e.getMessage());
    }

    // 层3: 都查不到 → CLI 已退出
    return CliStatus.builder()
            .alive(false).workerReachable(true)
            .taskInRegistry(false).source("fallback").build();
}
```

**探测层次说明**：

| 层次 | API | 场景 | 返回值 |
|------|-----|------|--------|
| 层1 | `GET /tasks/{taskId}/status` | 任务还在 task_registry 中（正在执行或有 external subscriber 保持） | `cli_alive`, `closed`, `source` |
| 层2 | `GET /processes` | task_registry 已清理但 CLI node 进程仍在（orphan） | 匹配 `foggy_task_id` |
| 层3 | - | 上述都失败 | `alive=false` |

#### 2.9 DTO

```java
// model/dto/ResyncResult.java

@Data
public class ResyncResult {
    private String taskId;

    /** RECONNECTED | MESSAGES_SYNCED | ALREADY_ALIGNED | NO_SESSION_DATA | WORKER_UNREACHABLE */
    private String action;

    private CliStatus cliStatus;
    private MessageSyncReport messageSync;  // null when action=RECONNECTED
    private String taskStatusAfter;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CliStatus {
    private boolean alive;
    private boolean workerReachable;
    private boolean taskInRegistry;
    private String source;
    private String detail;

    public static CliStatus unreachable(String reason) {
        return CliStatus.builder()
                .alive(false).workerReachable(false)
                .source("health_check").detail(reason).build();
    }
}

@Data
public class MessageSyncReport {
    private MessageCount platformBefore;
    private MessageCount workerTotal;
    private int imported;
    private MessageCount platformAfter;
    private List<Map<String, Object>> missingPreview;
}

@Data
@AllArgsConstructor
public class MessageCount {
    private int user;
    private int assistant;
    private int total;
}
```

### 3. 前端改动清单

| 文件 | 改动类型 | 说明 |
|------|----------|------|
| `api/claudeWorker.ts` | 新增函数 | `resyncTask(taskId)` |
| `types/index.ts` | 新增类型 | `ResyncResult`, `CliStatus`, `MessageSyncReport` |
| `views/ClaudeWorkerView.vue` | 修改 | `#header-extra` slot 加按钮 + 侧边栏菜单项 + 处理函数 |
| `composables/useClaudeWorker.ts` | 新增方法 | `resyncTask()` 包装 |

#### 3.1 API

```typescript
// api/claudeWorker.ts

export async function resyncTask(taskId: string): Promise<ResyncResult> {
  const res = await http.post(`/claude-tasks/${taskId}/resync`)
  return res.data.data
}
```

#### 3.2 类型

```typescript
// types/index.ts

export interface ResyncResult {
  taskId: string
  action: 'RECONNECTED' | 'MESSAGES_SYNCED' | 'ALREADY_ALIGNED' | 'NO_SESSION_DATA' | 'WORKER_UNREACHABLE'
  cliStatus: CliStatus
  messageSync?: MessageSyncReport
  taskStatusAfter?: string
}

export interface CliStatus {
  alive: boolean
  workerReachable: boolean
  taskInRegistry: boolean
  source: string
  detail?: string
}

export interface MessageSyncReport {
  platformBefore: MessageCount
  workerTotal: MessageCount
  imported: number
  platformAfter: MessageCount
  missingPreview?: Array<{ role: string; content: string; timestamp?: string }>
}

export interface MessageCount {
  user: number
  assistant: number
  total: number
}
```

#### 3.3 入口 1 — TaskPane Header 按钮

```vue
<!-- ClaudeWorkerView.vue — #header-extra slot 中 -->

<!-- 在已有的 interactionState 标签和 "搁置"/"归档" 按钮之间新增 -->
<el-button
  v-if="paneState.task.value?.status === 'FAILED'"
  size="small"
  text
  :loading="resyncLoading[paneState.task.value.taskId]"
  @click="handleResyncFromPane(paneState)"
>
  {{ resyncLoading[paneState.task.value?.taskId] ? '同步中...' : '重新同步' }}
</el-button>
```

#### 3.4 入口 2 — 历史会话列表 `...` 菜单

```vue
<!-- ClaudeWorkerView.vue — 会话列表 el-dropdown 中 -->
<!-- 在 "扫描" 和 "删除" 之间新增 -->

<el-dropdown-item
  v-if="conv.latestTask.status === 'FAILED'"
  :disabled="resyncLoading[conv.latestTask.taskId]"
  @click="handleResyncFromList(conv)"
>
  {{ resyncLoading[conv.latestTask.taskId] ? '同步中...' : '重新同步' }}
</el-dropdown-item>
```

#### 3.5 处理函数

```typescript
// ClaudeWorkerView.vue — script setup 中

const resyncLoading = ref<Record<string, boolean>>({})

/**
 * 核心 resync 逻辑 — 两个入口共享
 * @param taskId   要同步的任务 ID
 * @param paneState 如果从 TaskPane 触发则传入（可选），用于更新聊天面板
 * @param conv      如果从列表触发则传入（可选），用于更新列表状态
 */
async function doResync(
  taskId: string,
  paneState?: TaskPaneState | null,
  conv?: ConversationGroup | null,
) {
  resyncLoading.value[taskId] = true
  try {
    const result = await dirApi.resyncTask(taskId)

    switch (result.action) {
      case 'RECONNECTED':
        ElMessage.success('CLI 仍在运行，已重新连接')
        // 更新任务状态
        if (paneState?.task.value) paneState.task.value.status = 'RUNNING'
        if (conv?.latestTask) conv.latestTask.status = 'RUNNING'
        // 如果 TaskPane 打开，追加系统消息并恢复 SSE
        if (paneState) {
          paneState.chatState.messages.value.push({
            id: `resync-${Date.now()}`,
            type: 'STATE_SYNC' as AipMessageType,
            sender: 'system',
            content: '已重新连接到 Claude Code',
            raw: { subtype: 'reconnected' },
            timestamp: Date.now(),
          })
          paneState.reconnectSse()
        }
        break

      case 'MESSAGES_SYNCED':
        ElMessage.success(`已从 Worker 同步 ${result.messageSync?.imported ?? 0} 条消息`)
        if (paneState?.task.value) {
          paneState.task.value.status = result.taskStatusAfter ?? 'COMPLETED'
          // 重新加载聊天消息
          await reloadPaneMessages(paneState)
        }
        if (conv?.latestTask) {
          conv.latestTask.status = result.taskStatusAfter ?? 'COMPLETED'
        }
        break

      case 'ALREADY_ALIGNED':
        ElMessage.info('消息已同步，无需更新')
        if (result.taskStatusAfter) {
          if (paneState?.task.value) paneState.task.value.status = result.taskStatusAfter
          if (conv?.latestTask) conv.latestTask.status = result.taskStatusAfter
        }
        break

      case 'NO_SESSION_DATA':
        ElMessage.warning('Worker 中未找到该会话记录')
        break

      case 'WORKER_UNREACHABLE':
        ElMessage.error('Worker 不可达，请检查 Worker 状态')
        break
    }

    if (activeWorkspace.value) triggerRef(activeWorkspace.value.panes)
    workerState.loadAwaitingReplyTasks()
  } catch (e: any) {
    ElMessage.error('重新同步失败: ' + (e?.response?.data?.message || e.message))
  } finally {
    resyncLoading.value[taskId] = false
  }
}

// 入口 1: 从 TaskPane header 触发
async function handleResyncFromPane(paneState: TaskPaneState) {
  const task = paneState.task.value
  if (!task) return
  await doResync(task.taskId, paneState, null)
}

// 入口 2: 从历史会话列表菜单触发
async function handleResyncFromList(conv: ConversationGroup) {
  // 找到对应已打开的 pane（如果有的话）
  const pane = panes.value.find(p => p.task.value?.sessionId === conv.sessionId)
  await doResync(conv.latestTask.taskId, pane ?? null, conv)
}
```

#### 3.6 reloadPaneMessages

MESSAGES_SYNCED 后平台 `session_messages` 已更新，需要刷新 ChatPanel 中的消息。

**方案**：在 `useTaskPane.ts` 中新增 `reloadMessages()` 方法：

```typescript
// useTaskPane.ts

async function reloadMessages() {
  const t = task.value
  if (!t) return
  // 从后端重新拉取 session messages
  const messages = await api.getSessionMessages(t.sessionId)
  // 转换为 ChatMessage 格式并替换当前消息列表
  const chatMessages = messages.map(convertToChatMessage)
  chatState.messages.value = chatMessages
}
```

> 注意：如果 session messages 的 API（`GET /api/v1/sessions/{sessionId}/messages`）返回的消息格式
> 与 SSE relay 产生的 ChatMessage 格式有差异，需要在 `convertToChatMessage` 中做适配。
> 这是策略 B 的关键技术难点之一，实现时需要先对比两种格式确定转换逻辑。

### 4. Worker 侧（Python）

**无需改动。** 现有 API 完全满足需求：

| 已有 API | 本方案用途 |
|----------|-----------|
| `GET /api/v1/tasks/{taskId}/status` | 探测层1：task_registry 状态、cli_alive |
| `GET /api/v1/processes` | 探测层2：进程列表匹配 foggy_task_id |
| `GET /api/v1/tasks/{taskId}/subscribe` | 策略 A：SSE 重新连接（被 streamRelay 调用） |
| `GET /api/v1/sessions/{id}/messages` | 策略 B：获取 JSONL 完整对话 |

---

## 与现有 reconnect 的关系

| 维度 | 现有 reconnect | 新增 resync |
|------|----------------|-------------|
| 端点 | `POST /{taskId}/reconnect` | `POST /{taskId}/resync` |
| 前置条件 | 仅 `status=FAILED` | FAILED（推荐），其他状态也可 |
| CLI 必须活着？ | 是（否则 subscribe 会失败） | 否（自动降级到消息同步） |
| 消息补齐？ | 否（仅重连 SSE 流） | 是（策略 B 从 JSONL 补齐） |
| 任务状态恢复 | → RUNNING | → RUNNING（策略A）或 COMPLETED（策略B） |
| 触发方 | 用户手动 / ErrorBlock 按钮 | 用户手动（header-extra 按钮） |

**不废弃现有 reconnect**：ErrorBlock 中的"重连"按钮继续使用现有端点，适用于"刚失败马上重连"的快速场景。新增的"重新同步"是更全面的恢复操作。

---

## 边界情况处理

### 1. SYNCED 任务（无 sessionId）

- `syncLocalSessions()` 创建的任务没有 `sessionId`，也没有 `session_messages`
- **策略 B 中处理**：检测到 `sessionId == null` 时调用 `createSessionForTask()` 补建
- 补建后的 sessionId 回写到 `ClaudeTaskEntity`

### 2. 并发安全

- 用户可能快速多次点击，或 Reconciler 同时在操作同一任务
- **策略 A**：复用 `WorkerStreamRelay.reconnecting` mutex（ConcurrentHashMap + AtomicBoolean）
- **策略 B**：`@Transactional` 确保消息导入原子性；指纹匹配天然幂等（相同内容不重复导入）

### 3. Worker 重启后 task_registry 清空

- Worker 重启后 `task_registry` 为空，`getTaskStatus()` 会返回 404 或从 persistence 层查
- **探测逻辑已处理**：层1 失败后自动落到层2（进程列表），层2 失败落到层3（CLI 已退出 → 策略 B）

### 4. 消息格式差异

- Worker `/messages` API 返回简化格式（纯文本，不含 tool_use/thinking）
- 平台 `session_messages` 中的消息可能包含 SSE relay 处理后的格式（如 tool call blocks）
- **指纹策略**：只比较 content 前 200 字符，容忍格式差异
- **可接受的结果**：策略 B 导入的消息是"简化版"对话视图，缺少 tool call 细节，但用户可读

### 5. 重复消息（如多次输入"继续"）

- 有序位置匹配（而非集合差集）确保即使内容完全相同的消息也能正确匹配
- Worker 侧 JSONL 保证了原始消息顺序

---

## 实现步骤

| 步骤 | 内容 | 预计工作量 |
|------|------|-----------|
| 1 | 后端 DTO（`ResyncResult`, `CliStatus`, `MessageSyncReport`） | 30 min |
| 2 | 后端 `detectCliStatus()` — 三层探测 | 1 hour |
| 3 | 后端 `resync()` 主流程 + 策略 A（复用 reconnect） | 1 hour |
| 4 | 后端策略 B（`computeMissing` + `importMessages` + `markAsCompletedFromSync`） | 1.5 hours |
| 5 | 前端类型 + API 函数 | 20 min |
| 6 | 前端 `#header-extra` 按钮 + `handleResync` + Toast | 1 hour |
| 7 | 前端 `reloadPaneMessages` + ChatPanel 刷新 | 1 hour |
| 8 | 单元测试 + 手动验证 | 1 hour |

**总计约 1-1.5 天**

---

## 测试计划

### 后端单元测试

- [ ] `computeMissing()` — 全缺失、部分缺失、完全一致、重复内容、空消息
- [ ] `fingerprint()` — 长内容截断、null content、Unicode、空白差异
- [ ] `detectCliStatus()` — task_registry 存在/不存在、进程列表匹配/不匹配、Worker 不可达
- [ ] `resync()` — 策略 A 触发、策略 B 触发、ALREADY_ALIGNED、NO_SESSION_DATA、WORKER_UNREACHABLE

### 集成测试

- [ ] Mock Worker 返回不同 task status → 验证策略选择
- [ ] Mock Worker 返回消息列表 → 验证消息正确导入到 session_messages
- [ ] SYNCED 任务补建 Session → 验证 sessionId 回写

### 前端验证

- [ ] 按钮显示条件：FAILED + claudeSessionId → 显示；RUNNING → 不显示
- [ ] Loading 状态 + 各 action 对应的 Toast 提示
- [ ] MESSAGES_SYNCED 后聊天面板正确刷新
- [ ] RECONNECTED 后 SSE 恢复推送新消息
- [ ] 构建通过：`cd packages/navigator-frontend && pnpm exec vite build`
