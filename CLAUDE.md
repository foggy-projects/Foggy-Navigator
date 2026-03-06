# Claude AI 开发指南

Foggy Navigator - 基于 LangChain4j 的个人 AI Agent 编排中枢。

## 模块结构

### 后端模块（Maven）

```
Foggy-Navigator/
├── navigator-common/           # 公共 DTO、Entity、CredentialEncryptor
├── navigator-spi/              # SPI 接口定义（CodingAgentFacade、ClaudeWorkerFacade）
├── agent-framework/            # Agent 核心框架（LLM调用、Skill解析、工具执行、会话路由）
├── user-auth-module/           # 用户认证（JWT）
├── metadata-config-module/     # Skill 配置 + 平台配置管理（Git/LLM/AgentModel）
├── metadata-query-module/      # 元数据查询服务
├── session-module/             # 会话管理 + SSE 推送 + JpaAgentRegistry
├── tutor-agent/                # 导师 Agent（引导用户、分派任务）
├── addons/coding-agent/        # 编程 Agent（OpenHands 集成）
├── addons/claude-worker-agent/ # Claude Code 工人 Agent（远程编程）
└── launcher/                   # Spring Boot 启动器
```

### 前端模块（pnpm workspace）

```
packages/
├── foggy-chat/             # 聊天组件库（ChatPanel、useChatStore）
└── navigator-frontend/     # Navigator 前端（Vue 3 + Element Plus）
```

### 工具与测试支撑

```
tools/
├── claude-agent-worker/    # Claude Worker Python 服务
└── mock-llm-service/       # Mock Anthropic 端点（L3 集成测试用）
```

## 项目启动

### 启动脚本一览

| 脚本 | 说明 | 端口 |
|------|------|------|
| `start-launcher.ps1` | 后端（编译+启动） | 8112 |
| `start-launcher-mock.ps1` | 后端（Mock LLM 模式） | 8112 |
| `stop-launcher.ps1` | 停止后端 | - |
| `start-frontend.ps1` | 前端开发服务器 | 5174 |
| `tools/claude-agent-worker/start.ps1` | Claude Worker | 3031 |
| `tools/claude-agent-worker/stop.ps1` | 停止 Claude Worker | - |

### 后端启动

```powershell
# 推荐：一键启动脚本（仅停止 8112 端口进程，不影响其他 Java/Node 进程）
powershell -ExecutionPolicy Bypass -File start-launcher.ps1

# 手动启动
mvn package -pl launcher -am -DskipTests
java -jar launcher/target/launcher-1.0.0-SNAPSHOT.jar --spring.profiles.active=docker

# 停止
powershell -ExecutionPolicy Bypass -File stop-launcher.ps1
```

后端端口：8112，健康检查：`curl http://localhost:8112/actuator/health`

### 前端启动

```powershell
# 推荐：一键启动脚本
powershell -ExecutionPolicy Bypass -File start-frontend.ps1

# 手动启动
cd packages/navigator-frontend
pnpm install && pnpm dev
```

前端端口：5174，登录账号：root / root123

### 编译（不启动）

```powershell
# 后端编译
mvn compile test -pl launcher -am 

# 前端编译
cd packages/navigator-frontend && pnpm exec vite build
```

## 重要配置

- **LLM 配置**：`launcher/src/main/resources/application-docker.yml`（已 gitignore）
- **平台配置**：首次使用需在 `/#/setup` 配置 Git 提供者和 AI 模型
- **日志文件**：`logs/backend.log`、`logs/backend-error.log`

## Agent 编排核心 — A2A 统一发现与调用

Claude Worker Agent 是系统核心模块之一。所有 Agent（无论底层实现）统一通过 A2A 协议交互。

- **SPI 接口**: `A2aAgent`（执行）+ `A2aAgentProvider`（提供者模式），位于 `navigator-spi/spi/agent/`
- **统一注册**: `DefaultA2aAgentRegistry`（session-module）聚合所有 Provider
- **REST 端点**: `GET /api/v1/agents`（发现）、`POST /api/v1/agents/{id}/ask`（调用）
- **当前实现**: `ClaudeWorkerAgentProvider` → `ClaudeWorkerA2aAgent`（通过 `syncQuery` 同步执行）
- **扩展**: 新 addon 只需实现 `A2aAgentProvider` + `@Component`，自动注入 Registry

详见 [A2A Agent 架构文档](docs/a2a-agent-architecture.md)

## 开发规范

1. **JPA 单体设计**：Entity 间不用关联注解，用外键字段 + Service 层组合查询
2. **统一返回**：Controller 返回 `RX<T>`，成功 `RX.ok(data)`，失败 `RX.failA/B/C(msg)`
3. **接口参数**：使用 Form/DTO 而非 Entity，详见 `/form-design` 技能
4. **需求记录**：`docs/requirement-tracker/YYYY-QX/DD-需求简述.md`，用户确认后再开发
5. **先调研再实现**：集成外部系统（Claude Code SDK、OpenHands 等）的功能时，必须先调研目标系统的已有机制和内部数据结构，再设计实现方案。禁止在不了解底层机制的情况下"猜测式"实现。
6. **语义对齐**：实现涉及用户交互的功能前，先明确关键语义（操作是否产生新实体、是否等待用户确认、UI 状态如何变化），必要时主动向用户确认，避免多轮返工。
7. **SecurityConfig.java**：增加新的http端口注意更新权限
8. **前端构建验证**：修改完前端代码后，务必运行 `bash scripts/build-frontend.sh` 确保可以正确构建（含 TypeScript 类型检查）