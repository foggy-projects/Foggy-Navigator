# Foggy Navigator 文档中心

> 当前代码实现对应的文档索引

## 核心文档

| 文档 | 说明 | 状态 |
|------|------|------|
| [系统架构概览](./00-system-overview.md) | 当前系统定位、功能架构、模块职责、关键流程 | 已更新 (v4.0) |
| [功能架构总说明](./functional-architecture-summary.md) | 面向团队沟通的收官版功能架构说明 | 已新增 |
| [功能架构说明](./02-modules/functional-architecture.md) | 当前一级功能域与功能总图 | 已新增 |
| [文档状态清单](./documentation-status.md) | 区分当前有效、部分有效与历史参考文档 | 已新增 |
| [术语表](./terminology-glossary.md) | 统一 Worker、会话、任务、A2A、Coding Agent 等术语 | 已新增 |
| [Agent 框架需求](./agent-framework-requirements.md) | 公共 Agent 框架接口与实现要求 | 已完成 |
| [Agent 框架指南](./agent-framework-guide.md) | 框架使用指南 | 已完成 |
| [导师 Agent 设计](./tutor-agent-design.md) | Tutor Agent 详细设计 | 已完成 |
| [认证快速上手](./auth-quickstart.md) | JWT 认证配置 | 已完成 |

---

## 模块与功能文档

### 当前功能域

| 文档 | 说明 |
|------|------|
| [工作区与 Worker 中心](./02-modules/worker-workspace-center.md) | 远程 Worker、目录、Git、文件与任务工作台 |
| [会话协作中心](./02-modules/session-collaboration.md) | 会话、消息、委派、分享与实时协作 |
| [任务治理中心](./02-modules/task-governance.md) | 统一任务分发、查询、恢复与治理 |
| [跨项目编排](./02-modules/cross-project-orchestration.md) | 多阶段、多目录、多 Agent 任务编排 |
| [平台设置与资源治理](./02-modules/platform-governance.md) | Git/LLM/凭证/记忆/Worker/助手治理 |
| [用户与访问控制](./02-modules/user-and-access-control.md) | 登录、用户、角色与 API Key 管理 |
| [监控、通知与开放集成](./02-modules/observability-notification-integration.md) | 监控事件、SSE、Open API 与 SDK |

### 当前关键模块设计

| 文档 | 说明 |
|------|------|
| [会话模块](./02-modules/session-module.md) | 会话管理、统一任务分发、SSE 与绑定模型 |
| [编码 Agent 集成](./02-modules/coding-agent-integration.md) | `addons/coding-agent` 编程执行模块说明 |
| [Tutor-Coding 联动](./02-modules/tutor-coding-agent-integration.md) | Tutor 到编程执行能力的协作链路 |
| [可观察性系统](./02-modules/observability-system.md) | 监控、日志、追踪设计 |
| [工具模块](./02-modules/tool-module.md) | `agent-framework` 工具能力说明 |

---

## 历史设计文档

### 01-overview

`docs/01-overview/` 反映的是项目早期方案，仍可作为历史参考，但不应直接视为当前实现说明。

当前已确认偏差较大的内容包括：

- 数据分析平台定位
- 语义层 JavaScript 管理主线
- 早期 MVP 规划项

请优先以 [系统架构概览](./00-system-overview.md) 和 [功能架构说明](./02-modules/functional-architecture.md) 为准。

---

## 其他文档

| 目录 | 说明 |
|------|------|
| [03-implementation/](./03-implementation/) | 实施与工程细节 |
| [frontend-design/](./frontend-design/) | 前端设计文档 |
| [dev-specs/](./dev-specs/) | 开发规格 |
| [version-tracker/](./version-tracker/) | 新增事项唯一登记入口，按版本号跟踪需求、缺陷与重构记录 |
| [requirement-tracker/](./requirement-tracker/) | 历史季度制归档目录，停止新增写入 |

---

**更新日期**: 2026-04-03
