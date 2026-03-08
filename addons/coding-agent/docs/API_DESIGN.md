# Coding Agent API 设计文档

## 概述

本模块封装 OpenHands V1 API，为前端提供语义层编辑能力。核心概念与 OpenHands 完全对齐：
- **Conversation（对话）**: 一个持续的编辑会话 = 一个沙箱环境
- **Message（消息）**: 用户发送的编辑指令
- **Event（事件）**: Conversation 执行过程中产生的所有事件

## 架构设计

### 核心职责

1. **封装 OpenHands API**: 调用 OpenHands V1 REST API
2. **SSE 转换层**: 将轮询转换为 SSE 实时推送给前端
3. **验证集成**: 调用语义层验证服务

### 模块结构

```
coding-agent/
├── foundation/git/              # OpenHands 集成
│   ├── OpenHandsClient         # OpenHands REST API 客户端
│   ├── ValidationServiceClient # 验证服务客户端
│   └── EventPoller             # Event 轮询器
├── api/
│   ├── model/                  # 数据模型
│   │   ├── Conversation        # 对话模型
│   │   ├── Message             # 消息模型
│   │   └── Event               # 事件模型
│   ├── service/
│   │   ├── ConversationService # 对话业务逻辑
│   │   └── EventStreamService  # SSE 事件流服务
│   └── controller/
│       ├── ConversationController  # 对话 API
│       └── EventStreamController   # 事件流 API
```

---

## API 端点设计

### 1. 创建并启动 Conversation

**端点**: `POST /api/conversations`

**描述**: 创建一个新的编辑会话（沙箱环境）

**请求体**:
```json
{
  "userId": "user-123",
  "projectId": "proj-456",
  "gitRepoUrl": "https://gitlab.com/org/semantic-layer.git",
  "branchName": "feature/user-orders",
  "gitCredentials": {
    "username": "git-user",
    "token": "git-token"
  },
  "initialMessage": "创建一个名为 user_orders 的 TM 文件，包含 user_id, order_id, amount 字段"
}
```

**请求参数**:
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| userId | String | 是 | 用户 ID |
| projectId | String | 是 | 项目 ID |
| gitRepoUrl | String | 是 | Git 仓库地址 |
| branchName | String | 是 | 分支名称 |
| gitCredentials | Object | 否 | Git 凭证 |
| initialMessage | String | 否 | 初始消息（可选，也可以后续发送） |

**响应**:
```json
{
  "success": true,
  "message": "Conversation 创建成功",
  "data": {
    "conversationId": "conv-789",
    "sandboxId": "sandbox-abc",
    "status": "STARTING",
    "namespace": "user-user-123-conv-789",
    "createdAt": "2024-01-20T10:30:00Z"
  }
}
```

**Conversation 状态**:
- `STARTING`: 启动中
- `READY`: 就绪，可以发送消息
- `RUNNING`: 执行中
- `IDLE`: 空闲，等待新消息
- `ERROR`: 错误
- `STOPPED`: 已停止

---

### 2. 发送消息

**端点**: `POST /api/conversations/{conversationId}/messages`

<thinking>
用户说"一个task就相当于向其发送一条初始消息"，所以发送消息就是执行编辑任务的方式。
</thinking>

**描述**: 向 Conversation 发送编辑指令

**路径参数**:
| 参数 | 类型 | 说明 |
|------|------|------|
| conversationId | String | Conversation ID |

**请求体**:
```json
{
  "content": "修改 user_orders TM 文件，添加 status 字段，类型为 String"
}
```

**响应**:
```json
{
  "success": true,
  "message": "消息已发送",
  "data": {
    "messageId": "msg-123",
    "conversationId": "conv-789",
    "content": "修改 user_orders TM 文件...",
    "timestamp": "2024-01-20T10:35:00Z"
  }
}
```

---

### 3. 获取 Conversation 详情

**端点**: `GET /api/conversations/{conversationId}`

**描述**: 获取 Conversation 的当前状态和信息

**路径参数**:
| 参数 | 类型 | 说明 |
|------|------|------|
| conversationId | String | Conversation ID |

**响应**:
```json
{
  "success": true,
  "data": {
    "conversationId": "conv-789",
    "sandboxId": "sandbox-abc",
    "status": "IDLE",
    "namespace": "user-user-123-conv-789",
    "gitRepoUrl": "https://gitlab.com/org/semantic-layer.git",
    "branchName": "feature/user-orders",
    "createdAt": "2024-01-20T10:30:00Z",
    "updatedAt": "2024-01-20T10:35:00Z"
  }
}
```

---

### 4. 获取事件流（SSE）

**端点**: `GET /api/conversations/{conversationId}/events/stream`

**描述**: 订阅 Conversation 的实时事件流（Server-Sent Events）

**路径参数**:
| 参数 | 类型 | 说明 |
|------|------|------|
| conversationId | String | Conversation ID |

**查询参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| lastEventId | String | 否 | 上次接收的事件 ID，用于断线重连 |

**响应** (Server-Sent Events):
```
event: conversation_status
data: {"conversationId":"conv-789","status":"READY","timestamp":"2024-01-20T10:30:05Z"}

event: message_sent
data: {"messageId":"msg-123","content":"创建 user_orders TM 文件..."}

event: agent_action
data: {"action":"write_file","args":{"path":"tm/user_orders.tm.js"}}

event: agent_observation
data: {"observation":"文件创建成功"}

event: validation_triggered
data: {"validationId":"val-456","status":"VALIDATING"}

event: validation_result
data: {"success":true,"totalFiles":1,"validFiles":1,"invalidFiles":0}

event: conversation_idle
data: {"conversationId":"conv-789","status":"IDLE"}
```

**事件类型**:
- `conversation_status`: Conversation 状态变更
- `message_sent`: 消息已发送
- `agent_action`: Agent 执行动作
- `agent_observation`: Agent 观察结果
- `validation_triggered`: 验证已触发
- `validation_result`: 验证结果
- `conversation_idle`: Conversation 进入空闲状态
- `error`: 错误信息

---

### 5. 查询历史事件

**端点**: `GET /api/conversations/{conversationId}/events`

**描述**: 查询 Conversation 的历史事件（分页）

**路径参数**:
| 参数 | 类型 | 说明 |
|------|------|------|
| conversationId | String | Conversation ID |

**查询参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| kind | String | 否 | 事件类型过滤 |
| timestampGte | DateTime | 否 | 时间戳 >= |
| timestampLt | DateTime | 否 | 时间戳 < |
| pageId | String | 否 | 分页 ID |
| limit | Integer | 否 | 每页数量，默认 100 |

**响应**:
```json
{
  "success": true,
  "data": {
    "events": [
      {
        "id": "evt-1",
        "conversationId": "conv-789",
        "kind": "agent_action",
        "timestamp": "2024-01-20T10:31:00Z",
        "data": {
          "action": "write_file",
          "args": {"path": "tm/user_orders.tm.js"}
        }
      }
    ],
    "nextPageId": "page-2"
  }
}
```

---

### 6. 停止 Conversation

**端点**: `POST /api/conversations/{conversationId}/stop`

**描述**: 停止 Conversation 执行

**路径参数**:
| 参数 | 类型 | 说明 |
|------|------|------|
| conversationId | String | Conversation ID |

**响应**:
```json
{
  "success": true,
  "message": "Conversation 已停止"
}
```

---

### 7. 删除 Conversation

**端点**: `DELETE /api/conversations/{conversationId}`

**描述**: 删除 Conversation 和关联的沙箱环境

**路径参数**:
| 参数 | 类型 | 说明 |
|------|------|------|
| conversationId | String | Conversation ID |

**响应**:
```json
{
  "success": true,
  "message": "Conversation 已删除"
}
```

---

### 8. 查询用户的 Conversation 列表

**端点**: `GET /api/conversations`

**描述**: 查询用户的所有 Conversation（用于恢复会话）

**查询参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| userId | String | 是 | 用户 ID |
| projectId | String | 否 | 项目 ID 过滤 |
| status | String | 否 | 状态过滤 |
| page | Integer | 否 | 页码，默认 0 |
| size | Integer | 否 | 每页数量，默认 20 |

**响应**:
```json
{
  "success": true,
  "data": {
    "conversations": [
      {
        "conversationId": "conv-789",
        "sandboxId": "sandbox-abc",
        "projectId": "proj-456",
        "status": "IDLE",
        "gitRepoUrl": "https://gitlab.com/org/semantic-layer.git",
        "branchName": "feature/user-orders",
        "lastMessageAt": "2024-01-20T10:35:00Z",
        "createdAt": "2024-01-20T10:30:00Z"
      }
    ],
    "pagination": {
      "page": 0,
      "size": 20,
      "total": 1
    }
  }
}
```

---

### 9. 触发验证

**端点**: `POST /api/conversations/{conversationId}/validate`

**描述**: 手动触发语义层验证

**路径参数**:
| 参数 | 类型 | 说明 |
|------|------|------|
| conversationId | String | Conversation ID |

**响应**:
```json
{
  "success": true,
  "message": "验证已触发",
  "data": {
    "validationId": "val-789",
    "status": "VALIDATING"
  }
}
```

---

## 数据模型

### Conversation
```java
@Data
@Builder
public class Conversation {
    private String conversationId;
    private String sandboxId;
    private String userId;
    private String projectId;
    private ConversationStatus status;
    private String namespace;
    private String gitRepoUrl;
    private String branchName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

public enum ConversationStatus {
    STARTING,   // 启动中
    READY,      // 就绪
    RUNNING,    // 执行中
    IDLE,       // 空闲
    ERROR,      // 错误
    STOPPED     // 已停止
}
```

### Message
```java
@Data
@Builder
public class Message {
    private String messageId;
    private String conversationId;
    private String content;
    private LocalDateTime timestamp;
}
```

### Event
```java
@Data
@Builder
public class Event {
    private String id;
    private String conversationId;
    private EventKind kind;
    private LocalDateTime timestamp;
    private Map<String, Object> data;
}

public enum EventKind {
    CONVERSATION_STATUS,
    MESSAGE_SENT,
    AGENT_ACTION,
    AGENT_OBSERVATION,
    VALIDATION_TRIGGERED,
    VALIDATION_RESULT,
    ERROR
}
```

---

## SSE 转换层设计

### EventPoller（事件轮询器）

**职责**: 轮询 OpenHands Events API，检测新事件

```java
@Component
public class EventPoller {
    private final OpenHandsClient openHandsClient;
    private final ScheduledExecutorService scheduler;

    // 为每个活跃的 Conversation 启动轮询
    public void startPolling(String conversationId, Consumer<Event> eventHandler) {
        scheduler.scheduleAtFixedRate(() -> {
            List<Event> newEvents = openHandsClient.getNewEvents(conversationId, lastEventId);
            newEvents.forEach(eventHandler);
        }, 0, 2, TimeUnit.SECONDS);  // 每 2 秒轮询一次
    }

    public void stopPolling(String conversationId) {
        // 停止轮询
    }
}
```

### EventStreamService（SSE 服务）

**职责**: 管理 SSE 连接，将轮询到的事件推送给前端

```java
@Service
public class EventStreamService {
    private final EventPoller eventPoller;
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String conversationId, String lastEventId) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.put(conversationId, emitter);

        // 启动轮询，将事件推送到 SSE
        eventPoller.startPolling(conversationId, event -> {
            try {
                emitter.send(SseEmitter.event()
                    .name(event.getKind().name())
                    .data(event.getData()));
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        });

        emitter.onCompletion(() -> cleanup(conversationId));
        emitter.onTimeout(() -> cleanup(conversationId));

        return emitter;
    }

    private void cleanup(String conversationId) {
        eventPoller.stopPolling(conversationId);
        emitters.remove(conversationId);
    }
}
```

---

## OpenHands API 映射

### 我们的 API → OpenHands API

| 我们的 API | OpenHands API | 说明 |
|-----------|--------------|------|
| POST /api/conversations | POST /api/v1/app-conversations/stream-start | 创建 Conversation |
| POST /api/conversations/{id}/messages | SDK: send_message() | 发送消息（通过 SDK） |
| GET /api/conversations/{id} | GET /api/v1/app-conversations/{id} | 获取详情 |
| GET /api/conversations/{id}/events/stream | 轮询 GET /api/v1/conversation/{id}/events/search | SSE 封装 |
| GET /api/conversations/{id}/events | GET /api/v1/conversation/{id}/events/search | 历史事件 |
| DELETE /api/conversations/{id} | DELETE /api/v1/app-conversations/{id} | 删除 |

---

## 前端集成示例

### 创建 Conversation 并监听事件流

```javascript
// 1. 创建 Conversation
const response = await fetch('/api/conversations', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    userId: 'user-123',
    projectId: 'proj-456',
    gitRepoUrl: 'https://gitlab.com/org/semantic-layer.git',
    branchName: 'feature/user-orders',
    initialMessage: '创建 user_orders TM 文件'
  })
});
const { data: conversation } = await response.json();

// 2. 订阅事件流
const eventSource = new EventSource(
  `/api/conversations/${conversation.conversationId}/events/stream`
);

eventSource.addEventListener('conversation_status', (e) => {
  const { status } = JSON.parse(e.data);
  console.log('状态变更:', status);
});

eventSource.addEventListener('agent_action', (e) => {
  const action = JSON.parse(e.data);
  console.log('Agent 动作:', action);
});

eventSource.addEventListener('validation_result', (e) => {
  const result = JSON.parse(e.data);
  if (result.success) {
    console.log('验证通过');
  } else {
    console.error('验证失败:', result.errors);
  }
});

eventSource.addEventListener('conversation_idle', (e) => {
  console.log('Conversation 空闲，可以发送新消息');
});

// 3. 发送新消息
async function sendMessage(content) {
  await fetch(`/api/conversations/${conversation.conversationId}/messages`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content })
  });
}

// 用户在执行过程中发送新消息
await sendMessage('修改 user_orders，添加 status 字段');
```

### 继续会话（恢复 Conversation）

```javascript
// 场景：用户关闭浏览器后重新打开，需要恢复之前的会话

// 1. 查询用户的活跃 Conversation
const response = await fetch('/api/conversations?userId=user-123&status=IDLE,READY');
const { data } = await response.json();

// 2. 展示可恢复的会话列表
const activeConversations = data.conversations;
console.log('可恢复的会话:', activeConversations);

// 3. 用户选择要恢复的会话
const selectedConversation = activeConversations[0];

// 4. 获取会话详情
const detailResponse = await fetch(
  `/api/conversations/${selectedConversation.conversationId}`
);
const { data: conversation } = await detailResponse.json();

// 5. 重新订阅事件流（从上次断开的位置继续）
const lastEventId = localStorage.getItem(`lastEventId_${conversation.conversationId}`);
const eventSource = new EventSource(
  `/api/conversations/${conversation.conversationId}/events/stream?lastEventId=${lastEventId || ''}`
);

eventSource.addEventListener('message', (e) => {
  // 保存最新的 eventId，用于断线重连
  localStorage.setItem(`lastEventId_${conversation.conversationId}`, e.lastEventId);
});

// 6. 查询历史事件（可选，用于展示之前的对话历史）
const eventsResponse = await fetch(
  `/api/conversations/${conversation.conversationId}/events?limit=50`
);
const { data: eventsData } = await eventsResponse.json();
console.log('历史事件:', eventsData.events);

// 7. 继续发送新消息
await fetch(`/api/conversations/${conversation.conversationId}/messages`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ content: '继续编辑...' })
});
```

---

## 会话持久化策略

### Conversation 生命周期

1. **创建**: 用户创建 Conversation，OpenHands 启动沙箱
2. **活跃**: 用户发送消息，Agent 执行任务
3. **空闲**: Agent 完成任务，Conversation 进入 IDLE 状态
4. **休眠**: 用户关闭浏览器，Conversation 保持运行（沙箱不销毁）
5. **恢复**: 用户重新打开，通过 conversationId 恢复会话
6. **销毁**: 用户主动删除，或超时自动清理

### 自动清理策略

```yaml
foggy:
  coding-agent:
    conversation:
      idle-timeout: 3600000      # 空闲超时（毫秒），默认 1 小时
      max-lifetime: 86400000     # 最大生命周期（毫秒），默认 24 小时
      auto-cleanup: true         # 是否自动清理
```

**清理规则**:
- Conversation 空闲超过 `idle-timeout` 自动停止沙箱
- Conversation 创建超过 `max-lifetime` 自动删除
- 用户可以在超时前恢复会话

### 断线重连机制

**SSE 断线重连**:
```javascript
const eventSource = new EventSource(
  `/api/conversations/${conversationId}/events/stream?lastEventId=${lastEventId}`
);

eventSource.onerror = (error) => {
  console.error('SSE 连接断开，尝试重连...');
  // EventSource 会自动重连，并通过 lastEventId 恢复
};
```

**后端处理**:
```java
@GetMapping("/conversations/{conversationId}/events/stream")
public SseEmitter streamEvents(
    @PathVariable String conversationId,
    @RequestParam(required = false) String lastEventId) {

    SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);

    // 如果提供了 lastEventId，先发送之后的所有事件
    if (lastEventId != null) {
        List<Event> missedEvents = eventService.getEventsSince(conversationId, lastEventId);
        missedEvents.forEach(event -> emitter.send(event));
    }

    // 然后开始实时推送新事件
    eventStreamService.subscribe(conversationId, emitter);

    return emitter;
}
```

---

## 会话恢复最佳实践

### 前端实现建议

1. **本地存储 conversationId**:
```javascript
// 创建会话时保存
localStorage.setItem('currentConversationId', conversation.conversationId);

// 页面加载时检查
const savedConversationId = localStorage.getItem('currentConversationId');
if (savedConversationId) {
  // 尝试恢复会话
  await resumeConversation(savedConversationId);
}
```

2. **保存 lastEventId**:
```javascript
eventSource.addEventListener('message', (e) => {
  localStorage.setItem(
    `lastEventId_${conversationId}`,
    e.lastEventId
  );
});
```

3. **展示会话列表**:
```javascript
// 在项目页面展示该项目的活跃会话
const conversations = await fetchConversations({
  userId: currentUser.id,
  projectId: currentProject.id,
  status: 'IDLE,READY,RUNNING'
});

// 用户可以选择恢复或创建新会话
```

### 后端实现要点

1. **Conversation 状态同步**:
   - 定期轮询 OpenHands 获取 Conversation 状态
   - 更新本地数据库中的状态

2. **沙箱保活与恢复**:
   - OpenHands 沙箱可能因超时而停止（但未销毁）
   - 恢复会话时需要检查沙箱状态：
     - `RUNNING`: 直接恢复
     - `STOPPED`: 调用 OpenHands API 重启沙箱
     - `MISSING`: 提示用户沙箱已销毁，需要创建新会话

3. **Event 缓存**:
   - 缓存最近的 Event（如最近 1000 条）
   - 用于快速恢复和断线重连

4. **孤儿实例清理**:
   - 定期扫描 Docker 容器，检测孤儿实例
   - 孤儿实例：Docker 容器存在但数据库中无对应 Conversation 记录
   - 自动清理超过阈值的孤儿实例

### 孤儿实例处理

**孤儿实例定义**:
- Docker 容器正在运行
- 但数据库中没有对应的 Conversation 记录
- 或 Conversation 记录的 sandboxId 与实际容器不匹配

**产生原因**:
- 程序崩溃导致数据库记录未保存
- 数据库回滚但 Docker 容器已创建
- 手动删除数据库记录但未删除容器
- 网络故障导致状态不一致

**检测机制**:
```java
@Scheduled(fixedRate = 300000) // 每 5 分钟执行一次
public void detectOrphanedContainers() {
    // 1. 获取所有运行中的 OpenHands 容器
    List<Container> runningContainers = dockerClient.listContainers(
        new ListContainersCmd()
            .withLabelFilter("app=openhands")
            .withStatusFilter("running")
    );

    // 2. 获取数据库中所有活跃的 Conversation
    List<Conversation> activeConversations = conversationRepository
        .findByStatusIn(Arrays.asList(
            ConversationStatus.STARTING,
            ConversationStatus.READY,
            ConversationStatus.RUNNING,
            ConversationStatus.IDLE
        ));

    Set<String> trackedSandboxIds = activeConversations.stream()
        .map(Conversation::getSandboxId)
        .collect(Collectors.toSet());

    // 3. 找出孤儿容器
    List<Container> orphanedContainers = runningContainers.stream()
        .filter(container -> !trackedSandboxIds.contains(container.getId()))
        .collect(Collectors.toList());

    // 4. 处理孤儿容器
    for (Container orphan : orphanedContainers) {
        handleOrphanedContainer(orphan);
    }
}

private void handleOrphanedContainer(Container container) {
    // 检查容器创建时间
    long createdAt = container.getCreated();
    long ageInMinutes = (System.currentTimeMillis() / 1000 - createdAt) / 60;

    if (ageInMinutes > 30) {
        // 超过 30 分钟的孤儿容器，直接清理
        log.warn("清理孤儿容器: id={}, age={}分钟", container.getId(), ageInMinutes);
        dockerClient.stopContainer(container.getId());
        dockerClient.removeContainer(container.getId());
    } else {
        // 新创建的容器，可能是正在创建中，记录日志观察
        log.info("发现疑似孤儿容器: id={}, age={}分钟", container.getId(), ageInMinutes);
    }
}
```

**清理策略**:
- 容器年龄 < 30 分钟：观察，可能是正在创建中
- 容器年龄 >= 30 分钟：停止并删除
- 提供手动清理接口供运维使用

**预防措施**:
```java
@Transactional
public Conversation createConversation(CreateConversationRequest request) {
    // 1. 先创建数据库记录（状态为 CREATING）
    Conversation conversation = Conversation.builder()
        .userId(request.getUserId())
        .projectId(request.getProjectId())
        .status(ConversationStatus.CREATING)
        .build();
    conversation = conversationRepository.save(conversation);

    try {
        // 2. 调用 OpenHands 创建沙箱
        String sandboxId = openHandsClient.createSandbox(request);

        // 3. 更新数据库记录
        conversation.setSandboxId(sandboxId);
        conversation.setStatus(ConversationStatus.STARTING);
        conversationRepository.save(conversation);

        return conversation;

    } catch (Exception e) {
        // 4. 创建失败，清理数据库记录
        conversationRepository.delete(conversation);
        throw e;
    }
}
```

**运维接口**:
```java
@RestController
@RequestMapping("/api/admin/maintenance")
public class MaintenanceController {

    @PostMapping("/cleanup-orphaned-containers")
    public ResponseEntity<ApiResponse<CleanupResult>> cleanupOrphanedContainers(
        @RequestParam(defaultValue = "30") int minAgeMinutes) {

        CleanupResult result = maintenanceService.cleanupOrphanedContainers(minAgeMinutes);
        return ResponseEntity.ok(ApiResponse.success("清理完成", result));
    }

    @GetMapping("/orphaned-containers")
    public ResponseEntity<ApiResponse<List<OrphanedContainerInfo>>> listOrphanedContainers() {
        List<OrphanedContainerInfo> orphans = maintenanceService.listOrphanedContainers();
        return ResponseEntity.ok(ApiResponse.success(orphans));
    }
}
```

### 沙箱状态处理

**检查并恢复沙箱**:
```java
@Service
public class ConversationService {

    public Conversation resumeConversation(String conversationId) {
        Conversation conversation = getConversation(conversationId);

        // 检查沙箱状态
        SandboxStatus sandboxStatus = openHandsClient.getSandboxStatus(conversation.getSandboxId());

        switch (sandboxStatus) {
            case RUNNING:
                // 沙箱运行中，直接恢复
                conversation.setStatus(ConversationStatus.READY);
                break;

            case STOPPED:
                // 沙箱已停止，尝试重启
                log.info("沙箱已停止，正在重启: {}", conversation.getSandboxId());
                openHandsClient.startSandbox(conversation.getSandboxId());
                conversation.setStatus(ConversationStatus.STARTING);
                break;

            case MISSING:
                // 沙箱已销毁，无法恢复
                log.warn("沙箱已销毁，无法恢复会话: {}", conversationId);
                conversation.setStatus(ConversationStatus.ERROR);
                throw new ConversationException("沙箱已销毁，请创建新会话");

            default:
                throw new ConversationException("未知的沙箱状态: " + sandboxStatus);
        }

        return conversation;
    }
}
```

**前端处理**:
```javascript
async function resumeConversation(conversationId) {
  try {
    const response = await fetch(`/api/conversations/${conversationId}`);
    const { data: conversation } = await response.json();

    if (conversation.status === 'ERROR') {
      // 沙箱已销毁，提示用户
      alert('会话已过期，请创建新会话');
      return null;
    }

    if (conversation.status === 'STARTING') {
      // 沙箱正在重启，显示加载状态
      showLoading('正在恢复会话...');

      // 等待沙箱就绪
      await waitForConversationReady(conversationId);
    }

    // 恢复成功，重新订阅事件流
    subscribeToEvents(conversationId);
    return conversation;

  } catch (error) {
    console.error('恢复会话失败:', error);
    return null;
  }
}
```

---

## 实现优先级

### Phase 1 - 核心功能（MVP）
1. Conversation 创建和管理
2. 消息发送
3. Event 轮询器
4. SSE 事件流
5. OpenHands API 客户端
6. 验证服务集成

### Phase 2 - 增强功能
7. Event 历史查询
8. Conversation 列表查询
9. 错误处理和重试
10. 连接管理和清理

### Phase 3 - 高级功能
11. 工具调用审批（可选）
12. 性能优化（减少轮询频率）
13. 事件持久化
14. 监控和日志

---

## 配置项

```yaml
foggy:
  coding-agent:
    openhands:
      url: http://localhost:3000  # OpenHands 服务地址
      api-version: v1
    validation:
      url: http://localhost:7108  # 验证服务地址
      enabled: true
      auto-trigger: false  # 是否自动触发验证（默认手动）
    event-polling:
      interval: 2000  # 轮询间隔（毫秒）
      batch-size: 100  # 每次获取的事件数量
```

---

## 错误码

| 错误码 | 说明 |
|--------|------|
| INVALID_PARAM | 参数错误 |
| CONVERSATION_NOT_FOUND | Conversation 不存在 |
| CONVERSATION_NOT_READY | Conversation 未就绪 |
| OPENHANDS_ERROR | OpenHands 通信错误 |
| VALIDATION_FAILED | 验证失败 |
| INTERNAL_ERROR | 内部错误 |

---

## 关键设计决策

1. ✅ **完全对齐 OpenHands**: 使用 Conversation 概念，放弃 Task 抽象
2. ✅ **SSE 封装**: 将轮询转换为 SSE，提供更好的前端体验
3. ✅ **简化 Phase 1**: 不实现工具审批，专注核心功能
4. ✅ **Namespace 策略**: `user-{userId}-{conversationId}` 确保隔离
5. ✅ **验证时机**: 默认手动触发，可配置为自动

---

## 下一步

请确认此设计，我将开始 Phase 1 的实现。
