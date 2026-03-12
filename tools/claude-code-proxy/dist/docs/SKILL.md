---
name: claude-proxy-install
description: Claude Code Proxy 安装、升级与运维指南。当用户需要在目标机器安装 Proxy、执行升级、使用 CLI 命令、排查安装问题时使用。触发词：/proxy-install, 提及"安装proxy"、"proxy升级"、"claude-code-proxy命令"、"proxy状态"。
---

# Claude Code Proxy 安装与运维指南

在目标机器上安装、配置、升级和管理 Claude Code Proxy 服务。

> 本文档随 Proxy 安装包分发，安装后位于 `~/.claude-code-proxy/docs/SKILL.md`

## 安装目录结构

```
~/.claude-code-proxy/
  ├── bin/claude-code-proxy    ← CLI 入口
  ├── src/                     ← Python 源码
  ├── .venv/                   ← Python 虚拟环境
  ├── .env                     ← 运行配置
  ├── start_proxy.py           ← 启动入口
  ├── VERSION                  ← 版本文件
  ├── docs/SKILL.md            ← 本文档
  └── logs/                    ← 运行日志
```

## 一键安装

### Linux/Mac

```bash
curl -sSL https://你的OBS地址/claude-code-proxy/install.sh | bash
```

### Windows (PowerShell)

```powershell
irm https://你的OBS地址/claude-code-proxy/install.ps1 | iex
```

### 安装完成后

1. **添加 PATH**（安装脚本会提示具体命令）：
   ```bash
   # Linux/Mac — 添加到 ~/.bashrc 或 ~/.zshrc:
   export PATH="$HOME/.claude-code-proxy/bin:$PATH"
   ```
   ```powershell
   # Windows — 安装脚本会自动添加到用户 PATH
   ```

2. **编辑配置** `~/.claude-code-proxy/.env`：
   ```env
   OPENAI_API_KEY="sk-your-openai-api-key"
   OPENAI_BASE_URL="https://api.openai.com/v1"
   BIG_MODEL="gpt-4o"
   MIDDLE_MODEL="gpt-4o"
   SMALL_MODEL="gpt-4o-mini"
   PORT=8082
   ```

3. **启动服务**：
   ```bash
   claude-code-proxy start
   ```

4. **使用 Claude Code**：
   ```bash
   ANTHROPIC_BASE_URL=http://localhost:8082 claude
   ```

## CLI 命令参考

```bash
claude-code-proxy start      # 启动 Proxy 服务（后台运行）
claude-code-proxy stop       # 停止 Proxy 服务
claude-code-proxy status     # 查看状态（版本、端口、PID、健康检查）
claude-code-proxy version    # 显示版本号
claude-code-proxy logs       # 实时查看日志（tail -f）
claude-code-proxy upgrade    # 自动升级到最新版
claude-code-proxy help       # 帮助信息
```

### start

启动 Proxy 服务。读取 `~/.claude-code-proxy/.env` 中的配置，在后台启动 uvicorn 进程。

```bash
claude-code-proxy start
# Proxy started on port 8082 (PID: 12345)
```

### stop

停止正在运行的 Proxy 进程。

```bash
claude-code-proxy stop
# Proxy stopped (PID: 12345)
```

### status

查看 Proxy 运行状态，包括版本号、端口、PID、健康检查结果。

```bash
claude-code-proxy status
# Claude Code Proxy v1.0.0
# Status: RUNNING (PID: 12345)
# Port: 8082
# Health: OK
```

### upgrade

自动检测并升级到最新版本。

```bash
# 自动升级（从 OBS 拉取最新版）
claude-code-proxy upgrade

# 手动升级（使用本地安装包）
claude-code-proxy upgrade /path/to/claude-code-proxy-1.1.0-linux.tar.gz
```

**升级流程：**
1. 从 `.env` 读取 `CLAUDE_PROXY_URL`
2. 请求 `<URL>/latest.json` 获取最新版本号
3. 对比本地 `VERSION` 文件
4. 如有新版，下载对应 OS 的安装包
5. 解压后运行 `install.sh --upgrade`（保留 `.env`，重建 venv）

**升级优先级：**
1. 本地安装包（命令行参数传入路径）
2. `CLAUDE_PROXY_URL`（OBS/HTTP，从 `.env` 或环境变量读取）
3. `CLAUDE_PROXY_REPO`（GitHub Releases，需配合 GITHUB_TOKEN）

## 配置说明

`~/.claude-code-proxy/.env` 文件：

```env
# ---- LLM 后端配置 ----
OPENAI_API_KEY="sk-your-openai-api-key"          # 必填：后端 API Key
OPENAI_BASE_URL="https://api.openai.com/v1"      # 后端 API 地址
BIG_MODEL="gpt-4o"                                # Claude opus 映射
MIDDLE_MODEL="gpt-4o"                             # Claude sonnet 映射
SMALL_MODEL="gpt-4o-mini"                         # Claude haiku 映射

# ---- 服务配置 ----
HOST="0.0.0.0"                                    # 监听地址
PORT=8082                                         # 监听端口
LOG_LEVEL="INFO"                                  # 日志级别

# ---- 自动升级 URL（远程安装时自动写入）----
CLAUDE_PROXY_URL=https://your-bucket.obs.cn-north-4.myhuaweicloud.com/claude-code-proxy
```

## 常见问题

### 安装时 bash 脚本报错 `\r` 或 BOM

**原因**：安装包中的脚本可能包含 Windows 行尾符。
**解决**：
```bash
sed -i 's/\r$//' ~/.claude-code-proxy/install.sh
# 或去 BOM:
sed -i '1s/^\xEF\xBB\xBF//' ~/.claude-code-proxy/install.sh
```
> 注意：v1.0.0 及以上版本已修复此问题（打包时自动转换行尾）。

### python3 venv 创建失败

**原因**：缺少 `python3.x-venv` 包（Ubuntu/Debian）。
**解决**：
```bash
# 查看 python3 版本
python3 --version

# 安装对应版本的 venv 包
sudo apt install python3.10-venv   # 或 python3.11-venv 等

# 清理残留并重新安装
rm -rf ~/.claude-code-proxy/.venv
curl -sSL <OBS_URL>/install.sh | bash
```

### upgrade 提示 "No source configured"

**原因**：`.env` 中没有 `CLAUDE_PROXY_URL`。
**解决**：
```bash
echo 'CLAUDE_PROXY_URL=https://your-bucket.obs.cn-north-4.myhuaweicloud.com/claude-code-proxy' >> ~/.claude-code-proxy/.env
```

### claude-code-proxy 命令找不到

**原因**：`~/.claude-code-proxy/bin` 未加入 PATH。
**解决**：
```bash
# Linux/Mac
echo 'export PATH="$HOME/.claude-code-proxy/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc

# Windows — 通常安装脚本已自动添加，如果没有：
# 手动将 %USERPROFILE%\.claude-code-proxy\bin 添加到系统 PATH
```

### Proxy 启动后端口被占用

**原因**：另一个进程已占用配置的端口。
**解决**：
```bash
# 查看端口占用
lsof -i :8082       # Linux/Mac
netstat -ano | findstr 8082   # Windows

# 修改端口
# 编辑 ~/.claude-code-proxy/.env:
PORT=8083
```

### 升级后配置丢失

正常情况下升级会保留 `.env`。如果发生异常：
```bash
# 升级前 .env 的备份位于临时目录
# 建议在升级前手动备份：
cp ~/.claude-code-proxy/.env ~/.claude-code-proxy/.env.backup
```
