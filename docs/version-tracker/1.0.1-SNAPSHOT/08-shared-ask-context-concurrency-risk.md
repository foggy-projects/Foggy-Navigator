# 08 Shared Ask Context Concurrency Risk

## Date

- 2026-04-04

## Background

`POST /api/v1/shared/ask` 允许外部系统通过同一个 `X-Sharing-Key` 持续复用同一段多轮上下文。

共享调用的上下文关键字有两种：

- `contextId`: 精确上下文主键
- `contextAlias`: 业务语义别名，由后端解析到真实 `contextId`

本次评估的问题是：

- 同一个 `X-Sharing-Key`
- 同一个 `contextId` 或同一个 `contextAlias`
- 在非常接近的时间同时调用 `POST /api/v1/shared/ask`

是否会让同一个会话并发执行两个任务。

## Conclusion

结论：**有明确并发风险，且已达到缺陷级别，不只是理论上的竞态。**

当前实现只有“查询式 busy 检查”，没有“按 context 原子占用”的互斥机制，因此不能保证同一共享会话同一时刻只接受一个任务。

风险分三类：

1. 已存在上下文的续聊并发时，两个请求可能同时通过 busy 检查，然后把两个任务发到同一个会话。
2. 首次用同一个 `contextId` 并发创建时，两个任务都可能成功创建，最终由后写请求覆盖 `contextId -> session` 映射。
3. 首次用同一个 `contextAlias` 并发创建时，两个请求会各自生成不同 `contextId`，随后在保存 `contextAlias` 唯一索引时发生冲突；更糟的是，任务已经创建，接口却可能在落库阶段抛错。

## Evidence

### 1. SharedAskController 直接把 context 透传给 A2A agent

`SharedAskController` 不做任何并发串行化或会话占用控制，只是解析 `X-Sharing-Key` 后调用 `agent.sendTask(message)`：

- `session-module/src/main/java/com/foggy/navigator/session/controller/SharedAskController.java:43`
- `session-module/src/main/java/com/foggy/navigator/session/controller/SharedAskController.java:75`
- `session-module/src/main/java/com/foggy/navigator/session/controller/SharedAskController.java:93`

### 2. ContextResolvingA2aAgent 的 busy 检查是 TOCTOU

`ContextResolvingA2aAgent` 的流程是：

1. 先查 `contextId/contextAlias -> agentSessionRef/navigatorSessionId`
2. 如果查到了 `agentSessionRef`，调用 `inner.isSessionBusy(...)`
3. 如果不 busy，就继续 `inner.sendTask(...)`
4. 任务创建成功后，再 `saveSessionRefFull(...)`

关键问题：

- busy 检查只在 `agentSessionRef != null` 时发生
- busy 检查和真正创建任务之间没有锁
- 上下文保存发生在任务创建之后

对应代码：

- `session-module/src/main/java/com/foggy/navigator/session/agent/ContextResolvingA2aAgent.java:74`
- `session-module/src/main/java/com/foggy/navigator/session/agent/ContextResolvingA2aAgent.java:115`
- `session-module/src/main/java/com/foggy/navigator/session/agent/ContextResolvingA2aAgent.java:152`
- `session-module/src/main/java/com/foggy/navigator/session/agent/ContextResolvingA2aAgent.java:160`

这意味着两个并发请求完全可能同时读到“当前不 busy”，然后双双创建任务。

### 3. context store 保存不是原子创建/占用

`AgentContextStoreImpl.saveSessionRefFull(...)` 先 `findById(contextId)`，找不到就 new entity，然后 `repository.save(entity)`。

它没有：

- `SELECT ... FOR UPDATE`
- 乐观锁版本字段
- “context 正在占用中”的状态位
- “按 alias 原子获取或创建”的语义

对应代码：

- `session-module/src/main/java/com/foggy/navigator/session/service/AgentContextStoreImpl.java:78`

同时，`AgentConversationContextEntity` 对 `(contextAlias, userId, targetAgentId)` 建了唯一索引：

- `navigator-common/src/main/java/com/foggy/navigator/common/entity/AgentConversationContextEntity.java:13`
- `navigator-common/src/main/java/com/foggy/navigator/common/entity/AgentConversationContextEntity.java:15`

所以同一 `contextAlias` 首次并发创建时，两个请求若生成不同 `contextId`，第二个保存很可能在数据库层触发唯一约束冲突。

### 4. A2A continuation 走的是 createTask，不是 resumeTask

无论 Claude 还是 Codex，A2A 续聊都会把已解析的 `agentSessionRef/sessionId` 填回表单，然后仍然走 `createTask(...)`：

- Claude:
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/adapter/ClaudeWorkerInnerA2aAgent.java:86`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/adapter/ClaudeWorkerInnerA2aAgent.java:87`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/adapter/ClaudeWorkerInnerA2aAgent.java:90`
- Codex:
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/adapter/CodexWorkerInnerA2aAgent.java:80`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/adapter/CodexWorkerInnerA2aAgent.java:84`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/adapter/CodexWorkerInnerA2aAgent.java:86`

而 Provider 自己更严格的“会话已有运行中任务则拒绝”的保护是在 `resumeTask(...)` 中：

- Claude:
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java:273`
- Codex:
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java:134`

但 A2A shared ask 并不会走这里，所以 Provider 层的这道保护没有真正覆盖 shared ask 的续聊并发场景。

## Failure Scenarios

### Scenario A: 已存在 context 的续聊并发

前提：

- `contextId` 或 `contextAlias` 已经能解析到已有 `agentSessionRef + navigatorSessionId`

并发序列：

1. 请求 A 读取上下文，`isSessionBusy(...) == false`
2. 请求 B 读取同一上下文，`isSessionBusy(...) == false`
3. A 调 `createTask(...)`，创建 RUNNING 任务
4. B 也调 `createTask(...)`，再次创建 RUNNING 任务

结果：

- 同一 provider session 可能同时收到两个任务
- 同一 Navigator session 会写入两条用户消息和两条任务轨迹
- 业务层面出现“同一会话同时进行了两个任务”

### Scenario B: 同一个 contextId 首次并发创建

前提：

- 两个请求都传同一个全新 `contextId`
- `agent_conversation_contexts` 里还没有这条记录

并发序列：

1. A/B 都查不到该 `contextId`
2. A/B 都把请求视为 first turn
3. A/B 都创建新任务和新 session
4. A/B 都回写同一个 `contextId`

结果：

- 两个任务都已经启动
- 最终 `contextId` 行只会保留最后一次写入的 `agentSessionRef/navigatorSessionId`
- 先创建的那个任务变成“运行着但后续上下文恢复不到它”的孤儿分支

### Scenario C: 同一个 contextAlias 首次并发创建

前提：

- 两个请求都传同一个全新 `contextAlias`
- 该 alias 还未解析到任何 existing context

并发序列：

1. A/B 都查不到 alias
2. A/B 各自生成新的 `resolvedContextId`
3. A/B 都创建新任务
4. A 保存成功
5. B 保存时命中 `(contextAlias, userId, targetAgentId)` 唯一索引

结果：

- 至少一个 HTTP 请求可能在任务创建成功后因为上下文落库失败而报错
- 但失败请求对应的远端任务很可能已经开始执行
- 外部调用方看到的是“接口失败”，平台内部却已经存在正在运行的真实任务

这是最危险的一类，因为会造成“失败响应 + 实际已执行”的不一致。

## Additional Notes

### Claude dedup 不能当作并发安全

当前 Claude 有 first-turn dedup，但它不是原子互斥：

- dedup 是“先查近期任务，再创建任务，再回写 dedupKey”
- 并发请求仍可能在 dedupKey 回写前同时 miss
- Codex 当前没有对应 dedup 实现

对应代码：

- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/adapter/ClaudeWorkerInnerA2aAgent.java:114`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/adapter/ClaudeWorkerInnerA2aAgent.java:127`

因此 dedup 只能降低重复请求概率，不能保证同一 context 的串行执行。

### 测试覆盖存在空白

现有 A2A 测试覆盖了：

- context restore
- alias restore
- firstMsg 语义
- mismatch 保护

但没有覆盖：

- 同一 `contextId` 的并发首轮
- 同一 `contextAlias` 的并发首轮
- 同一已存在 context 的并发续聊

这意味着当前缺陷没有被回归测试约束。

## Impact

对 `POST /api/v1/shared/ask` 来说，这不是低概率噪音，而是共享 API 的核心语义破坏：

- 同一会话不能保证串行
- `contextId` 可能绑定到错误的最终 session
- `contextAlias` 首次并发可能导致 500/唯一约束异常
- 外部系统可能误判任务未创建，但实际上任务已在后台运行

## Suggested Fix Direction

建议后续修复按“context 级互斥”处理，而不是继续依赖 `hasRunningTask(...)` 这种查询式保护。

可行方向：

1. 在 `contextId` 和 `contextAlias -> contextId` 解析阶段引入原子占用。
2. 首次 alias 创建改为“先占位 context，再创建任务”，禁止“先创建任务、后写 context”。
3. 对已存在 context 的续聊，引入基于 `contextId` 的短事务锁或状态位，确保同一 context 同时只有一个 `sendTask` 能进入 provider create。
4. A2A continuation 若复用已有 provider session，应复用 provider 侧真正带并发保护的恢复路径，或在 shared decorator 层补一层原子保护。
5. 为三类并发场景补 L2/L3 回归测试。

## Agreed Interim Direction

### 1. 续聊场景先按 `sessionId` 收敛

对于已经能解析出稳定 `navigatorSessionId` 的续聊请求，先按 `sessionId` 处理并发保护。

这里的关键不是“只读一次 session 状态”，而是把它做成一次短窗口的原子切换：

1. 先按 `sessionId` 读取当前会话状态
2. 如果会话已 busy，直接拒绝
3. 如果会话空闲，获取 `sessionId` 维度的全局锁
4. 锁内再次检查会话状态，防止并发穿透
5. 调用任务创建逻辑，把平台侧状态落为 `RUNNING/PROCESSING`
6. 状态成功持久化后立即释放锁，不需要等任务执行完成

该方案的目的不是锁住整个任务生命周期，而是只锁住“空闲 -> 忙碌”的状态切换窗口。

这可以解决“已有会话续聊时，两次请求同时通过 busy 检查”的核心竞态。

### 2. 首轮场景单独设计

首轮请求还没有稳定 `sessionId`，因此不能直接复用上面的方案。

首轮仍需后续单独设计：

- `contextId` 首轮并发
- `contextAlias` 首轮并发
- alias 首次解析与真实 `contextId` 创建的原子性

本条先记录续聊方向，首轮方案待进一步沟通后单独收敛。

### 3. 首轮加锁范围先收敛

首轮并不是所有 shared ask 都要上锁。

当前先收敛为：

- 首轮**只有带 `contextId` 或 `contextAlias`** 时才需要上锁
- 没带 `contextId/contextAlias` 的一次性提问，不做首轮锁治理

这样锁的目标很明确：只保护“调用方明确要求复用或绑定同一段上下文”的首轮创建。

### 4. `contextAlias` 先转真实 `contextId`

针对首轮 `contextAlias`，先不直接进入任务创建。

先收敛为以下顺序：

1. 在数据库层按 `(contextAlias, userId, targetAgentId)` 做唯一约束
2. 首轮请求先把 `contextAlias` 解析/转换为真实 `contextId`
3. 先把真实 `contextId` 行落库
4. 后续任务创建、续聊锁、上下文恢复，都统一按真实 `contextId` 处理

这样做的目的，是把 alias 只当成“建会话时的别名入口”，而不是长期并发控制主键。

数据库唯一约束负责保证：

- 同一个 `contextAlias` 在同一个 `userId + targetAgentId` 作用域下，只能绑定一个真实 `contextId`

在此基础上，后续并发治理统一收敛到真实 `contextId`，避免 alias 和 contextId 两套并发语义长期并存。

## Status

- 2026-04-04：评估确认存在并发风险与缺陷，记录到版本跟踪，待后续修复设计收敛。
- 2026-04-04：续聊场景先按 `sessionId` 维度收敛，采用“状态判断 + sessionId 锁 + 锁内二次检查 + 状态持久化后释放锁”的方案；首轮方案待讨论。
- 2026-04-04：首轮范围进一步收敛为“只有带 `contextId/contextAlias` 的请求才加锁”；`contextAlias` 先转换并落库为真实 `contextId`，数据库唯一约束负责 alias 唯一性，后续统一按真实 `contextId` 做并发控制。
