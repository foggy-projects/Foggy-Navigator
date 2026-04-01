# 29 - A2A `systemPrompt` / `firstMsg` 语义收敛

## 背景

当前 A2A `sendTask()` 链路只有一条消息入口，不同 Agent 对“系统提示词”的支持程度不一致：

1. 平台希望保留 `systemPrompt` 能力，供原生支持该能力的 Agent 使用。
2. 对不支持原生 `systemPrompt` 的 Agent，如果由平台偷偷把 system prompt 拼接进首条消息，会混淆能力边界。
3. 某些场景只需要“首轮可见初始化消息”，而不是严格的系统级不可见指令。
4. 当前 `SharedAskController` 已存在 controller 层直接拼接 `systemPrompt` 到 prompt 的逻辑，这会让不同入口行为分叉。

## 目标

1. **保留 `systemPrompt` 能力，但要求显式能力声明**。
2. **对不支持 `systemPrompt` 的 Agent，直接报错**，不再做平台隐式降级。
3. **新增 `firstMsg`**，定义为“仅在首次创建会话时，拼接到第一条用户消息前面”的显式首轮消息增强机制。
4. **`firstMsg` 是用户可观测内容**，会进入首轮 prompt 和会话消息，便于审计、排查和前端展示。
5. **不新增 A2A `init` 入口**，继续保持单入口 `sendTask()` 语义。

## 最终语义

### `systemPrompt`

- 语义：Agent 原生系统提示词能力。
- 适用范围：仅 `A2aAgentCard.capabilities.supportsSystemPrompt == true` 的 Agent。
- 行为：
  - 支持时：透传给 Agent Provider。
  - 不支持时：直接返回失败，提示调用者改用 `firstMsg`。

### `firstMsg`

- 语义：平台首轮附加消息。
- 适用范围：所有 Agent。
- 行为：
  - 仅在**首次创建会话**时生效。
  - 续聊时不再注入。
  - 若调用时已命中既有 Agent 会话，则忽略。
- 可观测性：
  - 会被拼入首轮 prompt。
  - 会进入平台会话消息和任务 prompt。
  - 用户可以在会话中看到这段内容。

### 为什么不引入 `init`

当前不新增 `A2aAgent.init(...)`，原因：

1. 会引入新的生命周期状态：已初始化未开始、重复初始化、初始化超时等。
2. 现有 `contextId/contextAlias -> sendTask()` 模型已经足以表达“首轮消息增强”。
3. 当前需求只涉及首轮消息拼接，不涉及必须独立初始化的资源。

若未来出现“初始化工具集 / 会话变量 / 授权上下文”等场景，再考虑引入 `init`。

## API 设计

三个入口统一支持：

- `question`
- `contextId`
- `contextAlias`
- `systemPrompt`
- `firstMsg`

入口包括：

1. `POST /api/v1/agents/{agentId}/ask`
2. `POST /api/v1/open/agents/{agentId}/ask`
3. `POST /api/v1/shared/ask`

### 共享调用

`SharingKeyEntity.systemPrompt` 保留，但语义调整为：

- 作为共享调用默认 `systemPrompt`
- 若目标 Agent 不支持原生 `systemPrompt`，则共享调用同样失败
- 不再在 controller 层偷偷拼到首条 prompt

后续若需要“共享场景首轮附加消息”，可单独为 sharing key 增加 `firstMsg`，本次先不扩展数据库字段。

## Agent Capability 变更

`A2aAgentCapabilities` 新增：

```java
private Boolean supportsSystemPrompt;
```

当前实现建议：

- Claude Worker: `false`
- Codex Worker: `false`
- 未来若某 Provider 具备原生系统提示词能力，再显式改为 `true`

## 执行链路设计

不在 controller 里做拼接，统一下沉到 A2A 公共 decorator。

### Controller 层

- 只做入参解析
- 将 `systemPrompt` / `firstMsg` 放入 `A2aMessage.metadata`
- 不做文本拼接

### ContextResolvingA2aAgent 层

新增公共策略：

1. 解析 `contextId/contextAlias`
2. 恢复 `agentSessionRef/navigatorSessionId`
3. 根据 `A2aAgentCard.capabilities.supportsSystemPrompt` 校验 `systemPrompt`
4. 判断当前是否首次创建会话：
   - 未解析出 `agentSessionRef`，视为首次
5. 若为首次且传入 `firstMsg`：
   - 重写首条用户消息文本为：

```text
[Initial Message]
{firstMsg}

[User Message]
{question}
```

6. 若不是首次：
   - 忽略 `firstMsg`

### Provider 层

- Claude / Codex Provider 只消费 decorator 已处理好的消息
- 原生 `systemPrompt` 支持的 Provider 再额外从 metadata 中读取 `systemPrompt`

## 失败行为

当调用方对不支持 `systemPrompt` 的 Agent 传入 `systemPrompt` 时：

- 返回 `FAILED`
- 错误信息示例：

```text
Agent does not support systemPrompt; use firstMsg for first-turn visible context instead.
```

## 兼容性

### 向后兼容

- 旧调用方不传 `systemPrompt` / `firstMsg`，行为不变。
- 旧共享调用若依赖 `SharingKey.systemPrompt` 对 Codex 生效，会变为失败；这是有意的语义收敛。

### 不兼容变更

- 平台不再为不支持 `systemPrompt` 的 Agent 做隐式首轮拼接。

## 开发计划

1. 记录本设计文档。
2. 为公共 DTO / 表单增加 `firstMsg` / `systemPrompt` 入参支持。
3. 为 `A2aAgentCapabilities` 增加 `supportsSystemPrompt`。
4. 在 `ContextResolvingA2aAgent` 实现：
   - `systemPrompt` 能力校验
   - `firstMsg` 首轮注入
5. 移除 `SharedAskController` 中现有 controller 层拼接逻辑。
6. 更新 SDK / 前端 API 参数。
7. 补充 Claude/Codex A2A 测试。

## 验收标准

1. 对 Codex 传 `systemPrompt`：
   - 直接失败
   - 不创建任务
   - 错误信息指向 `firstMsg`
2. 对 Codex 传 `firstMsg`：
   - 首轮任务 prompt 被注入
   - 同一 `contextId` 续聊时不再注入
3. 对共享调用：
   - 不再由 controller 直接拼接 `systemPrompt`
4. `A2aAgentCard` 能返回 `supportsSystemPrompt`
5. SDK / 前端接口能传递 `firstMsg` / `systemPrompt`
