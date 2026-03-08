# Coding Agent 开发计划

## 概述

本文档基于 [API_DESIGN.md](./API_DESIGN.md) 制定详细的开发计划，将开发分为多个阶段，从基础功能到高级特性逐步实现。

---

## 阶段划分

### Phase 0: 基础设施准备 ✅

**状态**: 已完成

**目标**: 搭建基础开发环境

**完成内容**:
- ✅ 项目结构搭建
- ✅ Docker 容器管理（OpenHandsContainerManager）
- ✅ 环境管理服务（EnvironmentService）
- ✅ 集成测试框架
- ✅ Docker 连接配置（docker-java 3.7.0 + httpclient5）

---

### Phase 1: 核心数据模型和基础服务

**预计时间**: 3-5 天

**目标**: 实现核心数据模型和基础服务层

#### 1.1 数据模型实现

**文件**: `src/main/java/com/foggy/navigator/api/model/`

**任务**:
- [ ] `Conversation.java` - 对话模型
  - 字段: conversationId, sandboxId, userId, projectId, status, namespace, gitRepoUrl, branchName, createdAt, updatedAt
  - 枚举: ConversationStatus (STARTING, READY, RUNNING, IDLE, ERROR, STOPPED)

- [ ] `Message.java` - 消息模型
  - 字段: messageId, conversationId, content, timestamp

- [ ] `Event.java` - 事件模型
  - 字段: id, conversationId, kind, timestamp, data
  - 枚举: EventKind (CONVERSATION_STATUS, MESSAGE_SENT, AGENT_ACTION, AGENT_OBSERVATION, VALIDATION_TRIGGERED, VALIDATION_RESULT, ERROR)

- [ ] `CreateConversationRequest.java` - 创建对话请求
  - 字段: userId, projectId, gitRepoUrl, branchName, gitCredentials, initialMessage

- [ ] `SendMessageRequest.java` - 发送消息请求
  - 字段: content

- [ ] `ValidationCredentials.java` - Git 凭证模型
  - 字段: username, token

#### 1.2 Conversation 服务实现

**文件**: `src/main/java/com/foggy/navigator/api/service/ConversationService.java`

**任务**:
- [ ] `createConversation(CreateConversationRequest)` - 创建对话
- [ ] `getConversation(String conversationId)` - 获取对话详情
- [ ] `listConversations(String userId, String projectId, ConversationStatus status, int page, int size)` - 查询对话列表
- [ ] `deleteConversation(String conversationId)` - 删除对话
- [ ] `stopConversation(String conversationId)` - 停止对话
- [ ] `exists(String conversationId)` - 检查对话是否存在

#### 1.3 消息服务实现

**文件**: `src/main/java/com/foggy/navigator/api/service/MessageService.java`

**任务**:
- [ ] `sendMessage(String conversationId, SendMessageRequest request)` - 发送消息
- [ ] `getMessages(String conversationId, int limit)` - 获取消息历史

#### 1.4 事件存储服务

**文件**: `src/main/java/com/foggy/navigator/api/service/EventService.java`

**任务**:
- [ ] `saveEvent(Event event)` - 保存事件
- [ ] `getEvents(String conversationId, EventKind kind, LocalDateTime timestampGte, LocalDateTime timestampLt, String pageId, int limit)` - 查询历史事件
- [ ] `getEventsSince(String conversationId, String lastEventId)` - 获取指定事件之后的所有事件
- [ ] `getLatestEventId(String conversationId)` - 获取最新事件 ID

---

### Phase 2: OpenHands API 集成

**预计时间**: 5-7 天

**目标**: 实现 OpenHands V1 API 的客户端封装

#### 2.1 OpenHands 客户端基础

**文件**: `src/main/java/com/foggy/navigator/foundation/git/OpenHandsClient.java`

**任务**:
- [ ] 配置 OpenHands API 基础 URL
- [ ] 实现 HTTP 客户端（使用 RestTemplate 或 WebClient）
- [ ] 实现认证机制（API Key）
- [ ] 实现错误处理和重试机制

#### 2.2 Conversation API

**任务**:
- [ ] `createConversation(CreateConversationRequest)` - 创建 Conversation
  - 调用: `POST /api/v1/app-conversations/stream-start`
  - 返回: conversationId, sandboxId

- [ ] `getConversation(String conversationId)` - 获取 Conversation 详情
  - 调用: `GET /api/v1/app-conversations/{id}`
  - 返回: Conversation 信息

- [ ] `deleteConversation(String conversationId)` - 删除 Conversation
  - 调用: `DELETE /api/v1/app-conversations/{id}`

- [ ] `stopConversation(String conversationId)` - 停止 Conversation
  - 调用: `POST /api/v1/app-conversations/{id}/stop`

#### 2.3 消息 API

**任务**:
- [ ] `sendMessage(String conversationId, String content)` - 发送消息
  - 调用: OpenHands SDK `send_message()`
  - 返回: messageId

#### 2.4 事件 API

**任务**:
- [ ] `searchEvents(String conversationId, EventKind kind, int limit)` - 搜索事件
  - 调用: `GET /api/v1/conversation/{id}/events/search`
  - 返回: 事件列表

- [ ] `getNewEvents(String conversationId, String lastEventId)` - 获取新事件
  - 调用: `GET /api/v1/conversation/{id}/events/search`
  - 参数: timestampGte=lastEventId

---

### Phase 3: 验证服务集成

**预计时间**: 2-3 天

**目标**: 实现与语义层验证服务的集成

#### 3.1 验证服务客户端

**文件**: `src/main/java/com/foggy/navigator/foundation/git/ValidationServiceClient.java`

**任务**:
- [ ] 配置验证服务 URL
- [ ] `validate(String namespace, String gitRepoUrl, String branchName)` - 触发验证
- [ ] `getValidationResult(String validationId)` - 获取验证结果
- [ ] 实现异步验证结果轮询

#### 3.2 验证触发逻辑

**任务**:
- [ ] 在 Conversation 创建后自动触发验证
- [ ] 在消息发送后自动触发验证
- [ ] 处理验证结果并转换为 Event

---

### Phase 4: SSE 事件流实现

**预计时间**: 4-6 天

**目标**: 实现 Server-Sent Events 实时推送

#### 4.1 事件轮询器

**文件**: `src/main/java/com/foggy/navigator/foundation/git/EventPoller.java`

**任务**:
- [ ] `startPolling(String conversationId, Consumer<Event> eventHandler)` - 启动轮询
  - 使用 ScheduledExecutorService 每 2 秒轮询一次
  - 调用 OpenHands API 获取新事件
  - 将事件传递给 eventHandler

- [ ] `stopPolling(String conversationId)` - 停止轮询
- [ ] 管理多个 Conversation 的轮询任务
- [ ] 实现轮询错误处理和重试

#### 4.2 SSE 服务

**文件**: `src/main/java/com/foggy/navigator/api/service/EventStreamService.java`

**任务**:
- [ ] `subscribe(String conversationId, String lastEventId)` - 订阅事件流
  - 创建 SseEmitter
  - 启动轮询
  - 将事件推送到 SSE
  - 处理断线重连（通过 lastEventId）

- [ ] `unsubscribe(String conversationId)` - 取消订阅
- [ ] 管理 SseEmitter 生命周期（onCompletion, onTimeout）
- [ ] 实现 SSE 心跳机制（防止连接超时）

#### 4.3 SSE 控制器

**文件**: `src/main/java/com/foggy/navigator/api/controller/EventStreamController.java`

**任务**:
- [ ] `GET /api/conversations/{conversationId}/events/stream` - 事件流端点
  - 路径参数: conversationId
  - 查询参数: lastEventId
  - 返回: SseEmitter

---

### Phase 5: REST API 控制器

**预计时间**: 3-4 天

**目标**: 实现所有 REST API 端点

#### 5.1 Conversation 控制器

**文件**: `src/main/java/com/foggy/navigator/api/controller/ConversationController.java`

**任务**:
- [ ] `POST /api/conversations` - 创建并启动 Conversation
  - 请求体: CreateConversationRequest
  - 响应: Conversation 信息

- [ ] `GET /api/conversations/{conversationId}` - 获取 Conversation 详情
  - 路径参数: conversationId
  - 响应: Conversation 信息

- [ ] `GET /api/conversations` - 查询 Conversation 列表
  - 查询参数: userId, projectId, status, page, size
  - 响应: Conversation 列表 + 分页信息

- [ ] `DELETE /api/conversations/{conversationId}` - 删除 Conversation
  - 路径参数: conversationId
  - 响应: 成功消息

- [ ] `POST /api/conversations/{conversationId}/stop` - 停止 Conversation
  - 路径参数: conversationId
  - 响应: 成功消息

#### 5.2 消息控制器

**文件**: `src/main/java/com/foggy/navigator/api/controller/MessageController.java`

**任务**:
- [ ] `POST /api/conversations/{conversationId}/messages` - 发送消息
  - 路径参数: conversationId
  - 请求体: SendMessageRequest
  - 响应: Message 信息

#### 5.3 事件控制器

**文件**: `src/main/java/com/foggy/navigator/api/controller/EventController.java`

**任务**:
- [ ] `GET /api/conversations/{conversationId}/events` - 查询历史事件
  - 路径参数: conversationId
  - 查询参数: kind, timestampGte, timestampLt, pageId, limit
  - 响应: 事件列表 + 分页信息

- [ ] `POST /api/conversations/{conversationId}/validate` - 触发验证
  - 路径参数: conversationId
  - 响应: 验证任务信息

---

### Phase 6: 会话持久化和恢复

**预计时间**: 3-4 天

**目标**: 实现会话持久化、自动清理和恢复机制

#### 6.1 数据库持久化

**任务**:
- [ ] 设计数据库表结构（Conversation, Message, Event）
- [ ] 实现 JPA Repository
- [ ] 迁移现有内存存储到数据库

#### 6.2 自动清理策略

**文件**: `src/main/java/com/foggy/navigator/api/service/ConversationCleanupService.java`

**任务**:
- [ ] 实现空闲超时清理（idle-timeout 配置）
- [ ] 实现最大生命周期清理（max-lifetime 配置）
- [ ] 使用 @Scheduled 定期执行清理任务
- [ ] 实现清理前的通知机制

#### 6.3 孤儿实例检测

**任务**:
- [ ] 定期扫描 Docker 容器
- [ ] 检测孤儿实例（容器存在但数据库无记录）
- [ ] 自动清理孤儿实例
- [ ] 记录孤儿实例日志

#### 6.4 会话恢复逻辑

**任务**:
- [ ] 实现会话状态同步（定期轮询 OpenHands）
- [ ] 实现沙箱保活和恢复逻辑
- [ ] 处理不同沙箱状态（RUNNING, STOPPED, MISSING）
- [ ] 实现 Event 缓存（最近 1000 条）

---

### Phase 7: 测试和优化

**预计时间**: 3-5 天

**目标**: 完善测试覆盖率和性能优化

#### 7.1 单元测试

**任务**:
- [ ] ConversationService 单元测试
- [ ] MessageService 单元测试
- [ ] EventService 单元测试
- [ ] OpenHandsClient 单元测试（Mock）
- [ ] ValidationServiceClient 单元测试（Mock）
- [ ] EventPoller 单元测试
- [ ] EventStreamService 单元测试

#### 7.2 集成测试

**任务**:
- [ ] Conversation API 集成测试
- [ ] Message API 集成测试
- [ ] Event API 集成测试
- [ ] SSE 事件流集成测试
- [ ] 验证服务集成测试
- [ ] 会话恢复集成测试

#### 7.3 性能优化

**任务**:
- [ ] 优化事件轮询频率（动态调整）
- [ ] 优化 SSE 连接管理（连接池）
- [ ] 优化数据库查询（索引、分页）
- [ ] 实现事件缓存（减少数据库查询）

#### 7.4 错误处理和日志

**任务**:
- [ ] 完善全局异常处理
- [ ] 实现统一的错误响应格式
- [ ] 优化日志级别和格式
- [ ] 添加监控指标（Prometheus/Micrometer）

---

### Phase 8: 文档和部署

**预计时间**: 2-3 天

**目标**: 完善文档和部署准备

#### 8.1 API 文档

**任务**:
- [ ] 使用 Swagger/OpenAPI 生成 API 文档
- [ ] 编写 API 使用示例
- [ ] 编写前端集成指南

#### 8.2 部署文档

**任务**:
- [ ] 编写 Docker 部署文档
- [ ] 编写 Kubernetes 部署文档
- [ ] 编写环境配置文档

#### 8.3 运维文档

**任务**:
- [ ] 编写监控和告警文档
- [ ] 编写故障排查文档
- [ ] 编写性能调优文档

---

## 依赖关系图

```
Phase 0 (基础设施)
    ↓
Phase 1 (数据模型和基础服务)
    ↓
Phase 2 (OpenHands API 集成)
    ↓
Phase 3 (验证服务集成)
    ↓
Phase 4 (SSE 事件流)
    ↓
Phase 5 (REST API 控制器)
    ↓
Phase 6 (会话持久化和恢复)
    ↓
Phase 7 (测试和优化)
    ↓
Phase 8 (文档和部署)
```

---

## 关键里程碑

| 里程碑 | 预计完成时间 | 标志 |
|--------|-------------|------|
| M1: 核心数据模型完成 | Phase 1 完成 | 所有数据模型和基础服务可用 |
| M2: OpenHands 集成完成 | Phase 2 完成 | 可以创建和管理 Conversation |
| M3: 验证服务集成完成 | Phase 3 完成 | 可以触发语义层验证 |
| M4: SSE 事件流完成 | Phase 4 完成 | 可以实时推送事件 |
| M5: REST API 完成 | Phase 5 完成 | 所有 API 端点可用 |
| M6: 持久化完成 | Phase 6 完成 | 会话可以持久化和恢复 |
| M7: 测试完成 | Phase 7 完成 | 测试覆盖率达标 |
| M8: 生产就绪 | Phase 8 完成 | 可以部署到生产环境 |

---

## 风险和缓解措施

### 技术风险

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| OpenHands API 变更 | 高 | 使用抽象层封装，便于适配变更 |
| SSE 连接不稳定 | 中 | 实现断线重连和事件缓存 |
| Docker 资源不足 | 中 | 实现资源限制和自动清理 |
| 数据库性能瓶颈 | 中 | 优化查询、添加索引、使用缓存 |

### 进度风险

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| 需求变更 | 中 | 保持模块化，便于调整 |
| 测试时间不足 | 中 | 提前规划测试，并行开发 |
| 集成问题 | 高 | 早期进行集成测试 |

---

## 开发规范

### 代码规范

- 使用 Lombok 简化代码
- 使用 Builder 模式创建对象
- 使用 SLF4J 进行日志记录
- 使用 JUnit 5 进行单元测试
- 使用 Mockito 进行 Mock

### Git 规范

- 功能分支: `feature/phase-X-task-name`
- 修复分支: `fix/bug-description`
- 提交信息: `Phase X: task description`

### 代码审查

- 每个 Phase 完成后进行代码审查
- 重点审查: 安全性、性能、可维护性
- 必须通过所有测试才能合并

---

## 下一步

请确认此开发计划，我将开始 **Phase 1: 核心数据模型和基础服务** 的实现。
