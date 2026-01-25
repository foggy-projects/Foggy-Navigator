# Foggy Navigator 文档中心

> 基于 LangChain4j 的企业级AI数据分析平台

## 📖 文档导航

### 🎯 核心文档（优先阅读）

| 文档 | 说明 | 状态 |
|------|------|------|
| [系统架构概览](./00-system-overview.md) | **必读**：系统整体架构、设计原则、可观察性设计 | ✅ 完成 |
| [Agent框架需求](./agent-framework-requirements.md) | 公共Agent框架接口与实现要求 | ✅ 当前开发 |
| [导师Agent设计](./tutor-agent-design.md) | 导师Agent详细设计与实现指南 | ✅ 当前开发 |

---

## 📚 系统设计文档

### 00. 系统整体

| 文档 | 说明 |
|------|------|
| [系统架构概览](./00-system-overview.md) | 系统整体架构、模块职责、可观察性、测试性设计 |

### 01. 概览与规划

| 文档 | 说明 | 阶段 |
|------|------|------|
| [需求文档](./01-overview/requirements.md) | 系统需求分析、功能清单 | 参考 |
| [系统架构](./01-overview/system-architecture.md) | 终态系统架构参考 | 参考 |
| [业务架构](./01-overview/business-architecture-updated.md) | 业务架构设计、Agent设计 | 参考 |
| [MVP路线图](./01-overview/mvp-roadmap.md) | 分阶段实施计划 | 参考 |

### 02. 核心模块设计

#### Phase 1 - 当前实施（优先）

| 文档 | 说明 | 状态 |
|------|------|------|
| [Agent框架需求](./agent-framework-requirements.md) | 公共Agent框架接口定义 | 🔥 开发中 |
| [导师Agent设计](./tutor-agent-design.md) | 导师Agent详细设计 | 🔥 开发中 |
| [会话模块](./02-modules/session-module.md) | 会话与消息管理 | Phase 1 |
| [工具模块](./02-modules/tool-module.md) | 工具管理与调用 | Phase 1-2 |

#### Phase 2 - 近期规划

| 文档 | 说明 | 状态 |
|------|------|------|
| [语义层工作流](./02-modules/semantic-layer-workflow.md) | 语义层生成流程 | Phase 2 |
| [语义层验证](./02-modules/semantic-layer-validation.md) | 沙盒验证机制 | Phase 2 |
| [编程Agent集成](./02-modules/coding-agent-integration.md) | OpenHands集成方案 | Phase 2 |
| [可观察性系统](./02-modules/observability-system.md) | 监控、日志、追踪 | Phase 2-3 |

#### Phase 3+ - 后续扩展

| 文档 | 说明 | 状态 |
|------|------|------|
| [任务编排模块](./02-modules/task-orchestration-module.md) | 复杂任务编排与调度 | Phase 3+ |
| [记忆系统](./02-modules/memory-system.md) | 多层级记忆管理 | Phase 3+ |
| [记忆适配层](./02-modules/memory-adapter-layer.md) | MEM0集成 | Phase 3+ |
| [RAG模块](./02-modules/rag-module.md) | 检索增强生成 | Phase 3+ |
| [编排层](./02-modules/orchestration-layer.md) | 高级编排能力 | Phase 3+ |

### 03. 实施指南

| 文档 | 说明 | 状态 |
|------|------|------|
| [项目结构](./03-implementation/project-structure.md) | 代码组织结构 | 📝 待更新 |

---

## 🚀 快速开始

### 1. 了解系统架构

**首次阅读**，请按以下顺序：

```
1. 系统架构概览 (00-system-overview.md)
   ├─ 了解系统定位与目标
   ├─ 理解分层架构
   ├─ 熟悉可观察性设计
   └─ 掌握测试策略

2. Agent框架需求 (agent-framework-requirements.md)
   ├─ 理解Agent配置化理念
   ├─ 熟悉核心接口
   └─ 了解MVP实现范围

3. 导师Agent设计 (tutor-agent-design.md)
   ├─ 理解导师Agent职责
   ├─ 学习Skill定义方式
   └─ 了解工具接口设计
```

### 2. 开发者指南

#### 如果你负责公共Agent框架

1. 阅读 [Agent框架需求](./agent-framework-requirements.md)
2. 实现P0优先级接口（AgentConfigLoader、AgentRegistry等）
3. 提供Mock实现供其他模块开发使用

#### 如果你负责导师Agent

1. 阅读 [导��Agent设计](./tutor-agent-design.md)
2. 实现SystemConfigController
3. 编写Skills markdown文件
4. 创建tutor-agent.yml配置

#### 如果你负责业务模块

1. 阅读 [系统架构概览](./00-system-overview.md)
2. 实现ConfigurationService接口
3. 提供数据源、语义层配置状态查询

---

## 📋 文档分类说明

### 文档类型

| 类型 | 说明 | 示例 |
|------|------|------|
| **架构设计** | 系统整体架构、设计原则 | 00-system-overview.md |
| **接口规范** | 模块间接口定义 | agent-framework-requirements.md |
| **实施指南** | 具体实现方案 | tutor-agent-design.md |
| **终态参考** | 未来扩展方向 | task-orchestration-module.md |

### 文档状态

| 状态 | 说明 |
|------|------|
| ✅ 完成 | 文档已完成，可直接使用 |
| 🔥 开发中 | 当前正在开发，文档持续更新 |
| 📝 待更新 | 需要根据当前架构更新 |
| 参考 | 作为系统目标参考，非当前实施 |

---

## 🔧 开发阶段

### Phase 1: 核心框架（当前）

**目标**: 验证核心流程，实现导师Agent引导配置

**时间**: 4-6周

**范围**:
- ✅ Agent框架核心能力
- ✅ 导师Agent
- ✅ 数据源管理
- ✅ 基础日志与监控

**关键文档**:
- [Agent框架需求](./agent-framework-requirements.md)
- [导师Agent设计](./tutor-agent-design.md)
- [会话模块](./02-modules/session-module.md)

---

### Phase 2: 语义层生成

**目标**: 实现语义层自动生成和管理

**时间**: 6-8周

**范围**:
- 语义层Agent
- Git集成
- 沙盒验证
- OpenHands集成

**关键文档**:
- [语义层工作流](./02-modules/semantic-layer-workflow.md)
- [语义层验证](./02-modules/semantic-layer-validation.md)
- [编程Agent集成](./02-modules/coding-agent-integration.md)

---

### Phase 3: 数据分析与高级特性

**目标**: 实现自然语言查询和高级功能

**时间**: 8-10周

**范围**:
- 数据分析Agent
- 权限管理
- 高级监控
- 记忆系统增强
- RAG检索

**关键文档**:
- [任务编排模块](./02-modules/task-orchestration-module.md)
- [记忆系统](./02-modules/memory-system.md)
- [RAG模块](./02-modules/rag-module.md)

---

## 🎯 关键设计理念

### 1. 可观察性优先

系统设计从一开始就考虑可观察性：
- **分层日志**: 业务日志、Agent日志、系统日志、性能日志
- **全链路追踪**: 完整的请求链路追踪（TraceID）
- **实时监控**: Prometheus指标收集
- **智能告警**: 基于规则的告警机制

**详见**: [系统架构概览 - 可观察性设计](./00-system-overview.md#4-可观察性设计)

---

### 2. 测试性设计

系统设计确保各模块易于测试：
- **接口清晰**: 所有公共接口有明确定义
- **Mock友好**: 依赖外部服务的模块提供Mock
- **配置驱动**: 支持测试配置，无需修改代码
- **高覆盖率**: 单元测试 > 80%，核心流程 100%

**详见**: [系统架构概览 - 测试性设计](./00-system-overview.md#5-测试性设计)

---

### 3. 配置化优于硬编码

Agent和Skill通过配置定义：
- **Agent配置**: YAML文件定义Agent行为
- **Skill定义**: markdown文件定义技能
- **工具注册**: 声明式工具配置
- **分派规则**: 配置化分派逻辑

**详见**: [Agent框架需求 - Agent配置规范](./agent-framework-requirements.md#6-agent配置规范)

---

### 4. 渐进式增强

MVP优先，持续迭代：
- **Phase 1**: 验证核心流程（导师Agent）
- **Phase 2**: 语义层生成与管理
- **Phase 3**: 高级功能（记忆、RAG、编排）

**详见**: [系统架构概览 - 分阶段实施](./00-system-overview.md#7-分阶段实施)

---

## 📊 文档贡献

### 添加新文档

1. 确定文档所属目录
2. 创建markdown文件（使用英文文件名）
3. 在文档顶部添加阶段标注
4. 更新本README的导航表格

### 文档命名规范

- **文件名**: 使用英文，单词用连字符分隔（如：`agent-framework-requirements.md`）
- **标题**: 使用中文，清晰简洁
- **结构**: 使用标准markdown语法

### 文档模板

```markdown
# 文档标题

> **实施阶段**: Phase X
> **本文档作用**: 文档用途说明

---

## 1. 概述

### 1.1 模块定位
模块在系统中的位置和作用

### 1.2 设计目标
模块的设计目标

### 1.3 核心职责
模块的核心职责

## 2. 接口定义
（如果是接口文档）

## 3. 可观察性要求
监控、日志、追踪要求

## 4. 测试要求
单元测试、集成测试要求

---

**文档版本**: X.X.X
**创建日期**: YYYY-MM-DD
**作者**: Team Name
```

---

## 🔗 相关链接

- [LangChain4j 官方文档](https://docs.langchain4j.dev/)
- [Spring Boot 官方文档](https://spring.io/projects/spring-boot)
- [项目 GitHub 仓库](https://github.com/your-org/foggy-navigator)

---

## 📝 更新日志

| 日期 | 版本 | 更新内容 |
|------|------|----------|
| 2026-01-25 | 2.0.0 | 重构文档结构，添加系统架构概览，标注各模块实施阶段 |
| 2026-01-20 | 1.0.0 | 初始版本，完成核心模块设计文档 |

---

## 💬 联系方式

如有问题或建议，请联系项目团队或提交Issue。

---

**Foggy Navigator** - 让数据查询更智能
