# Worker 执行上下文与路由规范设计

> 目标：消除 `agentId`、`providerType`、`modelConfigId` 在 Worker 任务链路中的语义混用，定义稳定的执行上下文模型与统一路由规范。

## 1. 背景

当前 Worker 任务链路存在三套并行但不一致的路由机制：

1. A2A 路径：`agentId -> UnifiedAgentResolver -> A2aAgent`
2. Direct 路径：`modelConfigId.workerBackend -> TaskQueryProvider`
3. Resume 路径：`session.providerType -> TaskQueryProvider`

这导致以下问题：

- `agentId` 既被当作逻辑 Agent，也被当作 `directoryId` / `workerId` / `providerType` 的查找键
- 新任务和续聊任务的 provider 决策顺序不同
- `modelConfigId` 在部分链路中承担“选 provider”职责，在部分链路中仅承担“选 auth/env/model”职责
- Claude / Codex 对“本次任务实际绑定的逻辑 Agent”持久化不一致
- 前端 UI 会按 `claude-worker` 默认 override 自动选模型，放大后端语义混乱

结论：

- Bug 的根因不是单个分支判断错误，而是输入模型本身没有被严格定义
- 现有 `TaskDispatchRequest` 语义不足以表达“逻辑 Agent”和“执行 Provider”是两个不同维度

## 2. 现状确认

以下结论来自当前主目录代码：

- `TaskDispatchFacade.createTask()` 在显式 `agentId` 时走 A2A 解析链
- `TaskDispatchFacade.tryCreateTaskDirect()` 在未传 `agentId` 且传了 `modelConfigId` 时，直接根据 `workerBackend` 选择 `providerType`
- `TaskDispatchFacade.resumeTask()` 优先使用 `SessionEntity.providerType`
- `UnifiedAgentResolver` 当前只接受单个 lookup key，不理解复合上下文
- `ClaudeWorkerAgentProvider` / `CodexWorkerAgentProvider` 当前解析的其实是“逻辑 Agent 实体或其别名”
- `ClaudeTaskService` 大量场景会将 `agentId` 固定写为 `claude-worker`
- `CodexTaskService` 已引入 `resolvedAgentId`，但该值未在 task entity 中稳定持久化
- `SessionEntity.authModelConfigId` 字段存在，但当前 Worker 主链路并未可靠维护

## 3. 设计目标

本次规范的目标不是立即重构全部实现，而是先定义：

1. 输入字段的唯一语义
2. 新任务 / 续聊任务的统一路由优先级
3. 顶层组合解析层与底层执行 provider 的职责边界
4. 会话与任务投影的最小持久化字段集
5. 前端表单与后端 DTO 的契约边界

## 4. 核心术语

### 4.1 logicalAgentId

表示逻辑上的 Coding Agent。

特征：

- 对应 `CodingAgentEntity.agentId`
- 表达“谁负责理解/执行这个项目上下文”
- 不再允许被 `directoryId`、`workerId`、`providerType` 冒充

### 4.2 providerType

表示实际执行后端。

当前取值：

- `claude-worker`
- `codex-worker`

特征：

- 表达“任务最终落到哪个执行引擎”
- 是续聊时的唯一真相源
- 不等同于 `logicalAgentId`

### 4.3 modelConfigId

表示平台模型与认证配置。

特征：

- 负责提供 API key / baseUrl / envVars / availableModels
- 对新任务可以参与推导 `providerType`
- 不能替代 `logicalAgentId`
- 必须与最终 `providerType` 做一致性校验

### 4.4 TaskExecutionContext

统一任务路由输入模型，替代“单个 `agentId` lookup key”的旧思路。

建议字段：

- `logicalAgentId`
- `sessionId`
- `providerType`
- `modelConfigId`
- `workerId`
- `directoryId`
- `cwd`
- `prompt`
- `model`
- `maxTurns`
- `permissionMode`
- `agentTeamsConfigId`
- `agentTeamsJson`
- `contextId`
- `claudeSessionId`
- `codexThreadId`

说明：

- `providerType` 可为空
- `sessionId` 在新任务中可为空
- `logicalAgentId` 在“只按 provider 运行”的场景中可为空

## 5. 字段规范

### 5.1 禁止的旧行为

以下行为后续应逐步移除：

- 用 `directoryId` 作为 `agentId` 的 fallback
- 用 `workerId` 作为 `agentId` 的 fallback
- 用 `providerType` 常量覆盖真实 `logicalAgentId`
- 让具体 provider 自己决定“是否把任务转交给另一家 provider”

### 5.2 允许的输入缺省

新任务允许：

- 仅传 `modelConfigId + workerId + directoryId`
- 仅传 `providerType + workerId + directoryId`
- 传 `logicalAgentId` 并省略 `providerType`

续聊任务必须传：

- `sessionId`
- 对 Claude：`claudeSessionId`
- 对 Codex：`codexThreadId`

### 5.3 一致性校验

若同时存在：

- `providerType`
- `modelConfigId`

则必须满足：

- `modelConfig.workerBackend` 与 `providerType` 一致

若同时存在：

- `logicalAgentId`
- `providerType`

则必须满足：

- `logicalAgentId` 在该 `providerType` 下可执行

## 6. 路由规范

### 6.1 新任务

新任务的 provider 决策顺序固定为：

1. 若显式传入 `providerType`，使用它
2. 否则若传入 `modelConfigId`，根据 `workerBackend` 推导 `providerType`
3. 否则若 `logicalAgentId` 有默认执行 backend，使用它
4. 否则报错：执行 backend 不明确

然后：

1. 若存在 `logicalAgentId`，校验其与 `providerType` 兼容
2. 构造 concrete execution request
3. 路由到对应 execution provider

### 6.2 续聊任务

续聊的 provider 决策顺序固定为：

1. `sessionId -> SessionEntity.providerType`
2. 若 session 未绑定 providerType，仅允许旧数据迁移兜底
3. 不允许续聊时切换 `providerType`

然后：

1. 若显式传入 `modelConfigId`，仅做兼容性校验，不允许改写 provider
2. 若显式传入 `logicalAgentId`，仅做一致性校验，不允许跨 Agent 漂移

### 6.3 读操作

以下操作应始终通过任务或会话中已保存的 `providerType` 路由：

- `resume`
- `reconnect`
- `resync`
- `rewind`
- `respond`
- `delete`

不再允许读操作重新推导 provider。

## 7. 推荐架构

## 7.1 不推荐方案

不推荐让 `ClaudeWorkerAgentProvider` 或 `CodexWorkerAgentProvider` 自己判断是否要转给对方。

原因：

- provider 职责应是“执行本 provider 的任务”
- 若 provider 内部再做二次转发，职责边界继续混乱
- 会导致排查日志和测试覆盖都更加困难

## 7.2 推荐方案：组合解析层

新增顶层组合解析层，例如：

- `WorkerExecutionResolver`
- 或 `CompositeWorkerA2aProvider`

职责：

1. 解析 `TaskExecutionContext`
2. 识别 `logicalAgentId`
3. 决定 `providerType`
4. 选择具体 execution provider

底层 provider：

- `ClaudeExecutionProvider`
- `CodexExecutionProvider`

底层只负责执行，不负责“选哪家”。

## 7.3 推荐的调用链

```text
TaskExecutionContext
  -> WorkerExecutionResolver
    -> resolve logical agent
    -> resolve provider type
    -> validate model/provider compatibility
    -> route to concrete execution provider
      -> claude-worker implementation
      -> codex-worker implementation
```

## 8. 持久化规范

### 8.1 SessionEntity

会话至少需要稳定保存：

- `agentId`
  - 含义：`logicalAgentId`
- `providerType`
  - 含义：实际执行 backend
- `bindingSource`
  - 取值建议：`EXPLICIT_PROVIDER` / `MODEL_CONFIG` / `AGENT_DEFAULT` / `RESTORED`
- `authModelConfigId`
  - 含义：会话首次绑定或当前锁定的模型配置

规则：

- `providerType` 一旦绑定，续聊不可变
- `authModelConfigId` 应真实写入，不再被随意清空

### 8.2 SessionTaskEntity

统一任务投影至少需要保存：

- `agentId`
  - 含义：`logicalAgentId`
- `providerType`
  - 含义：执行 backend
- `model`
- `taskStateJson`
  - provider-specific 状态

规则：

- 不允许再将 `agentId` 强制写成 `claude-worker` / `codex-worker`

### 8.3 Provider-specific task entity

短期内保留 `ClaudeTaskEntity` / `CodexTaskEntity`，但应补齐：

- Claude：新增或同步持久化 `resolvedAgentId`
- Codex：将 `resolvedAgentId` 从 transient 过渡到可稳定同步字段

## 9. 前端契约规范

前端 Workers 页应遵守：

1. `selectedAgentId` 只表示逻辑 Agent
2. `platformModelConfigId` 只表示模型配置
3. 不再假定某个固定 override（例如 `claude-worker`）就是当前默认选择
4. 模型列表应按当前解析出的 backend 过滤，而不是把 Claude/Codex 混在一起

前端建议新增概念：

- `selectedProviderType`

其来源：

- 已选会话：来自 `session.providerType`
- 新任务：来自 `modelConfig.workerBackend` 或显式 provider 选择

## 10. 实施阶段

### Phase 1：定义与落盘

目标：

- 文档化本规范
- 停止新增依赖旧语义的代码

动作：

- 统一注释和命名
- 明确 `TaskDispatchRequest` 已过渡，准备替换为 `TaskExecutionContext`

### Phase 2：组合解析层落地

目标：

- 用统一解析器替代 `resolveAgentLookup()` 与 `tryCreateTaskDirect()`

动作：

- 新增 `WorkerExecutionResolver`
- `TaskDispatchFacade` 改为先解析 execution context，再决定调用 A2A 或 provider

### Phase 3：持久化修正

目标：

- 会话和任务投影中稳定保存 `logicalAgentId + providerType + authModelConfigId`

动作：

- 修复 Claude 侧将 `agentId` 固定写死为 `claude-worker` 的逻辑
- 修复 `authModelConfigId` 未真实写入的问题

### Phase 4：前端收口

目标：

- UI 只展示与当前 provider 兼容的模型配置

动作：

- 按 provider 过滤 `platformModels`
- 移除 `claude-worker` 硬编码 override 默认逻辑

## 11. 需要补的回归测试

至少补以下场景：

1. 新任务：未传 `agentId`，仅传 `modelConfigId=OPENAI_CODEX`，应稳定路由到 `codex-worker`
2. 新任务：显式 `logicalAgentId` 属于 Claude，但 `modelConfigId` 属于 Codex，应直接报错
3. 续聊：session 已绑定 `codex-worker`，即使传入 Claude 配置也不得改路由
4. 会话投影：`session.agentId` 必须保存真实逻辑 Agent，而不是 provider 常量
5. 前端：切换 Codex 会话时，不应默认落到 `claude-worker` 的 override

## 12. 本次设计结论

本次重构的关键不是“再加一层 provider”本身，而是先把三个概念彻底拆开：

- `logicalAgentId`
- `providerType`
- `modelConfigId`

只要这三者仍能相互替代，后续无论使用 A2A provider、direct provider，还是二级 provider，都会继续产生同类 Bug。

因此，推荐路径是：

1. 定义 `TaskExecutionContext`
2. 引入组合解析层
3. 将 Claude / Codex provider 降为纯执行 provider
4. 用持久化字段固定住真实路由结果
