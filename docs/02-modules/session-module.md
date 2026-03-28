# Session Module — 会话管理与任务分派

> **最后更新**: 2026-03-28（需求 26 重构后）

## 1. 模块定位

session-module 是 Foggy Navigator 的**会话持久化、任务分派与实时通信**核心模块，承担以下职责：

| 职责 | 关键类 |
|------|--------|
| 会话 CRUD + 消息持久化 | JpaSessionManager, SessionController |
| **统一任务分派**（A2A / Direct 双路由） | TaskDispatchFacade, TaskController |
| **会话-Agent 绑定管理** | SessionBindingService |
| Agent 发现与解析 | DefaultA2aAgentRegistry, UnifiedAgentResolver |
| SSE 实时推送 | UnifiedSseEmitter, UnifiedSseController |
| AgentMessage 事件监听 | SessionEventListener |

## 2. 架构概览

### 2.1 两条调用链

**Tutor Agent 路径**（agent-framework 内建）：

```
Frontend → SessionController → JpaSessionManager + AgentInvoker
                                                      ↓ (异步)
                                            AgentMessage (Spring Event)
                                                      ↓
                                            SessionEventListener
                                            ├── 持久化到 DB
                                            └── SSE 推送到前端
```

**Worker 任务分派路径**（需求 26 重构后）：

```
Frontend → TaskController
               ↓
     TaskDispatchFacade.createTask(request, context)
               ↓
     resolveCreateExecutionTarget(request)
         ┌─────────┴─────────┐
         ▼                   ▼
   [Direct Route]      [A2A Route]
   providerType 明确     agentId 解析
   → createTaskDirect() → UnifiedAgentResolver.resolveAgent()
                              ↓
                   resolveLogicalAgentId()
                              ↓
              SessionBindingService.getOrBind()
              (建立/验证 Session ↔ Agent 绑定)
                              ↓
                   agent.sendTask(message)
                              ↓
                   toDispatchDTO() → DispatchTaskDTO
```

### 2.2 三个核心语义（需求 26）

| 概念 | 字段 | 含义 | 存储 |
|------|------|------|------|
| 逻辑 Agent | `logicalAgentId` | 哪个 Coding Agent 负责任务 | SessionEntity.agentId |
| 执行后端 | `providerType` | claude-worker / codex-worker | SessionEntity.providerType（不可变） |
| 模型配置 | `modelConfigId` | API Key / BaseURL / 模型参数 | SessionEntity.authModelConfigId |

**关键规则**：`agentId` 字段禁止存储 Provider 常量（如 "claude-worker"），必须存真实 Agent ID。

## 3. 会话绑定生命周期

`SessionBindingService` 管理 Session ↔ Agent 的绑定关系：

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

### Provider Type 路由优先级（新建任务）

```
1. request.providerType（显式指定）
2. modelConfigId → LlmModelManager → workerBackend → providerType
3. agentId → UnifiedAgentResolver.getProviderType()
4. 报错：后端不明确
```

## 4. 数据模型

### 4.1 SessionEntity 关键字段

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(64) PK | 会话 ID |
| userId | VARCHAR(64) | 所属用户 |
| agentId | VARCHAR(64) | 逻辑 Agent ID |
| providerType | VARCHAR(32) | 执行后端（绑定后不可变） |
| bindingSource | VARCHAR(32) | 绑定来源：EXPLICIT_AGENT / LEGACY_MODEL_CONFIG / RESTORED |
| authModelConfigId | VARCHAR(64) | 认证模型配置 ID |
| status | VARCHAR(32) | 会话状态 |
| providerStateJson | TEXT | Provider 特定状态 |
| participatingAgentIds | TEXT | 参与过的 Agent ID 列表 (JSON) |

### 4.2 SessionTaskEntity 关键字段

| 字段 | 类型 | 说明 |
|------|------|------|
| taskId | VARCHAR(64) UK | 平台任务 ID |
| sessionId | VARCHAR(64) | 所属会话 |
| providerType | VARCHAR(32) NOT NULL | 执行该任务的 Provider |
| agentId | VARCHAR(64) | 执行任务的逻辑 Agent |
| providerTaskId | VARCHAR(128) | 上游 Provider 侧任务 ID |
| taskStateJson | TEXT | Provider 特定任务状态 |

### 4.3 DispatchTaskDTO（统一任务视图）

跨 Provider 通用的任务 DTO：

- **通用字段**：taskId, sessionId, agentId, providerType, status, model, prompt, cwd, directoryId, costUsd, inputTokens, outputTokens, durationMs, numTurns, resultText, errorMessage
- **Claude 扩展**：claudeSessionId, checkpoints
- **Codex 扩展**：codexThreadId
- **A2A 扩展**：contextId
- **UI 冗余**：directoryName

## 5. REST API

### 5.1 会话管理（SessionController）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/sessions` | 创建会话 |
| GET | `/api/v1/sessions` | 查询会话列表 |
| GET | `/api/v1/sessions/{id}` | 获取单个会话 |
| DELETE | `/api/v1/sessions/{id}` | 删除会话 |
| GET | `/api/v1/sessions/{id}/messages` | 获取消息列表 |
| POST | `/api/v1/sessions/{id}/messages` | 发送消息 |
| GET | `/api/v1/sessions/{id}/stream` | SSE 事件流 |

### 5.2 Agent 发现（AgentDiscoveryController）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/agents` | 列出所有 Agent |
| GET | `/api/v1/agents/{id}/card` | 获取 Agent 名片 |
| POST | `/api/v1/agents/{id}/ask` | 向 Agent 提问（同步） |
| GET | `/api/v1/agents/consultations` | 查询 @Agent 咨询记录 |

### 5.3 任务分派（TaskController / AgentTaskController）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/tasks` | 创建任务（经 TaskDispatchFacade 路由） |
| POST | `/api/v1/tasks/resume` | 恢复任务 |
| GET | `/api/v1/tasks/{taskId}` | 查询任务 |
| POST | `/api/v1/tasks/{taskId}/cancel` | 取消任务 |
| GET | `/api/v1/tasks/active` | 列出活跃任务 |

## 6. 模块结构

```
session-module/src/main/java/com/foggy/navigator/session/
├── config/          # SessionModuleAutoConfiguration
├── controller/
│   ├── SessionController.java        # 会话 CRUD
│   ├── TaskController.java           # 统一任务分派
│   ├── AgentTaskController.java      # Agent 任务管理
│   ├── AgentDiscoveryController.java # A2A Agent 发现
│   ├── SessionConfigController.java  # 会话配置
│   ├── SharedAskController.java      # Sharing Key 公开提问
│   ├── SharingKeyController.java     # Sharing Key 管理
│   └── UnifiedSseController.java     # 统一 SSE 推送
├── registry/
│   ├── DefaultA2aAgentRegistry.java  # 聚合所有 A2aAgentProvider
│   ├── UnifiedAgentResolver.java     # 上下文感知 Agent 解析
│   └── JpaAgentRegistry.java         # Agent 配置持久化
├── service/
│   ├── JpaSessionManager.java        # SessionManager JPA 实现
│   ├── TaskDispatchFacade.java       # 统一任务分派入口（核心）
│   ├── SessionBindingService.java    # 会话-Agent 绑定管理
│   ├── AgentTaskService.java         # Agent 任务生命周期
│   ├── SessionMetadataService.java   # 会话元数据
│   └── SharingKeyService.java        # Sharing Key 服务
├── dto/             # SessionConfigDTO, UnifiedSessionDTO
├── sse/             # UnifiedSseEmitter
├── event/           # SessionEventListener
├── exception/       # SessionAgentBoundMismatchException
└── repository/      # JPA Repositories
```

## 7. Bean 优先级

- `SessionModuleAutoConfiguration` 使用 `@AutoConfigureBefore(AgentFrameworkAutoConfiguration.class)`
- JpaSessionManager 通过 `@Service` 注册，先于 InMemorySessionManager
- AgentFrameworkAutoConfiguration 的 `@ConditionalOnMissingBean(SessionManager.class)` 自动退让

## 8. 测试

| 测试类 | 覆盖范围 |
|--------|---------|
| JpaSessionManagerTest | 会话 CRUD、消息持久化 |
| TaskDispatchFacadeTest | 任务分派路由（A2A/Direct）、agentId 保持 |
| SessionBindingServiceTest | 绑定生命周期（新建/验证/遗留/冲突） |

## 9. 相关文档

- [A2A Agent 架构](../a2a-agent-architecture.md)
- [需求 26 — Worker 执行上下文路由](../requirement-tracker/2026-Q1/26-worker-execution-context-routing-design.md)
- [需求 25 — 会话存储统一](../requirement-tracker/2026-Q1/25-session-storage-unification-design.md)
- 开发指导：`.claude/skills/session-module/SKILL.md`
