# Foggy Navigator 系统架构概览

> 基于当前代码实现整理的系统定位、功能架构与模块分层

## 1. 当前系统定位

Foggy Navigator 当前不是“数据分析/语义层平台”，而是一个以 **多 Agent 编排、远程编程工作区管理、会话与任务治理** 为核心的平台。

系统当前主轴有三条：

1. 以会话为中心的 Agent 交互入口  
   用户通过统一会话界面与 Tutor Agent、Claude Worker Agent、Codex Worker Agent 等能力交互。
2. 以 Worker 和目录为中心的远程编程工作台  
   用户可以管理远程 Worker、工作目录、Git 状态、Worktree、文件浏览、终端与编程任务。
3. 以任务和事件为中心的平台治理能力  
   平台提供统一任务分发、跨项目阶段编排、监控、通知、用户与配置治理能力。

## 2. 当前功能架构

### 2.1 一级功能域

| 功能域 | 用户可见入口 | 核心目标 | 主要模块 |
|------|------|------|------|
| 工作区与 Worker 中心 | `Workers` | 管理远程 Worker、目录、文件、Git 与编程执行环境 | `addons/claude-worker-agent`、`addons/codex-worker-agent`、`packages/navigator-frontend` |
| 会话协作中心 | `会话` | 统一承接用户与 Agent 的对话、消息流和委派跳转 | `session-module`、`tutor-agent`、`agent-framework` |
| 任务治理中心 | `任务` | 统一查看和治理平台侧 Agent Task / Worker Task | `session-module` |
| 跨项目编排 | `跨项目` | 把一个目标拆成多阶段、多目录、多 Agent 的串行协作流程 | `addons/claude-worker-agent` |
| 平台设置与资源治理 | `设置` | 管理 Git、LLM、Worker、凭证、记忆、Agent 模型覆盖等 | `metadata-config-module`、`addons/task-assistant`、`addons/claude-worker-agent` |
| 用户与访问控制 | `登录`、`用户` | 登录认证、用户管理、角色状态、API Key 管理 | `user-auth-module` |
| 监控、通知与开放集成 | `监控`、SSE、Open API | 提供事件面板、通知流和对外集成接口 | `monitoring-module`、`session-module`、`navigator-open-sdk`、`addons/claude-worker-agent` |

### 2.2 前端功能地图

当前主前端 `packages/navigator-frontend` 的路由直接对应产品功能面：

- `/`：Workers，主工作台
- `/chat`、`/c/:id`：会话中心
- `/tasks`：任务看板
- `/cross-tasks`：跨项目任务
- `/monitoring`：监控事件
- `/users`：用户管理
- `/settings`：平台设置
- `/files`：文件浏览器

### 2.3 后端分层

```text
Navigator Frontend (Vue 3)
  -> REST + SSE

Launcher
  -> 聚合启动业务模块与 addon

业务能力层
  -> session-module
  -> user-auth-module
  -> metadata-config-module
  -> metadata-query-module
  -> monitoring-module
  -> tutor-agent
  -> addons/claude-worker-agent
  -> addons/codex-worker-agent
  -> addons/gemini-worker-agent
  -> addons/task-assistant

平台底座层
  -> agent-framework
  -> navigator-spi
  -> navigator-common

对外集成层
  -> navigator-open-sdk
  -> Open API / Worker API / SSE
```

## 3. 关键模块职责

### 3.1 聚合与底座

| 模块 | 职责 |
|------|------|
| `launcher` | 聚合并启动整个平台 |
| `navigator-common` | 公共 Entity、DTO、表单、枚举、通用工具 |
| `navigator-spi` | 业务模块之间的 SPI 接口 |
| `agent-framework` | Agent 调用、工具执行、Skill 解析、上下文编排 |

### 3.2 核心业务模块

| 模块 | 职责 |
|------|------|
| `session-module` | 会话、消息、统一任务分发、SSE、分享与 Agent 发现 |
| `user-auth-module` | 登录认证、用户管理、API Key 管理 |
| `metadata-config-module` | 平台配置写接口，管理 Git/LLM/凭证/记忆/覆盖配置 |
| `metadata-query-module` | 平台配置读接口与查询能力 |
| `monitoring-module` | 监控事件与统计接口 |
| `tutor-agent` | 默认引导型 Agent，承接统一会话入口 |

### 3.3 Addon 能力模块

| 模块 | 职责 |
|------|------|
| `addons/claude-worker-agent` | 远程 Claude Worker、目录、文件浏览、跨项目任务、Open API |
| `addons/codex-worker-agent` | Codex Worker 任务和进程治理 |
| `addons/gemini-worker-agent` | Gemini Worker 任务和进程治理 |
| `addons/task-assistant` | 针对任务生命周期生成通知和摘要的助手能力 |
| `addons/echo-agent` | 示例/测试型 Agent |

## 4. 当前核心业务流程

### 4.1 会话驱动流程

```text
用户进入会话页
  -> 创建或打开 Session
  -> 发送消息
  -> session-module 路由到目标 Agent
  -> Agent 执行并产生消息/任务/委派
  -> SSE 持续推送消息、状态、通知
  -> 前端实时更新会话内容
```

### 4.2 Worker 驱动流程

```text
用户进入 Workers
  -> 选择 Worker
  -> 选择目录 / 项目 / worktree
  -> 发起任务
  -> TaskDispatchFacade 统一分发到 Claude / Codex / A2A Agent
  -> 用户在历史区查看任务状态、回复、重连、回溯、同步
```

### 4.3 跨项目编排流程

```text
用户创建跨项目任务
  -> 定义多个阶段
  -> 为阶段绑定 Agent、目录、Prompt、分支
  -> 系统按阶段推进
  -> 阶段完成后产出 handoff 信息
  -> 人工审核后推进下一阶段
```

### 4.4 平台治理流程

```text
设置页
  -> 管理 Git 提供方
  -> 管理 LLM 模型
  -> 管理 Agent 模型覆盖
  -> 管理 Worker / 凭证 / 记忆
  -> 管理任务助手配置
```

## 5. 设计边界与现状判断

### 5.1 当前已经落地的重点

- 统一会话入口与 SSE 实时通信
- 统一任务分发与任务面板
- Claude Worker 工作区管理
- 文件浏览、Git diff、Git history、搜索
- 跨项目阶段式任务编排
- 平台级 Git/LLM/凭证/记忆治理
- 用户管理与 API Key
- 监控事件与统计
- 对外 Open API / SDK

### 5.2 当前不是主轴或仍偏支撑的能力

- `echo-agent` 属于示例/测试能力
- 历史文档中的“语义层管理、数据分析 Agent、权限建模平台”不再是当前产品主线

## 6. 文档使用建议

当前文档体系应按三层理解：

1. `docs/00-system-overview.md`  
   作为当前系统定位与总架构唯一总览口径。
2. `docs/02-modules/*.md`  
   分别描述各一级功能域或关键模块。
3. `docs/01-overview/*`  
   仅保留为历史设计参考，不作为当前实现依据。

术语如有歧义，优先以 [术语表](./terminology-glossary.md) 为准。

## 7. 相关文档

- [功能架构说明](./02-modules/functional-architecture.md)
- [术语表](./terminology-glossary.md)
- [工作区与 Worker 中心](./02-modules/worker-workspace-center.md)
- [会话协作中心](./02-modules/session-collaboration.md)
- [任务治理中心](./02-modules/task-governance.md)
- [跨项目编排](./02-modules/cross-project-orchestration.md)
- [平台设置与资源治理](./02-modules/platform-governance.md)
- [用户与访问控制](./02-modules/user-and-access-control.md)
- [监控、通知与开放集成](./02-modules/observability-notification-integration.md)

---

**文档版本**: 4.0.0  
**更新日期**: 2026-03-31  
**基准**: 当前仓库代码结构、前端路由、控制器接口与模块依赖
