# 导师Agent模块设计文档

> 基于配置化的导师Agent实现方案

**依赖框架**: [Agent Framework 使用指南](./agent-framework-guide.md)

## 1. 模块概述

### 1.1 定位

导师Agent是一个**配置化的Agent实例**，运行在通用Agent框架之上，负责引导用户完成系统初始化配置。

### 1.2 核心职责

- **引导配置**: 引导用户完成数据源、语义层等系统配置
- **状态检查**: 检查系统配置状态和进度
- **任务分派**: 根据用户需求，分派给专业Agent处理
- **会话提醒**: 提醒用户继续未完成的配置任务

### 1.3 实现原则

- **配置驱动**: 通过配置文件定义Agent行为，而非硬编码
- **技能组合**: 通过Skills组合实现复杂引导流程
- **工具辅助**: 提供HTTP工具接口供AI自行查询系统状态

---

## 2. 模块结构

```
tutor-agent/
├── src/
│   └── main/
│       ├── java/com/foggy/navigator/tutor/
│       │   ├── controller/
│       │   │   └── SystemConfigController.java    # 系统配置查询接口
│       │   ├── service/
│       │   │   └── TutorAgentInitializer.java     # 初始化器
│       │   └── model/
│       │       ├── ConfigStatus.java
│       │       └── ConfigProgress.java
│       └── resources/
│           ├── agent-config/
│           │   ├── tutor-agent.json               # Agent配置（主格式）
│           │   └── tutor-agent.yml                # Agent配置（导出格式）
│           └── skills/tutor/
│               ├── check-system-status.md         # 检查状态Skill
│               ├── guide-datasource-config.md     # 引导配置数据源
│               ├── guide-semantic-layer.md        # 引导配置语义层
│               └── suggest-next-step.md           # 建议下一步
├── README.md                                       # 集成指南
└── pom.xml
```

---

## 3. Agent配置文件

### 3.1 tutor-agent.json（主配置格式）

```json
{
  "agent": {
    "id": "tutor-agent",
    "name": "导师Agent",
    "type": "system",
    "description": "引导用户完成系统初始化配置，检查配置状态，分派任务给专业Agent",
    "capabilities": [
      "system-guidance",
      "configuration-check",
      "agent-orchestration"
    ],
    "skills": {
      "directory": "classpath:skills/tutor",
      "enabled": [
        "check-system-status",
        "guide-datasource-config",
        "guide-semantic-layer",
        "suggest-next-step"
      ]
    },
    "tools": [
      {
        "name": "checkDatasourceStatus",
        "description": "检查数据源配置状态",
        "http": {
          "method": "GET",
          "url": "http://localhost:8080/api/tutor/config/datasource/status"
        }
      },
      {
        "name": "checkSemanticLayerStatus",
        "description": "检查语义层配置状态",
        "http": {
          "method": "GET",
          "url": "http://localhost:8080/api/tutor/config/semantic-layer/status"
        }
      },
      {
        "name": "getConfigProgress",
        "description": "获取系统整体配置进度",
        "http": {
          "method": "GET",
          "url": "http://localhost:8080/api/tutor/config/progress"
        }
      },
      {
        "name": "findPendingTasks",
        "description": "查找用户未完成的配置任务",
        "http": {
          "method": "GET",
          "url": "http://localhost:8080/api/tutor/sessions/pending?userId={userId}"
        }
      }
    ],
    "model": {
      "provider": "openai",
      "model": "gpt-4",
      "temperature": 0.7,
      "systemPrompt": "你是Foggy Navigator的导师Agent，负责引导用户完成系统配置。\n\n你的职责：\n1. 检查系统配置状态（使用checkDatasourceStatus、checkSemanticLayerStatus等工具）\n2. 根据配置状态，提供清晰的下一步指导\n3. 当用户需要执行具体配置任务时，分派给专业Agent处理\n4. 检查是否有未完成的任务，提醒用户继续\n\n沟通风格：\n- 友好、耐心、专业\n- 使用清晰的步骤说明\n- 避免技术术语，使用用户易懂的语言"
    },
    "delegation": {
      "rules": [
        {
          "name": "delegate-datasource-config",
          "trigger": {
            "intents": ["configure-datasource", "add-datasource"],
            "keywords": ["配置数据源", "连接数据库", "添加数据库"]
          },
          "target": "datasource-agent",
          "preconditions": [],
          "contextMapping": [
            {
              "key": "dbType",
              "source": "userInput"
            },
            {
              "key": "connectionInfo",
              "source": "userInput"
            }
          ]
        },
        {
          "name": "delegate-semantic-layer-generation",
          "trigger": {
            "intents": ["generate-semantic-layer", "create-models"],
            "keywords": ["生成语义层", "创建模型", "分析数据库"]
          },
          "target": "semantic-layer-agent",
          "preconditions": [
            {
              "datasource_configured": true
            }
          ],
          "contextMapping": [
            {
              "key": "datasourceId",
              "source": "systemConfig.datasourceId"
            }
          ]
        },
        {
          "name": "delegate-semantic-layer-edit",
          "trigger": {
            "intents": ["edit-semantic-layer", "modify-models"],
            "keywords": ["编辑", "修改语义层"]
          },
          "target": "coding-agent",
          "preconditions": [
            {
              "semantic_layer_exists": true
            }
          ],
          "contextMapping": [
            {
              "key": "gitRepoUrl",
              "source": "systemConfig.semanticLayerRepo"
            }
          ]
        }
      ]
    },
    "sessionResume": {
      "enabled": true,
      "checkOnStartup": true,
      "reminderTemplate": "您好！检测到您有未完成的配置任务：**{taskName}**\n\n任务状态：{taskStatus}\n上次操作时间：{lastActivityTime}\n\n是否继续之前的配置？"
    }
  }
}
```

### 3.2 tutor-agent.yml（对应YAML格式，用于导出）

```yaml
# 导师Agent配置
agent:
  id: tutor-agent
  name: 导师Agent
  type: system
  description: 引导用户完成系统初始化配置，检查配置状态，分派任务给专业Agent

  # 能力声明（用于Agent发现）
  capabilities:
    - system-guidance          # 系统引导
    - configuration-check      # 配置检查
    - agent-orchestration      # Agent编排

  # 关联的Skills
  skills:
    directory: classpath:skills/tutor
    enabled:
      - check-system-status
      - guide-datasource-config
      - guide-semantic-layer
      - suggest-next-step

  # 可用工具（HTTP接口形式）
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

    - name: findPendingTasks
      description: 查找用户未完成的配置任务
      http:
        method: GET
        url: http://localhost:8080/api/tutor/sessions/pending?userId={userId}

  # 模型配置
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

      沟通风格：
      - 友好、耐心、专业
      - 使用清晰的步骤说明
      - 避免技术术语，使用用户易懂的语言

  # 分派规则
  delegation:
    rules:
      # 规则1: 配置数据源
      - name: delegate-datasource-config
        trigger:
          intents:
            - configure-datasource
            - add-datasource
          keywords:
            - 配置数据源
            - 连接数据库
            - 添加数据库
        target: datasource-agent
        preconditions: []  # 无前置条件
        contextMapping:
          - key: dbType
            source: userInput
          - key: connectionInfo
            source: userInput

      # 规则2: 生成语义层
      - name: delegate-semantic-layer-generation
        trigger:
          intents:
            - generate-semantic-layer
            - create-models
          keywords:
            - 生成语义层
            - 创建模型
            - 分析数据库
        target: semantic-layer-agent
        preconditions:
          - datasource_configured: true  # 必须先配置数据源
        contextMapping:
          - key: datasourceId
            source: systemConfig.datasourceId

      # 规则3: 编辑语义层
      - name: delegate-semantic-layer-edit
        trigger:
          intents:
            - edit-semantic-layer
            - modify-models
          keywords:
            - 编辑
            - 修改语义层
        target: coding-agent
        preconditions:
          - semantic_layer_exists: true
        contextMapping:
          - key: gitRepoUrl
            source: systemConfig.semanticLayerRepo

  # 会话恢复配置
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

### 4.1 check-system-status.md

```markdown
# 检查系统状态

## Skill ID
check-system-status

## 触发条件
- 用户询问系统状态
- 用户询问配置进度
- 关键词: 状态、进度、检查、当前配置

## 执行逻辑

### 步骤1: 调用工具查询状态
使用以下工具获取系统配置状态：
- `checkDatasourceStatus()` - 检查数据源配置
- `checkSemanticLayerStatus()` - 检查语义层配置
- `getConfigProgress()` - 获取整体进度

### 步骤2: 分析配置完成度
根据工具返回的结果，判断：
- 已完成哪些配置步骤
- 待配置哪些步骤
- 当前处于哪个阶段

### 步骤3: 生成友好的状态报告
按照以下格式输出：

**系统配置状态**

✅ **已完成**
- [列出已完成的配置项]

⏳ **待配置**
- [列出待配置项]

💡 **建议下一步**: [建议用户接下来应该做什么]

## 示例输出

```
**系统配置状态**

✅ **已完成**
- 数据源配置（MySQL，数据库：sales_db）

⏳ **待配置**
- 语义层生成
- 权限配置

💡 **建议下一步**:
您已完成数据源配置，接下来可以生成语义层。我可以帮您分析数据库结构并自动生成语义层模型。

请问是否开始生成语义层？
```

## 决策
- 仅提供状态信息，不进行分派
- 用户询问下一步操作时，调用 `suggest-next-step` Skill
```

### 4.2 guide-datasource-config.md

```markdown
# 引导配置数据源

## Skill ID
guide-datasource-config

## 触发条件
- 用户想要配置数据源
- 关键词: 数据源、连接数据库、配置DB、添加数据库

## 执行逻辑

### 步骤1: 检查数据源状态
调用 `checkDatasourceStatus()` 检查是否已配置数据源

**分支处理：**
- **已配置** → 询问是否添加新数据源或修改现有数据源
- **未配置** → 开始引导流程

### 步骤2: 收集必要信息
需要收集以下信息：
1. 数据库类型 (MySQL / PostgreSQL / Oracle / SQL Server)
2. 主机地址
3. 端口
4. 数据库名称
5. 用户名
6. 密码（提醒用户数据会加密存储）

**交互方式：**
- 逐步提问，每次一个问题
- 提供常用选项（如端口默认值）
- 友好的提示语

### 步骤3: 验证信息完整性
检查是否收集到所有必要信息：
- [ ] dbType
- [ ] host
- [ ] port
- [ ] database
- [ ] username
- [ ] password

### 步骤4: 分派决策
**如果信息完整** → 分派给 `datasource-agent` 执行配置

**如果信息不完整** → 继续追问缺失的信息

## 分派条件
触发 `delegate-datasource-config` 规则，需满足：
- 所有必要信息已收集
- 用户确认开始配置

## 上下文传递
分派时传递以下参数：
```json
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

## 示例对话流程

**用户**: 我想配置数据源

**导师Agent**:
好的，我来帮您配置数据源。首先请选择数据库类型：
1. MySQL
2. PostgreSQL
3. Oracle
4. SQL Server

**用户**: MySQL

**导师Agent**:
请提供MySQL数据库的主机地址（如: localhost 或 192.168.1.100）

**用户**: localhost

**导师Agent**:
请提供端口号（默认: 3306）

**用户**: 3306

**导师Agent**:
请提供数据库名称

**用户**: sales_db

**导师Agent**:
请提供用户名

**用户**: admin

**导师Agent**:
请提供密码（数据会加密存储，请放心）

**用户**: [输入密码]

**导师Agent**:
好的，我已收集到所有必要信息：
- 数据库类型: MySQL
- 主机: localhost:3306
- 数据库: sales_db
- 用户: admin

是否确认开始配置？

**用户**: 确认

**导师Agent**:
[分派给 datasource-agent，跳转到数据源配置Agent的对话窗口]
```

### 4.3 guide-semantic-layer.md

```markdown
# 引导生成语义层

## Skill ID
guide-semantic-layer

## 触发条件
- 用户想要生成语义层
- 关键词: 语义层、生成模型、分析数据库结构

## 前置检查
**必须满足**: 数据源已配置

调用 `checkDatasourceStatus()` 验证前置条件

**如果未满足** → 提示用户需要先配置数据源，引导至 `guide-datasource-config`

## 执行逻辑

### 步骤1: 说明语义层生成过程
向用户解释：
- 什么是语义层
- 生成过程（AI分析数据库结构 → 自动生成模型）
- 预计耗时

### 步骤2: 确认数据源
显示当前配置的数据源信息，询问是否使用该数据源

### 步骤3: 分派给语义层Agent
满足条件后，分派给 `semantic-layer-agent` 执行生成任务

## 分派条件
触发 `delegate-semantic-layer-generation` 规则

## 上下文传递
```json
{
  "datasourceId": "datasource-123",
  "mode": "auto-generate",
  "options": {
    "analyzeRelations": true,
    "generateDescriptions": true
  }
}
```

## 示例对话

**用户**: 生成语义层

**导师Agent**:
[调用 checkDatasourceStatus()]

好的！我将帮您生成语义层。

语义层是数据库结构的业务抽象层，让用户可以用自然语言查询数据。
我会分析您的数据库结构，自动生成业务模型。

当前数据源：
- 数据库: sales_db (MySQL)
- 主机: localhost:3306

是否使用此数据源生成语义层？

**用户**: 是

**导师Agent**:
[分派给 semantic-layer-agent，跳转到语义层生成Agent的对话窗口]
```

### 4.4 suggest-next-step.md

```markdown
# 建议下一步操作

## Skill ID
suggest-next-step

## 触发条件
- 用户询问"下一步做什么"
- 用户询问"接下来应该怎么做"
- 检查状态后，用户询问建议

## 执行逻辑

### 步骤1: 获取系统配置进度
调用 `getConfigProgress()` 获取配置完成情况

### 步骤2: 检查待办任务
调用 `findPendingTasks(userId)` 查找未完成的任务

### 步骤3: 生成建议
根据配置进度和待办任务，按优先级建议：

**优先级1: 未完成的任务**
如果有未完成任务 → 建议继续完成

**优先级2: 按配置流程顺序**
- 数据源未配置 → 建议配置数据源
- 数据源已配置，语义层未生成 → 建议生成语义层
- 语义层已生成，权限未配置 → 建议配置权限

**优先级3: 系统已就绪**
如果所有配置完成 → 建议开始使用数据分析功能

### 步骤4: 提供操作指引
不仅告诉用户下一步是什么，还要告诉如何操作

## 输出格式

**根据您当前的配置进度，建议下一步：**

📋 **[任务名称]**

**为什么要做**: [解释该步骤的意义]

**如何操作**: [简单的操作说明]

**预计耗时**: [大概时间]

是否开始此任务？

## 示例输出

```
**根据您当前的配置进度，建议下一步：**

📋 **生成语义层**

**为什么要做**:
语义层是连接数据库和AI分析的桥梁。生成后，您的用户就可以用自然语言查询数据，例如"上个月销售额是多少"。

**如何操作**:
我会自动分析您的数据库结构（sales_db），识别表和字段的业务含义，生成语义模型。

**预计耗时**: 约2-5分钟（取决于数据库复杂度）

是否开始生成语义层？
```
```

---

## 5. 工具接口实现

### 5.1 SystemConfigController.java

```java
package com.foggy.navigator.tutor.controller;

import com.foggy.navigator.tutor.model.*;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;

/**
 * 系统配置查询接口
 * 提供给导师Agent使用的HTTP工具
 */
@RestController
@RequestMapping("/api/tutor/config")
@RequiredArgsConstructor
public class SystemConfigController {

    private final ConfigurationService configService;
    private final SessionService sessionService;

    /**
     * 检查数据源配置状态
     *
     * curl http://localhost:8080/api/tutor/config/datasource/status
     */
    @GetMapping("/datasource/status")
    public ConfigStatusResponse checkDatasourceStatus() {
        ConfigStatus status = configService.getDataSourceStatus();

        if (status.isConfigured()) {
            Map<String, Object> details = status.getDetails();
            return ConfigStatusResponse.builder()
                .configured(true)
                .message(String.format("数据源已配置。类型: %s, 数据库: %s",
                    details.get("dbType"),
                    details.get("database")))
                .details(details)
                .build();
        } else {
            return ConfigStatusResponse.builder()
                .configured(false)
                .message("数据源尚未配置")
                .build();
        }
    }

    /**
     * 检查语义层配置状态
     *
     * curl http://localhost:8080/api/tutor/config/semantic-layer/status
     */
    @GetMapping("/semantic-layer/status")
    public ConfigStatusResponse checkSemanticLayerStatus() {
        ConfigStatus status = configService.getSemanticLayerStatus();

        if (status.isConfigured()) {
            Map<String, Object> details = status.getDetails();
            return ConfigStatusResponse.builder()
                .configured(true)
                .message(String.format("语义层已生成，共 %d 个模型",
                    details.get("modelCount")))
                .details(details)
                .build();
        } else {
            return ConfigStatusResponse.builder()
                .configured(false)
                .message("语义层尚未生成")
                .build();
        }
    }

    /**
     * 获取系统整体配置进度
     *
     * curl http://localhost:8080/api/tutor/config/progress
     */
    @GetMapping("/progress")
    public ConfigProgressResponse getConfigProgress() {
        ConfigProgress progress = configService.getOverallProgress();

        return ConfigProgressResponse.builder()
            .totalSteps(progress.getTotalSteps())
            .completedSteps(progress.getCompletedSteps())
            .currentStep(progress.getCurrentStep())
            .pendingSteps(progress.getPendingSteps())
            .progressPercentage(
                (int) ((progress.getCompletedSteps() * 100.0) / progress.getTotalSteps())
            )
            .build();
    }

    /**
     * 查找用户未完成的配置任务
     *
     * curl http://localhost:8080/api/tutor/sessions/pending?userId=user123
     */
    @GetMapping("/sessions/pending")
    public PendingTasksResponse findPendingTasks(@RequestParam String userId) {
        List<Session> pendingSessions = sessionService.findPendingByUser(userId);

        List<PendingTask> tasks = pendingSessions.stream()
            .map(session -> PendingTask.builder()
                .sessionId(session.getId())
                .taskName(session.getTaskName())
                .status(session.getStatus())
                .lastActivityTime(session.getUpdatedAt())
                .build())
            .collect(Collectors.toList());

        return PendingTasksResponse.builder()
            .hasPendingTasks(!tasks.isEmpty())
            .tasks(tasks)
            .build();
    }
}
```

### 5.2 数据模型

```java
package com.foggy.navigator.tutor.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ConfigStatusResponse {
    private boolean configured;
    private String message;
    private Map<String, Object> details;
}

@Data
@Builder
public class ConfigProgressResponse {
    private int totalSteps;
    private int completedSteps;
    private String currentStep;
    private List<String> pendingSteps;
    private int progressPercentage;
}

@Data
@Builder
public class PendingTasksResponse {
    private boolean hasPendingTasks;
    private List<PendingTask> tasks;
}

@Data
@Builder
public class PendingTask {
    private String sessionId;
    private String taskName;
    private String status;
    private LocalDateTime lastActivityTime;
}
```

---

## 6. 初始化逻辑

### 6.1 TutorAgentInitializer.java

```java
package com.foggy.navigator.tutor.service;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 导师Agent初始化器
 * 在系统启动时自动注册导师Agent
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TutorAgentInitializer implements CommandLineRunner {

    private final AgentRegistry agentRegistry;
    private final AgentConfigLoader configLoader;

    @Override
    public void run(String... args) throws Exception {
        log.info("开始初始化导师Agent...");

        // 1. 加载导师Agent配置
        AgentConfig config = configLoader.load("classpath:agent-config/tutor-agent.yml");

        // 2. 注册导师Agent
        agentRegistry.register(config);

        log.info("导师Agent初始化完成: {}", config.getName());
        log.info("  - Agent ID: {}", config.getId());
        log.info("  - 能力: {}", config.getCapabilities());
        log.info("  - Skills数量: {}", config.getSkills().getEnabled().size());
        log.info("  - 工具数量: {}", config.getTools().size());
    }
}
```

---

## 7. 依赖公共接口

### 7.1 需要的公共接口清单

```java
// ===== Agent框架接口 =====
public interface AgentConfigLoader {
    AgentConfig load(String path);
}

public interface AgentRegistry {
    void register(AgentConfig config);
}

// ===== 配置服务接口 =====
public interface ConfigurationService {
    ConfigStatus getDataSourceStatus();
    ConfigStatus getSemanticLayerStatus();
    ConfigProgress getOverallProgress();
}

// ===== 会话服务接口 =====
public interface SessionService {
    List<Session> findPendingByUser(String userId);
}
```

---

## 8. 集成步骤

### 8.1 添加Maven依赖

```xml
<dependency>
    <groupId>com.foggy.navigator</groupId>
    <artifactId>tutor-agent</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 8.2 配置扫描路径

```java
@SpringBootApplication(scanBasePackages = {
    "com.foggy.navigator.core",
    "com.foggy.navigator.tutor"
})
public class NavigatorApplication {
    public static void main(String[] args) {
        SpringApplication.run(NavigatorApplication.class, args);
    }
}
```

### 8.3 应用配置

```yaml
# application.yml
foggy:
  agent:
    tutor:
      enabled: true
      auto-register: true
```

---

## 9. 使用流程

### 9.1 用户首次登录

```
用户登录系统
    │
    ▼
前端检测到用户是新用户
    │
    ▼
前端自动跳转到导师Agent对话窗口
    │
    ▼
导师Agent发送欢迎消息:
"您好！欢迎使用Foggy Navigator。
我是您的导师，将引导您完成系统配置。
让我先检查一下系统状态..."
    │
    ▼
导师Agent调用工具检查配置状态
    │
    ▼
导师Agent提供配置建议
```

### 9.2 用户配置数据源

```
用户: "我想配置数据源"
    │
    ▼
导师Agent匹配到 guide-datasource-config Skill
    │
    ▼
导师Agent逐步收集信息
    │
    ▼
信息收集完成，触发分派规则
    │
    ▼
导师Agent分派给 datasource-agent
    │
    ▼
前端跳转到 datasource-agent 的对话窗口
```

---

## 10. 测试验证

### 10.1 工具接口测试

```bash
# 测试数据源状态查询
curl http://localhost:8080/api/tutor/config/datasource/status

# 测试语义层状态查询
curl http://localhost:8080/api/tutor/config/semantic-layer/status

# 测试配置进度查询
curl http://localhost:8080/api/tutor/config/progress

# 测试待办任务查询
curl http://localhost:8080/api/tutor/sessions/pending?userId=user123
```

### 10.2 预期响应

```json
// 数据源状态 - 已配置
{
  "configured": true,
  "message": "数据源已配置。类型: MySQL, 数据库: sales_db",
  "details": {
    "dbType": "MySQL",
    "host": "localhost",
    "port": 3306,
    "database": "sales_db"
  }
}

// 配置进度
{
  "totalSteps": 3,
  "completedSteps": 1,
  "currentStep": "生成语义层",
  "pendingSteps": ["生成语义层", "配置权限"],
  "progressPercentage": 33
}
```

---

## 11. 文件清单

| 文件路径 | 说明 |
|---------|------|
| `resources/agent-config/tutor-agent.yml` | Agent配置文件 |
| `resources/skills/tutor/check-system-status.md` | 检查系统状态Skill |
| `resources/skills/tutor/guide-datasource-config.md` | 引导配置数据源Skill |
| `resources/skills/tutor/guide-semantic-layer.md` | 引导生成语义层Skill |
| `resources/skills/tutor/suggest-next-step.md` | 建议下一步Skill |
| `controller/SystemConfigController.java` | 系统配置查询接口 |
| `service/TutorAgentInitializer.java` | 初始化器 |
| `model/*.java` | 数据模型 |

---

## 12. 后续扩展

### 12.1 新增Skill

1. 在 `resources/skills/tutor/` 目录下创建新的markdown文件
2. 在 `tutor-agent.yml` 的 `skills.enabled` 中添加Skill ID
3. 重启应用即可生效

### 12.2 新增工具

1. 在 `SystemConfigController` 中添加新的API端点
2. 在 `tutor-agent.yml` 的 `tools` 中注册新工具
3. 在Skill中引用新工具

### 12.3 新增分派规则

在 `tutor-agent.yml` 的 `delegation.rules` 中添加新规则

---

**文档版本**: 1.0.0
**创建日期**: 2026-01-25
**作者**: Foggy Navigator Tutor Agent Team
