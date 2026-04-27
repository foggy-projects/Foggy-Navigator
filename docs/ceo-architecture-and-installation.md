# Foggy Navigator 架构与安装说明

> 面向管理层、外部合作方和平台接入方的系统介绍文档。本文重点说明系统定位、能力架构、部署安装路径和对外开放能力；`tools/` 下各类 Worker 只做能力边界说明，不展开内部实现。

## 1. 系统定位

Foggy Navigator 是一个面向企业研发与业务自动化场景的多 Agent 编排平台。它把大模型、编程 Worker、会话协作、任务治理、工作区管理和开放接口统一到一个平台中，让用户可以通过 Web 控制台或 Open API 调用 AI Agent 执行任务。

当前系统主轴可以概括为三层：

1. **统一入口**：提供登录、会话、任务、Workers、跨项目任务、监控和设置页面。
2. **Agent 编排平台**：负责会话管理、任务分发、Agent 发现、模型配置、权限治理和实时事件推送。
3. **Worker 执行网络**：连接 Claude、Codex、Gemini、OpenHands、LangGraph 业务 Worker 等执行端，把具体任务落到远程机器、项目目录或业务流程中。

系统不是单一聊天机器人，而是一个可以管理多 Agent、多工作目录、多用户和多外部系统接入的 AI 执行平台。

## 2. 总体架构

```text
用户 / 管理员 / 外部系统
  -> Web 控制台 / Open API / Java SDK / Sharing Key

表现层
  -> packages/navigator-frontend
  -> 登录、Workers、会话、任务、跨项目、监控、设置、用户管理

平台服务层
  -> launcher
  -> 聚合 Spring Boot 后端模块，统一提供 REST、SSE、WebSocket 和 Open API

核心业务层
  -> session-module                 会话、消息、任务、SSE、共享访问
  -> user-auth-module               登录、用户、角色、API Key
  -> metadata-config-module         Git、LLM、凭证、记忆、Worker 等配置写入
  -> metadata-query-module          平台配置查询
  -> monitoring-module              监控事件与统计
  -> tutor-agent                    默认引导型 Agent

Agent 与执行层
  -> agent-framework                Agent 调用、工具、Skill、上下文编排底座
  -> addons/claude-worker-agent     Claude Worker 平台侧管理与任务路由
  -> addons/codex-worker-agent      Codex Worker 平台侧管理与任务路由
  -> addons/gemini-worker-agent     Gemini Worker 平台侧管理与任务路由
  -> addons/coding-agent            OpenHands 编码 Agent 集成
  -> addons/langgraph-biz-worker    LangGraph 业务流程 Worker 接入
  -> addons/task-assistant          任务通知和摘要助手

远程 Worker / 工具层
  -> tools/claude-agent-worker      Claude Code 远程执行端
  -> tools/codex-agent-worker       Codex 远程执行端
  -> tools/gemini-agent-worker      Gemini 远程执行端
  -> tools/claude-code-proxy        Claude Code 到 OpenAI 兼容模型的代理
  -> tools/llm-gateway              LLM 网关
  -> tools/code-server              远程代码服务辅助能力

基础设施层
  -> MySQL                          主业务数据存储
  -> Nginx                          前端静态资源与反向代理
  -> 本地或云端 LLM 服务            OpenAI 兼容模型、Claude、Gemini 等
```

## 3. 核心能力架构

### 3.1 Web 控制台

Web 控制台是平台的主要使用入口，面向研发用户、管理员和运营人员。主要功能包括：

| 功能入口 | 说明 |
|------|------|
| Workers | 管理远程 Worker、项目目录、worktree、文件浏览、Git diff、任务历史 |
| 会话 | 统一 Agent 对话入口，支持消息流、委派、分享和上下文延续 |
| 任务 | 从平台视角查看、取消、恢复、重连和同步 Agent / Worker 任务 |
| 跨项目 | 把复杂目标拆成多阶段、多目录、多 Agent 的可审核流程 |
| 设置 | 管理 Git Provider、LLM 模型、凭证、记忆、Worker 和助手配置 |
| 用户 | 管理用户、角色、状态和 API Key |
| 监控 | 查看运行事件、错误统计和通知链路 |

### 3.2 Agent 编排与任务治理

平台通过 `session-module` 和 `agent-framework` 建立统一的 Agent 调用面：

- 会话负责承接用户输入、消息历史和实时流式回复。
- 任务负责承接长耗时 Agent 执行、状态变更、取消、恢复和结果查询。
- Agent Resolver 负责发现不同来源的 Agent，并把请求路由到合适执行端。
- SSE 负责把会话消息、任务状态和通知实时推送到前端。

这让平台可以同时支持“人直接提问”和“系统通过 API 派发任务”两类场景。

### 3.3 Worker 执行网络

Worker 是执行任务的远程节点，通常运行在用户自己的机器、开发服务器或专用执行环境上。

当前 Worker 类型包括：

| Worker / 工具 | 定位 |
|------|------|
| Claude Agent Worker | 执行 Claude Code 编程任务，支持目录、文件、Git、终端、任务交互 |
| Codex Agent Worker | 执行 Codex SDK 任务，作为 OpenAI Codex 能力接入端 |
| Gemini Agent Worker | 执行 Gemini Agent 任务，作为 Google Gemini 能力接入端 |
| OpenHands / Coding Agent | 通过容器化环境执行编码任务 |
| LangGraph Biz Worker | 执行业务流程型 Agent 任务 |
| Claude Code Proxy | 可选代理，把 Claude Code 请求转发到 OpenAI 兼容模型后端 |
| LLM Gateway | 可选网关，统一管理模型访问入口 |

这些 Worker 不要求全部安装。实际部署时，可以根据企业使用的模型和任务类型选择需要的执行端。

### 3.4 平台治理能力

平台内置基础治理能力：

- 用户、角色、登录认证和 API Key。
- LLM 模型配置、连通性测试和 Agent 模型覆盖。
- Git Provider、凭证、记忆和 Worker 配置管理。
- Worker 健康检查、进程查看和指定进程终止。
- 监控事件、错误统计、SSE 通知和任务助手通知。

## 4. 对外提供的能力

Foggy Navigator 不只提供内部 Web UI，也提供面向外部系统的集成能力。

### 4.1 Open API

平台通过 `/api/v1/open` 提供外部接口，适合由上游业务系统、研发平台或自动化系统调用。当前覆盖：

- 第三方系统自助注册，生成租户和 API Key。
- Worker 注册、查询、编辑、删除和健康检查。
- 工作目录初始化、查询、删除和环境变量/文件更新。
- 员工 Provision，把员工、Worker、目录和 Agent 绑定成可使用的执行上下文。
- Agent 查询、问答、任务查询、任务取消、消息查询。
- Worker 进程查询和终止。

### 4.2 Java SDK

`navigator-open-sdk` 封装了 Open API，方便 Java 系统集成。SDK 按能力分为：

| SDK 模块 | 说明 |
|------|------|
| `WorkerApi` | Worker 管理和健康检查 |
| `DirectoryApi` | 目录初始化和目录配置 |
| `EmployeeApi` | 员工 Provision 和用户目录绑定 |
| `AgentApi` | Agent 查询、提问、等待任务完成、查询上下文消息 |

典型接入模式是：外部系统注册获取 API Key，然后使用 SDK 管理 Worker、初始化目录，并向指定 Agent 派发任务。

### 4.3 Sharing Key

Sharing Key 是轻量对外开放方式，适合临时授权外部用户或外部 AI 调用指定能力。它可以限制操作范围，例如：

- 发起问答。
- 查询任务。
- 回复任务。
- 查询会话。
- 查看任务产物。
- 只读访问指定文件或搜索文件。

### 4.4 实时事件与通知

平台通过 SSE 提供统一实时通道，用于：

- 会话消息流。
- 任务状态变更。
- 用户通知。
- 助手通知。
- 前端页面订阅关系管理。

## 5. 安装部署视图

安装可以分成两类：

1. **平台侧安装**：部署 Navigator 主服务、数据库、前端和基础配置。
2. **用户侧接入**：用户或执行机器安装 Worker，把本机能力注册到平台。

```text
平台服务器
  -> MySQL
  -> Foggy Navigator 后端
  -> Navigator Web 前端
  -> Nginx
  -> 模型、Git、凭证等平台配置

用户机器 / 执行机器
  -> Claude Worker / Codex Worker / Gemini Worker / OpenHands 等
  -> 可选 Claude Code Proxy
  -> 本地项目目录、Git 仓库、模型登录态或 API Key

外部系统
  -> Open API / Java SDK / Sharing Key
```

## 6. 平台侧安装步骤

以下步骤描述平台安装路径，重点是安装顺序和关键配置项。具体命令以仓库内脚本和运维环境为准。

### 6.1 准备运行环境

平台服务器需要准备：

| 依赖 | 用途 |
|------|------|
| Java 17+ | 运行 Spring Boot 后端 |
| Maven | 构建后端可执行 JAR |
| Node.js + pnpm | 构建和启动前端 |
| Docker / Docker Compose | 启动 MySQL、Nginx 等基础服务 |
| Git | 支撑工作目录和代码仓库操作 |
| 可访问的 LLM 服务 | OpenAI 兼容模型、Claude、Gemini 或企业内部模型 |

### 6.2 启动基础设施

开发或单机部署时，仓库提供了 Docker Compose 配置：

```powershell
cd docker
docker compose up -d mysql
```

默认 MySQL 信息：

| 项 | 默认值 |
|------|------|
| Host | `localhost` |
| Port | `13309` |
| Database | `coding_agent` |
| App User | `foggy` |
| App Password | `foggy@123` |

生产环境应替换默认密码，并使用正式数据库、备份和访问控制策略。

### 6.3 配置后端

后端由 `launcher` 模块聚合启动。关键配置包括：

| 配置项 | 说明 |
|------|------|
| 数据库连接 | MySQL URL、用户名、密码 |
| JWT Secret | 登录令牌签名密钥，生产环境必须替换默认值 |
| 凭证加密密钥 | 用于加密保存模型 Key、Git Token 等敏感信息 |
| ROOT 账号 | 首次登录和初始化使用 |
| LLM 配置 | 平台默认模型、API Key、Base URL |
| 外部访问地址 | Open API、Sharing Key 回调或外部展示地址 |

本地启动脚本会读取 `launcher/.env`，也可以通过 Spring 配置文件或环境变量覆盖默认配置。生产部署建议维护独立的 `application-docker.yml` 或等价配置，不直接使用开发默认值。

### 6.4 构建并启动后端

Windows 环境可以使用仓库脚本：

```powershell
powershell -ExecutionPolicy Bypass -File start-launcher.ps1
```

如果 JAR 已经构建过，可以跳过构建：

```powershell
powershell -ExecutionPolicy Bypass -File start-launcher.ps1 -SkipBuild
```

后端默认端口：

| 项 | 地址 |
|------|------|
| 后端服务 | `http://localhost:8112` |
| 健康检查 | `http://localhost:8112/actuator/health` |
| 后端日志 | `logs/backend.log`、`logs/backend-error.log` |

也可以直接使用 Maven 和 Java 运行：

```powershell
mvn clean package -pl launcher -am -DskipTests
java -jar launcher/target/launcher-1.0.0-SNAPSHOT.jar --spring.profiles.active=docker
```

### 6.5 构建并启动前端

开发或演示环境可以启动 Vite：

```powershell
powershell -ExecutionPolicy Bypass -File start-frontend.ps1
```

默认访问地址：

| 项 | 地址 |
|------|------|
| 前端开发服务 | `http://localhost:5174` |
| 默认账号 | `root / root123` |

生产或内网部署通常先构建静态资源，再由 Nginx 提供访问：

```powershell
powershell -ExecutionPolicy Bypass -File start-frontend.ps1 -BuildOnly
cd docker
docker compose up -d nginx
```

### 6.6 初始化平台配置

首次访问前端后，进入初始化或设置页面完成：

1. 配置 Git Provider。
2. 配置 LLM 模型和连通性。
3. 配置凭证、记忆和默认模型策略。
4. 创建或导入用户。
5. 创建 Worker Token 或注册 Worker。
6. 验证 Workers、会话、任务和监控页面是否可用。

## 7. 用户侧 Worker 安装与接入

用户侧安装的目标是把用户机器或执行服务器变成平台可调度的 Worker。一般由管理员先准备 Token，用户再安装并启动 Worker。

### 7.1 Claude Agent Worker

Claude Agent Worker 是当前最主要的远程编程执行端。

Linux / macOS：

```bash
curl -sSL https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-worker/install.sh | bash
```

Windows PowerShell 管理员：

```powershell
irm https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-worker/install.ps1 | iex
```

安装后主要配置位于 `~/.claude-worker/.env`：

| 配置项 | 说明 |
|------|------|
| `AGENT_WORKER_PORT` | Worker 本地服务端口，默认 `3031` |
| `AGENT_WORKER_WORKER_NAME` | 显示在平台上的机器名称 |
| `AGENT_WORKER_WORKER_TOKEN` | 平台分配的 Worker Token |
| `AGENT_WORKER_ALLOWED_CWDS` | 允许平台访问的工作目录白名单 |
| Claude 登录态或 API 配置 | 可使用 Claude 订阅登录、Anthropic API Key 或本地 Proxy |

启动：

```bash
claude-worker start
claude-worker status
```

启动后，平台会通过配置的 Worker 地址和 Token 进行健康检查，并在 Workers 页面展示该节点。

### 7.2 Claude Code Proxy

Claude Code Proxy 是可选组件。它用于把 Claude Code 的请求转发到 OpenAI 兼容模型后端，例如企业内部模型、智谱、通义、Ollama 等。

适用场景：

- 用户没有直接使用 Anthropic API 的条件。
- 企业希望统一模型出口。
- 希望让 Claude Code 使用 OpenAI 兼容模型服务。

安装后主要配置位于 `~/.claude-code-proxy/.env`，核心是：

| 配置项 | 说明 |
|------|------|
| `OPENAI_API_KEY` | 后端模型服务 Key |
| `OPENAI_BASE_URL` | OpenAI 兼容接口地址 |
| `BIG_MODEL` / `MIDDLE_MODEL` / `SMALL_MODEL` | Claude 模型到实际模型的映射 |
| `PORT` | Proxy 端口，默认 `8082` |

启动：

```bash
claude-code-proxy start
```

Claude Worker 可以把 `AGENT_WORKER_ANTHROPIC_BASE_URL` 指向本地 Proxy，从而通过 Proxy 使用第三方模型。

### 7.3 Codex / Gemini / 其他 Worker

`tools/codex-agent-worker` 和 `tools/gemini-agent-worker` 是 TypeScript Worker 服务，用于接入 Codex 和 Gemini 执行能力。它们通常由平台运维或研发团队按需部署，不要求普通用户默认安装。

通用接入模式与 Claude Worker 类似：

1. 安装运行环境和依赖。
2. 配置 Worker 名称、平台地址、Token、端口和模型凭证。
3. 启动 Worker 服务。
4. 在平台设置或 Workers 页面完成注册与健康检查。
5. 在任务或会话中选择对应 Agent 执行任务。

## 8. 用户使用流程

安装完成后，用户典型使用路径如下：

1. 登录 Navigator Web 控制台。
2. 在设置页确认模型、凭证和 Worker 可用。
3. 在 Workers 页面选择 Worker 和项目目录。
4. 初始化或同步项目目录。
5. 发起编程任务、业务任务或跨项目任务。
6. 在任务历史、会话页或任务看板中查看执行状态。
7. 需要时通过文件浏览、Git diff、Git history 审查结果。
8. 对外部系统场景，使用 Open API、SDK 或 Sharing Key 派发任务和查询结果。

## 9. 部署形态建议

### 9.1 演示 / 单机部署

适合内部演示、验证和小团队试用：

- Navigator 后端、前端、MySQL 部署在同一台服务器。
- Worker 可以部署在同机或用户机器。
- 使用默认脚本快速启动。
- 模型 Key 和 Git Token 通过设置页配置。

### 9.2 团队级部署

适合研发团队共享使用：

- Navigator 主服务部署在固定服务器。
- MySQL 使用独立实例并启用备份。
- 前端通过 Nginx 暴露统一访问域名。
- Worker 分布在多台开发机或专用执行机。
- 使用统一模型出口和企业 Git Provider。

### 9.3 企业集成部署

适合对接业务系统或研发平台：

- 主服务提供稳定的 Open API 地址。
- 外部系统通过 API Key 或 SDK 接入。
- 通过 Employee Provision 绑定用户、目录和 Agent。
- 通过 Sharing Key 做临时授权。
- 对 Worker、任务、会话、凭证和日志建立统一治理策略。

## 10. 安全与运维关注点

上线前建议重点确认：

| 关注点 | 建议 |
|------|------|
| 默认密码 | 修改 ROOT、MySQL、GitLab 或其他基础服务默认密码 |
| JWT Secret | 使用生产级随机密钥 |
| 凭证加密 | 配置正式 `NAVIGATOR_CREDENTIAL_KEY` 和 `NAVIGATOR_CREDENTIAL_SALT` |
| Worker Token | 按机器或用户分配，不混用 |
| 目录白名单 | Worker 只开放必要项目目录 |
| API Key | 为外部系统单独发放，定期轮换 |
| 网络访问 | 后端、数据库、Worker 端口按最小暴露原则配置 |
| 日志 | 后端日志、Worker 日志、任务日志需要纳入保留和脱敏策略 |
| 数据备份 | MySQL 和关键配置需要定期备份 |

## 11. 关键端口

| 服务 | 默认端口 |
|------|------|
| Navigator 后端 | `8112` |
| Navigator 前端开发服务 | `5174` |
| Nginx 前端访问 | `80` |
| MySQL | `13309` |
| Claude Agent Worker | `3031` |
| Claude Code Proxy | `8082` |

## 12. 文档参考

- [系统架构概览](./00-system-overview.md)
- [功能架构说明](./02-modules/functional-architecture.md)
- [工作区与 Worker 中心](./02-modules/worker-workspace-center.md)
- [监控、通知与开放集成](./02-modules/observability-notification-integration.md)
- [员工工具安装指南](./employee-install-guide.md)
- [Docker 环境说明](../docker/README.md)
