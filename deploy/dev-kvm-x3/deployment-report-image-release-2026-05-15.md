# Navigator 镜像发布流程改造报告

日期：2026-05-15

## 结论

Navigator 侧已补齐与 TMS 侧一致的主应用发布模型脚本和文档：`指定 Git ref -> dev-kvm-x3 构建镜像 -> 推送 Harbor x3 项目 -> 目标服务器按 image tag pull + run`。后端和前端进入镜像发布链路；`tools` 下的 Worker 继续按现有 OBS 安装包模式分发，作为节点级运行态单独安装、升级和检查。

本次改造未移除旧源码部署脚本，当前可运行环境仍可通过旧流程回退。新增流程以 `deploy/dev-kvm-x3/remote` 为远端执行入口，以 `deploy/dev-kvm-x3/runtime/docker-compose.navigator.yml` 作为标准运行态。

## 当前运行信息

| 项目 | 值 |
| --- | --- |
| 当前主机 | `dev-kvm-x3` / `192.168.31.81` |
| 旧源码部署目录 | `/opt/foggy/navigator/current` |
| 新发布脚本目录 | `/opt/foggy/navigator/release-kit` |
| 新运行态目录 | `/opt/foggy/navigator/runtime` |
| Harbor registry | `test.synthoflow.com:8080` |
| Harbor project | `x3` |
| npm registry | `http://192.168.31.81:4873` |
| Worker 分发 | OBS 安装包，本次启用 Claude Worker 与 Codex Worker |
| 当前镜像 tag | `dev-kvm-x3-20260515-1` |
| 当前 Git ref | `88ac31915eb97d3ace4dcaa762ba63a0e454fcdc` |
| 当前数据库 | dev-kvm-x3 本机 Docker MySQL：`foggy-navigator-mysql` / `coding_agent` / host port `13309` |
| 当前 RabbitMQ | dev-kvm-x3 本机 Docker RabbitMQ：`foggy-navigator-rabbitmq` / host ports `5672`,`15672` |

## 新增文件

| 文件 | 说明 |
| --- | --- |
| `host.env.example` | 本地 SSH/Registry 模板，不含密码。 |
| `release.env.example` | 远端 release/runtime 模板，不含密码。 |
| `images/backend/Dockerfile` | Launcher 后端镜像。 |
| `images/frontend/Dockerfile` | Navigator 前端镜像。 |
| `images/frontend/navigator-container.conf` | 容器内 Nginx 配置，代理到 `navigator-backend:8112`。 |
| `runtime/docker-compose.navigator.yml` | 标准 pull + run Compose 文件。 |
| `remote/init-host.sh` | 初始化远端目录、依赖检查、Harbor HTTP insecure registry。 |
| `remote/configure-release.sh` | 根据 `release.env` 固化镜像名。 |
| `remote/checkout-ref.sh` | checkout 指定 Git ref。 |
| `remote/build-and-push-images.sh` | 构建后端/前端镜像并 push。 |
| `remote/deploy-by-image.sh` | 按 image tag 拉取运行。 |
| `remote/rollback-by-image.sh` | 按 image tag 回滚。 |
| `remote/status-check.sh` | 容器、HTTP、健康探针和日志检查。 |
| `remote/install-workers-from-obs.sh` | 从 OBS 安装并启动启用的 Worker。 |
| `remote/update-workers-from-obs.sh` | 从 OBS 升级并重启启用的 Worker。 |
| `remote/check-workers.sh` | 检查启用的 Worker CLI 状态和健康探针。 |
| `platform-bootstrap.env.example` | 首次平台 bootstrap 配置模板，不含敏感值。 |
| `remote/start-langgraph-biz-worker.sh` | 启动 Navigator Business Agent 使用的 LangGraph Biz Worker。 |
| `remote/bootstrap-platform.sh` | 创建/复用租户管理员、LLM 模型、LangGraph worker、Biz worker pool、ClientApp 和 TMS env。 |
| `remote/check-platform-bootstrap.sh` | 检查主应用和 LangGraph Biz Worker 健康状态。 |
| `remote/smoke-tms-openapi.sh` | 使用 TMS ClientApp runtime credential 验证 OpenAPI runtime-token、preflight、ask、messages。 |
| `inventory.md` | 现有部署资产盘点。 |

## 标准命令

初始化远端：

```bash
bash deploy/dev-kvm-x3/scripts/11-remote-init-release.sh
```

构建并推送：

```bash
bash deploy/dev-kvm-x3/scripts/20-build-and-push-images.sh
```

部署指定镜像：

```bash
bash deploy/dev-kvm-x3/scripts/30-deploy-by-image.sh
```

回滚：

```bash
bash deploy/dev-kvm-x3/scripts/31-rollback-by-image.sh dev-kvm-x3-20260515-1
```

检查：

```bash
bash deploy/dev-kvm-x3/scripts/32-status-check.sh
```

Worker 安装/升级/检查：

```bash
bash deploy/dev-kvm-x3/scripts/40-install-workers-from-obs.sh
bash deploy/dev-kvm-x3/scripts/41-update-workers-from-obs.sh
bash deploy/dev-kvm-x3/scripts/42-check-workers.sh
```

首次平台 bootstrap / TMS 接入配置：

```bash
bash deploy/dev-kvm-x3/scripts/51-bootstrap-platform.sh
bash deploy/dev-kvm-x3/scripts/52-check-platform-bootstrap.sh
bash deploy/dev-kvm-x3/scripts/53-smoke-tms-openapi.sh
```

## 验证要求

每次部署完成后必须执行：

1. `docker compose ps` 确认 `navigator-backend`、`navigator-frontend` 为 running。
2. `http://127.0.0.1/health` 返回 2xx。
3. `http://127.0.0.1:8112/actuator/health` 返回 2xx 且包含 `UP`。
4. `bash remote/check-workers.sh` 确认启用的 Worker CLI 状态和健康探针正常。
5. `bash remote/check-platform-bootstrap.sh` 确认 `8112` 主应用和 `3061` LangGraph Biz Worker 健康探针正常。
6. 最近容器日志不得出现 `Application run failed`、`Failed to start`、`Traceback`、`FATAL`。

主应用检查已固化到 `remote/status-check.sh`，Worker 检查固化到 `remote/check-workers.sh`。

平台 bootstrap 检查已固化到 `remote/check-platform-bootstrap.sh`，敏感输出保存在远端 ignored 文件中：

- `/opt/foggy/navigator/runtime/platform-bootstrap.env`
- `/opt/foggy/navigator/runtime/langgraph-biz-worker.env`
- `/opt/foggy/navigator/runtime/tms-upstream.env`
- `/opt/foggy/navigator/runtime/platform-bootstrap-report.json`
- `/opt/foggy/navigator/runtime/tms-openapi-smoke-report.json`

首次 TMS 平台 bootstrap 结果（2026-05-17）：

| 项目 | 结果 |
| --- | --- |
| tenantId / admin | `tms-x3` / `tms-admin` |
| LLM model config | `de146b42-23db-4b2b-b349-0af76aa61ad3` / `qwen3.5-plus` |
| LangGraph Biz Worker | `dev-kvm-x3-langgraph-biz-worker` / `http://192.168.31.81:3061` / `ONLINE` |
| Worker pool | `tms-x3-langgraph-pool` |
| ClientApp | `capp_91859c0b-9049-46fa-ba2b-a4fcce50e3c9` / `TMS X3 Demo` |
| Agent / Skill | `tms.navigator.agent` / `tms.navigator.agent` |
| Agent bundle | 已同步，dev/demo 默认 `materialize=false` |
| Seed upstream user | `tms-x3-smoke-user` / grant `ENABLED` |
| Smoke task | `bt_59f1da71300943c38df6c467af54f81d`，worker task `lgt_1abbe6ce0eb14551`，worker status `COMPLETED` |
| OpenAPI runtime smoke | runtime-token issued，preflight `OK`，task `lgt_93b11a59a3044e15`，context `20260517-b890`，status `COMPLETED`，message count `6` |
| TMS env | `/opt/foggy/navigator/runtime/tms-upstream.env`，`0600`，不提交 |

## 本次执行结果

2026-05-15 已按镜像流程完成一次真实部署：

| 项目 | 结果 |
| --- | --- |
| 后端镜像 | `test.synthoflow.com:8080/x3/navigator-backend:dev-kvm-x3-20260515-1` |
| 前端镜像 | `test.synthoflow.com:8080/x3/navigator-frontend:dev-kvm-x3-20260515-1` |
| Compose 状态 | `foggy-navigator-backend`、`foggy-navigator-frontend`、`foggy-navigator-mysql`、`foggy-navigator-rabbitmq` 均 running/healthy |
| 前端健康检查 | `http://127.0.0.1/health` 返回 2xx |
| 后端健康检查 | `http://127.0.0.1:8112/actuator/health` 返回 2xx，状态 `UP` |
| 启动日志 | 未发现 `Application run failed`、`Failed to start`、`Traceback`、`FATAL` |
| Claude Worker | OBS 安装，`0.1.3`，`http://127.0.0.1:3031/health` OK，`claude_cli_available=true` |
| Codex Worker | OBS 安装，`1.0.1`，`http://127.0.0.1:3051/health` OK，`codex_sdk_available=true`，当前 `codex_auth_configured=false` |
| Gemini Worker | 本次跳过 |

按用户要求，已将 dev-kvm-x3 节点 Worker 注册到 `http://dev-kvm-jdk17.foggysource.com/` 对应 Navi：

| 项目 | 结果 |
| --- | --- |
| 前端 DNS | `dev-kvm-jdk17.foggysource.com -> 192.168.31.22`，不是 `192.168.31.81` 本机入口 |
| 登录验证 | `admin` 账号可通过浏览器登录，localStorage 写入 `navigator_token` |
| Worker 记录 | `dev-kvm-x3-worker` / workerId `93bf1a65` / 状态 `ONLINE` |
| Claude baseUrl | `http://192.168.31.81:3031` |
| Codex baseUrl | `http://192.168.31.81:3051`，默认模型 `codex-latest` |
| Claude 工作目录 | Navigator 已注册 `dev-kvm-x3-root` -> `/`；远端 `/home/sa/.claude-worker/.env` 已固化 `AGENT_WORKER_ALLOWED_CWDS=["/"]` |
| Codex 进程接口 | `/api/v1/codex-workers/93bf1a65/processes` 返回 200 |
| 测试会话 | 已创建 `953ee1a4-54a7-409b-8412-6b45a7e74701`，状态 `ACTIVE` |
| 已知问题 | `dev-kvm-jdk17` 现有后端的会话列表接口遇到历史 `sessions.status=DELETED` 会报 `No enum constant ... SessionStatus.DELETED`；这属于该现有 Navi 后端/数据库兼容问题，不在 dev-kvm-x3 本机 Docker MySQL 中。 |

## 回滚策略

运行态目录记录：

- `/opt/foggy/navigator/runtime/current-image-tag`
- `/opt/foggy/navigator/runtime/previous-image-tag`

无参数回滚使用上一版：

```bash
cd /opt/foggy/navigator/release-kit
bash remote/rollback-by-image.sh
```

指定 tag 回滚：

```bash
cd /opt/foggy/navigator/release-kit
bash remote/rollback-by-image.sh dev-kvm-x3-20260515-1
```

## 临时方案

- dev/demo 阶段允许 `dev-kvm-x3` 同时承担 build-and-push 与 pull-and-run。
- dev/demo 阶段镜像 tag 使用 `dev-kvm-x3-YYYYMMDD-N`。
- 旧源码部署链路保留，直到镜像链路完成一次真实 cutover 和回滚演练。
- 首次镜像 cutover 时 `NAVIGATOR_STOP_LEGACY_SOURCE=true` 会停止旧源码部署占用的 `8112` app 端口，并移除旧 Nginx 容器。Worker 由 OBS 安装脚本独立管理，不由 Docker Compose 清理。
- 本地 MySQL/RabbitMQ 通过 `NAVIGATOR_LOCAL_INFRA=true` 启用；生产应使用外部依赖。

## 生产前待处理

| 项目 | 处理要求 |
| --- | --- |
| 镜像 tag 策略 | 从 `dev-kvm-x3-YYYYMMDD-N` 切换为 Git tag 或语义化版本。 |
| 源码构建节点 | 生产不构建；如需正式 CI，应迁移到专用 CI/build 节点。 |
| 前端 workspace link | 明确锁文件策略或发布内部包到 Verdaccio；当前发布脚本按 `chat-core -> chat -> navigator-frontend` 顺序构建。 |
| Maven 私服 | 固化 Maven settings，避免依赖构建节点个人缓存。 |
| Harbor HTTP | 生产 pull 节点如继续访问内网 HTTP Harbor，必须配置 insecure registry。 |
| 密钥管理 | 迁移到环境级 secret 管理，不再手工编辑明文 env。 |
| 数据服务 | 生产改外部 MySQL/RabbitMQ，Compose 不启用 local infra。 |
| Worker 运行态 | 固化 OBS Worker 的安装目录、自启动、账号/token 注入、升级和回滚策略。 |
| LangGraph Biz Worker | 当前用源码目录 + Python venv 启动，生产前确认改为镜像/OBS 包或 systemd 托管方式。 |
| 平台 Git Provider | 未提供 GitHub token 时不自动创建 Git Provider；生产前补齐并纳入 bootstrap。 |
