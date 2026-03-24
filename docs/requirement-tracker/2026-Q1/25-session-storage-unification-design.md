# Session 存储统一化重构设计

> 目标：将当前分散在 `SessionEntity`、`ConversationConfigEntity`、`ClaudeTaskEntity`、`CodexTaskEntity` 中的会话与任务存储模型收敛为：
>
> - 一张会话主表：`sessions`
> - 一张统一任务表：`session_tasks`
> - 继续保留通用消息表：`session_messages`

## 1. 背景

当前系统的会话相关数据被拆散在多张表中：

- 平台公共会话头：`sessions`
- 平台公共消息：`session_messages`
- Claude 任务：`claude_tasks`
- Claude 会话配置：`claude_conversation_configs`
- Claude 删除同步会话标记：`deleted_claude_sessions`
- Codex 任务：`codex_tasks`

这导致几个问题：

1. 会话主身份存在，但不是真正的聚合根。
2. 历史列表、搜索、统计需要跨 Provider 聚合。
3. Pin、标题、标签、Auth 绑定、InteractionState 等能力只在 Claude 路径完整。
4. Claude 和 Codex 的任务明细表大量重复，前端与 Facade 层被迫做适配。

本次设计的核心目标不是兼容旧模型继续演进，而是定义新的、稳定的目标模型，为后续代码重构和迁移脚本提供依据。

---

## 2. 核心设计决策

### 2.1 SessionEntity 升格为真正的会话聚合根

`SessionEntity` 不再只是“会话 ID + 绑定外壳”，而是统一承载：

- 会话主身份
- 会话 UI 状态
- 会话级 Auth 绑定
- 会话级 Provider 状态
- 最新任务投影

结论：

- `ConversationConfigEntity` 将被移除
- 其通用能力全部合并进 `sessions`

### 2.2 ClaudeTask 与 CodexTask 合并为 SessionTask

`claude_tasks` 与 `codex_tasks` 的公共字段高度重合，后续统一为 `session_tasks`。

公共字段直接列化保存：

- `taskId / sessionId / workerId / directoryId / prompt / cwd`
- `providerType / providerTaskId / status / model`
- `tokens / cost / result / error`
- `createdAt / updatedAt / lastAliveAt`

Provider 特有的任务状态保存在 `task_state_json`：

- Claude：`checkpoints`、`fileCheckpointingEnabled`、`agentTeamsConfigId`、`contextId`、`dedupKey`
- Codex：当前几乎无额外任务级字段，但保留扩展位

### 2.3 Provider 特有的会话状态统一放入 SessionEntity 的 JSON

本次不新增 `SessionProviderStateEntity`。

结论：

- Provider 特有的“会话级”状态统一进入 `sessions.provider_state_json`
- 由 `provider_type` 决定 JSON 的解释方式

示例：

- Claude 会话：`claudeSessionId`
- Codex 会话：`codexThreadId`
- 未来可继续扩展为其他 Provider 的 resume cursor / upstream refs

### 2.4 公共高频查询字段仍保留为普通列

虽然 JSON + 虚拟列可用，但以下字段是高频筛选或排序路径，直接保留为列：

- `title`
- `status`
- `interaction_state`
- `pinned`
- `pinned_at`
- `last_activity_at`
- `latest_task_id`
- `current_worker_id`
- `current_directory_id`

这样可以保证：

- 前端历史列表查询简单
- 数据库索引直接可用
- 不依赖 JSON path 查询作为主路径

### 2.5 Tags 用 JSON，Auth 绑定用结构化列

`tags` 天然是数组，使用 JSON 列更合适。  
`auth` 是安全敏感数据，并且存在加密、脱敏、覆盖等逻辑，建议保留结构化列：

- `auth_mode`
- `auth_bound_at`
- `auth_model_config_id`
- `auth_base_url`
- `auth_token_ciphertext`

理由：

- 继续复用现有 `CredentialEncryptor` 逻辑
- 减少 auth 读写时对 JSON patch 的依赖
- 迁移脚本和服务层逻辑更简单

### 2.6 Session 删除语义改为软删除优先

当前 `deleted_claude_sessions` 的存在，本质上是在弥补“物理删除后丢失 tombstone”的问题。

目标设计建议：

- `sessions.status` 扩展为包含 `DELETED`
- 增加 `deleted_at`
- 默认删除操作改为软删除

这样可以避免继续维护 Provider 专属 tombstone 表。

若实施阶段暂时不改删除语义，则 `deleted_claude_sessions` 可以保留为过渡表，但不属于目标模型。

### 2.7 session_messages 继续保留

消息模型本次不重构，仍然保留：

- `sessions`
- `session_messages`

原因：

- 其结构已经是平台公共层
- 不与 Claude/Codex 强绑定
- 本次主要矛盾是“任务与会话配置碎片化”，不是消息存储本身

### 2.8 agent_tasks 不纳入本次统一

`agent_tasks` 仍然保留其“跨 Agent 委派任务”语义，不与 `session_tasks` 合并。

原因：

- `agent_tasks` 的 `parentSessionId / sourceAgentId / targetAgentId / taskType`
  代表的是任务编排语义
- `session_tasks` 代表的是用户主会话中的实际运行任务流水

两者是不同子域。

---

## 3. 目标表

本次目标模型固定为三张核心表：

1. `sessions`
2. `session_messages`
3. `session_tasks`

其中：

- `sessions` 是聚合根
- `session_tasks` 是会话内任务流水
- `session_messages` 是会话消息流

详细表结构见：

- [旧数据库设计](./25-session-storage-current-database-design.md)
- [目标数据库设计](./25-session-storage-target-database-design.md)
- [迁移约束与脚本说明](./25-session-storage-migration-guide.md)

---

## 4. 服务层重构边界

### 4.1 保留

- `JpaSessionManager`
- `SessionBindingService`
- `TaskDispatchFacade`

### 4.2 删除或吸收

- `ConversationConfigService` 的职责并入新的 `SessionMetadataService`
- `ConversationConfigEntity / ConversationConfigRepository` 删除
- `ClaudeTaskRepository / CodexTaskRepository` 的历史查询职责迁移到 `SessionTaskRepository`

### 4.3 新增建议

- `SessionMetadataService`
  - 维护 `pin / title / tags / auth / interactionState`
- `SessionTaskService`
  - 维护 `session_tasks`
  - 提供统一分页、搜索、目录过滤、状态聚合

---

## 5. 实施顺序

### Phase 1: 建表与双写

1. 扩展 `sessions`
2. 新建 `session_tasks`
3. Claude / Codex 创建任务时双写新表
4. Claude 会话配置写 `sessions`

### Phase 2: 读路径切换

1. 历史列表改读 `sessions + session_tasks`
2. 搜索改读 `sessions + session_tasks`
3. Pin / 标题 / 标签 / Auth / InteractionState 改写 `sessions`

### Phase 3: 删旧表

1. 删除 `claude_conversation_configs`
2. 删除 `claude_tasks`
3. 删除 `codex_tasks`
4. 视删除语义改造结果，删除 `deleted_claude_sessions`

---

## 6. 本文档对后续实现的约束

1. 不再新增新的 Provider 专属会话配置表。
2. 不再新增新的 Provider 专属任务主表。
3. Provider 会话级特殊字段统一进入 `sessions.provider_state_json`。
4. Provider 任务级特殊字段统一进入 `session_tasks.task_state_json`。
5. 历史列表与搜索能力必须以 `sessions + session_tasks` 为主查询路径。
6. 迁移脚本按“旧表 -> 新表”一次性回填设计，不要求运行期双模型长期共存。

