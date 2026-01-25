# Foggy Navigator 系统架构概览

> 基于AI驱动的数据分析平台 - 系统架构与设计原则

## 1. 系统定位与目标

### 1.1 核心价值

**Foggy Navigator** 是一个AI驱动的数据分析平台，核心价值在于：

- **自动化语义层生成**: 通过AI分析数据库结构，自动生成业务语义层
- **智能引导配置**: 导师Agent引导管理员快速完成系统配置
- **自然语言查询**: 用户通过自然语言查询数据，无需编写SQL
- **安全可控**: 沙盒验证 + Git版本管理 + 权限控制

### 1.2 目标用户

| 角色 | 职责 | 核心诉求 |
|------|------|---------|
| **管理员** | 配置系统、管理语义层 | 快速上手、安全可控 |
| **终端用户** | 使用自然语言查询数据 | 查询准确、易于使用 |

### 1.3 设计原则

1. **模块化**: 各模块职责清晰，松耦合设计
2. **配置化**: Agent、Skill通过配置定义，减少硬编码
3. **可观察**: 完整的日志、监控、追踪能力
4. **可测试**: 接口清晰，易于单元测试和集成测试
5. **渐进式**: MVP优先，持续迭代增强

---

## 2. 系统整体架构

### 2.1 分层架构

```
┌─────────────────────────────────────────────────────────────┐
│                     表现层 (Presentation)                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │  Web UI      │  │  API Gateway │  │  Mobile App  │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│                     API 层 (API Layer)                       │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │  REST API    │  │  WebSocket   │  │  SSE         │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│                   Agent 层 (Agent Layer)                     │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  Agent框架（公共模块）                                │   │
│  │  - Agent配置管理  - Skill系统  - 会话管理            │   │
│  │  - 工具调用       - 分派路由   - LLM集成             │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ 导师 Agent   │  │ 语义层Agent  │  │ 数据分析Agent│      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│                 业务模块层 (Business Modules)                │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ 数据源管理   │  │ 语义层管理   │  │ 权限管理     │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│  ┌──────────────┐  ┌──────────────┐                        │
│  │ Git集成      │  │ 沙盒验证     │                        │
│  └──────────────┘  └──────────────┘                        │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│                 基础设施层 (Infrastructure)                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │   Database   │  │   Cache      │  │  Git Repo    │      │
│  │   (MySQL)    │  │   (Redis)    │  │ (GitLab)     │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 核心流程

#### 管理员首次配置流程

```
1. 管理员登录
   ↓
2. 导师Agent欢迎并检查系统状态
   ↓
3. 引导配置数据源（分派给数据源Agent）
   ↓
4. 引导生成语义层（分派给语义层Agent）
   - AI分析数据库结构
   - 生成语义模型文件
   - 提交到Git
   - 沙盒环境验证
   ↓
5. 配置权限
   ↓
6. 同步到正式环境
```

#### 用户查询流程

```
1. 用户输入自然语言查询
   ↓
2. 数据分析Agent理解意图
   ↓
3. 基于语义层生成SQL
   ↓
4. 应用权限过滤
   ↓
5. 执行查询
   ↓
6. 返回结果（数据 + 可视化）
```

---

## 3. 核心模块职责

### 3.1 Agent框架（公共模块）

**职责**:
- Agent配置加载与管理
- Skill系统（加载、匹配、执行）
- 会话与上下文管理
- Agent间分派与路由
- 工具管理与调用
- LLM交互封装

**边界**:
- 提供通用能力，不涉及具体业务逻辑
- 业务逻辑在Skills和具体Agent中实现

**详细设计**: 参见 `agent-framework-requirements.md`

---

### 3.2 导师Agent

**职责**:
- 引导用户完成系统配置
- 检查系统配置状态
- 分派任务给专业Agent
- 提醒用户继续未完成任务

**能力**:
- 系统配置检查
- 任务规划与分派
- 用户引导

**详细设计**: 参见 `tutor-agent-design.md`

---

### 3.3 数据源管理模块

**职责**:
- 数据源连接配置
- 数据库Schema读取
- 连接测试与验证
- 凭证加密��储

**边界**:
- 不负责语义层生成（由语义层Agent负责）
- 提供Schema查询接口供其他模块使用

---

### 3.4 语义层管理模块

**职责**:
- 语义层文件生成（基于AI）
- 语义层版本管理（Git）
- 沙盒环境验证
- 语义层同步到正式环境

**核心流程**:
```
读取DB Schema → AI分析 → 生成语义层文件 →
提交Git → 沙盒验证 → 管理员审核 → 同步正式环境
```

---

### 3.5 权限管理模块

**职责**:
- 基于语义层的权限配置
- 用户角色管理
- 数据访问控制
- 权限规则验证

---

### 3.6 查询执行模块

**职责**:
- 自然语言理解
- 基于语义层生成SQL
- 应用权限过滤
- 查询执行与结果返回
- 结果可视化

---

## 4. 可观察性设计

### 4.1 日志系统

**分层日志策略**:

| 层级 | 记录内容 | 用途 |
|------|---------|------|
| **业务日志** | 用户操作、任务执行、配置变更 | 业务审计、问题追溯 |
| **Agent日志** | Agent调用、Skill执行、分派路由 | Agent行为分析 |
| **系统日志** | API调用、数据库操作、异常信息 | 系统监控、故障排查 |
| **性能日志** | 响应时间、Token使用、资源占用 | 性能优化 |

**日志格式**:
```json
{
  "timestamp": "2026-01-25T10:30:00Z",
  "level": "INFO",
  "module": "tutor-agent",
  "event": "delegation",
  "details": {
    "sourceAgent": "tutor-agent",
    "targetAgent": "semantic-layer-agent",
    "intent": "generate-semantic-layer",
    "sessionId": "session-123"
  },
  "traceId": "trace-abc-123"
}
```

**关键日志点**:
- Agent启动/停止
- Skill匹配与执行
- 工具调用（HTTP请求/响应）
- Agent分派
- 配置变更
- 错误和异常

---

### 4.2 监控指标

**Agent性能指标**:
```
- agent_requests_total{agent="tutor-agent"} - 请求总数
- agent_request_duration_seconds{agent="tutor-agent"} - 响应时间
- agent_delegation_total{source="tutor-agent", target="*"} - 分派次数
- agent_skill_execution_total{skill="check-status"} - Skill执行次数
- agent_tool_calls_total{tool="checkDatasourceStatus"} - 工具调用次数
- agent_errors_total{agent="tutor-agent", type="*"} - 错误数
```

**会话指标**:
```
- session_active_total - 活跃会话数
- session_duration_seconds - 会话时长
- session_message_count - 会话消息数
- session_pending_total{user="*"} - 待办会话数
```

**LLM使用指标**:
```
- llm_requests_total{model="gpt-4"} - 调用次数
- llm_tokens_total{type="prompt|completion"} - Token使用量
- llm_cost_total{model="gpt-4"} - 成本统计
- llm_latency_seconds - LLM响应延迟
```

**业务指标**:
```
- datasource_configured_total - 已配置数据源数
- semantic_layer_generated_total - 已生成语义层数
- query_executed_total - 查询执行次数
- query_success_rate - 查询成功率
```

---

### 4.3 追踪（Tracing）

**追踪范围**:
- 完整的请求链路（用户请求 → Agent → LLM → 工具 → 响应）
- Agent间调用链
- Skill执行流程
- 工具调用细节

**追踪信息**:
```json
{
  "traceId": "trace-abc-123",
  "spans": [
    {
      "spanId": "span-001",
      "name": "tutor-agent.handleMessage",
      "startTime": "2026-01-25T10:30:00Z",
      "duration": 1500,
      "tags": {
        "agent.id": "tutor-agent",
        "session.id": "session-123",
        "user.message": "检查系统状态"
      }
    },
    {
      "spanId": "span-002",
      "parentSpanId": "span-001",
      "name": "skill.execute",
      "startTime": "2026-01-25T10:30:00.100Z",
      "duration": 800,
      "tags": {
        "skill.id": "check-system-status"
      }
    },
    {
      "spanId": "span-003",
      "parentSpanId": "span-002",
      "name": "tool.call",
      "startTime": "2026-01-25T10:30:00.200Z",
      "duration": 50,
      "tags": {
        "tool.name": "checkDatasourceStatus",
        "http.method": "GET",
        "http.url": "/api/tutor/config/datasource/status"
      }
    }
  ]
}
```

---

### 4.4 告警规则

**Agent异常告警**:
```yaml
- name: HighAgentErrorRate
  condition: rate(agent_errors_total[5m]) > 0.1
  severity: warning
  message: "Agent错误率超过10%"

- name: AgentResponseSlow
  condition: histogram_quantile(0.95, agent_request_duration_seconds) > 5
  severity: warning
  message: "Agent响应时间P95超过5秒"
```

**会话异常告警**:
```yaml
- name: TooManyPendingSessions
  condition: session_pending_total > 100
  severity: info
  message: "待办会话数过多"
```

**资源告警**:
```yaml
- name: HighLLMCost
  condition: rate(llm_cost_total[1h]) > 10
  severity: warning
  message: "LLM成本每小时超过$10"
```

---

## 5. 测试性设计

### 5.1 测试策略

| 测试类型 | 测试范围 | 覆盖率目标 |
|---------|---------|-----------|
| **单元测试** | 各模块核心类 | > 80% |
| **集成测试** | 模块间交互 | 核心流程100% |
| **E2E测试** | 完整用户流程 | 关键场景100% |
| **性能测试** | 并发、响应时间 | 基准场景 |

---

### 5.2 接口可测试性

**原则**:
1. 所有公共接口必须有对应的单元测试
2. 依赖外部服务的模块提供Mock接口
3. 配置化的组件支持测试配置

**示例 - Agent框架接口**:
```java
// 生产代码
public interface AgentConfigLoader {
    AgentConfig load(String path);
}

// 测试Mock
public class MockAgentConfigLoader implements AgentConfigLoader {
    private Map<String, AgentConfig> configs = new HashMap<>();

    public void registerTestConfig(String path, AgentConfig config) {
        configs.put(path, config);
    }

    @Override
    public AgentConfig load(String path) {
        return configs.get(path);
    }
}

// 测试用例
@Test
public void testAgentRegistration() {
    MockAgentConfigLoader loader = new MockAgentConfigLoader();
    loader.registerTestConfig("test-agent.yml",
        AgentConfig.builder()
            .id("test-agent")
            .name("测试Agent")
            .build()
    );

    AgentRegistry registry = new AgentRegistry(loader);
    registry.register(loader.load("test-agent.yml"));

    assertNotNull(registry.findById("test-agent"));
}
```

---

### 5.3 Agent测试框架

**Agent单元测试**:
```java
@Test
public void testTutorAgentHandlesStatusCheck() {
    // Arrange
    MockSessionManager sessionManager = new MockSessionManager();
    MockConfigService configService = new MockConfigService();
    configService.setDatasourceStatus(true);
    configService.setSemanticLayerStatus(false);

    TutorAgent agent = new TutorAgent(sessionManager, configService);

    // Act
    AgentResponse response = agent.handleMessage(
        "检查系统状态",
        "session-123"
    );

    // Assert
    assertTrue(response.getContent().contains("数据源已配置"));
    assertTrue(response.getContent().contains("语义层尚未生成"));
}
```

**Skill测试**:
```java
@Test
public void testSkillMatching() {
    SkillManager skillManager = new SkillManager();
    skillManager.loadSkills("classpath:skills/tutor");

    Skill matched = skillManager.matchSkill(
        "check-status",
        Arrays.asList("检查", "状态"),
        "tutor-agent"
    );

    assertNotNull(matched);
    assertEquals("check-system-status", matched.getId());
}
```

**分派规则测试**:
```java
@Test
public void testDelegationPreconditions() {
    DelegationRule rule = DelegationRule.builder()
        .name("delegate-semantic-layer")
        .preconditions(Map.of("datasource_configured", true))
        .build();

    MockConfigService configService = new MockConfigService();
    configService.setDatasourceStatus(true);

    PreconditionChecker checker = new PreconditionChecker(configService);
    PreconditionCheckResult result = checker.check(rule.getPreconditions());

    assertTrue(result.isSatisfied());
}
```

---

### 5.4 集成测试

**完整配置流程测试**:
```java
@SpringBootTest
@AutoConfigureMockMvc
public class ConfigurationFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    public void testCompleteConfigurationFlow() throws Exception {
        // 1. 用户登录，导师Agent欢迎
        MvcResult result1 = mockMvc.perform(post("/api/sessions")
                .param("userId", "test-user")
                .param("agentId", "tutor-agent"))
            .andExpect(status().isOk())
            .andReturn();

        String sessionId = extractSessionId(result1);

        // 2. 用户询问状态
        mockMvc.perform(post("/api/sessions/{sessionId}/messages", sessionId)
                .content("{\"message\": \"检查系统状态\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.response").value(containsString("数据源尚未配置")));

        // 3. 用户配置数据源（触发分派）
        MvcResult result3 = mockMvc.perform(post("/api/sessions/{sessionId}/messages", sessionId)
                .content("{\"message\": \"配置数据源\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.delegated").value(true))
            .andExpect(jsonPath("$.targetAgent").value("datasource-agent"))
            .andReturn();

        // 验证分派结果
        assertNotNull(result3.getResponse().getHeader("X-Redirect-Session"));
    }
}
```

---

### 5.5 性能测试基准

**响应时间基准**:
```
- Agent消息处理（简单查询）: < 500ms
- Agent消息处理（需要工具调用）: < 1s
- Agent分派: < 200ms
- Skill匹配: < 100ms
- 工具HTTP调用: < 200ms
```

**并发性能基准**:
```
- 并发会话数: > 100
- 并发消息处理: > 50 req/s
- 单会话QPS: > 5
```

**测试场景**:
```java
@Test
public void performanceTest_AgentResponseTime() {
    StopWatch stopWatch = new StopWatch();

    for (int i = 0; i < 100; i++) {
        stopWatch.start();
        agent.handleMessage("检查系统状态", "session-" + i);
        stopWatch.stop();
    }

    double avgTime = stopWatch.getTotalTimeMillis() / 100.0;
    assertTrue("平均响应时间应小于500ms", avgTime < 500);
}
```

---

## 6. 技术栈

### 6.1 MVP阶段

| 组件 | 技术选型 | 说明 |
|------|---------|------|
| 后端框架 | Spring Boot 3.x | 成熟稳定 |
| Agent框架 | LangChain4j | Java生态LLM框架 |
| 数据库 | MySQL 8.0+ | 关系型数据 |
| 缓存 | Redis（可选） | 会话缓存 |
| Git | GitLab/GitHub | 语义层版本管理 |
| 配置解析 | Jackson (JSON) + SnakeYAML | JSON主格式 + YAML导入导出 |
| markdown解析 | Commonmark | Skill文件解析 |
| 测试框架 | JUnit 5 + Mockito | 单元测试 |
| 集成测试 | Spring Boot Test | 集成测试 |

### 6.2 后续扩展

| 组件 | 技术选型 | 阶段 |
|------|---------|------|
| 向量数据库 | Milvus/Qdrant | Phase 3+ |
| 消息队列 | RabbitMQ/Kafka | Phase 3+ |
| 监控 | Prometheus + Grafana | Phase 2 |
| 追踪 | OpenTelemetry | Phase 3 |
| 日志 | ELK Stack | Phase 3+ |

---

## 7. 分阶段实施

### Phase 1: 核心框架（当前）

**目标**: 验证核心流程，实现导师Agent引导配置

**范围**:
- ✅ Agent框架核心能力
- ✅ 导师Agent
- ✅ 数据源管理
- ✅ 基础日志与监控

**交付物**:
- 可运行的导师Agent
- 数据源配置功能
- 基本的系统监控

---

### Phase 2: 语义层生成

**目标**: 实现语义层自动生成和管理

**范围**:
- 语义层Agent
- Git集成
- 沙盒验证
- 语义层同步

**交付物**:
- 完整的语义层生成流程
- Git版本管理
- 沙盒验证机制

---

### Phase 3: 数据分析与高级特性

**目标**: 实现自然语言查询和高级功能

**范围**:
- 数据分析Agent
- 权限管理
- 高级监控（Prometheus + Grafana）
- 记忆系统（长期记忆）
- RAG增强

**交付物**:
- 完整的数据分析能力
- 企业级监控系统
- 用户行为分析

---

## 8. 质量保证

### 8.1 代码质量

- **代码覆盖率**: > 80%
- **代码审查**: 所有PR必须审查
- **静态分析**: SonarQube
- **代码规范**: Google Java Style Guide

### 8.2 文档质量

- **API文档**: OpenAPI/Swagger
- **模块文档**: 架构、接口、示例
- **用户文档**: 使用指南、FAQ
- **变更日志**: CHANGELOG.md

### 8.3 发布质量

- **版本管理**: 语义化版本（Semantic Versioning）
- **发布流程**: 测试环境 → 预发布 → 生产
- **回滚机制**: 支持快速回滚
- **监控验证**: 发布后监控关键指标

---

## 9. 成功指标

### 9.1 技术指标

| 指标 | 目标值 |
|------|--------|
| API响应时间P95 | < 1s |
| 系统可用性 | > 99.5% |
| 单元测试覆盖率 | > 80% |
| Agent错误率 | < 1% |

### 9.2 业务指标

| 指标 | 目标值 |
|------|--------|
| 新用户配置完成时间 | < 10分钟 |
| 语义层生成成功率 | > 95% |
| 查询准确率 | > 90% |
| 用户满意度 | > 85% |

---

## 10. 参考文档

| 文档 | 说明 |
|------|------|
| [Agent框架设计](./agent-framework-requirements.md) | 公共Agent框架接口与实现要求 |
| [导师Agent设计](./tutor-agent-design.md) | 导师Agent详细设计 |
| [任务编排模块](./02-modules/task-orchestration-module.md) | Phase 3+ 参考设计 |
| [记忆系统](./02-modules/memory-system.md) | Phase 3+ 参考设计 |
| [可观察性系统](./02-modules/observability-system.md) | 监控与追踪详细设计 |

---

**文档版本**: 2.0.0
**更新日期**: 2026-01-25
**作者**: Foggy Navigator Team

---

## 附录: 关键设计决策

### A1. 为什么使用JSON作为主配置格式？

**原因**:
1. **前端友好**: Web管理界面可直接使用JSON，无需格式转换
2. **数据库支持**: MySQL/PostgreSQL原生支持JSON字段类型
3. **API标准**: REST接口的标准数据格式
4. **全链路一致**: 前端表单 → API → 数据库 → 前端渲染，全程JSON
5. **辅助YAML**: 支持YAML导入导出，兼顾版本控制需求

**技术选择**:
- **主格式**: JSON（数据库存储、API交互、前端编辑）
- **辅助格式**: YAML（导入导出、版本控制、手写配置）
- **转换工具**: 提供JSON ↔ YAML双向转换

### A2. 为什么使用配置化Agent？

**原因**:
1. 降低开发成本：新增Agent无需编写代码
2. 提高灵活性：配置修改即时生效
3. 易于测试：配置驱动易于编写测试用例
4. 降低维护成本：配置比代码更易理解和维护

### A3. 为什么使用markdown定义Skill？

**原因**:
1. 人类可读：易于理解和维护
2. 版本控制：Git友好
3. 协作友好：非技术人员也能编辑
4. 双重用途：既是配置，也是文档

### A3. 为什么MVP阶段使用内存存储？

**原因**:
1. 快速验证：无需额外依赖
2. 降低复杂度：聚焦业务流程
3. 易于测试：单元测试无需数据库
4. 后续迁移成本低：接口不变
