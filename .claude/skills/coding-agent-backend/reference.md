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

## 配置参数完整列表

```yaml
foggy:
  coding-agent:
    openhands:
      image: ghcr.io/all-hands-ai/openhands:main
      workspace-base: /workspace
      container-timeout: 1800  # 秒
      max-concurrent: 10
      api-key: ${OPENAI_API_KEY}
      model-name: gpt-4
      api-base-url: ${OPENAI_API_BASE_URL:}

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
