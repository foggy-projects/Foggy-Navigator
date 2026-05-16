# dev-kvm-x3 Navigator 部署与 TMS 接入报告

日期：2026-05-15  
环境：`dev-kvm-x3`，测试与对外 demo 暂合并环境

## 1. 当前部署结论

Navigator 第一版已在 `dev-kvm-x3` 跑通，当前采用“源码同步到远端 + 远端构建/启动 + Docker 承载基础依赖”的方式。

| 项目 | 结果 |
| --- | --- |
| 前端入口 | `http://192.168.31.81/`，已验证 HTTP 200 |
| 后端入口 | `http://192.168.31.81:8112` |
| 后端健康检查 | `http://192.168.31.81:8112/actuator/health`，已验证 `UP` |
| Claude Agent Worker | `http://192.168.31.81:3031/health`，已验证 HTTP 200 |
| Claude Code CLI | 已安装，Worker health 中 `claude_cli_available=true` |
| MySQL | Docker 运行，`foggy-navigator-mysql` healthy，端口 `13309` |
| RabbitMQ | Docker 运行，`foggy-navigator-rabbitmq` healthy，端口 `5672`，管理端口 `15672` |
| Nginx | Docker 运行，`foggy-navigator-nginx` healthy，端口 `80` |
| Code Server | 暂未安装，远端访问 GitHub release 超时；不影响当前 Navigator Web、后端和 Worker |

## 2. 本次部署方式

本次不是完整 Docker 化部署，具体方式如下：

| 层级 | 当前方式 |
| --- | --- |
| 源码 | `rsync` 同步到 `/opt/foggy/navigator/current` |
| 后端 | 远端 Maven 构建 `launcher-1.0.0-SNAPSHOT.jar`，宿主机 Java 进程启动 |
| 前端 | 远端构建 dist，Docker Nginx 托管静态资源 |
| MySQL | Docker Compose |
| RabbitMQ | dev-kvm-x3 专属 Docker Compose override |
| Claude Agent Worker | 宿主机 Python venv 进程 |

后续建议升级为“CI 或本地构建 release 包 + 远端解压启动”，避免每次部署都依赖远端 Maven/npm/GitHub 网络。

## 3. 部署资料位置

本仓库已新增 `deploy/dev-kvm-x3` 目录：

| 文件/目录 | 说明 |
| --- | --- |
| `deploy/dev-kvm-x3/README.md` | 环境部署说明 |
| `deploy/dev-kvm-x3/.env.example` | 可提交变量模板 |
| `deploy/dev-kvm-x3/.env` | 本机私有部署变量，已被 `.gitignore` 忽略，不提交 |
| `deploy/dev-kvm-x3/scripts/deploy.sh` | 部署入口 |
| `deploy/dev-kvm-x3/scripts/status.sh` | 状态检查 |
| `deploy/dev-kvm-x3/scripts/stop.sh` | 停止服务 |
| `launcher/src/main/resources/application-dev-kvm-x3.yml` | dev-kvm-x3 后端 profile |

## 4. TMS 侧接入建议

TMS 侧建议按 Navigator Upstream CLI 接入，不建议拿 Navigator 数据库权限或后台管理账号。

TMS 项目根目录应安装 Navigator Upstream CLI，并维护项目本地、已 gitignore 的 `.navigator/upstream.env`。推荐基础流程：

```powershell
irm https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/navigator-upstream-cli/install.ps1 | iex

.\tools\navigator-upstream\navi.ps1 version
.\tools\navigator-upstream\navi.ps1 upstream config check
.\tools\navigator-upstream\navi.ps1 upstream runtime-token --write-profile
.\tools\navigator-upstream\navi.ps1 upstream verify-agent-readiness --upstream-user-id <tmsUserId>
.\tools\navigator-upstream\navi.ps1 upstream ensure-grant --upstream-user-id <tmsUserId>
.\tools\navigator-upstream\navi.ps1 upstream ask --upstream-user-id <tmsUserId> --message "连通性测试"
.\tools\navigator-upstream\navi.ps1 upstream messages --task-id <taskId> --poll --interval 4
```

TMS 侧 `.navigator/upstream.env` 建议结构：

```properties
NAVI_BASE_URL=http://192.168.31.81:8112
NAVI_TENANT_ID=<由 Navigator 侧提供>
NAVI_CLIENT_APP_ID=<由 Navigator 侧提供>
NAVI_CLIENT_APP_KEY=<由 Navigator 侧提供>
NAVI_CLIENT_APP_SECRET=<由 Navigator 侧提供>
NAVI_CLIENT_APP_ACCESS_TOKEN=
NAVI_CONTROL_API_KEY=<由 Navigator 侧提供，ClientApp scoped>
NAVI_UPSTREAM_USER_ID=<TMS 侧当前测试用户或服务账号 ID>
NAVI_UPSTREAM_USER_TOKEN=<可选，按 TMS 用户授权方案填写>
NAVI_AGENT_CODE=<由 Navigator/TMS 双方确认>
NAVI_MODEL_CONFIG_ID=<可选；如果为空则使用 ClientApp 默认模型授权>
NAVI_POLL_INTERVAL_SECONDS=4
```

## 5. 需要向 TMS 侧提供或共同确认的 Key/配置

以下凭证不要写入 Git，不要放入公开文档。建议通过公司密码库、一次性密文、加密邮件或线下安全渠道发放。

| 类别 | 变量/名称 | 用途 | 发放方 | 备注 |
| --- | --- | --- | --- | --- |
| Navigator 地址 | `NAVI_BASE_URL` | TMS CLI/API 访问 Navigator | Navigator | 当前为 `http://192.168.31.81:8112` |
| 租户标识 | `NAVI_TENANT_ID` | 上游 ClientApp 归属租户 | Navigator | 需要在平台侧确认/创建 |
| ClientApp ID | `NAVI_CLIENT_APP_ID` | TMS 应用身份 | Navigator | 建议命名为 `tms-x3-dev-demo` 或相近名称 |
| ClientApp Key | `NAVI_CLIENT_APP_KEY` | TMS 运行时换取 token | Navigator | 与 secret 配套 |
| ClientApp Secret | `NAVI_CLIENT_APP_SECRET` | TMS 运行时换取 token | Navigator | 明文仅安全渠道发放 |
| Control API Key | `NAVI_CONTROL_API_KEY` | TMS 侧执行 agent/skill/model grant 管理 | Navigator | 必须是 ClientApp scoped，不给 tenant admin key |
| Upstream User ID | `NAVI_UPSTREAM_USER_ID` | 标识 TMS 用户或测试服务账号 | TMS | 可先用固定测试用户 |
| Upstream User Token | `NAVI_UPSTREAM_USER_TOKEN` | 可选；承载 TMS 用户态授权 | TMS/Navigator 协商 | 不应进入 LLM 上下文 |
| Agent Code | `NAVI_AGENT_CODE` | 指定 TMS 调用的 Agent/Skill | 双方确认 | 例如 TMS X3 业务 Agent |
| Model Config ID | `NAVI_MODEL_CONFIG_ID` | 指定默认 LLM 配置 | Navigator 或 TMS | 可由 ClientApp grant 设置默认 |
| Sharing Key | `X-Sharing-Key` | 简单外部调用 `/api/v1/shared/ask` | Navigator | 如果 TMS 走 Upstream CLI，通常不优先使用 Sharing Key |
| TMS 回调 Token | `X-TMS-Agent-Token` | Navigator 调 TMS REST adapter 时的 TMS 入站鉴权 | TMS | 当前默认 header 名为 `X-TMS-Agent-Token` |
| TMS 上游地址 | `TMS_X3_AGENT_UPSTREAM_URL` | Navigator 调 TMS Agent/REST adapter | TMS | dev-kvm-x3 如需直连 TMS，应配置为 TMS 可访问地址 |

## 6. Sharing Key 与 Upstream CLI 的选择

两类 Key 作用不同，不建议混用：

| 方式 | 适用场景 | 特点 |
| --- | --- | --- |
| Sharing Key | 给外部用户、定时任务或简单 AI 调用某个 Agent | 使用 `POST /api/v1/shared/ask`，header 为 `X-Sharing-Key`，接入简单但授权维度较粗 |
| Upstream CLI / ClientApp Key | TMS 这类业务系统正式接入 | 支持 ClientApp、upstream user、agent/skill grant、model grant、session/message 查询，授权边界更清楚 |

对 TMS 正式测试建议使用 Upstream CLI / ClientApp Key。Sharing Key 可作为临时 smoke 或外部 demo 口径使用。

## 7. 本次踩坑与沉淀

1. 远端 Maven 缺少内部 `com.foggysource:*` 依赖，首次构建失败。临时方案是同步本地 `.m2/repository/com/foggysource` 到远端。长期应改为内网 Nexus 可访问、CI 构建产物发布，或部署包携带离线 Maven repo。
2. RabbitMQ 虽然在主 `docker-compose.yml` 中标注为暂停，但后端 Actuator health 会检查 RabbitMQ；不启动 RabbitMQ 时后端进程可启动，但 health 为 `DOWN/503`。
3. Ubuntu 运行 Worker 需要 `python3-venv`，已补入 bootstrap 脚本。
4. Claude Agent Worker 需要安装 `@anthropic-ai/claude-code`，否则 health 中 `claude_cli_available=false`；当前已安装并验证为 `true`。
5. Code Server 下载依赖 GitHub release，远端当前访问 GitHub 443 超时；后续应改成 OBS/内网包分发。
6. dev-kvm-x3 当前适合测试/demo 合并环境。后续测试与外部 demo 压力或权限要求提高后，应拆分独立环境和独立 ClientApp/Key。

## 8. 下一步建议

1. Navigator 侧创建或确认 TMS 专用 ClientApp，并发放 ClientApp scoped 凭证。
2. Navigator 侧为 TMS ClientApp 配置 Agent/Skill grant 和默认 Model grant。
3. TMS 侧安装 Navigator Upstream CLI，写入 `.navigator/upstream.env`，执行 `config check`、`runtime-token`、`verify-agent-readiness`。
4. 双方确认 TMS REST adapter 地址和入站鉴权 header/token，必要时在 `dev-kvm-x3` 配置 `TMS_X3_AGENT_UPSTREAM_URL`。
5. 完成一条端到端 smoke：TMS CLI 发起 `ask`，Navigator 分派 Worker，必要时回调 TMS REST adapter，TMS 侧轮询 messages 验证结果。
6. 将部署方式升级为 release artifact 部署，减少远端构建和公网下载依赖。
