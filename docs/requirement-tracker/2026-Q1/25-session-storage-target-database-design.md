# Session 存储统一化重构：目标数据库设计

## 1. 目标模型总览

目标模型固定为三张核心表：

1. `sessions`
2. `session_messages`
3. `session_tasks`

设计原则：

- `sessions` 是聚合根
- `session_tasks` 是统一任务流水
- `session_messages` 保持现有通用模型
- Provider 特有会话状态进入 `sessions.provider_state_json`
- Provider 特有任务状态进入 `session_tasks.task_state_json`

---

## 2. sessions 目标设计

### 2.1 职责

`sessions` 统一承载：

- 会话主身份
- 会话 UI 状态
- 会话级 Auth 绑定
- 最新任务投影
- Provider 级会话状态

### 2.2 建议字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | varchar(64) pk | 平台会话 ID |
| `user_id` | varchar(64) | 用户 ID |
| `tenant_id` | varchar(64) | 租户 ID |
| `agent_id` | varchar(64) | 会话绑定 Agent |
| `provider_type` | varchar(32) | `claude-worker` / `codex-worker` |
| `binding_source` | varchar(32) | 绑定来源 |
| `parent_session_id` | varchar(64) | 父会话 |
| `title` | varchar(256) | 会话显示标题，允许用户覆盖 |
| `summary` | text | AI 摘要 |
| `status` | varchar(32) | `ACTIVE / PAUSED / COMPLETED / DELETED` |
| `interaction_state` | varchar(32) | `PROCESSING / AWAITING_REPLY / ON_HOLD / ARCHIVED` |
| `pinned` | boolean | 是否置顶 |
| `pinned_at` | datetime | 置顶时间 |
| `tags_json` | json | 标签数组 |
| `auth_mode` | varchar(32) | `SUBSCRIPTION / API_KEY / CUSTOM_ENDPOINT / MODEL_CONFIG` |
| `auth_bound_at` | datetime | 绑定时间 |
| `auth_model_config_id` | varchar(64) | 绑定的平台模型配置 |
| `auth_base_url` | varchar(512) | 自定义地址 |
| `auth_token_ciphertext` | text | 加密后的 token |
| `current_worker_id` | varchar(64) | 当前会话默认 Worker |
| `current_directory_id` | varchar(64) | 当前会话默认目录 |
| `latest_task_id` | varchar(64) | 最新任务 ID |
| `latest_model` | varchar(128) | 最新任务模型 |
| `last_activity_at` | datetime | 最后活跃时间 |
| `provider_state_json` | json | Provider 会话级特殊状态 |
| `participating_agent_ids_json` | json | 参与 Agent 列表 |
| `deleted_at` | datetime | 软删除时间 |
| `created_at` | datetime | 创建时间 |
| `updated_at` | datetime | 更新时间 |

### 2.3 Provider State JSON

`provider_state_json` 只保存“会话级”的 Provider 特殊状态。

Claude 示例：

```json
{
  "claudeSessionId": "session-abc",
  "syncSource": "LOCAL",
  "importMetadata": {
    "workerId": "4bd44b86"
  }
}
```

Codex 示例：

```json
{
  "codexThreadId": "thread-xyz"
}
```

### 2.4 sessions 建议 DDL

```sql
create table sessions (
  id varchar(64) primary key,
  user_id varchar(64) not null,
  tenant_id varchar(64) null,
  agent_id varchar(64) null,
  provider_type varchar(32) null,
  binding_source varchar(32) null,
  parent_session_id varchar(64) null,

  title varchar(256) null,
  summary text null,
  status varchar(32) not null,
  interaction_state varchar(32) null,

  pinned boolean not null default false,
  pinned_at datetime null,
  tags_json json null,

  auth_mode varchar(32) null,
  auth_bound_at datetime null,
  auth_model_config_id varchar(64) null,
  auth_base_url varchar(512) null,
  auth_token_ciphertext text null,

  current_worker_id varchar(64) null,
  current_directory_id varchar(64) null,
  latest_task_id varchar(64) null,
  latest_model varchar(128) null,
  last_activity_at datetime null,

  provider_state_json json null,
  participating_agent_ids_json json null,

  deleted_at datetime null,
  created_at datetime not null,
  updated_at datetime not null
);
```

### 2.5 sessions 索引建议

```sql
create index idx_sessions_user_updated
  on sessions(user_id, updated_at desc);

create index idx_sessions_user_interaction_pinned
  on sessions(user_id, interaction_state, pinned, updated_at desc);

create index idx_sessions_user_worker
  on sessions(user_id, current_worker_id, updated_at desc);

create index idx_sessions_user_directory
  on sessions(user_id, current_directory_id, updated_at desc);

create index idx_sessions_latest_task
  on sessions(latest_task_id);
```

### 2.6 可选虚拟列

如果后续需要直接按 JSON 中的 Provider 状态查询，可增加虚拟列：

```sql
alter table sessions
add column claude_session_id_gen varchar(128)
generated always as (
  json_unquote(json_extract(provider_state_json, '$.claudeSessionId'))
) virtual;

create index idx_sessions_claude_session_id
  on sessions(claude_session_id_gen);
```

```sql
alter table sessions
add column codex_thread_id_gen varchar(256)
generated always as (
  json_unquote(json_extract(provider_state_json, '$.codexThreadId'))
) virtual;

create index idx_sessions_codex_thread_id
  on sessions(codex_thread_id_gen);
```

---

## 3. session_messages 目标设计

### 3.1 设计结论

`session_messages` 沿用现有模型，不做结构性调整。

当前字段已经足以支撑：

- 平台消息流
- SessionSummary
- Unified SSE 回放

### 3.2 仅建议补充

如果后续消息展示存在更多需求，可在 `metadata` 中继续扩展，而不是额外拆表。

---

## 4. session_tasks 目标设计

### 4.1 职责

`session_tasks` 是统一任务流水表，承载：

- Claude / Codex / 未来 Provider 的任务执行记录
- 历史列表与目录任务列表的主要数据来源
- 统计、成本、运行状态、错误信息

### 4.2 建议字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | bigint pk | 自增主键 |
| `task_id` | varchar(64) unique | 平台任务 ID |
| `session_id` | varchar(64) | 所属会话 |
| `user_id` | varchar(64) | 用户 ID |
| `tenant_id` | varchar(64) | 租户 ID |
| `agent_id` | varchar(64) | 逻辑 Agent |
| `provider_type` | varchar(32) | `claude-worker` / `codex-worker` |
| `provider_task_id` | varchar(128) | upstream task ID |
| `worker_id` | varchar(64) | Worker ID |
| `directory_id` | varchar(64) | 目录 ID |
| `prompt` | text | 输入提示词 |
| `cwd` | varchar(512) | 工作目录路径 |
| `status` | varchar(32) | 任务状态 |
| `model` | varchar(128) | 模型 |
| `source` | varchar(32) | `PLATFORM / SYNCED` |
| `input_tokens` | bigint | 输入 Token |
| `output_tokens` | bigint | 输出 Token |
| `cost_usd` | decimal(10,6) | 成本 |
| `duration_ms` | bigint | 时长 |
| `num_turns` | int | 轮次 |
| `result_text` | text | 结果 |
| `error_message` | text | 错误 |
| `last_acked_seq` | int | relay ack |
| `last_alive_at` | datetime | 最近存活时间 |
| `task_state_json` | json | 任务级特殊状态 |
| `created_at` | datetime | 创建时间 |
| `updated_at` | datetime | 更新时间 |

### 4.3 task_state_json

Claude 示例：

```json
{
  "claudeSessionId": "session-abc",
  "checkpoints": [
    { "id": "cp-1", "turnIndex": 1, "timestamp": "2026-03-24T22:00:00" }
  ],
  "fileCheckpointingEnabled": true,
  "agentTeamsConfigId": "cfg-1",
  "contextId": "ctx-1",
  "dedupKey": "hash-xxx"
}
```

Codex 示例：

```json
{
  "codexThreadId": "thread-xyz"
}
```

### 4.4 session_tasks 建议 DDL

```sql
create table session_tasks (
  id bigint primary key auto_increment,
  task_id varchar(64) not null unique,
  session_id varchar(64) not null,
  user_id varchar(64) not null,
  tenant_id varchar(64) null,
  agent_id varchar(64) null,
  provider_type varchar(32) not null,
  provider_task_id varchar(128) null,

  worker_id varchar(64) null,
  directory_id varchar(64) null,
  prompt text null,
  cwd varchar(512) null,
  status varchar(32) not null,
  model varchar(128) null,
  source varchar(32) null,

  input_tokens bigint null,
  output_tokens bigint null,
  cost_usd decimal(10,6) null,
  duration_ms bigint null,
  num_turns int null,

  result_text text null,
  error_message text null,
  last_acked_seq int null,
  last_alive_at datetime null,

  task_state_json json null,

  created_at datetime not null,
  updated_at datetime not null
);
```

### 4.5 session_tasks 索引建议

```sql
create index idx_session_tasks_session_created
  on session_tasks(session_id, created_at desc);

create index idx_session_tasks_user_created
  on session_tasks(user_id, created_at desc);

create index idx_session_tasks_user_worker_created
  on session_tasks(user_id, worker_id, created_at desc);

create index idx_session_tasks_user_directory_created
  on session_tasks(user_id, directory_id, created_at desc);

create index idx_session_tasks_user_status_created
  on session_tasks(user_id, status, created_at desc);

create index idx_session_tasks_provider_task
  on session_tasks(provider_type, provider_task_id);
```

### 4.6 可选虚拟列

如果需要直接按 JSON 字段查询：

```sql
alter table session_tasks
add column claude_session_id_gen varchar(128)
generated always as (
  json_unquote(json_extract(task_state_json, '$.claudeSessionId'))
) virtual;

create index idx_session_tasks_claude_session_id
  on session_tasks(claude_session_id_gen);
```

---

## 5. 目标查询路径

### 5.1 历史列表

查询主路径：

- `sessions`
- join/补充 `session_tasks` 中 `latest_task_id`

### 5.2 目录历史

查询主路径：

- `session_tasks` 过滤 `directory_id`
- 聚合到 `session_id`

### 5.3 搜索

搜索主路径：

- `sessions.title`
- `sessions.tags_json`
- `session_tasks.prompt`
- `session_tasks.result_text`

### 5.4 Attention 面板

查询主路径：

- `sessions.interaction_state`
- `sessions.pinned`
- `sessions.last_activity_at`

---

## 6. 目标模型的关键不变量

1. 一个 `session` 只能绑定一个主 `provider_type`。
2. 一个 `session` 下可以有多个 `session_tasks`。
3. `sessions.latest_task_id` 必须指向该会话最新任务。
4. `sessions.last_activity_at` 必须由最新任务或最新消息驱动更新。
5. `provider_state_json` 只能存会话级特殊状态。
6. `task_state_json` 只能存任务级特殊状态。

