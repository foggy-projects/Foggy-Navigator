# Foggy Navigator 文档中心

> 个人 AI Agent 编排中枢

## 核心文档

| 文档 | 说明 | 状态 |
|------|------|------|
| [系统架构概览](./00-system-overview.md) | 系统定位、分层架构、模块职责、设计模式 | 已更新 (v3.0) |
| [Agent 框架需求](./agent-framework-requirements.md) | 公共 Agent 框架接口与实现要求 | 已完成 |
| [Agent 框架指南](./agent-framework-guide.md) | 框架使用指南 | 已完成 |
| [导师 Agent 设计](./tutor-agent-design.md) | 导师 Agent 详细设计与 Skill 定义 | 已完成 |
| [认证快速上手](./auth-quickstart.md) | JWT 认证配置 | 已完成 |

---

## 模块设计文档

### 核心模块

| 文档 | 说明 |
|------|------|
| [会话模块](./02-modules/session-module.md) | 会话管理、消息持久化、SSE 推送 |
| [工具模块](./02-modules/tool-module.md) | 工具管理与调用框架 |
| [可观察性系统](./02-modules/observability-system.md) | 监控、日志、追踪设计 |

### Agent 集成

| 文档 | 说明 |
|------|------|
| [编程 Agent 集成](./02-modules/coding-agent-integration.md) | OpenHands 集成方案 |
| [Tutor-Coding 联动](./02-modules/tutor-coding-agent-integration.md) | 导师 Agent 到编程 Agent 分派 |

### 远期参考

| 文档 | 说明 |
|------|------|
| [任务编排模块](./02-modules/task-orchestration-module.md) | 复杂任务编排与调度 |
| [记忆系统](./02-modules/memory-system.md) | 多层级记忆管理 |
| [记忆适配层](./02-modules/memory-adapter-layer.md) | MEM0 集成 |
| [RAG 模块](./02-modules/rag-module.md) | 检索增强生成 |
| [编排层](./02-modules/orchestration-layer.md) | 高级编排能力 |

---

## 规划与参考

### 01-overview（早期规划文档，仅作历史参考）

| 文档 | 说明 |
|------|------|
| [需求文档](./01-overview/requirements.md) | 初始需求分析 |
| [系统架构](./01-overview/system-architecture.md) | 早期终态架构参考 |
| [业务架构](./01-overview/business-architecture-updated.md) | 早期业务架构设计 |
| [MVP 路线图](./01-overview/mvp-roadmap.md) | 早期分阶段计划 |

> 注意：01-overview 中的文档反映的是项目早期的"数据分析平台"定位，部分内容（数据源管理、语义层 CRUD）已在 2026-02 架构重构中移除。当前系统定位请参考 [系统架构概览](./00-system-overview.md)。

---

## 其他文档

| 目录 | 说明 |
|------|------|
| [03-implementation/](./03-implementation/) | 实施指南（项目结构等） |
| [frontend-design/](./frontend-design/) | 前端设计文档 |
| [dev-specs/](./dev-specs/) | 开发规格（Mock LLM 等） |

---

**更新日期**: 2026-02-09
