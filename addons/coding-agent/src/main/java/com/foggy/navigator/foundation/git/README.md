# Foundation Git 模块

> OpenHands 容器管理和验证服务集成

## 模块概述

本模块提供语义层编辑所需的基础设施：
- **OpenHands 容器管理**：动态创建和销毁 OpenHands 容器
- **验证服务集成**：调用独立部署的 Validation Service 进行实时验证

## 目录结构

```
foundation/git/
├── OpenHandsContainerManager.java    # OpenHands 容器管理
├── ValidationServiceClient.java      # 验证服务客户端
├── config/
│   └── GitModuleConfig.java          # 模块配置
└── model/
    ├── ContainerConfig.java          # 容器配置
    ├── ContainerStatus.java          # 容器状态
    ├── ValidationRequest.java        # 验证请求
    ├── ValidationResult.java         # 验证结果
    ├── ValidationError.java          # 验证错误
    ├── DatasourceConfig.java         # 数据源配置
    └── GitCredentials.java           # Git 凭证
```

## 核心功能

### 1. OpenHands 容器管理

```java
@Autowired
private OpenHandsContainerManager containerManager;

// 创建容器
String containerId = containerManager.createContainer(
    userId,
    sessionId,
    ContainerConfig.builder()
        .apiKey(apiKey)
        .modelName("gpt-4")
        .build()
);

// 等待容器就绪
boolean ready = containerManager.waitForContainerReady(containerId, 60);

// 销毁容器
containerManager.destroyContainer(containerId);
```

### 2. 验证服务集成

```java
@Autowired
private ValidationServiceClient validationClient;

// 验证语义层文件
ValidationResult result = validationClient.validate(workspacePath);

if (!result.isSuccess()) {
    // 处理验证错误
    for (ValidationError error : result.getErrors()) {
        log.error("验证失败: {}", error.getMessage());
    }
}
```

## 配置说明

### application.yml

```yaml
foggy:
  coding-agent:
    openhands:
      image: ghcr.io/all-hands-ai/openhands:main
      workspace-base: /workspace
      container-timeout: 1800
      max-concurrent: 10
      api-key: ${OPENAI_API_KEY}
      model-name: gpt-4

    validation:
      url: http://validation-service:8081
      enabled: true
      realtime: true
```

### 环境变量

```bash
# OpenAI API Key
export OPENAI_API_KEY=sk-xxx

# OpenAI API Base URL (可选)
export OPENAI_API_BASE_URL=https://api.openai.com/v1
```

## 依赖

```xml
<!-- Docker Java Client -->
<dependency>
    <groupId>com.github.docker-java</groupId>
    <artifactId>docker-java</artifactId>
    <version>3.3.4</version>
</dependency>

<!-- Spring Boot Web -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

## 架构设计

### 多对一架构

```
┌──────────────┐
│ OpenHands-1  │──┐
└──────────────┘  │
                  │    ┌──────────────────┐
┌──────────────┐  ├───>│  Validation      │
│ OpenHands-2  │──┤    │  Service (共享)  │
└──────────────┘  │    └──────────────────┘
                  │
┌──────────────┐  │
│ OpenHands-N  │──┘
└──────────────┘
```

- 多个 OpenHands 容器（按需创建）
- 单个 Validation Service（共享）
- 通过工作空间路径隔离

## TODO

- [ ] 完善验证服务的输入输出格式（等待验证服务规范）
- [ ] 添加 OpenHands API 客户端（调用 OpenHands REST API）
- [ ] 实现 System Prompt 构建器（注入 TM/QM 技能）
- [ ] 添加容器生命周期监控
- [ ] 实现容器池管理（可选优化）

## 参考文档

- [编程 Agent 集成设计](../../docs/02-modules/coding-agent-integration.md)
- [语义层验证服务设计](../../docs/02-modules/semantic-layer-validation.md)
- [完整工作流设计](../../docs/02-modules/semantic-layer-workflow.md)
