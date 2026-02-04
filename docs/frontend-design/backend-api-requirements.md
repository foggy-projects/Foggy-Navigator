# Navigator Frontend - Backend API Requirements

> 面向后端开发团队的 API 需求文档。本文档定义了 Navigator 前端所需的全部后端能力，包含架构设计、新建模块说明和详细 API 规格。

---

## 目录

1. [概述](#一概述)
2. [架构设计：session-module](#二架构设计session-module)
3. [API 总览](#三api-总览)
4. [认证相关 API（已有）](#四认证相关-api已有)
5. [会话管理 API（新建）](#五会话管理-api新建)
6. [实时事件流 API - SSE（新建）](#六实时事件流-api---sse新建)
7. [动态引导卡片 API（新建）](#七动态引导卡片-api新建)
8. [配置管理 API（已有）](#八配置管理-api已有)
9. [通用约定](#九通用约定)
10. [附录](#附录)

---

## 一、概述

### 1.1 背景

Navigator Frontend 是面向终端用户的对话式交互界面。用户通过前端与 Agent 对话（默认为 tutor-agent），Agent 在后台自行决策是否调用工具或调度其他 Agent。前端不感知 Agent 调度过程。

### 1.2 核心认知

**会话管理是框架级基础能力，不是某个 Agent 的职责。**

当前现状：
- `agent-framework` 已有 `SessionManager` 接口 + `InMemorySessionManager`（重启丢失）
- `coding-agent` 自建了 `ConversationEntity`（与 OpenHands 强关联，属于领域特化）
- 缺少：框架级的持久化会话管理、REST API、SSE 事件推送

目标：新建 `session-module`，提供数据库持久化的会话管理，任何 Agent 共用。

### 1.3 交互模型

```
用户 ←→ Navigator Frontend ←→ Backend API
                                    │
                                    ├── session-module（会话 CRUD、消息存储、SSE 推送）
                                    │        ↕
                                    ├── agent-framework（Agent 运行时，SessionManager 接口）
                                    │        ↕
                                    ├── tutor-agent（提示词、Skill、工具定义）
                                    ├── metadata-config（配置管理）
                                    └── user-auth（认证）
```

---

## 二、架构设计：session-module

### 2.1 定位

`session-module` 是 `agent-framework` 中 `SessionManager` 接口的 **JPA 持久化实现 + REST API + SSE 事件推送**。

它不是某个 Agent 的模块，而是框架级基础设施。

### 2.2 与现有模块的关系

```
agent-framework                       session-module (新建)
├── core/                            ├── entity/
│   ├── AgentRegistry (interface)    │   ├── SessionEntity.java
│   ├── AgentInvoker (interface) ◄───│   └── SessionMessageEntity.java
│   │   └── invokeAsync()            │
│   └── impl/                        ├── repository/
│       └── DefaultAgentInvoker      │   ├── SessionRepository.java
│                                    │   └── SessionMessageRepository.java
├── session/                         │
│   ├── SessionManager (interface)   ├── service/
│   ├── Session (POJO)        ←──────│   ├── JpaSessionManager.java ──── implements SessionManager
│   ├── Message (POJO)        ←──────│   └── SessionStreamService.java
│   ├── SessionCreateRequest         │
│   ├── SessionStatus                ├── controller/
│   ├── MessageRole                  │   └── SessionController.java
│   └── impl/                        │
│       └── InMemorySessionManager   ├── sse/
│           (保留，用于测试/轻量场景)   │   └── SseSessionEmitter.java
│                                    │
├── protocol/                        ├── event/
│   ├── AgentMessage  ──────────────→│   └── SessionEventListener.java
│   └── MessageType                  │       (监听 AgentMessage 事件)
│                                    │
├── llm/                             └── config/
│   ├── LlmAdapter (interface)           └── SessionModuleAutoConfiguration.java
│   └── LlmStreamHandler (interface)
│
└── config/
    └── AgentFrameworkAutoConfiguration
```

### 2.3 与 coding-agent 的关系

```
session-module                        coding-agent
┌───────────────────┐                ┌───────────────────┐
│  SessionEntity    │                │ ConversationEntity │
│  (通用会话)        │  1:1 可选关联   │ (OpenHands 特化)   │
│                   │ ←─────────────→│                   │
│  id               │                │ sessionId (FK)     │
│  userId           │                │ sandboxId          │
│  agentId          │                │ ohConversationId   │
│  title            │                │ gitCredentialId    │
│  status           │                │ workingBranch      │
│  ...              │                │ ...                │
└───────────────────┘                └───────────────────┘
```

- `SessionEntity` 存储所有 Agent 通用的会话信息
- `ConversationEntity` 通过 `sessionId` 字段关联到 `SessionEntity`，存储 coding-agent 特有的字段
- 未来如果 coding-agent 迁移到框架会话管理，只需在 `ConversationEntity` 增加 `sessionId` 外键

### 2.4 模块依赖

```xml
<!-- session-module/pom.xml -->
<dependencies>
    <!-- 实现 SessionManager 接口 -->
    <dependency>
        <groupId>com.foggy.navigator</groupId>
        <artifactId>agent-framework</artifactId>
    </dependency>

    <!-- Entity 定义（如果 Entity 放在 navigator-common） -->
    <dependency>
        <groupId>com.foggy.navigator</groupId>
        <artifactId>navigator-common</artifactId>
    </dependency>

    <!-- JPA + Web -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
</dependencies>
```

### 2.5 设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 会话管理归属 | session-module（框架级） | 任何 Agent 共用，不是 tutor 的职责 |
| agent-framework 不加 JPA | 保持纯库定位 | 避免强制依赖 JPA |
| Entity 位置 | navigator-common | 与 SkillConfigEntity 等保持一致 |
| SessionManager 接口 | 保留在 agent-framework | 已有，且是 Agent 运行时的核心依赖 |
| InMemorySessionManager | 保留 | 单元测试和轻量场景仍需要 |
| SSE 事件推送 | 参考 coding-agent 的 SseEventEmitter | 成熟方案，心跳+连接管理 |
| API 路径 | `/api/v1/sessions` | 框架中立，通过 agentId 参数区分 |
| Agent 调用 | AgentInvoker 接口（agent-framework 定义） | session-module 只调接口，不依赖执行细节 |
| 事件回传 | AgentMessage 作为 Spring Event | 复用现有协议类型，双向解耦 |

### 2.6 Agent 调用链路设计

这是 session-module 的核心难点：**用户发消息后，如何触发 Agent 处理并把结果推回前端？**

#### 2.6.1 设计原则

- **session-module 只负责"接收消息"和"推送事件"**，不了解 Agent 执行细节
- **agent-framework 负责"执行 Agent 逻辑"**（LLM 调用、工具执行、Skill 匹配）
- 两者通过 **接口 + Spring Event** 双向通信，无循环依赖

#### 2.6.2 接口定义（agent-framework 新增）

```java
package com.foggy.navigator.agent.framework.core;

import com.foggy.navigator.agent.framework.session.Message;

/**
 * Agent 调用器
 * session-module 通过此接口触发 Agent 异步处理用户消息。
 * 处理结果通过 ApplicationEventPublisher 发布 AgentMessage 事件回传。
 */
public interface AgentInvoker {

    /**
     * 异步调用 Agent 处理消息
     *
     * @param sessionId    会话 ID
     * @param agentId      目标 Agent ID
     * @param userMessage  用户消息（已持久化）
     */
    void invokeAsync(String sessionId, String agentId, Message userMessage);
}
```

#### 2.6.3 默认实现（agent-framework 新增）

```java
package com.foggy.navigator.agent.framework.core.impl;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultAgentInvoker implements AgentInvoker {

    private final AgentRegistry agentRegistry;
    private final SessionManager sessionManager;
    private final LlmAdapter llmAdapter;
    private final ApplicationEventPublisher eventPublisher;
    private final AsyncTaskExecutor agentExecutor;  // 通过 AutoConfiguration 注入

    @Override
    public void invokeAsync(String sessionId, String agentId, Message userMessage) {
        agentExecutor.execute(() -> {
            try {
                doInvoke(sessionId, agentId, userMessage);
            } catch (Exception e) {
                log.error("Agent invocation failed: sessionId={}, agentId={}", sessionId, agentId, e);
                eventPublisher.publishEvent(AgentMessage.of(
                    sessionId, agentId, MessageType.ERROR,
                    Map.of("error", "Agent 处理失败: " + e.getMessage())
                ));
            }
        });
    }

    private void doInvoke(String sessionId, String agentId, Message userMessage) {
        // 1. 查找 Agent 配置
        AgentInfo agent = agentRegistry.findById(agentId);
        if (agent == null) {
            throw new IllegalArgumentException("Agent not found: " + agentId);
        }

        // 2. 构建 LLM 请求（系统提示词 + 历史消息 + 工具定义）
        List<Message> history = sessionManager.getRecentMessages(sessionId, 20);
        AgentConfig config = agent.getConfig();
        LlmRequest request = LlmRequest.builder()
                .model(config.getModel().getModel())
                .temperature(config.getModel().getTemperature())
                .systemPrompt(config.getModel().getSystemPrompt())
                .messages(toLlmMessages(history))
                .tools(resolveTools(config))
                .build();

        // 3. 流式调用 LLM，通过回调发布事件
        llmAdapter.chatStream(request, new AgentStreamHandler(
                sessionId, agentId, eventPublisher
        ));
    }
}
```

#### 2.6.4 流式回调处理器

```java
/**
 * LLM 流式输出 → AgentMessage 事件
 */
@RequiredArgsConstructor
public class AgentStreamHandler implements LlmStreamHandler {

    private final String sessionId;
    private final String agentId;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void onText(String text) {
        // 流式文本片段
        eventPublisher.publishEvent(AgentMessage.of(
            sessionId, agentId, MessageType.TEXT_CHUNK,
            Map.of("content", text)
        ));
    }

    @Override
    public void onToolCall(ToolCall toolCall) {
        // 工具调用开始
        eventPublisher.publishEvent(AgentMessage.of(
            sessionId, agentId, MessageType.TOOL_CALL_START,
            Map.of(
                "toolCallId", toolCall.getId(),
                "toolName", toolCall.getName(),
                "arguments", toolCall.getArguments()
            )
        ));
        // 注意：工具实际执行和 TOOL_CALL_RESULT 由 DefaultAgentInvoker 处理
    }

    @Override
    public void onComplete(LlmResponse response) {
        // 文本完成（完整内容）
        eventPublisher.publishEvent(AgentMessage.of(
            sessionId, agentId, MessageType.TEXT_COMPLETE,
            Map.of("content", response.getContent() != null ? response.getContent() : "")
        ));
    }

    @Override
    public void onError(Throwable error) {
        eventPublisher.publishEvent(AgentMessage.of(
            sessionId, agentId, MessageType.ERROR,
            Map.of("error", error.getMessage())
        ));
    }
}
```

#### 2.6.5 session-module 事件监听

```java
package com.foggy.navigator.session.event;

/**
 * 监听 AgentMessage 事件
 * 职责：持久化 Agent 回复 + SSE 推送
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionEventListener {

    private final SessionManager sessionManager;
    private final SseSessionEmitter sseEmitter;

    @Async("sessionEventExecutor")
    @EventListener
    public void onAgentMessage(AgentMessage message) {
        String sessionId = message.getSessionId();

        // 1. 持久化（仅对需要存储的消息类型）
        if (shouldPersist(message.getType())) {
            Message msg = toSessionMessage(message);
            sessionManager.addMessage(sessionId, msg);
        }

        // 2. 推送 SSE
        sseEmitter.sendEvent(sessionId, toSseEvent(message));
    }

    private boolean shouldPersist(MessageType type) {
        // TEXT_CHUNK 不持久化（只是流式片段），TEXT_COMPLETE 持久化（完整内容）
        return type != MessageType.TEXT_CHUNK
            && type != MessageType.HEARTBEAT;
    }

    private Message toSessionMessage(AgentMessage msg) {
        Map<String, Object> payload = (Map<String, Object>) msg.getPayload();
        String content = (String) payload.getOrDefault("content", null);

        Map<String, Object> metadata = new HashMap<>(payload);
        metadata.put("type", msg.getType().name());  // 保存消息子类型

        MessageRole role = (msg.getType() == MessageType.TOOL_CALL_RESULT
                         || msg.getType() == MessageType.TOOL_CALL_ERROR)
                ? MessageRole.TOOL
                : MessageRole.ASSISTANT;

        return Message.builder()
                .sessionId(msg.getSessionId())
                .role(role)
                .content(content)
                .metadata(metadata)
                .build();
    }
}
```

#### 2.6.6 完整调用链路

```
前端                    session-module                  agent-framework
 │                          │                               │
 │  POST /sessions/         │                               │
 │  {id}/messages           │                               │
 │  { content: "..." }      │                               │
 │─────────────────────────→│                               │
 │                          │                               │
 │                          │  ① sessionManager             │
 │                          │     .addMessage(USER)         │
 │                          │                               │
 │                          │  ② agentInvoker ─────────────→│
 │  ← HTTP 200             │     .invokeAsync()            │  (fire-and-forget)
 │  { id: "msg-001" }      │                               │
 │                          │                               │  ③ agentRegistry.findById()
 │                          │                               │  ④ sessionManager.getRecentMessages()
 │                          │                               │  ⑤ llmAdapter.chatStream()
 │                          │                               │
 │                          │                               │  ⑥ LLM 回调 → publish AgentMessage
 │                          │     ┌─────────────────────────│
 │                          │     │ AgentMessage             │
 │                          │     │ (Spring @EventListener)  │
 │                          │     ▼                          │
 │                          │  ⑦ SessionEventListener       │
 │                          │     ├── addMessage(ASSISTANT)  │
 │  ← SSE: TEXT_CHUNK      │     └── sseEmitter.sendEvent() │
 │  ← SSE: TOOL_CALL       │                               │
 │  ← SSE: TOOL_RESULT     │        (重复 ⑥⑦ 直到完成)      │
 │  ← SSE: TEXT_COMPLETE    │                               │
 │                          │                               │
```

#### 2.6.7 关键设计要点

| 要点 | 说明 |
|------|------|
| 单向依赖 | session-module → agent-framework（通过 AgentInvoker 接口），无反向依赖 |
| 事件解耦 | agent-framework 通过 `ApplicationEventPublisher` 发布 `AgentMessage`，session-module 通过 `@EventListener` 接收 |
| 协议复用 | 使用已有的 `AgentMessage` + `MessageType` 作为事件载体，不新增事件类型 |
| 异步执行 | `AgentInvoker.invokeAsync()` 内部用独立线程池执行，不阻塞 HTTP 请求 |
| 可替换 | `AgentInvoker` 是接口，不同 Agent 模块可提供自定义实现（如 tutor-agent 特化调用逻辑） |
| 工具执行 | `DefaultAgentInvoker` 在收到 `onToolCall` 后负责执行工具，结果作为 `TOOL_CALL_RESULT` 发布 |

#### 2.6.8 线程池配置

```java
// AgentFrameworkAutoConfiguration 或 SessionModuleAutoConfiguration 中注册
@Bean("agentExecutor")
public AsyncTaskExecutor agentExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(5);
    executor.setMaxPoolSize(20);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("agent-invoke-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(60);
    return executor;
}

@Bean("sessionEventExecutor")
public AsyncTaskExecutor sessionEventExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(5);
    executor.setMaxPoolSize(20);
    executor.setQueueCapacity(200);
    executor.setThreadNamePrefix("session-event-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(60);
    return executor;
}
```

---

## 三、API 总览

### 3.1 新建 API（session-module 提供）

| # | API | 说明 | 优先级 |
|---|-----|------|--------|
| 1 | `POST /api/v1/sessions` | 创建会话 | P0 |
| 2 | `GET /api/v1/sessions` | 会话列表（按用户、Agent 筛选） | P0 |
| 3 | `GET /api/v1/sessions/{id}` | 获取会话详情 | P0 |
| 4 | `DELETE /api/v1/sessions/{id}` | 删除会话 | P1 |
| 5 | `PATCH /api/v1/sessions/{id}` | 更新会话（改标题等） | P2 |
| 6 | `GET /api/v1/sessions/{id}/messages` | 获取历史消息 | P0 |
| 7 | `POST /api/v1/sessions/{id}/messages` | 发送消息（触发 Agent） | P0 |
| 8 | `GET /api/v1/sessions/{id}/stream` | SSE 实时事件流 | P0 |
| 9 | `GET /api/v1/sessions/guide-cards` | 动态引导卡片 | P0 |

### 3.2 已有可直接复用的 API

| # | API | 所在模块 | 说明 |
|---|-----|----------|------|
| 1 | `POST /api/v1/auth/login` | user-auth-module | 用户登录 |
| 2 | `GET /api/v1/auth/me` | user-auth-module | 当前用户信息 |
| 3 | `POST /api/config/datasource` | metadata-config-module | 创建数据源 |
| 4 | `PUT /api/config/datasource/{id}` | metadata-config-module | 更新数据源 |
| 5 | `DELETE /api/config/datasource/{id}` | metadata-config-module | 删除数据源 |
| 6 | `GET /api/config/skills` | metadata-config-module | Skill 列表 |
| 7 | `POST /api/config/skills` | metadata-config-module | 创建 Skill |

### 3.3 需要补充的 API

| # | API | 所在模块 | 说明 |
|---|-----|----------|------|
| 1 | `GET /api/config/datasources` | metadata-config-module | 数据源列表查询（当前缺少） |

---

## 四、认证相关 API（已有）

> 无需新建。以下列出前端调用方式供参考。

### 4.1 用户登录

```
POST /api/v1/auth/login
```

**Request**:
```json
{
  "username": "admin",
  "password": "admin123"
}
```

**Response** (`RX<LoginResultDTO>`):
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "token": "eyJhbGciOiJ...",
    "tokenType": "Bearer",
    "expiresIn": 86400,
    "user": {
      "id": "user-001",
      "tenantId": "default",
      "username": "admin",
      "displayName": "管理员",
      "roles": ["ADMIN"]
    }
  }
}
```

### 4.2 获取当前用户

```
GET /api/v1/auth/me
Authorization: Bearer {token}
```

**Response** (`RX<UserDTO>`):
```json
{
  "code": 0,
  "data": {
    "id": "user-001",
    "tenantId": "default",
    "username": "admin",
    "displayName": "管理员",
    "roles": ["ADMIN"]
  }
}
```

---

## 五、会话管理 API（新建）

> 由 session-module 的 `SessionController` 提供。对应 agent-framework 的 `SessionManager` 接口。

### 5.1 创建会话

```
POST /api/v1/sessions
Authorization: Bearer {token}
Content-Type: application/json
```

**Request**:
```json
{
  "title": "配置 MySQL 数据源",
  "agentId": "tutor-agent"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| title | String | 是 | 会话标题 |
| agentId | String | 否 | 处理会话的 Agent，默认系统配置的默认 Agent |

**Response** (`RX<SessionDTO>`):
```json
{
  "code": 0,
  "data": {
    "id": "sess-uuid-001",
    "userId": "user-001",
    "tenantId": "default",
    "agentId": "tutor-agent",
    "title": "配置 MySQL 数据源",
    "status": "ACTIVE",
    "createdAt": "2026-02-04T10:30:00",
    "updatedAt": "2026-02-04T10:30:00"
  }
}
```

**SessionDTO 字段定义**：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | String | 会话 ID（UUID） |
| userId | String | 所属用户 |
| tenantId | String | 租户 ID |
| agentId | String | 处理该会话的 Agent ID |
| title | String | 会话标题 |
| status | String | ACTIVE / COMPLETED / PAUSED / DELEGATED |
| parentSessionId | String | 父会话 ID（Agent 委派场景，可选） |
| lastMessagePreview | String | 最后一条消息预览（列表查询时返回） |
| messageCount | int | 消息总数（列表查询时返回） |
| createdAt | String | 创建时间 (ISO 8601) |
| updatedAt | String | 最后更新时间 |

**业务逻辑**：
1. 从 Token 解析 userId、tenantId
2. 调用 `SessionManager.createSession(request)` 创建会话
3. agentId 默认值从系统配置中读取（如 `foggy.session.default-agent-id=tutor-agent`）

### 5.2 获取会话列表

```
GET /api/v1/sessions
Authorization: Bearer {token}
```

**Query Parameters**:

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| agentId | String | - | 按 Agent 筛选（可选） |
| status | String | - | 按状态筛选（可选） |
| page | int | 1 | 页码 |
| size | int | 20 | 每页数量 |

**Response** (`RX<List<SessionDTO>>`):
```json
{
  "code": 0,
  "data": [
    {
      "id": "sess-uuid-001",
      "agentId": "tutor-agent",
      "title": "配置 MySQL 数据源",
      "status": "ACTIVE",
      "lastMessagePreview": "已为您保存数据源配置。",
      "messageCount": 8,
      "createdAt": "2026-02-04T10:30:00",
      "updatedAt": "2026-02-04T11:00:00"
    }
  ]
}
```

**业务逻辑**：
- 自动按当前用户过滤（从 Token 解析 userId）
- 按 `updatedAt` 降序排列
- 前端默认传 `agentId=tutor-agent`，只看自己的导师会话

> **注意**：这个接口是框架级的。如果未来有其他 Agent（如行业导师），前端只需传不同的 agentId。不需要为每个 Agent 建单独的 API。

### 5.3 获取会话详情

```
GET /api/v1/sessions/{sessionId}
Authorization: Bearer {token}
```

**Response** (`RX<SessionDTO>`): 同 5.1 的 SessionDTO 结构。

### 5.4 删除会话

```
DELETE /api/v1/sessions/{sessionId}
Authorization: Bearer {token}
```

**Response** (`RX<Void>`):
```json
{ "code": 0, "data": null }
```

### 5.5 更新会话

```
PATCH /api/v1/sessions/{sessionId}
Authorization: Bearer {token}
Content-Type: application/json
```

**Request**:
```json
{
  "title": "新标题"
}
```

**Response** (`RX<Void>`): 同上。

### 5.6 获取历史消息

```
GET /api/v1/sessions/{sessionId}/messages
Authorization: Bearer {token}
```

**Query Parameters**:

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| limit | int | 50 | 返回消息数量 |
| before | String | - | 游标：返回此 messageId 之前的消息 |

**Response** (`RX<List<SessionMessageDTO>>`):
```json
{
  "code": 0,
  "data": [
    {
      "id": "msg-001",
      "sessionId": "sess-uuid-001",
      "role": "USER",
      "content": "我想配置一个新的 MySQL 数据源",
      "metadata": null,
      "createdAt": "2026-02-04T10:31:00"
    },
    {
      "id": "msg-002",
      "sessionId": "sess-uuid-001",
      "role": "ASSISTANT",
      "content": "好的！我来帮您配置。请提供以下信息：...",
      "metadata": {
        "type": "TEXT"
      },
      "createdAt": "2026-02-04T10:31:05"
    },
    {
      "id": "msg-003",
      "sessionId": "sess-uuid-001",
      "role": "ASSISTANT",
      "content": null,
      "metadata": {
        "type": "TOOL_CALL",
        "toolCallId": "tc-001",
        "toolName": "test_connection",
        "command": "Connecting to 192.168.1.100:3306/foggy_db",
        "thought": "用户提供了完整的连接信息，测试连接"
      },
      "createdAt": "2026-02-04T10:32:00"
    },
    {
      "id": "msg-004",
      "sessionId": "sess-uuid-001",
      "role": "TOOL",
      "content": "Connection successful. Tables found: 23",
      "metadata": {
        "type": "TOOL_RESULT",
        "toolCallId": "tc-001",
        "toolName": "test_connection",
        "exitCode": 0
      },
      "createdAt": "2026-02-04T10:32:10"
    }
  ]
}
```

**SessionMessageDTO 字段定义**：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | String | 消息 ID |
| sessionId | String | 所属会话 ID |
| role | String | USER / ASSISTANT / SYSTEM / TOOL（对应 `MessageRole` 枚举） |
| content | String | 消息文本内容 |
| metadata | Map | 元数据（消息子类型、工具调用信息等） |
| createdAt | String | 时间 (ISO 8601) |

**关于 metadata**：

agent-framework 的 `Message` 已有 `Map<String, Object> metadata` 字段。利用此字段存储消息子类型信息：

| metadata.type | role | 说明 | metadata 额外字段 |
|---------------|------|------|-------------------|
| `TEXT` | ASSISTANT | 文本回复 | - |
| `TEXT_CHUNK` | ASSISTANT | 流式文本（仅 SSE 中出现，不持久化） | - |
| `TOOL_CALL` | ASSISTANT | Agent 发起工具调用 | toolCallId, toolName, command, thought |
| `TOOL_RESULT` | TOOL | 工具执行结果 | toolCallId, toolName, exitCode |
| `TOOL_ERROR` | TOOL | 工具执行失败 | toolCallId, toolName, error |
| `THINKING` | ASSISTANT | Agent 思考过程 | thought |
| `ERROR` | SYSTEM | 错误 | error |
| (无) | USER | 用户消息 | - |

> 这样设计的好处：Message 结构与 agent-framework 的 `Message` POJO 完全一致，JPA 持久化时 metadata 字段用 JSON 列存储，无需拆分为多个表。

### 5.7 发送消息

```
POST /api/v1/sessions/{sessionId}/messages
Authorization: Bearer {token}
Content-Type: application/json
```

**Request**:
```json
{
  "content": "我想配置一个新的 MySQL 数据源"
}
```

**Response** (`RX<SessionMessageDTO>`):
```json
{
  "code": 0,
  "data": {
    "id": "msg-005",
    "sessionId": "sess-uuid-001",
    "role": "USER",
    "content": "我想配置一个新的 MySQL 数据源",
    "createdAt": "2026-02-04T10:31:00"
  }
}
```

**业务逻辑**（详见 [2.6 Agent 调用链路设计](#26-agent-调用链路设计)）：
1. 从 Token 解析 userId，校验会话归属
2. 调用 `SessionManager.addMessage()` 存储用户消息
3. HTTP 响应立即返回（只确认消息已接收）
4. 调用 `AgentInvoker.invokeAsync(sessionId, session.agentId, userMessage)` 触发 Agent 异步处理
5. Agent 处理过程中通过 `ApplicationEventPublisher` 发布 `AgentMessage` 事件
6. `SessionEventListener` 接收事件 → 持久化 Agent 回复 + SSE 推送给前端

> **关键**：fire-and-forget 模式。前端在发消息前应先建立 SSE 连接。

---

## 六、实时事件流 API - SSE（新建）

> 由 session-module 的 `SessionController` + `SseSessionEmitter` 提供。

### 6.1 事件流连接

```
GET /api/v1/sessions/{sessionId}/stream
Authorization: Bearer {token}
```

或 Query Parameter 传 Token（EventSource 不支持自定义 Header）：

```
GET /api/v1/sessions/{sessionId}/stream?token={token}
```

**响应格式**：`text/event-stream`

### 6.2 SSE 事件格式

后端使用命名事件发送（`.name("event")`），与 `@foggy/chat` 的 `createSseClient` 对齐。

```
event: event
data: {"id":"evt-001","sessionId":"sess-uuid-001","kind":"TEXT","data":{"content":"好的！"},"createdAt":"2026-02-04T10:31:05"}

event: event
data: {"id":"evt-002","sessionId":"sess-uuid-001","kind":"TOOL_CALL","data":{"toolCallId":"tc-001","toolName":"test_connection","command":"...","thought":"..."},"createdAt":"2026-02-04T10:32:00"}
```

### 6.3 事件类型定义

| kind | 说明 | data 字段 |
|------|------|-----------|
| `TEXT` | Agent 完整文本回复 | `{ content: string }` |
| `TEXT_CHUNK` | Agent 流式文本片段 | `{ content: string }` |
| `TOOL_CALL` | Agent 发起工具调用 | `{ toolCallId, toolName, command, thought? }` |
| `TOOL_RESULT` | 工具执行结果 | `{ toolCallId, toolName, output, exitCode? }` |
| `TOOL_ERROR` | 工具执行失败 | `{ toolCallId, toolName, error }` |
| `THINKING` | Agent 思考过程 | `{ thought }` |
| `STATUS` | 会话状态变更 | `{ status }` |
| `ERROR` | 错误 | `{ error }` |

### 6.4 事件结构

```java
@Data
@Builder
public class SessionStreamEvent {
    private String id;          // 事件 UUID
    private String sessionId;   // 会话 ID
    private String kind;        // 事件类型
    private Map<String, Object> data;
    private LocalDateTime createdAt;
}
```

### 6.5 事件流实现架构

基于 `AgentInvoker` + `AgentMessage` + Spring `@EventListener` 的完整事件流（详见 [2.6 Agent 调用链路设计](#26-agent-调用链路设计)）：

```
SessionController.sendMessage()
    │
    ├── SessionManager.addMessage(USER)          → 持久化用户消息
    │
    └── AgentInvoker.invokeAsync()               → 触发 Agent（异步线程池）
            │
            ├── AgentRegistry.findById()          → 查找 Agent 配置
            ├── SessionManager.getRecentMessages() → 获取历史上下文
            └── LlmAdapter.chatStream()           → 流式调用 LLM
                    │
                    ▼  LlmStreamHandler 回调
            ApplicationEventPublisher.publishEvent(AgentMessage)
                    │
                    ▼
            SessionEventListener (@EventListener + @Async)
                ├── SessionManager.addMessage()    → 持久化 Agent 回复
                └── SseSessionEmitter.sendEvent()  → 推送 SSE
                        │
                        ▼
                    SseEmitter (HTTP SSE)
                        │
                        ▼
                    前端 EventSource → TutorAgentAdapter → ChatStore → ChatPanel
```

### 6.6 SseSessionEmitter 要点

参考 coding-agent 的 `SseEventEmitter` 实现：

| 功能 | 说明 |
|------|------|
| 连接管理 | `ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>>` |
| 心跳 | 每 15 秒发送心跳保持连接（`@Scheduled`） |
| 超时 | `SseEmitter` 超时设置 30 分钟 |
| 清理 | `onCompletion` / `onTimeout` / `onError` 回调清理死连接 |
| 多客户端 | 同一会话支持多个 SSE 连接（如多标签页） |

### 6.7 前端适配映射

前端 `TutorAgentAdapter` 将 SSE 事件转换为 `@foggy/chat` 的 AIP 消息：

| SSE kind | AIP MessageType |
|----------|-----------------|
| `TEXT` | `TEXT_COMPLETE` |
| `TEXT_CHUNK` | `TEXT_CHUNK` |
| `TOOL_CALL` | `TOOL_CALL_START` |
| `TOOL_RESULT` | `TOOL_CALL_RESULT` |
| `TOOL_ERROR` | `TOOL_CALL_ERROR` |
| `THINKING` | `THINKING` |
| `STATUS` | `STATE_SYNC` |
| `ERROR` | `ERROR` |

---

## 七、动态引导卡片 API（新建）

### 7.1 获取引导卡片

```
GET /api/v1/sessions/guide-cards
Authorization: Bearer {token}
```

**Query Parameters**:

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| agentId | String | 系统默认 Agent | 指定由哪个 Agent 生成引导卡片 |

**Response** (`RX<List<GuideCard>>`):
```json
{
  "code": 0,
  "data": [
    {
      "id": "guide-config-datasource",
      "icon": "Setting",
      "title": "配置数据源",
      "description": "连接 MySQL、PostgreSQL 等数据库",
      "prompt": "我想配置一个新的数据源",
      "priority": 1
    },
    {
      "id": "guide-add-git",
      "icon": "Connection",
      "title": "添加 Git 凭证",
      "description": "配置 GitLab、GitHub 等代码仓库",
      "prompt": "我想添加一个 Git 凭证",
      "priority": 2
    },
    {
      "id": "guide-explore-system",
      "icon": "InfoFilled",
      "title": "了解系统功能",
      "description": "查看 Foggy Navigator 能做什么",
      "prompt": "介绍一下你能帮我做什么",
      "priority": 3
    }
  ]
}
```

**GuideCard 字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | String | 卡片标识 |
| icon | String | 图标（Element Plus icon name 或 emoji） |
| title | String | 标题（2-6 字） |
| description | String | 描述（10-20 字） |
| prompt | String | 点击后填充到输入框的文本 |
| priority | int | 排序优先级（小的靠前） |

### 7.2 实现逻辑

引导卡片由 Agent 根据系统状态动态生成。session-module 只负责路由请求到对应 Agent，Agent 自身实现卡片生成逻辑。

**实现方式**：

1. session-module 收到请求，根据 agentId 找到对应 Agent
2. Agent 有一个 `generateGuideCards(userId, tenantId)` 能力（可以是 Skill 或硬编码方法）
3. tutor-agent 的实现：检查数据源配置、Git 凭证等系统状态，返回适当的引导卡片

**状态 → 卡片映射示例**：

| 系统状态 | 返回的卡片 |
|----------|-----------|
| 新系统（无数据源、无 Git 凭证） | "配置数据源"、"添加 Git 凭证"、"了解系统功能" |
| 已有数据源，无 Git 凭证 | "添加 Git 凭证"、"查看表结构"、"配置语义层" |
| 配置完善 | "编写代码"、"数据查询"、"排查问题" |

**初期建议**：规则引擎驱动（if-else），不调用 LLM。后续可迁移为 Skill 驱动。

### 7.3 缓存

- 缓存 5 分钟，Key：`guide-cards:{userId}:{tenantId}`
- 数据源或 Git 凭证变更时清除缓存

---

## 八、配置管理 API（已有）

### 8.1 数据源配置

#### 需要补充：数据源列表查询

```
GET /api/config/datasources
Authorization: Bearer {token}
```

**Query Parameters**: `tenantId`(可选), `status`(可选)

**Response** (`RX<List<DatasourceConfigDTO>>`):
```json
{
  "code": 0,
  "data": [
    {
      "id": "ds-001",
      "tenantId": "default",
      "name": "订单数据库",
      "type": "MYSQL",
      "status": "ENABLED",
      "connectionValid": true
    }
  ]
}
```

#### 已有接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/config/datasource` | POST | 创建数据源 |
| `/api/config/datasource/{id}` | PUT | 更新数据源 |
| `/api/config/datasource/{id}` | DELETE | 删除数据源 |
| `/api/config/datasource/{id}/status` | PATCH | 修改状态 |

### 8.2 Skill 配置（已有，无需修改）

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/config/skills` | GET | 查询列表 |
| `/api/config/skills/{id}` | GET | 详情 |
| `/api/config/skills` | POST | 创建 |
| `/api/config/skills/{id}` | PUT | 更新 |
| `/api/config/skills/{id}` | DELETE | 删除 |
| `/api/config/skills/{id}/status` | PATCH | 修改状态 |

---

## 九、通用约定

### 9.1 响应格式

统一使用 `RX<T>`：

```json
// 成功
{ "code": 0, "message": "success", "data": { ... } }

// 失败
{ "code": 400, "message": "参数错误：title 不能为空", "data": null }

// 认证失败 (HTTP 401)
{ "code": 401, "message": "Token 无效或已过期", "data": null }
```

### 9.2 认证

- 所有 API（除 login/register）需要 `Authorization: Bearer {token}`
- SSE 端点额外支持 `?token={token}` Query Parameter
- Token 过期返回 HTTP 401

### 9.3 时间格式

ISO 8601：`2026-02-04T10:30:00`

### 9.4 ID 格式

UUID (String)

### 9.5 错误码

| HTTP Status | code | 说明 |
|-------------|------|------|
| 200 | 0 | 成功 |
| 400 | 400 | 参数错误 |
| 401 | 401 | 认证失败 |
| 403 | 403 | 权限不足 |
| 404 | 404 | 资源不存在 |
| 500 | 500 | 服务器内部错误 |

### 9.6 跨域

```yaml
cors:
  allowed-origins:
    - http://localhost:5173
    - http://localhost:3000
  allowed-methods: GET, POST, PUT, PATCH, DELETE, OPTIONS
  allowed-headers: "*"
  allow-credentials: true
```

---

## 附录

### A. 数据模型

```
                             session-module (新建)
                             ┌─────────────────────────┐
┌─────────────┐              │  SessionEntity           │
│  UserEntity  │─────userId──│  ──────────────          │
│  (已有)      │              │  id (PK)                │
│              │              │  userId                  │
│ id           │              │  tenantId                │
│ tenantId     │              │  agentId                 │
│ username     │              │  parentSessionId         │
└─────────────┘              │  title                   │
                             │  status (SessionStatus)  │
                             │  createdAt               │
                             │  updatedAt               │
                             └────────┬────────────────┘
                                      │ 1:N
                                      ▼
                             ┌─────────────────────────┐
                             │  SessionMessageEntity    │
                             │  ──────────────────      │
                             │  id (PK)                 │
                             │  sessionId (FK)          │
                             │  role (MessageRole)      │
                             │  content                 │
                             │  metadata (JSON)         │
                             │  createdAt               │
                             └─────────────────────────┘

                             coding-agent (已有，可选关联)
                             ┌─────────────────────────┐
                             │  ConversationEntity      │
                             │  ──────────────────      │
                             │  id                      │
                             │  sessionId (FK, 新增)     │ ← 未来关联到 SessionEntity
                             │  sandboxId               │
                             │  ohConversationId        │
                             │  gitCredentialId         │
                             │  ...                     │
                             └─────────────────────────┘
```

### B. 前端调用时序

#### B.1 新建会话并对话

```
前端                    session-module               agent-framework
 │                          │                              │
 │  POST /sessions          │                              │
 │  { title, agentId }      │                              │
 │─────────────────────────→│  SessionManager              │
 │  { id: "sess-001" }     │  .createSession()            │
 │←─────────────────────────│                              │
 │                          │                              │
 │  GET /sessions/sess-001/ │                              │
 │      stream?token=xxx    │                              │
 │─────────────────────────→│  SseSessionEmitter           │
 │  (SSE connected)         │  .createEmitter()            │
 │←─────────────────────────│                              │
 │                          │                              │
 │  POST /sessions/sess-001/│                              │
 │      messages             │                              │
 │  { content: "配置数据源" } │                              │
 │─────────────────────────→│  ① addMessage(USER)          │
 │  { id: "msg-001" }      │  ② agentInvoker ────────────→│ invokeAsync()
 │←─────────────────────────│    .invokeAsync()            │
 │                          │                              │  ③ agentRegistry.findById()
 │                          │                              │  ④ llmAdapter.chatStream()
 │                          │                              │
 │                          │     ┌── AgentMessage ────────│  ⑤ LLM 回调
 │                          │     ▼                        │
 │                          │  ⑥ SessionEventListener      │
 │  SSE: kind=TEXT_CHUNK    │     ├── sseEmitter.sendEvent()│
 │←─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─│     │                        │
 │                          │     │                        │
 │                          │     ┌── AgentMessage ────────│  ⑦ 工具调用
 │  SSE: kind=TOOL_CALL     │     ├── addMessage(ASSISTANT)│
 │←─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─│     ├── sseEmitter.sendEvent()│
 │                          │     │                        │
 │                          │     ┌── AgentMessage ────────│  ⑧ 工具结果
 │  SSE: kind=TOOL_RESULT   │     ├── addMessage(TOOL)     │
 │←─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─│     ├── sseEmitter.sendEvent()│
 │                          │     │                        │
 │                          │     ┌── AgentMessage ────────│  ⑨ 完成
 │  SSE: kind=TEXT_COMPLETE  │     ├── addMessage(ASSISTANT)│
 │←─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─│     └── sseEmitter.sendEvent()│
 │                          │                              │
```

#### B.2 欢迎页引导卡片

```
前端                            session-module              Agent
 │                               │                          │
 │  GET /sessions/guide-cards    │                          │
 │  ?agentId=tutor-agent         │                          │
 │──────────────────────────────→│  找到 tutor-agent ──────→│
 │                               │                          │ 检查系统状态
 │                               │                          │ 生成卡片列表
 │  [                            │  ←── List<GuideCard>     │
 │    { "配置数据源", ... }       │                          │
 │    { "添加 Git", ... }        │                          │
 │  ]                            │                          │
 │←──────────────────────────────│                          │
```

### C. 与 coding-agent 的关系

```
Navigator Frontend
      │
      ├── /api/v1/auth/*              → user-auth-module (已有)
      ├── /api/v1/sessions/*          → session-module (新建，框架级)
      ├── /api/config/*               → metadata-config-module (已有)
      │
      └── 不直接调用:
          /api/v1/conversations/*      → coding-agent (特化会话，内部调试)
          /api/v1/git-credentials/*    → coding-agent (tutor-agent 通过 Tool 调用)
          /api/v1/environments/*       → coding-agent (tutor-agent 通过 Tool 调用)
```

### D. 新建文件清单

| 模块 | 文件 | 说明 |
|------|------|------|
| **agent-framework** | `core/AgentInvoker.java` | Agent 调用器接口 |
| **agent-framework** | `core/impl/DefaultAgentInvoker.java` | 默认实现（LLM + 工具执行） |
| **agent-framework** | `core/impl/AgentStreamHandler.java` | LLM 流式回调 → AgentMessage 事件 |
| navigator-common | `entity/SessionEntity.java` | 会话 JPA Entity |
| navigator-common | `entity/SessionMessageEntity.java` | 消息 JPA Entity |
| session-module | `pom.xml` | 模块 POM |
| session-module | `config/SessionModuleAutoConfiguration.java` | 自动配置（含线程池 Bean） |
| session-module | `repository/SessionRepository.java` | 会话 Repository |
| session-module | `repository/SessionMessageRepository.java` | 消息 Repository |
| session-module | `service/JpaSessionManager.java` | 持久化 SessionManager |
| session-module | `controller/SessionController.java` | REST API |
| session-module | `sse/SseSessionEmitter.java` | SSE 连接管理 |
| session-module | `event/SessionEventListener.java` | 监听 AgentMessage，持久化 + SSE 推送 |

### E. 开发优先级

#### P0 - 核心功能

1. agent-framework: AgentInvoker 接口 + DefaultAgentInvoker + AgentStreamHandler（2.6）
2. session-module 模块搭建（Entity + Repository + AutoConfiguration）
3. JpaSessionManager 实现（持久化 SessionManager）
4. SessionController - 会话 CRUD（5.1 - 5.5）
5. SessionController - 消息 CRUD + AgentInvoker 集成（5.6 - 5.7）
6. SSE 事件推送（SseSessionEmitter + SessionEventListener 监听 AgentMessage）
7. 端到端验证：发消息 → AgentInvoker → LLM → AgentMessage → SSE 回推
8. 引导卡片 API（7.1）
9. 数据源列表查询补充（8.1）

#### P1 - 完善

10. 消息分页（before 游标）
11. 会话软删除
12. 引导卡片缓存

#### P2 - 增强

13. coding-agent ConversationEntity 关联 sessionId
14. 引导卡片国际化
15. 全局 SSE 端点（跨会话监听）
