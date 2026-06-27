---
name: session-module
description: Session Module 会话管理模块开发指导。当用户需要开发 session-module 的新功能、修改会话API、调整SSE推送、编写单元测试时使用。触发词：/session-module, /sm, 提及"会话管理"、"session"、"SSE推送"、"JpaSessionManager"。
---

# Session Module 开发指导

为 session-module 会话管理模块的开发和维护提供规范指导。

## 模块概述

session-module 是会话持久化、任务分派与实时通信模块，提供：
- JPA 持久化的 SessionManager（替代 agent-framework 的 InMemorySessionManager）
- **TaskDispatchFacade** — 统一任务分派入口（A2A 路由 / Direct Provider 路由）
- **SessionBindingService** — 会话-Agent 绑定生命周期管理
- **UnifiedAgentResolver** — 上下文感知的 Agent 解析
- REST API（会话 CRUD、任务分派、Agent 发现、SSE 推送）
- SSE 实时事件推送（Agent 响应流式回传前端）
- AgentMessage 事件监听（持久化 + SSE 转发）

## 架构要点

### 调用链（Tutor Agent 路径）

```
Frontend → SessionController → SessionManager(JPA) + AgentInvoker
                                                        ↓
                                              agent-framework (异步)
                                                        ↓
                                              AgentMessage (Spring Event)
                                                        ↓
                                              SessionEventListener
                                              ├── 持久化到数据库
                                              └── SSE推送到前端
```

### 调用链（Worker 任务分派路径 — 需求 26 重构后）

```
Frontend → TaskController / AgentTaskController
                   ↓
        TaskDispatchFacade.createTask(request, context)
                   ↓
        resolveCreateExecutionTarget(request)
              ┌────────┴────────┐
              ▼                 ▼
        [Direct Route]    [A2A Route]
        providerType 明确    通过 agentId 解析
        → createTaskDirect() → UnifiedAgentResolver.resolveAgent()
                                 ↓
                          resolveLogicalAgentId()
                                 ↓
                   SessionBindingService.getOrBind()
                   (建立/验证 Session ↔ Agent 绑定)
                                 ↓
                          agent.sendTask(message)
                                 ↓
                          toDispatchDTO() → DispatchTaskDTO
```

### 三个核心语义（需求 26）

| 概念 | 字段 | 含义 |
|------|------|------|
| 逻辑 Agent | `logicalAgentId` (SessionEntity.agentId) | 哪个 Coding Agent 负责该任务 |
| 执行后端 | `providerType` | 实际执行后端：claude-worker / codex-worker |
| 模型配置 | `modelConfigId` | API Key、BaseURL、模型参数 |

### 会话绑定生命周期（SessionBindingService）

```
新会话 → 第一次 createTask → getOrBind()
  → 写入 agentId + providerType + bindingSource
  → 此后同一 Session 不可切换 Agent（抛 SessionAgentBoundMismatchException）

遗留会话 → agentId 已有但 providerType 为空
  → getOrBind() 自动回填 providerType，bindingSource = "RESTORED"

Resume 任务 → 始终使用 Session 已绑定的 providerType
  → 不允许跨 Provider 切换
```

### Bean 优先级机制

- `SessionModuleAutoConfiguration` 使用 `@AutoConfigureBefore(AgentFrameworkAutoConfiguration.class)`
- JpaSessionManager 通过 `@Service` 注册，先于 InMemorySessionManager
- AgentFrameworkAutoConfiguration 中 `@ConditionalOnMissingBean(SessionManager.class)` 自动退让

## 模块结构

```
session-module/
├── pom.xml
└── src/
    ├── main/java/com/foggy/navigator/session/
    │   ├── config/          # SessionModuleAutoConfiguration
    │   ├── controller/
    │   │   ├── SessionController.java        # 会话 CRUD API
    │   │   ├── TaskController.java           # 统一任务分派 API
    │   │   ├── AgentTaskController.java      # Agent 任务管理 API
    │   │   ├── AgentDiscoveryController.java # A2A Agent 发现 API
    │   │   ├── SessionConfigController.java  # 会话配置 API
    │   │   ├── SharedAskController.java      # Sharing Key 公开提问 API
    │   │   ├── SharingKeyController.java     # Sharing Key 管理 API
    │   │   └── UnifiedSseController.java     # 统一 SSE 推送 API
    │   ├── registry/
    │   │   ├── DefaultA2aAgentRegistry.java  # 聚合所有 A2aAgentProvider
    │   │   ├── UnifiedAgentResolver.java     # 上下文感知的 Agent 解析
    │   │   └── JpaAgentRegistry.java         # Agent 配置持久化
    │   ├── service/
    │   │   ├── JpaSessionManager.java        # SessionManager JPA 实现
    │   │   ├── TaskDispatchFacade.java       # 统一任务分派入口（核心）
    │   │   ├── SessionBindingService.java    # 会话-Agent 绑定管理（需求26新增）
    │   │   ├── AgentTaskService.java         # Agent 任务生命周期
    │   │   ├── SessionMetadataService.java   # 会话元数据管理
    │   │   ├── SharingKeyService.java        # Sharing Key 服务
    │   │   └── AgentContextStoreImpl.java    # Agent 上下文持久化
    │   ├── dto/
    │   │   ├── SessionConfigDTO.java         # 会话配置 DTO
    │   │   └── UnifiedSessionDTO.java        # 统一会话视图 DTO
    │   ├── sse/             # UnifiedSseEmitter (SSE连接管理+心跳)
    │   ├── event/           # SessionEventListener (AgentMessage监听)
    │   ├── exception/       # SessionAgentBoundMismatchException（需求26新增）
    │   └── repository/      # SessionRepository, SessionMessageRepository 等
    ├── main/resources/
    │   └── META-INF/spring/...AutoConfiguration.imports
    └── test/
        ├── java/.../service/
        │   ├── JpaSessionManagerTest.java
        │   ├── TaskDispatchFacadeTest.java      # 分派路由测试
        │   └── SessionBindingServiceTest.java   # 绑定生命周期测试（需求26新增）
        └── resources/application.yml  # H2测试配置
```

### 关联文件（其他模块）

| 文件 | 模块 | 说明 |
|------|------|------|
| `SessionEntity.java` | navigator-common | 会话JPA实体（含 providerType/bindingSource/authModelConfigId） |
| `SessionTaskEntity.java` | navigator-common | 统一任务投影实体（含 providerType） |
| `SessionMessageEntity.java` | navigator-common | 消息JPA实体 |
| `DispatchTaskDTO.java` | navigator-common | 统一任务视图 DTO（跨 Provider 通用） |
| `TaskDispatchRequest.java` | session-module | 任务分派请求（含 agentId/providerType/modelConfigId） |
| `A2aAgent.java` / `A2aAgentProvider.java` | navigator-spi | A2A SPI 接口 |
| `AgentInvoker.java` | agent-framework | Agent异步调用接口 |
| `DefaultAgentInvoker.java` | agent-framework | 默认调用实现 |
| `AgentStreamHandler.java` | agent-framework | LLM流→AgentMessage事件 |
| `AgentFrameworkAutoConfiguration.java` | agent-framework | 条件Bean注册 |

### TaskDispatchFacade 核心方法

| 方法 | 说明 |
|------|------|
| `createTask(request, context)` | 创建任务（A2A 或 Direct 路由） |
| `resumeTask(request, context)` | 恢复任务（优先 Session 绑定的 providerType） |
| `getTask(taskId, context)` | 查询单个任务 |
| `listTasksBySession(sessionId)` | 按会话列出任务 |
| `listActiveTasks(userId)` | 列出用户活跃任务 |
| `cancelTask(taskId, agentId, context)` | 取消任务 |

### TaskDispatchRequest 关键字段

```java
@Builder @Data
public class TaskDispatchRequest {
    String agentId;           // 逻辑 Agent ID
    String providerType;      // 执行后端: claude-worker / codex-worker
    String modelConfigId;     // 模型配置 ID（可推导 providerType）
    String sessionId;         // 平台会话 ID（null = 新建）
    String workerId;          // Worker ID
    String prompt;            // 任务提示词
    String cwd;               // 工作目录
    String directoryId;       // 目录 ID
    String model;             // 模型名称
    Integer maxTurns;         // 最大轮次
    List<String> images;      // 图片附件
    String claudeSessionId;   // Claude Session ID（resume）
    String codexThreadId;     // Codex Thread ID（resume）
    boolean resume;           // 是否为 resume 操作
}
```

### DispatchTaskDTO 关键字段

```java
@Builder @Data
public class DispatchTaskDTO {
    // 通用字段
    String taskId, workerTaskId, sessionId, workerId, userId, agentId;
    String providerType;      // 执行后端标识
    String prompt, cwd, directoryId, status, model;
    BigDecimal costUsd;
    Long inputTokens, outputTokens, durationMs;
    Integer numTurns;
    String resultText, errorMessage, source;
    // Provider 扩展字段（nullable）
    String claudeSessionId;   // Claude 专属
    String codexThreadId;     // Codex 专属
    String contextId;         // A2A 多轮上下文
    String directoryName;     // UI 显示冗余
}
```

## REST API 清单

### 会话管理（SessionController）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/sessions` | 创建会话 |
| GET | `/api/v1/sessions` | 查询会话列表（支持agentId筛选） |
| GET | `/api/v1/sessions/{id}` | 获取单个会话 |
| DELETE | `/api/v1/sessions/{id}` | 删除会话 |
| GET | `/api/v1/sessions/{id}/messages` | 获取消息列表 |
| POST | `/api/v1/sessions/{id}/messages` | 发送消息（触发Agent异步处理） |
| GET | `/api/v1/sessions/{id}/stream` | SSE事件流 |
| GET | `/api/v1/sessions/guide-cards` | 获取引导卡片 |

### Agent 发现（AgentDiscoveryController）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/agents` | 列出所有 Agent（可选 `?type=` 过滤） |
| GET | `/api/v1/agents/{agentId}/card` | 获取 Agent 名片 |
| POST | `/api/v1/agents/{agentId}/ask` | 向 Agent 提问（同步） |
| GET | `/api/v1/agents/consultations` | 查询 @Agent 咨询记录 |

### 任务分派（TaskController / AgentTaskController）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/tasks` | 创建任务（经 TaskDispatchFacade 路由） |
| POST | `/api/v1/tasks/resume` | 恢复任务 |
| GET | `/api/v1/tasks/{taskId}` | 查询任务 |
| POST | `/api/v1/tasks/{taskId}/cancel` | 取消任务 |
| GET | `/api/v1/tasks/active` | 列出活跃任务 |

## 执行流程

### 新增API

1. 在 `SessionController` 添加方法，使用 `@RequireAuth` + `UserContext.getCurrentUser()`
2. 统一返回 `RX<T>`（`RX.ok(data)` / `RX.throwB(msg)`）
3. 如涉及新查询，在 Repository 添加方法（Spring Data 命名约定）
4. 编写测试用例
5. 运行测试验证

### 修改持久化逻辑

1. 读取 `JpaSessionManager` 理解当前 Entity↔POJO 转换
2. 如需新字段：先改 Entity（navigator-common）→ 再改 Service 转换逻辑
3. `metadata` 字段使用 `ObjectMapper` 做 `Map<String,Object>` ↔ JSON String
4. Entity 使用 `columnDefinition = "TEXT"` 兼容 H2 测试
5. 运行测试验证

### 修改SSE推送

1. 读取 `SseSessionEmitter` 和 `SessionEventListener`
2. SSE 命名事件使用 `.name("event")`，与前端 `@foggy/chat` 的 `createSseClient` 对齐
3. `shouldPersist()` 控制哪些 MessageType 需要持久化（TEXT_CHUNK 和 HEARTBEAT 不持久化）
4. 心跳间隔 15 秒

### 修改AgentInvoker调用链

1. `AgentInvoker` 接口在 agent-framework 模块
2. `DefaultAgentInvoker` 使用 `agentExecutor` 线程池异步执行
3. LLM 回调通过 `AgentStreamHandler` → `ApplicationEventPublisher` → `SessionEventListener`
4. 修改时注意跨模块影响，运行两个模块的测试

## 代码规范

### Controller 模式

```java
@PostMapping
public RX<Session> createSession(@RequestBody CreateSessionForm form) {
    CurrentUser user = UserContext.getCurrentUser();
    // ... 业务逻辑 ...
    return RX.ok(session);
}
```

### Entity 模式（navigator-common）

```java
@Data
@Entity
@Table(name = "sessions", indexes = {
    @Index(name = "idx_session_user_id", columnList = "userId")
})
public class SessionEntity {
    @Id
    @Column(length = 64)
    private String id;
    // ... TEXT字段用 columnDefinition = "TEXT" ...
    @PrePersist
    protected void onCreate() { ... }
    @PreUpdate
    protected void onUpdate() { ... }
}
```

### 测试模式

```java
@SpringBootTest(classes = JpaSessionManagerTest.TestConfig.class)
@ActiveProfiles("test")
class JpaSessionManagerTest {
    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.foggy.navigator.common.entity")
    @EnableJpaRepositories(basePackages = "com.foggy.navigator.session.repository")
    @ComponentScan(basePackages = "com.foggy.navigator.session.service")
    static class TestConfig {}
    // ... H2内存数据库，create-drop模式 ...
}
```

## 线程池配置

| 线程池 | 模块 | 用途 | 参数 |
|--------|------|------|------|
| `agentExecutor` | agent-framework | Agent异步调用 | core=5, max=20, queue=100 |
| `sessionEventExecutor` | session-module | 事件监听异步处理 | core=5, max=20, queue=200 |

## 依赖说明

| 依赖 | 用途 |
|------|------|
| agent-framework | AgentInvoker, SessionManager接口, AgentMessage |
| navigator-common | SessionEntity, SessionMessageEntity, UserContext |
| user-auth-module | Token解析, @RequireAuth |
| spring-boot-starter-web | REST + SSE |
| spring-boot-starter-data-jpa | JPA持久化 |
| foggy-core | RX统一返回 |
| h2 (test) | 单元测试内存数据库 |

## 约束条件

- 包名：`com.foggy.navigator.session.{子包}`
- Entity 放 navigator-common（`com.foggy.navigator.common.entity`）
- Controller 统一返回 `RX<T>`，使用 `@RequireAuth`
- metadata 列用 TEXT 不用 JSON（兼容 H2）
- SSE 事件用 `.name("event")` 命名
- TEXT_CHUNK 和 HEARTBEAT 不持久化
- session-module 单向依赖 agent-framework，不可反向

## 决策规则

- 如果新增 Entity 字段 → 改 navigator-common 的 Entity + session-module 的转换逻辑
- 如果新增 API → 在对应 Controller 添加，遵循 RX + RequireAuth 模式
- 如果新增事件类型 → 在 SessionEventListener 的 shouldPersist() 和 toSessionMessage() 中处理
- 如果修改 SessionManager 接口 → 同时更新 JpaSessionManager 和 InMemorySessionManager
- 如果新增 SSE 推送场景 → 在 UnifiedSseEmitter 添加方法或复用 sendEvent
- 如果涉及 AgentInvoker 改动 → 在 agent-framework 模块修改，运行两个模块的测试
- 如果修改任务分派路由 → 在 TaskDispatchFacade 修改，注意 A2A/Direct 双路径一致性
- 如果新增 Provider 类型 → 更新 SessionBindingService 的 bindingSource 枚举 + TaskDispatchFacade 的路由判断
- 如果修改 Session-Agent 绑定规则 → 在 SessionBindingService 修改，同步更新 SessionBindingServiceTest
- **禁止**在 agentId 字段存储 Provider 常量（如 "claude-worker"），必须存真实逻辑 Agent ID

## 常用命令

```bash
# 编译
mvn compile -pl session-module -am

# 运行 session-module 测试
mvn test -pl session-module -am

# 运行 agent-framework 测试（验证无回归）
mvn test -pl agent-framework -am

# 两个模块一起测试
mvn test -pl agent-framework,session-module -am

# 单个测试类
mvn test -pl session-module -Dtest=JpaSessionManagerTest

# 全量构建
mvn clean package -pl navigator-common,agent-framework,session-module,user-auth-module -am -DskipTests
```

## 相关文档

- 设计文档：`docs/frontend-design/backend-api-requirements.md`（第 2.6 节）
- A2A 架构文档：`docs/a2a-agent-architecture.md`
- 需求 26（路由重构）：`docs/requirement-tracker/2026-Q1/26-worker-execution-context-routing-design.md`
- 需求 25（存储统一）：`docs/requirement-tracker/2026-Q1/25-session-storage-unification-design.md`
