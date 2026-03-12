# Claude 工具套件 — 安装与升级指南

> **适用对象**：全体开发人员
> **最后更新**：2026-03-12
> **版本**：Claude Code Proxy v1.0.0 / Claude Agent Worker v0.1.1

---

## 📋 总览

本指南覆盖两个工具的安装与管理：

| 工具 | 用途 | 默认端口 |
|------|------|----------|
| **Claude Code Proxy** | 将 Claude Code 请求转发到 OpenAI 兼容后端（智谱/通义/本地模型等） | 8082 |
| **Claude Agent Worker** | Claude Code 远程工人节点，接受 Navigator 平台派发的编程任务 | 3031 |

**两者是独立的**，按需安装即可。如果你只需要用 Claude Code 连第三方模型，装 Proxy 就够了；如果要接入 Navigator 平台做远程编程，装 Worker。

---

## 一、Claude Code Proxy

### 1.1 一键安装

**Linux / macOS：**
```bash
curl -sSL https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-code-proxy/install.sh | bash
```

**Windows（PowerShell 管理员）：**
```powershell
irm https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-code-proxy/install.ps1 | iex
```

### 1.2 安装后配置

安装目录：`~/.claude-code-proxy/`

**① 添加 PATH（Linux/Mac，安装脚本会提示）：**
```bash
export PATH="$HOME/.claude-code-proxy/bin:$PATH"
# 建议写入 ~/.bashrc 或 ~/.zshrc
```
> Windows 安装脚本会自动添加到用户 PATH。

**② 编辑配置文件** `~/.claude-code-proxy/.env`：

```env
# ---- 必填：后端 API 配置 ----
OPENAI_API_KEY="sk-your-api-key"
OPENAI_BASE_URL="https://api.openai.com/v1"

# ---- 模型映射 ----
BIG_MODEL="gpt-4o"           # Claude opus  → 映射到此模型
MIDDLE_MODEL="gpt-4o"        # Claude sonnet → 映射到此模型
SMALL_MODEL="gpt-4o-mini"    # Claude haiku  → 映射到此模型

# ---- 服务配置 ----
PORT=8082
HOST="0.0.0.0"
LOG_LEVEL="INFO"
```

**常用后端配置示例：**

<details>
<summary>智谱 GLM</summary>

```env
OPENAI_API_KEY="your-zhipu-key"
OPENAI_BASE_URL="https://open.bigmodel.cn/api/paas/v4"
BIG_MODEL="glm-4.7"
MIDDLE_MODEL="glm-4.7"
SMALL_MODEL="glm-4.5-air"
```
</details>

<details>
<summary>通义千问（DashScope）</summary>

```env
OPENAI_API_KEY="sk-your-dashscope-key"
OPENAI_BASE_URL="https://dashscope.aliyuncs.com/compatible-mode/v1"
BIG_MODEL="qwen-max"
MIDDLE_MODEL="qwen-max"
SMALL_MODEL="qwen-turbo"
```
</details>

<details>
<summary>本地 Ollama</summary>

```env
OPENAI_API_KEY="dummy"
OPENAI_BASE_URL="http://localhost:11434/v1"
BIG_MODEL="llama3.1:70b"
MIDDLE_MODEL="llama3.1:70b"
SMALL_MODEL="llama3.1:8b"
```
</details>

<details>
<summary>多 Key 池模式（负载均衡多个后端）</summary>

```env
# 后端 A - 智谱
OPENAI_API_KEY_A=glm-key-xxx
OPENAI_BASE_URL_A=https://open.bigmodel.cn/api/paas/v4
BIG_MODEL_A=glm-4.7
SMALL_MODEL_A=glm-4.5-air

# 后端 B - 通义
OPENAI_API_KEY_B=sk-ali-key-xxx
OPENAI_BASE_URL_B=https://dashscope.aliyuncs.com/compatible-mode/v1
BIG_MODEL_B=qwen-max
SMALL_MODEL_B=qwen-turbo

# 客户端 Key 路由（可选）
# KEY_MAPPING=sk-ant-a1:A,B;sk-ant-a2:B
```
</details>

### 1.3 启动与使用

```bash
# 启动 Proxy
claude-code-proxy start

# 使用 Claude Code（指向 Proxy）
ANTHROPIC_BASE_URL=http://localhost:8082 claude
```

### 1.4 CLI 命令

```bash
claude-code-proxy start        # 启动服务（后台运行）
claude-code-proxy stop         # 停止服务
claude-code-proxy status       # 查看状态（版本、端口、PID、健康检查）
claude-code-proxy version      # 显示版本号
claude-code-proxy logs         # 实时查看日志
claude-code-proxy upgrade      # 自动升级到最新版
claude-code-proxy help         # 帮助信息
```

### 1.5 升级

```bash
# 自动升级（推荐，从服务器拉取最新版）
claude-code-proxy upgrade

# 手动升级（使用本地安装包）
claude-code-proxy upgrade /path/to/claude-code-proxy-1.1.0-linux.tar.gz
```

> 升级会自动保留你的 `.env` 配置，无需重新填写。

### 1.6 前置要求

- **Python 3.9+**（带 venv 支持）
- 网络能访问配置的后端 API

---

## 二、Claude Agent Worker

### 2.1 一键安装

**Linux / macOS：**
```bash
curl -sSL https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-worker/install.sh | bash
```

**Windows（PowerShell 管理员）：**
```powershell
irm https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-worker/install.ps1 | iex
```

### 2.2 安装后配置

安装目录：`~/.claude-worker/`

**① 添加 PATH（Linux/Mac）：**
```bash
export PATH="$HOME/.claude-worker/bin:$PATH"
# 建议写入 ~/.bashrc 或 ~/.zshrc
```

**② 编辑配置文件** `~/.claude-worker/.env`：

```env
# ---- 必填 ----
AGENT_WORKER_PORT=3031
AGENT_WORKER_WORKER_NAME=张三-MacBook       # 你的机器名（显示在平台上）
AGENT_WORKER_WORKER_TOKEN=your-token        # 找管理员获取

# ---- 工作目录白名单（安全限制）----
# Windows: AGENT_WORKER_ALLOWED_CWDS=["D:\\projects"]
# Linux:   AGENT_WORKER_ALLOWED_CWDS=["/home/user/projects"]
AGENT_WORKER_ALLOWED_CWDS=[]

# ---- LLM 配置（三选一）----

# 方式一：订阅模式（推荐，使用本机 Claude 登录）
#   什么都不填，直接运行 `claude login` 登录即可

# 方式二：Auth Token + Proxy（通过 claude-code-proxy 中转）
# AGENT_WORKER_ANTHROPIC_AUTH_TOKEN=any-token
# AGENT_WORKER_ANTHROPIC_BASE_URL=http://localhost:8082

# 方式三：API Key（按量付费）
# AGENT_WORKER_ANTHROPIC_API_KEY=sk-ant-api03-xxx
```

### 2.3 启动与使用

```bash
# 启动 Worker
claude-worker start

# 查看状态
claude-worker status
```

启动后，Worker 会自动注册到 Navigator 平台，在平台管理页面可以看到。

### 2.4 CLI 命令

```bash
claude-worker start        # 启动 Worker（后台运行）
claude-worker stop         # 停止 Worker
claude-worker status       # 查看状态
claude-worker version      # 显示版本号
claude-worker logs         # 实时查看日志
claude-worker upgrade      # 自动升级到最新版
claude-worker help         # 帮助信息
```

### 2.5 升级

```bash
# 自动升级
claude-worker upgrade

# 手动升级
claude-worker upgrade /path/to/claude-worker-0.2.0-linux.tar.gz
```

### 2.6 前置要求

- **Python 3.10+**（带 venv 支持）
- **Claude Code CLI**：`npm install -g @anthropic-ai/claude-code`
- Claude 账号已登录：`claude login`

---

## 三、常见问题

### Q：安装时报错 `\r` 或 BOM 字符？

```bash
sed -i 's/\r$//' ~/.claude-code-proxy/install.sh
# 或
sed -i 's/\r$//' ~/.claude-worker/install.sh
```
> v1.0.0+ 已修复此问题。

### Q：python3 venv 创建失败？（Ubuntu/Debian）

```bash
# 查看版本
python3 --version

# 安装对应 venv 包
sudo apt install python3.10-venv   # 或 python3.11-venv 等
```

### Q：命令找不到（command not found）？

确认 bin 目录已加入 PATH：
```bash
# Proxy
echo 'export PATH="$HOME/.claude-code-proxy/bin:$PATH"' >> ~/.bashrc

# Worker
echo 'export PATH="$HOME/.claude-worker/bin:$PATH"' >> ~/.bashrc

source ~/.bashrc
```

### Q：端口被占用？

```bash
# 查看端口占用
lsof -i :8082       # Linux/Mac
netstat -ano | findstr 8082   # Windows

# 修改端口：编辑对应 .env 文件
# Proxy: PORT=8083
# Worker: AGENT_WORKER_PORT=3032
```

### Q：升级提示 "No source configured"？

```bash
# Proxy
echo 'CLAUDE_PROXY_URL=https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-code-proxy' >> ~/.claude-code-proxy/.env

# Worker
echo 'CLAUDE_WORKER_URL=https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-worker' >> ~/.claude-worker/.env
```

### Q：升级后配置会丢失吗？

不会。升级脚本会自动保留 `.env` 配置文件。建议升级前手动备份一份：
```bash
cp ~/.claude-code-proxy/.env ~/.claude-code-proxy/.env.backup
cp ~/.claude-worker/.env ~/.claude-worker/.env.backup
```

---

## 四、典型场景

### 场景 A：只用 Claude Code + 第三方模型

只装 **Proxy**：
```bash
# 1. 安装
curl -sSL https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-code-proxy/install.sh | bash

# 2. 配置 .env（填入你的 API Key 和模型）

# 3. 启动
claude-code-proxy start

# 4. 使用
ANTHROPIC_BASE_URL=http://localhost:8082 claude
```

### 场景 B：接入 Navigator 平台 + 订阅版 Claude

只装 **Worker**：
```bash
# 1. 安装
curl -sSL https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-worker/install.sh | bash

# 2. 配置 .env（填入机器名和 Token）

# 3. Claude 登录
claude login

# 4. 启动
claude-worker start
```

### 场景 C：接入 Navigator 平台 + 通过 Proxy 使用第三方模型

装 **Proxy** + **Worker**：
```bash
# 1. 两个都装
curl -sSL https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-code-proxy/install.sh | bash
curl -sSL https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-worker/install.sh | bash

# 2. 配置 Proxy .env（API Key、模型映射）
# 3. 配置 Worker .env，LLM 部分指向本地 Proxy：
#    AGENT_WORKER_ANTHROPIC_AUTH_TOKEN=any-token
#    AGENT_WORKER_ANTHROPIC_BASE_URL=http://localhost:8082

# 4. 启动
claude-code-proxy start
claude-worker start
```

---

## 五、安装目录一览

### Claude Code Proxy (`~/.claude-code-proxy/`)

```
├── bin/claude-code-proxy    ← CLI 入口
├── src/                     ← Python 源码
├── .venv/                   ← 虚拟环境
├── .env                     ← 配置文件（你需要编辑这个）
├── start_proxy.py           ← 启动入口
├── VERSION                  ← 版本文件
├── docs/SKILL.md            ← 详细运维文档
└── logs/proxy.log           ← 运行日志
```

### Claude Agent Worker (`~/.claude-worker/`)

```
├── bin/claude-worker        ← CLI 入口
├── src/agent_worker/        ← Python 源码
├── .venv/                   ← 虚拟环境
├── .env                     ← 配置文件（你需要编辑这个）
├── VERSION                  ← 版本文件
├── docs/SKILL.md            ← 详细运维文档
└── logs/worker.log          ← 运行日志
```

---

## 六、快速参考卡

| 操作 | Claude Code Proxy | Claude Agent Worker |
|------|-------------------|---------------------|
| **安装** | `curl -sSL .../claude-code-proxy/install.sh \| bash` | `curl -sSL .../claude-worker/install.sh \| bash` |
| **配置** | `~/.claude-code-proxy/.env` | `~/.claude-worker/.env` |
| **启动** | `claude-code-proxy start` | `claude-worker start` |
| **停止** | `claude-code-proxy stop` | `claude-worker stop` |
| **状态** | `claude-code-proxy status` | `claude-worker status` |
| **日志** | `claude-code-proxy logs` | `claude-worker logs` |
| **升级** | `claude-code-proxy upgrade` | `claude-worker upgrade` |
| **端口** | 8082（`PORT`） | 3031（`AGENT_WORKER_PORT`） |
| **Python** | 3.9+ | 3.10+ |
| **额外依赖** | 无 | Claude Code CLI + claude login |

---

> **遇到问题？** 联系管理员或查看各工具安装目录下的 `docs/SKILL.md` 获取完整运维文档。
