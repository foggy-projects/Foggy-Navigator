# Chat Session History and Client Context

- doc_type: requirement-design-implementation
- source_issue: https://github.com/foggy-projects/Foggy-Navigator/issues/106
- version: 1.1.3-SNAPSHOT
- status: implemented
- owner_scope: session-module | claude-worker-agent-openapi | navigator-chat-widget | navigator-open-sdk

## 背景

TMS 已通过 `@foggy/navigator-chat-widget` 接入 Navigator 助手。下一步需要在上游业务系统内展示历史会话列表，点击后回放对应 `contextId` 的历史消息，并继续沿用同一个上下文发送后续问题。

平台侧需要提供完整能力：OpenAPI、OpenSDK 和 Widget 均支持会话列表、历史消息读取、会话加载与继续对话。上游可以选择直接使用这些能力；如果上游自行实现 UI 或缓存策略，属于上游实现边界。

## 平台能力边界

1. OpenAPI 保留并完善以下端点：
   - `GET /api/v1/open/agents/{agentId}/sessions`
   - `GET /api/v1/open/agents/{agentId}/sessions/{contextId}/messages`
   - `POST /api/v1/open/agents/{agentId}/ask`
2. OpenSDK 暴露会话列表、历史消息读取，以及带 Client App runtime token 的对应方法。
3. Navigator Upstream CLI 支持：
   - `ask --context-id <contextId>` 继续会话
   - `ask --client-context-json <json>` 或 `ask --client-context-file <path>` 保存上游会话扩展数据
   - `sessions` 查询会话列表
   - `session-messages --context-id <contextId>` 查询历史消息
4. Widget 暴露：
   - `listSessions(options)`
   - `getSessionMessages(contextId, options)`
   - `loadSession(contextId, options)`
   - `send(content, options)` 继续复用当前 `contextId`
5. `clear()` 保持新会话语义：清空当前渲染状态并释放当前 `contextId`。
6. 历史 structured output 和 action 只负责展示；Widget 不自动触发副作用。用户点击 action 时继续沿用现有 `@action` 事件。

## Client Context 契约

创建或继续会话时，`POST /ask` 支持顶层字段：

```json
{
  "message": "查询订单状态",
  "contextId": "ctx-optional",
  "clientContext": {
    "upstreamConversationId": "tms-ai-10001",
    "bizObjectType": "order",
    "bizObjectId": "SO-10001"
  }
}
```

约束：

1. `clientContext` 是 Client App 提供的透明 JSON 对象，用于上游保存自身需要的会话扩展数据。
2. `clientContext` 只持久化到 Navigator 会话摘要，不作为 worker metadata 注入，不进入 LLM prompt。
3. 同一 `contextId` 再次传入 `clientContext` 时，平台用新对象覆盖旧对象；不传时保持已有值。
4. 会话列表响应返回该字段，便于上游把 Navigator 会话与自身业务对象、页面状态或外部会话 ID 对齐。
5. `metadata` 仍用于 Agent 执行链路参数，不应用来保存上游 UI 私有状态。

## 分页与运行中任务策略

1. 会话列表按 `updatedAt/lastAccessedAt` 倒序返回；`nextCursor` 使用上一页最后一个 `contextId`。
2. 历史消息按创建时间升序返回；`nextCursor` 使用上一页最后一个 `messageId`。
3. `limit` 服务端限制：
   - sessions: `1..100`
   - messages: `1..200`
4. `loadSession(contextId)` 默认加载首屏历史消息，返回原始分页对象；上游需要完整历史时可继续调用 `getSessionMessages` 拉取后续页。
5. 运行中任务不在历史加载时自动恢复轮询。上游可通过已有 task 端点或 active task 列表自行决定是否提示“有任务运行中”。

## 上游职责

1. 上游 BFF 负责保存并注入 Client App runtime token，浏览器不持有 Navigator token。
2. 上游 BFF 可以代理 OpenAPI，并过滤不希望暴露给浏览器的字段。
3. 上游前端可以使用 Widget 暴露的 `loadSession`，也可以自建历史会话列表 UI。

## 非目标

1. 不实现会话删除、重命名、置顶、归档和批量管理。
2. 不自动执行历史 action 或 structured output 中的副作用。
3. 不要求 Navigator 替上游维护业务对象状态机。

## 实施清单

- [x] 文档记录平台能力边界、`clientContext` 语义和分页策略。
- [x] 后端持久化 `clientContext` 并在会话列表摘要中返回。
- [x] 后端修正 session list cursor 分页。
- [x] Java OpenSDK 支持 ask 时携带 `clientContext`。
- [x] Navigator Upstream CLI 支持 ask 时携带 `clientContext`，并支持会话列表与历史消息 smoke。
- [x] Widget API 增加 `listSessions`、`getSessionMessages`、`loadSession`。
- [x] Widget 历史消息回放支持 user、assistant、action 展示，并保证后续 `send()` 复用同一 `contextId`。
- [x] 补充后端与 Widget 针对性测试。

## 验收标准

1. 创建会话时传入 `clientContext` 后，会话列表摘要可读回同一 JSON 对象。
2. 会话列表分页传入 `cursor` 后返回下一页，不重复第一页。
3. Widget `loadSession(contextId)` 能回放历史 user/assistant 消息。
4. `loadSession(contextId)` 后继续 `send()`，请求体包含同一个 `contextId`。
5. 历史 action 展示但不自动触发；点击后仍通过现有 `@action` 事件交给上游。
6. `clear()` 清空当前会话状态，下一次发送创建新会话。
