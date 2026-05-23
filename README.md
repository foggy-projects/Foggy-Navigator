# Foggy Navigator

> 基于 LangChain4j 的个人 AI Agent 编排中枢

## 项目简介

Foggy Navigator 是一个个人 AI Agent 编排系统，将多种 AI 能力统一到一个平台中。基于 LangChain4j 和 Spring Boot 构建，支持多 Agent 协作、远程编程任务分发、插件化能力扩展。

### 核心能力

- **导师 Agent**：统一对话入口，引导用户、分派任务到专业 Agent
- **远程编程**：通过 Claude、Codex、Gemini Worker 在多台主机上执行编程任务
- **语义层服务**（计划中）：集成 foggy-data-mcp-bridge 进行 TM/QM 语义层建模
- **AI 分身**（计划中）：提供 AI 替身回答同事/朋友的常见问题
- **SSE 实时推送**：Agent 回复实时流式推送到前端

## 系统架构

```
Navigator Frontend (Vue 3)
        ↓ SSE + REST
   Spring Boot Launcher (port 8112)
   ├── agent-framework (LLM调用、Skill、工具执行)
   ├── session-module (会话、SSE)
   ├── tutor-agent (导师Agent、6个Skill)
   ├── addons/claude-worker-agent (远程 Claude Code)
   ├── addons/codex-worker-agent (远程 Codex)
   └── addons/gemini-worker-agent (远程 Gemini)
```

详细架构：[系统架构文档](./docs/00-system-overview.md)

## 模块结构

### 后端（Maven 多模块）

```
Foggy-Navigator/
├── navigator-common/           # 公共 DTO、Entity、工具类
├── navigator-spi/              # SPI 接口定义
├── agent-framework/            # Agent 核心框架
├── user-auth-module/           # JWT 认证
├── metadata-config-module/     # Skill 配置管理
├── metadata-query-module/      # 元数据查询服务
├── session-module/             # 会话管理 + SSE
├── tutor-agent/                # 导师 Agent
├── addons/claude-worker-agent/ # Claude Code 工人 Agent
├── addons/codex-worker-agent/  # Codex Worker Agent
├── addons/gemini-worker-agent/ # Gemini Worker Agent
└── launcher/                   # Spring Boot 启动器
```

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
- Node.js 18+ / pnpm（前端）

### 后端启动

```powershell
# 推荐：一键启动脚本
powershell -ExecutionPolicy Bypass -File start-launcher.ps1

# 手动启动
mvn clean package -pl launcher -am -DskipTests
java -jar launcher/target/launcher-1.0.0-SNAPSHOT.jar --spring.profiles.active=docker
```

后端端口：8112，健康检查：`http://localhost:8112/actuator/health`

### 前端启动

```bash
cd packages/navigator-frontend
pnpm install && pnpm dev
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
