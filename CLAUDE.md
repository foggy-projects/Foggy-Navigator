# Claude AI 开发指南

Foggy Navigator - 基于 LangChain4j 的个人 AI Agent 编排中枢。

## 项目称呼

- 用户或上下文中提到 `navi`、`Navi`、`NAVI` 时，默认指当前项目 Foggy Navigator，也就是本仓库/当前工作区。
- 如同时出现其他 Navigator 环境，先按工作区路径、端口和进程命令行确认具体目标，避免误操作其他环境。

## 工作区与上游联调

- 当前 Navigator 工作区：`/home/sa/workspace/Foggy-Navigator`。
- 当前同级上游：`/home/sa/workspace/tms-x3`、`/home/sa/workspace/foggy-world-sim`、`/home/sa/workspace/foggy-data-mcp`。
- 默认只修改当前 Navigator 仓库；需要 TMS、SIM 或 Foggy Data MCP 配合时，优先给出 issue / handoff / 配置步骤，除非用户明确要求跨仓改动。
- 本机上游拓扑、凭据边界、WSL Biz Worker、`dev-kvm-x3` 发布注意事项见 [本机上游联调说明](docs/dev-specs/local-upstream-collaboration.md)。
- 不把明文 API key、admin key、credential secret 写入仓库文档；上游本地配置以各项目 `.navigator/upstream.env` 等本机文件为准。

## 模块结构

模块清单以工程配置为准，本文只保留当前协作时最常用的分层口径：

- **Maven 模块**：以 [pom.xml](./pom.xml) 的 `<modules>` 为准。
- **前端 workspace**：以 [pnpm-workspace.yaml](./pnpm-workspace.yaml) 和 `packages/*/package.json` 为准。
- **Worker / 辅助服务**：以 `tools/*/start.ps1`、`tools/*/.env.example` 和具体 README 为准。
- **系统级架构说明**：以 [docs/00-system-overview.md](./docs/00-system-overview.md) 和 [docs/02-modules/functional-architecture.md](./docs/02-modules/functional-architecture.md) 为当前口径。

### 后端分层

| 层级 | 当前模块 |
|------|------|
| 聚合启动 | `launcher` |
| 底座与 SPI | `navigator-common`、`navigator-spi`、`agent-framework` |
| 核心业务 | `session-module`、`business-agent-module`、`user-auth-module`、`metadata-config-module`、`metadata-query-module`、`monitoring-module` |
| Worker / Agent addon | `addons/claude-worker-agent`、`addons/codex-worker-agent`、`addons/gemini-worker-agent`、`addons/langgraph-biz-worker`、`addons/echo-agent`、`addons/task-assistant` |
| 对外 SDK / 本地 BFF | `navigator-open-sdk`、`tools/navigator-chat-observer-bff` |

`addons/code-review-agent` 目前存在源码目录，但未纳入根 `pom.xml`，开发前先确认是否仍为实验模块或待接入模块。

旧独立“会话”入口及其配套 `tutor-agent` 模块已移除；不要再把它当作当前主线模块设计新能力。

### 前端与移动端

| 包 | 说明 |
|------|------|
| `packages/navigator-frontend` | Navigator 管理台与主工作台（Vue 3 + Element Plus） |
| `packages/foggy-chat` | 聊天组件库 |
| `packages/foggy-chat-core` | 跨 Web / Mobile 复用的聊天核心能力 |
| `packages/navigator-chat-widget` | 可嵌入上游系统的聊天组件 |
| `packages/foggy-mobile` | uni-app 移动端 |

### 工具与 Worker

| 目录 | 说明 |
|------|------|
| `tools/claude-agent-worker` | Claude Worker Python 服务 |
| `tools/codex-agent-worker` | Codex Worker TypeScript 服务 |
| `tools/gemini-agent-worker` | Gemini Worker TypeScript 服务 |
| `tools/langgraph-biz-worker` | LangGraph Biz Worker Python 服务 |
| `tools/mock-llm-service` | Mock LLM 端点 |
| `tools/navigator-upstream`、`tools/navigator-upstream-cli` | 上游接入工具与 CLI |
| `tools/code-server`、`tools/foggy-monitor` | 开发辅助与监控工具 |
| `tools/claude-code-proxy`、`tools/llm-gateway`、`tools/llm-recorder-proxy` | LLM / Claude Code 调试与代理工具 |

## 项目启动

### 启动脚本一览

| 脚本 | 说明 | 端口 |
|------|------|------|
| `scripts/local-dev-stack.sh` | Linux/WSL 本地栈（后端 + Claude/Codex + Biz Worker） | 8112/3031/3051/3061/3161 |
| `scripts/local-dev-stack.ps1` | Windows 本地栈（后端 + Claude/Codex/Gemini + Biz Worker） | 8112/3031/3051/3071/3061/3161 |
| `scripts/start-launcher.sh` | 后端（编译+启动，Linux/WSL） | 8112 |
| `scripts/start-launcher.ps1` | 后端（编译+启动） | 8112 |
| `scripts/start-launcher-mock.ps1` | 后端（Mock LLM 模式） | 8112 |
| `scripts/stop-launcher.sh` | 停止后端（Linux/WSL） | - |
| `scripts/stop-launcher.ps1` | 停止后端 | - |
| `scripts/start-frontend.sh` | 前端开发服务器（Linux/WSL） | 5174 |
| `scripts/start-frontend.ps1` | 前端开发服务器 | 5174 |
| `tools/claude-agent-worker/start.ps1` | Claude Worker | 3031 |
| `tools/claude-agent-worker/stop.ps1` | 停止 Claude Worker | - |
| `tools/codex-agent-worker/start.ps1` | Codex Worker | 3051 |
| `tools/codex-agent-worker/stop.ps1` | 停止 Codex Worker | - |
| `tools/gemini-agent-worker/start.ps1` | Gemini Worker | 3071 |
| `tools/gemini-agent-worker/stop.ps1` | 停止 Gemini Worker | - |
| `tools/langgraph-biz-worker/start.ps1` | LangGraph Biz Worker（Windows 本地默认） | 3061 |
| `tools/langgraph-biz-worker/restart-wsl-3161.ps1` | LangGraph Biz Worker（WSL / 上游联调常用） | 3161 |
| `tools/langgraph-biz-worker/stop.ps1` | 停止 LangGraph Biz Worker | - |

### Worker 更新边界

当前工作区路径为 `/home/sa/workspace/Foggy-Navigator`。当需要更新、重启或排查 Worker 时，只处理以下实例：

- 当前工作区内的 Worker：`/home/sa/workspace/Foggy-Navigator/tools/...`
- WSL 中对应的 Worker，例如 `/home/navigator/.codex-worker` 或 3161 Biz Worker

不要停止、重启或升级其他工作区的 Worker。端口号只能作为线索，不能作为归属依据；如果端口或进程归属不明确，先用进程命令行确认工作区路径，再执行操作。当前同级上游目录也在 `/home/sa/workspace` 下，跨仓操作前必须确认用户授权。

本机联调时注意区分两类 Biz Worker：

- `tools/langgraph-biz-worker/start.ps1` 默认启动 Windows 本地 3061。
- `application.yml` 默认 `BUSINESS_AGENT_DEV_SYNC_WORKER_URL=http://localhost:3061`。
- `scripts/start-launcher.ps1` 未显式设置 `BUSINESS_AGENT_DEV_SYNC_WORKER_URL` 时，会默认把 Skill 同步指向 `http://127.0.0.1:3161`。
- `scripts/local-dev-stack.sh` 会同时管理本地 3061 和 WSL 3161；需要固定同步目标时显式设置 `BUSINESS_AGENT_DEV_SYNC_WORKER_URL`。

### 后端启动

```powershell
# 推荐：一键启动脚本（仅停止 8112 端口进程，不影响其他 Java/Node 进程）
powershell -ExecutionPolicy Bypass -File scripts/start-launcher.ps1

# 手动启动
mvn package -pl launcher -am -DskipTests
java -jar launcher/target/launcher-1.0.0-SNAPSHOT.jar --spring.profiles.active=docker

# 停止
powershell -ExecutionPolicy Bypass -File scripts/stop-launcher.ps1
```

后端端口：8112，健康检查：`curl http://localhost:8112/actuator/health`

### 前端启动

```powershell
# 推荐：一键启动脚本
powershell -ExecutionPolicy Bypass -File scripts/start-frontend.ps1

# 手动启动
cd packages/navigator-frontend
pnpm install && pnpm dev
```

前端端口：5174，登录账号：root / root123
后端 root 默认值来自 `launcher/.env.example` / `application.yml`，现有数据库或本机 `.env` 可覆盖。

### 编译（不启动）

```powershell
# 后端测试编译
mvn test -pl launcher -am

# 前端编译
bash scripts/build-frontend.sh
```

## 重要配置

- **LLM 配置**：`launcher/src/main/resources/application-docker.yml`（已 gitignore）
- **平台配置**：首次使用需在 `/#/settings` 配置 Git 提供者和 AI 模型
- **日志文件**：`logs/backend.log`、`logs/backend-error.log`

## Agent 编排核心

所有 Agent（无论底层实现）统一通过 A2A / provider 路由接入会话与任务分发链路。

- **SPI 接口**: `A2aAgent`（执行）+ `A2aAgentProvider`（提供者模式），位于 `navigator-spi/spi/agent/`
- **统一注册**: `DefaultA2aAgentRegistry`（session-module）聚合所有 Provider
- **统一解析**: `UnifiedAgentResolver`（session-module）按 `agentId` / `providerType` / `modelConfigId` 解析目标
- **统一分派**: `TaskDispatchFacade`（session-module）是 Worker / Agent 任务入口，支持 A2A 路由和 Direct Provider 路由
- **会话绑定**: `SessionBindingService`（session-module）管理 Session ↔ Agent 绑定生命周期，绑定后不可切换
- **REST 端点**: `GET /api/v1/agents`（发现）、`POST /api/v1/agents/{id}/ask`（调用）、`POST /api/v1/tasks`（任务分派）
- **当前 Provider**: Claude Worker、Codex Worker、Gemini Worker、LangGraph Biz Worker、Echo Agent
- **三个核心语义**（需求 26）：`logicalAgentId`（逻辑 Agent）、`providerType`（执行后端）、`modelConfigId`（模型配置）— 禁止混淆
- **扩展**: 新 addon 只需实现 `A2aAgentProvider` + `@Component`，自动注入 Registry

详见 [A2A Agent 架构文档](docs/a2a-agent-architecture.md)

## 开发规范

1. **JPA 单体设计**：Entity 间不用关联注解，用外键字段 + Service 层组合查询
2. **统一返回**：Controller 返回 `RX<T>`，成功 `RX.ok(data)`，失败 `RX.failA/B/C(msg)`
3. **接口参数**：使用 Form/DTO 而非 Entity，详见 `/form-design` 技能
4. **需求记录**：所有新增需求、缺陷、重构、延期事项统一记录到 `docs/version-tracker/<version>/NN-事项简述.md`，按版本号跟踪，用户确认后再开发；`docs/requirement-tracker/` 仅保留为历史季度制归档，禁止再写入新事项
5. **先调研再实现**：集成外部系统（Claude Code SDK、Codex SDK、Gemini CLI 等）的功能时，必须先调研目标系统的已有机制和内部数据结构，再设计实现方案。禁止在不了解底层机制的情况下"猜测式"实现。
6. **语义对齐**：实现涉及用户交互的功能前，先明确关键语义（操作是否产生新实体、是否等待用户确认、UI 状态如何变化），必要时主动向用户确认，避免多轮返工。
7. **SecurityConfig.java**：增加新的http端口注意更新权限
8. **前端构建验证**：修改完前端代码后，务必运行 `bash scripts/build-frontend.sh` 确保可以正确构建（含 TypeScript 类型检查）
9. **Vite HMR 缓存陷阱**：修改 Vue 文件后如果浏览器行为与源码不符（如字段未传递、逻辑未生效），**首先怀疑 Vite HMR 缓存过期**，而非代码错误。排查步骤：
   - 用 Playwright 读取浏览器实际运行的函数源码（`comp.setupState.xxx.toString()`），与磁盘源文件对比
   - 如果不一致，执行：`Remove-Item -Recurse -Force packages/navigator-frontend/node_modules/.vite`，然后刷新页面
   - 若仍不一致，重启 Vite dev server（停止 5174 端口进程后执行 `scripts/start-frontend.ps1`）
10. **会话 modelConfigId 绑定原则**：一个会话使用 `modelConfigId` 创建后（创建新会话必须指定 modelConfigId），该值永远固定，除非用户主动修改。会话内可以切换 `model`（如 opus → sonnet），但不能自动切换 `modelConfigId`（即 API 凭证/订阅不变）。
11. **测试产物落点规范**：根目录禁止新增临时测试产物（如 `*.yaml`、`*.yml`、`*.png`、`.tmp-*.log`、`.tmp-*.json`）。临时调试/回归产物统一写入 `temp/test-artifacts/<任务或日期>/`，该目录仅用于本地暂存并保持 git ignore；需要长期保留的验收证据，写入对应版本目录下的 `docs/version-tracker/<version>/evidence/`。编写 Playwright、脚本或临时验证命令时，必须显式指定输出目录，避免再次把大量测试文件落到仓库根目录。
12. **异常与事务治理规范**：后端可预期业务异常、资源 not-ready、readiness/preflight blocker 不得被裸抛成 HTTP 500。Service 层需要区分系统缺陷与业务/资源状态异常；会被外层捕获并转换为 structured response 的事务方法，优先使用统一的 `@ReadinessTransactional` 或等价 `noRollbackFor` 策略，self-healing/readiness 聚合入口优先隔离大外层事务，避免 `UnexpectedRollbackException` 覆盖真实响应。详细规范见 [后端异常处理与事务治理规范](docs/dev-specs/exception-handling-and-transaction-governance.md)。
13. **文档输出精简**：输出需求、方案、进度、验收等文档时，减少不必要的铺垫、客套和重复说明；优先保留结论、约束、证据和下一步，尽量做到言简意赅。
