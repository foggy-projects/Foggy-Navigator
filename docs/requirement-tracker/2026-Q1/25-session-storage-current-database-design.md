# Session 存储统一化重构：旧数据库设计

## 1. 目标

本文档记录当前会话相关表的职责、关键字段与问题点，供后续：

- 编写迁移脚本
- 对照新模型做字段映射
- 编写重构测试与回填校验

---

## 2. 当前核心表

### 2.1 `sessions`

来源：

- [SessionEntity.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/navigator-common/src/main/java/com/foggy/navigator/common/entity/SessionEntity.java)

当前职责：

- 平台会话主身份
- Session 与 Agent/Provider 绑定
- 会话标题与摘要

关键字段：

| 字段 | 含义 |
|------|------|
| `id` | 平台会话 ID |
| `userId` | 用户 ID |
| `tenantId` | 租户 ID |
| `agentId` | 已绑定的 Agent |
| `providerType` | `claude-worker` / `codex-worker` |
| `bindingSource` | 绑定来源 |
| `parentSessionId` | 父会话 |
| `title` | 会话标题 |
| `summary` | 会话摘要 |
| `status` | 生命周期状态 |
| `participatingAgentIds` | 参与 Agent 列表 JSON |
| `createdAt` / `updatedAt` | 时间戳 |

当前问题：

1. 不包含 pin、tags、auth、interactionState。
2. 不包含 provider 级续聊状态。
3. 不包含会话最新任务投影。
4. 历史页无法只靠这张表完成查询。

### 2.2 `session_messages`

来源：

- [SessionMessageEntity.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/navigator-common/src/main/java/com/foggy/navigator/common/entity/SessionMessageEntity.java)

当前职责：

- 平台通用消息存储

关键字段：

| 字段 | 含义 |
|------|------|
| `id` | 消息 ID |
| `sessionId` | 所属会话 |
| `role` | USER / ASSISTANT / SYSTEM |
| `content` | 消息正文 |
| `metadata` | JSON 元数据 |
| `createdAt` | 创建时间 |

结论：

- 本表结构已经足够通用
- 本次不作为重点重构对象

### 2.3 `claude_tasks`

来源：

- [ClaudeTaskEntity.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/model/entity/ClaudeTaskEntity.java)

当前职责：

- Claude 会话中的任务流水
- Claude 会话恢复、checkpoint、目录历史等主查询表

关键字段：

| 字段 | 含义 |
|------|------|
| `taskId` | 平台任务 ID |
| `workerTaskId` | upstream worker task ID |
| `sessionId` | 平台 session ID |
| `workerId` | Worker ID |
| `userId` | 用户 ID |
| `prompt` | 提示词 |
| `cwd` | 工作目录 |
| `directoryId` | 目录 ID |
| `status` | 任务状态 |
| `claudeSessionId` | Claude 本地会话 ID |
| `costUsd` / `inputTokens` / `outputTokens` | 成本与 Token |
| `durationMs` / `numTurns` | 执行统计 |
| `model` | 模型名 |
| `errorMessage` / `resultText` | 结果 |
| `contextId` | A2A context |
| `dedupKey` | 幂等去重 |
| `checkpoints` | checkpoint JSON |
| `fileCheckpointingEnabled` | 文件 checkpoint 开关 |
| `source` | PLATFORM / SYNCED |
| `agentTeamsConfigId` | AgentTeams 配置 |
| `lastAckedSeq` / `lastAliveAt` | relay 状态 |

当前问题：

1. 和 Codex 表重复度高。
2. 历史查询逻辑大量和 Provider 强绑定。
3. 一部分字段属于任务级特殊状态，不适合作为单独主表存在的理由。

### 2.4 `claude_conversation_configs`

来源：

- [ConversationConfigEntity.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/model/entity/ConversationConfigEntity.java)

当前职责：

- Claude 会话 UI 配置
- 会话级 Auth 绑定
- interactionState

关键字段：

| 字段 | 含义 |
|------|------|
| `sessionId` | 对应平台 session |
| `workerId` | Worker ID |
| `userId` | 用户 ID |
| `pinned` / `pinnedAt` | 置顶 |
| `customTitle` | 用户自定义标题 |
| `authMode` | SUBSCRIPTION / API_KEY / CUSTOM_ENDPOINT |
| `authToken` | 加密后的 token |
| `baseUrl` | 自定义地址 |
| `authBoundAt` | 绑定时间 |
| `tags` | 标签 JSON |
| `interactionState` | PROCESSING / AWAITING_REPLY / ON_HOLD / ARCHIVED |
| `agentTeamsConfigId` | 会话绑定的 AgentTeams |

当前问题：

1. 会话配置表只覆盖 Claude 路径，Codex 没有对等模型。
2. 会话主信息与会话配置分裂。
3. 历史列表查询必须 join 或二次补齐。
4. 后续如果继续接第三种 Provider，会继续复制一张类似表。

### 2.5 `codex_tasks`

来源：

- [CodexTaskEntity.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/model/entity/CodexTaskEntity.java)

当前职责：

- Codex 任务流水

关键字段：

| 字段 | 含义 |
|------|------|
| `taskId` | 平台任务 ID |
| `workerTaskId` | upstream worker task ID |
| `sessionId` | 平台 session ID |
| `directoryId` | 目录 ID |
| `workerId` | Worker ID |
| `userId` / `tenantId` | 用户 / 租户 |
| `prompt` / `cwd` | 输入 |
| `status` | 任务状态 |
| `codexThreadId` | Codex thread ID |
| `model` | 模型名 |
| `costUsd` / `inputTokens` / `outputTokens` | 成本与 Token |
| `durationMs` / `numTurns` | 统计 |
| `resultText` / `errorMessage` | 结果 |
| `lastAckedSeq` / `source` / `lastAliveAt` | relay 状态 |

当前问题：

1. 与 `claude_tasks` 的公共字段重复度很高。
2. 早期没有完整会话配置模型，导致很多前端能力不对齐。
3. 历史分页最初没有被统一入口聚合，说明表分裂直接推高了查询复杂度。

### 2.6 `deleted_claude_sessions`

来源：

- [DeletedClaudeSessionEntity.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/model/entity/DeletedClaudeSessionEntity.java)

当前职责：

- 防止已删除的同步 Claude 会话再次被导入

关键字段：

| 字段 | 含义 |
|------|------|
| `claudeSessionId` | upstream Claude 会话 ID |
| `workerId` | Worker ID |
| `userId` | 用户 ID |
| `deletedAt` | 删除时间 |

当前问题：

1. 它是物理删除语义的补丁表。
2. 强耦合 Claude。
3. 不是稳定目标模型的一部分。

---

## 3. 当前查询路径的问题

### 3.1 历史列表

当前历史列表至少涉及：

- `claude_tasks`
- `codex_tasks`
- `claude_conversation_configs`
- `sessions`

因此必须在 Service 或 Facade 层做拼装。

### 3.2 搜索

当前搜索能力天然偏向 Claude，因为：

- 标题、标签、interactionState 在 Claude 专属表
- Codex 没有对等会话配置模型

### 3.3 Auth

当前会话级 Auth 绑定逻辑几乎都依赖：

- `ConversationConfigService`

这意味着会话 auth 不是平台一级能力。

---

## 4. 旧模型总结

当前数据库模型的根本问题不是“字段不够”，而是“语义分层不正确”：

1. `sessions` 太薄
2. `ConversationConfigEntity` 承担了本应属于会话主表的职责
3. `claude_tasks` / `codex_tasks` 将 Provider 差异放大为主表差异
4. UI 级历史、搜索、统计被迫依赖跨表聚合

因此目标不是继续给旧表打补丁，而是切换到新的聚合根设计。

