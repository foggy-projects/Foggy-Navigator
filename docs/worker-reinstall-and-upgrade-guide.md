# Worker 安装、重装与升级指南

> 适用对象：在个人电脑、远程开发机或服务器上安装和维护 Foggy Navigator Worker 的用户。  
> 最后更新：2026-04-27

这份文档只解决三个问题：

1. 第一次怎么装 Worker。
2. 已经装过，怎么重装或升级。
3. Worker 能启动，但底层 Claude Code / Codex / Gemini 环境怎么更新。

## 快速选择

| 你现在要做什么 | 看这里 |
|----------------|--------|
| 第一次安装 Worker | [一键安装](#一键安装) |
| 之前装过，要升级 Worker 程序 | [升级已安装的-worker](#升级已安装的-worker) |
| Worker 能启动，但 Claude / Codex / Gemini CLI 太旧 | [升级底层本体](#升级底层本体) |
| 前端模型名变了，想知道真实模型 | [模型名称说明](#模型名称说明) |
| 装完后不知道是否成功 | [安装后检查](#安装后检查) |

## Worker 是什么

Foggy Navigator 通过 Worker 调用你机器上的 AI 编程工具。

| Worker | 用途 | 默认端口 | 默认安装目录 |
|--------|------|----------|--------------|
| Claude Worker | 调用 Claude Code 执行任务 | `3031` | `~/.claude-worker` |
| Codex Worker | 调用 OpenAI Codex 执行任务 | `3051` | `~/.codex-worker` |
| Gemini Worker | 调用 Gemini CLI 执行任务 | `3071` | `~/.gemini-worker` |

在 Windows 上，`~` 通常是 `%USERPROFILE%`，例如 `C:\Users\your-name`。

## 一键安装

选择你要安装的 Worker，复制对应命令执行即可。

### Claude Worker

Linux / macOS：

```bash
curl -sSL https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-worker/install.sh | bash
```

Windows PowerShell：

```powershell
irm https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-worker/install.ps1 | iex
```

### Codex Worker

Linux / macOS：

```bash
curl -sSL https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/codex-worker/install.sh | bash
```

Windows PowerShell：

```powershell
irm https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/codex-worker/install.ps1 | iex
```

### Gemini Worker

Linux / macOS：

```bash
curl -sSL https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/gemini-worker/install.sh | bash
```

Windows PowerShell：

```powershell
irm https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/gemini-worker/install.ps1 | iex
```

安装脚本会自动下载最新版安装包。安装完成后，会在 `.env` 中写入升级地址，后续可以直接运行 `xxx-worker upgrade`。

## 安装后检查

安装完成后，重新打开终端，然后执行：

```powershell
claude-worker status
codex-worker status
gemini-worker status
```

只安装了其中一个 Worker，就只执行对应命令。

如果提示命令不存在，说明 PATH 还没生效。按安装脚本最后的提示，把 `bin` 目录加入 PATH，然后重新打开终端。

也可以直接检查端口：

```powershell
Invoke-RestMethod http://127.0.0.1:3031/health
Invoke-RestMethod http://127.0.0.1:3051/health
Invoke-RestMethod http://127.0.0.1:3071/health
```

| Worker | 健康检查重点 |
|--------|--------------|
| Claude Worker | `claude_cli_available=true` |
| Codex Worker | Codex CLI 可用，或已经配置有效 API Key |
| Gemini Worker | `gemini_cli_available=true`，`gemini_auth_configured=true` |

## 升级已安装的 Worker

如果是通过一键安装脚本安装的 Worker，优先用下面的命令升级：

```powershell
claude-worker upgrade
codex-worker upgrade
gemini-worker upgrade
```

只升级你实际安装的 Worker。

升级会保留原来的 `.env` 配置。建议升级前手动备份一份：

```powershell
Copy-Item ~/.claude-worker/.env ~/.claude-worker/.env.backup -ErrorAction SilentlyContinue
Copy-Item ~/.codex-worker/.env ~/.codex-worker/.env.backup -ErrorAction SilentlyContinue
Copy-Item ~/.gemini-worker/.env ~/.gemini-worker/.env.backup -ErrorAction SilentlyContinue
```

如果管理员给的是离线安装包，也可以用本地文件升级：

```powershell
claude-worker upgrade C:\path\to\claude-worker-x.y.z-windows.zip
codex-worker upgrade C:\path\to\codex-worker-x.y.z-windows.zip
gemini-worker upgrade C:\path\to\gemini-worker-x.y.z-windows.zip
```

如果只是想升级 Codex SDK（`@openai/codex-sdk`）而不动 Worker 主程序，可以用 `codex-worker upgrade-sdk`，详见[升级底层本体](#升级底层本体) → Codex 环境。

## 重新安装

通常不需要删除目录再重装。直接再次执行一键安装命令即可，脚本会识别已有安装并覆盖程序文件，同时保留 `.env`。

如果你手上是解压后的安装包，在安装包目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\install.ps1 -Upgrade
```

重装后再运行一次状态检查：

```powershell
claude-worker status
codex-worker status
gemini-worker status
```

## 启动与停止

日常操作只需要这些命令：

| 操作 | Claude | Codex | Gemini |
|------|--------|-------|--------|
| 启动 | `claude-worker start` | `codex-worker start` | `gemini-worker start` |
| 停止 | `claude-worker stop` | `codex-worker stop` | `gemini-worker stop` |
| 状态 | `claude-worker status` | `codex-worker status` | `gemini-worker status` |
| 日志 | `claude-worker logs` | `codex-worker logs` | `gemini-worker logs` |
| 升级 Worker | `claude-worker upgrade` | `codex-worker upgrade` | `gemini-worker upgrade` |
| 升级底层 SDK | — | `codex-worker upgrade-sdk` | — |

## 升级底层本体

Worker 程序升级和底层工具升级不是一回事。

| 你升级的是 | 解决什么问题 |
|------------|--------------|
| Worker 程序 | Foggy 的任务接口、SSE、模型映射、启动脚本等 |
| 底层本体 | Claude Code、Codex SDK / CLI、Gemini CLI 的能力和版本 |

### Claude Code 环境

如果你使用仓库里的 `tools/claude-agent-worker`，运行：

```powershell
cd tools\claude-agent-worker
powershell -ExecutionPolicy Bypass -File .\update.ps1
```

这个脚本会升级 `claude-agent-sdk`，并刷新其中携带的 Claude Code CLI。

订阅模式还需要确认本机已经登录：

```powershell
claude auth status
claude login
```

如果要升级全局 Claude Code CLI：

```powershell
npm install -g @anthropic-ai/claude-code
```

### Codex 环境

如果你是通过一键安装脚本装的 Codex Worker，运行：

```powershell
codex-worker upgrade-sdk
```

这条命令做的事：

1. 停掉本地 Codex Worker
2. 在安装目录里执行 `npm install @openai/codex-sdk@latest --omit=dev`，同时升级它捆绑的 `@openai/codex` CLI（`@openai/codex-sdk` 通过 `optionalDependencies` 拉对应平台的二进制）
3. 重启 Worker
4. 30 秒内轮询 `/health`，确认升级后能正常启动

支持的选项：

| 选项 (Windows) | 选项 (Linux/macOS) | 含义 |
|----------------|---------------------|------|
| `-SdkVersion 0.130.0` | `--sdk-version 0.130.0` | 锁定到指定版本而不是 latest |
| `-NoRestart` | `--no-restart` | 升级完不自动重启，方便先做其他检查 |

如果新 SDK 起不来（health 探测 30 秒超时），脚本会提示：

```
Recovery: run 'codex-worker upgrade' to reinstall the worker-pinned SDK from OBS.
```

`codex-worker upgrade` 会从 OBS 重新拉一份 worker archive 解压安装，archive 里 `package-lock.json` 会把 SDK 还原到该 worker 版本锁定的版本，相当于天然的回滚通道。

如果你直接用源码（`tools/codex-agent-worker`），路径稍有不同：

```powershell
cd tools\codex-agent-worker
powershell -ExecutionPolicy Bypass -File .\update.ps1
```

源码版的 `update.ps1` 多跑一步 `npm run typecheck`；OBS 安装包没有 devDependencies 也没有 `src/`，所以发布版的 `update.ps1` 跳过 typecheck，改用启动后的 `/health` 探测兜底。

订阅模式需要确认 Worker 主机已经登录：

```powershell
codex login
```

如果要升级全局 Codex CLI（不在 Worker 里使用，只在本地终端用）：

```powershell
npm install -g @openai/codex
```

### Gemini 环境

Gemini Worker 当前没有单独的 `update.ps1`。它直接调用 PATH 中的 `gemini` 命令。

升级 Gemini CLI：

```powershell
npm install -g @google/gemini-cli@latest
gemini --version
```

确认 Gemini CLI 可执行：

```powershell
gemini -p "ping" --output-format stream-json --yolo --skip-trust
```

如使用 API Key，可在环境变量或 `.env` 中配置：

```properties
GEMINI_API_KEY=your-key
```

## 模型名称说明

本次迭代后，前端尽量展示稳定名称，例如 `Codex Deep`、`Gemini Flash`。真实模型版本由 Worker 在执行前解析。这样以后模型升级时，只改 Worker 配置，不需要用户重新理解一堆版本号。

### Codex

| 前端显示 | 配置值 | 当前实际指向 |
|----------|--------|--------------|
| Codex Latest | `codex-latest` | `gpt-5.5` |
| Codex Fast | `codex-fast` | `gpt-5.5:low` |
| Codex Deep | `codex-deep` | `gpt-5.5:high` |
| Codex Extra High | `codex-xhigh` | `gpt-5.5:xhigh` |
| Codex Mini | `codex-mini` | `gpt-5.4-mini` |

所以，`Codex Deep` 不是一个单独真实模型名。它表示：使用 `gpt-5.5`，并启用 `high` reasoning effort。`Codex Extra High` 则启用更高一层的 `xhigh` reasoning effort。

管理员可以在 Codex Worker 的 `.env` 中覆盖映射：

```properties
CODEX_DEFAULT_MODEL=codex-latest
CODEX_MODEL_ALIASES={"codex-latest":"gpt-5.5","codex-fast":"gpt-5.5:low","codex-deep":"gpt-5.5:high","codex-xhigh":"gpt-5.5:xhigh","codex-mini":"gpt-5.4-mini"}
```

### Gemini

| 前端显示 | 配置值 | 当前实际指向 |
|----------|--------|--------------|
| Gemini Pro | `gemini-pro` | `auto-gemini-3` |
| Gemini Flash | `gemini-flash` | `gemini-3-flash-preview` |
| Gemini Flash Lite | `gemini-flash-lite` | `gemini-3.1-flash-lite-preview` |

管理员可以在 Gemini Worker 的 `.env` 中覆盖映射：

```properties
GEMINI_DEFAULT_MODEL=gemini-pro
GEMINI_MODEL_ALIASES=gemini-pro=auto-gemini-3,gemini-flash=gemini-3-flash-preview,gemini-flash-lite=gemini-3.1-flash-lite-preview
```

### Claude

Claude Worker 仍使用 Claude Code 支持的模型名或 alias。平台不在文档里写死 Claude alias 到精确版本号的映射，避免把已经变化的官方 alias 解释成过期版本。

## Navigator 中需要检查什么

Worker 装好后，还需要在 Navigator 后台确认配置没有指错。

| Worker | Base URL 示例 | Backend |
|--------|---------------|---------|
| Claude | `http://<worker-host>:3031` | `CLAUDE_CODE` |
| Codex | `http://<worker-host>:3051` | `OPENAI_CODEX` |
| Gemini | `http://<worker-host>:3071` | `GEMINI_CLI` |

重点检查：

1. Worker 地址是否是正确机器和端口。
2. Token 是否和 Worker `.env` 一致。
3. 目录 Auth 或模型配置是否选了正确 backend。
4. Gemini 不要误填到 Codex Worker 端口，Codex 也不要误填到 Gemini Worker 端口。

如果填错，常见现象是健康检查字段不对，或者任务日志里出现另一个 Worker 的名称。

## 常见问题

### 命令找不到

重新打开终端。如果仍然不行，把安装目录下的 `bin` 加入 PATH。

默认路径：

| Worker | bin 目录 |
|--------|----------|
| Claude | `~/.claude-worker/bin` |
| Codex | `~/.codex-worker/bin` |
| Gemini | `~/.gemini-worker/bin` |

### Worker 显示离线

先在 Worker 机器上检查本地状态：

```powershell
claude-worker status
codex-worker status
gemini-worker status
```

再从 Navigator 服务器确认是否能访问对应端口。跨机器部署时，注意防火墙、VPN、内网 IP 和端口暴露。

### 订阅模式执行失败

通常是本机 CLI 没登录或登录态过期。

```powershell
claude login
codex login
gemini --version
```

Gemini 如果使用本地登录，确认 `gemini -p "ping" --output-format stream-json --yolo --skip-trust` 能跑通。

### 升级后配置会不会丢

正常不会。安装和升级脚本会保留 `.env`。但升级前仍建议备份：

```powershell
Copy-Item ~/.claude-worker/.env ~/.claude-worker/.env.backup -ErrorAction SilentlyContinue
Copy-Item ~/.codex-worker/.env ~/.codex-worker/.env.backup -ErrorAction SilentlyContinue
Copy-Item ~/.gemini-worker/.env ~/.gemini-worker/.env.backup -ErrorAction SilentlyContinue
```

## 管理员参考

源码目录方式主要用于开发机和内网调试机。

| Worker | 源码目录 | 源码升级脚本 | OBS 安装包升级 |
|--------|----------|--------------|----------------|
| Claude | `tools/claude-agent-worker` | `update.ps1` / `update.sh` | 暂未透出独立 SDK 升级命令，需重装 |
| Codex | `tools/codex-agent-worker` | `update.ps1` / `update.sh` | `codex-worker upgrade-sdk`（同时打包了 `release/update.ps1` / `release/update.sh`） |
| Gemini | `tools/gemini-agent-worker` | 无单独脚本，升级全局 `gemini` CLI | 同左 |

源码目录启动：

```powershell
powershell -ExecutionPolicy Bypass -File tools\claude-agent-worker\start.ps1
powershell -ExecutionPolicy Bypass -File tools\codex-agent-worker\start.ps1
powershell -ExecutionPolicy Bypass -File tools\gemini-agent-worker\start.ps1
```

源码目录停止：

```powershell
powershell -ExecutionPolicy Bypass -File tools\claude-agent-worker\stop.ps1
powershell -ExecutionPolicy Bypass -File tools\codex-agent-worker\stop.ps1
powershell -ExecutionPolicy Bypass -File tools\gemini-agent-worker\stop.ps1
```

## 外部 CLI 参考

- Claude Code 官方安装文档：https://docs.anthropic.com/en/docs/claude-code/getting-started
- OpenAI Codex CLI 官方帮助：https://help.openai.com/en/articles/11096431-openai-codex-ci-getting-started
- Gemini CLI 官方仓库：https://github.com/google-gemini/gemini-cli
