# Claude Agent Worker 本地部署指南

## 前提条件

| 依赖 | 要求 | 检查命令 |
|------|------|----------|
| Python | >= 3.10 | `python --version` |
| Claude Code CLI | 已安装并可用 | `claude --version` |
| Claude 登录状态 | 已登录（订阅模式）或有 API Key | `claude auth status` |

> **Claude Code CLI 安装**：如果尚未安装，参考 https://docs.anthropic.com/en/docs/claude-code
>
> **订阅模式**：运行 `claude login` 完成登录，Worker 会自动使用你的订阅额度。
> **API Key 模式**：在 `.env` 中配置 `AGENT_WORKER_ANTHROPIC_API_KEY`，无需 login。

---

## 快速启动

### 1. 安装

```bash
cd tools/claude-agent-worker
pip install -e .
```

### 2. 配置

```bash
cp .env.example .env
```

编辑 `.env`，**至少修改以下两项**：

```properties
AGENT_WORKER_WORKER_NAME=我的开发机
AGENT_WORKER_WORKER_TOKEN=随便取一个强密码
```

### 3. 启动

```bash
cd tools/claude-agent-worker
uvicorn agent_worker.main:app --host 0.0.0.0 --port 3001
```

### 4. 验证

```bash
curl http://localhost:3001/health
```

预期返回：
```json
{
  "hostname": "你的机器名",
  "version": "0.1.0",
  "active_tasks": 0,
  "claude_cli_available": true,
  "worker_name": "我的开发机"
}
```

> 如果 `claude_cli_available` 为 `false`，说明 `claude` 命令不在 PATH 中，需要检查 Claude Code CLI 安装。

---

## 配置详解

所有配置通过环境变量设置，前缀 `AGENT_WORKER_`。也可以写在 `.env` 文件中。

### 基础配置

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `AGENT_WORKER_PORT` | `3001` | 服务监听端口 |
| `AGENT_WORKER_HOST` | `0.0.0.0` | 监听地址。`0.0.0.0` 允许远程访问 |
| `AGENT_WORKER_WORKER_NAME` | `""` | Worker 显示名称，在 Navigator 前端展示 |
| `AGENT_WORKER_WORKER_TOKEN` | `""` | 认证令牌。Navigator 调用 Worker 时携带此令牌。**留空则跳过认证（仅限开发）** |

### 安全配置

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `AGENT_WORKER_ALLOWED_CWDS` | `[]` | 允许 Claude Code 操作的目录白名单。子目录自动放行。**留空则不限制（仅限开发）** |
| `AGENT_WORKER_MAX_CONCURRENT_TASKS` | `3` | 最大并发任务数。每个任务是独立的 Claude Code 子进程 |

`ALLOWED_CWDS` 使用 JSON 数组格式：

```properties
# Windows
AGENT_WORKER_ALLOWED_CWDS=["D:\\projects","E:\\work\\repos"]

# Linux / macOS
AGENT_WORKER_ALLOWED_CWDS=["/home/user/projects","/opt/work"]
```

**目录规则**：配置父目录即可覆盖所有子目录。例如配置 `D:\projects`，则 `D:\projects\foo`、`D:\projects\bar\baz` 都允许访问。

### LLM 凭证配置

Worker 支持三种模式，按需选一种：

**模式 1：订阅模式（推荐个人使用）**

不设置任何 LLM 相关变量，Worker 使用 `claude login` 的订阅额度。

```properties
# 什么都不填，确保本机已 claude login
```

**模式 2：API Key 模式**

```properties
AGENT_WORKER_ANTHROPIC_API_KEY=sk-ant-api03-xxxx
```

**模式 3：自定义端点（代理 / DashScope 等）**

```properties
AGENT_WORKER_ANTHROPIC_API_KEY=sk-xxxx
AGENT_WORKER_ANTHROPIC_BASE_URL=https://your-proxy.example.com/v1
```

> 这些凭证通过 `env` 参数注入 Claude Code CLI 子进程，不会影响本机全局配置。

---

## 在 Navigator 中注册

Worker 启动后，在 Foggy Navigator 前端注册：

1. 登录 Navigator → 左下角点击 **Workers 图标** → 进入 Worker 管理页
2. 点击 **+ 添加**
3. 填写：
   - **名称**：和 `.env` 中的 `WORKER_NAME` 一致（如"我的开发机"）
   - **地址**：Worker 的访问地址（如 `http://192.168.1.100:3001`）
   - **认证令牌**：和 `.env` 中的 `WORKER_TOKEN` 一致
   - **认证模式**：选择你使用的 LLM 模式（仅展示用）
4. 点击**添加**，Navigator 会立即进行健康检查，状态变为绿色 `ONLINE` 即成功

---

## 网络要求

Navigator 服务器必须能访问到 Worker 的 HTTP 端口。几种场景：

| 场景 | 方案 |
|------|------|
| 同一局域网（公司内网） | 直接用内网 IP，如 `http://192.168.1.100:3001` |
| 家庭机 ↔ 公司服务器 | Tailscale / WireGuard VPN 组网，用 VPN IP |
| 临时外网暴露 | ngrok / frp 隧道，如 `https://xxx.ngrok.io` |

---

## API 参考

### GET /health

无需认证。返回 Worker 状态。

### POST /api/v1/query

发起 Claude Code 任务，返回 SSE 流。

请求体：
```json
{
  "prompt": "帮我修复 auth.py 中的 bug",
  "cwd": "D:\\projects\\my-app",
  "session_id": null,
  "max_turns": null
}
```

- `prompt`（必填）：任务描述
- `cwd`（可选）：工作目录，不填则使用 Worker 进程的当前目录
- `session_id`（可选）：填入之前的 Claude Session ID 可恢复上下文
- `max_turns`（可选）：限制最大轮次

SSE 事件类型：

| type | 说明 | 关键字段 |
|------|------|----------|
| `assistant_text` | Claude 的文本输出 | `content` |
| `tool_use` | Claude 调用工具 | `tool`, `input` |
| `tool_result` | 工具返回结果 | `tool`, `output` |
| `result` | 任务完成 | `content`, `cost_usd`, `duration_ms`, `session_id` |
| `error` | 出错 | `error` |

### POST /api/v1/query/{task_id}/abort

中止运行中的任务。

### GET /api/v1/sessions

列出本机上所有已跟踪的 Claude Code 会话（仅运行期内存中的记录）。

### GET /api/v1/sessions/{session_id}

获取单个会话详情。

---

## 手动测试（curl）

```bash
# 健康检查
curl http://localhost:3001/health

# 发起任务（SSE 流式输出）
curl -N -H "Authorization: Bearer your-secret-token" \
     -H "Content-Type: application/json" \
     -d '{"prompt":"列出当前目录下的文件","cwd":"D:\\projects"}' \
     http://localhost:3001/api/v1/query
```

---

## 常见问题

**Q: `claude_cli_available` 为 false？**
A: 确保 `claude` 命令在 PATH 中。尝试在终端直接运行 `claude --version`。如果使用 nvm/pyenv 等版本管理器，确保 Worker 进程继承了正确的 PATH。

**Q: 任务报错 "Working directory is not in the allowed list"？**
A: 请求的 `cwd` 不在 `ALLOWED_CWDS` 白名单中。添加对应目录或设为空数组 `[]` 取消限制。

**Q: 429 "Maximum concurrent tasks reached"？**
A: 同时运行的任务数超过了 `MAX_CONCURRENT_TASKS`。等待现有任务完成，或调大此值。

**Q: Worker 在 Navigator 中显示 OFFLINE？**
A: 检查网络连通性、防火墙、以及 Worker 是否正在运行。在 Navigator 中点击"刷新状态"重试。

**Q: 如何让 Worker 开机自启？**
A: Linux 可用 systemd，Windows 可用任务计划程序或 NSSM 包装为服务。
