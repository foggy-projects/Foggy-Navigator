# 新 Agent 接入指南

> 统一任务分发重构后，新增 Agent 只需实现 2 个 SPI 接口 + 1 个 Spring 注解，
> 无需修改任何现有 Controller、路由逻辑或前端代码。

## 架构概览

```
前端 (不改)          统一 API (不改)         你的 Agent (新增)
───────────         ──────────────         ─────────────────
/api/v1/tasks  →  TaskDispatchFacade  →  A2aAgentProvider (@Component)
                  SessionBindingService      └─ A2aAgent (sendTask/getTask/cancelTask)
                  UnifiedAgentResolver       └─ TaskQueryProvider (查询)
                  TaskQueryProvider[]     →  YourTaskService (implements TaskQueryProvider)
```

## 必须实现的接口

### 1. `A2aAgentProvider` — Agent 发现与解析

```java
@Component
public class YourAgentProvider implements A2aAgentProvider {

    @Override
    public String getProviderType() {
        return "your-agent";  // 唯一标识
    }

    @Override
    public List<A2aAgentCard> listAgentCards(String userId) {
        // 返回该用户可见的 Agent 列表
    }

    @Override
    public Optional<A2aAgent> resolveAgent(String agentId, String userId) {
        // 根据 agentId 创建 A2aAgent 实例
    }

    // 可选：覆写 tenant 维度方法（OpenAPI 场景）
    @Override
    public List<A2aAgentCard> listAgentCards(AgentResolveContext context) {
        if ("OPEN_API".equals(context.getRequestSource()) && context.getTenantId() != null) {
            return listByTenant(context.getTenantId());
        }
        return listAgentCards(context.getUserId());
    }
}
```

### 2. `A2aAgent` — 任务执行

```java
public class YourA2aAgent implements A2aAgent {

    @Override
    public A2aAgentCard getAgentCard() { /* Agent 身份信息 */ }

    @Override
    public A2aTask sendTask(A2aMessage message) {
        // 核心：接收任务，返回 SUBMITTED 状态 + taskId
        // 后台异步执行，通过 AgentMessage 事件推送进度
    }

    @Override
    public Optional<A2aTask> getTask(String taskId) {
        // 查询任务状态
    }

    @Override
    public void cancelTask(String taskId) {
        // 取消任务
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
```

### 3. `TaskQueryProvider` — 统一查询（推荐在 TaskService 上实现）

```java
@Service
public class YourTaskService implements TaskQueryProvider {

    @Override
    public String getProviderType() { return "your-agent"; }

    @Override
    public Optional<DispatchTaskDTO> getTaskById(String taskId) { /* ... */ }

    @Override
    public Optional<DispatchTaskDTO> getTaskByIdAndUser(String taskId, String userId) { /* ... */ }

    @Override
    public List<DispatchTaskDTO> listTasksBySession(String sessionId) { /* ... */ }

    @Override
    public List<DispatchTaskDTO> listActiveDispatchTasks(String userId) { /* ... */ }
}
```

## 自动生效，无需修改的部分

| 组件 | 说明 |
|------|------|
| `UnifiedAgentResolver` | 自动发现你的 `@Component` Provider |
| `TaskDispatchFacade` | 自动聚合你的 `TaskQueryProvider` |
| `TaskController /api/v1/tasks` | 自动支持你的 Agent 创建/查询/取消 |
| `AgentDiscoveryController /api/v1/agents` | 自动列出你的 Agent |
| `OpenApiController /api/v1/open/agents` | 自动支持租户维度（如果覆写了 context 方法） |
| `SessionBindingService` | 自动绑定会话到你的 Agent |
| 前端 `createTask()` | 无需改动，通过 `modelConfigId` 或 `agentId` 路由 |

## 需要做的额外工作

### 必做

1. **新建 Maven 模块** `addons/your-agent/`，依赖 `navigator-spi` + `agent-framework`
2. **实体 + Repository**：你的 Task 实体表
3. **Stream Relay**（如果是流式执行）：监听 TaskStartEvent → SSE → `AgentMessage`
   - 使用 `AgentMessageBuilder` 构建消息：
     ```java
     AgentMessageBuilder.create(sessionId, "your-agent")
         .taskId(taskId)
         .textComplete("Hello!")
         .build();
     ```

### 可选

4. **LLM 模型配置**：在 `LlmModelConfig` 表加一行 `workerBackend = "YOUR_AGENT"`
5. **前端模型列表**：在 `ClaudeWorkerView.vue` 的 model selector 加上你的 Agent 可用模型

## 对比：重构前 vs 重构后

| 维度 | 重构前 | 重构后 |
|------|--------|--------|
| 路由逻辑 | 改 `ClaudeTaskController.isCodexBackend()` | 不改 |
| Controller | 加分支或新 Controller | 不改 |
| OpenAPI | 改 `OpenApiController` 注入 | 不改 |
| SSE/事件 | 手写 payload Map | 用 `AgentMessageBuilder` |
| 前端 | 加 API + 改调用 | 不改（统一走 `/api/v1/tasks`） |
| 会话管理 | 无绑定 | 自动绑定 + 防漂移 |
| 任务查询 | 各 Controller 各查各的 | `TaskQueryProvider` 自动聚合 |
| **总改动点** | **5-8 处** | **1 个新模块（2 个接口 + @Component）** |
