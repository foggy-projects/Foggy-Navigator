# Coding Agent 模块

> 控制 OpenHands 进行语义层编辑的 Agent 模块

## 模块职责

根据语义层编辑要求，控制 OpenHands 容器完成以下任务：
- 动态创建和管理 OpenHands 容器
- 注入语义层生成技能（TM/QM）
- 实时验证语义层文件
- 自动提交代码并创建 PR

## 目录结构

```
coding-agent/
├── pom.xml
├── src/main/
│   ├── java/com/foggy/navigator/foundation/git/
│   │   ├── OpenHandsContainerManager.java    # OpenHands 容器管理
│   │   ├── ValidationServiceClient.java      # 验证服务客户端
│   │   ├── config/
│   │   │   └── GitModuleConfig.java
│   │   └── model/                            # 数据模型
│   └── resources/
│       ├── application.yml                   # 配置文件
│       └── openhands/skills/                 # OpenHands 技能
│           └── system-prompt-template.md
└── README.md
```

## 快速开始

### 1. 配置环境变量

```bash
export OPENAI_API_KEY=sk-xxx
export OPENAI_API_BASE_URL=https://api.openai.com/v1  # 可选
```

### 2. 启动服务

```bash
mvn spring-boot:run
```

### 3. 使用示例

```java
@Autowired
private OpenHandsContainerManager containerManager;

@Autowired
private ValidationServiceClient validationClient;

// 创建容器
String containerId = containerManager.createContainer(
    "user-123",
    "session-abc",
    ContainerConfig.builder()
        .apiKey(apiKey)
        .modelName("gpt-4")
        .build()
);

// 验证语义层
ValidationResult result = validationClient.validate("/workspace/user-123/session-abc");

// 销毁容器
containerManager.destroyContainer(containerId);
```

## 核心功能

### OpenHands 容器管理
- ✅ 动态创建容器
- ✅ 容器状态监控
- ✅ 自动销毁容器
- ✅ 容器就绪检测

### 验证服务集成
- ✅ 实时验证调用
- ✅ 验证结果处理
- ✅ 服务健康检查
- ⏳ 验证输入输出规范（TODO）

## 配置说明

详见 [application.yml](src/main/resources/application.yml)

## 依赖服务

- **OpenHands**: `ghcr.io/all-hands-ai/openhands:main`
- **Validation Service**: 独立部署的验证服务
- **Docker**: 用于容器管理

## 参考文档

- [编程 Agent 集成设计](../../docs/02-modules/coding-agent-integration.md)
- [系统架构概览](../../docs/00-system-overview.md)
