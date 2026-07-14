# 功能架构说明

> 面向产品与研发协同的当前功能地图

## 1. 功能总图

```text
统一入口
  -> 登录 / 初始化配置
  -> 主导航

主业务能力
  -> Workers
  -> 任务
  -> 跨项目

平台治理能力
  -> 设置
  -> 用户

平台支撑能力
  -> SSE
  -> Agent 发现与分发
  -> Open API / SDK
  -> 上游接入 / 嵌入式组件 / 移动端
```

## 2. 一级功能域拆解

### 2.1 Workers

目标：把远程 Worker、工作目录、文件、Git、终端和任务执行整合成一个操作台。

包含能力：

- Worker 注册、编辑、健康检查、进程管理
- 工作目录、项目目录、子目录、worktree 管理
- 目录级任务创建、回复、重连、回溯、同步
- 文件浏览、全文搜索、Git diff、Git history
- 终端、代码服务入口、附件与绘图辅助
- 目录授权 Agent 与 Agent Team 配置
- Claude / Codex / Gemini / LangGraph Biz Worker 接入

### 2.2 会话底座

目标：为 Worker 任务、跨项目阶段和开放集成提供统一的 Session、消息历史、SSE 与 Agent/Provider 绑定能力。它不再作为 PC 顶部主导航里的独立入口。

包含能力：

- Session 创建、删除、切换
- 消息历史与实时流式回复
- Agent 委派与返回路由
- 会话绑定 Agent / provider / 模型配置
- 分享 Key 与公开提问

### 2.3 任务

目标：从平台视角统一查看 Agent Task 和 Worker Task 的运行情况。

包含能力：

- 任务列表、状态筛选、类型筛选、Agent 筛选
- 任务摘要查看
- 任务恢复、取消、重连、重同步
- 目录级或 Worker 级任务查询

### 2.4 跨项目

目标：用阶段化流程管理复杂任务，而不是只靠单轮对话。

包含能力：

- 创建多阶段任务
- 阶段绑定 Agent、目录、Prompt、worktree 分支
- 阶段 handoff 编辑与审核
- 任务启动、推进、取消
- 阶段会话回跳

### 2.5 设置

目标：管理平台运行所需的外部资源、模型、凭证和偏好。

包含能力：

- Git Provider 管理
- LLM 模型管理与连通性测试
- Agent 模型覆盖
- 用户记忆管理
- API 凭证管理
- Claude Worker 管理
- 业务 Agent 与上游接入资源管理
- 任务助手配置

### 2.6 用户

目标：提供平台管理员视角的用户和凭证治理。

包含能力：

- 用户新增、编辑、删除
- 角色与状态管理
- API Key 创建、撤销、查看使用情况

### 2.7 通知与基础运行观测

目标：在不维护旧自研 Monitoring 产品切片的前提下，保留任务实时通知和最低限度的运行诊断能力。

包含能力：

- SSE 通知与助手通知
- 应用日志与健康检查
- 有限的 Micrometer 指标和安全/运行审计

### 2.8 开放集成

目标：让平台能力可以被其他系统调用。

包含能力：

- Agent 发现与问答接口
- Claude Worker Open API
- Java SDK 封装
- 上游 CLI、嵌入式聊天组件、移动端入口

## 3. 功能边界判断

### 3.1 主业务能力

- Workers
- 任务
- 跨项目

### 3.2 平台治理能力

- 设置
- 用户

### 3.3 平台底座能力

- 统一任务分发
- A2A Agent 发现
- SSE
- Open API / SDK
- 上游接入与嵌入入口

## 4. 工程模块映射

| 工程层 | 当前模块 |
|------|------|
| 聚合启动 | `launcher` |
| 平台底座 | `navigator-common`、`navigator-spi`、`agent-framework` |
| 核心业务 | `session-module`、`business-agent-module`、`user-auth-module`、`metadata-config-module` |
| Worker / Agent Addon | `addons/claude-worker-agent`、`addons/codex-worker-agent`、`addons/gemini-worker-agent`、`addons/langgraph-biz-worker`、`addons/task-assistant`、`addons/echo-agent` |
| 开放集成 | `navigator-open-sdk`、`tools/navigator-upstream`、`tools/navigator-upstream-cli`、`tools/navigator-chat-observer-bff` |
| 前端与多端 | `packages/navigator-frontend`、`packages/foggy-chat`、`packages/foggy-chat-core`、`packages/navigator-chat-widget`、`packages/foggy-mobile` |
| Worker 运行时工具 | `tools/claude-agent-worker`、`tools/codex-agent-worker`、`tools/gemini-agent-worker`、`tools/langgraph-biz-worker`、`tools/mock-llm-service` |

旧独立会话入口及其配套 `tutor-agent` 已从源码目录、根 `pom.xml` 与 `launcher` 运行时依赖中移除；当前主线功能模块不再保留旧引导 Agent。

旧 `monitoring-module`、RabbitMQ 日志采集、PC Monitoring 页面/API 与 `tools/foggy-monitor` 已在 1.4.2 dev 阶段移除，不属于当前工程或产品入口；如未来需要集中观测，应按新需求接入，不恢复旧切片的隐式装配。

旧 `metadata-query-module` 已在 1.4.2 dev 阶段从源码、根 reactor 和 `launcher` 物理退役；`metadata-config-module` 仍作为活跃的平台配置能力保留。LangGraph FSScript 不在该退役范围内。

## 5. 推荐阅读顺序

1. [系统架构概览](../00-system-overview.md)
2. [工作区与 Worker 中心](./worker-workspace-center.md)
3. [会话协作中心](./session-collaboration.md)
4. [任务治理中心](./task-governance.md)
5. [跨项目编排](./cross-project-orchestration.md)
6. [平台设置与资源治理](./platform-governance.md)
7. [用户与访问控制](./user-and-access-control.md)
8. [通知、基础观测与开放集成](./observability-notification-integration.md)
