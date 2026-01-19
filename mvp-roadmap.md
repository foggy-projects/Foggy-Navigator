# MVP 功能清单和实施计划

## 1. 功能分类分析

### 1.1 核心功能（MVP 必需）

核心功能是系统最小可用版本必须具备的功能，没有这些功能系统无法正常运行。

#### 1.1.1 Agent 管理
- ✅ Agent 定义和存储
- ✅ Agent 基本配置
- ✅ Agent 查询和选择
- ❌ Agent 能力描述（可简化）
- ❌ Agent 权限管理（可简化）

#### 1.1.2 Session 管理
- ✅ Session 创建和恢复
- ✅ 消息存储和检索
- ✅ 基本上下文管理
- ❌ 上下文压缩（可延后）
- ❌ 上下文窗口优化（可延后）

#### 1.1.3 Task 编排
- ✅ 基本任务执行
- ✅ 任务状态跟踪
- ❌ 任务分解（可延后）
- ❌ 动态任务调整（可延后）
- ❌ 任务中断和恢复（可延后）

#### 1.1.4 Memory 系统
- ✅ 短期记忆（ChatMemory）
- ❌ 长期记忆（可延后）
- ❌ 记忆压缩（可延后）
- ❌ 记忆关联（可延后）

#### 1.1.5 RAG 模块
- ❌ 知识库检索（可延后）
- ❌ 上下文注入（可延后）
- ❌ 检索策略优化（可延后）

#### 1.1.6 Tool 模块
- ✅ 内置工具（文件读写、HTTP 请求）
- ❌ MCP 工具（可延后）
- ❌ HITL 人工确认（可延后）
- ❌ 工具隔离（可延后）

#### 1.1.7 编排层
- ✅ 基本请求处理流程
- ✅ Agent 选择
- ✅ Session 管理
- ✅ 模型调用
- ✅ 响应生成
- ❌ 复杂流程编排（可延后）

#### 1.1.8 可观察性
- ✅ 基本日志记录
- ❌ 实时监控（可延后）
- ❌ 任务控制（可延后）
- ❌ 告警机制（可延后）

### 1.2 重要功能（Phase 2）

重要功能是提升系统可用性和用户体验的功能，但不是 MVP 必需的。

- Agent 能力描述和匹配
- Session 上下文压缩
- 任务分解和规划
- 长期记忆存储
- 知识库检索（RAG）
- MCP 工具支持
- HITL 人工确认
- 实时监控和任务控制

### 1.3 增强功能（Phase 3+）

增强功能是进一步提升系统能力的功能。

- 动态任务调整
- 记忆压缩和优化
- 记忆关联
- 高级 RAG 策略
- 工具隔离和连接池
- 告警机制
- 数据可视化
- 多租户支持

## 2. MVP 功能清单

### 2.1 MVP 目标

**目标**：实现一个最小可用的 AI 对话系统，支持：
- 多 Agent 管理
- 基本对话功能
- 简单工具调用
- 基本日志记录

### 2.2 MVP 功能列表

#### 2.2.1 数据模型

**Agent 相关**
- Agent 表：存储 Agent 定义
  - id, name, description, type, config, created_at, updated_at

**Session 相关**
- Session 表：存储会话信息
  - id, user_id, agent_id, status, created_at, updated_at
- Message 表：存储对话消息
  - id, session_id, role, content, created_at

**Tool 相关**
- ToolDefinition 表：存储工具定义
  - id, name, description, type, config, created_at

**Orchestration 相关**
- OrchestrationRequest 表：存储请求记录
  - id, user_id, session_id, agent_id, message, status, created_at

#### 2.2.2 核心服务

**AgentService**
- createAgent(definition)
- updateAgent(id, definition)
- deleteAgent(id)
- getAgent(id)
- getAllAgents()
- selectAgent(request)

**SessionService**
- createSession(userId, agentId)
- getSession(sessionId)
- closeSession(sessionId)
- addMessage(sessionId, message)
- getMessages(sessionId)

**ToolService**
- registerTool(toolDefinition)
- executeTool(toolId, parameters)
- getTool(toolId)
- getAllTools()

**OrchestrationService**
- processRequest(request)
- processRequestWithStreaming(request, streamHandler)

#### 2.2.3 内置工具

**文件工具**
- readFile(filePath)
- writeFile(filePath, content)
- listFiles(dirPath)

**HTTP 工具**
- httpRequest(url, method, headers, body)

#### 2.2.4 API 接口

**Agent API**
- POST /api/agents - 创建 Agent
- GET /api/agents - 查询所有 Agent
- GET /api/agents/{id} - 查询 Agent 详情
- PUT /api/agents/{id} - 更新 Agent
- DELETE /api/agents/{id} - 删除 Agent

**Session API**
- POST /api/sessions - 创建 Session
- GET /api/sessions/{id} - 查询 Session
- POST /api/sessions/{id}/messages - 发送消息
- GET /api/sessions/{id}/messages - 获取消息列表
- DELETE /api/sessions/{id} - 关闭 Session

**Orchestration API**
- POST /api/orchestration/process - 处理请求
- POST /api/orchestration/process/stream - 流式处理

#### 2.2.5 基础设施

**数据库**
- MySQL 8.0+
- 基本表结构和索引

**缓存**
- Redis（可选，用于 Session 缓存）

**日志**
- 基本日志记录（文件日志）
- 结构化日志格式

**配置**
- application.yml 配置文件
- Agent 配置
- 模型配置

## 3. 分阶段实施计划

### Phase 1: MVP（4-6 周）

**目标**：实现最小可用的 AI 对话系统

**Week 1-2: 基础设施和数据模型**
- 搭建 Spring Boot 项目
- 配置数据库连接
- 创建数据表
- 实现基础配置管理

**Week 3-4: 核心服务**
- 实现 AgentService
- 实现 SessionService
- 实现 ToolService
- 实现基本工具（文件、HTTP）

**Week 5-6: 编排层和 API**
- 实现 OrchestrationService
- 实现 REST API
- 集成 LangChain4j
- 基本测试

**交付物**：
- 可运行的 MVP 系统
- 支持 Agent 管理
- 支持基本对话
- 支持简单工具调用

### Phase 2: 核心增强（6-8 周）

**目标**：添加重要功能，提升系统能力

**Week 1-2: Session 增强**
- 实现上下文管理
- 实现上下文压缩
- 优化消息存储

**Week 3-4: Task 编排**
- 实现任务分解
- 实现任务执行
- 实现任务状态跟踪

**Week 5-6: Memory 系统**
- 实现长期记忆存储
- 集成向量数据库（Milvus/Qdrant）
- 实现记忆检索

**Week 7-8: RAG 模块**
- 实现知识库管理
- 实现知识检索
- 实现上下文注入

**交付物**：
- 增强的对话系统
- 支持任务分解
- 支持长期记忆
- 支持 RAG 检索

### Phase 3: 高级功能（8-10 周）

**目标**：添加高级功能，提升用户体验

**Week 1-2: MCP 工具**
- 实现 MCP 客户端
- 实现 MCP 连接池
- 实现 MCP 工具适配器

**Week 3-4: HITL 人工确认**
- 实现 HITL 管理器
- 实现 HITL API
- 实现审批流程

**Week 5-6: 可观察性**
- 实现实时监控
- 实现任务控制
- 实现指标收集

**Week 7-8: 告警和日志**
- 实现告警规则
- 实现告警通知
- 实现日志追踪

**Week 9-10: 测试和优化**
- 编写单元测试
- 编写集成测试
- 性能优化
- 文档完善

**交付物**：
- 完整的企业级系统
- 支持 MCP 工具
- 支持 HITL
- 完整的可观察性

### Phase 4: 生产就绪（4-6 周）

**目标**：系统生产部署和运维

**Week 1-2: 安全加固**
- 实现认证和授权
- 实现数据加密
- 实现审计日志

**Week 3-4: 性能优化**
- 实现缓存策略
- 优化数据库查询
- 实现连接池

**Week 5-6: 部署和运维**
- 容器化部署
- Kubernetes 编排
- 监控和告警配置
- 灾备方案

**交付物**：
- 生产就绪的系统
- 完整的部署文档
- 运维手册

## 4. MVP 技术栈

### 4.1 核心技术

- **Java**: 17+
- **Spring Boot**: 3.x
- **LangChain4j**: 最新稳定版

### 4.2 数据库

- **MySQL**: 8.0+（主数据库）
- **Redis**: 7.x（可选，用于缓存）

### 4.3 构建工具

- **Maven**: 3.8+
- **JDK**: 17+

### 4.4 开发工具

- **IDE**: IntelliJ IDEA / VS Code
- **Git**: 版本控制
- **Postman**: API 测试

## 5. MVP 数据库设计

### 5.1 Agent 表

```sql
CREATE TABLE agents (
    id VARCHAR(100) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    type VARCHAR(50) NOT NULL,
    config JSON,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_type (type),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 5.2 Session 表

```sql
CREATE TABLE sessions (
    id VARCHAR(100) PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    agent_id VARCHAR(100) NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_user_id (user_id),
    INDEX idx_agent_id (agent_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at),
    FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 5.3 Message 表

```sql
CREATE TABLE messages (
    id VARCHAR(100) PRIMARY KEY,
    session_id VARCHAR(100) NOT NULL,
    role VARCHAR(50) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    INDEX idx_session_id (session_id),
    INDEX idx_created_at (created_at),
    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 5.4 ToolDefinition 表

```sql
CREATE TABLE tool_definitions (
    id VARCHAR(100) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    type VARCHAR(50) NOT NULL,
    config JSON,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_type (type),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 5.5 OrchestrationRequest 表

```sql
CREATE TABLE orchestration_requests (
    id VARCHAR(100) PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    session_id VARCHAR(100),
    agent_id VARCHAR(100) NOT NULL,
    message TEXT NOT NULL,
    status VARCHAR(50) NOT NULL,
    result JSON,
    error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_user_id (user_id),
    INDEX idx_session_id (session_id),
    INDEX idx_agent_id (agent_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## 6. MVP 项目结构

```
foggy-navigator/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/foggy/navigator/
│   │   │       ├── NavigatorApplication.java
│   │   │       ├── agent/
│   │   │       │   ├── Agent.java
│   │   │       │   ├── AgentService.java
│   │   │       │   ├── AgentController.java
│   │   │       │   └── AgentRepository.java
│   │   │       ├── session/
│   │   │       │   ├── Session.java
│   │   │       │   ├── Message.java
│   │   │       │   ├── SessionService.java
│   │   │       │   ├── SessionController.java
│   │   │       │   └── SessionRepository.java
│   │   │       ├── tool/
│   │   │       │   ├── ToolDefinition.java
│   │   │       │   ├── ToolService.java
│   │   │       │   ├── ToolController.java
│   │   │       │   └── ToolRepository.java
│   │   │       ├── orchestration/
│   │   │       │   ├── OrchestrationService.java
│   │   │       │   ├── OrchestrationController.java
│   │   │       │   └── OrchestrationRequest.java
│   │   │       └── config/
│   │   │           ├── DatabaseConfig.java
│   │   │           └── LangChain4jConfig.java
│   │   └── resources/
│   │       ├── application.yml
│   │       └── db/migration/
│   └── test/
│       └── java/
│           └── com/foggy/navigator/
├── pom.xml
└── README.md
```

## 7. MVP 验收标准

### 7.1 功能验收

- [ ] 可以创建和管理 Agent
- [ ] 可以创建 Session 并进行对话
- [ ] 可以调用内置工具（文件读写、HTTP 请求）
- [ ] 可以查询历史消息
- [ ] 可以关闭 Session
- [ ] 日志正常记录

### 7.2 性能验收

- [ ] 单次请求响应时间 < 5 秒
- [ ] 支持 10 个并发 Session
- [ ] 系统稳定运行 24 小时无崩溃

### 7.3 质量验收

- [ ] 代码覆盖率 > 60%
- [ ] 无严重 Bug
- [ ] API 文档完整

## 8. 风险和缓解

### 8.1 技术风险

**风险**：LangChain4j 学习曲线陡峭
**缓解**：提前学习官方文档，参考示例代码

**风险**：模型调用失败
**缓解**：实现重试机制，提供降级方案

### 8.2 进度风险

**风险**：功能范围过大，无法按时完成
**缓解**：严格按 MVP 范围实施，非核心功能延后

**风险**：需求变更
**缓解**：明确 MVP 范围，变更走流程

### 8.3 质量风险

**风险**：测试不充分
**缓解**：编写单元测试和集成测试，进行代码审查

**风险**：性能不达标
**缓解**：提前进行性能测试，优化关键路径

## 9. 总结

MVP 的核心目标是快速验证系统架构和核心功能，为后续迭代奠定基础。通过合理的功能分类和分阶段实施计划，我们可以：

1. **快速交付**：4-6 周交付 MVP
2. **风险可控**：分阶段实施，逐步验证
3. **灵活调整**：根据实际情况调整计划
4. **持续改进**：基于反馈不断优化

MVP 实现后，我们可以根据用户反馈和实际需求，逐步添加 Phase 2、Phase 3 的高级功能，最终实现完整的企业级动态 Agent 编排系统。
