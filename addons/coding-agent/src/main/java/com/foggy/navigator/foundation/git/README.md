# Foundation Git 模块

> OpenHands Per-User 实例管理和验证服务集成

## 模块概述

本模块提供语义层编辑所需的基础设施：
- **OpenHands Per-User 实例管理**：每个用户一个持久的 OpenHands V1 容器实例，在实例内管理多个会话
- **验证服务集成**：调用独立部署的 Validation Service 进行实时验证

## 目录结构

```
foundation/git/
├── OpenHandsInstanceManager.java    # Per-user OpenHands 实例管理
├── OpenHandsClient.java             # OpenHands V1 REST API 客户端
├── OpenHandsClientFactory.java      # 按 userId 获取客户端的工厂
├── ValidationServiceClient.java     # 验证服务客户端
├── config/
│   └── GitModuleConfig.java         # 模块配置
├── model/
│   ├── v1/                          # OpenHands V1 API DTOs
│   │   ├── AppConversationStartRequest.java
│   │   ├── AppConversationStartTask.java
│   │   ├── AppConversationInfo.java
│   │   └── SendMessagePayload.java
│   ├── ValidationRequest.java       # 验证请求
│   ├── ValidationResult.java        # 验证结果
│   ├── ValidationError.java         # 验证错误
│   ├── DatasourceConfig.java        # 数据源配置
│   └── GitCredentials.java          # Git 凭证
└── util/
    └── NamespaceGenerator.java      # 命名空间生成工具
```

## 核心功能

### 1. Per-User OpenHands 实例管理

```java
@Autowired
private OpenHandsInstanceManager instanceManager;

// 确保用户实例运行中 (若不存在则创建)
UserInstance instance = instanceManager.ensureUserInstance(userId);

// 获取用户实例 base URL
String baseUrl = instanceManager.getBaseUrl(userId);

// 检查用户实例是否就绪
boolean ready = instanceManager.isReady(userId);

// 关闭用户实例
instanceManager.shutdownUserInstance(userId);
```

### 2. OpenHands V1 API 客户端

```java
@Autowired
private OpenHandsClientFactory clientFactory;

// 获取用户的 OpenHands 客户端
OpenHandsClient client = clientFactory.getClientForUser(userId);

// 创建 OH 会话
AppConversationStartTask task = client.startConversation(
    AppConversationStartRequest.builder()
        .selectedRepository(gitRepoUrl)
        .selectedBranch(branch)
        .llmModel("gpt-4")
        .agentType("DEFAULT")
        .build()
);

// 查询会话状态
AppConversationInfo info = client.getConversationInfo(ohConversationId);

// Sandbox 管理
client.deleteSandbox(sandboxId);
client.pauseSandbox(sandboxId);
client.resumeSandbox(sandboxId);
```

### 3. 验证服务集成

```java
@Autowired
private ValidationServiceClient validationClient;

// 验证语义层文件
ValidationResult result = validationClient.validate(workspacePath);
```

## 配置说明

### application.yml

```yaml
foggy:
  coding-agent:
    openhands:
      image: ghcr.io/all-hands-ai/openhands:main
      workspace-base: /workspace
      port-range-start: 30100
      port-range-end: 36000
      instance-health-check-interval: 30000
      api-key: ${OPENAI_API_KEY}
      model-name: gpt-4

    validation:
      url: http://validation-service:8081
      enabled: true
      realtime: true
```

## 架构设计

### Per-User 实例架构

```
User A ──> OpenHands Instance (port 30100)
            ├── Conversation 1 (sandbox-1)
            ├── Conversation 2 (sandbox-2)
            └── Conversation N (sandbox-N)

User B ──> OpenHands Instance (port 30101)
            ├── Conversation 1 (sandbox-3)
            └── Conversation 2 (sandbox-4)

Shared ──> Validation Service
```

- 每个用户一个 OpenHands V1 容器 (按需创建)
- 同一用户的多个会话复用同一容器
- V1 API 管理会话生命周期 (sandbox)
- 单个 Validation Service (共享)
