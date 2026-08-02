# Foggy Navigator 系统架构概览

> 基于当前代码实现整理的系统定位、功能架构与模块分层

## 1. 当前系统定位

Foggy Navigator 当前不是“数据分析/语义层平台”，而是一个以 **多 Agent 编排、远程编程工作区管理、会话与任务治理** 为核心的平台。

系统当前主轴有三条：

1. 以 Worker 任务会话为中心的 Agent 交互入口
   用户主要从 Workers 工作台发起、继续和查看 Claude / Codex / Gemini / LangGraph Biz 等任务会话；旧独立 `/chat` 入口不再作为主导航入口。
2. 以 Worker 和目录为中心的远程编程工作台  
   用户可以管理远程 Worker、工作目录、Git 状态、Worktree、文件浏览、终端与编程任务。
3. 以任务和事件为中心的平台治理能力  
   平台提供统一任务分发、通知、用户与配置治理能力。Task / Session 仍是核心运行对象，但旧 `任务`、`跨项目` 顶部入口已退出日常工作台。

## 2. 当前功能架构

### 2.1 一级功能域

| 功能域 | 用户可见入口 | 核心目标 | 主要模块 |
|------|------|------|------|
| 工作区与 Worker 中心 | `Workers` | 管理远程 Worker、目录、文件、Git 与编程执行环境 | `addons/claude-worker-agent`、`addons/codex-worker-agent`、`addons/gemini-worker-agent`、`addons/langgraph-biz-worker`、`packages/navigator-frontend` |
| 会话协作中心 | `Workers` 内任务会话、`/c/:id` 深链 | 统一承接 Worker 任务会话、消息流、SSE、绑定与委派跳转 | `session-module`、`agent-framework` |
| 任务治理中心 | Workers 内任务历史、深链与 API | 统一查看和治理平台侧 Agent Task / Worker Task | `session-module` |
| 历史跨项目记录 | 无顶部入口；仅 owner-scoped GET | 只读查看既有跨项目记录；mutation surface 默认退役 | `addons/claude-worker-agent` |
| 平台设置与资源治理 | `设置` | 管理 Git、LLM、Worker、凭证、记忆、业务 Agent、Agent 模型覆盖等 | `metadata-config-module`、`business-agent-module`、`addons/task-assistant`、`addons/claude-worker-agent` |
| 用户与访问控制 | `登录`、`用户` | 登录认证、用户管理、角色状态、API Key 管理 | `user-auth-module` |
| 通知与开放集成 | SSE、Open API | 提供通知流、对外 SDK、上游接入与嵌入式聊天入口 | `session-module`、`navigator-open-sdk`、`business-agent-module`、`tools/navigator-chat-observer-bff`、`addons/claude-worker-agent` |

### 2.2 前端功能地图

当前主前端 `packages/navigator-frontend` 的路由直接对应产品功能面：

- `/`：Workers，主工作台
- `/chat`：旧独立会话入口，当前重定向到 Workers
- `/c/:id`：会话深链兼容入口
- `/tasks`：旧任务入口，按 named route 重定向到 Workers
- `/cross-tasks`：旧跨项目入口，按 named route 重定向到 Workers
- `/users`：用户管理
- `/settings`：平台设置
- `/files`：文件浏览器

### 2.3 多端与嵌入入口

除主前端外，当前仓库还包含面向不同集成场景的前端与客户端包：

| 包 | 定位 |
|------|------|
| `packages/foggy-chat` | 独立聊天体验与调试入口 |
| `packages/foggy-chat-core` | 聊天 UI 与协议复用核心 |
| `packages/navigator-chat-widget` | 嵌入第三方页面的聊天组件 |
| `packages/foggy-mobile` | 移动端 uni-app 入口 |

### 2.4 后端分层

```text
Navigator Frontend (Vue 3)
  -> REST + SSE

Launcher
  -> 聚合启动业务模块与 addon

业务能力层
  -> session-module
  -> business-agent-module
  -> user-auth-module
  -> metadata-config-module
  -> addons/claude-worker-agent
  -> addons/codex-worker-agent
  -> addons/gemini-worker-agent
  -> addons/langgraph-biz-worker
  -> addons/task-assistant

平台底座层
  -> agent-framework
  -> navigator-spi
  -> navigator-common

对外集成层
  -> navigator-open-sdk
  -> tools/navigator-chat-observer-bff
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
| `business-agent-module` | 业务 Agent、上游接入资源、业务动作与开放集成治理 |
| `user-auth-module` | 登录认证、用户管理、API Key 管理 |
| `metadata-config-module` | 活跃的平台配置能力，管理 Git/LLM/凭证/记忆/覆盖配置 |
旧独立“会话”入口配套的 `tutor-agent` 已从源码目录、根 `pom.xml` 与 `launcher` 运行时依赖中移除；当前主线不再保留旧引导 Agent。

旧自研 `monitoring-module`、`tools/foggy-monitor` 及 PC Monitoring 页面/API 已在 1.4.2 dev 阶段物理移除；RabbitMQ 不再是当前主线前置依赖。平台继续保留应用日志、健康检查、有限的 Micrometer 指标、SSE 运行信号与安全审计，退役旧 Monitoring 不等于取消运行观测。

旧 `metadata-query-module` 已在 1.4.2 dev 阶段从源码、根 reactor 和 `launcher` 物理退役。该结论不延伸到 `metadata-config-module` 或 LangGraph FSScript：前者仍是当前平台配置能力，后者仍按其现有 Worker 边界保留。

### 3.3 Addon 能力模块

| 模块 | 职责 |
|------|------|
| `addons/claude-worker-agent` | 远程 Claude Worker、SSH/终端、目录、文件与 Git 能力、Open API，以及历史跨项目记录的只读兼容面 |
| `addons/codex-worker-agent` | Codex Worker 任务和进程治理 |
| `addons/gemini-worker-agent` | Gemini Worker 任务和进程治理 |
| `addons/langgraph-biz-worker` | LangGraph Biz Worker 接入与业务 Agent 执行通道 |
| `addons/task-assistant` | 针对任务生命周期生成通知和摘要的助手能力 |

`addons/echo-agent` 已在 1.4.2 dev 阶段从源码、根 reactor 和 `launcher` 物理退役，默认制品不再注册 Echo Agent。有价值的 A2A discovery/resolve/send/query/cancel 行为由 `session-module` 的 test-only 内存 fixture 回归；普通 BusinessFunction 的 `LocalEchoBusinessFunctionAdapterInvoker` 不在退役范围内。

## 4. 当前核心业务流程

### 4.1 Worker 任务会话流程

```text
用户进入 Workers
  -> 创建任务或打开历史会话
  -> session-module 建立 / 读取 Session
  -> TaskDispatchFacade 路由到目标 Worker / Agent
  -> Agent 执行并产生消息/任务/委派
  -> SSE 持续推送消息、状态、通知到任务面板
  -> 前端实时更新会话内容与任务状态
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

### 4.3 历史跨项目记录边界

```text
旧 /cross-tasks 路由
  -> 重定向 Workers

已认证 owner 调用历史查询 API
  -> GET 列表或详情
  -> 只读返回既有记录

任一 create/start/review/handoff/advance/cancel mutation
  -> 认证优先
  -> 默认 HTTP 410 + no-store + CROSS_PROJECT_TASK_MUTATION_RETIRED
  -> 不进入 repository、dispatch、worktree、event 或 state mutation
```

旧记录保持原样，不做回填、清洗或修复。`NAVIGATOR_CROSS_PROJECT_TASK_MUTATIONS_ENABLED=true` 仅是需要显式开启的临时 rollback 开关，不是常规能力或自动兼容路径。

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

- Worker 内任务会话与 SSE 实时通信
- 统一任务分发与任务面板
- Claude / Codex / Gemini / LangGraph Biz Worker 接入与工作区管理
- 文件浏览、Git diff、Git history、搜索
- 历史跨项目记录的 owner-scoped 只读访问与默认关闭的 mutation 兼容桩
- 平台级 Git/LLM/凭证/记忆治理
- 用户管理与 API Key
- 应用日志、健康检查、有限指标与运行审计
- 对外 Open API / SDK / 上游 CLI
- 嵌入式聊天组件与移动端入口

### 5.2 当前不是主轴或仍偏支撑的能力

- PC 顶部独立 `/chat` 会话入口已下线；`/c/:id` 暂作为深链兼容入口，不是主导航入口
- 旧独立会话入口及其配套 `tutor-agent` 已移除
- 旧 `echo-agent` 示例 Provider 已退出默认制品，仅保留 test-only A2A fixture 作为回归替代
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
- [跨项目退役与只读边界](./02-modules/cross-project-orchestration.md)
- [平台设置与资源治理](./02-modules/platform-governance.md)
- [用户与访问控制](./02-modules/user-and-access-control.md)
- [通知、基础观测与开放集成](./02-modules/observability-notification-integration.md)

---

**文档版本**: 4.2.0
**更新日期**: 2026-08-03
**基准**: 当前仓库代码结构、前端路由、Provider 实现、控制器接口与模块依赖
