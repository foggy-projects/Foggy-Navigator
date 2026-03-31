# 工具能力模块

> 当前状态：部分有效。
>
> 旧版文档把工具体系写成了一个较完整的平台级 Tool Service/MCP/HITL 架构蓝图。当前代码中真正稳定存在的是 `agent-framework` 内的轻量工具注册与执行能力，因此本文档改为“当前实现说明 + 演进方向”。

## 1. 模块定位

当前仓库中的“工具能力”主要位于 `agent-framework`，作用是给 Agent 提供一层基础工具抽象，而不是一个已经完整落地的独立工具平台。

它解决的核心问题是：

- Agent 如何声明可用工具
- 内置工具如何统一注册
- Agent 级 HTTP/MCP 工具如何挂接
- 用户凭证如何决定某些工具是否可见或可用
- LLM 返回 tool call 后如何落到统一执行入口

## 2. 当前代码中已确认的实现

当前主干实现可以从 `agent-framework/src/main/java/com/foggy/navigator/agent/framework/tool` 直接确认：

### 2.1 基础抽象

- `ToolDefinition`
- `ToolExecutionRequest`
- `ToolExecutionResult`
- `ToolRegistry`
- `CredentialStore`
- `UserToolCredential`

这一层提供了最小闭环：

- 工具描述
- 工具调用入参
- 工具调用结果
- 工具注册与查找
- 用户级工具凭证管理

### 2.2 注册表实现

- `impl/InMemoryToolRegistry`
- `impl/InMemoryCredentialStore`

当前特征很明确：

- 以内存实现为主
- 支持内置工具注册
- 支持为某个 Agent 注册 HTTP 工具或 MCP 工具
- 支持按用户绑定凭证
- 支持根据凭证过滤可用工具

需要特别注意的是：

- Agent 级工具当前主要完成“注册、可见性判断、基础执行入口”
- `InMemoryToolRegistry.executeTool(...)` 对 Agent 级工具仍是 MVP 级返回，不是完整远程执行平台

### 2.3 已有内置工具

当前可确认的内置工具包括：

- `BashTool`
- `ReadTool`
- `GlobTool`
- `GrepTool`
- `DelegateTool`
- `CheckAgentTasksTool`
- `SaveMemoryTool`
- `ListMemoryTool`
- `DeleteMemoryTool`

这说明当前工具体系的重点是：

- 文件与命令类基础能力
- 委托/任务链辅助能力
- 记忆读写能力

而不是一个已经产品化的“可视化工具市场”。

### 2.4 自动注册

- `config/BuiltInToolConfiguration`

这部分负责把内置工具在启动时注册到 `ToolRegistry`，说明当前工具能力主要服务于 Agent 运行时，而不是后台管理控制台。

## 3. 当前能力边界

对照代码，当前可以明确说“已存在”的是：

- 内置工具抽象与注册
- Agent 级工具定义
- HTTP/MCP 工具配置挂接
- 用户凭证绑定
- 基于用户凭证过滤工具可见性
- LLM tool call 对应的数据结构

当前不应直接写成“已完整落地”的包括：

- 完整的 MCP 连接池与连接治理平台
- 完整的 Tool Service 分层架构
- 通用工具状态持久化体系
- 完整的 HITL 审批中心
- 产品化的工具运营后台与工具市场
- 面向全平台的一致工具审计与限流体系

这些更接近演进方向或设计草案，而不是当前事实。

## 4. 与当前产品功能面的关系

在当前产品里，工具能力更多是底层支撑层，服务于以下场景：

- Claude/Codex/其他 Worker 的文件与命令操作
- Agent 委托链中的辅助工具调用
- 记忆写入、查询、删除
- 未来对外部 HTTP/MCP 能力的可控挂接

它不是当前前端中的独立一级导航功能，也不是用户直接配置的完整“工具中心”。

如果从功能架构看，它更适合归类为：

- Agent 运行时基础设施
- Worker 执行能力的一部分
- 平台治理中的扩展点，而不是主功能面

## 5. 当前推荐口径

后续文档中提到“工具系统”时，建议统一使用下面的表达：

- 当前系统已经具备基础工具抽象、内置工具注册、Agent 级 HTTP/MCP 工具挂接能力
- 当前实现以内存注册表与运行时调用为主
- 更复杂的连接治理、审批、安全控制与运营能力仍处于待演进状态

不建议再使用下面的表述：

- “系统已经具备完整 Tool Service 平台”
- “MCP 连接池、HITL、状态治理已全面落地”
- “工具模块已经等同于一个独立中台”

## 6. 后续整理建议

如果继续细化这部分文档，建议拆成两层：

- 一份“当前工具运行时能力说明”，专门描述 `agent-framework` 现状
- 一份“工具体系演进草案”，单独放未来规划，不和当前实现混写

## 7. 相关文档

- [系统架构概览](../00-system-overview.md)
- [功能架构说明](./functional-architecture.md)
- [平台设置与资源治理](./platform-governance.md)
- [A2A Agent 架构](../a2a-agent-architecture.md)
