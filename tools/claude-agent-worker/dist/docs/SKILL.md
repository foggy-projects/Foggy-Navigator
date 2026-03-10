---
name: claude-worker-install
description: Claude Agent Worker 安装、升级与运维指南。当用户需要在目标机器安装 Worker、执行升级、使用 CLI 命令、排查安装问题时使用。触发词：/cw-install, /worker-install, 提及"安装worker"、"worker升级"、"claude-worker命令"、"worker状态"。
---

# Claude Agent Worker 安装与运维指南

在目标机器上安装、配置、升级和管理 Claude Agent Worker 服务。

> 本文档随 Worker 安装包分发，安装后位于 `~/.claude-worker/docs/SKILL.md`

## 安装目录结构

```
~/.claude-worker/
  ├── bin/claude-worker      ← CLI 入口
  ├── src/agent_worker/      ← Python 源码
  ├── .venv/                 ← Python 虚拟环境
  ├── .env                   ← 运行配置
  ├── VERSION                ← 版本文件
  ├── docs/SKILL.md          ← 本文档
  └── logs/                  ← 运行日志
```

## 一键安装

### Linux/Mac

```bash
curl -sSL https://你的OBS地址/claude-worker/install.sh | bash
```

### Windows (PowerShell)

```powershell
irm https://你的OBS地址/claude-worker/install.ps1 | iex
```

### 安装完成后

1. **添加 PATH**（安装脚本会提示具体命令）：
   ```bash
   # Linux/Mac — 添加到 ~/.bashrc 或 ~/.zshrc:
   export PATH="$HOME/.claude-worker/bin:$PATH"
   ```
   ```powershell
   # Windows — 安装脚本会自动添加到用户 PATH
   ```

2. **编辑配置** `~/.claude-worker/.env`：
   ```env
   AGENT_WORKER_PORT=3031
   AGENT_WORKER_WORKER_NAME=your-machine-name
   AGENT_WORKER_WORKER_TOKEN=your-secret-token
   AGENT_WORKER_ALLOWED_CWDS=["/home/user/projects"]
   ```

3. **启动服务**：
   ```bash
   claude-worker start
   ```

## CLI 命令参考

```bash
claude-worker start      # 启动 Worker 服务（后台运行）
claude-worker stop       # 停止 Worker 服务
claude-worker status     # 查看状态（版本、端口、PID、健康检查）
claude-worker version    # 显示版本号
claude-worker logs       # 实时查看日志（tail -f）
claude-worker upgrade    # 自动升级到最新版
claude-worker help       # 帮助信息
```

### start

启动 Worker 服务。读取 `~/.claude-worker/.env` 中的配置，在后台启动 uvicorn 进程。

```bash
claude-worker start
# Worker started on port 3031 (PID: 12345)
```

### stop

停止正在运行的 Worker 进程。

```bash
claude-worker stop
# Worker stopped (PID: 12345)
```

### status

查看 Worker 运行状态，包括版本号、端口、PID、健康检查结果。

```bash
claude-worker status
# Claude Agent Worker v0.1.0
# Status: RUNNING (PID: 12345)
# Port: 3031
# Health: OK
```

### upgrade

自动检测并升级到最新版本。

```bash
# 自动升级（从 OBS 拉取最新版）
claude-worker upgrade

# 手动升级（使用本地安装包）
claude-worker upgrade /path/to/claude-worker-0.2.0-linux.tar.gz
```

**升级流程：**
1. 从 `.env` 读取 `CLAUDE_WORKER_URL`
2. 请求 `<URL>/latest.json` 获取最新版本号
3. 对比本地 `VERSION` 文件
4. 如有新版，下载对应 OS 的安装包
5. 解压后运行 `install.sh --upgrade`（保留 `.env`，重建 venv）

**升级优先级：**
1. 本地安装包（命令行参数传入路径）
2. `CLAUDE_WORKER_URL`（OBS/HTTP，从 `.env` 或环境变量读取）
3. `CLAUDE_WORKER_REPO`（GitHub Releases，需配合 GITHUB_TOKEN）

## 配置说明

`~/.claude-worker/.env` 文件：

```env
# ---- Worker 运行配置 ----
AGENT_WORKER_PORT=3031                              # 监听端口
AGENT_WORKER_WORKER_NAME=dev-machine-01             # Worker 名称（显示在平台上）
AGENT_WORKER_WORKER_TOKEN=your-secret-token         # 认证 Token
AGENT_WORKER_ALLOWED_CWDS=["/home/user/projects"]   # 允许操作的目录列表

# ---- 自动升级 URL（远程安装时自动写入）----
CLAUDE_WORKER_URL=https://your-bucket.obs.cn-north-4.myhuaweicloud.com/claude-worker
```

## 常见问题

### 安装时 bash 脚本报错 `\r` 或 BOM

**原因**：安装包中的脚本可能包含 Windows 行尾符。
**解决**：
```bash
sed -i 's/\r$//' ~/.claude-worker/install.sh
# 或去 BOM:
sed -i '1s/^\xEF\xBB\xBF//' ~/.claude-worker/install.sh
```
> 注意：v0.1.0 及以上版本已修复此问题（打包时自动转换行尾）。

### python3 venv 创建失败

**原因**：缺少 `python3.x-venv` 包（Ubuntu/Debian）。
**解决**：
```bash
# 查看 python3 版本
python3 --version

# 安装对应版本的 venv 包
sudo apt install python3.10-venv   # 或 python3.11-venv 等

# 清理残留并重新安装
rm -rf ~/.claude-worker/.venv
curl -sSL <OBS_URL>/install.sh | bash
```

### upgrade 提示 "No source configured"

**原因**：`.env` 中没有 `CLAUDE_WORKER_URL`。
**解决**：
```bash
echo 'CLAUDE_WORKER_URL=https://your-bucket.obs.cn-north-4.myhuaweicloud.com/claude-worker' >> ~/.claude-worker/.env
```

### claude-worker 命令找不到

**原因**：`~/.claude-worker/bin` 未加入 PATH。
**解决**：
```bash
# Linux/Mac
echo 'export PATH="$HOME/.claude-worker/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc

# Windows — 通常安装脚本已自动添加，如果没有：
# 手动将 %USERPROFILE%\.claude-worker\bin 添加到系统 PATH
```

### Worker 启动后端口被占用

**原因**：另一个进程已占用配置的端口。
**解决**：
```bash
# 查看端口占用
lsof -i :3031       # Linux/Mac
netstat -ano | findstr 3031   # Windows

# 修改端口
# 编辑 ~/.claude-worker/.env:
AGENT_WORKER_PORT=3032
```

### 升级后配置丢失

正常情况下升级会保留 `.env`。如果发生异常：
```bash
# 升级前 .env 的备份位于：
ls ~/.claude-worker/.env.backup.*

# 恢复：
cp ~/.claude-worker/.env.backup.最新日期 ~/.claude-worker/.env
```
