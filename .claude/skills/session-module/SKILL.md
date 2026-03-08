---
name: session-module
description: Session Module 会话管理模块开发指导。当用户需要开发 session-module 的新功能、修改会话API、调整SSE推送、编写单元测试时使用。触发词：/session-module, /sm, 提及"会话管理"、"session"、"SSE推送"、"JpaSessionManager"。
---

# Session Module 开发指导

为 session-module 会话管理模块的开发和维护提供规范指导。

## 模块概述

session-module 是会话持久化与实时通信模块，提供：
- JPA 持久化的 SessionManager（替代 agent-framework 的 InMemorySessionManager）
- REST API（会话 CRUD、消息收发、引导卡片）
- SSE 实时事件推送（Agent 响应流式回传前端）
- AgentMessage 事件监听（持久化 + SSE 转发）

## 架构要点

### 调用链

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
    │   ├── repository/      # SessionRepository, SessionMessageRepository
    │   ├── service/         # JpaSessionManager (实现 SessionManager 接口)
    │   ├── controller/      # SessionController (REST API)
    │   ├── sse/             # SseSessionEmitter (SSE连接管理+心跳)
    │   └── event/           # SessionEventListener (AgentMessage监听)
    ├── main/resources/
    │   └── META-INF/spring/...AutoConfiguration.imports
    └── test/
        ├── java/.../service/JpaSessionManagerTest.java
        └── resources/application.yml  # H2测试配置
```

### 关联文件（其他模块）

| 文件 | 模块 | 说明 |
|------|------|------|
| `SessionEntity.java` | navigator-common | 会话JPA实体 |
| `SessionMessageEntity.java` | navigator-common | 消息JPA实体 |
| `AgentInvoker.java` | agent-framework | Agent异步调用接口 |
| `DefaultAgentInvoker.java` | agent-framework | 默认调用实现 |
| `AgentStreamHandler.java` | agent-framework | LLM流→AgentMessage事件 |
| `AgentFrameworkAutoConfiguration.java` | agent-framework | 条件Bean注册 |

## REST API 清单

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
- 如果新增 API → 在 SessionController 添加，遵循 RX + RequireAuth 模式
- 如果新增事件类型 → 在 SessionEventListener 的 shouldPersist() 和 toSessionMessage() 中处理
- 如果修改 SessionManager 接口 → 同时更新 JpaSessionManager 和 InMemorySessionManager
- 如果新增 SSE 推送场景 → 在 SseSessionEmitter 添加方法或复用 sendEvent
- 如果涉及 AgentInvoker 改动 → 在 agent-framework 模块修改，运行两个模块的测试

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
