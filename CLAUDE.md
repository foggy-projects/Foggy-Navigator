# Claude AI 开发指南

Foggy Navigator - 基于 LangChain4j 的企业级动态 Agent 编排系统。

## 模块结构

### 后端模块（Maven）

```
Foggy-Navigator/
├── navigator-common/       # 公共 DTO、工具类
├── navigator-spi/          # SPI 接口定义
├── agent-framework/        # Agent 核心框架（LLM调用、Skill解析、会话路由）
├── user-auth-module/       # 用户认证（JWT）
├── metadata-config-module/ # 元数据配置管理
├── metadata-query-module/  # 元数据查询服务
├── session-module/         # 会话管理 + SSE 推送
├── tutor-agent/            # 导师 Agent（引导用户配置）
├── addons/coding-agent/    # 编程 Agent（OpenHands 集成）
└── launcher/               # Spring Boot 启动器
```

### 前端模块（pnpm workspace）

```
packages/
├── foggy-chat/             # 聊天组件库（ChatPanel、useChatStore）
└── navigator-frontend/     # Navigator 前端（Vue 3 + Element Plus）
```

## 项目启动

### 后端启动

```powershell
# 推荐：一键启动脚本
powershell -ExecutionPolicy Bypass -File start-launcher.ps1

# 手动启动
mvn clean package -pl launcher -am -DskipTests
java -jar launcher/target/launcher-1.0.0-SNAPSHOT.jar --spring.profiles.active=docker
```

后端端口：8112，健康检查：`curl http://localhost:8112/actuator/health`

### 前端启动

```bash
cd packages/navigator-frontend
pnpm install && pnpm dev
```

前端端口：5174，登录账号：root / root123

## 重要配置

- **LLM 配置**：`launcher/src/main/resources/application-docker.yml`（已 gitignore）
- **日志文件**：`logs/backend.log`、`logs/backend-error.log`

## 开发规范

1. **JPA 单体设计**：Entity 间不用关联注解，用外键字段 + Service 层组合查询
2. **统一返回**：Controller 返回 `RX<T>`，成功 `RX.ok(data)`，失败 `RX.failA/B/C(msg)`
3. **接口参数**：使用 Form/DTO 而非 Entity，详见 `/form-design` 技能
4. **需求记录**：`docs/requirement-tracker/YYYY-QX/DD-需求简述.md`，用户确认后再开发
