# 会话模块详细设计文档

## 1. 概述

### 1.1 模块定位
会话模块是动态 Agent 编排系统的核心组件，负责管理用户与 Agent 之间的完整对话生命周期，包括消息存储、上下文管理、状态维护等关键功能。

### 1.2 设计原则
- **完整性**: 完整记录对话历史，不丢失任何信息
- **一致性**: 保证上下文状态的一致性
- **可追溯**: 支持完整的审计和回溯
- **高性能**: 支持高并发场景下的快速响应
- **可扩展**: 支持未来功能扩展

## 2. 会话生命周期

### 2.1 状态机设计

```
┌──────────┐
│ CREATED  │  ← 会话创建
└────┬─────┘
     │
     ↓
┌──────────┐
│ ACTIVE   │  ← 活跃对话中
└────┬─────┘
     │
     ├───────────────┐
     ↓               ↓
┌──────────┐   ┌──────────┐
│ PAUSED   │   │ ENDED    │
└────┬─────┘   └──────────┘
     │
     ↓
┌──────────┐
│ ARCHIVED │  ← 归档状态
└──────────┘
```

### 2.2 状态转换说明

| 当前状态 | 目标状态 | 触发条件 | 说明 |
|---------|---------|---------|------|
| CREATED | ACTIVE | 用户发送第一条消息 | 会话进入活跃状态 |
| ACTIVE | PAUSED | 用户暂停会话 | 临时暂停，可恢复 |
| ACTIVE | ENDED | 用户主动结束或超时 | 会话正常结束 |
| PAUSED | ACTIVE | 用户恢复会话 | 继续对话 |
| ENDED | ARCHIVED | 归档任务触发 | 长期存储 |
| ACTIVE | ARCHIVED | 系统归档策略 | 自动归档 |

### 2.3 会话超时机制
- **空闲超时**: 30 分钟无活动自动暂停
- **最大时长**: 24 小时自动结束
- **归档周期**: 结束后 7 天自动归档
- **配置化**: 所有超时参数可配置

## 3. 消息模型设计

### 3.1 消息类型

#### 3.1.1 基础消息类型
```java
public enum MessageType {
    USER,           // 用户消息
    ASSISTANT,      // AI 助手回复
    SYSTEM,         // 系统提示
    TOOL_CALL,      // 工具调用
    TOOL_RESULT,    // 工具执行结果
    ERROR,          // 错误消息
    THINKING,       // 思考过程（可选）
    METADATA        // 元数据消息
}
```

#### 3.1.2 消息结构
```java
public class Message {
    private Long id;
    private Long sessionId;
    private MessageType type;
    private String content;
    private Map<String, Object> metadata;
    private Integer tokenCount;
    private Long durationMs;
    private String modelUsed;
    private Integer retryCount;
    private String errorMessage;
    private LocalDateTime createdAt;
    private Integer sequenceNumber;  // 消息序号，保证顺序
}
```

### 3.2 消息元数据

#### 3.2.1 通用元数据
```json
{
  "source": "web|api|mobile",
  "user_agent": "Mozilla/5.0...",
  "ip_address": "192.168.1.1",
  "request_id": "uuid",
  "trace_id": "uuid"
}
```

#### 3.2.2 ASSISTANT 消息元数据
```json
{
  "model": "gpt-4",
  "temperature": 0.7,
  "max_tokens": 2000,
  "finish_reason": "stop|length|content_filter",
  "prompt_tokens": 150,
  "completion_tokens": 300,
  "total_tokens": 450
}
```

#### 3.2.3 TOOL_CALL 消息元数据
```json
{
  "tool_name": "code_interpreter",
  "tool_id": "uuid",
  "arguments": {"code": "print('hello')"},
  "execution_time_ms": 1500
}
```

#### 3.2.4 TOOL_RESULT 消息元数据
```json
{
  "tool_id": "uuid",
  "success": true,
  "result": "hello",
  "error": null,
  "execution_time_ms": 1500
}
```

### 3.3 消息存储策略

#### 3.3.1 存储分层
- **热数据** (最近 7 天): 主数据库，快速查询
- **温数据** (7-30 天): 主数据库，普通查询
- **冷数据** (30 天以上): 归档存储，压缩存储

#### 3.3.2 消息索引
```sql
CREATE INDEX idx_session_id ON messages(session_id);
CREATE INDEX idx_created_at ON messages(created_at);
CREATE INDEX idx_session_created ON messages(session_id, created_at);
CREATE INDEX idx_message_type ON messages(message_type);
```

## 4. 上下文管理

### 4.1 上下文窗口管理

#### 4.1.1 上下文窗口策略
```java
public class ContextWindowConfig {
    private int maxTokens;           // 最大 Token 数（如 4000）
    private int reserveTokens;       // 预留 Token 数（如 500）
    private int systemPromptTokens;  // 系统提示 Token 数
    private int maxHistoryMessages;  // 最大历史消息数
}
```

#### 4.1.2 上下文计算
```java
public int calculateAvailableTokens(ContextWindowConfig config) {
    return config.getMaxTokens()
           - config.getReserveTokens()
           - config.getSystemPromptTokens();
}

public List<Message> selectMessagesForContext(
    List<Message> history,
    int availableTokens
) {
    List<Message> selected = new ArrayList<>();
    int usedTokens = 0;

    // 从最新消息开始倒序选择
    for (int i = history.size() - 1; i >= 0; i--) {
        Message msg = history.get(i);
        if (usedTokens + msg.getTokenCount() <= availableTokens) {
            selected.add(0, msg);
            usedTokens += msg.getTokenCount();
        } else {
            break;
        }
    }

    return selected;
}
```

### 4.2 上下文压缩策略

#### 4.2.1 压缩策略类型

##### 策略 1: 基于时间的压缩
```java
public class TimeBasedCompressionStrategy implements CompressionStrategy {
    @Override
    public List<Message> compress(List<Message> messages, int targetTokens) {
        // 保留最近 N 条消息
        int recentCount = calculateRecentCount(messages, targetTokens);
        return messages.subList(
            Math.max(0, messages.size() - recentCount),
            messages.size()
        );
    }
}
```

##### 策略 2: 基于重要性的压缩
```java
public class ImportanceBasedCompressionStrategy implements CompressionStrategy {
    @Override
    public List<Message> compress(List<Message> messages, int targetTokens) {
        // 计算每条消息的重要性分数
        List<ScoredMessage> scored = messages.stream()
            .map(this::calculateImportance)
            .sorted(Comparator.comparingDouble(ScoredMessage::getScore).reversed())
            .collect(Collectors.toList());

        // 选择最重要的消息
        List<Message> selected = new ArrayList<>();
        int usedTokens = 0;

        for (ScoredMessage scoredMsg : scored) {
            if (usedTokens + scoredMsg.getTokenCount() <= targetTokens) {
                selected.add(scoredMsg.getMessage());
                usedTokens += scoredMsg.getTokenCount();
            }
        }

        // 按时间顺序返回
        return selected.stream()
            .sorted(Comparator.comparing(Message::getCreatedAt))
            .collect(Collectors.toList());
    }

    private ScoredMessage calculateImportance(Message msg) {
        double score = 0.0;

        // 用户消息权重更高
        if (msg.getType() == MessageType.USER) {
            score += 2.0;
        }

        // 工具调用结果很重要
        if (msg.getType() == MessageType.TOOL_RESULT) {
            score += 1.5;
        }

        // 错误消息很重要
        if (msg.getType() == MessageType.ERROR) {
            score += 2.0;
        }

        // 最近的消息权重更高
        long hoursAgo = ChronoUnit.HOURS.between(
            msg.getCreatedAt(),
            LocalDateTime.now()
        );
        score += Math.max(0, 10 - hoursAgo);

        return new ScoredMessage(msg, score);
    }
}
```

##### 策略 3: 智能摘要压缩
```java
public class SummaryCompressionStrategy implements CompressionStrategy {
    private final ChatLanguageModel summarizer;

    @Override
    public List<Message> compress(List<Message> messages, int targetTokens) {
        if (calculateTotalTokens(messages) <= targetTokens) {
            return messages;
        }

        // 将旧消息分组
        List<List<Message>> groups = groupMessagesByTime(messages, 5);

        // 为每个组生成摘要
        List<Message> compressed = new ArrayList<>();
        int usedTokens = 0;

        for (List<Message> group : groups) {
            if (usedTokens + calculateTotalTokens(group) <= targetTokens) {
                compressed.addAll(group);
                usedTokens += calculateTotalTokens(group);
            } else {
                // 生成摘要
                String summary = generateSummary(group);
                Message summaryMsg = createSummaryMessage(summary);
                compressed.add(summaryMsg);
                break;
            }
        }

        return compressed;
    }

    private String generateSummary(List<Message> messages) {
        String conversation = messages.stream()
            .map(m -> m.getType() + ": " + m.getContent())
            .collect(Collectors.joining("\n"));

        Prompt prompt = Prompt.from(
            "请用中文简要总结以下对话的关键信息（不超过 200 字）：\n" + conversation
        );

        return summarizer.generate(prompt).content();
    }
}
```

#### 4.2.2 压缩策略选择
```java
public class CompressionStrategySelector {
    public CompressionStrategy selectStrategy(Session session) {
        // 根据会话特征选择策略
        if (session.getMessageCount() < 10) {
            return new NoCompressionStrategy();
        }

        if (session.getDurationHours() < 1) {
            return new TimeBasedCompressionStrategy();
        }

        if (hasComplexTasks(session)) {
            return new ImportanceBasedCompressionStrategy();
        }

        return new SummaryCompressionStrategy();
    }
}
```

### 4.3 上下文持久化

#### 4.3.1 上下文快照
```java
public class ContextSnapshot {
    private Long id;
    private Long sessionId;
    private String snapshotName;
    private String contextData;  // JSON 格式
    private int tokenCount;
    private LocalDateTime createdAt;
    private String createdBy;  // user or system
}
```

#### 4.3.2 快照操作
```java
public interface ContextSnapshotService {
    // 创建快照
    ContextSnapshot createSnapshot(Long sessionId, String name);

    // 恢复快照
    void restoreSnapshot(Long snapshotId);

    // 列出快照
    List<ContextSnapshot> listSnapshots(Long sessionId);

    // 删除快照
    void deleteSnapshot(Long snapshotId);

    // 自动快照（在关键节点）
    void autoSnapshot(Session session, String reason);
}
```

#### 4.3.3 自动快照触发点
- 会话创建时
- 上下文压缩前
- 任务开始前
- 任务完成后
- 错误发生时
- 定时快照（每小时）

## 5. 会话存储与检索

### 5.1 数据库设计

#### 5.1.1 会话表 (sessions)
```sql
CREATE TABLE sessions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    title VARCHAR(255),
    status ENUM('CREATED', 'ACTIVE', 'PAUSED', 'ENDED', 'ARCHIVED') NOT NULL,
    context_data TEXT,
    context_token_count INT DEFAULT 0,
    message_count INT DEFAULT 0,
    total_tokens INT DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    ended_at TIMESTAMP NULL,
    archived_at TIMESTAMP NULL,

    INDEX idx_user_id (user_id),
    INDEX idx_agent_id (agent_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at),
    INDEX idx_user_created (user_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

#### 5.1.2 消息表 (messages)
```sql
CREATE TABLE messages (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    message_type ENUM('USER', 'ASSISTANT', 'SYSTEM', 'TOOL_CALL', 'TOOL_RESULT', 'ERROR', 'THINKING', 'METADATA') NOT NULL,
    content TEXT NOT NULL,
    metadata JSON,
    token_count INT DEFAULT 0,
    duration_ms BIGINT DEFAULT 0,
    model_used VARCHAR(100),
    retry_count INT DEFAULT 0,
    error_message TEXT,
    sequence_number INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_session_id (session_id),
    INDEX idx_created_at (created_at),
    INDEX idx_session_created (session_id, created_at),
    INDEX idx_message_type (message_type),
    INDEX idx_sequence_number (session_id, sequence_number),

    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

#### 5.1.3 上下文快照表 (context_snapshots)
```sql
CREATE TABLE context_snapshots (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    snapshot_name VARCHAR(255) NOT NULL,
    context_data TEXT NOT NULL,
    token_count INT NOT NULL,
    created_by VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_session_id (session_id),
    INDEX idx_created_at (created_at),

    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 5.2 查询优化

#### 5.2.1 分页查询
```java
public Page<Message> getMessages(Long sessionId, int page, int size) {
    return messageRepository.findBySessionIdOrderByCreatedAtAsc(
        sessionId,
        PageRequest.of(page, size)
    );
}
```

#### 5.2.2 游标分页（大数据量）
```java
public List<Message> getMessagesAfterCursor(
    Long sessionId,
    Long lastMessageId,
    int limit
) {
    return messageRepository.findBySessionIdAndIdGreaterThanOrderByIdAsc(
        sessionId,
        lastMessageId,
        limit
    );
}
```

#### 5.2.3 会话列表查询
```java
public Page<Session> getUserSessions(
    Long userId,
    SessionStatus status,
    LocalDateTime startTime,
    LocalDateTime endTime,
    Pageable pageable
) {
    Specification<Session> spec = Specification.where(null);

    spec = spec.and((root, query, cb) ->
        cb.equal(root.get("userId"), userId)
    );

    if (status != null) {
        spec = spec.and((root, query, cb) ->
            cb.equal(root.get("status"), status)
        );
    }

    if (startTime != null) {
        spec = spec.and((root, query, cb) ->
            cb.greaterThanOrEqualTo(root.get("createdAt"), startTime)
        );
    }

    if (endTime != null) {
        spec = spec.and((root, query, cb) ->
            cb.lessThanOrEqualTo(root.get("createdAt"), endTime)
        );
    }

    return sessionRepository.findAll(spec, pageable);
}
```

### 5.3 缓存策略

#### 5.3.1 多级缓存
```java
public class SessionCache {
    private final Cache<Long, Session> l1Cache;  // 本地缓存 (Caffeine)
    private final RedisTemplate<String, Session> l2Cache;  // Redis 缓存

    public Session getSession(Long sessionId) {
        // L1 缓存
        Session session = l1Cache.getIfPresent(sessionId);
        if (session != null) {
            return session;
        }

        // L2 缓存
        String key = "session:" + sessionId;
        session = l2Cache.opsForValue().get(key);
        if (session != null) {
            l1Cache.put(sessionId, session);
            return session;
        }

        // 数据库
        session = sessionRepository.findById(sessionId).orElse(null);
        if (session != null) {
            l2Cache.opsForValue().set(key, session, 1, TimeUnit.HOURS);
            l1Cache.put(sessionId, session);
        }

        return session;
    }
}
```

#### 5.3.2 缓存失效策略
- 写入时失效
- 定时刷新（每 5 分钟）
- 主动刷新（关键操作后）

## 6. 会话安全与隐私

### 6.1 数据加密

#### 6.1.1 敏感信息脱敏
```java
public class SensitiveDataMasker {
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b"
    );
    private static final Pattern PHONE_PATTERN = Pattern.compile(
        "\\b1[3-9]\\d{9}\\b"
    );
    private static final Pattern ID_CARD_PATTERN = Pattern.compile(
        "\\b[1-9]\\d{5}(18|19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx]\\b"
    );

    public String maskSensitiveData(String content) {
        content = EMAIL_PATTERN.matcher(content).replaceAll("***@***.***");
        content = PHONE_PATTERN.matcher(content).replaceAll("***********");
        content = ID_CARD_PATTERN.matcher(content).replaceAll("******************");
        return content;
    }
}
```

#### 6.1.2 加密存储
```java
public class MessageEncryptionService {
    private final AESUtil aesUtil;

    public void encryptMessage(Message message) {
        if (containsSensitiveData(message.getContent())) {
            String encrypted = aesUtil.encrypt(message.getContent());
            message.setContent(encrypted);
            message.getMetadata().put("encrypted", true);
        }
    }

    public String decryptMessage(String encryptedContent) {
        return aesUtil.decrypt(encryptedContent);
    }
}
```

### 6.2 访问控制

#### 6.2.1 权限检查
```java
public class SessionAccessControl {
    public boolean canAccessSession(Long userId, Long sessionId) {
        Session session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return false;
        }

        // 只有会话创建者可以访问
        return session.getUserId().equals(userId);
    }

    public boolean canDeleteSession(Long userId, Long sessionId) {
        return canAccessSession(userId, sessionId);
    }

    public boolean canExportSession(Long userId, Long sessionId) {
        return canAccessSession(userId, sessionId);
    }
}
```

#### 6.2.2 审计日志
```java
public class SessionAuditLog {
    private Long id;
    private Long sessionId;
    private Long userId;
    private String action;  // CREATE, READ, UPDATE, DELETE, EXPORT
    private String details;
    private String ipAddress;
    private String userAgent;
    private LocalDateTime timestamp;
}
```

## 7. 性能优化

### 7.1 批量操作

#### 7.1.1 批量插入消息
```java
public void batchInsertMessages(List<Message> messages) {
    final int BATCH_SIZE = 100;

    for (int i = 0; i < messages.size(); i += BATCH_SIZE) {
        int end = Math.min(i + BATCH_SIZE, messages.size());
        List<Message> batch = messages.subList(i, end);
        messageRepository.saveAll(batch);
    }
}
```

#### 7.1.2 批量查询
```java
public Map<Long, List<Message>> getMessagesForSessions(List<Long> sessionIds) {
    return sessionIds.stream()
        .collect(Collectors.toMap(
            Function.identity(),
            id -> messageRepository.findBySessionIdOrderByCreatedAtAsc(id)
        ));
}
```

### 7.2 异步处理

#### 7.2.1 异步保存消息
```java
@Async
public void saveMessageAsync(Message message) {
    messageRepository.save(message);
    // 更新会话统计
    updateSessionStats(message.getSessionId());
    // 清除缓存
    sessionCache.evict(message.getSessionId());
}
```

#### 7.2.2 异步压缩上下文
```java
@Async
public void compressContextAsync(Long sessionId) {
    Session session = sessionRepository.findById(sessionId).orElse(null);
    if (session == null) return;

    CompressionStrategy strategy = compressionStrategySelector.selectStrategy(session);
    List<Message> compressed = strategy.compress(
        session.getMessages(),
        session.getContextTokenCount()
    );

    session.setContextData(JsonUtil.toJson(compressed));
    sessionRepository.save(session);
}
```

### 7.3 连接池配置

#### 7.3.1 数据库连接池
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
```

#### 7.3.2 Redis 连接池
```yaml
spring:
  redis:
    lettuce:
      pool:
        max-active: 20
        max-idle: 10
        min-idle: 5
        max-wait: 3000
```

## 8. 监控与告警

### 8.1 关键指标

#### 8.1.1 会话指标
- 活跃会话数
- 会话创建速率
- 会话平均时长
- 会话平均消息数
- 会话失败率

#### 8.1.2 消息指标
- 消息吞吐量
- 消息平均响应时间
- 消息 Token 使用量
- 消息错误率

#### 8.1.3 上下文指标
- 上下文平均 Token 数
- 上下文压缩频率
- 上下文压缩比例
- 上下文恢复成功率

### 8.2 告警规则

```java
public class SessionAlertRules {
    // 活跃会话数超过阈值
    @Alert(threshold = 1000, operator = ">")
    public void alertActiveSessionCount(int count) {
        log.warn("Active session count too high: {}", count);
    }

    // 消息响应时间超过阈值
    @Alert(threshold = 5000, operator = ">")
    public void alertMessageResponseTime(long durationMs) {
        log.warn("Message response time too high: {}ms", durationMs);
    }

    // 会话失败率超过阈值
    @Alert(threshold = 0.05, operator = ">")
    public void alertSessionFailureRate(double rate) {
        log.warn("Session failure rate too high: {}%", rate * 100);
    }
}
```

## 9. 最佳实践

### 9.1 消息序号管理
- 使用数据库自增 ID 或分布式 ID 生成器
- 保证消息序号的连续性和唯一性
- 使用序号进行消息排序和去重

### 9.2 上下文管理
- 定期评估上下文压缩策略的效果
- 根据业务场景调整压缩阈值
- 保留关键消息的完整信息

### 9.3 数据归档
- 实现自动归档策略
- 归档数据压缩存储
- 支持归档数据查询

### 9.4 错误处理
- 记录详细的错误信息
- 实现自动重试机制
- 提供错误恢复能力

### 9.5 测试策略
- 单元测试覆盖核心逻辑
- 集成测试验证完整流程
- 性能测试确保系统容量
- 压力测试验证极限场景

## 10. 扩展功能

### 10.1 会话导出
- 支持多种导出格式（JSON、Markdown、PDF）
- 支持选择性导出（时间范围、消息类型）
- 导出数据脱敏处理

### 10.2 会话分享
- 生成分享链接
- 设置分享有效期
- 访问权限控制
- 访问次数限制

### 10.3 会话搜索
- 全文搜索消息内容
- 按时间范围筛选
- 按消息类型筛选
- 按关键词高亮

### 10.4 会话分析
- 对话情感分析
- 主题聚类分析
- 用户意图识别
- Agent 表现评估

## 11. 技术选型建议

### 11.1 数据库
- **主数据库**: MySQL 8.0+ 或 PostgreSQL 14+
- **缓存**: Redis 6.0+
- **搜索引擎**: Elasticsearch（可选，用于全文搜索）

### 11.2 消息队列
- **异步处理**: RabbitMQ 或 Kafka
- **延迟任务**: Redis + Redisson 或 RabbitMQ 延迟队列

### 11.3 监控工具
- **指标监控**: Prometheus + Grafana
- **日志收集**: ELK Stack 或 Loki
- **链路追踪**: Jaeger 或 SkyWalking

## 12. 实施计划

### Phase 1: 基础功能（2 周）
- 数据库设计与建表
- 会话 CRUD 操作
- 消息存储与查询
- 基础缓存实现

### Phase 2: 上下文管理（2 周）
- 上下文窗口管理
- 基础压缩策略
- 上下文快照功能

### Phase 3: 高级功能（2 周）
- 智能压缩策略
- 异步处理优化
- 批量操作支持

### Phase 4: 安全与监控（1 周）
- 数据加密与脱敏
- 访问控制
- 监控指标与告警

### Phase 5: 扩展功能（2 周）
- 会话导出
- 会话搜索
- 会话分析

### Phase 6: 测试与优化（1 周）
- 单元测试
- 集成测试
- 性能测试
- 压力测试
