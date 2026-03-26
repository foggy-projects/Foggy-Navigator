# Session 存储统一化重构：迁移约束与脚本说明

## 1. 本文档作用

本文档不是运行时兼容方案，而是给后续迁移脚本、重构测试和数据回填使用的约束说明。

前提约束：

- 不要求长期保留新旧模型双读双写
- 可以先完成重构代码与测试，再编写一次性迁移脚本
- 迁移脚本以“旧表 -> 新表”的回填逻辑为主

---

## 2. 迁移对象

### 2.1 需要收敛的旧表

- `sessions`
- `session_messages`
- `claude_tasks`
- `claude_conversation_configs`
- `codex_tasks`
- `deleted_claude_sessions`（视删除语义是否切软删除）

### 2.2 保持独立的旧表

- `agent_tasks`

原因：

- 它属于跨 Agent 编排任务，不是会话内主任务流水

---

## 3. 字段映射规则

### 3.1 sessions <- sessions + claude_conversation_configs + 最新任务投影

| 新字段 | 旧来源 | 规则 |
|--------|--------|------|
| `id` | `sessions.id` | 直接映射 |
| `user_id` | `sessions.userId` | 直接映射 |
| `tenant_id` | `sessions.tenantId` | 直接映射 |
| `agent_id` | `sessions.agentId` | 直接映射 |
| `provider_type` | `sessions.providerType` | 直接映射 |
| `binding_source` | `sessions.bindingSource` | 直接映射 |
| `parent_session_id` | `sessions.parentSessionId` | 直接映射 |
| `title` | `claude_conversation_configs.customTitle` 或 `sessions.title` | 优先自定义标题，回退旧 title |
| `summary` | `sessions.summary` | 直接映射 |
| `status` | `sessions.status` | 直接映射；若引入软删除则脚本可转 `DELETED` |
| `interaction_state` | `claude_conversation_configs.interactionState` | Claude 会话直接映射；Codex 无配置时按最新任务状态推导 |
| `pinned` | `claude_conversation_configs.pinned` | 无记录则 false |
| `pinned_at` | `claude_conversation_configs.pinnedAt` | 直接映射 |
| `tags_json` | `claude_conversation_configs.tags` | 直接映射 |
| `auth_mode` | `claude_conversation_configs.authMode` | 直接映射 |
| `auth_bound_at` | `claude_conversation_configs.authBoundAt` | 直接映射 |
| `auth_base_url` | `claude_conversation_configs.baseUrl` | 直接映射 |
| `auth_token_ciphertext` | `claude_conversation_configs.authToken` | 直接映射，不解密 |
| `auth_model_config_id` | 旧模型无直接字段 | 初始可为空 |
| `current_worker_id` | 最新任务 `workerId` | 取最新任务 |
| `current_directory_id` | 最新任务 `directoryId` | 取最新任务 |
| `latest_task_id` | 最新任务 `taskId` | 取最新任务 |
| `latest_model` | 最新任务 `model` | 取最新任务 |
| `last_activity_at` | 最新任务 `updatedAt` 或 `sessions.updatedAt` | 取较新值 |
| `provider_state_json` | Claude/Codex 特有字段 | 见下文 |
| `participating_agent_ids_json` | `sessions.participatingAgentIds` | 字段重命名 |
| `deleted_at` | `deleted_claude_sessions.deletedAt` 或空 | 如启用软删除则写入 |
| `created_at` | `sessions.createdAt` | 直接映射 |
| `updated_at` | `sessions.updatedAt` | 直接映射 |

### 3.2 provider_state_json 映射

Claude 会话：

```json
{
  "claudeSessionId": "<latest claudeSessionId>"
}
```

Codex 会话：

```json
{
  "codexThreadId": "<latest codexThreadId>"
}
```

规则：

- 以该 session 最新任务中的 Provider 会话引用为准
- 若没有值，则写 `null` 或空 JSON

### 3.3 session_tasks <- claude_tasks + codex_tasks

| 新字段 | Claude 来源 | Codex 来源 | 规则 |
|--------|-------------|------------|------|
| `task_id` | `taskId` | `taskId` | 直接映射 |
| `session_id` | `sessionId` | `sessionId` | 直接映射 |
| `user_id` | `userId` | `userId` | 直接映射 |
| `tenant_id` | 空 | `tenantId` | Claude 可为空 |
| `agent_id` | 固定 `claude-worker` | 固定 `codex-worker` | 以 Provider 逻辑 Agent 写入 |
| `provider_type` | `claude-worker` | `codex-worker` | 固定值 |
| `provider_task_id` | `workerTaskId` | `workerTaskId` | 字段归一 |
| `worker_id` | `workerId` | `workerId` | 直接映射 |
| `directory_id` | `directoryId` | `directoryId` | 直接映射 |
| `prompt` | `prompt` | `prompt` | 直接映射 |
| `cwd` | `cwd` | `cwd` | 直接映射 |
| `status` | `status` | `status` | 直接映射 |
| `model` | `model` | `model` | 直接映射 |
| `source` | `source` | `source` | 直接映射 |
| `input_tokens` | `inputTokens` | `inputTokens` | 直接映射 |
| `output_tokens` | `outputTokens` | `outputTokens` | 直接映射 |
| `cost_usd` | `costUsd` | `costUsd` | 直接映射 |
| `duration_ms` | `durationMs` | `durationMs` | 直接映射 |
| `num_turns` | `numTurns` | `numTurns` | 直接映射 |
| `result_text` | `resultText` | `resultText` | 直接映射 |
| `error_message` | `errorMessage` | `errorMessage` | 直接映射 |
| `last_acked_seq` | `lastAckedSeq` | `lastAckedSeq` | 直接映射 |
| `last_alive_at` | `lastAliveAt` | `lastAliveAt` | 直接映射 |
| `task_state_json` | 见下文 | 见下文 | Provider 特有字段 |
| `created_at` | `createdAt` | `createdAt` | 直接映射 |
| `updated_at` | `updatedAt` | `updatedAt` | 直接映射 |

### 3.4 task_state_json 映射

Claude 任务：

```json
{
  "claudeSessionId": "...",
  "contextId": "...",
  "dedupKey": "...",
  "checkpoints": "...",
  "fileCheckpointingEnabled": true,
  "agentTeamsConfigId": "..."
}
```

Codex 任务：

```json
{
  "codexThreadId": "..."
}
```

---

## 4. 迁移顺序建议

### Step 1: 创建新结构

1. 扩展 `sessions`
2. 创建 `session_tasks`
3. 保持 `session_messages` 原样

### Step 2: 回填 session_tasks

1. 从 `claude_tasks` 导入全部记录
2. 从 `codex_tasks` 导入全部记录

### Step 3: 回填 sessions

1. 先从旧 `sessions` 复制基础字段
2. 再将 `claude_conversation_configs` 合并进来
3. 再根据 `session_tasks` 计算：
   - `latest_task_id`
   - `latest_model`
   - `current_worker_id`
   - `current_directory_id`
   - `last_activity_at`
   - `provider_state_json`

### Step 4: 校验

1. 校验每个旧 session 在新表中存在
2. 校验每个旧 Claude/Codex task 在 `session_tasks` 中存在
3. 校验会话最新任务投影正确
4. 校验 Claude 特有字段进入 JSON

### Step 5: 切换读路径

1. 历史列表改读 `sessions + session_tasks`
2. 搜索改读 `sessions + session_tasks`
3. 会话配置接口改读写 `sessions`

### Step 6: 删除旧表

1. 删除 `claude_conversation_configs`
2. 删除 `claude_tasks`
3. 删除 `codex_tasks`
4. 如果采用软删除，删除 `deleted_claude_sessions`

---

## 5. 测试脚本需要验证的断言

后续重构测试应至少覆盖：

### 5.1 Session 级断言

1. Claude 会话的 pin / tags / auth / interactionState 被正确迁入 `sessions`
2. Codex 会话在 `sessions` 中也能被历史列表检索
3. 最新任务投影字段正确
4. `provider_state_json` 中的 Provider 会话引用正确

### 5.2 Task 级断言

1. Claude / Codex 任务都进入 `session_tasks`
2. `provider_type` 正确
3. `provider_task_id` 正确
4. Claude 特殊字段位于 `task_state_json`
5. Codex 特殊字段位于 `task_state_json`

### 5.3 接口级断言

1. `/api/v1/tasks/page` 能同时返回 Claude 和 Codex 历史
2. `/api/v1/tasks/directory/{id}/page` 能同时返回 Claude 和 Codex 历史
3. pin / title / tags / auth 接口不再依赖 Claude 专属表

---

## 6. 脚本实现建议

迁移脚本建议分为三类：

1. `V1__extend_sessions.sql`
2. `V2__create_session_tasks.sql`
3. `V3__backfill_sessions_and_session_tasks.sql`

如果使用 Java/CLI 脚本回填，建议输出三份校验报告：

- session 数量对照
- task 数量对照
- 丢失字段或异常字段报告

---

## 7. 本文档结论

后续迁移脚本必须遵守两个原则：

1. 旧模型中的“公共能力”要进入结构化列，而不是继续散落在 Provider 表。
2. 旧模型中的“Provider 特有状态”要进入 JSON，而不是再生成新的 Provider 专属主表。

