# Provider 内部状态封装 — 从公共接口撤回 claudeSessionId / codexThreadId

> 目标：A2A 抽象边界只认 `sessionId`（平台会话）+ `contextId`（多轮上下文），
> Provider 特定的会话标识（`claudeSessionId`、`codexThreadId`）由 Provider 内部管理，
> 不再在公共接口和前端之间透传。

## 1. 背景

当前 `claudeSessionId` 和 `codexThreadId` 泄漏到了整个链路：

```
后端创建任务 → DispatchTaskDTO.claudeSessionId 返回给前端
前端存在 task/conversation 对象 → resume 时原样传回后端
后端 TaskDispatchRequest.claudeSessionId → toCommonParams → Provider 接收
```

前端本身 **不理解也不使用** 这些字段的值——只是做了透传。
这导致每新增一种 Worker 类型都要在公共接口上加 provider-specific 字段，违反开闭原则。

### 泄漏范围

| 层 | 文件/字段 | 泄漏形式 |
|---|---------|---------|
| SPI | `ClaudeWorkerFacade.syncQuery(claudeSessionId)` | 方法参数 |
| SPI | `TaskQueryProvider` 注释 | javadoc 引用 |
| SPI | `AgentContextStore` 注释 | javadoc 引用 |
| Facade | `TaskDispatchRequest.claudeSessionId` / `.codexThreadId` | 字段 |
| Facade | `toCommonParams()` 透传 | 参数组装 |
| DTO | `DispatchTaskDTO.claudeSessionId` / `.codexThreadId` | 响应字段 |
| 前端 | 11 个文件、~50 处引用 | resume 透传 + UI 展示 |

## 2. 目标设计

### 2.1 外部（公共接口）只认两个概念

| 概念 | 含义 | 谁维护 |
|-----|------|--------|
| `sessionId` | 平台会话（Session ↔ Agent 绑定） | Facade / SessionBindingService |
| `contextId` | 多轮对话上下文（A2A /ask 端点用） | AgentContextStore |

### 2.2 内部（Provider 封装）

| 概念 | 含义 | 谁维护 | 存储位置 |
|-----|------|--------|---------|
| `claudeSessionId` | Claude CLI 会话 | ClaudeWorkerAgent / ClaudeTaskService | `SessionEntity.providerStateJson` |
| `codexThreadId` | Codex thread | CodexWorkerAgent / CodexTaskService | `SessionEntity.providerStateJson` |

### 2.3 Resume 流程变化

**Before（前端透传）：**
```
前端 resume → POST /tasks/resume { sessionId, claudeSessionId, prompt }
后端: request.claudeSessionId → Provider
```

**After（后端自行恢复）：**
```
前端 resume → POST /tasks/resume { sessionId, prompt }
后端: sessionId → SessionEntity.providerStateJson → 取出 claudeSessionId → Provider
```

## 3. 变更清单

### 3.1 后端

#### TaskDispatchRequest
- 标记 `claudeSessionId` 和 `codexThreadId` 为 `@Deprecated`（保留字段，向后兼容 Open API）
- resume 路径优先从 `SessionEntity.providerStateJson` 恢复，request 中的值作为 fallback

#### SessionEntity.providerStateJson
- 已有字段（VARCHAR TEXT），当前使用情况需确认
- 存储格式：`{"claudeSessionId": "xxx"}` 或 `{"codexThreadId": "xxx"}`

#### ClaudeTaskService / CodexTaskService
- 创建任务后，将 provider-specific 会话 ID 写入 `SessionEntity.providerStateJson`
- resume 时从 `providerStateJson` 读取，不依赖 request 参数

#### ClaudeWorkerFacade.syncQuery()
- 参数 `claudeSessionId` → 改为 `contextId`，内部通过 AgentContextStore 映射

#### toCommonParams()
- 不再包含 `claudeSessionId` / `codexThreadId`（当 @Deprecated 字段最终移除时）
- 过渡期：仍透传但标记 deprecated

#### DispatchTaskDTO
- `claudeSessionId` / `codexThreadId` 保留（前端展示用，如调试面板）
- 但前端不应再作为 resume 的输入参数使用

### 3.2 前端

#### resume 调用点（ClaudeWorkerView.vue 3 处）
- 删除 `resumeForm.claudeSessionId = oldTask.claudeSessionId`
- 删除 `resumeForm.codexThreadId = oldTask.codexThreadId`
- 只传 `sessionId`，后端自行恢复

#### useClaudeWorker.ts / unifiedTask.ts
- resumeTask 类型中移除 `claudeSessionId` / `codexThreadId`

#### ConversationGroup 类型
- `claudeSessionId` / `codexThreadId` 保留（UI 展示），但不用于 resume 参数构建

#### getTaskResumeRef() / getConversationResumeRef()
- 这些函数用于判断会话是否可以 resume —— 改为检查 `sessionId` 是否存在即可

## 4. 迁移策略

**一步到位**：不兼容旧数据（无 `providerStateJson` 的旧会话直接在后台删除）。

1. 后端 resume 只从 `providerStateJson` 恢复，不读 `request.claudeSessionId`
2. 前端 resume 不再传 `claudeSessionId` / `codexThreadId`
3. `TaskDispatchRequest` 直接删除这两个字段
4. `toCommonParams()` 移除对应行

## 5. 扩展性收益

新增 Worker 类型（如未来的 Gemini Worker）时：
- 不需要在 `TaskDispatchRequest` 加新字段（如 `geminiSessionId`）
- 不需要在前端 resume 逻辑中加透传
- 只需要 Provider 内部管理自己的 `providerStateJson`

## 6. 风险点

| 风险 | 缓解 |
|------|------|
| 旧会话没有 `providerStateJson` | 直接删除旧会话，不兼容 |
| Open API 调用方可能依赖 `claudeSessionId` 参数 | Breaking change，Open API 也改用 sessionId |
| `providerStateJson` 当前值 | 已确认为 `{"claudeSessionId":"..."}` 或空，可直接使用 |
