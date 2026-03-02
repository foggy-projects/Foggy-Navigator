# A2A Agent 统一发现与调用架构

> Foggy Navigator 的 Agent 编排核心，基于 Google A2A（Agent-to-Agent）协议设计。

## 架构概览

```
┌─────────────────────────────────────────────────────┐
│  REST API Layer (session-module)                     │
│  AgentDiscoveryController: /api/v1/agents            │
│    GET /              → 列出所有 Agent                │
│    GET /{id}/card     → 获取 Agent 名片              │
│    POST /{id}/ask     → 向 Agent 提问（同步）         │
│    GET /consultations → 查询 @Agent 咨询记录          │
└──────────────────┬──────────────────────────────────┘
                   │
┌──────────────────▼──────────────────────────────────┐
│  DefaultA2aAgentRegistry (session-module)             │
│  聚合所有 A2aAgentProvider，统一发现与解析              │
│    listAgents(userId)                                 │
│    resolveAgent(agentId, userId)                      │
│    listByProviderType(type, userId)                   │
└──────────────────┬──────────────────────────────────┘
                   │ Spring DI: List<A2aAgentProvider>
       ┌───────────┴───────────┐
       ▼                       ▼
┌──────────────┐      ┌───────────────┐
│ claude-worker │      │ (future)      │
│ AgentProvider │      │ other addons  │
└──────┬───────┘      └───────────────┘
       │
       ▼
┌──────────────────────────────────────┐
│  ClaudeWorkerA2aAgent                 │
│  通过 workerFacade.syncQuery() 执行   │
│  maxTurns=1, 同步等待结果              │
└──────────────────────────────────────┘
```

## SPI 层（navigator-spi/spi/agent/）

### A2aAgent — 统一执行接口

```java
public interface A2aAgent {
    A2aAgentCard getAgentCard();                    // Agent 名片
    A2aTask sendTask(A2aMessage message);           // 发送任务（同步返回结果）
    Optional<A2aTask> getTask(String taskId);       // 查询任务状态
    void cancelTask(String taskId);                 // 取消任务
    boolean isAvailable();                          // 可用性检查
}
```

### A2aAgentProvider — 提供者模式

```java
public interface A2aAgentProvider {
    List<A2aAgentCard> listAgentCards(String userId);           // 该 Provider 管理的所有 Agent
    Optional<A2aAgent> resolveAgent(String agentId, String userId);  // 解析 Agent 实例
    String getProviderType();                                   // 类型标识: "claude-worker" 等
}
```

### A2A DTO 结构

- `A2aAgentCard`: name, description, endpoint, skills[]
- `A2aAgentCardSkill`: name, description, tags[]
- `A2aMessage`: role, parts[]
- `A2aPart`: type(text/data/file), text, data, file
- `A2aTask`: id, status{state, description}, artifacts[], history[]
- `A2aArtifact`: artifactId, name, parts[]

## 平台层（session-module）

### DefaultA2aAgentRegistry

- 通过 Spring DI 注入 `List<A2aAgentProvider>`，自动聚合所有 Provider
- `listAgents(userId)`: 展平所有 Provider 的 AgentCard
- `resolveAgent(agentId, userId)`: 遍历 Provider 查找匹配的 Agent
- `listByProviderType(type, userId)`: 按 Provider 类型筛选

### AgentDiscoveryController

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/v1/agents` | GET | 列出所有 Agent（可选 `?type=` 过滤） |
| `/api/v1/agents/{agentId}/card` | GET | 获取 Agent 名片 |
| `/api/v1/agents/{agentId}/ask` | POST | 向 Agent 提问，body: `{question, sessionId?}` |
| `/api/v1/agents/consultations` | GET | 查询会话的 @Agent 咨询记录 `?sessionId=` |

`ask` 端点行为：
1. 解析 Agent → `registry.resolveAgent()`
2. 构建 A2aMessage → `agent.sendTask()`
3. 记录 AgentConsultationEntity（含 sessionId 时）
4. 更新 SessionEntity.participatingAgentIds

## Claude Worker Agent 实现

### ClaudeWorkerAgentProvider

- 实现 `A2aAgentProvider`，providerType = `"claude-worker"`
- 从 `CodingAgentRepository` 读取 `agentType == LOCAL_CLAUDE_WORKER` 的实体
- 将 `CodingAgentEntity` 适配为 `ClaudeWorkerA2aAgent`

### ClaudeWorkerA2aAgent

- Package-private 实现，通过 `workerFacade.syncQuery()` 执行
- 参数: `(userId, workerId, prompt, cwd, null, maxTurns=1, null)`
- syncQuery 同步阻塞（最长 60s），返回 `{resultText, error}`
- 状态映射: PENDING→SUBMITTED, RUNNING→WORKING, COMPLETED→COMPLETED, FAILED→FAILED, ABORTED→CANCELED, AWAITING_PERMISSION→INPUT_REQUIRED

## 扩展方式

新增 Agent 类型只需：
1. 在 addon 模块实现 `A2aAgentProvider`
2. 标注 `@Component`，Spring 自动注入到 Registry
3. 无需修改 session-module 任何代码
