---
name: ssh-terminal-dev
description: SSH 终端全栈开发指导（Python Worker + Java 代理层 + Vue 前端）。当用户需要迭代 SSH 终端功能、修改会话管理、调整 WebSocket 通信、添加终端特性时使用。触发词：/ssh, /ssh-terminal, 提及"SSH终端"、"终端面板"、"SSH会话"、"WebSocket终端"。
---

# SSH Terminal Full-Stack Development Guide

SSH 终端功能的全栈开发指导：Python Worker SSH 会话管理 → Java 代理层 → Vue 前端终端面板。

## 架构概述

```
浏览器 (xterm.js + WebSocket)
  ↕ WS 直连 Worker（含 token）
Python Worker (asyncssh PTY + FastAPI WS bridge)
  ↕ REST（被 Java 代理）
Java 后端 (SshProxyController — 代理 REST，构建 wsUrl)
  ↕ HTTP API
浏览器 (Vue 前端 — 调 Java REST，拿到 wsUrl 后直连 Worker WS)
```

**关键设计**：REST 调用经 Java 代理（隐藏 Worker token），WebSocket 由浏览器直连 Worker（性能）。

## 核心文件清单

### Python Worker（SSH 会话生命周期）

| 文件 | 职责 |
|------|------|
| `tools/claude-agent-worker/src/agent_worker/models.py` | `SshConnectRequest`, `SshSessionInfo` — 请求/响应模型 |
| `tools/claude-agent-worker/src/agent_worker/ssh/session_manager.py` | `SshSession` dataclass, `create_ssh_session()`, `close_ssh_session()`, idle cleanup |
| `tools/claude-agent-worker/src/agent_worker/routes/ssh.py` | REST + WS 路由：`POST /connect`, `GET /sessions`, `WS /{id}/ws`, `POST /{id}/resize`, `POST /{id}/close` |
| `tools/claude-agent-worker/src/agent_worker/config.py` | `max_ssh_sessions`, `ssh_idle_timeout_seconds` 配置 |

### Java 代理层（安全代理 + 用户过滤）

| 文件 | 职责 |
|------|------|
| `addons/claude-worker-agent/.../controller/SshProxyController.java` | 代理 SSH REST API，构建 wsUrl，按用户过滤会话 |
| `addons/claude-worker-agent/.../client/ClaudeWorkerClient.java` | `sshConnect()`, `sshClose()`, `sshResize()`, `listSshSessions()` — Worker HTTP 客户端 |
| `addons/claude-worker-agent/.../model/form/SshConnectForm.java` | 前端提交的连接表单 |
| `addons/claude-worker-agent/.../model/dto/SshSessionDTO.java` | 返回前端的会话重连信息 DTO |
| `addons/claude-worker-agent/.../service/WorkingDirectoryService.java` | 目录凭证查询、SSH 密码解密 |

### Vue 前端（终端 UI + 会话恢复）

| 文件 | 职责 |
|------|------|
| `packages/navigator-frontend/src/api/ssh.ts` | `sshConnect()`, `sshClose()`, `sshResize()`, `listSshSessions()` API |
| `packages/navigator-frontend/src/composables/useWorkspaceContext.ts` | `SshTerminalTab` 接口, `WorkspaceContext`, `restoreTerminalTabs()`, sync 状态管理 |
| `packages/navigator-frontend/src/components/worker/SshTerminalPanel.vue` | 终端面板 tab bar、resize handle、操作按钮 |
| `packages/navigator-frontend/src/components/worker/SshTerminal.vue` | xterm.js 终端组件、WS ↔ PTY 数据桥接、resize 同步 |
| `packages/navigator-frontend/src/views/ClaudeWorkerView.vue` | `doSshConnect()`, `syncSshSessions()`, `handleCloseTerminalTab()`, `handlePopOutTerminal()` |

## 关键模式与决策规则

### 1. 新增 SSH 功能字段的传播路径

当需要给 SSH session 增加新字段时，按此顺序修改：

1. **Python models.py** — `SshConnectRequest` (入) + `SshSessionInfo` (出)
2. **Python session_manager.py** — `SshSession` dataclass + `create_ssh_session()` 参数
3. **Python routes/ssh.py** — `connect()` 透传参数 + `list_sessions()` 返回字段
4. **Java ClaudeWorkerClient.java** — 如有新 REST 接口则加方法
5. **Java SshProxyController.java** — 代理逻辑、字段映射
6. **Java DTO** — 如需返回前端则更新/新建 DTO
7. **Frontend ssh.ts** — TypeScript 接口 + API 函数
8. **Frontend useWorkspaceContext.ts** — `SshTerminalTab` 接口如需扩展
9. **Frontend ClaudeWorkerView.vue** — 连接/同步逻辑

### 2. WebSocket URL 构建

Java 代理层负责构建 wsUrl，前端不接触 Worker token：

```java
String wsBase = worker.getBaseUrl().replaceFirst("^http", "ws");
String token = workerService.getDecryptedToken(worker);
String wsUrl = wsBase + "/api/v1/ssh/" + sessionId + "/ws?token=" + token;
```

### 3. 会话恢复（后端驱动）

- **后端是 source of truth** — Worker 存储活跃 SSH 会话
- **前端按需同步** — `syncSshSessions()` 调 Java `GET /api/v1/ssh/sessions?workerId=xxx`
- **每目录仅自动同步一次** — `syncedDirectories` Set 记录，页面刷新自动重置
- **手动同步** — 终端面板同步按钮调 `syncSshSessions(true)` 强制刷新
- **Java 做用户过滤** — 通过 `directoryService.getDirectoryEntity(userId, dirId)` 验证目录归属

### 4. 终端 tab 生命周期

```
创建: doSshConnect() → sshApi.sshConnect() → new WebSocket(wsUrl) → push to terminalTabs
恢复: syncSshSessions() → listSshSessions() → restoreTerminalTabs() → new WebSocket(wsUrl)
关闭: handleCloseTerminalTab() → ws.close() → sshApi.sshClose() → remove from terminalTabs
弹出: handlePopOutTerminal() → window.open() → 独立 xterm.js + WS
```

### 5. WS 重连后的 prompt 刷新

SshTerminal.vue 的 `setupWsHandler()` 检测 WS 处于 CONNECTING 状态时，open 后发送 `\n` 触发 shell 输出 prompt。

### 6. 终端 resize 双通道

- **主通道**：WS JSON 控制帧 `{ type: "resize", cols, rows }`
- **备用通道**：REST `POST /api/v1/ssh/{id}/resize`（防 WS 未连接时丢失）

## 常见操作

### 添加 SSH 连接参数

如果需要支持新的连接选项（如 private key file path）：

1. `models.py` SshConnectRequest 加字段
2. `session_manager.py` create_ssh_session() 加参数并使用
3. `routes/ssh.py` connect() 透传
4. `SshConnectForm.java` 加字段
5. `SshProxyController.java` connect() body.put() 透传
6. `ssh.ts` sshConnect form 参数加字段
7. `ClaudeWorkerView.vue` sshForm + doSshConnect() 传值

### 添加会话元数据

如果需要在会话列表中返回更多信息：

1. `SshSession` dataclass 加字段
2. `SshSessionInfo` 模型加字段
3. `list_sessions()` 返回时填充
4. `SshSessionDTO.java` 加字段
5. `SshProxyController.listSessions()` 映射字段
6. `ssh.ts` SshSessionDTO 加字段
7. 前端组件按需使用

### 调试 SSH 连接问题

- Worker 日志：`tools/claude-agent-worker/` 控制台输出
- Java 日志：`logs/backend.log` 搜索 `SshProxy` 或 `SSH`
- 浏览器：DevTools Network 面板查看 WS 连接状态
- 空闲超时：默认 30 分钟（`ssh_idle_timeout_seconds`），Worker 每 60s 扫描一次

## 约束条件

- Worker token 永远不暴露给前端 JS — 仅嵌在 wsUrl query param 中供 WS 直连
- SSH 密码加密存储在 WorkingDirectoryEntity — 通过 `directoryService.getDecryptedSshPassword()` 解密
- 最大并发 SSH 会话受 `max_ssh_sessions` 限制
- SSH session 无持久化（Worker 重启即丢失）— 这是设计决策，非 bug
- `useWorkspaceContext.ts` 中 `SshTerminalTab.buffer` 仅为组件重挂载时回放，不跨页面刷新
