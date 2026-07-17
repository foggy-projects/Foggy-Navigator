# 本机上游联调说明

## 文档作用

本文记录当前开发机上的 Navigator 与上游项目联调拓扑、操作边界和本地栈脚本约定。`CLAUDE.md` 只保留高频规则，具体联调细节以本文为准。

## 当前工作区拓扑

当前同级 workspace 根目录为 `/home/sa/workspace`。

| 项目 | 路径 | 角色 |
|------|------|------|
| Navigator | `/home/sa/workspace/Foggy-Navigator` | 当前仓库，负责平台、会话、Worker 接入、Open SDK、前端与移动端 |
| TMS 上游 | `/home/sa/workspace/tms-x3` | SaaS 业务系统上游，主要通过 Biz Worker / upstream CLI / Open SDK 接入 Navigator |
| SIM 上游 | `/home/sa/workspace/foggy-world-sim` | 软件系统型上游，可按用户明确授权开放更宽的本机能力 |
| Foggy Data MCP | `/home/sa/workspace/foggy-data-mcp` | 数据与 MCP 相关上游/辅助工程，按具体任务确认是否参与联调 |

如发现同名历史工作区、旧进程或端口占用，不要只根据端口判断归属；先确认进程命令行、工作目录和启动脚本来源。

## 上游协作边界

- 默认只修改 Navigator 当前仓库。
- 不直接修改 TMS、SIM 或 Foggy Data MCP 代码，除非用户在当前任务中明确要求跨仓修改。
- 需要上游配合时，优先输出 GitLab/GitHub issue、handoff、复现步骤或所需配置项。
- TMS 按 SaaS 业务系统处理，优先通过 Biz Worker、upstream CLI、Open SDK、公开 API 和租户配置联调。
- SIM 按软件系统处理；需要开放文件、命令或 Worker 能力时，先明确能力范围和目标路径。

## 凭据与本地配置

- 不把明文 API key、admin key、credential secret 写入仓库文档。
- TMS 当前本地 upstream 配置位于 `/home/sa/workspace/tms-x3/.navigator/upstream.env`，需要更新时只说明目标变量名和操作步骤，不在文档里复制密钥值。
- 历史 Windows 环境中的 credential rotate 记录仅作为排查线索；迁移到当前 workspace 后，以当前服务、当前数据库和当前 `.navigator/upstream.env` 为准重新检查。
- 交付给上游的密钥文件、脱敏响应、临时验证输出统一放到 `temp/` 或对应版本 evidence 目录，不写入仓库根目录。

## 本地栈与端口

| 服务 | 默认端口 | 常用脚本 |
|------|------|------|
| Navigator 后端 | 8112 | `scripts/start-launcher.sh`、`scripts/start-launcher.ps1` |
| Navigator 前端 | 5174 | `scripts/start-frontend.sh`、`scripts/start-frontend.ps1` |
| Claude Worker | 3031 | `tools/claude-agent-worker/start.sh`、`tools/claude-agent-worker/start.ps1` |
| Codex Worker | 3051 | `tools/codex-agent-worker/start.sh`、`tools/codex-agent-worker/start.ps1` |
| Gemini Worker | 3071 | `tools/gemini-agent-worker/start.ps1` |
| 本地 LangGraph Biz Worker | 3061 | `tools/langgraph-biz-worker/start.ps1`，或 `scripts/local-dev-stack.sh` 自动拉起 |
| WSL LangGraph Biz Worker | 3161 | `tools/langgraph-biz-worker/restart-wsl-3161.sh`、`tools/langgraph-biz-worker/restart-wsl-3161.ps1` |

Linux/WSL 本机栈优先使用：

```bash
scripts/local-dev-stack.sh status
scripts/local-dev-stack.sh restart --skip-build
scripts/local-dev-stack.sh restart --skip-build  # 默认同步当前 LangGraph Biz Worker 到 WSL 3161
scripts/local-dev-stack.sh restart --skip-build --no-sync-wsl-biz-source
```

Windows 本机栈优先使用：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/local-dev-stack.ps1 status
powershell -ExecutionPolicy Bypass -File scripts/local-dev-stack.ps1 restart -SkipBuild
powershell -ExecutionPolicy Bypass -File scripts/local-dev-stack.ps1 restart -SkipBuild -SyncWslBizSource
```

`scripts/start-launcher.ps1` 在未设置 `BUSINESS_AGENT_DEV_SYNC_WORKER_URL` 时会默认指向 `http://127.0.0.1:3161`。`application.yml` 自身默认值是 `http://localhost:3061`，`scripts/start-launcher.sh` 不会覆盖该变量。需要固定联调目标时，显式设置 `BUSINESS_AGENT_DEV_SYNC_WORKER_URL`。

Linux/WSL 的 `local-dev-stack.sh start|restart` 默认将当前仓库的 LangGraph Biz Worker runtime source、`pyproject.toml`、内置 Skills 和文档同步到目标 WSL Worker，并以目标 Worker 的 Python 环境运行 `pip install -e .`，再启动 3161。它保留目标 `.env`、public Skills、日志和状态目录；`stop` 与 `status` 不会同步。`--no-sync-wsl-biz-source` 可仅重启已安装内容，旧的 `--sync-wsl-biz-source` 仍可使用但已是默认行为。

Claude 与 Codex Worker 均由该 Linux 脚本直接从当前仓库的 `tools/` 源码目录启动，因此重启本身已经使用当前 checkout；它不会升级全局 Claude/Codex CLI 或 OBS 发布版。`codex-biz-worker` 是 Codex Worker 上的业务路由，并非独立的 WSL 服务。

## dev-kvm-x3 发布

`dev-kvm-x3` 是对外测试和演示环境。用户明确要求“更新 x3”或“更新 dev-kvm-x3”时，优先参考 `deploy/dev-kvm-x3/README.md`，再使用该目录下脚本：

- 主应用镜像：`scripts/20-build-and-push-images.sh`、`scripts/30-deploy-by-image.sh`、`scripts/32-status-check.sh`
- Worker 同步：`scripts/41-update-workers-from-obs.sh`、`scripts/42-check-workers.sh`
- 一体化入口：`scripts/deploy.sh`

发布前按 README 确认远端 `release.env`、镜像 tag、worker 包版本和目标主机，避免把本地联调配置带到演示环境。
