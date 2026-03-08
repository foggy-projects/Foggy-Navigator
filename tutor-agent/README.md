# Tutor Agent 模块

导师Agent - 引导用户完成系统配置的配置化Agent实例

## 模块概述

导师Agent是一个基于Agent Framework的配置化Agent，负责：
- 引导用户完成数据源配置
- 引导用户生成语义层
- 检查系统配置状态
- 分派任务给专业Agent

## 实现特点

- **90%配置 + 10%代码**: 核心逻辑通过配置文件和Skill定义
- **依赖Agent Framework**: 所有Agent能力由框架提供
- **极简实现**: 仅需实现工具接口和初始化逻辑

## 快速开始

### 1. 构建模块

```bash
cd tutor-agent
mvn clean install
```

### 2. 运行

```bash
mvn spring-boot:run
```

### 3. 验证

访问工具接口：

```bash
curl http://localhost:8080/api/tutor/config/datasource/status
curl http://localhost:8080/api/tutor/config/semantic-layer/status
curl http://localhost:8080/api/tutor/config/progress
```

## 目录结构

```
tutor-agent/
├── src/main/java/
│   └── com/foggy/navigator/tutor/
│       ├── TutorAgentApplication.java      # 启动类
│       ├── TutorAgentInitializer.java      # 初始化器
│       ├── controller/
│       │   └── SystemConfigController.java # 工具接口
│       └── model/                           # 响应模型
├── src/main/resources/
│   ├── agent-config/
│   │   └── tutor-agent.yml                 # Agent配置
│   ├── skills/tutor/                       # Skill定义（4个md文件）
│   └── application.yml                     # Spring Boot配置
└── pom.xml
```

## 依赖接口

### 已提供（Agent Framework）

- `AgentRegistry`
- `AgentConfigLoader`
- `SessionManager`
- `ToolRegistry`
- `SkillManager`
- `SessionRouter`

### 待实现（业务模块）

需要业务模块团队提供：

```java
public interface ConfigurationService {
    ConfigStatus getDataSourceStatus();
    ConfigStatus getSemanticLayerStatus();
    ConfigProgress getOverallProgress();
}
```

## 开发指南

详见 [docs/tutor-agent-design.md](../docs/tutor-agent-design.md)

## License

Copyright (c) 2026 Foggy Navigator Team
