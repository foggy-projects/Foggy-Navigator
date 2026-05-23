# 文档状态清单

> 用于区分哪些文档可以作为当前实现依据，哪些文档只适合作为历史参考。

## 1. 使用规则

阅读和引用文档时，建议遵循下面的优先顺序：

1. 当前口径文档  
   用于描述当前系统定位、一级功能架构和沟通口径。
2. 当前模块设计文档  
   用于补充模块级实现说明，但不能覆盖当前口径文档。
3. 历史参考文档  
   仅用于追溯设计演变，不应用于描述当前产品。

## 2. 当前口径文档

| 文档 | 状态 | 用途 |
|------|------|------|
| [系统架构概览](./00-system-overview.md) | 当前有效 | 当前系统定位、一级能力面、模块分层 |
| [功能架构总说明](./functional-architecture-summary.md) | 当前有效 | 面向团队沟通的高层总说明与统一表达 |
| [功能架构说明](./02-modules/functional-architecture.md) | 当前有效 | 当前功能地图与功能域边界 |
| [术语表](./terminology-glossary.md) | 当前有效 | 统一 Worker、会话、任务、A2A、Coding Agent 等术语 |
| [工作区与 Worker 中心](./02-modules/worker-workspace-center.md) | 当前有效 | Worker、目录、文件、Git 相关功能说明 |
| [PC Workers 操作与体验检查清单](./test-cases/pc-workers-experience-checklist.md) | 当前有效 | 桌面端 Workers 页面操作路径、验收清单与 Playwright 执行口径 |
| [会话协作中心](./02-modules/session-collaboration.md) | 当前有效 | 会话、消息、委派、分享能力说明 |
| [任务治理中心](./02-modules/task-governance.md) | 当前有效 | 统一任务分发与治理说明 |
| [跨项目编排](./02-modules/cross-project-orchestration.md) | 当前有效 | 多项目任务编排说明 |
| [平台设置与资源治理](./02-modules/platform-governance.md) | 当前有效 | Git、LLM、凭证、记忆、Worker、助手治理说明 |
| [用户与访问控制](./02-modules/user-and-access-control.md) | 当前有效 | 登录、用户、角色、API Key 说明 |
| [监控、通知与开放集成](./02-modules/observability-notification-integration.md) | 当前有效 | 监控、通知、Open API、SDK 说明 |

## 3. 当前模块设计文档

以下文档总体仍与当前实现一致，但属于模块实现层，不负责定义产品总口径：

| 文档 | 状态 | 备注 |
|------|------|------|
| [A2A Agent 架构](./a2a-agent-architecture.md) | 当前有效 | 已对齐当前产品口径，可作为 A2A 实现参考 |
| [会话模块](./02-modules/session-module.md) | 当前有效 | 已对齐当前产品口径，可作为会话与任务路由实现参考 |
| [可观测性系统](./02-modules/observability-system.md) | 当前有效 | 已区分当前实现与规划能力 |
| [工具能力模块](./02-modules/tool-module.md) | 部分有效 | 已改为当前 `agent-framework` 工具能力说明，但后续仍可继续拆分“现状”与“演进草案” |

## 4. 历史参考文档

以下文档不应再作为当前系统说明使用：

| 文档 | 状态 | 原因 |
|------|------|------|
| [01-overview/requirements.md](./01-overview/requirements.md) | 历史参考 | 早期企业级动态 Agent 平台需求草案 |
| [01-overview/system-architecture.md](./01-overview/system-architecture.md) | 历史参考 | 包含大量未落地或已偏移的中台化设计 |
| [01-overview/business-architecture-updated.md](./01-overview/business-architecture-updated.md) | 历史参考 | 仍以数据分析/语义层平台为主线 |
| [01-overview/business-architecture.md](./01-overview/business-architecture.md) | 历史参考 | 已被后续方向替代 |
| [01-overview/mvp-roadmap.md](./01-overview/mvp-roadmap.md) | 历史参考 | 仅反映早期规划，不反映当前里程碑 |

## 5. 待后续修订文档

这些文档没有明显错误，但仍可以继续做第二轮精修：

| 文档 | 建议 |
|------|------|
| [02-modules/tool-module.md](./02-modules/tool-module.md) | 后续可拆成“当前工具运行时能力说明”和“工具体系演进草案”两份 |

## 6. 推荐引用方式

后续如果需要写新方案、需求文档或任务说明，建议按下面顺序引用：

1. [系统架构概览](./00-system-overview.md)
2. [功能架构说明](./02-modules/functional-architecture.md)
3. 对应功能域说明文档
4. 对应模块设计文档

不要再直接从 `docs/01-overview/` 引用“当前系统说明”。
