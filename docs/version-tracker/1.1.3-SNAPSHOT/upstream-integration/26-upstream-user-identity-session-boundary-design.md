# Upstream User Identity and Session Boundary Design

## 文档作用

- doc_type: requirement-design-implementation-plan
- version: 1.1.3-SNAPSHOT
- status: draft
- date: 2026-05-13
- priority: P0
- source_type: architecture-clarification
- intended_for: navigator-owner | business-agent-owner | session-owner | upstream-llm-coding-agent | reviewer
- purpose: 明确 `upstreamUserId` 独立于 Navigator `userId` 的身份模型、会话归属边界与后续 ClientApp-scoped 控制面凭证实施前置条件

## 背景

Navigator 上游接入已经形成两条身份线：

1. Navigator 内部用户身份：`userId`，用于平台登录、角色、API Key、会话列表、SSE、Worker 任务归属和审计。
2. 上游业务用户身份：`upstreamUserId`，用于某个 ClientApp 下的外部账号授权、业务函数访问、account context 和上游可见会话归属。

如果把 `upstreamUserId` 直接等价为 `userId`，短期可以复用现有 `SessionEntity.userId` 查询，但会引入命名空间冲突、安全边界混淆和未来上游用户登录 Navi 时的账号绑定困难。

因此本设计确认：`upstreamUserId` 必须作为独立身份维度存在。Navigator 需要为 Business Agent 提供一套 `tenantId + clientAppId + upstreamUserId/accountId` 维度的会话归属能力，而不是让上游用户复用 Navigator 内部 `userId`。

## 当前内部关系

### 身份与资源关系

| 概念 | 当前含义 | 归属边界 | 是否等价 |
| --- | --- | --- | --- |
| `tenantId` | Navigator 租户隔离边界 | 平台级 | 不等价于任何用户 |
| `userId` | Navigator 内部用户 ID | `tenantId` 下的平台用户 | 不等价于 `upstreamUserId` |
| `clientAppId` | 上游系统/项目接入主体 | `tenantId + clientAppId` | 不等价于用户 |
| `upstreamUserId` | 上游系统自己的用户或账号 ID | `tenantId + clientAppId + upstreamUserId` | 不等价于 `userId` |
| `accountId` | ClientApp 内的账号上下文 ID | `tenantId + clientAppId + accountId` | 当前可由 `upstreamUserId` 解析，但不是 Navigator `userId` |
| `navigatorEffectiveUserId` | Navigator 内部执行/审计身份 | 平台用户或受控服务身份 | 不代表上游业务用户 |

### 当前代码落点

| 模块 | 现状 | 影响 |
| --- | --- | --- |
| `navigator-common` / `SessionEntity` | 会话主表包含 `userId`、`tenantId`、`agentId` | 普通 Navi 会话按内部用户归属 |
| `session-module` / `SessionRepository` | 会话列表、pending session、标题/标签查询均按 `userId` 过滤 | 不能直接满足上游用户会话归属 |
| `session-module` / `AgentConversationContextEntity` | OpenAPI context 使用 `userId + targetAgentId` 建索引 | 现有 OpenAPI context 是内部用户视角 |
| `business-agent-module` / `BusinessAgentTaskEntity` | 已独立保存 `clientAppId`、`upstreamUserId`、`navigatorEffectiveUserId` | Business Agent 已具备分离身份的基础 |
| `addons/langgraph-biz-worker` | Business task 启动 Worker 时使用 `actorUserId` 创建/复用底层 session | 底层 session 的 `userId` 可能是控制面操作者，不应作为上游可见归属 |

## 设计决策

### 1. `upstreamUserId` 独立于 `userId`

平台内所有新设计必须遵循：

```text
Navigator userId != upstreamUserId
```

除非显式存在外部身份绑定记录，否则不得把上游传入的 `upstreamUserId` 当作 `UserEntity.id`、API Key owner、WorkingDirectory owner、UserMemory owner 或 SSE user key。

### 2. `accountId` 是 ClientApp 内账号上下文

首阶段约定：

```text
accountId = resolveAccountId(tenantId, clientAppId, upstreamUserId)
```

当前实现可先使用：

```text
accountId == upstreamUserId
```

但所有 API、DB 唯一键和审计都必须保留 `tenantId + clientAppId` 前缀，禁止让 `accountId` 变成全局 ID。

### 3. Business Agent 会话归属走独立读模型

新增 Business Agent 专用会话读模型，建议实体名：

```text
BusinessAgentSessionEntity
```

核心字段：

```text
tenantId
clientAppId
upstreamUserId
accountId
sessionId
contextId
skillId
agentId
latestTaskId
status
clientContextJson
createdAt
updatedAt
lastAccessedAt
```

推荐索引：

```text
tenantId + clientAppId + upstreamUserId + updatedAt
tenantId + clientAppId + upstreamUserId + contextId
tenantId + clientAppId + upstreamUserId + sessionId
tenantId + clientAppId + sessionId
```

业务含义：

- `SessionEntity` 继续服务 Navigator 内部会话、Worker 执行链路和现有 UI。
- `BusinessAgentSessionEntity` 服务上游可见会话列表、历史消息访问权限校验、client context 摘要和后续 upstream 登录 Navi 的归属桥接。
- 同一个底层 `sessionId` 只能在同一 `tenantId + clientAppId + upstreamUserId` 下暴露给上游用户。

### 4. 读取消息必须校验 Business Agent 会话归属

上游读取历史消息时，不能只凭 `sessionId` 或 `contextId` 查消息。必须先验证：

```text
runtime token -> tenantId + clientAppId
X-Upstream-User-Id -> upstreamUserId
session/context -> BusinessAgentSession(tenantId, clientAppId, upstreamUserId)
```

验证通过后，才允许读取 `SessionMessageEntity`。

### 5. `navigatorEffectiveUserId` 只用于执行和审计

在 ClientApp-scoped control-plane credential 方案中，控制面凭证不是上游用户。它只能解析出：

```text
tenantId
clientAppId
credentialId
scopes
effectiveNavigatorUserId
```

其中 `effectiveNavigatorUserId` 可来自 ClientApp owner、credential issuer 或专用 service user，用于现有 Worker/session 需要 `userId` 的内部链路。它不应出现在上游会话归属判断中。

### 6. 未来开放 upstreamUser 登录 Navi 使用身份绑定

如果未来允许某些上游用户登录 Navi，新增绑定表，而不是把 `upstreamUserId` 写成 `UserEntity.id`：

```text
NavigatorExternalIdentityLink
```

建议字段：

```text
tenantId
clientAppId
upstreamUserId
accountId
linkedNavigatorUserId
provider
status
createdAt
updatedAt
```

登录后的 Navi 用户通过绑定关系反查上游会话，不改变历史 `BusinessAgentSession` 的归属键。

## 对外能力边界

### 上游查询自己的会话

后续开放给上游的 API/SDK/CLI 必须使用当前 runtime token 和 `X-Upstream-User-Id` 解析身份：

```http
GET /api/v1/open/business-agent/sessions
GET /api/v1/open/business-agent/sessions/{contextId}/messages
```

约束：

1. 浏览器不直接持有 runtime token；上游 BFF 代理调用。
2. `upstreamUserId` 必须来自受控请求头或 BFF 侧认证上下文。
3. 服务端不得接受任意 `userId` 查询参数。
4. 返回的会话列表只包含当前 `tenantId + clientAppId + upstreamUserId` 下的会话。
5. `clientContextJson` 可以回显给上游 BFF/前端，但不得注入 LLM prompt。

### 继续会话

上游继续会话时，`contextId` 必须属于当前 upstream user：

```text
POST ask(contextId)
  -> check BusinessAgentSession ownership
  -> resolve sessionId
  -> create task with same tenant/clientApp/upstreamUser
```

不允许 ClientApp A 或 upstream user B 复用另一个账号的 `contextId`。

## 非目标

1. 不把现有 `SessionEntity.userId` 全面迁移为上游身份。
2. 不把 `upstreamUserId` 注册成 Navigator `UserEntity`。
3. 不在本阶段实现上游用户登录 Navi。
4. 不改变普通 Navigator UI 的会话列表归属逻辑。
5. 不开放浏览器直连 Worker Gateway 或持有 task scoped token。

## Module Responsibility

| Module | Responsibility |
| --- | --- |
| `business-agent-module` | 新增 Business Agent 会话读模型、归属校验、上游会话列表/消息查询服务、ask/continue 会话归属绑定 |
| `session-module` | 保持内部会话与消息存储；提供按 session/task 读取消息的底层能力，不负责上游身份决策 |
| `navigator-common` | 如读模型实体放公共模块，需要承载 entity/DTO；否则保持现状 |
| `addons/langgraph-biz-worker` | 继续使用内部 `userId` 执行 Worker 任务；上下文中保留 `upstreamUserId/accountId` |
| `navigator-open-sdk` | 后续封装 business-agent 会话列表、消息读取、继续会话 |
| `navigator-upstream-cli` | 后续命令从 business-agent 会话 API 读取上游自己的会话，而不是泛用 `userId` session API |
| `packages/foggy-chat` / widget | 如需展示 Business Agent 历史会话，使用新 API，不假设 `upstreamUserId == userId` |

## Code Inventory

```yaml
code_inventory:
  - repo: Foggy-Navigator
    path: business-agent-module/src/main/java/com/foggy/navigator/business/agent
    role: Business Agent 身份、task、session 归属控制面
    expected_change: update
    notes: 新增 BusinessAgentSession 读模型和 service，ask/continue/list/messages 均通过该模型校验 upstreamUserId

  - repo: Foggy-Navigator
    path: navigator-common/src/main/java/com/foggy/navigator/common/entity/SessionEntity.java
    role: Navigator 内部会话主表
    expected_change: read-only-analysis
    notes: 不把 upstreamUserId 写入 userId，不做全局迁移

  - repo: Foggy-Navigator
    path: session-module/src/main/java/com/foggy/navigator/session
    role: 会话消息持久化和内部查询能力
    expected_change: update
    notes: 如需要，补只读底层查询方法；不得把业务身份判断塞回通用 SessionRepository

  - repo: Foggy-Navigator
    path: addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker
    role: Business Agent Worker 执行后端
    expected_change: read-only-analysis
    notes: 维持内部 userId 执行语义，确认 context 中 upstreamUserId/accountId 不回归

  - repo: Foggy-Navigator
    path: navigator-open-sdk
    role: 上游 Java SDK
    expected_change: update
    notes: 后续封装 upstream session list/read/continue

  - repo: Foggy-Navigator
    path: tools/navigator-upstream-cli
    role: 上游本地 CLI
    expected_change: update
    notes: 后续 sessions/session-messages 改走 Business Agent upstream session API
```

## Implementation Plan

### Step 1 - Internal identity boundary

- 固化本文身份关系。
- 检查现有 business-agent create task、OpenAPI ask、CLI sessions 是否仍存在 `upstreamUserId == userId` 的隐性假设。
- 在 progress 中记录需要改造的 API 与测试清单。

### Step 2 - Business Agent session read model

- 新增 `BusinessAgentSessionEntity` 与 repository。
- 在创建 task 或首次 ask 时 upsert 会话归属。
- 继续会话时校验 `contextId/sessionId` 属于当前 `tenantId + clientAppId + upstreamUserId`。
- 保存 `clientContextJson`，供上游会话列表展示。

### Step 3 - Upstream session API

- 新增或调整 business-agent upstream session list/messages API。
- 所有读取入口必须通过 runtime token 和 upstream user grant 解析当前账号。
- 消息读取只在归属校验通过后访问底层 `SessionMessageEntity`。

### Step 4 - SDK / CLI / Skill

- SDK 增加上游会话列表、消息读取、继续会话 wrapper。
- CLI 的 `sessions`、`session-messages`、`ask --context-id` 改用新身份边界。
- `navigator-upstream-cli` skill 中补充：上游会话按 `upstreamUserId` 独立归属，不要求或暗示 `upstreamUserId == userId`。

### Step 5 - Future external login hook

- 本阶段只预留 `NavigatorExternalIdentityLink` 设计，不实现登录。
- 后续若开放 upstream user 登录 Navi，先实现身份绑定和可见会话查询，不迁移历史 session owner。

## Testing Plan

| Area | Required Tests |
| --- | --- |
| BusinessAgentSession repository/service | 同一 `upstreamUserId` 在不同 ClientApp 下不串会话；不同 `upstreamUserId` 不可读取彼此 session |
| ask continue | 使用他人 `contextId` 继续会话时 fail-closed |
| message read | `sessionId/contextId` 归属校验通过后才返回消息 |
| CLI/SDK | sessions/session-messages 不接受任意 `userId`，只使用当前 runtime token + upstream user |
| regression | 普通 Navigator `SessionEntity.userId` 会话列表不受影响 |

## 验收标准

1. 文档明确 `userId`、`upstreamUserId`、`accountId`、`navigatorEffectiveUserId` 的关系。
2. Business Agent 上游会话归属不依赖 `SessionEntity.userId`。
3. 任意上游历史会话读取均校验 `tenantId + clientAppId + upstreamUserId`。
4. CLI/SDK/Skill 不再指导上游把 `upstreamUserId` 当成 Navigator `userId`。
5. 为未来 upstream user 登录 Navi 预留 identity link 模型，不破坏历史数据。

## Progress Skeleton

| Item | Status | Notes |
| --- | --- | --- |
| 身份关系设计落档 | done | 本文新增 |
| code inventory | done | 初版列出核心模块 |
| BusinessAgentSession 读模型 | pending | 后续实现 |
| upstream session API | pending | 后续实现 |
| SDK/CLI/Skill 更新 | pending | 后续实现 |
| 测试覆盖 | pending | 后续实现 |
| 上游交付提示词 | pending | 能力稳定后再提供 |
