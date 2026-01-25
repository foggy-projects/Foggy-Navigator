# 公共Agent框架设计需求文档

> 支持配置化Agent的通用框架设计

## 1. 框架概述

### 1.1 定位

公共Agent框架是一个**通用的、配置驱动的Agent运行时框架**，为各类Agent（导师Agent、语义层Agent、编程Agent等）提供统一的运行环境和基础能力。

### 1.2 核心目标

- **配置化**: Agent通过JSON配置定义（支持YAML导入导出），而非硬编码
- **可扩展**: 支持自定义Skill、Tool、分派规则
- **标准化**: 统一的数据模型、接口规范、通信协议
- **高性能**: 支持并发会话、流式响应
- **可观察**: 完整的日志、监控、追踪

### 1.3 设计原则

- **关注点分离**: 框架负责通用能力，具体业务逻辑在Skills中实现
- **约定优于配置**: 提供合理的默认值，减少配置复杂度
- **渐进式增强**: MVP阶段实现核心功能，后续逐步扩展

---

## 2. 框架核心能力

```
┌────────────────────────────────────────────────────────────────┐
│                    Agent框架核心能力                            │
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. Agent生命周期管理                                           │
│     - 配置加载与解析                                            │
│     - Agent实例化                                               │
│     - Agent注册与发现                                           │
│     - Agent启动与停止                                           │
│                                                                 │
│  2. Skill管理系统                                               │
│     - Skill加载（从markdown）                                   │
│     - Skill解析与验证                                           │
│     - Skill匹配引擎（意图 → Skill）                             │
│     - Skill执行引擎                                             │
│                                                                 │
│  3. 工具（Tool）管理                                            │
│     - HTTP工具注册                                              │
│     - 工具调用执行                                              │
│     - 工具响应处理                                              │
│                                                                 │
│  4. 会话与上下文管理                                            │
│     - 会话创建与恢复                                            │
│     - 消息存储与检索                                            │
│     - 上下文注入                                                │
│     - 会话路由（Agent间跳转）                                   │
│                                                                 │
│  5. LLM交互层                                                   │
│     - LangChain4j集成                                           │
│     - Prompt构建                                                │
│     - 流式响应支持                                              │
│     - Token计数                                                 │
│                                                                 │
│  6. 分派与编排                                                  │
│     - 分派规则引擎                                              │
│     - 前置条件检查                                              │
│     - 上下文传递                                                │
│     - 会话跳转                                                  │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
```

---

## 3. 核心接口定义

### 3.1 Agent配置加载器

```java
package com.foggy.navigator.agent.core;

/**
 * Agent配置加载器
 * 主要格式：JSON（用于数据库存储、API交互、前端编辑）
 * 辅助格式：YAML（用于导入导出、版本控制）
 */
public interface AgentConfigLoader {

    /**
     * 从JSON字符串加载Agent配置（主要方法）
     * @param json JSON格式的配置内容
     * @return Agent配置对���
     * @throws AgentConfigException 配置解析失败
     */
    AgentConfig loadFromJson(String json) throws AgentConfigException;

    /**
     * 从YAML字符串加载Agent配置（辅助方法，用于导入）
     * @param yaml YAML格式的配置内容
     * @return Agent配置对象
     * @throws AgentConfigException 配置解析失败
     */
    AgentConfig loadFromYaml(String yaml) throws AgentConfigException;

    /**
     * 从指定路径加载Agent配置（自动检测格式）
     * @param path 配置文件路径（.json 或 .yml/.yaml）
     * @return Agent配置对象
     * @throws AgentConfigException 配置加载失败
     */
    AgentConfig load(String path) throws AgentConfigException;

    /**
     * 验证配置的有效性
     * @param config Agent配置
     * @return 验证结果
     */
    ValidationResult validate(AgentConfig config);
}
```

### 3.2 Agent注册表

```java
package com.foggy.navigator.agent.core;

/**
 * Agent注册表
 * 管理所有已注册的Agent
 */
public interface AgentRegistry {

    /**
     * 注册Agent
     * @param config Agent配置
     */
    void register(AgentConfig config);

    /**
     * 注销Agent
     * @param agentId Agent ID
     */
    void unregister(String agentId);

    /**
     * 根据ID查找Agent
     * @param agentId Agent ID
     * @return Agent信息，不存在返回null
     */
    AgentInfo findById(String agentId);

    /**
     * 根据能力查找Agent
     * @param capability 能力标识
     * @return 具备该能力的Agent列表
     */
    List<AgentInfo> findByCapability(String capability);

    /**
     * 获取所有已注册的Agent
     * @return Agent列表
     */
    List<AgentInfo> findAll();

    /**
     * 检查Agent是否已注册
     * @param agentId Agent ID
     * @return 是否已注册
     */
    boolean exists(String agentId);
}
```

### 3.3 Skill管理器

```java
package com.foggy.navigator.agent.skill;

/**
 * Skill管理器
 */
public interface SkillManager {

    /**
     * 从目录加载Skills
     * @param directory Skill文件目录路径
     */
    void loadSkills(String directory);

    /**
     * 注册单个Skill
     * @param skill Skill对象
     */
    void registerSkill(Skill skill);

    /**
     * 根据意图匹配Skill
     * @param intent 用户意图
     * @param keywords 关键词列表
     * @param agentId 所属Agent ID
     * @return 匹配的Skill，无匹配返回null
     */
    Skill matchSkill(String intent, List<String> keywords, String agentId);

    /**
     * 执行Skill
     * @param skill Skill对象
     * @param context 执行上下文
     * @return 执行结果
     */
    SkillExecutionResult executeSkill(Skill skill, SkillExecutionContext context);

    /**
     * 获取Agent的所有Skill
     * @param agentId Agent ID
     * @return Skill列表
     */
    List<Skill> getSkillsByAgent(String agentId);
}
```

### 3.4 工具注册表

```java
package com.foggy.navigator.agent.tool;

/**
 * 工具注册表
 */
public interface ToolRegistry {

    /**
     * 注册HTTP工具
     * @param toolConfig 工具配置
     */
    void registerHttpTool(HttpToolConfig toolConfig);

    /**
     * 获取Agent的所有工具
     * @param agentId Agent ID
     * @return 工具列表
     */
    List<ToolDefinition> getToolsByAgent(String agentId);

    /**
     * 调用工具
     * @param toolName 工具名称
     * @param parameters 参数
     * @return 工具执行结果
     */
    ToolExecutionResult executeTool(String toolName, Map<String, Object> parameters);
}
```

### 3.5 会话管理器

```java
package com.foggy.navigator.agent.session;

/**
 * 会话管理器
 */
public interface SessionManager {

    /**
     * 创建新会话
     * @param request 会话创建请求
     * @return 会话ID
     */
    String createSession(SessionCreateRequest request);

    /**
     * 获取会话
     * @param sessionId 会话ID
     * @return 会话对象
     */
    Session getSession(String sessionId);

    /**
     * 发送消息
     * @param sessionId 会话ID
     * @param message 消息内容
     * @return 消息ID
     */
    String sendMessage(String sessionId, String message);

    /**
     * 获取会话的最近N条消息
     * @param sessionId 会话ID
     * @param limit 数量限制
     * @return 消息列表
     */
    List<Message> getRecentMessages(String sessionId, int limit);

    /**
     * 结束会话
     * @param sessionId 会话ID
     */
    void closeSession(String sessionId);

    /**
     * 查找用户的待办会话
     * @param userId 用户ID
     * @return 待办会话列表
     */
    List<Session> findPendingByUser(String userId);
}
```

### 3.6 会话路由器

```java
package com.foggy.navigator.agent.router;

/**
 * 会话路由器
 * 负责Agent间的会话跳转和上下文传递
 */
public interface SessionRouter {

    /**
     * 分派任务给另一个Agent
     * @param request 分派请求
     * @return 分派结果（包含新会话ID、跳转URL等）
     */
    DelegationResult delegateToAgent(DelegationRequest request);

    /**
     * 恢复到父会话
     * @param currentSessionId 当前会话ID
     * @return 父会话ID
     */
    String returnToParent(String currentSessionId);

    /**
     * 检查分派的前置条件
     * @param preconditions 前置条件
     * @return 是否满足
     */
    boolean checkPreconditions(Map<String, Object> preconditions);
}
```

### 3.7 配置服务（业务层提供）

```java
package com.foggy.navigator.config;

/**
 * 配置服务
 * 由业务层实现，供Agent查询系统配置状态
 */
public interface ConfigurationService {

    /**
     * 获取数据源配置状态
     */
    ConfigStatus getDataSourceStatus();

    /**
     * 获取语义层配置状态
     */
    ConfigStatus getSemanticLayerStatus();

    /**
     * 获取整体配置进度
     */
    ConfigProgress getOverallProgress();
}
```

---

## 4. 数据模型定义

### 4.1 Agent配置模型

```java
package com.foggy.navigator.agent.model;

import lombok.Data;

/**
 * Agent配置
 */
@Data
public class AgentConfig {

    // 基本信息
    private String id;              // Agent唯一标识
    private String name;            // Agent名称
    private String type;            // Agent类型: system / user
    private String description;     // Agent描述

    // 能力声明
    private List<String> capabilities;

    // Skills配置
    private SkillsConfig skills;

    // 工具配置
    private List<ToolConfig> tools;

    // 模型配置
    private ModelConfig model;

    // 分派规则
    private DelegationConfig delegation;

    // 会话恢复配置
    private SessionResumeConfig sessionResume;

    // 扩展配置（供特定Agent使用）
    private Map<String, Object> extensions;
}

@Data
public class SkillsConfig {
    private String directory;           // Skill文件目录
    private List<String> enabled;       // 启用的Skill ID列表
}

@Data
public class ToolConfig {
    private String name;                // 工具名称
    private String description;         // 工具描述
    private HttpToolConfig http;        // HTTP工具配置
}

@Data
public class HttpToolConfig {
    private String method;              // HTTP方法: GET/POST
    private String url;                 // 请求URL
    private Map<String, String> headers; // 请求头
}

@Data
public class ModelConfig {
    private String provider;            // 模型提供商: openai/anthropic
    private String model;               // 模型名称: gpt-4
    private double temperature;         // 温度参数
    private String systemPrompt;        // 系统提示词
}

@Data
public class DelegationConfig {
    private List<DelegationRule> rules; // 分派规则列表
}

@Data
public class SessionResumeConfig {
    private boolean enabled;            // 是否启用会话恢复提醒
    private boolean checkOnStartup;     // 启动时是否检查
    private String reminderTemplate;    // 提醒模板
}
```

### 4.2 分派规则模型

```java
package com.foggy.navigator.agent.model;

import lombok.Data;

@Data
public class DelegationRule {

    private String name;                        // 规则名称
    private TriggerConfig trigger;              // 触发条件
    private String target;                      // 目标Agent ID
    private Map<String, Object> preconditions;  // 前置条件
    private List<ContextMapping> contextMapping; // 上下文映射
}

@Data
public class TriggerConfig {
    private List<String> intents;               // 意图列表
    private List<String> keywords;              // 关键词列表
}

@Data
public class ContextMapping {
    private String key;                         // 参数键
    private String source;                      // 来源: userInput / systemConfig / sessionContext
    private String sourceKey;                   // 来源中的字段名（可选）
}
```

### 4.3 Skill模型

```java
package com.foggy.navigator.agent.skill;

import lombok.Data;

/**
 * Skill定义
 */
@Data
public class Skill {

    private String id;                      // Skill ID
    private String name;                    // Skill名称
    private String agentId;                 // 所属Agent

    // 触发条件
    private List<String> triggerKeywords;   // 触发关键词
    private List<String> intents;           // 适用的意图

    // Skill内容（从markdown解析）
    private String description;             // 描述
    private String executionLogic;          // 执行逻辑
    private String outputFormat;            // 输出格式
    private String delegationCondition;     // 分派条件

    // 元数据
    private String markdownPath;            // 原始markdown文件路径
    private LocalDateTime loadedAt;         // 加载时间
}
```

### 4.4 会话模型

```java
package com.foggy.navigator.agent.session;

import lombok.Data;

/**
 * 会话
 */
@Data
public class Session {

    private String id;                  // 会话ID
    private String userId;              // 用户ID
    private String agentId;             // 当前Agent ID
    private String parentSessionId;     // 父会话ID（分派时使用）

    private SessionStatus status;       // 会话状态
    private String taskName;            // 任务名称

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

public enum SessionStatus {
    ACTIVE,     // 活跃
    PAUSED,     // 暂停
    COMPLETED,  // 已完成
    DELEGATED   // 已分派
}
```

### 4.5 消息模型

```java
package com.foggy.navigator.agent.session;

import lombok.Data;

/**
 * 消息
 */
@Data
public class Message {

    private String id;                  // 消息ID
    private String sessionId;           // 会话ID
    private MessageRole role;           // 角色
    private String content;             // 内容

    private Map<String, Object> metadata; // 元数据
    private LocalDateTime createdAt;
}

public enum MessageRole {
    USER,       // 用户消息
    ASSISTANT,  // AI助手消息
    SYSTEM,     // 系统消息
    TOOL        // 工具调用结果
}
```

### 4.6 分派模型

```java
package com.foggy.navigator.agent.router;

import lombok.Data;

/**
 * 分派请求
 */
@Data
public class DelegationRequest {

    private String sourceSessionId;         // 来源会话ID
    private String sourceAgentId;           // 来源Agent
    private String targetAgentId;           // 目标Agent

    private String intent;                  // 用户意图
    private List<Message> contextMessages;  // 上下文消息
    private Map<String, Object> parameters; // 附加参数
}

/**
 * 分派结果
 */
@Data
public class DelegationResult {

    private boolean success;                // 是否成功
    private String newSessionId;            // 新会话ID
    private String redirectUrl;             // 跳转URL（供前端使用）
    private String errorMessage;            // 错误信息
}
```

### 4.7 配置状态模型（业务层定义）

```java
package com.foggy.navigator.config;

import lombok.Data;

/**
 * 配置状态
 */
@Data
public class ConfigStatus {

    private boolean configured;             // 是否已配置
    private String statusMessage;           // 状态描述
    private Map<String, Object> details;    // 详细信息
}

/**
 * 配置进度
 */
@Data
public class ConfigProgress {

    private int totalSteps;                 // 总步骤数
    private int completedSteps;             // 已完成步骤数
    private String currentStep;             // 当前步骤
    private List<String> pendingSteps;      // 待办步骤
}
```

---

## 5. Skill管理系统设计

### 5.1 Skill文件格式规范

Skills使用markdown格式定义，标准结构：

```markdown
# Skill标题

## Skill ID
skill-unique-id

## 触发条件
- 意图列表
- 关键词列表

## 前置检查（可选）
需要满足的前置条件

## 执行逻辑
### 步骤1: 描述
### 步骤2: 描述

## 决策
何时自己处理，何时分派

## 分派条件（可选）
触发分派的条件

## 上下文传递（可选）
分派时传递的参数

## 输出格式（可选）
期望的输出格式示例
```

### 5.2 Skill解析器

```java
package com.foggy.navigator.agent.skill;

/**
 * Skill解析器
 * 从markdown文件解析Skill定义
 */
public interface SkillParser {

    /**
     * 解析Skill文件
     * @param markdownContent markdown内容
     * @return Skill对象
     */
    Skill parse(String markdownContent);

    /**
     * 从文件解析
     * @param filePath 文件路径
     * @return Skill对象
     */
    Skill parseFile(String filePath);
}
```

### 5.3 Skill匹配引擎

```java
package com.foggy.navigator.agent.skill;

/**
 * Skill匹配引擎
 * 根据用户消息匹配合适的Skill
 */
public interface SkillMatcher {

    /**
     * 匹配Skill
     * @param userMessage 用户消息
     * @param availableSkills 可用的Skill列表
     * @return 匹配的Skill，无匹配返回null
     */
    Skill match(String userMessage, List<Skill> availableSkills);

    /**
     * 计算匹配得分
     * @param userMessage 用户消息
     * @param skill Skill
     * @return 匹配得分（0-1）
     */
    double calculateScore(String userMessage, Skill skill);
}
```

### 5.4 Skill执行引擎

```java
package com.foggy.navigator.agent.skill;

/**
 * Skill执行引擎
 */
public interface SkillExecutor {

    /**
     * 执行Skill
     * @param skill Skill对象
     * @param context 执行上下文
     * @return 执行结果
     */
    SkillExecutionResult execute(Skill skill, SkillExecutionContext context);
}

/**
 * Skill执行上下文
 */
@Data
public class SkillExecutionContext {

    private String sessionId;               // 会话ID
    private String userId;                  // 用户ID
    private String userMessage;             // 用户消息
    private List<Message> recentMessages;   // 最近消息
    private Map<String, Object> variables;  // 变量
}

/**
 * Skill执行结果
 */
@Data
public class SkillExecutionResult {

    private boolean success;                // 是否成功
    private String response;                // AI响应
    private boolean shouldDelegate;         // 是否应该分派
    private String targetAgentId;           // 目标Agent（如果分派）
    private Map<String, Object> contextData; // 上下文数据
    private String errorMessage;            // 错误信息
}
```

---

## 6. Agent配置规范

### 6.1 配置文件结构

```yaml
agent:
  # ===== 必填字段 =====
  id: unique-agent-id               # Agent唯一标识
  name: Agent名称                   # 显示名称
  type: system                      # system / user
  description: Agent描述            # 功能说明

  # ===== 能力声明 =====
  capabilities:                     # Agent具备的能力（用于发现）
    - capability-1
    - capability-2

  # ===== Skills配置 =====
  skills:
    directory: classpath:skills/xxx # Skill文件目录
    enabled:                         # 启用的Skill列表
      - skill-id-1
      - skill-id-2

  # ===== 工具配置 =====
  tools:
    - name: toolName                 # 工具名称
      description: 工具描述          # 功能说明
      http:                          # HTTP工具配置
        method: GET                  # HTTP方法
        url: http://...              # 请求URL
        headers:                     # 请求头（可选）
          Authorization: Bearer xxx

  # ===== 模型配置 =====
  model:
    provider: openai                 # 模型提供商
    model: gpt-4                     # 模型名称
    temperature: 0.7                 # 温度参数
    systemPrompt: |                  # 系统提示词
      你是一个...

  # ===== 分派规则（可选） =====
  delegation:
    rules:
      - name: rule-name              # 规则名称
        trigger:
          intents:                   # 意图列表
            - intent-1
          keywords:                  # 关键词列表
            - keyword-1
        target: target-agent-id      # 目标Agent
        preconditions:               # 前置条件
          config_key: true
        contextMapping:              # 上下文映射
          - key: paramName
            source: userInput

  # ===== 会话恢复（可选） =====
  sessionResume:
    enabled: true                    # 是否启用
    checkOnStartup: true             # 启动时检查
    reminderTemplate: |              # 提醒模板
      您有未完成的任务...

  # ===== 扩展配置（可选） =====
  extensions:
    customKey: customValue           # 自定义配置
```

### 6.2 配置验证规则

框架应提供配置验证功能：
- ID唯一性检查
- 必填字段检查
- Skill目录存在性检查
- 工具URL格式检查
- 分派规则有效性检查

---

## 7. 分派与路由机制

### 7.1 分派流程

```
用户消息
    │
    ▼
当前Agent接收消息
    │
    ▼
Skill匹配与执行
    │
    ▼
是否满足分派条件？
    │
    ├─ 是 ──▶ 检查前置条件
    │         │
    │         ├─ 满足 ──▶ 创建新会话
    │         │           │
    │         │           ▼
    │         │         传递上下文
    │         │           │
    │         │           ▼
    │         │         返回跳转URL
    │         │
    │         └─ 不满足 ──▶ 提示用户先完成前置任务
    │
    └─ 否 ──▶ 当前Agent继续处理
```

### 7.2 前置条件检查器

```java
package com.foggy.navigator.agent.router;

/**
 * 前置条件检查器
 */
public interface PreconditionChecker {

    /**
     * 检查前置条件
     * @param preconditions 前置条件Map
     * @return 检查结果
     */
    PreconditionCheckResult check(Map<String, Object> preconditions);
}

@Data
public class PreconditionCheckResult {
    private boolean satisfied;              // 是否满足
    private List<String> missedConditions;  // 未满足的条件
    private String suggestionMessage;       // 建议消息
}
```

### 7.3 上下文构建器

```java
package com.foggy.navigator.agent.router;

/**
 * 上下文构建器
 * 根据contextMapping规则构建分派上下文
 */
public interface ContextBuilder {

    /**
     * 构建分派上下文
     * @param mappings 映射规则
     * @param sources 数据源
     * @return 构建的上下文参数
     */
    Map<String, Object> buildContext(
        List<ContextMapping> mappings,
        ContextSources sources
    );
}

@Data
public class ContextSources {
    private String userInput;               // 用户输入
    private Map<String, Object> systemConfig; // 系统配置
    private Map<String, Object> sessionContext; // 会话上下文
}
```

---

## 8. LLM交互层设计

### 8.1 Prompt构建器

```java
package com.foggy.navigator.agent.llm;

/**
 * Prompt构建器
 */
public interface PromptBuilder {

    /**
     * 构建完整的Prompt
     * @param systemPrompt 系统提示词
     * @param skill 当前Skill
     * @param recentMessages 最近消息
     * @param userMessage 用户消息
     * @return 构建的Prompt
     */
    String buildPrompt(
        String systemPrompt,
        Skill skill,
        List<Message> recentMessages,
        String userMessage
    );
}
```

### 8.2 LLM调用器

```java
package com.foggy.navigator.agent.llm;

/**
 * LLM调用器
 */
public interface LLMInvoker {

    /**
     * 调用LLM生成响应
     * @param request 请求
     * @return 响应
     */
    LLMResponse invoke(LLMRequest request);

    /**
     * 流式调用LLM
     * @param request 请求
     * @param handler 流式处理器
     */
    void invokeStream(LLMRequest request, StreamHandler handler);
}

@Data
public class LLMRequest {
    private String model;                   // 模型名称
    private double temperature;             // 温度
    private String prompt;                  // Prompt
    private List<ToolDefinition> tools;     // 可用工具
}

@Data
public class LLMResponse {
    private String content;                 // 响应内容
    private List<ToolCall> toolCalls;       // 工具调用
    private int tokenCount;                 // Token数量
}
```

---

## 9. MVP实现范围

### 9.1 Phase 1: 核心框架（优先级最高）

**必须实现**：
- [x] AgentConfigLoader - YAML配置加载
- [x] AgentRegistry - Agent注册与发现
- [x] SessionManager - 会话管理（基于内存实现）
- [x] Message存储 - 消息存储与检索（基于内存实现）
- [x] ToolRegistry - HTTP工具注册与调用
- [x] LLMInvoker - LangChain4j集成

**简化实现**：
- 会话存储：使用内存Map，无需持久化
- 消息存储：使用内存List，无需数据库
- Agent发现：简单的Map查找，无需复杂索引

### 9.2 Phase 2: Skill系统

**必须实现**：
- [x] SkillParser - markdown解析
- [x] SkillManager - Skill加载与管理
- [x] SkillMatcher - 基于关键词的简单匹配
- [x] SkillExecutor - Skill执行引擎

**延后实现**：
- 复杂的意图识别（Phase 3）
- Skill版本管理（Phase 3）

### 9.3 Phase 3: 分派与路由

**必须实现**：
- [x] SessionRouter - 会话跳转
- [x] PreconditionChecker - 前置条件检查
- [x] ContextBuilder - 上下文构建

**简化实现**：
- 前置条件：只支持简单的布尔检查
- 上下文映射：只支持基本的字段映射

---

## 10. 实现建议

### 10.1 技术选型

| 组件 | 推荐方案 | 说明 |
|------|---------|------|
| 配置解析 | SnakeYAML | 轻量级YAML解析 |
| LLM集成 | LangChain4j | 已有技术栈 |
| markdown解析 | Commonmark | 标准markdown解析库 |
| HTTP客户端 | RestTemplate | Spring自带 |
| 会话存储（MVP） | ConcurrentHashMap | 内存存储 |
| 会话存储（生产） | Redis | 后续迁移 |

### 10.2 模块划分

```
agent-framework-core/
├── agent-config/          # Agent配置管理
├── agent-registry/        # Agent注册表
├── skill-management/      # Skill管理
├── tool-management/       # 工具管理
├── session-management/    # 会话管理
├── routing/               # 路由与分派
├── llm-integration/       # LLM集成
└── common/                # 公共模型与工具
```

### 10.3 测试策略

| 测试类型 | 覆盖范围 |
|---------|---------|
| 单元测试 | 每个核心类 > 80%覆盖率 |
| 集成测试 | 完整的Agent交互流程 |
| 配置测试 | 各种配置场景的加载与验证 |

---

## 11. 接口清单总结

### 11.1 核心接口（必须实现）

| 接口 | 优先级 | 说明 |
|------|--------|------|
| AgentConfigLoader | P0 | 配置加载 |
| AgentRegistry | P0 | Agent注册 |
| SessionManager | P0 | 会话管理 |
| ToolRegistry | P0 | 工具管理 |
| LLMInvoker | P0 | LLM调用 |
| SkillManager | P1 | Skill管理 |
| SessionRouter | P1 | 会话路由 |

### 11.2 业务接口（业务层提供）

| 接口 | 提供方 | 说明 |
|------|--------|------|
| ConfigurationService | 配置模块 | 系统配置状态查询 |

---

## 12. 配置示例

### 12.1 最小配置示例（JSON格式）

```json
{
  "agent": {
    "id": "demo-agent",
    "name": "演示Agent",
    "type": "system",
    "description": "最简单的Agent示例",
    "capabilities": ["demo"],
    "model": {
      "provider": "openai",
      "model": "gpt-4",
      "temperature": 0.7,
      "systemPrompt": "你是一个演示Agent"
    }
  }
}
```

**对应YAML格式（用于导出）**:
```yaml
agent:
  id: demo-agent
  name: 演示Agent
  type: system
  description: 最简单的Agent示例
  capabilities:
    - demo
  model:
    provider: openai
    model: gpt-4
    temperature: 0.7
    systemPrompt: "你是一个演示Agent"
```

### 12.2 完整配置示例

参见导师Agent的配置文件（tutor-agent.json / tutor-agent.yml）

---

## 13. 后续扩展方向

### 13.1 Phase 4+

- [ ] 持久化存储（MySQL）
- [ ] 缓存层（Redis）
- [ ] 高级意图识别（基于LLM）
- [ ] Agent间协作协议（简化版A2A）
- [ ] Skill版本管理
- [ ] 可视化配置界面
- [ ] 监控与可观察性

---

## 14. FAQ

### Q1: 为什么使用markdown定义Skill？

**A**: markdown格式：
- 人类可读性强
- 易于编辑和维护
- 支持版本控制
- 可直接作为文档

### Q2: 如何保证Agent配置的正确性？

**A**: 通过以下机制：
- 配置Schema验证
- 启动时配置检查
- 单元测试覆盖各种配置场景
- 提供配置验证工具

### Q3: MVP阶段为何使用内存存储？

**A**: MVP阶段优先验证业务流程，内存存储：
- 开发效率高
- 无需额外依赖
- 后续迁移到Redis/MySQL成本低

### Q4: 如何扩展新的工具类型？

**A**: 框架设计预留了扩展点：
- 定义新的ToolConfig子类
- 实现对应的ToolExecutor
- 在ToolRegistry中注册

---

**文档版本**: 1.0.0
**创建日期**: 2026-01-25
**作者**: Foggy Navigator Agent Framework Team
