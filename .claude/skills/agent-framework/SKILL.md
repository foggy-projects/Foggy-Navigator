---
name: agent-framework
description: Agent Framework 模块开发指导。当用户需要开发 agent-framework 的新功能、修复bug、添加接口、编写单元测试时使用。触发词：/agent-framework, /af, 提及"agent框架"、"公共框架"、"LLM适配"。
---

# Agent Framework 开发指导

为 agent-framework 公共模块的开发和维护提供规范指导。

## 模块概述

agent-framework 是配置驱动的 Agent 运行时框架，提供：
- Agent 注册与生命周期管理
- 会话管理（多用户、父子会话）
- 工具注册（HTTP/MCP）与用户凭证隔离
- Skill 解析与匹配
- Agent 间路由分派
- LLM 适配层（LangChain4j）
- Agent-前端交互协议（AIP）

## 模块结构

```
agent-framework/
├── pom.xml
└── src/
    ├── main/java/com/foggy/navigator/agent/
    │   ├── core/           # 核心：AgentRegistry, AgentConfig, AgentConfigLoader
    │   │   ├── impl/       # 实现：InMemoryAgentRegistry, DefaultAgentConfigLoader
    │   │   └── model/      # 配置模型：AgentConfig, ToolConfig, DelegationRule
    │   ├── protocol/       # AIP协议
    │   │   ├── route/      # 路由协议：RoutePayload, RouteAction
    │   │   ├── surface/    # UI协议：SurfaceUpdatePayload, UiComponent
    │   │   ├── action/     # 用户操作：UserActionRequestPayload, FormConfig
    │   │   └── state/      # 状态同步：StateSyncPayload
    │   ├── tool/           # 工具管理：ToolRegistry, CredentialStore
    │   │   └── impl/       # 实现：InMemoryToolRegistry, InMemoryCredentialStore
    │   ├── session/        # 会话管理：SessionManager, Session, Message
    │   │   └── impl/       # 实现：InMemorySessionManager
    │   ├── skill/          # 技能系统：SkillManager, SkillParser, SkillMatcher
    │   │   └── impl/       # 实现：DefaultSkillParser, KeywordSkillMatcher
    │   ├── router/         # 路由分派：SessionRouter, DelegationResult
    │   │   └── impl/       # 实现：DefaultSessionRouter
    │   └── llm/            # LLM适配：LlmAdapter, LlmRequest, LlmResponse
    │       └── impl/       # 实现：LangChain4jAdapter
    └── test/java/          # 单元测试，结构与main对应
```

## 执行流程

### 新增功能

1. 确定功能所属包（core/tool/session/skill/router/llm/protocol）
2. 如是新能力，先定义接口（放在包根目录）
3. 创建实现类（放在 impl/ 子包）
4. 编写单元测试（测试目录结构与源码对应）
5. 运行测试验证：`mvn test -pl agent-framework`

### 修改现有功能

1. 读取相关接口和实现类
2. 理解现有设计意图
3. 修改实现，保持接口兼容
4. 更新或新增测试用例
5. 运行测试验证

### 添加新协议类型

1. 在 protocol/ 对应子包创建 Payload 类
2. 在 MessageType 枚举添加新类型
3. 使用 Lombok @Data @Builder @NoArgsConstructor @AllArgsConstructor
4. 添加协议测试到 AgentMessageTest

## 代码规范

### 类设计

```java
// 接口定义（包根目录）
public interface ToolRegistry {
    void registerHttpTool(String agentId, String name, String description, HttpToolConfig config);
    List<ToolDefinition> getToolsByAgent(String agentId);
}

// 实现类（impl子包）
@Component
public class InMemoryToolRegistry implements ToolRegistry {
    private final ConcurrentHashMap<String, List<ToolEntry>> agentTools = new ConcurrentHashMap<>();
    // ...
}
```

### 模型类

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentConfig {
    private String id;
    private String name;
    private List<String> capabilities;
    // ...
}
```

### 测试类

```java
class InMemoryToolRegistryTest {
    private InMemoryToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new InMemoryToolRegistry(new InMemoryCredentialStore());
    }

    @Test
    void registerHttpTool_shouldAddTool() {
        // given
        HttpToolConfig config = HttpToolConfig.builder().url("...").build();
        // when
        registry.registerHttpTool("agent-1", "search", "Search API", config);
        // then
        assertEquals(1, registry.getToolsByAgent("agent-1").size());
    }
}
```

## 依赖说明

| 依赖 | 用途 |
|------|------|
| spring-boot-starter | Spring IoC容器 |
| jackson-databind/yaml | JSON/YAML解析 |
| langchain4j, langchain4j-open-ai | LLM调用 |
| commonmark | Skill Markdown解析 |
| lombok | 代码简化 |
| spring-boot-starter-test | 测试（JUnit5 + Mockito） |

## 约束条件

- 包名：`com.foggy.navigator.agent.{子模块}`
- 接口在包根目录，实现在 `impl/` 子包
- 模型类使用 Lombok：@Data @Builder @NoArgsConstructor @AllArgsConstructor
- 内存实现类以 `InMemory` 前缀命名
- 默认实现类以 `Default` 前缀命名
- 测试类命名：`{被测类}Test`
- 不引入 spring-boot-starter-web（作为库使用）
- LangChain4j 版本：0.35.0

## 决策规则

- 如果是新的核心能力 → 先定义接口，再提供 InMemory 实现
- 如果涉及用户隔离 → 方法参数包含 userId
- 如果涉及多租户 → 方法参数包含 tenantId
- 如果是协议类 → 放在 protocol/ 对应子包，添加到 MessageType
- 如果需要 Spring 注入 → 实现类添加 @Component
- 如果有多个实现 → 默认实现添加 @Primary
- 如果修改接口 → 检查所有实现类和测试

## 常用命令

```bash
# 编译
mvn compile -pl agent-framework

# 运行测试
mvn test -pl agent-framework

# 单个测试类
mvn test -pl agent-framework -Dtest=InMemoryToolRegistryTest

# 查看测试报告
cat agent-framework/target/surefire-reports/*.txt
```

## 相关文档

- 需求文档：`docs/agent-framework-requirements.md`
- 使用指南：`docs/agent-framework-guide.md`
- 导师Agent示例：`docs/tutor-agent-design.md`
