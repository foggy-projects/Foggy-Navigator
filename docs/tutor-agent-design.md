# 导师Agent模块设计文档

> 基于配置化的导师Agent实现方案

**依赖框架**: [Agent Framework 使用指南](./agent-framework-guide.md)

---

## 1. 模块概述

### 1.1 定位

导师Agent是一个**配置化的Agent实例**，运行在Agent Framework之上，负责引导用户完成系统初始化配置。

### 1.2 核心职责

- **引导配置**: 引导用户完成数据源、语义层等系统配置
- **状态检查**: 检查系统配置状态和进度
- **任务分派**: 根据用户需求，分派给专业Agent处理
- **会话提醒**: 提醒用户继续未完成的配置任务

### 1.3 实现特点

- **90%配置 + 10%代码**: 核心逻辑通过配置文件和Skill定义实现
- **依赖框架**: 依赖Agent Framework提供的运行时能力
- **极简实现**: 仅需实现工具接口和初始化逻辑

---

## 2. 模块结构

```
tutor-agent/
├── src/
│   └── main/
│       ├── java/com/foggy/navigator/tutor/
│       │   ├── TutorAgentApplication.java      # 独立运行入口
│       │   ├── TutorAgentInitializer.java      # Agent注册初始化
│       │   ├── controller/
│       │   │   └── SystemConfigController.java # 配置查询工具接口
│       │   └── model/
│       │       ├── ConfigStatusResponse.java   # 响应模型
│       │       └── ConfigProgressResponse.java # 响应模型
│       └── resources/
│           ├── agent-config/
│           │   └── tutor-agent.yml             # Agent配置
│           ├── skills/tutor/
│           │   ├── check-system-status.md      # Skill: 检查状态
│           │   ├── guide-datasource-config.md  # Skill: 引导配置数据源
│           │   ├── guide-semantic-layer.md     # Skill: 引导生成语义层
│           │   └── suggest-next-step.md        # Skill: 建议下一步
│           └── application.yml                 # Spring Boot配置
├── pom.xml
└── README.md
```

### 2.1 代码量统计（预估）

| 类型 | 文件数 | 行数 | 说明 |
|------|--------|------|------|
| **配置文件** | 5 | 350行 | Agent配置(1) + Skills(4) |
| **Java代码** | 4 | 150行 | 初始化(1) + Controller(1) + Model(2) |
| **总计** | 9 | ~500行 | **配置占70%，代码占30%** |

---

## 3. Agent配置文件

参见 [agent-framework-guide.md](./agent-framework-guide.md#21-创建-agent-配置)

**配置文件**: `resources/agent-config/tutor-agent.yml`

```yaml
agent:
  id: tutor-agent
  name: 导师Agent
  type: system
  description: 引导用户完成系统初始化配置

  capabilities:
    - system-guidance
    - configuration-check
    - agent-orchestration

  skills:
    directory: classpath:skills/tutor
    enabled:
      - check-system-status
      - guide-datasource-config
      - guide-semantic-layer
      - suggest-next-step

  tools:
    - name: checkDatasourceStatus
      description: 检查数据源配置状态
      http:
        method: GET
        url: http://localhost:8080/api/tutor/config/datasource/status

    - name: checkSemanticLayerStatus
      description: 检查语义层配置状态
      http:
        method: GET
        url: http://localhost:8080/api/tutor/config/semantic-layer/status

    - name: getConfigProgress
      description: 获取系统整体配置进度
      http:
        method: GET
        url: http://localhost:8080/api/tutor/config/progress

  model:
    provider: openai
    model: gpt-4
    temperature: 0.7
    systemPrompt: |
      你是Foggy Navigator的导师Agent，负责引导用户完成系统配置。

      你的职责：
      1. 检查系统配置状态（使用checkDatasourceStatus、checkSemanticLayerStatus等工具）
      2. 根据配置状态，提供清晰的下一步指导
      3. 当用户需要执行具体配置任务时，分派给专业Agent处理
      4. 检查是否有未完成的任务，提醒用户继续

      沟通风格：友好、耐心、专业，使用清晰的步骤说明

  delegation:
    rules:
      - name: delegate-datasource-config
        trigger:
          keywords: [配置数据源, 连接数据库, 添加数据库]
        target: datasource-agent
        preconditions: []

      - name: delegate-semantic-layer-generation
        trigger:
          keywords: [生成语义层, 创建模型, 分析数据库]
        target: semantic-layer-agent
        preconditions:
          - datasource_configured: true

  sessionResume:
    enabled: true
    checkOnStartup: true
    reminderTemplate: |
      您好！检测到您有未完成的配置任务：**{taskName}**

      任务状态：{taskStatus}
      上次操作时间：{lastActivityTime}

      是否继续之前的配置？
```

---

## 4. Skills定义

Skills采用Markdown格式，参见 [agent-framework-guide.md](./agent-framework-guide.md#34-skillmanager---技能管理)

### 4.1 check-system-status.md

```markdown
# Skill ID
check-system-status

# Skill标题
检查系统状态

# 触发条件
- 检查状态
- 查看配置
- 系统状态
- 当前进度

# 执行逻辑
1. 调用 checkDatasourceStatus() 检查数据源
2. 调用 checkSemanticLayerStatus() 检查语义层
3. 调用 getConfigProgress() 获取整体进度
4. 生成友好的状态报告

# 输出格式
**系统配置状态**

✅ **已完成**
- [已完成项列表]

⏳ **待配置**
- [待配置项列表]

💡 **建议下一步**: [具体建议]

# 决策
仅提供状态信息，不分派
```

### 4.2 guide-datasource-config.md

```markdown
# Skill ID
guide-datasource-config

# Skill标题
引导配置数据源

# 触发条件
- 配置数据源
- 连接数据库
- 添加数据库

# 执行逻辑
1. 调用 checkDatasourceStatus() 检查当前状态
2. 逐步收集信息：数据库类型、主机、端口、数据库名、用户名、密码
3. 验证信息完整性
4. 如果信息完整，分派给 datasource-agent

# 分派条件
- 信息收集完整
- 用户确认开始配置
- 触发 delegate-datasource-config 规则

# 上下文传递
{
  "dbType": "MySQL",
  "connectionInfo": {
    "host": "localhost",
    "port": 3306,
    "database": "sales_db",
    "username": "admin",
    "password": "******"
  }
}
```

### 4.3 guide-semantic-layer.md

```markdown
# Skill ID
guide-semantic-layer

# Skill标题
引导生成语义层

# 触发条件
- 生成语义层
- 创建模型
- 分析数据库

# 前置条件
数据源已配置（调用checkDatasourceStatus验证）

# 执行逻辑
1. 检查数据源配置状态
2. 如果未配置，引导至 guide-datasource-config
3. 说明语义层生成过程
4. 确认数据源信息
5. 分派给 semantic-layer-agent

# 分派条件
- 数据源已配置
- 用户确认使用当前数据源
- 触发 delegate-semantic-layer-generation 规则
```

### 4.4 suggest-next-step.md

```markdown
# Skill ID
suggest-next-step

# Skill标题
建议下一步操作

# 触发条件
- 下一步做什么
- 接下来怎么做
- 给我建议

# 执行逻辑
1. 调用 getConfigProgress() 获取配置进度
2. 根据进度生成建议（优先级：未完成任务 > 按流程顺序 > 系统已就绪）
3. 提供操作指引

# 输出格式
**根据您当前的配置进度，建议下一步：**

📋 **[任务名称]**

**为什么要做**: [解释意义]

**如何操作**: [简单说明]

**预计耗时**: [大概时间]

是否开始此任务？
```

---

## 5. 代码实现

### 5.1 需要编写的类

| 类名 | 职责 | 行数（预估） |
|------|------|------------|
| `TutorAgentApplication` | Spring Boot启动类 | 15行 |
| `TutorAgentInitializer` | Agent注册初始化 | 30行 |
| `SystemConfigController` | 配置查询工具接口 | 60行 |
| `ConfigStatusResponse` | 响应模型 | 15行 |
| `ConfigProgressResponse` | 响应模型 | 15行 |
| `PendingTasksResponse` | 响应模型 | 15行 |
| **合计** | | **150行** |

### 5.2 SystemConfigController 示例

参见 [agent-framework-guide.md](./agent-framework-guide.md#62-处理用户消息)

```java
@RestController
@RequestMapping("/api/tutor/config")
@RequiredArgsConstructor
public class SystemConfigController {

    // 需要依赖的服务接口（由业务模块提供）
    private final ConfigurationService configService;

    @GetMapping("/datasource/status")
    public ConfigStatusResponse checkDatasourceStatus() {
        ConfigStatus status = configService.getDataSourceStatus();
        return ConfigStatusResponse.builder()
            .configured(status.isConfigured())
            .message(status.getMessage())
            .details(status.getDetails())
            .build();
    }

    @GetMapping("/semantic-layer/status")
    public ConfigStatusResponse checkSemanticLayerStatus() {
        // 类似实现
    }

    @GetMapping("/progress")
    public ConfigProgressResponse getConfigProgress() {
        // 类似实现
    }
}
```

### 5.3 TutorAgentInitializer 示例

```java
@Component
@RequiredArgsConstructor
public class TutorAgentInitializer implements CommandLineRunner {

    private final AgentRegistry agentRegistry;
    private final AgentConfigLoader configLoader;

    @Override
    public void run(String... args) {
        // 加载配置并注册Agent
        AgentConfig config = configLoader.load(
            "classpath:agent-config/tutor-agent.yml"
        );
        agentRegistry.register(config);

        log.info("导师Agent初始化完成");
    }
}
```

---

## 6. 依赖清单

### 6.1 Maven依赖

```xml
<dependencies>
    <!-- Agent Framework -->
    <dependency>
        <groupId>com.foggy.navigator</groupId>
        <artifactId>agent-framework</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </dependency>

    <!-- Spring Boot Web (提供REST接口) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- Lombok -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

### 6.2 需要的业务接口（缺失项清单）

以下接口需要由业务模块团队提供：

| 接口 | 提供方 | 状态 | 说明 |
|------|--------|------|------|
| `ConfigurationService` | 配置管理模块 | ⏳ 待实现 | 系统配置状态查询 |

**ConfigurationService 接口定义**:

```java
package com.foggy.navigator.config;

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

// 数据模型
@Data
public class ConfigStatus {
    private boolean configured;
    private String message;
    private Map<String, Object> details;
}

@Data
public class ConfigProgress {
    private int totalSteps;
    private int completedSteps;
    private String currentStep;
    private List<String> pendingSteps;
}
```

### 6.3 Agent Framework提供的接口

以下接口由agent-framework模块提供，可直接使用：

| 接口 | 状态 | 说明 |
|------|------|------|
| `AgentRegistry` | ✅ 已实现 | Agent注册管理 |
| `AgentConfigLoader` | ✅ 已实现 | 配置加载器 |
| `SessionManager` | ✅ 已实现 | 会话管理 |
| `ToolRegistry` | ✅ 已实现 | 工具注册 |
| `SkillManager` | ✅ 已实现 | Skill管理 |
| `SessionRouter` | ✅ 已实现 | 会话路由 |

---

## 7. 开发步骤

### 7.1 Phase 1: 基础框架（1天）

1. 创建Maven模块结构
2. 添加pom.xml依赖
3. 创建Application启动类
4. 创建Initializer类

### 7.2 Phase 2: 配置文件（1天）

1. 编写 tutor-agent.yml
2. 编写4个Skill markdown文件
3. 测试配置加载

### 7.3 Phase 3: 工具接口（1-2天）

1. 定义响应模型类
2. 实现 SystemConfigController
3. Mock ConfigurationService（用于测试）
4. 测试工具接口

### 7.4 Phase 4: 集成测试（1天）

1. 启动应用
2. 测试Agent注册
3. 测试工具调用
4. 测试Skill匹配

---

## 8. 测试验证

### 8.1 单元测试

```bash
# 测试工具接口
curl http://localhost:8080/api/tutor/config/datasource/status
curl http://localhost:8080/api/tutor/config/semantic-layer/status
curl http://localhost:8080/api/tutor/config/progress
```

### 8.2 集成测试

1. 启动tutor-agent应用
2. 验证Agent注册成功（日志输出）
3. 验证Skills加载成功
4. 验证工具注册成功

---

## 9. 与Agent Framework集成

参见 [agent-framework-guide.md § 6. 与导师Agent集成示例](./agent-framework-guide.md#6-与导师agent集成示例)

---

## 10. 后续扩展

### 10.1 新增Skill

1. 在 `resources/skills/tutor/` 下创建markdown
2. 在 `tutor-agent.yml` 的 `skills.enabled` 中添加
3. 重启生效

### 10.2 新增工具

1. 在 `SystemConfigController` 添加接口
2. 在 `tutor-agent.yml` 的 `tools` 中注册
3. 在Skill中引用

### 10.3 新增分派规则

在 `tutor-agent.yml` 的 `delegation.rules` 中添加

---

**文档版本**: 2.0.0
**创建日期**: 2026-01-25
**最后更新**: 2026-01-25
**作者**: Foggy Navigator Team

**变更记录**:
- v2.0.0: 简化文档，对接agent-framework，突出配置化特点
- v1.0.0: 初始版本
