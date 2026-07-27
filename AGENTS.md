# Foggy Navigator Repository Guidance

Foggy Navigator 是基于 LangChain4j 的个人 AI Agent 编排中枢。本文件是仓库根目录唯一的 Agent 记忆与开发指引；开始仓库工作前先阅读本文件，不再维护根级 `CLAUDE.md`。

## Project Sources of Truth

- 用户或上下文中提到 `navi`、`Navi`、`NAVI` 时，默认指当前 Foggy Navigator 仓库与工作区。
- 如同时存在其他 Navigator 环境，先按工作区路径、进程命令行和配置确认目标；端口只能作为线索，不能证明实例归属。
- `pom.xml`、`pnpm-workspace.yaml`、package manifest、模块级指引和当前代码是工程事实源；文档过时时以这些内容为准。
- Maven 模块以根 `pom.xml` 的 `<modules>` 为准；前端 workspace 以 `pnpm-workspace.yaml` 和 `packages/*/package.json` 为准；Worker / 辅助服务以各自启动脚本、`.env.example` 和 README 为准。
- 当前架构文档以 [docs/README.md](docs/README.md) 的索引为准，优先阅读 [系统架构概览](docs/00-system-overview.md) 和 [功能架构说明](docs/02-modules/functional-architecture.md)；明确标记为历史的文档仅作背景参考。
- 产品代码、SDK 示例或历史交付证据中的 `CLAUDE.md` 可能指 Claude Worker 管理的外部工作目录，不代表本仓库需要恢复根级 `CLAUDE.md`。

## Scope and Workspace Safety

- 当前工作区：`/home/sa/workspace/Foggy-Navigator`。
- 当前同级上游：`/home/sa/workspace/tms-x3`、`/home/sa/workspace/foggy-world-sim`、`/home/sa/workspace/foggy-data-mcp`。
- 默认只修改当前 Navigator 仓库。需要 TMS、SIM 或 Foggy Data MCP 配合时，优先给出 issue、handoff 或配置步骤；除非用户明确授权，不修改同级仓库。
- 当前目录处于 `main` 分支时，除非用户明确许可，不得通过 `git switch`、`git checkout` 等方式切换到其他分支开展工作。需要新分支开发时，应在新目录独立 clone 仓库，并在该 clone 中创建或检出目标分支，保持当前 `main` 工作目录不变。
- 保留现有 dirty worktree。不得回滚、覆盖、格式化或夹带与当前任务无关的用户改动。
- 停止、重启、升级或发布 Worker 前，必须通过进程命令行和工作区路径确认归属；不得仅凭端口操作。
- 不得把明文 API key、admin key、credential secret 或本地密钥写入 tracked files。上游本地配置使用各项目 `.navigator/upstream.env` 等本机文件。
- 根目录不得新增临时测试产物，例如 `*.yaml`、`*.yml`、`*.png`、`.tmp-*.log` 或 `.tmp-*.json`。临时调试与回归产物写入 `temp/test-artifacts/<task-or-date>/` 并保持 git ignored；Playwright、脚本和临时验证命令必须显式指定输出目录。长期验收证据写入对应 `docs/version-tracker/<version>/evidence/`。
- 本机上游拓扑、凭据边界、WSL Biz Worker 与 `dev-kvm-x3` 发布注意事项见 [本机上游联调说明](docs/dev-specs/local-upstream-collaboration.md)。

## Current Module Topology

模块清单以工程配置为准，本节只保留高频分层口径。

### Backend

| 层级 | 当前模块 |
|------|------|
| 聚合启动 | `launcher` |
| 底座与 SPI | `navigator-common`、`navigator-spi`、`agent-framework` |
| 核心业务 | `session-module`、`business-agent-module`、`user-auth-module`、`metadata-config-module` |
| Worker / Agent addon | `addons/claude-worker-agent`、`addons/codex-worker-agent`、`addons/gemini-worker-agent`、`addons/langgraph-biz-worker`、`addons/task-assistant` |
| 对外 SDK / 本地 BFF | `navigator-open-sdk`、`tools/navigator-chat-observer-bff` |

以下旧模块已退出当前主线，不得重新当作当前模块或启动前置：

- 实验性 `addons/code-review-agent` 已移除。若恢复 GitLab MR 自动审查，按新集成重新完成消费者、鉴权和运行态审计。
- 旧独立“会话”入口及配套 `tutor-agent` 已移除。
- 旧自研 `monitoring-module` 与 `tools/foggy-monitor` 已移除。RabbitMQ 和旧 Monitoring API/页面不是启动前置；应用日志、健康检查、有限指标和安全审计仍须保留。
- 旧 `metadata-query-module` 已物理退役，不得加回根 reactor、`launcher` 或当前模块清单。`metadata-config-module` 仍是活跃平台配置能力；LangGraph FSScript 不在退役范围。
- 旧 `addons/echo-agent` 已从源码、根 reactor 和 `launcher` 退役。A2A discovery/resolve/send/query/cancel 回归由 `session-module` 的 test-only 内存 fixture 覆盖；`LocalEchoBusinessFunctionAdapterInvoker` 是独立 BusinessFunction 本地适配能力，继续保留。

### Frontend and Mobile

| 包 | 说明 |
|------|------|
| `packages/navigator-frontend` | Navigator 管理台与主工作台（Vue 3 + Element Plus） |
| `packages/foggy-chat` | 聊天组件库 |
| `packages/foggy-chat-core` | 跨 Web / Mobile 复用的聊天核心能力 |
| `packages/navigator-chat-widget` | 可嵌入上游系统的聊天组件 |
| `packages/foggy-mobile` | uni-app 移动端 |

### Tools and Workers

| 目录 | 说明 |
|------|------|
| `tools/claude-agent-worker` | Claude Worker Python 服务 |
| `tools/codex-agent-worker` | Codex Worker TypeScript 服务 |
| `tools/codex-app-server-worker` | 独立 Codex app-server Worker，默认 3062，作为 dark/canary 目标 runtime |
| `tools/gemini-agent-worker` | Gemini Worker TypeScript 服务 |
| `tools/langgraph-biz-worker` | LangGraph Biz Worker Python 服务 |
| `tools/mock-llm-service` | Mock LLM 端点 |
| `tools/navigator-upstream`、`tools/navigator-upstream-cli` | 上游接入工具与 CLI |
| `tools/code-server` | 远程代码服务辅助能力 |
| `tools/claude-code-proxy`、`tools/llm-gateway`、`tools/llm-recorder-proxy` | LLM / Claude Code 调试与代理工具 |

## Startup and Runtime Operations

### Main Scripts

| 脚本 | 说明 | 端口 |
|------|------|------|
| `scripts/local-dev-stack.sh` | Linux/WSL 本地栈（后端 + Claude/Codex + Biz Worker） | 8112/3031/3051/3061/3161 |
| `scripts/local-dev-stack.ps1` | Windows 本地栈（后端 + Claude/Codex/Gemini + Biz Worker） | 8112/3031/3051/3071/3061/3161 |
| `scripts/start-launcher.sh` | 后端，Linux/WSL | 8112 |
| `scripts/start-launcher.ps1` | 后端 | 8112 |
| `scripts/start-launcher-mock.ps1` | 后端 Mock LLM 模式 | 8112 |
| `scripts/stop-launcher.sh` / `scripts/stop-launcher.ps1` | 停止后端 | - |
| `scripts/start-frontend.sh` / `scripts/start-frontend.ps1` | 前端开发服务器 | 5174 |
| `tools/claude-agent-worker/start.ps1` / `stop.ps1` | Claude Worker | 3031 |
| `tools/codex-agent-worker/start.ps1` / `stop.ps1` | Codex Worker | 3051 |
| `tools/codex-app-server-worker/start.ps1` / `stop.ps1` | Codex App Server Worker | 3062 |
| `tools/gemini-agent-worker/start.ps1` / `stop.ps1` | Gemini Worker | 3071 |
| `tools/langgraph-biz-worker/start.ps1` | Windows 本地 LangGraph Biz Worker | 3061 |
| `tools/langgraph-biz-worker/restart-wsl-3161.ps1` | WSL / 上游联调 LangGraph Biz Worker | 3161 |
| `tools/langgraph-biz-worker/stop.ps1` | 停止 LangGraph Biz Worker | - |

### Worker Ownership Boundary

只处理以下实例：

- 当前工作区 `/home/sa/workspace/Foggy-Navigator/tools/...` 下的 Worker。
- WSL 中与当前工作区对应的 Worker，例如 `/home/navigator/.codex-worker` 或 3161 Biz Worker。

不得停止、重启或升级其他工作区的 Worker。归属不明确时，先检查进程命令行中的工作区路径。

本机 Codex / Claude Worker 还需区分两个 WSL 所有权域：

- 当前工作 WSL 中的 `/home/sa/.codex-worker`（通常为 3053）和
  `/home/sa/.claude-worker` 是独立实例；没有用户当轮明确授权时不得停止、重启或
  升级。
- 宿主的 `Ubuntu-24.04` 发行版承载 3151 Codex SDK Worker，安装目录为
  `/home/navigator/.codex-worker`，运行用户为 `navigator`。操作前同时核对该目录的
  `VERSION`、`.env` 中 `CODEX_WORKER_PORT=3151`、listener PID 的 cwd 和
  `http://127.0.0.1:3151/health`；Windows `wslrelay.exe` 只能证明端口转发，不能单独
  证明 Worker 归属。
- 3151 原地升级使用目标安装目录自带脚本：
  `CODEX_WORKER_HOME=/home/navigator/.codex-worker
  /home/navigator/.codex-worker/update-worker.sh
  /tmp/codex-worker-<version>-linux.tar.gz`，并以 `navigator` 用户在
  `Ubuntu-24.04` 内执行。发布包可先经
  `\\wsl.localhost\Ubuntu-24.04\tmp\` 复制进去；升级后必须复核版本、健康、端口、
  cwd、termination identity 和 replay ledger。安装器会保留 `.env` 的业务配置，
  同时可能规范化 termination 配置行，因此不要用 `.env` 字节级 digest 不变作为
  升级成功条件。
- 若当前 WSL 的 Windows interop registration 暂时不可用，可通过
  `/init /mnt/c/Windows/System32/wsl.exe wsl.exe ...` 调用宿主 `wsl.exe`；仍须显式
  指定 `-d Ubuntu-24.04`，避免误操作当前 WSL 的 `/home/sa` Worker。

本机联调需区分两类 Biz Worker：

- `tools/langgraph-biz-worker/start.ps1` 默认启动 Windows 本地 3061。
- `application.yml` 默认 `BUSINESS_AGENT_DEV_SYNC_WORKER_URL=http://localhost:3061`。
- `scripts/start-launcher.ps1` 未显式设置该变量时，默认把 Skill 同步指向 `http://127.0.0.1:3161`。
- `scripts/local-dev-stack.sh` 同时管理本地 3061 和 WSL 3161；需要固定同步目标时显式设置 `BUSINESS_AGENT_DEV_SYNC_WORKER_URL`。

### Backend

```powershell
# 推荐：编译并启动；脚本只应处理当前工作区的 8112 后端，不影响其他 Java/Node 进程
powershell -ExecutionPolicy Bypass -File scripts/start-launcher.ps1

# 手动启动
mvn package -pl launcher -am -DskipTests
java -jar launcher/target/launcher-1.0.0-SNAPSHOT.jar --spring.profiles.active=docker

# 停止
powershell -ExecutionPolicy Bypass -File scripts/stop-launcher.ps1
```

后端默认端口为 8112，健康检查：`curl http://localhost:8112/actuator/health`。

### Frontend

```powershell
# 推荐：一键启动
powershell -ExecutionPolicy Bypass -File scripts/start-frontend.ps1

# 手动启动
nvm use
corepack enable
pnpm install --frozen-lockfile
pnpm --filter @foggy/navigator-frontend dev
```

前端默认端口为 5174。本地默认登录账号为 `root / root123`；root 默认值来自 `launcher/.env.example` / `application.yml`，现有数据库或本机 `.env` 可以覆盖。

### Build Without Starting

```powershell
# 后端测试编译
mvn test -pl launcher -am

# 前端编译
bash scripts/build-frontend.sh
```

### Important Configuration

- LLM 配置：`launcher/src/main/resources/application-docker.yml`（git ignored）。
- 平台配置：首次使用时在 `/#/settings` 配置 Git provider 和 AI model。
- 日志：`logs/backend.log`、`logs/backend-error.log`。

## Agent Orchestration Core

所有 Agent 无论底层实现，统一通过 A2A / provider 路由接入会话与任务分发链路。

- SPI：`A2aAgent`（执行）与 `A2aAgentProvider`（provider 模式），位于 `navigator-spi/spi/agent/`。
- 统一注册与解析：`UnifiedAgentResolver`（`session-module`）聚合 `A2aAgentProvider`，按 `agentId` / `providerType` / `modelConfigId` 解析目标。
- 统一分派：`TaskDispatchFacade`（`session-module`）是 Worker / Agent 任务入口，支持 A2A 与 Direct Provider 路由。
- 会话绑定：`SessionBindingService`（`session-module`）管理 Session ↔ Agent 绑定生命周期，绑定后不可自动切换。
- REST：`GET /api/v1/agents`、`POST /api/v1/agents/{id}/ask`、`POST /api/v1/tasks`。
- 当前 provider：Claude Worker、Codex Worker、Gemini Worker、LangGraph Biz Worker。
- 核心语义：`logicalAgentId`（逻辑 Agent）、`providerType`（执行后端）、`modelConfigId`（模型配置），不得混淆。
- 扩展方式：新 addon 实现 `A2aAgentProvider` 并注册为 `@Component`，由 Registry 自动注入。

详见 [A2A Agent 架构文档](docs/a2a-agent-architecture.md)。

## Delivery Workflow

- 对分析或规划请求，只调查并比较方案；用户确认方向前不实施。
- 用户确认后，维护一个 canonical project-level delivery spec，记录 approved goal、scope、non-goals、decisions、acceptance criteria、validation obligations 和 risks。
- 规划阶段不要过度规定文件、类、函数级实现；在已批准契约内，由 Ultra implementation session 选择合理的局部实现。
- 若实现需要改变已批准的 goal、scope、compatibility policy、data migration、security boundary 或 architecture decision，停止扩张并标记 `NEEDS_REPLAN`。
- 实施期间在 canonical work item 中记录 changed paths、精确验证命令和结果、deviations、residual risks。
- 实现会话最多将状态更新为 `READY_FOR_SIGNOFF`，不得自行标记 `ACCEPTED`。

## Implementation Rules

### Architecture and Scope

- 遵守现有模块边界和依赖方向，不得把业务 Controller、Service 或编排逻辑放入 deployment-shell 模块。
- 变更保持聚焦、可维护；避免 speculative abstractions、重复 adapter、无退出条件的 compatibility layer 和大范围无关清理。
- 对外部 SDK、CLI、Worker 或 provider 集成，先检查真实支持机制和数据契约，再设计实现；禁止猜测 provider 行为。
- 需求、Java/TypeScript/Python 代码、数据库对象、API path、event、test 和 acceptance record 使用一致的领域术语。
- 注释只用于解释非显然业务规则、兼容约束、回归预防或临时退出条件。

### Backend Conventions

1. JPA Entity 之间不使用关联注解，使用外键字段和 Service 层组合查询。
2. Controller 统一返回 `RX<T>`：成功使用 `RX.ok(data)`，失败使用 `RX.failA/B/C(msg)`。
3. 接口参数使用 Form/DTO，不直接使用 Entity；需要设计时遵循 `/form-design` 技能。
4. 新增 HTTP endpoint、route 或监听端口时同步检查授权配置与相关 contract tests。
5. 可预期业务异常、资源 not-ready、readiness/preflight blocker 不得裸抛为 HTTP 500。Service 层区分系统缺陷和业务/资源状态异常。
6. 会被外层捕获并转换为 structured response 的事务方法，优先使用统一 `@ReadinessTransactional` 或等价 `noRollbackFor` 策略；self-healing/readiness 聚合入口优先隔离大外层事务，避免 `UnexpectedRollbackException` 覆盖真实响应。详见 [异常与事务治理规范](docs/dev-specs/exception-handling-and-transaction-governance.md)。

### Product Semantics and Frontend

- 涉及用户交互前先明确关键语义：操作是否创建新实体、是否等待用户确认、UI 状态如何变化。必要时向用户确认，避免语义漂移。
- 会话创建必须指定 `modelConfigId`。创建后该值固定，除非用户主动修改；会话内可以切换 `model`，但不得自动切换 `modelConfigId`，即 API 凭证/订阅保持不变。
- 修改前端后至少运行 `bash scripts/build-frontend.sh`，包含 TypeScript 类型检查。
- Vue 源码与浏览器行为不一致时，优先排查 Vite HMR 缓存：
  1. 用 Playwright 读取浏览器实际运行的函数源码，例如 `comp.setupState.xxx.toString()`，与磁盘文件比较。
  2. 不一致时删除 `packages/navigator-frontend/node_modules/.vite` 后刷新。
  3. 仍不一致时，确认 5174 进程归属后重启 Vite dev server。

## Verification

- 验证范围与变更面匹配，优先使用模块本地命令和文档，不复制其他项目的命令。
- 后端变更在可行时运行相关 Maven 模块测试并带上依赖；跨模块变更运行 root 或 launcher-wide tests。
- 前端变更至少运行 `bash scripts/build-frontend.sh`；delivery spec 或受影响行为要求时，补充 targeted tests 或 Playwright flow。
- Worker 变更运行对应 Worker 的 unit/integration tests，以及受跨 runtime 契约影响的 contract tests。
- 可复现且有明显回归风险的 BUG，优先先写并运行失败测试，再修复并运行通过；如豁免自动化，记录理由。
- 严格区分“测试代码存在”和“测试实际执行且通过”。不得把未运行或失败的检查描述为成功。
- 环境依赖检查无法运行时，记录精确原因、受影响 acceptance items 和残余风险。

## Documentation and Evidence

- 新需求、缺陷、重构和延期事项写入当前 `docs/version-tracker/<version>/`，不得向历史 `docs/requirement-tracker/` 新增内容。
- 每项变更只保留一个 canonical work item；多模块 ownership、阶段、changed paths、tests 和 evidence 都记录在该条目中，不创建竞争副本。
- 实现改变 migration、API、configuration、architecture 或运行契约时，同步更新相关 migration、API、configuration、architecture、progress 和 acceptance 文档。
- delivery 文档保持简洁，保留 decisions、constraints、evidence、risks 和 next actions，删除重复背景与泛化建议。
- 版本化交付记录中对根 `CLAUDE.md` 的既有引用保留原文；需要执行这些旧指引时，统一解释为读取当前根 `AGENTS.md`。

## Legacy Workflow References

- 历史版本文档可保留已退役个人技能名称，不为现代化术语而改写既有证据。
- 新工作在 approved-plan handoff 边界，将 `plan-evaluator`、`foggy-plan-execution-docs`、`foggy-versioned-doc-tracking`、`foggy-bug-regression-workflow` 映射为 `foggy-delivery-spec`。
- 将 `foggy-implementation-quality-gate`、`foggy-test-coverage-audit`、`foggy-acceptance-signoff` 映射为一次独立的 `foggy-delivery-signoff`。
- `code-simplify` 无替代技能，原生执行 scoped implementation review；使用系统 `skill-creator` 替代旧 `skill-writer`。

## Definition of Done

Material implementation 只有满足以下条件才可进入 signoff：

- approved scope 已实现，或所有 deviation 已披露；
- 相关 build、test、lint、E2E 和 experience checks 已记录结果；
- 变更涉及的 contract、migration、configuration 和 documentation 已更新；
- blocker 和 residual risk 明确；
- canonical delivery spec 已更新为 `READY_FOR_SIGNOFF`；
- 独立 signoff 可以把每个 critical acceptance criterion 映射到可审查证据。
