# 上游接入总览

## 文档作用

- doc_type: integration-guide
- version: 1.1.3-SNAPSHOT
- status: draft
- date: 2026-05-04
- intended_for: upstream-business-owner | upstream-backend-developer | upstream-frontend-developer | platform-admin
- purpose: 为上游业务系统接入 Navigator Business Agent 能力提供总览、角色分工和三层接入路径说明

## 概述

Navigator 1.1.3 的 Business Agent 能力允许上游业务系统以 **Client Application**（内部代码名 `ClientApp`）的身份接入平台，使用自然语言驱动的业务函数调用、审批流、Skill 编排和审计链路。

上游接入时有三层可选路径，按推荐优先级排列：

| 层级 | 路径 | 适用角色 | 推荐程度 |
| --- | --- | --- | --- |
| **Backend SDK** | `navigator-open-sdk` Java SDK | 上游后端开发者 | ⭐ 推荐 |
| **Frontend Component** | `@foggy/chat` + `@foggy/navigator-chat-widget` Vue 组件 | 上游前端开发者 | ⭐ 推荐 |
| **REST API** | HTTP 控制面 API | 平台管理员 / SDK 未覆盖场景 | 协议参考 |

> **原则**：上游后端优先使用 SDK 完成系统注册、凭证管理、任务创建和消息拉取；上游前端使用预构建组件完成聊天、消息展示和审批 UI；REST API 仅作为底层协议参考或 SDK 尚未封装能力的临时兜底。

## 总体架构

```mermaid
graph TB
    subgraph Upstream["上游业务系统"]
        UB[上游后端<br/>navigator-open-sdk]
        UF[上游前端<br/>@foggy/chat / @foggy/navigator-chat-widget]
    end

    subgraph Navigator["Navigator 平台"]
        CP[Java Control Plane<br/>业务接入 / 授权 / 审批 / 审计]
        WGW[Worker Gateway<br/>内部 API — 不对外暴露]
        BW[Biz Worker Pool<br/>LangGraph Biz Worker]
    end

    subgraph UpstreamBiz["上游业务 REST"]
        UAPI[上游 REST API]
    end

    UB -->|SDK / REST API| CP
    UF -->|通过上游 BFF 或平台公开会话 API| CP
    CP -->|内部路由| WGW
    WGW -->|标准工具调用| BW
    BW -->|LLM + Skill + Tool| WGW
    WGW -->|REST Adapter| UAPI
    CP -->|审批 / 审计| CP

    style WGW fill:#f9d0c4,stroke:#e06c75,stroke-width:2px
    style UB fill:#d4edda,stroke:#28a745
    style UF fill:#d4edda,stroke:#28a745
```

> **⚠️ 重要**：Worker Gateway 是内部 API，仅供受信 Worker 调用，**不对上游前端或后端开放**。上游系统只与 Java Control Plane 交互。

## 角色分工

### 平台 / 租户管理员

| 职责 | 说明 | 接入方式 |
| --- | --- | --- |
| 创建 Client Application | 签发 provisioning credential、绑定 `tenantId` | SDK / REST 控制面 |
| 授权 LLM 模型 | 将 `LlmModelConfig` 授权给 ClientApp | SDK / REST 控制面 |
| 授权 Skill | 将 Skill 授权给 ClientApp | SDK / REST 控制面 |
| 注册 Biz Worker | 注册 Worker identity 并加入 Pool | REST 控制面（`SUPER_ADMIN`） |

### 上游后端开发者

| 职责 | 说明 | 接入方式 |
| --- | --- | --- |
| 系统注册 | 注册第三方系统、获取 API Key | SDK `NavigatorClient.register()` |
| Worker / Employee 管理 | 创建 Worker、Provision 员工 | SDK `client.workers()` / `client.employees()` |
| 发起任务 | 创建 Agent Task | SDK `client.agents().ask()` |
| 轮询任务状态 | 获取任务进度和结果 | SDK `client.agents().getTask()` |
| 轮询增量消息 | 获取任务执行中产生的消息 | SDK `client.agents().getTaskMessages()` |
| 会话回放 | 获取完整会话消息 | SDK `client.agents().getSessionMessages()` |
| 注册 BusinessObject / Function | 定义业务对象和函数 | SDK / REST 控制面 |
| 处理审批 | Resume suspension | SDK / REST 控制面 |

### 上游前端开发者

| 职责 | 说明 | 接入方式 |
| --- | --- | --- |
| 聊天 UI | 消息展示、输入、流式渲染 | `@foggy/chat` ChatPanel |
| 工具调用展示 | 展示 Agent 工具执行过程 | `@foggy/chat` ToolCallBlock |
| 审批卡片 | 展示审批请求、Approve/Reject | `@foggy/chat` ChatPanel/MessageList 内置审批渲染 |
| Suspension 弹窗 | 展示审批、人工确认、支付等暂停交互 | `@foggy/chat` BusinessSuspensionDialog |
| 快速集成 | 开箱即用的对话组件 | `@foggy/navigator-chat-widget` NavigatorChat |

## 安全边界概要

- 上游前端**不得**持有 admin token、provisioning credential、runtime credential 或 task scoped token。
- 上游前端**不得**直接调用 Worker Gateway。
- LLM 上下文中**不包含** `task_scoped_token`、`adapterConfigJson`、`manifestJson` 或上游凭证。
- 详见 [09-security-boundaries.md](./09-security-boundaries.md)。

## 文档索引

| 文档 | 主题 | 适用角色 |
| --- | --- | --- |
| [01-backend-sdk-quickstart.md](./01-backend-sdk-quickstart.md) | 后端 SDK 快速上手 | 上游后端 |
| [02-frontend-component-quickstart.md](./02-frontend-component-quickstart.md) | 前端组件快速上手 | 上游前端 |
| [03-client-app-bootstrap.md](./03-client-app-bootstrap.md) | 创建 Client Application | 平台管理员 / 上游后端 |
| [04-skill-user-model-grants.md](./04-skill-user-model-grants.md) | Skill、用户、模型授权 | 平台管理员 |
| [05-business-object-and-function.md](./05-business-object-and-function.md) | 业务对象与函数注册 | 上游后端 / 平台管理员 |
| [06-session-task-message-flow.md](./06-session-task-message-flow.md) | 会话、任务与消息流 | 上游后端 / 上游前端 |
| [07-approval-flow.md](./07-approval-flow.md) | 审批与 Suspension 流 | 上游后端 / 上游前端 |
| [08-rest-api-reference.md](./08-rest-api-reference.md) | REST API 参考 | 协议参考 |
| [09-security-boundaries.md](./09-security-boundaries.md) | 安全边界 | 全部角色 |
| [10-demo-checklist.md](./10-demo-checklist.md) | 最小接入 Checklist | 上游后端 / 上游前端 |
| [11-llm-sdk-usage-guide.md](./11-llm-sdk-usage-guide.md) | LLM SDK 使用指南 | 上游 LLM coding agent |
| [12-tms-business-agent-sdk-and-token-injection-plan.md](./12-tms-business-agent-sdk-and-token-injection-plan.md) | TMS Stage 10 计划 | Navigator / reviewer |
| [13-tms-minimal-onboarding-sample.md](./13-tms-minimal-onboarding-sample.md) | TMS 最小接入样例 | 上游 LLM coding agent / 上游后端 |
| [14-upstream-auto-bootstrap-contract.md](./14-upstream-auto-bootstrap-contract.md) | 上游自动 bootstrap 契约 | 上游 LLM coding agent / 上游后端 |
| [15-upstream-suspension-dialog-component-contract.md](./15-upstream-suspension-dialog-component-contract.md) | 上游 Suspension 对话框组件契约 | 上游前端 / 上游后端 |
