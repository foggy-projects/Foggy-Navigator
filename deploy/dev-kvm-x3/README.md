# dev-kvm-x3 Navigator 镜像发布部署说明

`dev-kvm-x3` 当前同时承担测试/demo 环境和临时 build-and-push 节点。Navigator 侧部署规范从本目录开始切换为：

1. 本地验证完成后创建 Git tag 或 release 分支。
2. `dev-kvm-x3` checkout 指定 `GIT_REF`。
3. `dev-kvm-x3` 构建后端、前端产物和 Docker 镜像。
4. 镜像推送到 Harbor `test.synthoflow.com:8080/x3`。
5. dev/demo 或目标服务器只按 `IMAGE_TAG` pull + run。
6. 生产服务器不拉源码、不构建，只执行第 5 步。

旧的 `scripts/deploy.sh`、`02-sync-code.sh` 等源码同步部署脚本暂时保留，作为当前可运行环境的回退路径。新增镜像发布链路使用 `remote/`、`runtime/`、`images/` 和 `scripts/1x-3x` 脚本，不覆盖旧流程。

## 目录

| 路径 | 职责 |
| --- | --- |
| `host.env.example` | 本地操作端 SSH、远端目录、Registry 模板。实际 `host.env` 或 `.env` 不提交。 |
| `release.env.example` | 远端发布和运行配置模板。实际 `runtime/release.env` 不提交。 |
| `platform-bootstrap.env.example` | 首次平台 bootstrap 模板。实际 `/opt/foggy/navigator/runtime/platform-bootstrap.env` 不提交。 |
| `tms-upstream.env.example` | TMS 接入 Navigator 的 env 模板，不含敏感值。真实值在远端 runtime env。 |
| `images/backend/Dockerfile` | Launcher Spring Boot 镜像。 |
| `images/frontend/Dockerfile` | Navigator 前端 Nginx 镜像。 |
| `runtime/docker-compose.navigator.yml` | pull + run 运行态 Compose 文件。 |
| `remote/*.sh` | 在 dev-kvm-x3 或目标服务器执行的标准发布脚本。 |
| `scripts/10-sync-release-kit.sh` | 从本地同步发布脚本包到远端。 |
| `scripts/11-remote-init-release.sh` | 初始化远端目录、检查依赖、配置 Harbor insecure registry。 |
| `scripts/20-build-and-push-images.sh` | 远端 checkout 指定 Git ref，构建并推送镜像。 |
| `scripts/30-deploy-by-image.sh` | 目标服务器按 image tag pull + run。 |
| `scripts/31-rollback-by-image.sh` | 按历史或指定 image tag 回滚。 |
| `scripts/32-status-check.sh` | Compose、HTTP、健康探针和启动日志检查。 |
| `scripts/40-install-workers-from-obs.sh` | 从 OBS 安装并启动启用的 Worker。 |
| `scripts/41-update-workers-from-obs.sh` | 从 OBS 升级并重启启用的 Worker。 |
| `scripts/42-check-workers.sh` | 检查启用的 Worker CLI 状态和健康探针。 |
| `scripts/50-start-langgraph-biz-worker.sh` | 启动 Navigator Business Agent 使用的 LangGraph Biz Worker。 |
| `scripts/51-bootstrap-platform.sh` | 首次平台 bootstrap：租户、管理员、LLM、Biz Worker、Worker Pool、ClientApp、TMS env。 |
| `scripts/52-check-platform-bootstrap.sh` | 检查主应用和 LangGraph Biz Worker 健康状态。 |
| `scripts/53-smoke-tms-openapi.sh` | 用 TMS ClientApp runtime credential 执行 runtime-token、preflight、ask、messages 烟测。 |
| `inventory.md` | 当前部署资产、端口、依赖、密钥清单。 |
| `deployment-report-image-release-2026-05-15.md` | 本次部署流程改造报告。 |

## 基础设施

| 项目 | 值 |
| --- | --- |
| dev-kvm-x3 | `192.168.31.81` |
| 远端部署根目录 | `/opt/foggy/navigator` |
| 当前旧源码部署目录 | `/opt/foggy/navigator/current` |
| 新发布脚本目录 | `/opt/foggy/navigator/release-kit` |
| 新运行态目录 | `/opt/foggy/navigator/runtime` |
| Harbor registry | `test.synthoflow.com:8080` |
| Harbor project | `x3` |
| npm registry | `http://192.168.31.81:4873` |
| 镜像 tag 示例 | `dev-kvm-x3-20260515-1` |

镜像命名统一为：

```text
test.synthoflow.com:8080/x3/navigator-backend:<image-tag>
test.synthoflow.com:8080/x3/navigator-frontend:<image-tag>
```

Worker 不进入 Navigator 镜像发布链路，沿用 OBS 安装包分发：

```text
https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-worker
https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/codex-worker
https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/gemini-worker
```

## 配置文件

本地操作端：

```bash
cp deploy/dev-kvm-x3/host.env.example deploy/dev-kvm-x3/.env
```

远端运行态：

```bash
cp /opt/foggy/navigator/release-kit/release.env.example /opt/foggy/navigator/runtime/release.env
chmod 0600 /opt/foggy/navigator/runtime/release.env
```

敏感信息只允许填写在 ignored 文件中：

- `deploy/dev-kvm-x3/.env`
- `deploy/dev-kvm-x3/host.env`
- `/opt/foggy/navigator/runtime/release.env`
- `/opt/foggy/navigator/runtime/platform-bootstrap.env`
- `/opt/foggy/navigator/runtime/langgraph-biz-worker.env`
- `/opt/foggy/navigator/runtime/tms-upstream.env`

不要提交数据库密码、Harbor 密码、Worker token、JWT secret、凭证加密 key、TMS token、私钥。

## dev-kvm-x3 Build And Push

第一次初始化：

```bash
bash deploy/dev-kvm-x3/scripts/11-remote-init-release.sh
```

在远端编辑：

```bash
vi /opt/foggy/navigator/runtime/release.env
```

关键字段：

```bash
GIT_URL=<Navigator Git repository URL>
GIT_REF=<tag-or-release-branch>
IMAGE_TAG=dev-kvm-x3-20260515-1
HARBOR_USERNAME=<harbor-user>
HARBOR_PASSWORD=<harbor-password>
```

构建并推送：

```bash
bash deploy/dev-kvm-x3/scripts/20-build-and-push-images.sh
```

## Pull And Run

dev/demo 或目标服务器部署：

```bash
bash deploy/dev-kvm-x3/scripts/30-deploy-by-image.sh
```

部署脚本会在 `docker compose up -d` 后等待前端和后端容器进入 healthy，并验证 HTTP 探针。默认等待窗口由远端 `release.env` 控制：

```bash
NAVIGATOR_DEPLOY_HEALTH_RETRIES=45
NAVIGATOR_DEPLOY_HEALTH_INTERVAL_SECONDS=2
```

dev/demo 节点安装或升级 Worker：

```bash
bash deploy/dev-kvm-x3/scripts/40-install-workers-from-obs.sh
bash deploy/dev-kvm-x3/scripts/42-check-workers.sh
```

首次平台 bootstrap 或更新 TMS 接入配置：

```bash
cp deploy/dev-kvm-x3/platform-bootstrap.env.example /opt/foggy/navigator/runtime/platform-bootstrap.env
chmod 0600 /opt/foggy/navigator/runtime/platform-bootstrap.env
# 在 dev-kvm-x3 上填入 root 密码、LLM API key 等敏感配置后执行：
bash deploy/dev-kvm-x3/scripts/51-bootstrap-platform.sh
bash deploy/dev-kvm-x3/scripts/52-check-platform-bootstrap.sh
bash deploy/dev-kvm-x3/scripts/53-smoke-tms-openapi.sh
```

该流程会在远端生成：

- `/opt/foggy/navigator/runtime/langgraph-biz-worker.env`
- `/opt/foggy/navigator/runtime/tms-upstream.env`
- `/opt/foggy/navigator/runtime/platform-bootstrap-report.json`
- `/opt/foggy/navigator/runtime/tms-openapi-smoke-report.json`

`tms-upstream.env` 给 TMS BFF/runner 使用，包含 ClientApp runtime credential、control key、model config 和 worker pool 信息，必须只在服务器或 secret 管理系统中保存。

当前 dev/demo bootstrap 默认 `NAVIGATOR_TMS_MATERIALIZE_AGENT_BUNDLE=false`：先完成 ClientApp、Business Agent、public skill 索引、model grant 和 upstream user grant。TMS 侧同步真实 function manifest / skill bundle 后，再按需打开 materialize 或通过 CLI/SDK 执行 materialize，避免首次空 bundle 依赖 Worker 文件落地能力。

只检查状态：

```bash
bash deploy/dev-kvm-x3/scripts/32-status-check.sh
```

`32-status-check.sh` 默认只执行一次。需要等待启动完成时可临时设置：

```bash
NAVIGATOR_STATUS_RETRIES=45 NAVIGATOR_STATUS_INTERVAL_SECONDS=2 bash deploy/dev-kvm-x3/scripts/32-status-check.sh
```

部署脚本内部会把重试过程中的临时 HTTP 失败显示为 `waiting`；只有等待窗口耗尽后才输出最终 `FAILED`。

回滚到上一个 tag：

```bash
bash deploy/dev-kvm-x3/scripts/31-rollback-by-image.sh
```

回滚到指定 tag：

```bash
bash deploy/dev-kvm-x3/scripts/31-rollback-by-image.sh dev-kvm-x3-20260515-1
```

远端也可以直接执行：

```bash
cd /opt/foggy/navigator/release-kit
bash remote/deploy-by-image.sh
bash remote/rollback-by-image.sh dev-kvm-x3-20260515-1
bash remote/status-check.sh
```

## 运行态容器

固定容器名：

| 容器 | 端口 | 说明 |
| --- | --- | --- |
| `foggy-navigator-frontend` | `80:80` | 前端和 `/api` 反向代理 |
| `foggy-navigator-backend` | `8112:8112` | Launcher API |
| `foggy-navigator-mysql` | `13309:3306` | dev/demo 本地 MySQL，`NAVIGATOR_LOCAL_INFRA=true` 时启用 |
| `foggy-navigator-rabbitmq` | `5672` / `15672` | dev/demo 本地 RabbitMQ，`NAVIGATOR_LOCAL_INFRA=true` 时启用 |

重复部署前脚本只清理 Navigator 应用容器：

- `foggy-navigator-backend`
- `foggy-navigator-frontend`
- 旧源码部署的 `foggy-navigator-nginx`

默认还会停止旧源码部署占用的 Navigator app 端口 `8112`，用于从旧流程切换到镜像流程。Worker 由 OBS 安装脚本独立管理，不在 Docker Compose 清理范围内。脚本不会主动删除 TMS、Verdaccio、共享数据库或其他非 Navigator 管理容器。MySQL/RabbitMQ 数据卷也不会在部署脚本中删除。

## 验证

`remote/status-check.sh` 会检查：

- Docker Compose 容器状态。
- 应用容器 Docker health，`starting` 和 `unhealthy` 会被视为未就绪。
- `http://127.0.0.1/health`
- `http://127.0.0.1:8112/actuator/health`
- 关键容器最近日志中的启动失败模式。

Worker 由 `remote/check-workers.sh` 单独检查：

- `http://127.0.0.1:3031/health`
- `http://127.0.0.1:3051/health`
- `http://127.0.0.1:3071/health`

平台 bootstrap 由 `remote/check-platform-bootstrap.sh` 单独检查：

- `http://127.0.0.1:8112/actuator/health`
- `http://127.0.0.1:3061/health`
- `/opt/foggy/navigator/runtime/platform-bootstrap-report.json`
- `/opt/foggy/navigator/runtime/tms-openapi-smoke-report.json`

对外访问入口仍是：

- 前端：`http://192.168.31.81`
- API：`http://192.168.31.81:8112`

## 临时兼容方案

- 前端仍依赖 monorepo workspace：`@foggy/navigator-frontend` 使用 `@foggy/chat: workspace:*`，`@foggy/chat` 使用 `@foggy/chat-core: workspace:*`。当前 build-and-push 在同一个 Git checkout 内执行 `pnpm install --no-frozen-lockfile`，并按 `chat-core -> chat -> navigator-frontend` 顺序构建。
- 根目录 `pnpm-lock.yaml` 当前未跟踪，生产前需要决定是否提交锁文件，或将内部包发布到 Verdaccio 后切换成普通版本依赖。
- Claude/Codex/Gemini Worker 继续使用 OBS 安装脚本分发，节点级运行态位于 `~/.claude-worker`、`~/.codex-worker`、`~/.gemini-worker`。生产前需要固化 Worker 配置、升级策略和账号/token 注入方式。
- LangGraph Biz Worker 当前作为 Business Agent 的 Python 节点级运行态，默认从 `/opt/foggy/navigator/build/source/<IMAGE_TAG>/tools/langgraph-biz-worker` 创建 venv 启动，保证与镜像 tag 对齐。`BIZ_WORKER_SOURCE_DIR` 只作为应急兼容开关使用；生产前需要决定是否改为独立镜像或 OBS 包，并固化 systemd/自启动、token 注入和回滚策略。
- `dev-kvm-x3` 可继续启用本地 MySQL/RabbitMQ；生产建议改为外部托管依赖并设置 `NAVIGATOR_LOCAL_INFRA=false`。
