# A2A Agent 统一发现与调用架构

> 当前 Foggy Navigator 中 Agent 发现、解析与调用的统一模型

## 1. 文档定位

本文档解释的是平台如何把不同来源的 Agent 统一暴露出来并调用。

它是实现设计文档，不负责定义产品功能范围。产品功能边界请优先看：

- [系统架构概览](./00-system-overview.md)
- [功能架构说明](./02-modules/functional-architecture.md)

## 2. 当前作用

A2A 架构在当前系统中主要承担三类职责：

1. Agent 发现  
   把多个 provider 暴露的 Agent 聚合成统一列表。
2. Agent 解析  
   根据 `agentId` 找到具体的执行对象。
3. Agent 调用  
   通过统一接口发问、获取任务、取消任务。

它当前主要服务：

- 会话中的 Agent 发现与问答
- 统一任务分发中的 A2A Route
- 部分跨 Agent 协作场景

## 3. 当前架构层次

```text
前端 / 外部调用
  -> AgentDiscoveryController
  -> TaskDispatchFacade (A2A Route)

session-module
  -> DefaultA2aAgentRegistry
  -> UnifiedAgentResolver

SPI
  -> A2aAgent
  -> A2aAgentProvider

各 addon / provider 实现
  -> ClaudeWorkerAgentProvider
  -> 其他 provider
```

## 4. 核心接口

### 4.1 `A2aAgent`

统一执行接口，表达“一个可调用 Agent”。

当前承担的动作包括：

- 返回 Agent Card
- 发送任务
- 查询任务
- 取消任务
- 检查可用性

### 4.2 `A2aAgentProvider`

统一提供者接口，表达“某类 Agent 来源”。

每个 provider 负责：

- 列出自己管理的 Agent Card
- 解析指定 `agentId`
- 返回自己的 `providerType`

## 5. 当前平台实现

### 5.1 `DefaultA2aAgentRegistry`

职责：

- 聚合所有 `A2aAgentProvider`
- 列出所有 Agent
- 根据 `agentId` 解析 Agent
- 按 providerType 过滤 Agent

### 5.2 `AgentDiscoveryController`

这是前端和外部系统感知 A2A 的主要入口。

当前暴露能力：

- 列出 Agent
- 获取 Agent Card
- 向指定 Agent 发问
- 查询 consultation 记录

### 5.3 `UnifiedAgentResolver`

它是统一任务分发中的桥梁，负责：

- 根据 `agentId` 找到真实 Agent
- 推断 `providerType`
- 给 `TaskDispatchFacade` 提供一致的解析结果

## 6. 当前主要 provider

### 6.1 Claude Worker Agent Provider

当前代码中最明确的 A2A provider 是 Claude Worker 方向的 provider。

它负责：

- 把可用的 Claude Worker / Coding Agent 适配成统一 Agent
- 通过 facade 将同步问答能力包装成 `A2aAgent`

### 6.2 扩展空间

这个架构允许后续继续挂接新的 Agent 来源，但前提是：

- 实现 `A2aAgentProvider`
- 暴露可解析的 `agentId`
- 接入统一调用语义

## 7. 与统一任务分发的关系

`TaskDispatchFacade` 当前支持两种路径：

- Direct Route
- A2A Route

其中 A2A Route 的核心就是：

1. 根据 `agentId` 找到真实 Agent
2. 获取其 `providerType`
3. 建立 Session 绑定
4. 调用 `A2aAgent.sendTask()`

所以 A2A 架构不是独立产品能力，而是统一任务分发的重要底座。

## 8. 当前边界

### 8.1 它不是通用工作流引擎

它关注的是 Agent 的发现与调用，不负责：

- 多阶段流程编排
- 目录治理
- Git/文件操作
- 配置资源治理

### 8.2 它不是主前端入口

用户通常不会直接感知“A2A”这个概念，而是通过：

- 会话中的 Agent 问答
- Workers 中的任务执行
- 后端统一任务分发

间接使用这套架构。

## 9. 阅读建议

建议结合以下文档一起阅读：

- [Session Module](./02-modules/session-module.md)
- [任务治理中心](./02-modules/task-governance.md)
- [会话协作中心](./02-modules/session-collaboration.md)
