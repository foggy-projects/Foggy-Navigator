# Coding Agent 后端技术参考

## 核心服务一览

| 服务 | 职责 | 关键方法 |
|-----|-----|---------|
| `ConversationService` | 会话生命周期管理 | `createConversation`, `getConversation`, `deleteConversation`, `stopConversation` |
| `MessageService` | 消息发送和查询 | `sendMessage`, `getMessages` |
| `EventService` | 事件存储和查询 | `saveEvent`, `getEvents`, `getEventsByKind`, `getEventsSince` |
| `ValidationService` | 验证触发和状态 | `triggerValidation`, `getValidationStatus`, `getValidationResults` |
| `ConversationCleanupService` | 定时清理 | `cleanupIdleSessions`, `cleanupExpiredSessions`, `performHealthChecks` |
| `OrphanDetectionService` | 孤儿容器检测 | `detectOrphanContainers`, `cleanupOrphans` |
| `ConversationRecoveryService` | 会话恢复 | `recoverConversation`, `recoverAllConversations`, `deleteConversation` |
| `OpenHandsEventPoller` | 轮询 OH agent server 事件 | `startPolling`, `stopPolling`, `isPolling`, `convertOhEvent`, `isTerminalEvent` |
| `OpenHandsMessageForwarder` | 转发用户消息到 OH | `onMessageSent` (@EventListener) |
| `OpenHandsInstanceManager` | 管理 OH Docker 容器 | `createInstance`, `stopInstance`, `buildEnvironmentVariables` |
| `OpenHandsClient` | OH HTTP 客户端 | `createConversation`, `sendMessage`, `getNewEvents`, `getConversationInfo` |
| `OpenHandsClientFactory` | 管理 OH 客户端实例 | `getClientForUser` |

## 事件类型

```java
public enum EventKind {
    CONVERSATION_STATUS,    // 会话状态变更
    MESSAGE_SENT,           // 消息已发送
    AGENT_ACTION,           // Agent 执行动作
    AGENT_OBSERVATION,      // Agent 观察结果
    VALIDATION_TRIGGERED,   // 验证已触发
    VALIDATION_RESULT,      // 验证结果
    ERROR                   // 错误事件
}
```

## 会话状态

```java
public enum ConversationStatus {
    STARTING,   // 正在启动容器
    READY,      // 容器就绪，可以交互
    RUNNING,    // 正在执行任务
    IDLE,       // 空闲状态
    ERROR,      // 发生错误
    STOPPED     // 已停止
}
```

## SSE 事件推送基础设施

### SseEventEmitter

位置：`api/sse/SseEventEmitter.java`

核心组件，负责管理 SSE 连接和事件推送。

```java
@Component
@Slf4j
public class SseEventEmitter {
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> conversationEmitters;
    private final ObjectMapper objectMapper;  // 必须注入 Spring ObjectMapper（支持 JSR310）
    private final ScheduledExecutorService heartbeatScheduler;

    // 创建新的 SSE 连接
    public SseEmitter createEmitter(String conversationId);

    // 发送事件（使用命名事件 "event"）
    public void sendEvent(String conversationId, Event event);

    // 定期心跳（每 15 秒）防止连接断开
    private void sendHeartbeats();
}
```

**关键实现细节：**

1. **ObjectMapper 必须注入 Spring 管理的实例**
   ```java
   // ❌ 错误：new ObjectMapper() 不支持 LocalDateTime
   private final ObjectMapper objectMapper = new ObjectMapper();

   // ✅ 正确：注入 Spring ObjectMapper（已注册 JavaTimeModule）
   public SseEventEmitter(ObjectMapper objectMapper) {
       this.objectMapper = objectMapper;
   }
   ```

2. **分离序列化和发送**
   ```java
   public void sendEvent(String conversationId, Event event) {
       // 先序列化，失败不移除 emitter
       String json;
       try {
           json = objectMapper.writeValueAsString(event);
       } catch (Exception e) {
           log.error("序列化失败", e);
           return;  // 不移除 emitter
       }

       // 再发送，失败移除 emitter
       emitters.removeIf(emitter -> {
           try {
               emitter.send(SseEmitter.event().name("event").data(json));
               return false;
           } catch (IOException e) {
               return true;  // 移除失效的 emitter
           }
       });
   }
   ```

3. **心跳调度器必须捕获异常**
   ```java
   private void sendHeartbeats() {
       try {
           // ... 心跳逻辑
       } catch (Exception e) {
           log.error("心跳调度异常（不影响后续心跳）", e);
       }
   }
   ```

### SseEventListener

位置：`api/sse/SseEventListener.java`

监听 Spring 事件并推送到 SSE 客户端。

```java
@Component
@Slf4j
public class SseEventListener {
    private final SseEventEmitter sseEventEmitter;

    @Async("eventPublisherExecutor")
    @EventListener
    public void onEvent(Event event) {
        sseEventEmitter.sendEvent(event.getConversationId(), event);
    }
}
```

### EventStreamController

位置：`api/controller/EventStreamController.java`

SSE 端点控制器。

```java
@RestController
@RequestMapping("/api/v1/events")
public class EventStreamController {

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String conversationId) {
        SseEmitter emitter = sseEventEmitter.createEmitter(conversationId);

        // 发送初始连接确认
        Event connectedEvent = Event.builder()
                .conversationId(conversationId)
                .kind(EventKind.CONVERSATION_STATUS)
                .data(Map.of("type", "connected"))
                .build();
        sseEventEmitter.sendEvent(conversationId, connectedEvent);

        return emitter;
    }
}
```

### 前端 SSE 集成

前端使用 `@foggy/chat` 的 `createSseClient`，需要配合 `EventAdapter` 将后端 `Event` 转换为 `AipMessage`。

- 事件名：后端 `.name("event")` → 前端 `eventName: 'event'`
- 开发模式绕过 Vite 代理直连后端（避免 SSE 缓冲问题）

---

## 线程池配置

### eventPublisherExecutor
- **用途**：事件发布和 SSE 推送
- **核心线程**：5
- **最大线程**：20
- **队列容量**：100
- **线程前缀**：`event-`

### validationExecutor
- **用途**：验证服务异步执行
- **核心线程**：3
- **最大线程**：10
- **队列容量**：50
- **线程前缀**：`validation-`

## OpenHands V1 Docker 环境变量参考

主容器 (`openhands-local:dev`) 需要以下环境变量：

| 环境变量 | 用途 | 示例 |
|---------|------|------|
| `LLM_API_KEY` | OH 配置系统读取的 API Key | `sk-xxx` |
| `LLM_MODEL` | 模型名（必须带 provider 前缀） | `openai/glm-4.7` |
| `LLM_BASE_URL` | LLM API 基础 URL | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| `OH_SECRET_KEY` | V1 必需的密钥 | `openhands-dev-secret-key-xxx` |
| `DISABLE_MCP` | 禁用 MCP 功能 | `true` |
| `AGENT_SERVER_IMAGE_REPOSITORY` | Agent server 镜像仓库 | `ghcr.io/openhands/agent-server` |
| `AGENT_SERVER_IMAGE_TAG` | Agent server 镜像 tag | `31536c8-python` |
| `OH_AGENT_SERVER_ENV` | 注入到 agent server 的环境变量 (JSON) | `{"OPENAI_API_KEY":"sk-xxx","OPENAI_API_BASE":"https://..."}` |
| `WORKSPACE_BASE` | 工作空间路径 | `/workspace` |
| `SANDBOX_LOCAL_RUNTIME_URL` | Sandbox 连接 URL | `http://host.docker.internal` |
| `SANDBOX_USER_ID` | Sandbox 用户 ID | `0` |

### LLM_API_KEY vs OPENAI_API_KEY 区分

- **OH 配置系统**（主容器内 `load_from_env`）：读 `LLM_` 前缀 → 用 `LLM_API_KEY`
- **litellm 运行时**（agent server 容器内）：读 `OPENAI_API_KEY` → 通过 `OH_AGENT_SERVER_ENV` 传递
- **model name**：litellm 需要 provider 前缀，如 `openai/glm-4.7`，否则报 `LLM Provider NOT provided`

### OH V1 Agent Server API

Agent server 的 `conversation_url` 从主容器 API 获取：

```
GET  /api/v1/app-conversations/{id}      → 返回 { conversation_url: "http://host:port" }
POST /api/v1/app-conversations/{id}/messages  → 发送消息
GET  {conversation_url}/events/search?limit=50&start_id={page_id}  → 查询事件
```

事件响应格式：
```json
{
  "items": [
    { "id": "uuid", "kind": "TerminalAction", "timestamp": "...", "command": "ls -la", ... },
    { "id": "uuid", "kind": "TerminalObservation", "timestamp": "...", "content": "file1.txt", ... }
  ],
  "next_page_id": null
}
```

## 配置参数完整列表

```yaml
foggy:
  coding-agent:
    openhands:
      image: openhands-local:dev           # OH V1 本地镜像
      workspace-base: /workspace
      container-timeout: 1800  # 秒
      max-concurrent: 10
      api-key: ${LLM_API_KEY}
      model-name: openai/glm-4.7           # ⚠️ 必须带 provider 前缀
      api-base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
      agent-server-image: ghcr.io/openhands/agent-server
      agent-server-tag: 31536c8-python
      oh-secret-key: openhands-dev-secret-key-1234567890

    validation:
      url: http://localhost:7108
      enabled: true
      realtime: true
      timeout: 30000  # 毫秒
      auto-trigger-on-create: true
      auto-trigger-on-message: false

    git:
      default-branch: main
      auto-commit: true
      auto-pr: true

    cleanup:
      enabled: true
      interval: 300000  # 5分钟
      idle-timeout: 3600000  # 1小时
      max-age: 86400000  # 1天
      max-age-check-interval: 3600000  # 1小时
      orphan-interval: 600000  # 10分钟

    health:
      check-interval: 60000  # 1分钟
      enabled: true

    recovery:
      auto-recover-on-startup: false
```

## 常用代码模式

### 异步事件发布

```java
@Async("eventPublisherExecutor")
@EventListener
public void onEvent(Event event) {
    try {
        eventService.saveEvent(event);
        sseEventEmitter.sendEvent(event.getConversationId(), event);
    } catch (Exception e) {
        log.error("处理事件失败: {}", e.getMessage(), e);
    }
}
```

### 定时任务

```java
@Scheduled(fixedDelayString = "${foggy.coding-agent.cleanup.interval:300000}", initialDelay = 60000)
@Transactional
public void cleanupIdleSessions() {
    // 清理逻辑
}
```

### 条件化 Bean

```java
@Service
@ConditionalOnProperty(prefix = "foggy.coding-agent.cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ConversationCleanupService {
    // 只在 cleanup.enabled=true 时加载
}
```

### 事务管理

```java
@Transactional
public Conversation createConversation(CreateConversationRequest request) {
    // 整个方法在一个事务中
}

@Transactional(readOnly = true)
public List<Conversation> listConversations(String userId) {
    // 只读事务，性能更好
}
```

## Repository 查询方法命名规则

| 方法名模式 | SQL 等效 |
|-----------|---------|
| `findByXxx(xxx)` | `WHERE xxx = ?` |
| `findByXxxAndYyy(xxx, yyy)` | `WHERE xxx = ? AND yyy = ?` |
| `findByXxxBefore(time)` | `WHERE xxx < ?` |
| `findByXxxAfter(time)` | `WHERE xxx > ?` |
| `existsByXxx(xxx)` | `SELECT 1 WHERE xxx = ? LIMIT 1` |
| `deleteByXxx(xxx)` | `DELETE WHERE xxx = ?` |
| `countByXxx(xxx)` | `SELECT COUNT(*) WHERE xxx = ?` |

## 测试工具

### Mock 注入

```java
@ExtendWith(MockitoExtension.class)
class ServiceTest {
    @Mock
    private Repository repository;

    @InjectMocks
    private Service service;
}
```

### @Value 字段设置

```java
@BeforeEach
void setUp() {
    ReflectionTestUtils.setField(service, "fieldName", value);
}
```

### 验证调用

```java
verify(mock).method(arg);                    // 验证调用一次
verify(mock, times(2)).method(arg);          // 验证调用两次
verify(mock, never()).method(any());         // 验证从未调用
verify(mock, atLeastOnce()).method(any());   // 验证至少调用一次
```

### 参数捕获

```java
ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
verify(publisher).publishEvent(captor.capture());
Event captured = captor.getValue();
assertEquals(expectedKind, captured.getKind());
```

## 错误处理模式

### Service 层

```java
public Conversation getConversation(String conversationId) {
    Conversation conversation = cache.get(conversationId);
    if (conversation == null) {
        conversation = loadFromDatabase(conversationId);
    }
    if (conversation == null) {
        throw new NotFoundException("会话不存在: " + conversationId);
    }
    return conversation;
}
```

### Controller 层

```java
@GetMapping("/{conversationId}")
public ResponseEntity<Conversation> getConversation(@PathVariable String conversationId) {
    try {
        Conversation conversation = conversationService.getConversation(conversationId);
        return ResponseEntity.ok(conversation);
    } catch (NotFoundException e) {
        return ResponseEntity.notFound().build();
    }
}
```

## 日志规范

```java
// 方法入口
log.info("执行操作: param1={}, param2={}", param1, param2);

// 关键步骤
log.debug("中间状态: state={}", state);

// 警告
log.warn("异常情况但可恢复: reason={}", reason);

// 错误
log.error("操作失败: param={}, error={}", param, e.getMessage(), e);
```
