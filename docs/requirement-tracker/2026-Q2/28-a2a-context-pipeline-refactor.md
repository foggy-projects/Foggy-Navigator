# 28 - A2A Context Pipeline 重构

## 背景

定时任务（scheduled-task）需要在多次调用间保持同一会话。当前问题：
1. `ClaudeTaskService.createTask()` 每次都创建新 session，即使 contextId 相同
2. session 复用逻辑被临时加在 ClaudeTaskService 中，但 ClaudeTaskService 是多入口共用的低层服务，不应承担多轮会话语义
3. 调用者传 `contextId="time-writer-task"` 这种业务语义标识，不适合直接当 PK
4. A2aAgent 接口只有 `sendTask(A2aMessage)`，缺少上下文解析层

## 目标

1. **Pipeline/装饰者模式**：拆分 A2aAgent 为外层（上下文解析）+ 内层（任务执行），职责清晰
2. **业务别名 contextAlias**：新增字段，用户可传业务语义标识（如 `time-writer-task`），通过 `contextAlias + userId + targetAgentId` 定位 AgentConversationContextEntity，再获取 contextId
3. **Session 复用上移**：从 ClaudeTaskService 移到 A2aAgent pipeline 层
4. **CreateTaskForm 接受 sessionId**：TaskService 只执行，不做查找

## 设计

### 新增字段：contextAlias

AgentConversationContextEntity 新增 `contextAlias` 字段（VARCHAR 128, nullable）。

**查找逻辑（在 Pipeline 层）**：
- 调用者传 `contextId` → 直接按 contextId（PK）查找 → 原有逻辑不变
- 调用者传 `contextAlias`（新字段）→ 按 `(contextAlias, userId, targetAgentId)` 查找 → 找到则拿到 contextId 和 agentSessionRef，找不到则新建
- 两者都传 → contextId 优先

### Pipeline / 装饰者模式

```
Controller
  └── A2aAgent（外层接口，Controller 看到的）
        └── ContextResolvingA2aAgent（装饰者，处理上下文）
              ├── 解析 contextId / contextAlias → AgentConversationContextEntity
              ├── Agent 归属校验（ContextAgentMismatchException）
              ├── 提取 agentSessionRef + navigatorSessionId
              ├── 委托 InnerA2aAgent.sendTask(A2aContext)
              └── 任务完成后保存/更新 context
                    └── InnerA2aAgent（内层接口，实际执行）
                          └── ClaudeWorkerInnerA2aAgent（创建 task，不关心上下文）
```

### 新接口：InnerA2aAgent

```java
// navigator-spi/spi/agent/InnerA2aAgent.java
public interface InnerA2aAgent {
    A2aAgentCard getAgentCard();
    A2aTask sendTask(A2aContext context);
    Optional<A2aTask> getTask(String taskId);
    void cancelTask(String taskId);
}
```

### 新 DTO：A2aContext

```java
// navigator-common/dto/a2a/A2aContext.java
@Data @Builder
public class A2aContext {
    private A2aMessage message;          // 原始消息
    private String contextId;            // 解析后的 contextId（PK）
    private String contextAlias;         // 业务别名（可选）
    private String agentSessionRef;      // 已解析的 claudeSessionId（可为 null = 首次）
    private String navigatorSessionId;   // 已解析的平台 sessionId（可为 null = 首次）
    private String userId;
    private String tenantId;
    private String agentId;
}
```

### AgentConversationContextEntity 变更

```
现有字段不变：
  contextId (PK, VARCHAR 64)
  agentType, agentSessionRef, userId, targetAgentId, createdAt, lastAccessedAt

新增字段：
  contextAlias (VARCHAR 128, nullable) — 业务语义标识
  navigatorSessionId (VARCHAR 64, nullable) — 平台 session ID

新增索引：
  UNIQUE (contextAlias, userId, targetAgentId) — 别名唯一约束（contextAlias 非 null 时生效）
```

### CreateTaskForm 变更

新增 `sessionId` 字段。

### ClaudeTaskService.createTask() 变更

回退 contextId 查找逻辑，改为：
```java
String sessionId = form.getSessionId();
if (sessionId == null || sessionManager.getSession(sessionId) == null) {
    sessionId = sessionManager.createSession(...);
}
```

### ContextResolvingA2aAgent（装饰者）

```java
// addons/claude-worker-agent/adapter/ContextResolvingA2aAgent.java
class ContextResolvingA2aAgent implements A2aAgent {
    private final InnerA2aAgent inner;
    private final AgentContextStore contextStore;
    private final String agentId;
    private final String userId;

    @Override
    public A2aTask sendTask(A2aMessage message) {
        // 1. 解析 context：contextId 或 contextAlias → AgentConversationContextEntity
        // 2. Agent 归属校验
        // 3. 构建 A2aContext（含 agentSessionRef, navigatorSessionId）
        // 4. 委托 inner.sendTask(context)
        // 5. 任务返回后，保存/更新 AgentConversationContextEntity
        //    （contextId, agentSessionRef, navigatorSessionId）
    }
}
```

### ClaudeWorkerInnerA2aAgent（内层实现）

原 ClaudeWorkerA2aAgent 重命名，实现 InnerA2aAgent：
- `sendTask(A2aContext)` — 不再处理 contextStore，直接从 context 中取值
- 构建 CreateTaskForm 时设置 `form.setSessionId(context.getNavigatorSessionId())`
- 构建 CreateTaskForm 时设置 `form.setClaudeSessionId(context.getAgentSessionRef())`

### ClaudeWorkerAgentProvider 组装 Pipeline

```java
private A2aAgent toA2aAgent(CodingAgentEntity entity) {
    String cwd = resolveDefaultCwd(entity);
    InnerA2aAgent inner = new ClaudeWorkerInnerA2aAgent(entity, taskService, cwd);
    return new ContextResolvingA2aAgent(inner, contextStore, entity);
}
```

### AgentContextStore SPI 变更

新增方法：
```java
/** 按 contextAlias + userId + targetAgentId 查找 */
Optional<AgentConversationContextEntity> findByAlias(
        String contextAlias, String userId, String targetAgentId, int ttlHours);

/** 保存时同时存储 navigatorSessionId */
void saveSessionRef(String contextId, String agentType,
        String agentSessionRef, String navigatorSessionId,
        String userId, String targetAgentId);
```

### A2aMessage 变更

新增 `contextAlias` 字段，Controller 层从请求中提取并设置。

### Controller 层变更

AgentDiscoveryController、SharedAskController 从请求中提取 `contextAlias`，设置到 A2aMessage。

### API 请求格式变更

```json
{
  "question": "...",
  "contextId": "xxx",          // 可选，精确 ID
  "contextAlias": "time-writer-task"  // 可选，业务别名
}
```

## 实施步骤

### Phase 1: 基础设施
1. AgentConversationContextEntity 加 contextAlias + navigatorSessionId 字段 + 索引
2. A2aContext DTO
3. InnerA2aAgent 接口
4. AgentContextStore SPI 新增方法
5. AgentContextStoreImpl 实现
6. A2aMessage 加 contextAlias 字段

### Phase 2: Pipeline 实现
7. ContextResolvingA2aAgent 装饰者
8. ClaudeWorkerA2aAgent → ClaudeWorkerInnerA2aAgent（实现 InnerA2aAgent）
9. ClaudeWorkerAgentProvider 组装 pipeline
10. CreateTaskForm 加 sessionId
11. ClaudeTaskService.createTask() 回退 + 接受 sessionId

### Phase 3: 入口层
12. AgentDiscoveryController 支持 contextAlias
13. SharedAskController 支持 contextAlias
14. SharedAskForm 加 contextAlias
15. SharedAskController.ensureNavigatorSession() 简化（session 已由 pipeline 管理）

### Phase 4: 测试 & 验证
16. AgentContextStoreImplTest 新增 alias 相关测试
17. ContextResolvingA2aAgentTest 单元测试
18. mvn compile + 全量测试
19. 手动测试：POST /api/v1/shared/ask 两次同 contextAlias，确认相同 sessionId

## 验收标准

1. `POST /shared/ask` 传 `contextAlias="time-writer-task"` 两次 → 返回相同 sessionId + 相同 claudeSessionId
2. `POST /shared/ask` 传 `contextAlias="time-writer-task"` 用不同 agent → 返回 FAILED + 错误信息
3. `POST /agents/{id}/ask` 传 `contextId` 或 `contextAlias` 均正常工作
4. 原有 contextId 逻辑不受影响
5. ClaudeTaskService.createTask() 不含 context 查找逻辑
6. mvn compile + mvn test 通过
