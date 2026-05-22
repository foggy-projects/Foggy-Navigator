# BUG/治理: Root Prompt Account Memory 注入与非标准 Runtime Session 目录

## 状态

- 状态: 目录治理问题已修复，Account Memory / Workspace Resolver 进入设计收口
- 发现日期: 2026-05-22
- 影响范围: `tools/langgraph-biz-worker`
- 关联设计:
  - `09-llm-submission-message-contract.md`
  - `12-agent-frame-and-skill-tool-boundary.md`
  - `13-default-subagent-base-prompt-and-skill-discovery.md`

## 背景

BizWorker 当前已经将真实提交给 LLM 的 body 保存到 `logs/llm-submissions`，用于复盘 Root / Agent frame 的上下文窗口。

在真实会话复盘时发现两个新问题需要单独跟踪:

1. upstream user 工作目录中的记忆文件内容应进入 LLM runtime context，但当前 Root 会话提交中疑似没有看到。
2. `data/runtime/sessions/by-date/...` 下除了标准 `bctx_yyyyMMdd_<hash>_<id>` 目录，还出现了 UUID、`debug_*`、`e2e_*`、`nest*` 等非标准 session 目录。

## 问题 1: Root Prompt 疑似未注入 upstream user 记忆文件

### 期望行为

当请求携带可解析的 upstream user / account 标识时，BizWorker 提交给 Root LLM 的 system prompt 应包含该用户工作目录中的记忆上下文。

第一版按现有代码能力收口为以下文件:

- `ACCOUNT_POLICY.md`
- `AGENT.md`
- `MEMORY.md`

这些内容应作为受治理的 account context 注入 system prompt，而不是混入当前用户 human message。`llm-submissions/*.json` 应能精确看到该 account context block，方便对账。

### 当前代码观察

已有读取与 prompt 构造能力:

- `runtime/account_context_files.py`
  - 定义读取顺序: `ACCOUNT_POLICY.md` -> `AGENT.md` -> `MEMORY.md`
  - 单文件当前限制为 32KB
  - `build_account_context_prompt(...)` 会生成 `## Account Context`
- `runtime/llm_skill_agent.py`
  - 当 `data_root` 与 `account_id` 存在时调用 `build_account_context_prompt(...)`
  - 该 prompt 通过 `build_initial_llm_messages(..., account_context_prompt=...)` 进入 messages
- `runtime/llm_agent_prompts.py`
  - `_build_system_prompt(...)` 会将 `account_context_prompt` 拼入 system prompt

Root 路径的 account id 来源:

- `graphs/root_graph.py::_account_id_from_state(...)`
  - `context.account_id`
  - `context.accountId`
  - `context.upstream_user_id`
  - `context.upstreamUserId`
  - `state.user_id`

注意: 当前实现只会读取 `data_root/accounts/<account-id>/ACCOUNT_POLICY.md`、`AGENT.md`、`MEMORY.md`。如果“upstream user 工作目录”的真实位置不是这个布局，即使请求里带了用户标识，Root prompt 也不会拿到这些记忆文件。

因此需要复盘确认:

1. 上游真实请求是否把 upstream user / account 标识放在上述字段之一。
2. 账号目录是否与 `data_root/accounts/<account-id>/` 下 account context 文件布局一致。
3. Root `conversation.root` 的 `llm-submissions` 是否真的缺少 `## Account Context`。
4. 子 Agent / Agent frame 是否和 Root 使用同一套 account context 注入规则。

### 验收标准

1. 构造带 `upstreamUserId` 的 Root LLM smoke，准备该用户目录下的 `MEMORY.md`，`llm-submissions` 中必须出现 `## Account Context` 与 `MEMORY.md` 内容摘要。
2. 当前用户消息仍保持用户原文为主，最多追加当前时间；不得把 account memory 拼入 human message。
3. 子 Agent frame 默认继承平台治理提示词和必要 handoff，但 account memory 是否注入要显式设计:
   - 若子 Agent 代表同一用户上下文执行任务，允许注入同一 account context。
   - 若后续支持跨账号/跨租户委派，必须先隔离 account context。

## 问题 2: Runtime 下出现非 `bctx_` 标准 session 目录

### 期望行为

真实 OpenAPI 会话路径应统一落在:

```text
data/runtime/sessions/by-date/YYYY/MM/DD/<hash>/bctx_yyyyMMdd_<hash>_<id>/
```

目录内统一包含:

- `session.json`
- `frames/`
- `logs/`
- `reports/`

`contextId` 由 BizWorker 生成或接收合法 `bctx_yyyyMMdd_<hash>_<id>`，后续可直接从 id 推断目录位置。

### 当前观察

2026-05-22 的 runtime 目录中存在非标准 session 目录。按目录名称粗分，当前观察到:

- `uuid-session`: 约 66 个
- `e2e-test`: 约 4 个
- `nest3-test`: 约 6 个
- `debug-manual`: 约 2 个
- `context-isolation-test`: 约 2 个
- `task-test`: 约 1 个

进一步追踪后可确认，这些目录主要由测试触发，但根因是仍存在一条真实代码 fallback 路径:

1. `tests/test_e2e_smoke.py` 明确使用 `e2e_smoke_001` / `e2e_smoke_003` / `e2e_smoke_004` / `e2e_smoke_005`。
2. `tests/test_three_level_nesting.py` 明确使用 `nest3_001` ... `nest3_005` / `nest3_parent_001`。
3. `tests/test_context_isolation.py` 明确使用 `ctx_iso_001` / `ctx_iso_002`。
4. 大量 UUID 目录来自未显式传 `taskId` 的 HTTP 测试或 smoke；`routes/query.py` 会生成 UUID task id。
5. 这些测试走了 `graphs/root_graph.py::route_skill(...)` 的 legacy non-LLM deterministic fallback:
   - 当 `context.order_id` 存在时，fallback 到 `exception_triage`。
   - 创建 frame 时调用 `_runtime.invoke_skill(...)`。
   - 该调用没有传入 `conversation_id`。
6. 因为 frame 上 `conversation_id/session_id` 为空，`FileFrameJournal.save(...)` 只能 fallback 到 `task_id`，最终生成 `<hash>/<taskId>` 目录，而不是 `bctx_...` 标准 session 目录。

示例:

```text
tools/langgraph-biz-worker/data/runtime/sessions/by-date/2026/05/22/ff/8eac779e-12f4-4d26-9bca-f1e94a13598b
```

该目录没有 `session.json`。其 frame 中可见:

- `task_id`: `8eac779e-12f4-4d26-9bca-f1e94a13598b`
- `conversation_id`: `null`
- `session_id`: `null`
- `skill_id`: `rule_check` / `order_evidence_collect` / `address_verify`

### 当前代码来源

`FileFrameJournal.save(...)` 当前逻辑:

```python
conversation_id = _standard_conversation_id(frame.conversation_id)
session_key = conversation_id or session_key_for_frame(frame)
```

`session_key_for_frame(...)` 当前 fallback 顺序:

```python
frame.conversation_id or frame.session_id or frame.task_id or "_no-session"
```

`session_data_dir(...)` 当前逻辑:

```python
if require_standard_context or has_embedded_context:
    use context_segment_path(session_id)
else:
    use hashed_segment_path(session_id)
```

因此，当旧测试、直接 runtime 调用或 legacy frame 没有设置标准 `conversation_id/contextId` 时，journal 会退回到 `task_id`，并写出 `<hash>/<taskId>` 形态的非标准目录。

### 风险

1. 用户从目录结构上无法判断哪些是有效业务会话，哪些是测试/legacy 产物。
2. 标准会话定位能力被稀释，后续排查需要扫描多个目录形态。
3. 如果真实入口漏传/漏生成 contextId，问题不会 fail fast，而是静默写入非标准目录。
4. 单元测试/脚本 smoke 使用真实 `data_root` 时会污染业务 runtime 数据。

### 收口建议

1. OpenAPI / Root conversation 入口必须保证 contextId 标准化:
   - 未传时 BizWorker 生成 `bctx_yyyyMMdd_<hash>_<id>`。
   - 传了非法值时拒绝或标准化为新 contextId，并明确记录 mapping 策略。
2. `FileFrameJournal` 对真实 runtime 模式增加保护:
   - Root/Agent frame 若需要持久化到正式 `data_root`，必须具备标准 conversation id。
   - 直接测试路径应使用 pytest tmp data root，或显式标记为 test mode。
3. `route_skill(...)` 的 legacy deterministic fallback 创建 Agent frame 时也必须传入 `_conversation_id_for_root_frame(...)` 计算出的标准 conversation id，不能只传 `task_id`。
4. 对 legacy fallback 目录制定清理规则:
   - 可以直接删除历史非标准目录，或迁移到 `data/runtime/_legacy-or-test/`。
   - 第一阶段先记录来源和禁止新增真实入口污染。
5. 增加回归测试:
   - API query 无 contextId 时目录必须是 `bctx_...`。
   - API query 有 contextId 时 frame/report/log 全部落同一 `bctx_...` session 目录。
   - 直接 frame journal 测试不得写入项目真实 `data/runtime`。

## 待复盘问题

1. upstream user 记忆文件实际位于哪个目录，是否和 `account_context_files.py` 的读取布局一致。
2. 上游真实请求中的用户标识字段是 `upstreamUserId`、`user_id`，还是其他名字。
3. 非 `bctx_` 目录是否全部来自测试/旧入口，还是仍有真实 API path 会触发 fallback。
4. 是否要在开发态保留一个开关允许 legacy fallback 写盘，还是默认 fail fast。

## 下一步

1. 基于 `14-account-workspace-resolver-and-delegated-mode.md` 实现 Account Workspace Resolver。
2. 增加 Root account memory 注入回归测试。
3. 将 AccountFileTools、SkillRegistry、Artifact store 等固定 `<data_root>/accounts/<accountId>` 的路径来源迁移到 resolver。
4. 补 OpenAPI smoke: delegated workspace 的 `MEMORY.md` 能进入 `llm-submissions` system prompt。

## 执行记录: 2026-05-22

### 非 `bctx_` session 目录修复

已修复 `graphs/root_graph.py::route_skill(...)` 的 legacy deterministic fallback:

- fallback 创建 Agent frame 时会从请求 context 中解析标准 `contextId`。
- `_runtime.invoke_skill(...)` 现在透传 `conversation_id`、`session_id`、`current_task_id`、`origin_task_id`。
- frame journal 因此按标准 `bctx_yyyyMMdd_<hash>_<id>` session 目录写入，不再退回 `<hash>/<taskId>`。

同时收口 standalone `SkillAgent.ask(...)`:

- 请求 context 未携带 `contextId` 时，自动生成标准 `bctx_yyyyMMdd_<hash>_<id>`。
- standalone frame 同样透传 `conversation_id` / `session_id`，避免 SDK/OpenAPI 入口按 `task_id` 写 session 目录。

新增回归测试:

- `tests/test_root_graph.py::test_legacy_fallback_agent_frame_uses_standard_conversation_directory`
- `tests/test_skill_agent_facade.py::test_skill_agent_ask_generates_standard_context_directory_when_missing`

验证证据:

```text
$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests/test_root_graph.py tests/test_skill_agent_facade.py -q
25 passed in 0.74s

.\.venv\Scripts\python.exe -m ruff check src tests/test_root_graph.py tests/test_skill_agent_facade.py
All checks passed!
```

### 非 `bctx_` session 目录二次收口

已进一步收紧所有已知 `by-date` 写入口，避免 frame 之外的调试产物继续用 `taskId`、UUID、`debug_*`、`e2e_*` 等 legacy key 生成非标准 session 目录。

本次代码口径:

- `FileFrameJournal.save(...)` 显式 `conversation_id` 非法时仍 fail fast；缺省时由 BizWorker 生成标准 `bctx_yyyyMMdd_<hash>_<id>`，并把 frame 写入标准 session 目录。
- `llm-submissions`、`runtime-message-events`、`skill-tool-calls` 和 frame report 只接受标准 `bctx_...` contextId；没有标准 contextId 时跳过调试日志写入，不再 fallback 到 `taskId` / `frameId`。
- Root graph 直连调用未提供 `contextId` 时也会生成标准 `bctx_...`，并通过返回的 `context` 写回 graph state。
- `FileFrameJournal.load_by_task(...)` 的无 context 扫描只读取标准 `bctx_...` session 目录，避免旧 legacy 目录继续参与恢复路径。

新增/调整回归测试:

- `tests/test_file_frame_journal.py`：覆盖缺省生成标准 contextId、非法 conversationId 拒绝、cleanup date shard、runtime journal 写入。
- `tests/test_llm_submission_log.py`：覆盖非标准 sessionId 不写 `by-date`。
- `tests/test_llm_message_builder.py`：覆盖 runtime message event 非标准 sessionId 不写 `by-date`。
- `tests/test_frame_execution_report.py`：覆盖 tool audit 非标准 sessionId 不写 `by-date`。
- `tests/test_root_graph.py`：覆盖无 contextId 的 Root 直连调用只创建 `bctx_...` session 目录。

验证证据:

```text
.\.venv\Scripts\python.exe -m ruff check src tests
All checks passed!

.\.venv\Scripts\python.exe -m pytest -q
635 passed, 6 skipped, 11 warnings in 94.48s
```

备注:

- 本次只阻止新写入和新恢复路径继续依赖非标准目录。
- 已存在的旧 `data/runtime/sessions/by-date/.../<non-bctx>` 目录尚未删除，后续可单独按日期 shard 做清理脚本或手工归档。
