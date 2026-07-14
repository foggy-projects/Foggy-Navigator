# Foggy Navigator

> 多 Worker 远程编程工作台与 Session/Task/A2A 治理平台

## 项目简介

Foggy Navigator 当前是面向内部开发协作的多 Worker 远程编程工作台。平台基于 Spring Boot、Vue 和多类 Worker 运行时，统一治理 Session、Task、A2A、Provider、工作目录和上游集成；当前 Addon 是编译期模块化单体，不是动态插件平台。

### 核心能力

- **Workers 工作台**：统一发起和继续 Claude、Codex、Gemini、LangGraph Biz 等任务会话
- **远程编程**：通过 Claude、Codex、Gemini Worker 在多台主机上执行编程任务
- **Session / Task / A2A 治理**：统一分发、查询、恢复、取消、审批和跨项目协作
- **工作区能力**：文件、Git、工作目录、worktree、终端与代码服务集成
- **业务与上游集成**：Business Agent、Open SDK、ClientApp 与 upstream user 链路
- **SSE 实时推送**：Agent 回复实时流式推送到前端

## 系统架构

```
Navigator Frontend (Vue 3)
        ↓ SSE + REST
   Spring Boot Launcher (port 8112)
   ├── agent-framework (LLM调用、Skill、工具执行)
   ├── session-module (Session、Task、A2A、SSE)
   ├── business-agent-module (ClientApp、BusinessTask/Function、Worker Gateway)
   ├── addons/claude-worker-agent (远程 Claude Code)
   ├── addons/codex-worker-agent (远程 Codex)
   ├── addons/gemini-worker-agent (远程 Gemini)
   └── addons/langgraph-biz-worker (LangGraph Biz Worker)
```

详细架构：[系统架构文档](./docs/00-system-overview.md)

当前部署以可信内网 dev/internal 使用为主。未来外部运行面必须通过显式、默认关闭的模式开关启用，并满足 ClientApp/upstream user、task-scoped token、Worker readiness、工具边界和审计门禁。

## 模块结构

### 后端（Maven 多模块）

```
Foggy-Navigator/
├── navigator-common/           # 公共 DTO、Entity、工具类
├── navigator-spi/              # SPI 接口定义
├── agent-framework/            # Agent 核心框架
├── user-auth-module/           # JWT 认证
├── metadata-config-module/     # 平台配置管理（Git、LLM、凭据等）
├── session-module/             # 会话管理 + SSE
├── addons/claude-worker-agent/ # Claude Code 工人 Agent
├── addons/codex-worker-agent/  # Codex Worker Agent
├── addons/gemini-worker-agent/ # Gemini Worker Agent
└── launcher/                   # Spring Boot 启动器
```

旧 `metadata-query-module` 已在 1.4.2 dev 阶段物理退役；`metadata-config-module` 继续作为活跃的平台配置能力，不属于该退役范围。

### 前端（pnpm workspace）

```
packages/
├── foggy-chat/                 # 聊天组件库
└── navigator-frontend/         # Navigator 前端应用
```

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.8+
- MySQL 8.0+
- Node.js 22.23.1 / pnpm 10.34.5（通过 Corepack 管理）

### 后端启动

```powershell
# 推荐：一键启动脚本
powershell -ExecutionPolicy Bypass -File scripts/start-launcher.ps1

# 手动启动
mvn clean package -pl launcher -am -DskipTests
java -jar launcher/target/launcher-1.0.0-SNAPSHOT.jar --spring.profiles.active=docker
```

后端端口：8112，健康检查：`http://localhost:8112/actuator/health`

### 前端启动

```bash
nvm use
corepack enable
pnpm install --frozen-lockfile
pnpm --filter @foggy/navigator-frontend dev
```

前端端口：5174，登录账号：root / root123

### 配置

- **LLM 配置**：`launcher/src/main/resources/application-docker.yml`
- **关键参数**：`agent.llm.openai.api-key`、`agent.llm.openai.base-url`

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端 | Spring Boot 3.x, LangChain4j, JPA, MySQL |
| 认证 | JWT (jjwt) |
| 前端 | Vue 3, Element Plus, Pinia, Vite |
| 推送 | SSE (Server-Sent Events) |
| 容器 | Docker / Docker Compose |
| 测试 | JUnit 5, Mockito, Vitest |

## 文档

完整文档请查看 [docs/README.md](./docs/README.md)

## 许可证

MIT License
