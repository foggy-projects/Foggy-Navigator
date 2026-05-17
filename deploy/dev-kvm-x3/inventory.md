# Navigator dev-kvm-x3 部署资产盘点

日期：2026-05-15

## 当前部署形态

| 项目 | 当前状态 |
| --- | --- |
| 旧流程 | 本地/远端源码同步到 `/opt/foggy/navigator/current`，远端构建并启动。 |
| 新目标流程 | 指定 Git ref，dev-kvm-x3 构建镜像并推 Harbor，目标服务器 pull + run。 |
| 当前运行入口 | `http://192.168.31.81` |
| 当前 API | `http://192.168.31.81:8112` |
| 当前 Claude Worker | `http://192.168.31.81:3031` |
| 当前 Codex Worker | `http://192.168.31.81:3051` |
| Harbor project | `x3` |
| npm registry | `http://192.168.31.81:4873` |

## 组件

| 组件 | 当前启动方式 | 新镜像 | 端口 | 健康检查 |
| --- | --- | --- | --- | --- |
| Launcher 后端 | `start-launcher.sh` 后台 jar | `navigator-backend` | `8112` | `/actuator/health` |
| Navigator 前端 | `start-build-frontend.sh` + Nginx | `navigator-frontend` | `80` | `/health` |
| Claude Agent Worker | OBS 安装包 / `claude-worker` CLI | 不进入 Navigator 镜像链路 | `3031` | `/health` |
| Codex Worker | OBS 安装包 / `codex-worker` CLI | 不进入 Navigator 镜像链路 | `3051` | `/health` |
| Gemini Worker | OBS 安装包 / `gemini-worker` CLI | 不进入 Navigator 镜像链路 | `3071` | `/health` |
| LangGraph Biz Worker | `tools/langgraph-biz-worker` + venv | 不进入主应用镜像链路 | `3061` | `/health` |
| MySQL | Docker Compose | `mysql:8.0` | `13309 -> 3306` | `mysqladmin ping` |
| RabbitMQ | Docker Compose override | `rabbitmq:3-management-alpine` | `5672`, `15672` | `rabbitmq-diagnostics ping` |

## 运行态环境变量

| 变量 | 用途 | 敏感 |
| --- | --- | --- |
| `IMAGE_TAG` | 当前运行镜像 tag | 否 |
| `GIT_URL` / `GIT_REF` | build-and-push 源码来源 | URL 视仓库策略 |
| `HARBOR_REGISTRY` / `HARBOR_PROJECT` | 镜像仓库定位 | 否 |
| `HARBOR_USERNAME` / `HARBOR_PASSWORD` | 推送或拉取 Harbor 镜像 | 是 |
| `NPM_REGISTRY` | 前端依赖安装 registry | 否 |
| `SPRING_DATASOURCE_URL` | 数据库连接串 | 可能 |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | 数据库账号 | 是 |
| `MYSQL_ROOT_PASSWORD` / `MYSQL_PASSWORD` | dev/demo 本地 MySQL | 是 |
| `RABBITMQ_USER` / `RABBITMQ_PASS` | RabbitMQ 账号 | 是 |
| `JWT_SECRET` | 应用 JWT 签名 | 是 |
| `SYSTEM_ROOT_PASSWORD` | Navigator root 初始化密码 | 是 |
| `NAVIGATOR_CREDENTIAL_KEY` / `NAVIGATOR_CREDENTIAL_SALT` | 凭证加密配置 | 是 |
| `TMS_X3_AGENT_UPSTREAM_URL` | TMS Adapter 地址 | 否 |
| `WORKER_INSTALL_CLAUDE` / `WORKER_INSTALL_CODEX` / `WORKER_INSTALL_GEMINI` | dev/demo 节点启用哪些 OBS Worker | 否 |
| `CLAUDE_WORKER_URL` / `CODEX_WORKER_URL` / `GEMINI_WORKER_URL` | OBS Worker 安装包地址 | 否 |
| Worker 本地 `.env` | Worker token、模型账号、工作目录等节点级配置 | 是 |
| `platform-bootstrap.env` | 首次平台 bootstrap 输入：root 密码、LLM API key、租户、ClientApp、worker pool | 是 |
| `langgraph-biz-worker.env` | LangGraph Biz Worker runtime env、worker token、LLM key | 是 |
| `tms-upstream.env` | TMS 接入 Navigator 的 ClientApp runtime credential、control key、model/workerPool | 是 |
| `NAVIGATOR_STOP_LEGACY_SOURCE` | 首次镜像切换时停止旧源码部署占用的 app 端口 | 否 |

## 远端目录

| 路径 | 说明 |
| --- | --- |
| `/opt/foggy/navigator/current` | 当前旧源码部署目录，保留回退。 |
| `/opt/foggy/navigator/release-kit` | 新镜像发布脚本包。 |
| `/opt/foggy/navigator/runtime` | Compose 文件、`release.env`、当前/上一版 tag 文件。 |
| `/opt/foggy/navigator/build/source/<image-tag>` | dev-kvm-x3 构建节点 checkout 的指定 Git ref。 |

## 当前运行版本

| 项目 | 值 |
| --- | --- |
| image tag | `dev-kvm-x3-20260515-1` |
| Git ref | `88ac31915eb97d3ace4dcaa762ba63a0e454fcdc` |
| 后端镜像 | `test.synthoflow.com:8080/x3/navigator-backend:dev-kvm-x3-20260515-1` |
| 前端镜像 | `test.synthoflow.com:8080/x3/navigator-frontend:dev-kvm-x3-20260515-1` |
| 本地数据库 | `foggy-navigator-mysql` / `coding_agent` / host port `13309` |
| 本地消息队列 | `foggy-navigator-rabbitmq` / host ports `5672`,`15672` |
| Worker 注册目标 | `http://dev-kvm-jdk17.foggysource.com/` 对应 Navi，当前 DNS 指向 `192.168.31.22` |
| Worker 记录 | `dev-kvm-x3-worker` / `93bf1a65` / `ONLINE` |
| Claude 工作目录 | Navigator 记录 `dev-kvm-x3-root` -> `/`；远端 Claude Worker 白名单 `AGENT_WORKER_ALLOWED_CWDS=["/"]` |
| 平台 bootstrap env | `/opt/foggy/navigator/runtime/platform-bootstrap.env` |
| TMS upstream env | `/opt/foggy/navigator/runtime/tms-upstream.env` |
| 平台 bootstrap 报告 | `/opt/foggy/navigator/runtime/platform-bootstrap-report.json` |
| TMS OpenAPI smoke 报告 | `/opt/foggy/navigator/runtime/tms-openapi-smoke-report.json` |

## Docker 命名

| 类型 | 名称 |
| --- | --- |
| Compose project | `foggy-navigator` |
| Network | `foggy-navigator-network` |
| Backend container | `foggy-navigator-backend` |
| Frontend container | `foggy-navigator-frontend` |
| MySQL container | `foggy-navigator-mysql` |
| RabbitMQ container | `foggy-navigator-rabbitmq` |
| MySQL volume | `foggy-navigator-mysql-data` |
| RabbitMQ volume | `foggy-navigator-rabbitmq-data` |

## Open Items

| 项目 | 生产前处理建议 |
| --- | --- |
| 根 `pnpm-lock.yaml` 未跟踪 | 提交锁文件，或固定 Verdaccio 内部包版本后再构建。 |
| `@foggy/chat-core` / `@foggy/chat` workspace 依赖 | dev/demo 可 monorepo 构建；生产发布前建议发布内部包到 Verdaccio 或保留 `chat-core -> chat -> navigator-frontend` 的 release 构建规范。 |
| Maven 私服依赖 | dev-kvm-x3 已可构建；生产构建节点需要复用 Maven settings，不应依赖开发者本机 `.m2`。 |
| Worker 节点运行态 | 当前沿用 OBS 安装包；生产前需要确认 Worker 安装目录、systemd/自启动、账号/token 注入和升级回滚策略。 |
| LangGraph Biz Worker 运行态 | 当前由源码目录创建 Python venv 并由脚本启动；生产前需要确认是否改为镜像或 OBS 包，以及 systemd、token 注入、升级回滚策略。 |
| Agent bundle materialize | dev/demo 当前默认只在 Navigator 侧注册 bundle/grant，不立即 materialize 到 Worker；TMS 同步真实 skill/function bundle 后再按需启用。 |
| Git provider | 未提供 GitHub token 时不要伪造平台 Git 配置；Business Agent/TMS 接入可先运行，生产前补齐平台 Git Provider。 |
| 本地 MySQL/RabbitMQ | dev/demo 可继续使用；生产应切到外部数据库/消息队列并关闭本地 infra profile。 |
| Harbor HTTP | 所有 build/run 节点都要配置 Docker `insecure-registries`。 |
| Codex Worker 认证 | dev-kvm-x3 上 Codex Worker 已启动，但当前 health 显示 `codex_auth_configured=false`；生产或正式 demo 前需要固化 Codex CLI/API 认证。 |
| dev-kvm-jdk17 会话列表 | `dev-kvm-jdk17.foggysource.com` 现有后端存在历史 `sessions.status=DELETED` 兼容问题，会影响会话列表 UI；需要在该后端修复枚举兼容或清理历史数据。 |
