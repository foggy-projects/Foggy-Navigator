# Agent Framework 使用指南

> 基于 LangChain4j 的配置驱动 Agent 运行时框架

**版本**: 1.0.0
**最后更新**: 2026-01-25

---

## 1. 概述

### 1.1 什么是 Agent Framework

Agent Framework 是 Foggy Navigator 的公共 Agent 运行时框架，提供：

- **配置驱动**: 通过 JSON/YAML 配置文件定义 Agent 行为
- **Skill 系统**: 基于 Markdown 的技能定义和自动匹配
- **工具管理**: 支持 HTTP 和 MCP 工具，用户级凭证隔离
- **会话管理**: 多用户会话支持，父子会话链
- **Agent 路由**: Agent 间任务分派和上下文传递
- **交互协议**: 统一的 Agent-前端交互协议 (AIP)

### 1.2 架构图

```
┌─────────────────────────────────────────────────────────────┐
│                      业务模块层                              │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │ tutor-agent │  │ coding-agent│  │ semantic-layer-agent│  │
│  └─────────────┘  └─────────────┘  └─────────────────────┘  │
├─────────────────────────────────────────────────────────────┤
│                   Agent Framework 层                         │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────┐   │
│  │ Registry │ │ Session  │ │  Tools   │ │    Skills    │   │
│  │ Manager  │ │ Manager  │ │ Registry │ │   Manager    │   │
│  └──────────┘ └──────────┘ └──────────┘ └──────────────┘   │
│  ┌──────────┐ ┌──────────┐ ┌──────────────────────────────┐ │
│  │ Router   │ │ Protocol │ │      LLM Adapter             │ │
│  └──────────┘ └──────────┘ └──────────────────────────────┘ │
├─────────────────────────────────────────────────────────────┤
│                      LangChain4j                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. 快速开始

### 2.1 添加依赖

```xml
<dependency>
    <groupId>com.foggy.navigator</groupId>
    <artifactId>agent-framework</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2.2 创建 Agent 配置

在 `resources/agent-config/` 目录下创建配置文件：

**tutor-agent.yml**
```yaml
agent:
  id: tutor-agent
  name: 导师Agent
  type: system
  description: 引导用户完成系统初始化配置

  capabilities:
    - system-guidance
    - configuration-check

  skills:
    directory: classpath:skills/tutor
    enabled:
      - check-system-status
      - guide-datasource-config

  tools:
    - name: checkDatasourceStatus
      description: 检查数据源配置状态
      http:
        method: GET
        url: http://localhost:8080/api/config/datasource/status

  model:
    provider: openai
    model: gpt-4
    temperature: 0.7
    systemPrompt: |
      你是系统导师，负责引导用户完成配置。

  delegation:
    rules:
      - name: delegate-datasource
        trigger:
          keywords: [配置数据源, 连接数据库]
        target: datasource-agent
```

### 2.3 注册 Agent

```java
@Component
@RequiredArgsConstructor
public class TutorAgentInitializer implements CommandLineRunner {

    private final AgentRegistry agentRegistry;
    private final AgentConfigLoader configLoader;

    @Override
    public void run(String... args) {
        // 加载配置
        AgentConfig config = configLoader.load("classpath:agent-config/tutor-agent.yml");

        // 注册Agent
        agentRegistry.register(config);

        log.info("Agent已注册: {}", config.getName());
    }
}
```

---

## 3. 核心组件

### 3.1 AgentRegistry - Agent注册表

管理所有已注册的 Agent 实例。

```java
public interface AgentRegistry {
    // 注册Agent
    void register(AgentConfig config);

    // 注销Agent
    void unregister(String agentId);

    // 按ID查找
    AgentInfo findById(String agentId);

    // 按能力查找
    List<AgentInfo> findByCapability(String capability);

    // 获取所有Agent
    List<AgentInfo> findAll();

    // 检查是否存在
    boolean exists(String agentId);

    // 更新状态
    void updateStatus(String agentId, AgentStatus status);
}
```

**使用示例：**
```java
@Autowired
private AgentRegistry agentRegistry;

// 查找所有具有coding能力的Agent
List<AgentInfo> codingAgents = agentRegistry.findByCapability("coding");

// 检查Agent是否存在
if (agentRegistry.exists("tutor-agent")) {
    AgentInfo info = agentRegistry.findById("tutor-agent");
    log.info("Agent名称: {}", info.getName());
}
```

### 3.2 SessionManager - 会话管理

管理用户与 Agent 的会话。

```java
public interface SessionManager {
    // 创建会话
    String createSession(SessionCreateRequest request);

    // 获取会话
    Session getSession(String sessionId);

    // 更新状态
    void updateStatus(String sessionId, SessionStatus status);

    // 添加消息
    String addMessage(String sessionId, Message message);

    // 获取最近消息
    List<Message> getRecentMessages(String sessionId, int limit);

    // 获取所有消息
    List<Message> getAllMessages(String sessionId);

    // 关闭会话
    void closeSession(String sessionId);

    // 查找用户未完成的会话
    List<Session> findPendingByUser(String userId);
}
```

**使用示例：**
```java
@Autowired
private SessionManager sessionManager;

// 创建新会话
String sessionId = sessionManager.createSession(
    SessionCreateRequest.builder()
        .userId("user-123")
        .tenantId("tenant-1")
        .agentId("tutor-agent")
        .taskName("系统配置引导")
        .build()
);

// 添加用户消息
sessionManager.addMessage(sessionId, Message.builder()
    .role(MessageRole.USER)
    .content("我想配置数据源")
    .build());

// 获取会话历史
List<Message> history = sessionManager.getRecentMessages(sessionId, 20);
```

### 3.3 ToolRegistry - 工具注册表

管理 Agent 可用的工具，支持用户级凭证隔离。

```java
public interface ToolRegistry {
    // 注册HTTP工具
    void registerHttpTool(String agentId, String name, String description,
                          HttpToolConfig config);

    // 注册MCP工具
    void registerMcpTool(String agentId, McpToolConfig config);

    // 绑定用户凭证
    void bindUserCredential(String userId, String toolName,
                            UserToolCredential credential);

    // 解绑用户凭证
    void unbindUserCredential(String userId, String toolName);

    // 获取Agent的所有工具
    List<ToolDefinition> getToolsByAgent(String agentId);

    // 获取用户可用的工具（已授权）
    List<ToolDefinition> getAvailableTools(String agentId, String userId);

    // 执行工具
    ToolExecutionResult executeTool(ToolExecutionRequest request);
}
```

**MCP工具配置示例：**
```java
// 注册MCP工具（系统级配置，不含凭证）
McpToolConfig mcpConfig = McpToolConfig.builder()
    .name("github")
    .description("GitHub代码仓库操作")
    .mcpServerUrl("mcp://github.localhost")
    .protocol("stdio")
    .requiresAuth(true)
    .build();

toolRegistry.registerMcpTool("coding-agent", mcpConfig);

// 为用户绑定凭证（用户级，隔离存储）
UserToolCredential credential = UserToolCredential.builder()
    .userId("user-123")
    .toolName("github")
    .accessToken("ghp_xxxxxxxxxxxx")
    .expiresAt(LocalDateTime.now().plusDays(30))
    .build();

toolRegistry.bindUserCredential("user-123", "github", credential);
```

### 3.4 SkillManager - 技能管理

加载和匹配 Markdown 格式的技能定义。

```java
public interface SkillManager {
    // 加载目录下的所有技能
    void loadSkills(String directory);

    // 获取Agent的所有技能
    List<Skill> getSkillsByAgent(String agentId);

    // 匹配用户消息到技能
    Skill matchSkill(String agentId, String userMessage);

    // 获取特定技能
    Skill getSkill(String skillId);
}
```

**Skill Markdown 格式：**

```markdown
# Skill ID
check-system-status

# Skill标题
检查系统状态

# 触发条件
- 检查状态
- 查看配置
- 系统状态

# 意图
- check_status
- view_config

# 描述
检查系统配置状态并生成状态报告

# 执行逻辑
1. 调用 checkDatasourceStatus() 检查数据源
2. 调用 checkSemanticLayerStatus() 检查语义层
3. 生成友好的状态报告

# 输出格式
状态报告，包含已完成和待配置项

# 分派条件
仅提供状态信息，不进行分派
```

### 3.5 SessionRouter - 会话路由

处理 Agent 间的任务分派和会话跳转。

```java
public interface SessionRouter {
    // 分派任务给另一个Agent
    DelegationResult delegateToAgent(DelegationRequest request);

    // 返回到父会话
    DelegationResult returnToParent(String currentSessionId);

    // 检查分派的前置条件
    PreconditionCheckResult checkPreconditions(Map<String, Object> preconditions);
}
```

**分派示例：**
```java
@Autowired
private SessionRouter sessionRouter;

// 检查前置条件
PreconditionCheckResult checkResult = sessionRouter.checkPreconditions(
    Map.of("datasource_configured", true)
);

if (!checkResult.isSatisfied()) {
    // 前置条件不满足
    log.warn("前置条件不满足: {}", checkResult.getSuggestionMessage());
    return;
}

// 分派任务
DelegationResult result = sessionRouter.delegateToAgent(
    DelegationRequest.builder()
        .sourceSessionId("session-123")
        .targetAgentId("semantic-layer-agent")
        .userId("user-123")
        .tenantId("tenant-1")
        .intent("生成语义层")
        .parameters(Map.of("datasourceId", "ds-001"))
        .build()
);

if (result.isSuccess()) {
    // 获取路由协议，发送给前端
    RoutePayload route = result.getRoute();
    sendToFrontend(route);
}
```

---

## 4. Agent配置详解

### 4.1 完整配置结构

```yaml
agent:
  # 基础信息
  id: tutor-agent                      # 唯一标识
  name: 导师Agent                       # 显示名称
  type: system                          # 类型: system/business/assistant
  description: 引导用户完成系统配置      # 描述
  capabilities:                         # 能力标签（用于Agent发现）
    - system-guidance
    - configuration-check

  # 技能配置
  skills:
    directory: classpath:skills/tutor   # 技能文件目录
    enabled:                             # 启用的技能ID列表
      - check-system-status
      - guide-datasource-config

  # 工具配置
  tools:
    # HTTP工具
    - name: checkDatasourceStatus
      description: 检查数据源配置状态
      http:
        method: GET
        url: http://localhost:8080/api/config/datasource/status
        headers:
          Content-Type: application/json

    # MCP工具
    - name: github
      description: GitHub代码操作
      mcp:
        mcpServerUrl: mcp://github.localhost
        protocol: stdio
        requiresAuth: true
        capabilities:
          - read_file
          - write_file
          - create_pr

  # 模型配置
  model:
    provider: openai                    # 提供商: openai/anthropic
    model: gpt-4                        # 模型名称
    temperature: 0.7                    # 温度 0-1
    maxTokens: 4096                     # 最大token数
    systemPrompt: |                     # 系统提示词
      你是系统导师，负责引导用户完成配置。

  # 分派规则
  delegation:
    rules:
      - name: delegate-datasource-config
        trigger:
          intents:                       # 意图匹配
            - configure-datasource
          keywords:                      # 关键词匹配
            - 配置数据源
            - 连接数据库
        target: datasource-agent         # 目标Agent
        preconditions:                   # 前置条件
          logged_in: true
        contextMapping:                  # 上下文映射
          - key: dbType
            source: userInput

  # 会话恢复配置
  sessionResume:
    enabled: true
    checkOnStartup: true
    reminderTemplate: |
      您好！检测到您有未完成的任务：{taskName}
      是否继续？
```

### 4.2 工具类型对比

| 特性 | HTTP工具 | MCP工具 |
|------|----------|---------|
| 协议 | REST API | MCP协议 |
| 配置 | URL+Method+Headers | ServerUrl+Protocol |
| 认证 | Header注入 | CredentialStore管理 |
| 适用场景 | 内部API调用 | 外部服务集成 |

---

## 5. 交互协议 (AIP)

Agent Framework 定义了统一的 Agent-前端交互协议。

### 5.1 消息类型

```java
public enum MessageType {
    // 文本流
    TEXT_CHUNK,           // 文本片段（流式输出）
    TEXT_COMPLETE,        // 文本完成

    // 工具调用
    TOOL_CALL_START,      // 开始调用工具
    TOOL_CALL_RESULT,     // 工具调用结果
    TOOL_CALL_ERROR,      // 工具调用错误

    // UI渲染
    SURFACE_UPDATE,       // UI组件更新

    // 路由跳转
    ROUTE_REQUEST,        // 请求路由跳转
    ROUTE_CONFIRM,        // 确认路由

    // 用户操作
    USER_ACTION_REQUEST,  // 请求用户操作
    USER_ACTION_RESPONSE, // 用户操作响应

    // 状态同步
    STATE_SYNC,           // 状态同步

    // 系统
    ERROR,                // 错误
    HEARTBEAT             // 心跳
}
```

### 5.2 路由协议示例

当需要分派到另一个 Agent 时，发送路由协议：

```json
{
  "type": "ROUTE_REQUEST",
  "payload": {
    "action": "DELEGATE",
    "mode": "REPLACE",
    "target": {
      "agentId": "datasource-agent",
      "agentName": "数据源配置Agent",
      "sessionId": "new-session-456"
    },
    "context": {
      "summary": "用户想要配置MySQL数据源",
      "variables": {
        "dbType": "MySQL",
        "host": "localhost"
      },
      "preserveHistory": true
    },
    "callback": {
      "notifyOnComplete": true,
      "autoReturn": true
    },
    "uiHint": {
      "requireConfirmation": true,
      "confirmationMessage": "即将跳转到数据源配置，是否继续？"
    }
  },
  "sessionId": "session-123",
  "agentId": "tutor-agent",
  "timestamp": "2026-01-25T10:30:00Z"
}
```

### 5.3 用户操作请求示例

请求用户确认：

```json
{
  "type": "USER_ACTION_REQUEST",
  "payload": {
    "actionType": "CONFIRM",
    "confirmation": {
      "title": "删除数据源",
      "message": "确定要删除数据源 'sales_db' 吗？此操作不可恢复。",
      "confirmText": "删除",
      "cancelText": "取消",
      "severity": "danger"
    }
  }
}
```

请求用户填写表单：

```json
{
  "type": "USER_ACTION_REQUEST",
  "payload": {
    "actionType": "FORM_SUBMIT",
    "form": {
      "title": "数据库连接信息",
      "fields": [
        {"name": "host", "type": "text", "label": "主机地址", "required": true},
        {"name": "port", "type": "number", "label": "端口", "defaultValue": "3306"},
        {"name": "database", "type": "text", "label": "数据库名", "required": true}
      ],
      "submitText": "连接",
      "cancelText": "取消"
    }
  }
}
```

---

## 6. 与导师Agent集成示例

以下是 `tutor-agent` 模块如何使用 Agent Framework 的完整示例。

### 6.1 模块结构

```
tutor-agent/
├── src/main/java/com/foggy/navigator/tutor/
│   ├── TutorAgentInitializer.java      # 初始化器
│   ├── controller/
│   │   └── SystemConfigController.java # 工具接口
│   └── service/
│       └── TutorAgentService.java      # 业务逻辑
├── src/main/resources/
│   ├── agent-config/
│   │   └── tutor-agent.yml             # Agent配置
│   └── skills/tutor/
│       ├── check-system-status.md
│       └── guide-datasource-config.md
└── pom.xml
```

### 6.2 初始化器

```java
package com.foggy.navigator.tutor;

import com.foggy.navigator.agent.core.*;
import com.foggy.navigator.agent.core.model.AgentConfig;
import com.foggy.navigator.agent.skill.SkillManager;
import com.foggy.navigator.agent.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TutorAgentInitializer implements CommandLineRunner {

    private final AgentRegistry agentRegistry;
    private final AgentConfigLoader configLoader;
    private final SkillManager skillManager;
    private final ToolRegistry toolRegistry;

    @Override
    public void run(String... args) {
        log.info("初始化导师Agent...");

        // 1. 加载Agent配置
        AgentConfig config = configLoader.load("classpath:agent-config/tutor-agent.yml");

        // 2. 注册Agent
        agentRegistry.register(config);

        // 3. 加载Skills
        skillManager.loadSkills(config.getSkills().getDirectory());

        // 4. 注册工具
        config.getTools().forEach(tool -> {
            if (tool.getHttp() != null) {
                toolRegistry.registerHttpTool(
                    config.getId(),
                    tool.getName(),
                    tool.getDescription(),
                    tool.getHttp()
                );
            }
        });

        log.info("导师Agent初始化完成");
        log.info("  - ID: {}", config.getId());
        log.info("  - Skills: {}", config.getSkills().getEnabled().size());
        log.info("  - Tools: {}", config.getTools().size());
    }
}
```

### 6.3 处理用户消息

```java
package com.foggy.navigator.tutor.service;

import com.foggy.navigator.agent.core.*;
import com.foggy.navigator.agent.llm.*;
import com.foggy.navigator.agent.protocol.*;
import com.foggy.navigator.agent.router.*;
import com.foggy.navigator.agent.session.*;
import com.foggy.navigator.agent.skill.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TutorAgentService {

    private final SessionManager sessionManager;
    private final SkillManager skillManager;
    private final SessionRouter sessionRouter;
    private final LlmAdapter llmAdapter;

    public AgentMessage handleUserMessage(String sessionId, String userMessage) {
        Session session = sessionManager.getSession(sessionId);
        String agentId = session.getAgentId();

        // 1. 记录用户消息
        sessionManager.addMessage(sessionId, Message.builder()
            .role(MessageRole.USER)
            .content(userMessage)
            .build());

        // 2. 匹配Skill
        Skill matchedSkill = skillManager.matchSkill(agentId, userMessage);

        // 3. 构建LLM请求
        LlmRequest request = buildLlmRequest(session, userMessage, matchedSkill);

        // 4. 调用LLM
        LlmResponse response = llmAdapter.chat(request);

        // 5. 记录助手消息
        sessionManager.addMessage(sessionId, Message.builder()
            .role(MessageRole.ASSISTANT)
            .content(response.getContent())
            .build());

        // 6. 检查是否需要分派
        if (shouldDelegate(response, matchedSkill)) {
            return handleDelegation(session, matchedSkill);
        }

        return AgentMessage.builder()
            .type(MessageType.TEXT_COMPLETE)
            .payload(response.getContent())
            .sessionId(sessionId)
            .agentId(agentId)
            .build();
    }

    private AgentMessage handleDelegation(Session session, Skill skill) {
        DelegationResult result = sessionRouter.delegateToAgent(
            DelegationRequest.builder()
                .sourceSessionId(session.getId())
                .targetAgentId(skill.getDelegationCondition())
                .userId(session.getUserId())
                .tenantId(session.getTenantId())
                .intent(skill.getName())
                .build()
        );

        if (result.isSuccess()) {
            return AgentMessage.builder()
                .type(MessageType.ROUTE_REQUEST)
                .payload(result.getRoute())
                .sessionId(session.getId())
                .build();
        }

        return AgentMessage.builder()
            .type(MessageType.ERROR)
            .payload(result.getErrorMessage())
            .build();
    }
}
```

---

## 7. 凭证管理

### 7.1 设计原则

- **共享Agent + 用户级凭证隔离**: Agent实例共享，凭证按用户隔离
- **凭证不存储在Agent配置中**: 通过 CredentialStore 独立管理
- **运行时动态注入**: 执行工具时按 userId 查找凭证

### 7.2 CredentialStore 接口

```java
public interface CredentialStore {
    // 保存凭证
    void save(UserToolCredential credential);

    // 查找凭证
    UserToolCredential find(String userId, String toolName);

    // 查找用户所有凭证
    List<UserToolCredential> findByUser(String userId);

    // 删除凭证
    void delete(String userId, String toolName);

    // 检查凭证是否有效
    boolean isValid(String userId, String toolName);

    // 刷新凭证
    UserToolCredential refresh(String userId, String toolName);
}
```

### 7.3 凭证绑定流程

```java
// 用户授权GitHub后，保存凭证
@PostMapping("/oauth/github/callback")
public void handleGitHubCallback(@RequestParam String code,
                                  @AuthenticationPrincipal User user) {
    // 1. 用code换取token
    GitHubToken token = gitHubService.exchangeToken(code);

    // 2. 创建凭证
    UserToolCredential credential = UserToolCredential.builder()
        .userId(user.getId())
        .tenantId(user.getTenantId())
        .toolName("github")
        .accessToken(token.getAccessToken())
        .refreshToken(token.getRefreshToken())
        .expiresAt(token.getExpiresAt())
        .build();

    // 3. 绑定到工具注册表
    toolRegistry.bindUserCredential(user.getId(), "github", credential);
}
```

---

## 8. 最佳实践

### 8.1 Agent 设计原则

1. **单一职责**: 每个Agent专注一个领域（导师、数据源、语义层）
2. **配置驱动**: 行为通过配置定义，避免硬编码
3. **Skill组合**: 复杂流程通过多个Skill组合实现
4. **工具辅助**: AI通过工具获取系统状态，而非硬编码逻辑

### 8.2 Skill 编写建议

1. **清晰的触发条件**: 关键词和意图要明确
2. **分步执行逻辑**: 列出清晰的步骤
3. **示例对话**: 提供示例帮助AI理解
4. **分派条件**: 明确何时分派给其他Agent

### 8.3 安全考虑

1. **凭证隔离**: 使用 CredentialStore 隔离用户凭证
2. **工具白名单**: 只注册必要的工具
3. **前置条件检查**: 分派前检查权限和条件
4. **敏感信息加密**: 生产环境使用加密存储

---

## 9. API 参考

### 9.1 核心接口

| 接口 | 说明 | 实现类 |
|------|------|--------|
| `AgentRegistry` | Agent注册表 | `InMemoryAgentRegistry` |
| `AgentConfigLoader` | 配置加载器 | `DefaultAgentConfigLoader` |
| `SessionManager` | 会话管理 | `InMemorySessionManager` |
| `ToolRegistry` | 工具注册表 | `InMemoryToolRegistry` |
| `CredentialStore` | 凭证存储 | `InMemoryCredentialStore` |
| `SkillManager` | 技能管理 | `DefaultSkillManager` |
| `SkillParser` | Skill解析 | `DefaultSkillParser` |
| `SkillMatcher` | Skill匹配 | `KeywordSkillMatcher` |
| `SessionRouter` | 会话路由 | `DefaultSessionRouter` |
| `LlmAdapter` | LLM适配器 | `LangChain4jAdapter` |

### 9.2 模型类

| 类 | 说明 |
|----|------|
| `AgentConfig` | Agent配置模型 |
| `AgentInfo` | Agent运行时信息 |
| `Session` | 会话模型 |
| `Message` | 消息模型 |
| `Skill` | 技能模型 |
| `ToolDefinition` | 工具定义 |
| `UserToolCredential` | 用户凭证 |

### 9.3 协议类

| 类 | 说明 |
|----|------|
| `AgentMessage` | 协议消息 |
| `RoutePayload` | 路由协议 |
| `SurfaceUpdatePayload` | UI更新协议 |
| `UserActionRequestPayload` | 用户操作请求 |
| `StateSyncPayload` | 状态同步协议 |

---

## 10. 常见问题

### Q: 如何替换内存实现为持久化存储？

实现对应接口并注册为Spring Bean，框架会自动使用：

```java
@Component
@Primary  // 优先使用此实现
public class JpaCredentialStore implements CredentialStore {
    // 实现接口方法，使用JPA持久化
}
```

### Q: 如何添加新的LLM提供商？

实现 `LlmAdapter` 接口：

```java
@Component
public class AnthropicAdapter implements LlmAdapter {
    @Override
    public LlmResponse chat(LlmRequest request) { ... }

    @Override
    public boolean supportsModel(String model) {
        return model.startsWith("claude-");
    }
}
```

### Q: 如何自定义Skill匹配逻辑？

实现 `SkillMatcher` 接口：

```java
@Component
@Primary
public class SemanticSkillMatcher implements SkillMatcher {
    // 使用语义相似度匹配
}
```

---

**文档版本**: 1.0.0
**创建日期**: 2026-01-25
**维护团队**: Foggy Navigator Team
