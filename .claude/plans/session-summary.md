# 会话归档/搁置时 AI 摘要生成方案

## 需求概述

当会话被归档（ARCHIVED）或搁置（ON_HOLD）时，通过定时任务扫描，用 AI 生成摘要写入 SessionEntity，为后续检索做准备。

## 数据模型变更

### SessionEntity 新增字段

```java
// SessionEntity.java (navigator-common)
@Column(columnDefinition = "TEXT")
private String summary;               // AI 生成的摘要

@Column
private LocalDateTime summaryGeneratedAt;  // 摘要生成时间
```

- `summary`：TEXT 类型，存放 AI 摘要（通常 100-300 字）
- `summaryGeneratedAt`：摘要生成时间戳，用于判断是否需要重新生成

### 判断逻辑

需要生成摘要的条件：
- ConversationConfig.interactionState = `ARCHIVED` 或 `ON_HOLD`
- SessionEntity.summary 为 `null`（未生成过）

## 实现位置

在 **task-assistant** 模块实现（用户口中的"assistant模块"），复用已有的：
- `ClaudeWorkerFacade.syncQuery()` — 轻量调用，不创建 AgentTask 记录
- `TaskAssistantConfigEntity` — 获取 workerId、cwd、model 等配置
- `@Scheduled` 定时任务模式

## 新增组件

### 1. SessionSummaryService（task-assistant 模块）

```
addons/task-assistant/src/main/java/com/foggy/navigator/task/assistant/service/SessionSummaryService.java
```

**职责**：定时扫描 + 调用 LLM 生成摘要 + 写回 SessionEntity

**核心流程**：

```
@Scheduled(cron = "0 */10 * * * *")   // 每10分钟扫描一次
public void generatePendingSummaries() {
    // 1. 查询所有 ARCHIVED / ON_HOLD 状态的 sessionId
    //    → ConversationConfigRepository（已有方法）

    // 2. 过滤出 summary 为 null 的 SessionEntity
    //    → SessionRepository（新增方法）

    // 3. 对每个待处理的 session：
    //    a. 获取消息数据（首条 + 最后5条 user/assistant 消息）
    //    b. 获取该用户的 TaskAssistantConfig（取 workerId/model 等）
    //    c. 构建摘要 prompt
    //    d. 调用 ClaudeWorkerFacade.syncQuery() (maxTurns=1, 纯文本)
    //    e. 将结果写入 SessionEntity.summary + summaryGeneratedAt

    // 4. 错误处理：单个 session 失败不影响其他，记日志跳过
}
```

### 2. 数据访问层扩展

**SessionRepository**（session-module，已存在）新增方法：

```java
// 查询 summary 为 null 的指定 session 列表
@Query("SELECT s FROM SessionEntity s WHERE s.id IN :ids AND s.summary IS NULL")
List<SessionEntity> findByIdInAndSummaryIsNull(@Param("ids") List<String> ids);
```

**ConversationConfigRepository**（已有方法可直接用）：

```java
// 已存在：按 interactionState 查 sessionIds
findSessionIdsByInteractionStateIn(userId, List.of("ARCHIVED", "ON_HOLD"))
```

但定时任务需要**跨用户**扫描，需新增：

```java
@Query("SELECT c.sessionId FROM ConversationConfigEntity c " +
       "WHERE c.interactionState IN :states")
List<String> findSessionIdsByInteractionStates(@Param("states") List<String> states);
```

### 3. Prompt 设计

```
你是会话摘要生成器。请根据以下会话信息生成简洁的中文摘要（100-200字），概括会话的主题、关键操作和结果。

## 会话标题
{title}

## 首条消息
{firstMessage.content}

## 最近消息（最后5条）
{recentMessages}

请直接输出摘要文本，不要加标题或格式标记。
```

- 使用 `syncQuery`（非 `syncQueryTracked`），`maxTurns=1`（纯文本，无工具调用）
- 不创建 AgentTask 记录，保持轻量

### 4. 消息获取

通过 SPI 接口获取消息，避免 task-assistant 直接依赖 session-module 的 Repository：

**方案**：在 `SessionManager`（agent-framework SPI）中新增方法：

```java
// SessionManager.java (agent-framework)
List<Message> getFirstAndRecentMessages(String sessionId, int recentCount);
```

**实现**（session-module 的 JpaSessionManager）：

```java
public List<Message> getFirstAndRecentMessages(String sessionId, int recentCount) {
    // 取首条
    List<SessionMessageEntity> all = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    if (all.isEmpty()) return Collections.emptyList();

    List<Message> result = new ArrayList<>();
    result.add(toMessage(all.get(0)));  // 首条

    // 取最后 recentCount 条（去重首条）
    int start = Math.max(1, all.size() - recentCount);
    for (int i = start; i < all.size(); i++) {
        result.add(toMessage(all.get(i)));
    }
    return result;
}
```

### 5. Summary 写回

通过 `SessionManager` 新增方法：

```java
// SessionManager.java
void updateSessionSummary(String sessionId, String summary);
```

实现中直接更新 `SessionEntity.summary` 和 `summaryGeneratedAt`。

## 依赖关系

```
task-assistant
  ├── navigator-spi        (ClaudeWorkerFacade)
  ├── agent-framework      (SessionManager - 新增 getFirstAndRecentMessages / updateSessionSummary)
  └── claude-worker-agent  (ConversationConfigRepository - 新增跨用户查询方法)
```

**问题**：task-assistant 不能直接依赖 claude-worker-agent 的 Repository。

**解决**：新增 SPI 接口方法，由 claude-worker-agent 实现：

```java
// navigator-spi 新增接口
public interface ConversationStateQuery {
    /** 查询指定状态的所有 sessionId（跨用户） */
    List<String> findSessionIdsByStates(List<String> states);
}
```

claude-worker-agent 实现此接口，注册为 `@Component`。

## 文件变更清单

| 文件 | 模块 | 变更类型 | 说明 |
|------|------|---------|------|
| `SessionEntity.java` | navigator-common | 修改 | +summary, +summaryGeneratedAt 字段 |
| `ConversationStateQuery.java` | navigator-spi | 新建 | SPI 接口，查询会话状态 |
| `ConversationStateQueryImpl.java` | claude-worker-agent | 新建 | 实现 SPI，查 ConversationConfigRepository |
| `ConversationConfigRepository.java` | claude-worker-agent | 修改 | +findSessionIdsByInteractionStates(跨用户) |
| `SessionManager.java` | agent-framework | 修改 | +getFirstAndRecentMessages(), +updateSessionSummary() |
| `JpaSessionManager.java` | session-module | 修改 | 实现新增的 SessionManager 方法 |
| `SessionRepository.java` | session-module | 修改 | +findByIdInAndSummaryIsNull() |
| `SessionSummaryService.java` | task-assistant | 新建 | 定时任务 + 摘要生成核心逻辑 |
| `TaskAssistantAutoConfiguration.java` | task-assistant | 修改 | 扫描新增 Service |

## 定时任务频率

- **建议**：每 10 分钟扫描一次（`0 */10 * * * *`）
- 归档/搁置是低频操作，10 分钟延迟可接受
- 每轮最多处理 10 个 session（防止批量归档时 LLM 压力过大）

## 后续扩展

1. **搜索集成**：summary 字段可用于全文检索（LIKE 或接入搜索引擎）
2. **前端展示**：归档列表页显示 summary 预览
3. **重新生成**：用户可手动触发重新生成摘要（清空 summary 字段即可）
