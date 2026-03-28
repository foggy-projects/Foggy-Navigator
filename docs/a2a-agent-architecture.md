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

## 任务分派与会话绑定（需求 26 重构）

### 统一分派层

`TaskDispatchFacade`（session-module）是所有 Worker 任务的统一入口，取代了各 addon 直接暴露 Controller 的旧模式。

```
前端 POST /api/v1/tasks
  │
  ▼
TaskDispatchFacade.createTask(request, context)
  │
  ├── resolveCreateExecutionTarget(request)
  │   ├── [Direct 路由] providerType 明确 → TaskQueryProvider.createTaskDirect()
  │   └── [A2A 路由]   通过 agentId → UnifiedAgentResolver → A2aAgent.sendTask()
  │
  ├── resolveLogicalAgentId(agent, lookupId)
  │   → 优先取 agentCard.getId()，确保存储真实逻辑 Agent ID
  │
  └── SessionBindingService.getOrBind(sessionId, agentId, providerType, bindingSource)
      → 建立或验证 Session ↔ Agent 绑定
```

### 三个核心语义

| 概念 | 字段 | 含义 | 存储位置 |
|------|------|------|---------|
| 逻辑 Agent | `logicalAgentId` | 哪个 Coding Agent 负责任务 | SessionEntity.agentId |
| 执行后端 | `providerType` | claude-worker / codex-worker | SessionEntity.providerType（不可变） |
| 模型配置 | `modelConfigId` | API Key、BaseURL、模型参数 | SessionEntity.authModelConfigId |

**关键规则**：`agentId` 字段禁止存储 Provider 常量（如 "claude-worker"），必须存真实 Agent ID。

### 会话绑定生命周期（SessionBindingService）

```java
@Transactional
public String getOrBind(String sessionId, String agentId, String providerType, String bindingSource)
```

| 场景 | 行为 |
|------|------|
| 新会话首次 createTask | 写入 agentId + providerType + bindingSource |
| 同 Agent 后续 createTask | 验证一致性，通过 |
| 不同 Agent 的 createTask | 抛 `SessionAgentBoundMismatchException` |
| 遗留会话（agentId 有但 providerType 空） | 自动回填 providerType，bindingSource = "RESTORED" |
| Resume 任务 | 始终使用已绑定的 providerType，不允许切换 |

### SessionEntity 新字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `providerType` | VARCHAR(32) | 执行后端标识，绑定后不可变 |
| `bindingSource` | VARCHAR(32) | 绑定来源：EXPLICIT_AGENT / LEGACY_MODEL_CONFIG / RESTORED |
| `authModelConfigId` | VARCHAR(64) | 认证模型配置 ID |
| `providerStateJson` | TEXT | Provider 特定状态存储 |

### SessionTaskEntity 新字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `providerType` | VARCHAR(32) NOT NULL | 执行该任务的 Provider |

### DispatchTaskDTO

统一任务视图，跨 Provider 通用：
- 通用字段：taskId, sessionId, agentId, providerType, status, model, cost...
- Claude 扩展：claudeSessionId, checkpoints
- Codex 扩展：codexThreadId
- A2A 扩展：contextId

### Provider Type 路由优先级（新建任务）

```
1. request.providerType（显式指定）
2. modelConfigId → LlmModelManager → workerBackend → providerType
3. agentId → UnifiedAgentResolver.getProviderType()
4. 报错：后端不明确
```

## 扩展方式

新增 Agent 类型只需：
1. 在 addon 模块实现 `A2aAgentProvider`
2. 标注 `@Component`，Spring 自动注入到 Registry
3. 无需修改 session-module 任何代码

新增 Provider 类型还需：
4. 在 `TaskDispatchFacade` 注册对应的 `TaskQueryProvider`
5. 在 `SessionBindingService` 确保 bindingSource 枚举覆盖
