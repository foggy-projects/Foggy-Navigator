# Tutor Agent 开发方案

**模块**: tutor-agent
**负责人**: 待分配
**预估工期**: 3-4天
**依赖**: agent-framework (已完成)

---

## 1. 开发概览

### 1.1 实现特点

✅ **已完成（配置文件）**:
- Agent配置文件（tutor-agent.yml）
- 4个Skill定义文件（Markdown）
- Maven模块结构
- Spring Boot配置

⏳ **待开发（Java代码）**:
- 6个Java类（约150行代码）
- Mock服务（用于测试）

### 1.2 代码量统计

| 类型 | 已完成 | 待开发 | 说明 |
|------|--------|--------|------|
| 配置文件 | 100% | - | Agent配置 + Skills + application.yml |
| Java代码 | 0% | 150行 | 6个类 |
| 测试代码 | 0% | 100行 | 单元测试 + Mock |
| **总计** | **70%** | **30%** | **配置为主，代码很少** |

---

## 2. 待开发代码清单

### 2.1 核心类（必须）

| 类名 | 文件路径 | 行数 | 优先级 | 说明 |
|------|---------|------|--------|------|
| `TutorAgentApplication` | `com/foggy/navigator/tutor/TutorAgentApplication.java` | 15行 | P0 | Spring Boot启动类 |
| `TutorAgentInitializer` | `com/foggy/navigator/tutor/TutorAgentInitializer.java` | 30行 | P0 | Agent注册初始化器 |
| `SystemConfigController` | `com/foggy/navigator/tutor/controller/SystemConfigController.java` | 60行 | P0 | 配置查询工具接口 |
| `ConfigStatusResponse` | `com/foggy/navigator/tutor/model/ConfigStatusResponse.java` | 15行 | P0 | 响应模型 |
| `ConfigProgressResponse` | `com/foggy/navigator/tutor/model/ConfigProgressResponse.java` | 15行 | P0 | 响应模型 |
| `PendingTasksResponse` | `com/foggy/navigator/tutor/model/PendingTasksResponse.java` | 15行 | P0 | 响应模型 |

**合计**: 6个类，150行代码

### 2.2 Mock服务（测试用）

| 类名 | 文件路径 | 行数 | 优先级 | 说明 |
|------|---------|------|--------|------|
| `MockConfigurationService` | `com/foggy/navigator/tutor/service/MockConfigurationService.java` | 50行 | P1 | Mock配置服务 |
| `TutorAgentIntegrationTest` | `test/.../TutorAgentIntegrationTest.java` | 50行 | P1 | 集成测试 |

**合计**: 2个类，100行代码

---

## 3. 代码实现模板

### 3.1 TutorAgentApplication.java

```java
package com.foggy.navigator.tutor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
    "com.foggy.navigator.agent",  // Agent Framework
    "com.foggy.navigator.tutor"   // Tutor Agent
})
public class TutorAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(TutorAgentApplication.class, args);
    }
}
```

### 3.2 TutorAgentInitializer.java

```java
package com.foggy.navigator.tutor;

import core.com.foggy.navigator.agent.framework.AgentConfigLoader;
import core.com.foggy.navigator.agent.framework.AgentRegistry;
import model.core.com.foggy.navigator.agent.framework.AgentConfig;
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

    @Override
    public void run(String... args) {
        log.info("初始化导师Agent...");

        // 加载Agent配置
        AgentConfig config = configLoader.load(
                "classpath:agent-config/tutor-agent.yml"
        );

        // 注册Agent
        agentRegistry.register(config);

        log.info("导师Agent初始化完成: {}", config.getName());
        log.info("  - Agent ID: {}", config.getId());
        log.info("  - 能力: {}", config.getCapabilities());
        log.info("  - Skills数量: {}", config.getSkills().getEnabled().size());
        log.info("  - 工具数量: {}", config.getTools().size());
    }
}
```

### 3.3 SystemConfigController.java

```java
package com.foggy.navigator.tutor.controller;

import com.foggy.navigator.tutor.model.*;
import com.foggy.navigator.config.ConfigurationService;
import com.foggy.navigator.config.ConfigStatus;
import com.foggy.navigator.config.ConfigProgress;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tutor/config")
@RequiredArgsConstructor
public class SystemConfigController {

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
        ConfigStatus status = configService.getSemanticLayerStatus();
        return ConfigStatusResponse.builder()
            .configured(status.isConfigured())
            .message(status.getMessage())
            .details(status.getDetails())
            .build();
    }

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
}
```

### 3.4 ConfigStatusResponse.java

```java
package com.foggy.navigator.tutor.model;

import lombok.Builder;
import lombok.Data;
import java.util.Map;

@Data
@Builder
public class ConfigStatusResponse {
    private boolean configured;
    private String message;
    private Map<String, Object> details;
}
```

### 3.5 ConfigProgressResponse.java

```java
package com.foggy.navigator.tutor.model;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class ConfigProgressResponse {
    private int totalSteps;
    private int completedSteps;
    private String currentStep;
    private List<String> pendingSteps;
    private int progressPercentage;
}
```

### 3.6 PendingTasksResponse.java

```java
package com.foggy.navigator.tutor.model;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class PendingTasksResponse {
    private boolean hasPendingTasks;
    private List<PendingTask> tasks;

    @Data
    @Builder
    public static class PendingTask {
        private String sessionId;
        private String taskName;
        private String status;
        private LocalDateTime lastActivityTime;
    }
}
```

---

## 4. 缺失接口清单

### 4.1 ConfigurationService（业务模块提供）

**状态**: ⏳ 待实现
**提供方**: 配置管理模块团队
**优先级**: P0（阻塞项）

**接口定义**:

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
```

**数据模型**:

```java
package com.foggy.navigator.config;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class ConfigStatus {
    private boolean configured;       // 是否已配置
    private String message;            // 状态描述
    private Map<String, Object> details; // 详细信息
}

@Data
public class ConfigProgress {
    private int totalSteps;            // 总步骤数
    private int completedSteps;        // 已完成步骤数
    private String currentStep;        // 当前步骤
    private List<String> pendingSteps; // 待完成步骤列表
}
```

**Mock实现（用于测试）**:

```java
package com.foggy.navigator.tutor.service;

import com.foggy.navigator.config.*;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class MockConfigurationService implements ConfigurationService {

    @Override
    public ConfigStatus getDataSourceStatus() {
        // Mock数据
        ConfigStatus status = new ConfigStatus();
        status.setConfigured(false);
        status.setMessage("数据源尚未配置");
        status.setDetails(new HashMap<>());
        return status;
    }

    @Override
    public ConfigStatus getSemanticLayerStatus() {
        ConfigStatus status = new ConfigStatus();
        status.setConfigured(false);
        status.setMessage("语义层尚未生成");
        status.setDetails(new HashMap<>());
        return status;
    }

    @Override
    public ConfigProgress getOverallProgress() {
        ConfigProgress progress = new ConfigProgress();
        progress.setTotalSteps(3);
        progress.setCompletedSteps(0);
        progress.setCurrentStep("配置数据源");
        progress.setPendingSteps(Arrays.asList(
            "配置数据源", "生成语义层", "配置权限"
        ));
        return progress;
    }
}
```

---

## 5. 开发步骤

### Phase 1: 基础框架（0.5天）

**任务**:
1. 创建6个Java类（使用上述模板）
2. 创建Mock服务
3. 编译通过

**验证**:
```bash
cd tutor-agent
mvn clean compile
```

### Phase 2: 启动测试（0.5天）

**任务**:
1. 启动应用
2. 验证Agent注册成功
3. 验证Skills加载成功
4. 验证工具接口可访问

**验证**:
```bash
mvn spring-boot:run

# 另一个终端测试
curl http://localhost:8080/api/tutor/config/datasource/status
curl http://localhost:8080/api/tutor/config/semantic-layer/status
curl http://localhost:8080/api/tutor/config/progress
```

**预期输出**:
```json
{
  "configured": false,
  "message": "数据源尚未配置",
  "details": {}
}
```

### Phase 3: 集成测试（1天）

**任务**:
1. 编写集成测试
2. 测试Agent注册流程
3. 测试Skill匹配逻辑
4. 测试工具调用

**测试代码**:

```java
@SpringBootTest
class TutorAgentIntegrationTest {

    @Autowired
    private AgentRegistry agentRegistry;

    @Autowired
    private SkillManager skillManager;

    @Test
    void testAgentRegistered() {
        AgentInfo agent = agentRegistry.findById("tutor-agent");
        assertNotNull(agent);
        assertEquals("导师Agent", agent.getName());
    }

    @Test
    void testSkillsLoaded() {
        List<Skill> skills = skillManager.getSkillsByAgent("tutor-agent");
        assertEquals(4, skills.size());
    }

    @Test
    void testToolEndpoints() {
        // 测试工具接口
    }
}
```

### Phase 4: 对接业务接口（1-2天）

**任务**:
1. 等待ConfigurationService接口实现
2. 替换Mock实现
3. 联调测试
4. 完整流程验证

---

## 6. 验证清单

### 6.1 编译验证

```bash
cd tutor-agent
mvn clean compile
# 预期: BUILD SUCCESS
```

### 6.2 启动验证

```bash
mvn spring-boot:run
```

**预期日志**:
```
初始化导师Agent...
导师Agent初始化完成: 导师Agent
  - Agent ID: tutor-agent
  - 能力: [system-guidance, configuration-check, agent-orchestration]
  - Skills数量: 4
  - 工具数量: 3
```

### 6.3 接口验证

```bash
curl http://localhost:8080/api/tutor/config/datasource/status
# 预期: 返回JSON响应

curl http://localhost:8080/api/tutor/config/semantic-layer/status
# 预期: 返回JSON响应

curl http://localhost:8080/api/tutor/config/progress
# 预期: 返回JSON响应，包含progressPercentage字段
```

### 6.4 Agent注册验证

**检查点**:
- [ ] Agent在AgentRegistry中注册成功
- [ ] 4个Skills成功加载
- [ ] 3个Tools成功注册
- [ ] 2条分派规则配置正确

---

## 7. 风险与依赖

### 7.1 阻塞依赖

| 依赖项 | 提供方 | 状态 | 影响 | 解决方案 |
|--------|--------|------|------|---------|
| `ConfigurationService` | 配置管理模块 | ⏳ 待实现 | 工具接口无法返回真实数据 | 使用Mock，后续替换 |

### 7.2 技术风险

| 风险 | 可能性 | 影响 | 缓解措施 |
|------|--------|------|---------|
| Agent Framework接口变�� | 低 | 高 | 及时同步agent-framework文档 |
| Skill匹配不准确 | 中 | 中 | 补充触发关键词，优化Skill定义 |
| 分派规则不生效 | 低 | 高 | 单元测试覆盖所有分派场景 |

---

## 8. 交付物

### 8.1 代码交付

- [ ] 6个Java类（已模板化）
- [ ] Mock服务
- [ ] 单元测试
- [ ] 集成测试

### 8.2 配置交付（已完成）

- [x] `tutor-agent.yml`
- [x] 4个Skill markdown文件
- [x] `application.yml`
- [x] `pom.xml`

### 8.3 文档交付

- [x] `README.md`
- [x] `docs/tutor-agent-design.md`
- [x] 本开发方案文档

---

## 9. 后续计划

### 9.1 Phase 5: 真实业务对接（待业务接口完成后）

1. 集成真实的ConfigurationService
2. 连接数据源模块
3. 连接语义层模块
4. 端到端流程测试

### 9.2 Phase 6: 优化迭代

1. 根据测试反馈优化Skill定义
2. 补充更多分派规则
3. 优化SystemPrompt
4. 性能调优

---

## 10. 开发排期建议

| 阶段 | 任务 | 工时 | 负责人 |
|------|------|------|--------|
| Phase 1 | 编写6个Java类 | 0.5天 | 待分配 |
| Phase 2 | 启动测试 + 接口验证 | 0.5天 | 待分配 |
| Phase 3 | 集成测试 | 1天 | 待分配 |
| Phase 4 | 对接业务接口 | 1-2天 | 待分配 + 配置管理团队 |
| **总计** | | **3-4天** | |

**前置条件**: Agent Framework已完成 ✅

**阻塞项**: ConfigurationService接口（可使用Mock先行开发）

---

**方案版本**: 1.0.0
**创建日期**: 2026-01-25
**作者**: Foggy Navigator Team
