# 上游 Agent 接入执行进度

## 文档作用

- doc_type: progress
- intended_for: root-controller | reviewer
- purpose: 记录 1.0.3-SNAPSHOT 上游接入首版的执行进度与实际实现情况

## Version

- `1.0.3-SNAPSHOT`

## Status

- In Progress
- 2026-04-16

## 执行进度

### Step 1: 落地 Open API 合同 ✅

**完成内容：**

1. **状态枚举对齐**：`WORKING → RUNNING`、`CANCELED → CANCELLED`、`INPUT_REQUIRED → AWAITING_INPUT`
   - 更新了 `mapTaskStatus()`（内部状态映射）和新增 `mapA2aState()`（A2A 枚举映射）
2. **cancelTask 返回结构调整**：从 `RX<String>` 改为 `RX<OpenApiTaskDTO>`，返回取消后的任务状态
3. **ask 请求字段**：新增 `message` 字段（合同正式字段），保留 `question` 兼容，`resolveMessage()` 优先取 `message`
4. **新增 OpenApiTaskDTO.updatedAt 字段**
5. **新增 3 个端点**：
   - `GET /agents/{agentId}/tasks/{taskId}/messages` — 任务增量消息
   - `GET /agents/{agentId}/sessions` — 会话上下文列表
   - `GET /agents/{agentId}/sessions/{contextId}/messages` — 会话消息列表

**新增文件：**
- `OpenSessionSummaryDTO.java`
- `OpenSessionMessageDTO.java`
- `OpenTaskMessagesResponse.java`
- `OpenSessionListResponse.java`
- `OpenSessionMessagesResponse.java`

**修改文件：**
- `OpenApiController.java` — 修改 3 个现有方法 + 新增 3 个端点 + 6 个辅助方法
- `OpenApiTaskDTO.java` — 新增 updatedAt、状态注释更新
- `OpenApiQueryForm.java` — 新增 message/metadata 字段 + resolveMessage()

### Step 2: 落地 contextId 读模型 ✅

**完成内容：**

1. `AgentConversationContextRepository` 新增查询方法：
   - `findByUserIdAndTargetAgentIdOrderByLastAccessedAtDesc` — 会话列表
   - `findByContextIdAndTargetAgentId` — 直接查找
   - `findByNavigatorSessionId` — sessionId → contextId 反查
2. `OpenApiSessionQueryService`（新建）提供完整的 contextId 读模型：
   - `resolveSessionId(contextId, userId)` — 正向映射
   - `resolveContextId(sessionId)` — 反向映射
   - `listSessions(userId, agentId, limit)` — 列表
   - `findContext(contextId, agentId)` — 查找

**关键设计决策：**
- contextId → sessionId 通过 `AgentConversationContextEntity.navigatorSessionId` 映射
- 映射在 `saveSessionRefFull` 时建立，不允许改绑（由 `AgentContextStoreImpl` 保证）

### Step 3: 落地 taskId + cursor 增量轮询 ✅

**完成内容：**

1. **数据模型扩展**：
   - `SessionMessageEntity` 新增 `taskId` 列 + 索引 `idx_msg_task_id`
   - `AgentMessage` 新增 `taskId` 字段
   - `Message`（framework POJO）新增 `taskId` 字段
2. **消息持久化链路打通**：
   - `AgentMessageBuilder.build()` 将 `taskId` 设置到 `AgentMessage` 对象级字段
   - `WorkerStreamRelay.publishMessage()` 从 payload 提取 `taskId` 设置到消息对象
   - `SessionEventListener.toSessionMessage()` 传递 `taskId` 到 `Message`
   - `JpaSessionManager.addMessage()` 持久化 `taskId` 到 `SessionMessageEntity`
3. **cursor 轮询实现**：
   - cursor = 上一页最后一条消息的 ID
   - 服务端通过 cursor ID 查找其 `createdAt`，用 `createdAt >` 查询增量
   - 保证幂等：相同 cursor 重复调用结果稳定
4. **分页语义**：
   - 多取 1 条判断 `hasMore`
   - 空结果也返回 taskId/contextId

**与规划偏差：**
- cursor 编码方式：使用消息 ID 作为 cursor（而非单独编码），简单且幂等

### Step 4: 落地 Java SDK ✅

**完成内容：**

1. **新增 SDK Model 类**：
   - `SessionSummary` — 会话摘要
   - `SessionMessage` — 消息
   - `TaskMessagesPage` — 任务消息分页
   - `SessionListPage` — 会话列表分页
   - `SessionMessagesPage` — 会话消息分页
2. **AgentApi 新增方法**：
   - `listSessions(agentId, limit, cursor)` — 会话列表
   - `getSessionMessages(agentId, contextId, limit, cursor)` — 会话消息
   - `getTaskMessages(agentId, taskId, limit, cursor)` — 任务增量消息
   - `pollTaskMessages(agentId, taskId, timeout, pollInterval)` — 便捷轮询方法
3. **兼容性更新**：
   - `ask()` 同时发送 `message` + `question` 字段
   - `AgentTask.isTerminal()` 兼容 `CANCELLED` 和 `CANCELED`

### Step 5: 落地 Java Demo ✅

**完成内容：**

- `UpstreamIntegrationDemo.java` — 可运行的完整接入示例
- 覆盖主流程：配置 → ask → 轮询状态 + 增量消息 → 回放会话 → 查看会话列表
- 使用环境变量配置 API Key 和 agentId，不依赖内部调试脚本

### 测试 ✅

**OpenApiSessionQueryServiceTest（12 项全通过）：**

| 测试 | 说明 |
|------|------|
| resolveSessionId_shouldMapContextIdToSessionId | contextId → sessionId 正向映射 |
| resolveSessionId_shouldReturnEmptyForUnknownContext | 未知 context 返回空 |
| resolveContextId_shouldReverseMapSessionToContext | sessionId → contextId 反向映射 |
| listSessions_shouldReturnContextsByUserAndAgent | 会话列表按 userId + agentId |
| listSessions_shouldReturnEmptyForDifferentAgent | 不同 agent 返回空 |
| getSessionMessages_shouldReturnMessagesInOrder | 消息升序返回 |
| getSessionMessages_cursorShouldSkipPreviousMessages | cursor 跳过已返回消息 |
| getTaskMessages_shouldFilterByTaskId | 按 taskId 过滤 |
| getTaskMessages_cursorShouldReturnIncrementalMessages | taskId + cursor 增量 |
| getTaskMessages_sameCursorShouldBeIdempotent | 同 cursor 幂等 |
| getTaskMessages_shouldReturnEmptyForNoMessages | 空任务返回空 |
| contextToSession_shouldBeOneToOneStable | 映射一对一稳定 |

**全量回归测试：** BUILD SUCCESS，所有既有测试通过

## 待后续版本处理

1. 历史数据中无 `taskId` 的消息无法通过 `getTaskMessages` 查询（预期行为，首版只保证新数据）
2. `contextId → sessionId` 反查依赖 `navigatorSessionId` 字段，历史无此字段的 context 不在首版范围
3. cursor 当前使用消息 ID 作为不透明游标，后续如需优化可切换为 Base64 编码
4. 前端页面 Demo、SSE 对外开放、安全矩阵细化均不在本次范围
