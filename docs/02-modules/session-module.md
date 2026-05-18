# Session Module

> 当前实现对应的会话、统一任务分发与实时通信模块说明

## 1. 模块定位

`session-module` 是 Foggy Navigator 的核心业务模块之一，负责把“会话、消息、统一任务分发、Agent 发现、SSE 推送”收口到一个平台层。

它既服务主会话界面，也服务 Workers、任务看板、跨项目阶段回跳和通知链路。

## 2. 当前职责

| 职责 | 关键对象 |
|------|------|
| Session 生命周期管理 | `SessionController`、`SessionManager`、`JpaSessionManager` |
| 消息持久化与查询 | `SessionController`、`SessionRepository` |
| 统一任务分发 | `TaskController`、`TaskDispatchFacade` |
| Session 与 Agent/Provider 绑定 | `SessionBindingService` |
| A2A Agent 发现与问答 | `AgentDiscoveryController`、`DefaultA2aAgentRegistry`、`UnifiedAgentResolver` |
| SSE 推送与订阅 | `UnifiedSseController`、`UnifiedSseEmitter` |
| 分享问答 | `SharingKeyController`、`SharedAskController` |
| 会话配置管理 | `SessionConfigController` |

## 3. 在当前产品中的位置

### 3.1 它支撑哪些前端入口

- `会话`
- `任务`
- `Workers` 中的任务交互能力
- `跨项目` 中阶段会话回跳
- 通知与 SSE 相关能力

### 3.2 它不负责什么

- 不负责远程 Worker 的目录、文件、进程治理
- 不直接实现 Claude/Codex/Gemini 具体执行逻辑
- 不定义 LLM、Git、凭证等平台配置资源

这些能力分别属于 addon 模块或配置模块。

## 4. 当前核心模型

### 4.1 Session 视角

Session 是平台级主对象，承接：

- 用户消息
- Agent 回复
- 父子会话关系
- Agent 参与记录
- provider 绑定关系
- 可选模型配置

### 4.2 Task 视角

Task 是执行视角的主对象，负责表达：

- 哪个任务在执行
- 属于哪个 Session
- 由哪个 provider 执行
- 目前执行状态如何
- 是否可取消、恢复、重连、重同步

### 4.3 Agent 视角

`session-module` 不实现具体业务 Agent，但负责：

- 发现可用 Agent
- 根据 `agentId` 解析到对应实现
- 通过 A2A 或统一分发入口对接执行

## 5. 当前两条核心链路

### 5.1 会话消息链路

```text
前端发送消息
  -> SessionController
  -> SessionManager 持久化用户消息
  -> AgentInvoker 异步调用目标 Agent
  -> Agent 输出消息/事件
  -> Session 事件监听与持久化
  -> UnifiedSseEmitter 推送到前端
```

### 5.2 统一任务分发链路

```text
前端创建任务
  -> TaskController
  -> TaskDispatchFacade
  -> 解析 providerType / modelConfigId / agentId
  -> SessionBindingService 建立或校验绑定
  -> 走 Direct Route 或 A2A Route
  -> 返回统一任务 DTO
```

## 6. Session 绑定机制

`SessionBindingService` 负责把 Session 与执行上下文绑定起来。

绑定后，平台会固定：

- `agentId`
- `providerType`
- `authModelConfigId`

这保证以下动作在同一执行语义下进行：

- 继续对话
- 恢复任务
- 重连任务
- 重同步任务

## 7. 当前暴露的能力面

### 7.1 会话能力

- 创建、查询、删除 Session
- 查询消息
- 发送消息
- 获取 Guide Cards

### 7.2 配置能力

- 绑定认证配置
- 归档 / 取消归档
- Hold / Unhold
- 批量绑定认证

### 7.3 任务能力

- 创建任务
- 查询任务
- 取消、恢复、重连、重同步、回溯
- 查询目录级和 Worker 级任务信息

### 7.4 A2A 能力

- 列出 Agent
- 获取 Agent Card
- 对指定 Agent 发问
- 查询会话中的 Agent consultation 记录

### 7.5 分享能力

- 创建分享 Key
- 查询分享 Key
- 更新 / 删除分享 Key
- 通过分享入口公开提问

### 7.6 SSE 能力

- 建立统一 SSE 连接
- 订阅和取消订阅
- 查询当前订阅
- 推送消息、任务状态、通知和助手通知

## 8. 与其他模块的关系

| 关联模块 | 关系 |
|------|------|
| `agent-framework` | 会话消息链路中的 Agent 调用底座 |
| `tutor-agent` | 会话默认入口 Agent |
| `metadata-config-module` | 任务分发时会读取模型配置与资源配置 |
| `user-auth-module` | 所有会话与任务能力依赖用户身份 |
| `addons/claude-worker-agent` | 提供 Worker 执行能力、目录会话与跨项目会话来源 |
| `addons/codex-worker-agent` | 提供 Codex 类执行能力 |
| `addons/task-assistant` | 通过事件和 SSE 向会话侧发通知 |

## 9. 当前阅读建议

如果要理解当前主产品链路，建议和以下文档一起看：

- [系统架构概览](../00-system-overview.md)
- [功能架构说明](./functional-architecture.md)
- [会话协作中心](./session-collaboration.md)
- [任务治理中心](./task-governance.md)
- [A2A Agent 架构](../a2a-agent-architecture.md)
